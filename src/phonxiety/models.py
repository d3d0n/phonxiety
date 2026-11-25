"""Public call inputs and persisted state vocabulary."""

from enum import StrEnum

from pydantic import BaseModel, ConfigDict, Field


class CallState(StrEnum):
    PREPARING = "preparing"
    DIALING = "dialing"
    ACTIVE = "active"
    ENDING = "ending"
    ENDED = "ended"
    CANCELLED = "cancelled"
    FAILED = "failed"
    UNKNOWN = "unknown"


TERMINAL_STATES = frozenset(
    {CallState.ENDED, CallState.CANCELLED, CallState.FAILED, CallState.UNKNOWN}
)


class CallLimits(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    max_duration_s: int = Field(default=900, ge=10, le=3600)
    ring_timeout_s: int = Field(default=60, ge=5, le=180)


class CallRequest(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    request_id: str = Field(min_length=1, max_length=128, pattern=r"^[A-Za-z0-9_.:-]+$")
    destination: str = Field(pattern=r"^\+[1-9][0-9]{3,14}$")
    goal: str = Field(min_length=1, max_length=8000)
    context: str = Field(default="", max_length=32000)
    constraints: str = Field(default="", max_length=16000)
    limits: CallLimits = Field(default_factory=CallLimits)
    result_fields: list[str] = Field(default_factory=list, max_length=64)
    phone_account: str | None = None
