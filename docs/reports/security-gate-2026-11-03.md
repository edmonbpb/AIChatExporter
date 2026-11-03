# Security gate run — 2026-11-03

The artifact proves itself before it is handed out. Every check below is measured on the **built APK**, not the source, because the source being clean is not what a user installs.

Gate: `scripts/security-gate.sh` (six checks: no AI key in dex, account configuration present, not debuggable, no cleartext, versionCode match, backup disallowed).

## Step 2 — Gate run on the release artifact

Command:

```bash
bash scripts/security-gate.sh app/build/outputs/apk/release/app-release.apk
```

Output (controller fills verbatim):

```
PENDING: controller run output — expected six PASS lines and RESULT: PASS
```

APK under test, md5sum:

```
PENDING: md5sum app/build/outputs/apk/release/app-release.apk
```

## Step 3 — The gate can go red (two independent breaks, measured and restored)

### (a) A debuggable artifact must fail

Command:

```bash
bash scripts/security-gate.sh app/build/outputs/apk/debug/app-debug.apk; echo "exit=$?"
```

Expected: exit `1`, naming `android:debuggable`. Output:

```
PENDING: controller run output
```

### (b) A planted key must fail

Command (per task brief, artifacts kept outside the repo, `/tmp` copies deleted afterwards):

```bash
cp app/build/outputs/apk/release/app-release.apk /tmp/planted.apk
printf 'AIzaSy%s\n' 'PlantedKeyForTheGateTest0000000000' > /tmp/planted.dex
(cd /tmp && unzip -o -q planted.apk -d planteddir && cp planteddir/classes.dex /tmp/original-classes.dex \
  && cat planteddir/classes.dex /tmp/planted.dex > planteddir/classes2.dex && (cd planteddir && zip -q -r ../planted-new.apk .) )
bash scripts/security-gate.sh /tmp/planted-new.apk; echo "exit=$?"
```

Expected: exit `1`, naming the key count. Output:

```
PENDING: controller run output
```

## Final re-run on the shipped release

Expected: `RESULT: PASS` again after the break artifacts are removed. Output:

```
PENDING: controller run output
```

## Step 6 — Rebuild and re-gate (controller only)

```bash
./gradlew --stop
./gradlew :app:testDebugUnitTest
./gradlew assembleRelease -PpublicBuild=true --no-configuration-cache
bash scripts/security-gate.sh app/build/outputs/apk/release/app-release.apk
```

Expected: test suite green, `BUILD SUCCESSFUL`, `RESULT: PASS`. Output:

```
PENDING: controller run output
```

## Strategy decision recorded in the build file (S7)

`missingGoogleServicesStrategy` stays **WARN**: a checkout without `app/google-services.json` must still build, and that file is deliberately not in the repository (`.gitignore:42`). The strictness lives in the gate — check 2 fails any artifact that carries no account configuration, which is the only state that actually ships.
