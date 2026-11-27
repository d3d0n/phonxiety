# Unreleased

The phone side of Phonxiety: the app and the root module that let the daemon on your computer place
and run cellular calls through this phone.

## A new look

- **Phonxiety brand:**
  - deep violet surfaces;
  - Chakra Petch headings and JetBrains Mono text;
  - cut-corner cards;
  - the arrow mark in the header and on the welcome screen;
  - a branded splash;
  - a new launcher icon with a themed monochrome layer;
  - the arrow on notifications and quick-settings tiles.
- Dynamic wallpaper colours are off by default, so the brand stays consistent.
- Captions look uppercase, while screen readers read them in their normal case.
- The interface is English throughout.

## Getting around

- **Each tab keeps its own history:** switching tabs and coming back returns to the same screen. Tapping the active tab again returns to its first screen, and then scrolls back to the top.
- **A thin strip above the tabs** says when the computer link is down, reconnecting or relayed. It hides itself once the link is direct and healthy.
- The keyboard never covers a field or a message, and back gestures preview where they lead.
- The keyboard's action key does what it says: Search in the calls list, then the keyboard closes; Next through the settings and Done on the last field.
- Screen readers announce each call in the list as a button that opens it.

## Pairing you can trust

- **The pairing code carries the computer's LAN and Tailscale addresses**, so the phone finds the daemon on every network without opening Settings.
- **Re-pairing to another computer never carries over** the previous computer's token or addresses.
- `phonxiety pair` refuses a code too large to scan.

## Calls

- After you answer over the system call screen, the live call screen comes back on top, once per call. It never covers another call.
- Hanging up from the notification or the tile asks the computer first, when the link is up, and otherwise ends Phonxiety's own call on the phone at once. Another call on the phone, incoming or on hold, is never touched, and a call still being placed is cancelled as soon as the phone sees it.

## Safer actions

- Unpairing, installing an update, deleting a call, a doctor repair and the lab's test call each ask first. A second tap never repeats them, and the question survives rotating the phone.
- "Install again" after a failed update works, and an update offer that disappears no longer reopens its question by itself.

## Reliability

- **During a reconnect**, a request waits for the link within its deadline instead of failing at once.
- A read that meets a brief daemon or network hiccup is tried again, at most twice and within the same 30-second limit, so a passing glitch no longer shows an error. Changes are never repeated, and a retry never reaches a computer paired after the read began.
- A link whose control messages back up is replaced instead of silently dropping them.
- When the link to the computer drops, the dashboard and the Doctor say why in a plain sentence, next to the reason's code.

## Under the hood

- Named background scopes that report crashes.
- Shared build conventions.
- Logs that build their messages only when they are kept.
- A smaller app: fewer null checks compiled in.
