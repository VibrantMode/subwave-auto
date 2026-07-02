# SUB/WAVE Auto — Android Auto companion app (sideload) — Implementation Plan

**Status:** planned · **Target:** sideloadable APK that surfaces SUB/WAVE in Android Auto
**Repo home:** new top-level dir `app-auto/` (pure Kotlin/Gradle — deliberately OUTSIDE `app/`'s Expo project and outside upstream's merge surface; upstream never touches this path)

---

## 0. Background & architecture decision (read first)

### Why a separate app, not the existing `app/`

The existing native app (`app/`, Expo SDK 56 + react-native-track-player 4.1.2) **cannot appear in Android Auto**:

1. **AA requires a browsable media service.** Android Auto only lists apps exposing a
   `MediaLibraryService` (androidx.media3) or legacy `MediaBrowserServiceCompat`, declared with the
   `android.media.browse.MediaBrowserService` intent filter **and** a
   `res/xml/automotive_app_desc.xml` (`<uses name="media"/>`). RNTP 4.1.2 has neither — its Android
   service is a headless-JS task service, and AA support is a long-open upstream feature request.
2. **RNTP's ExoPlayer is private.** KotlinAudio owns the player instance; a bolt-on Kotlin
   MediaLibraryService can't attach to it — you'd run two players / two sessions that fight over
   audio focus and notification ownership.
3. **Expo CNG makes native surgery expensive.** `android/` is regenerated on every build; all native
   additions must be config plugins, and the project already carries a hand-rolled new-arch patch on
   RNTP (`app/patches/react-native-track-player+4.1.2.patch`). Every iteration is an EAS cloud build.

A **dedicated Kotlin app using androidx.media3** is the architecture Google's own
Universal Android Music Player sample uses, is ~20 small files, builds locally in seconds with
Gradle, and is fully deterministic for worker models.

### Why sideloading works for Android Auto

Android Auto normally only shows media apps installed from the Play Store. The escape hatch is
per-phone and one-time: **Android Auto app → tap version number 10× → Developer mode →
Developer settings → "Unknown sources"**. With that toggled, sideloaded media apps appear in the AA
launcher. No Play Console, no review, no TTS-content policy. (Documented as a runbook in WP6.)

### Free bonus: Bluetooth AVRCP metadata

A correct media3 `MediaSession` propagates title/artist/album to car displays over **plain
Bluetooth (AVRCP)** — independent of Android Auto. This directly addresses the known gap where the
RN app plays fine over BT but shows no metadata on the car screen.

### What the station already provides (no controller changes needed)

| Endpoint | Use |
|---|---|
| `GET {base}/stream.mp3` | The live MP3 stream (universal floor; append `?t=<ms>` cache-buster on (re)connect — mirrors `app/src/audio/player.ts:77`) |
| `GET {base}/api/now-playing` | **CORRECTED post-build (2026-07-02, the row-4 defect):** track fields are NESTED — `nowPlaying.{title,artist,album}`; artwork is top-level `cover` (CONTROLLER-relative, public URL = `{base}/api{path}`) with optional `art`; station name = `dj.station`; plus `streamOnline`, `listeners`, `stream{…}`. Poll every 5 s while playing. The original plan text described flat top-level fields — wrong; parser reads nested-first with flat fallback (see `StationApi.parseNowPlaying` + its live-payload regression tests) |
| `GET {base}/api/state` | station name, active show/persona (v1: optional) |
| `GET {base}/listen.pls` | not needed (we hardcode `/stream.mp3` like the RN app) |

Default station base: `https://radio.powerpoppalace.com` (user-editable; the RN app is
multi-station via runtime base URL — we keep that spirit with one editable URL in v1).

### Product decisions (locked)

- **Play / Pause / Stop only. NO skip, NO seek** — shared live broadcast; mirrors the deliberate
  omission in `app/service.ts` and the web player.
