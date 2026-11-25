"""The JSON record kept for every call in the archive directory."""

from __future__ import annotations

import json
import os
import tempfile
from pathlib import Path
from typing import Any


def write_call_record(directory: Path, record: dict[str, Any]) -> Path:
    """Atomically write `<call_id>.json` with owner-only access."""
    directory.mkdir(parents=True, exist_ok=True, mode=0o700)
    path = directory / f"{record['call_id']}.json"
    fd, temporary = tempfile.mkstemp(prefix=".call-", suffix=".json", dir=directory)
    try:
        with os.fdopen(fd, "w") as stream:
            json.dump(record, stream, ensure_ascii=False, indent=2)
        os.replace(temporary, path)
    except BaseException:
        Path(temporary).unlink(missing_ok=True)
        raise
    return path
