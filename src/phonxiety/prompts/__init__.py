"""Every text a model reads lives here as a Jinja template, not inline in code.

Templates are plain files next to this module, reviewed and versioned with the code that
fills them. Undefined variables raise, so a renamed field cannot silently blank a prompt.
"""

from __future__ import annotations

from functools import lru_cache
from pathlib import Path
from typing import Any

from jinja2 import Environment, FileSystemLoader, StrictUndefined


@lru_cache(maxsize=1)
def _environment() -> Environment:
    return Environment(
        loader=FileSystemLoader(Path(__file__).parent),
        undefined=StrictUndefined,
        autoescape=False,
        trim_blocks=True,
        lstrip_blocks=True,
        keep_trailing_newline=False,
    )


def render(name: str, **context: Any) -> str:
    """Render `prompts/<name>.j2` with `context`."""
    return _environment().get_template(f"{name}.j2").render(**context).strip()
