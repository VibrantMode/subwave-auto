# SUB/WAVE Auto — Android Auto acceptance testing

Acceptance matrix from `ANDROID_AUTO_PLAN.md` §2 WP6. P0 = rows 1–6 (all must
pass before the app is considered done for v1).

Rig shorthand: **DHU** = S24 Ultra (SM-S928U) over USB + Desktop Head Unit on
the Windows workstation (setup + gotchas: [DHU_NOTES.md](DHU_NOTES.md)).

## Results

| # | Scenario | Pass criteria | Result | Date / Rig | Notes |
|---|----------|---------------|--------|------------|-------|
| 1 | App visible in AA launcher (unknown sources ON) | icon + label present | **PASS** | 2026-07-02 / DHU, release-signed `efbdf2a` | Discovery via the legacy intent filter works for a sideloaded media3 service — the plan's headline risk. Legacy `MediaBrowserServiceCompat` fallback not needed. |
| 2 | Browse: root → live item | one playable "SUB/WAVE" entry | **PASS** | 2026-07-02 / DHU, `efbdf2a` | |
| 3 | Tap to play | audio < 5 s, AA shows playing state | **PASS** | 2026-07-02 / DHU, `efbdf2a` | |
| 4 | Song transition | AA title/artist/cover update ≤ 10 s, **zero audio gap** | **PASS** | 2026-07-02 / DHU, `efbdf2a` | Initial run **FAILED** (frozen metadata): payload-parsing defect — the API's nested `nowPlaying` object and `/api`-prefixed cover path weren't handled. Fixed + regression-tested; re-run passed. Artist line carries station branding ("&lt;artist&gt; • Power Pop Palace"). |
| 5 | Pause 60 s → Play | rejoins LIVE edge (compare against web player) | **PASS** | 2026-07-02 / DHU + adb, `efbdf2a` | Paused 65 s via `adb shell media dispatch pause`, played: audio matched the live web player (radio.powerpoppalace.com side-by-side), not the stale buffer — the >30 s staleness reload fired as designed. |
| 6 | No skip/seek | AA shows play/pause only | **PASS** | 2026-07-02 / DHU, `efbdf2a` | Seek/skip commands stripped by the ForwardingPlayer; transport renders play/pause only. |
| 7 | Phone call interrupt | duck/pause + auto-resume (audio focus) | **PASS** | 2026-07-02 / DHU + real call, `efbdf2a` | Recorded session-state timeline: PLAYING → PAUSED 19:41:10 (incoming call answered) → PLAYING 19:41:27 (auto-resume on hang-up, no user input). The ~17 s pause stayed under the 30 s threshold → resumed WITHOUT a needless stream reload — the exact transient-focus interaction plan §2 WP2 step 3 required verifying. |
| 8 | BT-only car (no AA) | title/artist on car display via AVRCP | **PASS** | 2026-07-02 / operator's car, BT audio (no AA projection), `efbdf2a` | Title/artist rendered on the car display over plain Bluetooth — the AVRCP metadata gap the RN app never closed. |
| 9 | Station offline mid-play | error state or silent retry; no crash; recovers when back | **PASS** | 2026-07-02 / DHU + adb, `efbdf2a` | Simulated by killing phone networking (svc wifi+data disable) for ~25 s mid-play: audio stopped, app process survived (no crash), session stayed registered; on restore + play, playback recovered AT THE LIVE EDGE (operator compared against the web player). |
| 10 | AA disconnect/reconnect | session resumes cleanly | **PASS** | 2026-07-02 / DHU kill + relaunch, `efbdf2a` | DHU process killed mid-play (car-unplug analogue): phone session paused cleanly, no error; after head-unit-server restart + DHU relaunch, app reopened from the launcher and played immediately with metadata intact. |

### Real head unit (car) confirmation

| Scenario | Result |
|---|---|
| Full matrix spot-check on a real in-car head unit (USB Android Auto) | **PASS** — 2026-07-02, operator's car: launcher entry, browse, playback, live metadata + cover updating on song change, play/pause-only transport all confirmed on real glass. |

## How to re-run

Rig setup — phone-side head unit server, `adb forward`, DHU launch, and the two
gotchas that bite every time — is in [DHU_NOTES.md](DHU_NOTES.md). Build and
install per [../README.md](../README.md) / `SIDELOAD_RUNBOOK.md`. Then, per row:

1. **Launcher:** open the DHU app launcher; confirm the SUB/WAVE Auto icon + label.
2. **Browse:** open SUB/WAVE Auto; confirm root shows exactly one playable "SUB/WAVE" entry.
3. **Play:** tap the live item; audio within 5 s, transport shows playing.
4. **Song change:** keep playing across a track transition; title/artist/cover update on the AA screen within ~10 s with no audio gap (notification mirrors it).
5. **Live edge:** play ≥ 1 min → pause 60 s → play; compare audio against the web player at radio.powerpoppalace.com — it must rejoin the live edge, not resume the stale buffer.
6. **No skip:** inspect the AA transport; play/pause only, no seek bar or skip buttons.
7. **Call interrupt:** place/receive a call mid-play; playback ducks or pauses, then auto-resumes on hang-up.
8. **BT-only AVRCP:** pair the phone to a Bluetooth-only head unit (no AA), play; title/artist appear on the car display.
9. **Station offline:** disable phone networking mid-play for ~30 s; expect an error state or silent retry with no crash; re-enable + play = recovery.
10. **Reconnect:** kill and relaunch the DHU per DHU_NOTES (re-start the phone-side head unit server first); confirm the session resumes cleanly.

File defects back as WP-scoped fix tasks and re-run affected rows after fixes
(record re-runs as new Result/Date entries, as row 4 does).
