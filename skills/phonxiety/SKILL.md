---
name: phonxiety
description: Place a real phone call from the user's own SIM through phonxiety and return the outcome, fields and transcript. Use when the user asks to call, phone, ring or dial someone (a business, a hotel, a support line, a friend) or to get something done by phone.
---

# phonxiety: make a phone call

phonxiety is a daemon on this computer that dials from the user's own SIM through their
Android phone. A realtime voice agent talks on the line, a task agent presses keypad
digits and hangs up, and you get the outcome, the requested fields and the transcript.
The `phonxiety` command is on PATH after `mise run skills:install` (it links the repository's
CLI into `~/.local/bin`); inside the repository `uv run phonxiety ...` works too.

## 1. Check the line

```sh
phonxiety status       # daemon, phone, route, "calling ready"
phonxiety link-test    # verdict "good" means call audio will flow
```

If the phone is "not connected" or `link-test` says `poor`, tell the user before dialing:
the daemon would wait up to 90 s for the link and then fail with `phone_link_not_ready`.

## 2. Write the brief

- `--destination`: E.164 (`+998712001515`) or a 3-6 digit service number (`1099`).
  Resolve the number yourself (website, maps) and show the user where it came from.
  Emergency numbers are refused; never try to work around that.
- `--goal`: what to achieve and when the call is done, in one or two sentences.
  Name the language to speak. Say what NOT to do (no booking, no payment).
- `--constraints`: limits the agent must keep: no personal data, card numbers or
  codes; dates or budget; "if a human answers, say this was a test".
- `--result-field`: one per fact you need back (`--result-field price
  --result-field cancellation_policy`). They come back as `fields`.
- `--max-duration-s` (default 900) and `--ring-timeout-s` (default 60).
- `--request-id`: reuse the same id to retry without dialing twice.

Do not ask the voice agent to read results aloud; the fields are filled from the
transcript after the call.

Calls to anyone but the user cost money and reach real people: confirm the number and
the purpose with the user first, unless they already asked for exactly this call.

## 3. Call and wait

```sh
phonxiety call --destination +998712001515 \
  --goal "In English, ask for a room on 28-30 November for one adult; do not book." \
  --constraints "Never give names, card numbers or other personal data." \
  --result-field availability --result-field price_per_night
# -> {"call_id": "...", "state": "preparing", ...}

phonxiety result <call_id> --wait-s 45   # repeat until result_status is "ready"
phonxiety hangup <call_id>               # cancel before the dial, or drop the line
```

`result --wait-s` waits at most 45 s per call; call it again while `state` is
`preparing`, `dialing`, `active` or `ending`. Do not sleep between calls.

## 4. Report

From the final result report: `call_outcome` (answered, no_answer, busy, not_started...),
`task_outcome` (succeeded, declined, incomplete, needs_user, unknown), `summary`, `fields`,
and the quotes from `transcript` that back each field. Say plainly what was not
confirmed. `error: playback_drain_timeout` on an otherwise succeeded call is a known false
alarm.

Every call is archived in `~/.local/share/phonxiety/calls/`: `<call_id>.json` (request,
timings, outcomes, full transcript) and, if answered, `<call_id>.wav` (stereo: other
party left, agent right).

## Voice backends

The daemon talks with one backend for all calls, chosen when it starts:
`PHONXIETY_VOICE_BACKEND=codex` (default, any language), `gemini` (Russian works) or
`deepgram` (English only). Switching means restarting the daemon:
`phonxiety stop && PHONXIETY_VOICE_BACKEND=gemini phonxiety start`.
