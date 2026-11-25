"""Task-agent model resolution and Codex event handling (no network)."""

from __future__ import annotations

import asyncio
import json
from pathlib import Path

import pytest

from phonxiety.task_agent import (
    TaskAgent,
    TaskModelConfigError,
    TaskResultError,
    TaskToolError,
    _ResponseAccumulator,
    _StreamResult,
    _ToolCall,
    _iter_sse,
    _tool_schemas,
    resolve_task_model,
)


async def _lines(chunks: list[str]):
    for line in chunks:
        yield line


def test_resolve_task_model_precedence(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setenv("PHONXIETY_TASK_MODEL", "env-model")
    monkeypatch.setenv("CODEX_HOME", str(tmp_path))
    assert resolve_task_model("explicit-model") == "explicit-model"
    assert resolve_task_model(None) == "env-model"

    monkeypatch.delenv("PHONXIETY_TASK_MODEL")
    (tmp_path / "config.toml").write_text('model = "config-model"\n', encoding="utf-8")
    assert resolve_task_model(None) == "config-model"


def test_missing_task_model_is_explicit(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.delenv("PHONXIETY_TASK_MODEL", raising=False)
    monkeypatch.setenv("CODEX_HOME", str(tmp_path))
    with pytest.raises(TaskModelConfigError):
        resolve_task_model(None)


def test_task_agent_construction_is_lazy_but_run_fails_clearly(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.delenv("PHONXIETY_TASK_MODEL", raising=False)
    monkeypatch.setenv("CODEX_HOME", str(tmp_path))
    agent = TaskAgent(auth=object(), model=None)  # type: ignore[arg-type]
    assert agent.configured_model is None
    with pytest.raises(TaskModelConfigError):
        asyncio.run(agent.run({}, [], "hello", {}))


def test_unsupported_tool_is_rejected() -> None:
    with pytest.raises(TaskToolError):
        _tool_schemas({"open_gate": lambda args: None})  # type: ignore[arg-type]


def test_schemas_are_flat_responses_function_tools() -> None:
    schemas = _tool_schemas(
        {"send_dtmf": lambda args: None, "finish_call": lambda args: None}  # type: ignore[arg-type]
    )
    assert [schema["name"] for schema in schemas] == ["send_dtmf", "finish_call"]
    assert all(schema["type"] == "function" for schema in schemas)
    assert schemas[0]["parameters"]["required"] == ["digits"]
    assert "outcome" in schemas[1]["parameters"]["required"]


def test_accumulator_streams_text_and_function_call() -> None:
    accumulator = _ResponseAccumulator()
    accumulator.feed("response.output_text.delta", {"delta": "Об"})
    accumulator.feed("response.output_text.delta", {"delta": "работка"})
    accumulator.feed(
        "response.output_item.added",
        {
            "item": {
                "type": "function_call",
                "id": "fc_1",
                "call_id": "call_1",
                "name": "send_dtmf",
                "arguments": "",
                "output_index": 1,
            }
        },
    )
    accumulator.feed(
        "response.function_call_arguments.delta",
        {"item_id": "fc_1", "delta": '{"digits":"1'},
    )
    accumulator.feed(
        "response.function_call_arguments.delta",
        {"item_id": "fc_1", "delta": '23"}'},
    )
    accumulator.feed(
        "response.completed",
        {"response": {"status": "completed", "output": []}},
    )
    assert accumulator.finished
    result = accumulator.result()
    assert result.text == "Обработка"
    assert len(result.calls) == 1
    assert result.calls[0].name == "send_dtmf"
    assert json.loads(result.calls[0].arguments) == {"digits": "123"}


def test_accumulator_uses_completed_output_items() -> None:
    accumulator = _ResponseAccumulator()
    accumulator.feed(
        "response.completed",
        {
            "response": {
                "status": "completed",
                "output": [
                    {
                        "type": "function_call",
                        "call_id": "call_2",
                        "name": "finish_call",
                        "arguments": '{"outcome":"succeeded"}',
                    },
                    {
                        "type": "message",
                        "role": "assistant",
                        "content": [{"type": "output_text", "text": "Готово"}],
                    },
                ],
            }
        },
    )
    result = accumulator.result()
    assert result.text == "Готово"
    assert result.calls[0].call_id == "call_2"


def test_accumulator_failed_response_raises() -> None:
    accumulator = _ResponseAccumulator()
    with pytest.raises(Exception):
        accumulator.feed(
            "response.failed",
            {"response": {"status": "failed", "error": {"message": "bad"}}},
        )


def test_sse_parser_joins_multiline_data() -> None:
    async def run() -> list[tuple[str | None, str]]:
        stream = _lines(
            [
                "event: response.output_text.delta",
                'data: {"type":"response.output_text.delta",',
                'data: "delta":"x"}',
                "",
                ": keepalive",
                'data: {"type":"response.completed"}',
                "",
            ]
        )
        return [item async for item in _iter_sse(stream)]

    events = asyncio.run(run())
    assert events[0][0] == "response.output_text.delta"
    assert json.loads(events[0][1])["delta"] == "x"
    assert json.loads(events[1][1])["type"] == "response.completed"


def test_run_executes_tool_then_returns_final_text() -> None:
    seen: list[dict] = []

    async def send_dtmf(args: dict) -> dict:
        seen.append(args)
        return {"ok": True, "pressed": args["digits"]}

    agent = TaskAgent(auth=object(), model="test-model")  # type: ignore[arg-type]
    rounds = [
        _StreamResult(
            text="",
            calls=[_ToolCall("call_1", "send_dtmf", '{"digits":"12#"}')],
        ),
        _StreamResult(text="Готово, соединение установлено."),
    ]

    async def fake_stream(body, *, retry_auth=True):  # type: ignore[no-untyped-def]
        tool_round = rounds.pop(0)
        assert body["model"] == "test-model"
        assert body["tools"][0]["name"] == "send_dtmf"
        return tool_round

    agent._stream_turn = fake_stream  # type: ignore[method-assign]
    result = asyncio.run(
        agent.run(
            {"goal": "test"},
            [{"role": "user", "text": "hello"}],
            "press 12#",
            {"send_dtmf": send_dtmf},
        )
    )
    assert result == "Готово, соединение установлено."
    assert seen == [{"digits": "12#"}]


def test_run_rejects_empty_final_text() -> None:
    agent = TaskAgent(auth=object(), model="test-model")  # type: ignore[arg-type]

    async def fake_stream(body, *, retry_auth=True):  # type: ignore[no-untyped-def]
        return _StreamResult(text="   ")

    agent._stream_turn = fake_stream  # type: ignore[method-assign]
    with pytest.raises(Exception):
        asyncio.run(agent.run({}, [], "do nothing", {}))


def test_summarize_validates_and_filters_fields() -> None:
    agent = TaskAgent(auth=object(), model="test-model")  # type: ignore[arg-type]
    summary = {
        "task_outcome": "succeeded",
        "summary": "Order confirmed.",
        "fields": {"order": "A-1", "extra": "drop"},
    }

    async def fake_stream(body, *, retry_auth=True):  # type: ignore[no-untyped-def]
        return _StreamResult(
            text="",
            calls=[
                _ToolCall(
                    "call_summary", "submit_summary", json.dumps(summary, ensure_ascii=False)
                )
            ],
        )

    agent._stream_turn = fake_stream  # type: ignore[method-assign]
    result = asyncio.run(
        agent.summarize({"result_fields": ["order"]}, [{"role": "user", "text": "hi"}])
    )
    assert result == {
        "task_outcome": "succeeded",
        "summary": "Order confirmed.",
        "fields": {"order": "A-1"},
    }


def test_close_marks_agent_closed() -> None:
    agent = TaskAgent(auth=object(), model="test-model")  # type: ignore[arg-type]
    asyncio.run(agent.close())
    with pytest.raises(Exception):
        asyncio.run(agent.run({}, [], "hello", {}))


def test_summarize_rejects_missing_tool_call() -> None:
    agent = TaskAgent(auth=object(), model="test-model")  # type: ignore[arg-type]

    async def fake_stream(body, *, retry_auth=True):  # type: ignore[no-untyped-def]
        return _StreamResult(text="All good")

    agent._stream_turn = fake_stream  # type: ignore[method-assign]
    with pytest.raises(TaskResultError):
        asyncio.run(agent.summarize({}, []))
