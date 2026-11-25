"""Who answered: an automated menu (IVR), a voicemail box, or, by default, a person.

A keyphrase detector over the other party's transcript, the cheap and precise first stage
of far-end detection (Observe.AI's FEND, LiveKit AMD): it fires only on wording that a
person would not say, and "person" stays the default when nothing matches.
"""

from __future__ import annotations

import re

IVR = "ivr"
VOICEMAIL = "voicemail"

# Only wording a person would not use counts. "Please hold" or "оставайтесь на линии" is what a
# receptionist says too, so it proves nothing; a menu is recognised by a key it offers.
_NUMBER = (
    r"(?:\d|ноль|один|два|три|четыре|пять|шесть|семь|восемь|девять|звездочку|решетку"
    r"|zero|one|two|three|four|five|six|seven|eight|nine|star|pound|hash"
    r"|nol|bir|ikki|uch|to'rt|besh|olti|yetti|sakkiz|to'qqiz)"
)
_MENU = re.compile(
    rf"\b(?:нажмите|наберите|press|dial)\s+(?:\S+\s+){{0,2}}?{_NUMBER}\b"  # "нажмите 2", "press the number 1"
    rf"|\b{_NUMBER}\s+(?:raqamini\s+|tugmasini\s+)?(?:bosing|босинг)\b"  # "1 raqamini bosing"
    r"|\bдля выбора\b.*\bнажмите\b|\bfor english\b.*\bpress\b"
)
_MAILBOX = re.compile(
    r"после (?:звукового )?сигнала|абонент не может ответить|signaldan so'ng"
    r"|leave (?:a|your) message after|after the (?:tone|beep)"
)


def classify(text: str) -> str | None:
    """IVR or VOICEMAIL when the wording gives the machine away, otherwise None."""
    normalized = re.sub(r"\s+", " ", text.lower().replace("ё", "е"))
    if _MAILBOX.search(normalized):
        return VOICEMAIL
    if _MENU.search(normalized):
        return IVR
    return None
