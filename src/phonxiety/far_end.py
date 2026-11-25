"""Who answered: an automated menu (IVR), a voicemail box, or, by default, a person.

A keyphrase detector over the other party's transcript, the cheap and precise first stage
of far-end detection (Observe.AI's FEND, LiveKit AMD): it fires only on wording that a
person would not say, and "person" stays the default when nothing matches.
"""

from __future__ import annotations

import re

IVR = "ivr"
VOICEMAIL = "voicemail"

# Russian, Uzbek (Latin and Cyrillic) and English menu and mailbox wording.
_PHRASES: tuple[tuple[str, tuple[str, ...]], ...] = (
    (VOICEMAIL, (
        "после сигнала", "оставьте сообщение", "оставьте ваше сообщение", "голосовой почт",
        "абонент не может ответить", "signaldan so'ng", "xabar qoldiring",
        "leave a message", "leave your message", "after the tone", "after the beep", "voicemail",
    )),
    (IVR, (
        "нажмите", "для выбора", "наберите добавочный", "оставайтесь на линии",
        "ваш звонок очень важен", "bosing", "tanlash uchun", "босинг",
        "press ", "for english", "please hold", "your call is important", "dial the extension",
    )),
)


def classify(text: str) -> str | None:
    """IVR or VOICEMAIL when the wording gives the machine away, otherwise None."""
    normalized = re.sub(r"\s+", " ", text.lower().replace("ё", "е"))
    for label, phrases in _PHRASES:
        if any(phrase in normalized for phrase in phrases):
            return label
    return None
