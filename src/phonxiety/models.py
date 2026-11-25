"""Public call inputs and persisted state vocabulary."""

from enum import StrEnum

from pydantic import BaseModel, ConfigDict, Field, field_validator

# Never dialled by the agent: Uzbekistan's emergency services (101 fire, 102 police,
# 103 ambulance, 104 gas, 112 unified, 1050 emergencies ministry) and the common
# emergency numbers of other countries, in case the SIM roams.
EMERGENCY_NUMBERS = frozenset({"101", "102", "103", "104", "112", "1050", "911", "999", "000", "110", "118", "119", "120", "122"})


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
    # An E.164 number, or a 3-6 digit service short number such as a provider's call centre.
    destination: str = Field(pattern=r"^(\+[1-9][0-9]{3,14}|[0-9]{3,6})$")
    goal: str = Field(min_length=1, max_length=8000)
    context: str = Field(default="", max_length=32000)
    constraints: str = Field(default="", max_length=16000)
    limits: CallLimits = Field(default_factory=CallLimits)
    result_fields: list[str] = Field(default_factory=list, max_length=64)
    phone_account: str | None = None

    @field_validator("destination")
    @classmethod
    def _not_emergency(cls, destination: str) -> str:
        if destination in EMERGENCY_NUMBERS:
            raise ValueError("emergency numbers cannot be called by the agent")
        return destination
