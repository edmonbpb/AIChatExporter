# The encrypted key store, measured on the phone — 2026-11-04

The one witness the JVM suite cannot give: that the user's own key is not readable on a real
handset's disk. Robolectric hands out a fake keystore, so this had to run on hardware.

## Target

| What | Value |
| --- | --- |
| Device | `R58X30GS9QJ` — Samsung `SM-A155F` (`a15nsxx`), Android 16 / SDK 36, `arm64-v8a` |
| Branch / commit | `wave/security-2026-11-03` at `5249185` |
| App build | `app-debug.apk` (debug signing) and `app-debug-androidTest.apk`, built `--offline` |
| Package | `com.aistudio.aichatexporter`, runner `com.aistudio.aichatexporter.test/androidx.test.runner.AndroidJUnitRunner` |

The UFI modem on the same adb bus (`6517e41c`) was never a target: every command carried
`-s R58X30GS9QJ`.

## What was installed

The phone carried release version 7 signed with the owner's release key, so the debug build
could not update over it (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`). The owner's instruction was to
remove the installed build and install the test build:

```
adb -s R58X30GS9QJ uninstall com.aistudio.aichatexporter        -> Success
adb -s R58X30GS9QJ uninstall com.aistudio.aichatexporter.test   -> Success
adb -s R58X30GS9QJ install -r -t app-debug.apk                  -> Success
adb -s R58X30GS9QJ install -r -t app-debug-androidTest.apk      -> Success
```

The app's data on the phone was therefore cleared. That was a deliberate owner decision.

## The run

```
adb -s R58X30GS9QJ shell am instrument -w -e class com.example.EncryptedKeyStorageTest \
  com.aistudio.aichatexporter.test/androidx.test.runner.AndroidJUnitRunner
```

```
com.example.EncryptedKeyStorageTest:.

Time: 0.24

OK (1 test)
```

Exit code 0. The crash buffer was cleared before the run and read after it; it is empty
(`EncryptedKeyStorageTest-5249185-…-crash.log`, 0 lines).

Evidence, kept under a tracked path (the `raw/` directory is git-ignored):
`docs/reports/evidence/EncryptedKeyStorageTest-5249185-20261104T194040Z.txt` and the empty crash
buffer beside it.

## Independent check of the file itself

The test asserts in-process; this reads the file from outside the app, through `run-as` on the
debug build:

```
adb -s R58X30GS9QJ shell run-as com.aistudio.aichatexporter ls -l shared_prefs/
-rw-rw---- 1 u0_a1139 u0_a1139 1144 2026-11-04 23:10 aichat_secret_keys.xml
```

The file holds only the two AndroidX keyset blobs:

```xml
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <string name="__androidx_security_crypto_encrypted_prefs_key_keyset__">12a90134aa576a28…</string>
    <string name="__androidx_security_crypto_encrypted_prefs_value_keyset__">1288013ab856f045…</string>
</map>
```

Searches over the raw file: the key value marker `AIzaSy-NotARealKey-UsedOnlyForThisTest` — 0
hits; the preference name `gemini_api_key` — 0 hits. No user entry appears on disk in clear; even
the preference names are sealed.

## Health after install

The freshly installed build starts and takes the foreground:

```
adb -s R58X30GS9QJ shell am start -n com.aistudio.aichatexporter/com.example.MainActivity
mCurrentFocus=Window{27fc8f3 u0 com.aistudio.aichatexporter/com.example.MainActivity}
```

## What this closes

The security plan's open row: "the encryption itself is unproven until the androidTest witness
runs". It has now run, on the phone, green, with the raw file read from outside the app.

Still not covered by any device run: the other androidTest classes in this module
(`PersistenceRoundTripTest`, the real-user scenario phases, the PDF checks). They were not part of
this wave and were not run here.
