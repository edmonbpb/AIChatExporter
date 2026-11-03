# Security gate run — 2026-11-03

The artifact proves itself before it is handed out. Every check below is measured on the **built APK**, not the source, because the source being clean is not what a user installs.

Gate: `scripts/security-gate.sh` (six checks: no AI key in dex, account configuration present, not debuggable, no cleartext, versionCode match, backup disallowed).

## Step 2 — Gate run on the release artifact

Command:

```bash
bash scripts/security-gate.sh app/build/outputs/apk/release/app-release.apk
```

Output:

```
PASS: no AI key in any dex (3 files searched)
PASS: the account configuration is inside (aichatexporter-auth)
PASS: the artifact is not debuggable
PASS: the artifact sets no cleartext permission
PASS: versionCode 7 matches the build file
PASS: the artifact disallows backup
RESULT: PASS
exit=0
```

APK under test, md5sum:

```
8423930a7f32a1a563338ed412e44a78 *app/build/outputs/apk/release/app-release.apk
```

## Step 3 — The gate can go red (two independent breaks, measured and restored)

### (a) A debuggable artifact must fail

Command:

```bash
bash scripts/security-gate.sh app/build/outputs/apk/debug/app-debug.apk; echo "exit=$?"
```

Result: exit `1`. Output (the debug artifact also fails checks 2 and 5 — it is built without `google-services.json` and carries versionCode 1; the check this break witnesses is `android:debuggable`):

```
PASS: no AI key in any dex (13 files searched)
FAIL: no trace of aichatexporter-auth in the artifact: this build carries no account service
FAIL: the artifact sets android:debuggable
PASS: the artifact sets no cleartext permission
FAIL: versionCode in the artifact is '1', the build file says '7'
PASS: the artifact disallows backup
RESULT: FAIL (3 check(s))
exit=1
```

### (b) A planted key must fail

Command (artifacts kept outside the repo, deleted afterwards). The brief's `zip`-based command was adapted: `zip` is not installed on this host, so the planted APK was rebuilt with Python's `zipfile` — `classes.dex` copied through with the planted string appended:

```python
for item in src.infolist():
    data = src.read(item.filename)
    if item.filename == 'classes.dex':
        data += b'AIzaSyPlantedKeyForTheGateTest0000000000\n'
    out.writestr(item, data)
```

```bash
bash scripts/security-gate.sh <planted-new.apk>; echo "exit=$?"
```

Result: exit `1`, naming the key count. Output:

```
FAIL: 1 AI-key-shaped strings found in the dex files; build with -PpublicBuild=true
PASS: the account configuration is inside (aichatexporter-auth)
PASS: the artifact is not debuggable
PASS: the artifact sets no cleartext permission
PASS: versionCode 7 matches the build file
PASS: the artifact disallows backup
RESULT: FAIL (1 check(s))
exit=1
```

## Step 6 — Rebuild and re-gate (controller only)

```bash
./gradlew --stop
./gradlew :app:testDebugUnitTest
./gradlew assembleRelease -PpublicBuild=true --no-configuration-cache
bash scripts/security-gate.sh <release apk>
```

Result: suite green (`total=298 failures=0 errors=0`, from test-result XMLs), `BUILD SUCCESSFUL` for the release build, gate green again. Note: the `-PpublicBuild=true` rebuild produces `app-release-unsigned.apk` (the release signing config is stripped under `publicBuild`), so the final gate ran against that file. Output:

```
PASS: no AI key in any dex (3 files searched)
PASS: the account configuration is inside (aichatexporter-auth)
PASS: the artifact is not debuggable
PASS: the artifact sets no cleartext permission
PASS: versionCode 7 matches the build file
PASS: the artifact disallows backup
RESULT: PASS
exit=0
```

```
c3b1abe6914e2a77eb34eac75157a2bd *app/build/outputs/apk/release/app-release-unsigned.apk
```

## Strategy decision recorded in the build file (S7)

`missingGoogleServicesStrategy` stays **WARN**: a checkout without `app/google-services.json` must still build, and that file is deliberately not in the repository (`.gitignore:42`). The strictness lives in the gate — check 2 fails any artifact that carries no account configuration, which is the only state that actually ships.