- "Pause → Play" must **reload at the live edge** with a fresh cache-buster, never resume a stale
  buffer (mirrors `service.ts` RemotePlay handling).
- Package id `com.powerpoppalace.subwaveauto` (must NOT collide with `com.getsubwave.app`).
- minSdk 26, compileSdk/targetSdk 36 (matches the RN app's target 36).
- Single module, no DI framework, no fancy architecture — this is ~20 files; keep it boring.

---

## 1. Shared contracts (ALL workers read this; defined up front so WPs can run in parallel)

**Package root:** `com.powerpoppalace.subwaveauto`

```
app-auto/
├─ settings.gradle.kts, build.gradle.kts, gradle.properties, gradle/wrapper/…
├─ app/
│  ├─ build.gradle.kts
│  └─ src/main/
│     ├─ AndroidManifest.xml
│     ├─ res/xml/automotive_app_desc.xml
│     ├─ res/… (icon, strings, theme)
│     └─ java/com/powerpoppalace/subwaveauto/
│        ├─ net/StationApi.kt          (WP1)  — HTTP client + models
│        ├─ net/NowPlaying.kt          (WP1)  — data class
│        ├─ prefs/StationPrefs.kt      (WP1)  — SharedPreferences: baseUrl
│        ├─ playback/PlaybackService.kt(WP2)  — MediaLibraryService + ExoPlayer
│        ├─ playback/LiveMetadata.kt   (WP3)  — now-playing → MediaMetadata sync
│        ├─ playback/BrowseTree.kt     (WP3)  — AA browse tree (root → live item)
│        └─ ui/MainActivity.kt         (WP4)  — minimal Compose phone UI
├─ app/src/test/… (WP1 unit tests)
└─ scripts/ + docs/ (WP5, WP6)
```

**Interfaces (source of truth — implement exactly these signatures):**

```kotlin
// net/NowPlaying.kt  (WP1 owns)
data class NowPlaying(
    val title: String?, val artist: String?, val album: String?,
    val artUrl: String?,          // absolute URL: `art` field, falling back to `cover`
    val streamOnline: Boolean,
)

// net/StationApi.kt  (WP1 owns)
class StationApi(private val baseUrl: String) {   // baseUrl WITHOUT trailing slash
    suspend fun nowPlaying(): NowPlaying?          // GET {base}/api/now-playing, null on any error
    suspend fun fetchArt(url: String): ByteArray?  // bytes for MediaMetadata.artworkData, null on error
    fun streamUrl(cacheBust: Boolean = true): String  // {base}/stream.mp3[?t=<now>]
}

// prefs/StationPrefs.kt  (WP1 owns)
object StationPrefs {
    const val DEFAULT_BASE_URL = "https://radio.powerpoppalace.com"
    fun baseUrl(ctx: Context): String
    fun setBaseUrl(ctx: Context, url: String)
    // setBaseUrl: trims, strips trailing '/'. SECURITY: requires https:// —
    // plain http:// is accepted ONLY for private/dev hosts (localhost, 127.*,
    // 10.*, 172.16-31.*, 192.168.*). A user-editable URL feeds the stream URI
    // and artwork fetches; don't let a captive portal downgrade the transport.
    fun registerListener(ctx: Context, onChange: () -> Unit)  // WP3's base-URL-change hook
}

// playback/BrowseTree.kt constants (WP3 owns; WP2 + WP4 reference)
const val ROOT_ID = "subwave_root"
const val LIVE_ITEM_ID = "subwave_live"

// Construction contracts (no DI framework — plain constructors ARE the contract;
// parallel workers must not invent different shapes):
class BrowseTree(private val api: StationApi) : MediaLibrarySession.Callback
class LiveMetadata(
    private val player: Player,           // PlaybackService's ForwardingPlayer
    private val api: StationApi,
    private val scope: CoroutineScope,    // service-owned; cancelled in onDestroy
)
// PlaybackService (a Service — no constructor): owns ExoPlayer + session + scope,
// builds StationApi from StationPrefs.baseUrl(this), instantiates BrowseTree +
// LiveMetadata in onCreate, and owns the StationPrefs.registerListener hookup.

// Live MediaItem invariants (WP2 + WP3 must agree):
//   mediaId = LIVE_ITEM_ID; setMimeType(MimeTypes.AUDIO_MPEG)  // skip content sniffing
//   MEDIA_TYPE_RADIO_STATION; artworkData capped 1 MB; artwork cache ≤ 8 entries (LRU)
```

**Version pins (WP0 owns; nobody else edits build files):** Kotlin 2.0.x, AGP 8.7+,
`androidx.media3:media3-exoplayer` + `media3-session` **1.8.0** (or latest stable ≥1.4 at build
time — one version for all media3 artifacts), `kotlinx-coroutines-android`, OkHttp 4.x,
`org.json` (in Android; no serialization lib needed for one endpoint), Compose BOM (WP4 only).

**Worker ground rules:** own only your WP's files; never edit another WP's files; if you need a
change in a shared contract, STOP and surface it instead of drifting from this document. Every WP
ends with its acceptance criteria verified and `./gradlew :app:assembleDebug` green.

---

## 2. Work packages

### WP0 — Project scaffold  *(no dependencies; first)*

**Files:** everything under `app-auto/` except `java/**` sources owned by later WPs; a placeholder
`MainActivity` + manifest skeleton so the project compiles standalone.

Steps:
1. `app-auto/` Gradle project: `settings.gradle.kts` (project name `subwave-auto`), root
   `build.gradle.kts`, wrapper (Gradle 8.9+), `gradle.properties`
   (`android.useAndroidX=true`, JVM args).
2. `:app` module, `applicationId com.powerpoppalace.subwaveauto`, minSdk 26, target/compileSdk 36,
   Kotlin 2.0.x, Compose enabled, dependencies per §1 pins.
3. Manifest skeleton: `INTERNET`, `WAKE_LOCK`, `FOREGROUND_SERVICE`,
   `FOREGROUND_SERVICE_MEDIA_PLAYBACK`, `POST_NOTIFICATIONS`; application label
   `SUB/WAVE Auto`; placeholder launcher activity. (AA meta-data + service entries land in WP5 —
   leave `<!-- WP5: AA declarations -->` markers.)
4. Simple disc-mark placeholder icon (reuse `app/assets/` disc mark as source art, or a plain
   adaptive icon — final art is not a blocker).
5. `app-auto/.gitignore`: `build/`, `.gradle/`, `local.properties`, `*.keystore`, `keystore.properties`.

**Acceptance:** `./gradlew :app:assembleDebug` produces an installable APK; app opens to a blank
activity on a device/emulator. No AA behavior yet.

### WP1 — Station API client + prefs  *(no dependencies; parallel with WP0)*

**Files:** `net/StationApi.kt`, `net/NowPlaying.kt`, `prefs/StationPrefs.kt`,
`app/src/test/java/**` unit tests.

Steps:
1. Implement §1 signatures with OkHttp (8 s call timeout) + `org.json` parsing. Field mapping from
   `/api/now-playing`: `title`, `artist`, `album`, `art` (fall back to `cover`), `streamOnline`
   (fall back to `stream.online`, default true). **Never throw** — any network/parse failure returns
   null (the station may be briefly down; playback must not care).
2. Relative art URLs (defensive): resolve against `baseUrl`.
3. `streamUrl()`: `{base}/stream.mp3?t=<System.currentTimeMillis()>` when `cacheBust`.
4. `StationPrefs`: SharedPreferences file `station`, key `baseUrl`; setter normalizes (trim,
   strip trailing `/`) and enforces the **https-except-private-hosts rule from §1** (reject plain
   `http://` unless the host is localhost/127.*/10.*/172.16-31.*/192.168.*); implements
   `registerListener` (WP3's base-URL-change hook).
5. JVM unit tests with OkHttp `MockWebServer`: happy path, missing fields, `cover`-only payload,
   500, malformed JSON, timeout → null; `streamUrl` cache-buster format; prefs normalization
   **including the https/private-host acceptance-rejection matrix**.

**Acceptance:** `./gradlew :app:testDebugUnitTest` green with the above cases.

### WP2 — Playback service (the heart)  *(depends: WP0)*

**Files:** `playback/PlaybackService.kt`.

Steps:
1. `class PlaybackService : MediaLibraryService()` holding one ExoPlayer + one
   `MediaLibrarySession` (callback delegated to WP3's `BrowseTree`; until WP3 lands, ship a stub
   callback returning an empty root so WP2 is independently testable from the phone UI).
2. ExoPlayer config:
   - `AudioAttributes` (USAGE_MEDIA / CONTENT_TYPE_MUSIC), `handleAudioFocus = true`
   - `setHandleAudioBecomingNoisy(true)` (unplugged headphones → pause)
   - live stream `MediaItem`: URI from `StationApi.streamUrl()`, mediaId `LIVE_ITEM_ID`,
     initial `MediaMetadata` (title "SUB/WAVE", artist "Live broadcast",
     `MEDIA_TYPE_RADIO_STATION`, `isPlayable=true, isBrowsable=false`)
   - it's an endless progressive MP3 — no DASH/HLS modules needed
3. **Live-edge rule:** wrap the player in a `ForwardingPlayer` that intercepts `play()`/
   `COMMAND_PLAY_PAUSE` when the player is in a paused-with-stale-buffer state — **paused > 30 s,
   or `STATE_IDLE`/`STATE_ENDED`** (30 s, not lower: a brief pause from a short phone call or a
   transient audio-focus duck must NOT trigger a reload-and-rebuffer; media3's own transient-focus
   auto-resume does not route through the public `play()`, but the interaction MUST be verified —
   WP6 matrix #7): call `setMediaItem(freshCacheBustedItem)` + `prepare()` + `play()` instead of
   resuming. Threading: all media3 player access happens on the application/main thread (media3's
   threading contract — session callbacks already arrive there); the reload is a state swap, not
   blocking I/O, so no ANR risk — but do NOT add network calls inside the intercept. This mirrors
   `app/service.ts` RemotePlay and MUST be preserved — AA's play button, BT play button, and the
   notification all route through it.
4. **Disable skip/seek:** `ForwardingPlayer.getAvailableCommands()` removes
   `COMMAND_SEEK_TO_NEXT`, `COMMAND_SEEK_TO_PREVIOUS`, `COMMAND_SEEK_TO_NEXT_MEDIA_ITEM`,
   `COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM`, `COMMAND_SEEK_BACK`, `COMMAND_SEEK_FORWARD`,
   `COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM`. AA then renders play/pause only.
5. Error resilience: `Player.Listener.onPlayerError` → if network-ish, one automatic retry after
   3 s with a fresh cache-busted item; otherwise surface the error state (AA shows it).
6. Service lifecycle: `onGetSession` returns the session for ALL controllers (AA, BT, notification,
   phone UI); `onTaskRemoved` → if not playing, `stopSelf()`; release player + session in
   `onDestroy`. media3's `MediaSessionService` provides the foreground media notification
   automatically — do not hand-roll one.

**Acceptance:** with WP4's UI (or `adb shell media dispatch play-pause` while the service runs),
stream plays in background with a media notification; pausing >5 s then playing audibly rejoins the
LIVE edge (not the stale buffer — verify against a clock reference or the web player side-by-side);
headphone unplug pauses; no skip buttons in the notification.

### WP3 — Browse tree + live metadata sync  *(depends: WP1 + WP2)*

**Files:** `playback/BrowseTree.kt`, `playback/LiveMetadata.kt`; wires into `PlaybackService`
(coordinate: WP3 replaces WP2's stub callback — the only sanctioned cross-WP edit, ≤10 lines).

Steps:
1. `BrowseTree` implements `MediaLibrarySession.Callback`:
   - `onGetLibraryRoot` → browsable root (`ROOT_ID`, title "SUB/WAVE")
   - `onGetChildren(ROOT_ID)` → one **playable** item: `LIVE_ITEM_ID`, title = station name
     ("SUB/WAVE" v1), subtitle "Live radio", `MEDIA_TYPE_RADIO_STATION`, artwork = station logo
     if cheaply available, else app icon
   - `onGetItem(LIVE_ITEM_ID)` → same item
   - `onAddMediaItems` → resolve any requested id to the fully-formed live item with a **fresh
     cache-busted stream URI** (AA sends bare mediaIds without URIs — this hook is mandatory)
2. `LiveMetadata`: a coroutine started while `player.isPlaying` (stopped otherwise), every **5 s**:
   - `api.nowPlaying()` → if changed (title+artist tuple), build new `MediaMetadata`
     (title, artist, album, `artworkData` = `api.fetchArt(artUrl)` bytes, capped ~1 MB, cached by
     URL so the same cover isn't re-fetched)
   - apply via `player.replaceMediaItem(0, current.buildUpon().setMediaMetadata(newMeta).build())` —
     same URI → media3 keeps playback; **verify zero audio interruption**. If any glitch is
     observed, fall back to the ForwardingPlayer-metadata pattern (override `getMediaMetadata()` +
     emit `onMediaMetadataChanged`) — decide by testing, document which shipped.
   - `nowPlaying() == null` (station briefly down): keep last metadata, never crash the loop.
3. Base-URL change (via `StationPrefs.registerListener`): stop playback, rebuild `StationApi`,
   reset the media item.
4. **Tests (the AA-critical seam):** unit tests for `onAddMediaItems` — resolving `LIVE_ITEM_ID`
   (and a bare `MediaItem` with only a mediaId, exactly what AA sends) must return an item with a
   non-empty cache-busted stream URI, `MimeTypes.AUDIO_MPEG`, and `LIVE_ITEM_ID`. Keep `BrowseTree`
   constructible with a fake `StationApi` so these run as plain JVM/Robolectric tests. This hook is
   the single most common AA playback failure point — it gets tests, not just DHU eyeballs.

**Acceptance:** WP3 unit tests green; in DHU (see WP6) the app shows root → "SUB/WAVE" playable
item; tapping it starts audio; **title/artist/cover update on the AA screen within ~10 s of a song
change with no audio gap**; the media notification mirrors the same metadata.

### WP4 — Phone UI (minimal)  *(depends: WP0; WP1 for prefs; runs parallel to WP2/3)*

**Files:** `ui/MainActivity.kt` (+ tiny theme res).

Steps — one Compose screen, deliberately spartan (the car is the product; the phone UI is a remote):
1. Connect a `MediaController` to `PlaybackService` (`SessionToken` + `MediaController.Builder`);
   release in `onDestroy`.
2. UI: station name header; now-playing title/artist (from controller `mediaMetadata`, observed via
   `Player.Listener`); one Play/Pause button bound to controller state; station base-URL text field
   + Save (writes `StationPrefs`, informs user playback restarts); static hint text: *"To see
   SUB/WAVE in Android Auto: AA app → version ×10 → Developer settings → Unknown sources."*
3. Request `POST_NOTIFICATIONS` runtime permission on first launch (API 33+) — without it the media
   notification is invisible on Android 13+.

**Acceptance:** open app → Play → audio + notification; metadata visible in-app once WP3 lands;
URL edit round-trips through prefs; permission prompt appears once on Android 13+.

### WP5 — Android Auto declarations, signing, build & install scripts  *(depends: WP2)*

**Files:** manifest AA block, `res/xml/automotive_app_desc.xml`, `scripts/build-release.(sh|ps1)`,
`scripts/install.(sh|ps1)`, `docs/SIGNING.md`.

Steps:
1. `res/xml/automotive_app_desc.xml`:
   ```xml
   <automotiveApp><uses name="media"/></automotiveApp>
   ```
2. Manifest (replace WP0's markers):
   ```xml
   <application …>
     <meta-data android:name="com.google.android.gms.car.application"
                android:resource="@xml/automotive_app_desc"/>
     <service android:name=".playback.PlaybackService"
              android:exported="true"
              android:foregroundServiceType="mediaPlayback">
       <intent-filter>
         <action android:name="androidx.media3.session.MediaSessionService"/>
         <action android:name="android.media.browse.MediaBrowserService"/>
       </intent-filter>
     </service>
   </application>
   ```
   (Both actions: media3 controllers use the first, Android Auto discovers via the second.)
3. Release signing: `docs/SIGNING.md` — generate once with
   `keytool -genkeypair -v -keystore subwave-auto.keystore -alias subwaveauto -keyalg RSA -keysize 2048 -validity 10000`;
   `keystore.properties` (gitignored) read by `build.gradle.kts` signingConfig. **A stable
   signature matters:** `adb install -r` upgrades only work with the same key; changing keys later
   means uninstall + AA re-toggle.
4. Scripts: `build-release` → `./gradlew :app:assembleRelease` and print APK path;
   `install` → `adb install -r <apk>` with a device check. Provide both `.sh` and `.ps1`
   (operator's workstation is Windows; mirrors the `subwave-app-android` skill's adb flow).

5. **EARLY DHU discovery smoke (do not defer to WP6)** — both council reviewers flagged AA
   service discovery as the plan's biggest silent-failure risk, so verify it the moment the
   declarations land: install a debug build, enable AA unknown-sources (runbook §WP6.2 steps 2–4),
   connect the DHU, and confirm **(a)** SUB/WAVE Auto appears in the AA launcher and **(b)** the
   browse root loads. The dual intent-filter (media3 action + legacy
   `android.media.browse.MediaBrowserService`) is the documented media3 pattern —
   `MediaLibraryService` serves legacy `MediaBrowserCompat` clients through `onBind` — but this is
   exactly the kind of claim to prove on hardware, not trust. **Documented fallback if discovery
   fails:** ship a thin legacy `MediaBrowserServiceCompat` façade (own service entry with the
   legacy action) that connects a `MediaBrowserCompat` bridge to the media3 session — or drop the
   media3 service to `androidx.media` compat entirely; decide by test, record the outcome here.

**Acceptance:** signed release APK builds; `adb install -r` onto a phone succeeds; `aapt dump
badging` (or `apkanalyzer`) shows the car-application meta-data and the exported service with both
intent-filter actions; **DHU discovery smoke passes (launcher visibility + browse root)**.

### WP6 — QA, DHU verification, operator runbooks  *(depends: all; final gate)*

**Files:** `docs/ANDROID_AUTO_TESTING.md`, `docs/SIDELOAD_RUNBOOK.md`, updates to `app-auto/README.md`.

Steps:
1. **DHU (Desktop Head Unit) runbook** — test AA without a car:
   SDK Manager → install "Android Auto Desktop Head Unit emulator"; on phone enable AA developer
   mode → *Start head unit server*; `adb forward tcp:5277 tcp:5277`; run `desktop-head-unit`.
   Document Windows paths (`%LOCALAPPDATA%\Android\Sdk\extras\google\auto\`).
2. **Sideload runbook** (the reason this app exists):
   1. `scripts/install` the release APK
   2. Android Auto app → About/version → tap version **10×** → enable Developer mode
   3. ⋮ → Developer settings → **Unknown sources** ON
   4. Force-stop AA (or reboot phone) so the launcher re-scans
   5. Car/DHU → app launcher → **SUB/WAVE Auto** appears
3. **Acceptance matrix** (execute on DHU + at least one real head unit + one BT-only car):

   | # | Scenario | Pass criteria |
   |---|---|---|
   | 1 | App visible in AA launcher (unknown sources ON) | icon + label present |
   | 2 | Browse: root → live item | one playable "SUB/WAVE" entry |
   | 3 | Tap to play | audio < 5 s, AA shows playing state |
   | 4 | Song transition | AA title/artist/cover update ≤ 10 s, **zero audio gap** |
   | 5 | Pause 60 s → Play | rejoins LIVE edge (compare against web player) |
   | 6 | No skip/seek | AA shows play/pause only |
   | 7 | Phone call interrupt | duck/pause + auto-resume (audio focus) |
   | 8 | BT-only car (no AA) | title/artist on car display via AVRCP |
   | 9 | Station offline mid-play | error state or silent retry; no crash; recovers when back |
   | 10 | AA disconnect/reconnect | session resumes cleanly |
4. File real defects back as WP-scoped fix tasks; re-run the matrix after fixes.

**Acceptance:** matrix executed with results recorded in `docs/ANDROID_AUTO_TESTING.md`; all P0
rows (1–6) pass.

---

## 3. Dependency graph & suggested worker assignment

```
WP0 (scaffold) ──┬─→ WP2 (service) ──┬─→ WP3 (browse+metadata) ──→ WP6 (QA)
                 ├─→ WP4 (phone UI) ─┘         ↑
WP1 (api client)─┴────────────────────────────-┘
                                WP2 ──→ WP5 (AA decl + signing) ──→ WP6
```

- **Wave 1 (parallel):** worker A = WP0, worker B = WP1
- **Wave 2 (parallel):** worker C = WP2, worker D = WP4
- **Wave 3 (parallel):** worker E = WP3, worker F = WP5
- **Wave 4:** worker G = WP6 (QA + runbooks)

Each worker receives: this whole document + "you own WP _n_; touch only its files; contracts in §1
are law; end with your acceptance criteria demonstrated."

## 4. Risks & mitigations

| Risk | Mitigation |
|---|---|
| AA doesn't discover the media3 service via the legacy intent filter (flagged 2/2 by council) | WP5 step 5: EARLY DHU discovery smoke immediately after declarations land; documented fallback = legacy `MediaBrowserServiceCompat` façade/bridge |
| `replaceMediaItem` metadata update audibly glitches on some devices | Documented fallback in WP3: ForwardingPlayer metadata override; decide by test, not by assumption |
| User-editable base URL over plain http (captive portal / hostile Wi-Fi) | §1 rule: https required except private/dev hosts; enforced + unit-tested in WP1 (cert pinning deliberately skipped — Cloudflare rotates certs) |
| Brief pause (short call, focus duck) triggers a needless reload+rebuffer | 30 s staleness threshold + STATE_IDLE/ENDED; transient-focus interaction verified in WP6 matrix #7 |
| AA launcher doesn't show the app after sideload | Force-stop AA / reboot (launcher caches); confirm unknown-sources toggle survived AA updates |
| Samsung battery management kills the service | Media3 foreground service is exempt while playing; document "remove battery optimization" for the app in the sideload runbook if observed |
| Cloudflare-fronted stream stalls on cellular | Same origin the RN app already streams from — known-good; WP2's error-retry covers transient stalls |
| Key loss (release keystore) | `docs/SIGNING.md` mandates backing the keystore into the operator's password manager; a lost key = uninstall/reinstall, not fatal |
| Upstream someday ships AA in RNTP | This app stays useful (BT metadata, tiny footprint) or is retired; zero coupling to `app/` means zero migration cost either way |

## 5. Out of scope (v1)

- Play Store distribution (the entire point is sideload); CarPlay (different stack entirely);
  multi-station browse tree (v1 = one configurable base URL; a "Stations" browse node fed by the
  RN app's directory is a clean v2); voice "Hey Google, play SUB/WAVE" (needs Play-indexed media
  actions); Android Automotive OS (car-native, different target — the manifest declaration here is
  deliberately AA-projection only).
