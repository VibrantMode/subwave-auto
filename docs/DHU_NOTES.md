# DHU (Desktop Head Unit) — install facts

> Stub with verified install facts (WP5). WP6 owns the full testing doc
> (`ANDROID_AUTO_TESTING.md`) and expands this.

Installed 2026-07-02 via:

```
set JAVA_HOME=C:\Program Files\Android\Android Studio\jbr
"C:\Users\Mike\AppData\Local\Android\Sdk\cmdline-tools\latest\bin\sdkmanager.bat" "extras;google;auto"
```

Verified install location:

```
C:\Users\Mike\AppData\Local\Android\Sdk\extras\google\auto\desktop-head-unit.exe
```

(plus `libusb-1.0.dll`, `config\*.ini` presets, `voice\*.wav` in the same dir.)

## Run sequence (phone connected over USB, AA developer mode enabled)

1. On the phone: Android Auto app → Developer settings → **Start head unit server**
   (requires developer mode: AA app → tap version 10x).
2. Forward the DHU port:

   ```
   C:\Users\Mike\AppData\Local\Android\Sdk\platform-tools\adb.exe forward tcp:5277 tcp:5277
   ```

3. Launch the head unit:

   ```
   "C:\Users\Mike\AppData\Local\Android\Sdk\extras\google\auto\desktop-head-unit.exe"
   ```

Sideloaded apps only appear in the AA launcher with **Unknown sources** enabled
(AA app → Developer settings), and after force-stopping AA or rebooting the phone
so the launcher re-scans — full runbook in WP6's `SIDELOAD_RUNBOOK.md`.

## Gotchas — learned the hard way (verified session 2026-07-02)

Both bit us on the first real run; the sequence above only works with these:

1. **The DHU exits on stdin EOF.** It has an interactive `>` console; launched
   from a script/agent with a closed or detached stdin (backgrounded shell,
   `Start-Process`) it connects and then immediately quits. Launch it from an
   interactive terminal, or hold stdin open explicitly:

   ```bash
   # Git Bash — keeps the DHU alive from a non-interactive context
   tail -f /dev/null | ./desktop-head-unit.exe
   ```

2. **The phone-side head unit server stops when a connection drops.** After ANY
   failed/exited DHU attempt, re-tap **Start head unit server** on the phone
   before relaunching (if it shows "Stop head unit server", stop → start). A DHU
   stuck on "Waiting for phone" almost always means the server died with the
   previous connection.

Working order, every time: **(1) start server on phone → (2) `adb forward` →
(3) launch DHU with a live stdin.**

## First hardware run: results (2026-07-02, S24 Ultra / SM-S928U)

Discovery smoke **PASSED** — the plan's headline risk (media3 service discovery
via the legacy intent filter for a sideloaded app) is confirmed working on real
hardware: launcher icon present, browse root loads, playback starts, live
metadata + cover art render and track song changes, transport shows play/pause
only (seek/skip stripped). Artist line carries the station branding
("<artist> • <station name>"). The legacy `MediaBrowserServiceCompat`
fallback documented in the plan was NOT needed.
