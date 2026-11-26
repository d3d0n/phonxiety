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


class TestToneOptions(BaseModel):
    """The tone a Lab test call plays into the line, with its own stop time."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    hz: int = Field(default=1000, ge=100, le=4000)
    dbfs: float = Field(default=-20, ge=-60, le=0)
    # The daemon stops the tone itself after this many seconds, so the call can hold for it.
    seconds: float = Field(ge=1, le=10)


class TestAudioOptions(BaseModel):
    """Optional automatic probes for a Lab test call, started with the audio."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    loopback: bool = False
    tone: TestToneOptions | None = None

    @field_validator("loopback", mode="before")
    @classmethod
    def _strict_bool(cls, value: object) -> bool:
        if not isinstance(value, bool):
            raise ValueError("loopback must be true or false")
        return value


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
