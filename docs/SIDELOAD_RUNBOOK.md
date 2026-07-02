# SUB/WAVE Auto — sideload runbook

Get the app onto a phone and visible in Android Auto. Every step, in order.
Android Auto only shows Play-Store media apps by default; steps 5–6 are the
per-phone, one-time escape hatch for sideloads.

## 1. Build the release APK

From `app-auto/`:

```
scripts\build-release.ps1        # Windows
scripts/build-release.sh         # Git Bash / POSIX
```

- Both scripts set `JAVA_HOME` to Android Studio's bundled JBR
  (`C:\Program Files\Android\Android Studio\jbr`) before invoking Gradle. If
  you run `gradlew :app:assembleRelease` by hand, set the same `JAVA_HOME`
  first — a stray system JDK is the most common build failure.
- Output: `app-auto/app/build/outputs/apk/release/app-release.apk`.
- The script prints which signing key it used. **RELEASE key** requires
  `app-auto/keystore.properties` + `subwave-auto.keystore` to exist (see
  [SIGNING.md](SIGNING.md)); without them it silently falls back to the
  **DEBUG key**, which is a different signature (see step 8).

## 2. Enable USB debugging on the phone (one-time)

Samsung path (other Androids differ only in menu nesting):

1. Settings → About phone → Software information → tap **Build number** 7×
   ("Developer mode has been turned on").
2. Settings → **Developer options** → enable **USB debugging**.
3. Plug in USB; on the phone, accept the **RSA fingerprint prompt**
   ("Allow USB debugging?") — check "Always allow from this computer".

## 3. Install

```
scripts\install.ps1              # Windows
scripts/install.sh               # Git Bash / POSIX
```

Installs the release APK onto the one connected authorized device
(`adb install -r` under the hood — pass an APK path as an argument to install
something else). Manual equivalent:

```
adb install -r app/build/outputs/apk/release/app-release.apk
```

## 4. First launch on the phone

Open **SUB/WAVE Auto** once and **allow notifications** when prompted
(Android 13+; without it the media notification is invisible).

## 5. Android Auto: unknown sources (one-time per phone)

1. Open the **Android Auto** app (Settings → Connected devices →
   Android Auto, or search Settings for "Android Auto").
2. Scroll to the **version number** and tap it **10×** → confirm enabling
   Developer mode.
3. Menu (⋮) → **Developer settings** → enable **Unknown sources**.

## 6. Force-stop Android Auto

The AA launcher caches its app list. Force-stop the Android Auto app
(Settings → Apps → Android Auto → Force stop) — or reboot the phone — so it
re-scans and picks up the sideloaded app.

## 7. Verify

Connect to the car (USB) or the desktop head unit
([DHU_NOTES.md](DHU_NOTES.md) — setup, launch order, and the two gotchas) and
confirm **SUB/WAVE Auto** appears in the AA app launcher. Full acceptance
matrix: [ANDROID_AUTO_TESTING.md](ANDROID_AUTO_TESTING.md).

## 8. Upgrades: the same-signature rule

`adb install -r` upgrades in place (keeping app data and AA visibility) **only
when the new APK is signed with the same key** as the installed one. A
mismatch — including debug-signed over release-signed or vice versa — fails
with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`; the fix is
`adb uninstall com.powerpoppalace.subwaveauto` then a fresh install (app data
lost; AA unknown-sources survives, but re-check the toggle). Details and
keystore backup rules: [SIGNING.md](SIGNING.md).

## Troubleshooting

- **App not in the AA launcher** — confirm Unknown sources is ON (step 5);
  force-stop AA again (step 6); reboot the phone if it still hides.
- **adb sees no device / device "unauthorized"** — check the USB mode isn't
  charge-only, and accept the RSA prompt on the phone (step 2.3); replug if no
  prompt appears.
- **Build fails on the wrong JDK** — use the build scripts (they pin
  `JAVA_HOME` to the Android Studio JBR), or export that `JAVA_HOME` yourself
  before running `gradlew`.
- **`INSTALL_FAILED_UPDATE_INCOMPATIBLE`** — signature mismatch; see step 8.
