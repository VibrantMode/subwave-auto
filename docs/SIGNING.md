# SUB/WAVE Auto — release signing

## What exists

| File | Location | In git? |
|---|---|---|
| Release keystore | `app-auto/subwave-auto.keystore` | **No** (`app-auto/.gitignore`: `*.keystore`) |
| Credentials | `app-auto/keystore.properties` | **No** (`app-auto/.gitignore`: `keystore.properties`) |

`keystore.properties` keys (read by `app/build.gradle.kts`):

```
storeFile=../subwave-auto.keystore   # relative to the app/ module dir
storePassword=<in keystore.properties>
keyAlias=subwaveauto
keyPassword=<in keystore.properties>
```

## How it was generated (2026-07-02)

```
"C:\Program Files\Android\Android Studio\jbr\bin\keytool.exe" -genkeypair -v \
  -keystore subwave-auto.keystore -alias subwaveauto \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -dname "CN=SUB/WAVE Auto, OU=subwave-auto, O=Power Pop Palace"
```

One randomly generated password serves as both store and key password; it lives
only in `keystore.properties`. It is not recorded anywhere else — see **Back it
up** below.

## How the build uses it

`app/build.gradle.kts` loads `keystore.properties` from the project root **if it
exists** and creates a `release` signing config from it. If the file is absent
(fresh clone, CI), `assembleRelease` silently falls back to the **debug** signing
key so the build stays green — `scripts/build-release.{sh,ps1}` print which case
you got. A debug-signed "release" APK works for local testing but is a different
signature (see below).

## Back it up (do this once, now)

Store **both files** — `subwave-auto.keystore` and `keystore.properties` — in the
operator's password manager (as file attachments, or the base64 of the keystore +
the password as a note). They exist nowhere else.

Losing the key is annoying but **not fatal**: this app is sideload-only, so you
just generate a new keystore, then on each phone uninstall the app, reinstall the
new APK, and re-do the Android Auto unknown-sources dance (AA developer settings
survive an app reinstall, but re-check the toggle).

## The same-signature rule

`adb install -r` (upgrade-in-place, keeping app data + AA visibility) only works
when the new APK is signed with the **same key** as the installed one. A
signature mismatch fails with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`; the fix is
`adb uninstall com.powerpoppalace.subwaveauto` then a fresh install. This is why
the keystore must be stable and backed up — and why a debug-signed fallback build
won't `install -r` over a release-signed install (or vice versa).

## Regenerating on a new machine

1. Preferred: restore both files from the password manager into `app-auto/`.
   Done — builds sign identically.
2. If truly lost: re-run the keytool command above from `app-auto/` with a new
   password, write a fresh `keystore.properties` (keys as in the table above),
   back both up, and uninstall/reinstall on every device.

Verify any APK's signer with:

```
"C:/Users/Mike/AppData/Local/Android/Sdk/build-tools/36.0.0/apksigner.bat" \
  verify --print-certs app/build/outputs/apk/release/app-release.apk
```

Expected DN: `CN=SUB/WAVE Auto, OU=subwave-auto, O=Power Pop Palace`.
