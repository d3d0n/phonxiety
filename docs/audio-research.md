# Audio handling: what we wrote ourselves and what a library already does

phonxiety moves 48 kHz mono s16le speech between the phone link, the voice backends and the
call archive. This note lists every piece of audio code we wrote by hand, the libraries that
cover the same job, and what we chose. Audio quality (levels, gain control, loudness of the
far end) is out of scope here; it comes as a separate stage together with the network module.

The project already ships [PyAV](https://pyav.basswood-io.com/docs/stable/) (pulled in by
aiortc), whose wheels bundle FFmpeg: libopus, libswresample, libavfilter and the AAC encoder.
It is the first candidate everywhere because it adds no dependency.

| # | Component | Where it was | Candidates | Decision |
|---|---|---|---|---|
| 1 | Opus encode/decode, 20 ms frames | `codec.py` `OpusEncoder` / `OpusDecoder` | PyAV `CodecContext("libopus")`; [opuslib](https://github.com/orion-labs/opuslib) | **Adopt PyAV** (already used). The hand-kept byte remainder for whole 20 ms frames is replaced by [`av.AudioFifo`](https://pyav.basswood-io.com/docs/stable/api/audio.html#av.audio.fifo.AudioFifo), which hands out exact 960-sample frames with correct timestamps. opuslib needs a system libopus and adds nothing. |
| 2 | Resampling 16/24 kHz <-> 48 kHz | `realtime.py` `Resampler` | PyAV [`AudioResampler`](https://pyav.basswood-io.com/docs/stable/api/audio.html#av.audio.resampler.AudioResampler) (libswresample); [python-soxr](https://github.com/dofuuz/python-soxr) | **Adopt PyAV**. soxr is marginally better on paper for music, but needs numpy and a second resampler; speech at 16/24 kHz is well served by swresample, already used by aiortc. |
| 3 | Downmix of the Codex WebRTC track to mono | `voice.py` `downmix_frame` (manual sample loop) | PyAV `AudioResampler(layout="mono")` | **Adopt PyAV**. Checked: swresample's stereo-to-mono matrix gives the mean `(L+R)/2`, the same level as the hand loop; it rounds where the loop truncated, a difference of at most one sample step. The per-sample Python loop goes away, and a layout change mid-stream starts a fresh resampler. |
| 4 | Paced output of bursty TTS at playback speed, flush on barge-in | `realtime.py` `PacedOutput` | [Pipecat output transport](https://docs.pipecat.ai/server/services/transport/transport-params) (audio out chunking + interruption); [LiveKit `rtc.AudioSource`](https://docs.livekit.io/reference/python/livekit/rtc/audio_source.html) (`capture_frame` with an internal queue and `clear_queue`) | **Keep own code, buffer through `av.AudioFifo`.** Both libraries pace inside their own transport (a Pipecat pipeline, a LiveKit room); neither paces into an arbitrary async callback such as our phone link, and adopting one means adopting its whole runtime. The pacing loop stays a few lines; the byte buffer becomes an `AudioFifo`. |
| 5 | Paced PCM source for the Codex WebRTC peer | `voice.py` `PcmSourceTrack` | aiortc [`AudioStreamTrack`](https://aiortc.readthedocs.io/en/latest/api.html#aiortc.AudioStreamTrack); `aiortc.contrib.media.MediaPlayer` | **Keep, it already is the aiortc extension point.** `MediaPlayer` plays files or devices, not a live push source. Moves into the audio package unchanged. |
| 6 | Stereo call recording: two sides on one clock, then interleave | `recording.py` `CallRecorder` (sparse raw files + Python `array` interleave + `wave`) | PyAV: planar stereo frames packed by swresample into a `pcm_s16le` WAV muxer, or an FFmpeg [`join`](https://ffmpeg.org/ffmpeg-filters.html#join) filter graph; [soundfile](https://github.com/bastibe/python-soundfile) | **Adopt PyAV for the export**: each second of both sides becomes one planar (`s16p`) stereo frame, swresample packs it and PyAV muxes the WAV, replacing the hand interleave and the `wave` writer. The `join` graph does the same with more wiring. Placing each side on the call clock stays ours (sparse writes), since no library knows our clock; soundfile would add numpy and libsndfile for the same job. |
| 7 | Shareable compressed copy | `recording.py` `compress` (spawns the `ffmpeg` CLI) | PyAV filter graph (`pan`, `silenceremove`, `loudnorm`) + AAC encoder + MP4 muxer; [pydub](https://github.com/jiaaro/pydub) | **Adopt PyAV**. Same filters in-process, no external `ffmpeg` binary on PATH, no subprocess. pydub only wraps the CLI. |
| 8 | Phone wire framing (`PX01`/`PX02`/`PXLT` + epoch + sample index) | `device.py` `AUDIO_HEADER` | — | **Keep own**: it is our protocol with the Android app, not a generic format. |

## Layout

Everything audio lives in `src/phonxiety/audio/`:

- `format.py`: the one place for 48 kHz / 20 ms / s16 mono constants.
- `opus.py`: Opus encoder and decoder (1).
- `convert.py`: resampling and downmix (2, 3).
- `pacing.py`: paced output for websocket backends (4).
- `webrtc.py`: the aiortc source track for Codex (5).
- `recording.py`: call recording and its WAV export (6).
- `share.py`: the shareable compressed copy (7).

The call record JSON (`write_call_record`) is not audio and moves to `phonxiety/archive.py`.
