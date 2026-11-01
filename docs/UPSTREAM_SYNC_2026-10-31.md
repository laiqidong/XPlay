# Selective DiPlay sync — 2026-10-31

Source: shihabal3amri/DiPlay, main 615b32ce (0.2.22).
Applied production-source patches, preserving existing XPlay and P7 changes:

- 6e9c530b, 0d73aed3, 7f40cfab, 193e3fdd: music clock averaging, stream target measurement and queued audio arrival timing.
- 8d5fd4b2: MTU-aware default PCM microphone packet duration.
- 48c97171: stable multi-touch slots and release positions.
- ad03bf44, 47b39a5c: coalesce pending moves while preserving presses/releases and executor rejection handling.
- e0ccc4de, c69f0165: asynchronous session log writing, early rejected-line filtering and overflow reporting.

Deferred 50957d41 (lazy TRACE formatting): controller dependency conflict; no part applied.
Connection/wake, settings, vehicle-specific additions and updater changes are outside this batch.
Upstream tests were not imported or run. Validate with :mobile:assembleDebug only;
USB recording, wireless audio and touch require vehicle acceptance.
