"""Durable call intents, immutable transcript pages, and independent result state."""

from __future__ import annotations

import json
import sqlite3
import time
import uuid
from pathlib import Path
from typing import Any

from .models import CallRequest, CallState, TERMINAL_STATES


class CallStore:
    def __init__(self, path: Path):
        path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
        self.db = sqlite3.connect(path)
        self.db.row_factory = sqlite3.Row
        self.db.execute("PRAGMA journal_mode=WAL")
        self.db.execute("PRAGMA foreign_keys=ON")
        self.db.execute("PRAGMA busy_timeout=5000")
        self.db.executescript(
            """
            CREATE TABLE IF NOT EXISTS calls (
                call_id TEXT PRIMARY KEY,
                request_id TEXT NOT NULL UNIQUE,
                request_json TEXT NOT NULL,
                state TEXT NOT NULL,
                call_outcome TEXT,
                task_outcome TEXT,
                result_status TEXT NOT NULL DEFAULT 'pending',
                summary TEXT,
                fields_json TEXT NOT NULL DEFAULT '{}',
                error TEXT,
                created_at REAL NOT NULL,
                connected_at REAL,
                ended_at REAL,
                disconnect_cause_json TEXT,
                dial_submitted INTEGER NOT NULL DEFAULT 0,
                transcript_complete INTEGER NOT NULL DEFAULT 0
            );
            CREATE TABLE IF NOT EXISTS transcript (
                sequence INTEGER PRIMARY KEY AUTOINCREMENT,
                call_id TEXT NOT NULL REFERENCES calls(call_id),
                role TEXT NOT NULL,
                text TEXT NOT NULL,
                at REAL NOT NULL
            );
            CREATE INDEX IF NOT EXISTS transcript_call_sequence
                ON transcript(call_id, sequence);
            CREATE TABLE IF NOT EXISTS partials (
                call_id TEXT NOT NULL REFERENCES calls(call_id),
                role TEXT NOT NULL,
                text TEXT NOT NULL,
                at REAL NOT NULL,
                PRIMARY KEY(call_id, role)
            );
            """
        )
        self.db.commit()

    def close(self) -> None:
        self.db.close()

    @staticmethod
    def _canonical(request: CallRequest) -> str:
        return json.dumps(request.model_dump(mode="json"), sort_keys=True, separators=(",", ":"), ensure_ascii=False)

    def reserve(self, request: CallRequest) -> tuple[str, bool]:
        """Check retries before busy state; commit the intent before any external action."""
        payload = self._canonical(request)
        with self.db:
            self.db.execute("BEGIN IMMEDIATE")
            old = self.db.execute("SELECT call_id, request_json FROM calls WHERE request_id=?", (request.request_id,)).fetchone()
            if old is not None:
                if old["request_json"] != payload:
                    raise ValueError("request_conflict: request_id already belongs to a different call request")
                return old["call_id"], False
            busy = self.db.execute(
                "SELECT call_id FROM calls WHERE state IN ('preparing','dialing','active','ending') LIMIT 1"
            ).fetchone()
            if busy is not None:
                raise RuntimeError(f"line_busy: {busy['call_id']}")
            call_id = str(uuid.uuid4())
            self.db.execute(
                "INSERT INTO calls(call_id,request_id,request_json,state,created_at) VALUES(?,?,?,?,?)",
                (call_id, request.request_id, payload, CallState.PREPARING, time.time()),
            )
            return call_id, True

    def row(self, call_id: str) -> sqlite3.Row:
        row = self.db.execute("SELECT * FROM calls WHERE call_id=?", (call_id,)).fetchone()
        if row is None:
            raise LookupError(f"call_not_found: {call_id}")
        return row

    def request(self, call_id: str) -> CallRequest:
        return CallRequest.model_validate_json(self.row(call_id)["request_json"])

    def state(self, call_id: str) -> CallState:
        return CallState(self.row(call_id)["state"])

    def submit_dial(self, call_id: str) -> bool:
        """Atomic cancellation boundary. False means preparing was already cancelled."""
        with self.db:
            cursor = self.db.execute(
                "UPDATE calls SET state='dialing', dial_submitted=1 WHERE call_id=? AND state='preparing'",
                (call_id,),
            )
        return cursor.rowcount == 1

    def set_active(self, call_id: str) -> bool:
        with self.db:
            cursor = self.db.execute(
                "UPDATE calls SET state='active', call_outcome='answered', connected_at=COALESCE(connected_at,?) "
                "WHERE call_id=? AND state='dialing'",
                (time.time(), call_id),
            )
        return cursor.rowcount == 1

    def begin_ending(self, call_id: str) -> bool:
        with self.db:
            cursor = self.db.execute(
                "UPDATE calls SET state='ending' WHERE call_id=? AND state IN ('dialing','active')",
                (call_id,),
            )
        return cursor.rowcount == 1

    def resume_active(self, call_id: str) -> bool:
        with self.db:
            cursor = self.db.execute(
                "UPDATE calls SET state='active' WHERE call_id=? AND state='ending' AND connected_at IS NOT NULL",
                (call_id,),
            )
        return cursor.rowcount == 1

    def cancel_preparing(self, call_id: str) -> bool:
        with self.db:
            cursor = self.db.execute(
                "UPDATE calls SET state='cancelled', call_outcome='not_started', task_outcome='incomplete', "
                "ended_at=?, result_status='finalizing' WHERE call_id=? AND state='preparing' AND dial_submitted=0",
                (time.time(), call_id),
            )
        return cursor.rowcount == 1

    def terminate(
        self,
        call_id: str,
        state: CallState,
        outcome: str | None,
        *,
        error: str | None = None,
        cause: dict[str, Any] | None = None,
    ) -> bool:
        if state not in TERMINAL_STATES:
            raise ValueError("terminate requires a terminal call state")
        with self.db:
            cursor = self.db.execute(
                "UPDATE calls SET state=?,call_outcome=?,error=COALESCE(?,error),ended_at=COALESCE(ended_at,?),"
                "disconnect_cause_json=COALESCE(?,disconnect_cause_json),result_status='finalizing' "
                "WHERE call_id=? AND state NOT IN ('ended','cancelled','failed','unknown')",
                (state, outcome, error, time.time(), json.dumps(cause) if cause is not None else None, call_id),
            )
        return cursor.rowcount == 1

    def reconcile_ended(self, call_id: str, cause: dict[str, Any] | None = None) -> None:
        """A late phone acknowledgement can resolve an earlier uncertain disconnect."""
        with self.db:
            self.db.execute(
                "UPDATE calls SET state='ended', call_outcome=CASE WHEN connected_at IS NULL THEN 'disconnected' ELSE 'answered' END, "
                "disconnect_cause_json=COALESCE(?,disconnect_cause_json), ended_at=COALESCE(ended_at,?) "
                "WHERE call_id=? AND state='unknown'",
                (json.dumps(cause) if cause is not None else None, time.time(), call_id),
            )

    def append_transcript(self, call_id: str, role: str, text: str, final: bool) -> None:
        if role not in ("user", "assistant"):
            raise ValueError("Unknown transcript role")
        text = text.strip()
        with self.db:
            if final:
                if text:
                    self.db.execute(
                        "INSERT INTO transcript(call_id,role,text,at) VALUES(?,?,?,?)",
                        (call_id, role, text, time.time()),
                    )
                self.db.execute("DELETE FROM partials WHERE call_id=? AND role=?", (call_id, role))
            elif text:
                self.db.execute(
                    "INSERT INTO partials(call_id,role,text,at) VALUES(?,?,?,?) "
                    "ON CONFLICT(call_id,role) DO UPDATE SET text=excluded.text,at=excluded.at",
                    (call_id, role, text, time.time()),
                )

    def transcript(self, call_id: str) -> list[dict[str, Any]]:
        return [dict(row) for row in self.db.execute(
            "SELECT sequence,role,text,at FROM transcript WHERE call_id=? ORDER BY sequence", (call_id,)
        )]

    def partials(self, call_id: str) -> list[dict[str, Any]]:
        return [dict(row) for row in self.db.execute(
            "SELECT role,text,at FROM partials WHERE call_id=? ORDER BY at", (call_id,)
        )]

    def finish_result(
        self,
        call_id: str,
        *,
        task_outcome: str,
        summary: str | None,
        fields: dict[str, Any],
        transcript_complete: bool,
        error: str | None = None,
    ) -> None:
        allowed = {"succeeded", "declined", "incomplete", "needs_user", "unknown"}
        if task_outcome not in allowed:
            raise ValueError("Invalid task outcome")
        with self.db:
            self.db.execute(
                "UPDATE calls SET task_outcome=?,summary=?,fields_json=?,transcript_complete=?,"
                "error=COALESCE(?,error),result_status='ready' WHERE call_id=?",
                (task_outcome, summary, json.dumps(fields, ensure_ascii=False), int(transcript_complete), error, call_id),
            )

    def unfinished(self) -> list[dict[str, Any]]:
        return [dict(row) for row in self.db.execute(
            "SELECT * FROM calls WHERE state IN ('preparing','dialing','active','ending') OR result_status != 'ready'"
        )]

    def snapshot(self, call_id: str, cursor: int = 0, limit: int = 100) -> dict[str, Any]:
        if cursor < 0 or not 1 <= limit <= 100:
            raise ValueError("Invalid transcript pagination")
        row = self.row(call_id)
        request = json.loads(row["request_json"])
        page = [dict(item) for item in self.db.execute(
            "SELECT sequence,role,text,at FROM transcript WHERE call_id=? AND sequence>? ORDER BY sequence LIMIT ?",
            (call_id, cursor, limit + 1),
        )]
        has_more = len(page) > limit
        page = page[:limit]
        return {
            "call_id": call_id,
            "request_id": row["request_id"],
            "destination": request["destination"],
            "state": row["state"],
            "call_outcome": row["call_outcome"],
            "task_outcome": row["task_outcome"],
            "result_status": row["result_status"],
            "summary": row["summary"],
            "fields": json.loads(row["fields_json"]),
            "error": row["error"],
            "created_at": row["created_at"],
            "connected_at": row["connected_at"],
            "ended_at": row["ended_at"],
            "disconnect_cause": json.loads(row["disconnect_cause_json"]) if row["disconnect_cause_json"] else None,
            "transcript": page,
            "current_partials": self.partials(call_id),
            "next_cursor": page[-1]["sequence"] if page else cursor,
            "has_more": has_more,
            "transcript_complete": bool(row["transcript_complete"]),
        }
