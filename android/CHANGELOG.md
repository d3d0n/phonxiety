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
- **Pairing from the computer** (`mise run phone:pair`) stops the whole app and confirms it is gone before it replaces the configuration. It puts the old setup back only when the phone itself reports that it stopped before replacing anything; otherwise it leaves the app stopped and says what it knows. Its exit codes are documented at the top of the script.

## Calls

- After you answer over the system call screen, the live call screen comes back on top, once per call. It never covers another call.
- Hanging up from the notification or the tile asks the computer first, when the link is up, and otherwise ends Phonxiety's own call on the phone at once. Another call on the phone, incoming or on hold, is never touched, and a call still being placed is cancelled as soon as the phone sees it.
- **The live call screen offers only the controls the computer reports.** Until the computer answers, they wait instead of guessing.
- Hanging up from the live call screen goes through the computer. If it cannot end the call, the screen says "The call is still up" and offers the phone's own call screen, or another try. The message goes away once the call ends.
- The call's state and duration change with a short animation, or at once when animations are off. Transcript lines keep their place as new ones arrive.
- Writing a new call starts in the number field, and the keyboard's action key moves through the fields.
- A call the computer no longer has keeps the copy saved on this phone, with a note saying so. Opening a call asks the computer once instead of three times.

## Safer actions

- Unpairing, installing an update, deleting a call, hanging up from the live call screen, a doctor repair and the lab's test call each ask first. A second tap never repeats them, and the question survives rotating the phone.
- "Install again" after a failed update works, and an update offer that disappears no longer reopens its question by itself.
- **Error messages offer the step that can help.** A dropped link or a timeout offers Retry, a failure on the computer offers the Doctor, and a request the computer refused explains why without a Retry that would only be refused again.
- Retry repeats exactly what failed: a failed save sends your edits again, a failed new call opens its review instead of dialling at once, a missing update document offers Check again, and the Lab repeats its failed audio action on the same call with the same tone.

## Reliability

- **During a reconnect**, a request waits for the link within its deadline instead of failing at once.
- A read that meets a brief daemon or network hiccup is tried again, at most twice and within the same 30-second limit, so a passing glitch no longer shows an error. Changes are never repeated, and a retry never reaches a computer paired after the read began.
- A link whose control messages back up is replaced instead of silently dropping them.
- When the link to the computer drops, the dashboard and the Doctor say why in a plain sentence, next to the reason's code.

## Diagnostics

- **Message sizes** in the Lab records the size and kind of every message the bridge sends and receives, in a bounded file. Message contents are never written. It stays off until you turn it on.

## Under the hood

- Named background scopes that report crashes.
- Shared build conventions.
- Logs that build their messages only when they are kept.
- A smaller app: fewer null checks compiled in.
- The call cache keeps the list apart from each call's record, indexes what the list filters and sorts by, and writes a record together with its transcript.
- Every screen showing a call reads one shared request stream from the computer, and a call nobody watches is let go after a while.
