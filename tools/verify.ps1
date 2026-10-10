<#
.SYNOPSIS
  One-command green/red gate for AIChatExporter. Lane E3 of the hardening round 2026-10-10.

.DESCRIPTION
  Four stages, each reported with numbers, plus a summary table and a nonzero exit code when any
  stage failed:

    1  .\gradlew :app:assembleDebug --offline
    2  .\gradlew :app:testDebugUnitTest --rerun-tasks --offline   + parse the JUnit XML
    3  Persian/Arabic text-integrity scan over app\src and docs (reports the ZWNJ count)
    4  aapt dump badging on the built APK; package must be com.aistudio.aichatexporter.kxrtva

  Rules this script obeys (contract, not preference):
    - it Set-Location's to the repo root first, so it works from anywhere;
    - it never deletes anything and writes only under app\build\verify\ (the stage-3 baseline);
    - stage 4 reports SKIP, never a false PASS, when no aapt is installed;
    - no adb, no emulator, no install: device work is the controller's alone. Stage 3 and 4 touch
      no Gradle, so those two can be run by anyone; stages 1 and 2 invoke Gradle and per the round
      rules only the controller executes them.

  The script is pure ASCII on purpose: Windows PowerShell 5.1 decodes a BOM-less UTF-8 .ps1 as
  ANSI, so Persian literals inside the script would arrive mangled. Persian lives in the app and
  in docs, and stage 3 is what guards it.

.PARAMETER Stage
  Run only this stage (1..4). Default 0 runs all four.

.PARAMETER AcceptForeignBaseline
  Record today's stage-3 findings into app\build\verify\foreign-baseline.txt and treat them as
  pre-existing afterwards, so only NEW foreign characters can break the gate. A baseline that
  already exists is applied automatically and is always counted out loud on the console; it never
  hides anything silently.

.PARAMETER NoOffline
  Drop --offline from the Gradle calls (only if you deliberately want dependency resolution).

.EXAMPLE
  powershell -NoProfile -ExecutionPolicy Bypass -File tools\verify.ps1
  powershell -NoProfile -ExecutionPolicy Bypass -File tools\verify.ps1 -Stage 3
#>
[CmdletBinding()]
param(
    [int]$Stage = 0,
    [switch]$AcceptForeignBaseline,
    [switch]$NoOffline
)

$ErrorActionPreference = 'Continue'
$RepoRoot = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $RepoRoot

try {
    # Best effort only: the status lines below stay ASCII so this works on any codepage.
    [Console]::OutputEncoding = New-Object System.Text.UTF8Encoding($false)
} catch { }

$ExpectedPackage = 'com.aistudio.aichatexporter.kxrtva'
$WorkDir = Join-Path $RepoRoot 'app\build\verify'
$BaselinePath = Join-Path $WorkDir 'foreign-baseline.txt'
$OfflineFlag = '--offline'
if ($NoOffline) { $OfflineFlag = $null }

function Say([string]$Text) { Write-Host $Text }

function Rule([string]$Text) {
    $pad = 70 - $Text.Length
    if ($pad -lt 2) { $pad = 2 }
    Write-Host ''
    Write-Host ('== ' + $Text + ' ' + ('=' * $pad))
}

function Rel([string]$Path) {
    if (-not $Path.StartsWith($RepoRoot)) { return $Path }
    $r = $Path.Substring($RepoRoot.Length)
    return ($r -replace '^[\\/]', '' -replace '\\', '/')
}

# Lines are echoed with every non-ASCII byte turned into '?' so the console stays readable and the
# ASCII neighbours of a bad character are still visible (that is what an Edit must anchor on).
function Format-AsciiProbe([string]$Text) {
    $sb = New-Object System.Text.StringBuilder
    foreach ($ch in $Text.ToCharArray()) {
        if ([int]$ch -ge 0x20 -and [int]$ch -le 0x7E) { $null = $sb.Append($ch) } else { $null = $sb.Append('?') }
    }
    return $sb.ToString()
}

# ---------------------------------------------------------------------------------------------
# Stages 1/2: run gradlew.bat, echo a trimmed log, hand back exit code + markers.
# ---------------------------------------------------------------------------------------------
function Invoke-Gradle {
    param([string[]]$TaskList)

    $bat = Join-Path $RepoRoot 'gradlew.bat'
    if (-not (Test-Path -LiteralPath $bat)) {
        return [pscustomobject]@{ Exit = 999; Lines = 0; Success = $false; Cached = 0; UpToDate = 0; FailCount = 0 }
    }

    $callArgs = @($TaskList)
    if ($OfflineFlag) { $callArgs += $OfflineFlag }
    $callArgs += '--console=plain'

    Say ('    $ gradlew.bat ' + ($callArgs -join ' '))
    $lineCount = 0
    $success = $false
    $cached = 0
    $upToDate = 0
    $failCount = 0
    $shownFail = 0

    $prevEap = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'   # JVM stderr arrives as error records; keep the gate alive
    & $bat $callArgs 2>&1 | ForEach-Object {
        $line = "$_"
        $lineCount += 1
        if ($line -match 'BUILD SUCCESSFUL') { $success = $true }
        if ($line -match 'FROM-CACHE') { $cached += 1 }
        if ($line -match 'UP-TO-DATE') { $upToDate += 1 }
        if ($line -match 'BUILD FAILED' -or $line -match 'FAILURE:' -or $line -match '^\s*\*\s') {
            $failCount += 1
            if ($shownFail -lt 12) { Say ('    ! ' + (Format-AsciiProbe $line).Trim()); $shownFail += 1 }
        }
    }
    $exitCode = $LASTEXITCODE
    $ErrorActionPreference = $prevEap

    return [pscustomobject]@{
        Exit      = $exitCode
        Lines     = $lineCount
        Success   = $success
        Cached    = $cached
        UpToDate  = $upToDate
        FailCount = $failCount
    }
}

# ---------------------------------------------------------------------------------------------
# Stage 3: which codepoints may appear in authored Persian/English text. Everything else is a
# foreign-script or misdecoding suspect and is reported as file:line U+XXXX.
# ---------------------------------------------------------------------------------------------
$AllowedFragments = @(
    '\u0000-\u007F',                                       # ASCII, tab/CR/LF included
    '\u00A0', '\u00A1', '\u00A2', '\u00A5', '\u00A7', '\u00AB', '\u00BB', '\u00BF',
    '\u00B0', '\u00B1', '\u00B7', '\u00D7', '\u00F7',      # guillemets and common signs
    '\u0600-\u06FF',                                       # Arabic block: Persian letters (so the
                                                           # U+06AF and the U+0698 shape), the Persian
                                                           # digits 06F0-06F9, Arabic comma/semicolon,
                                                           # percent, hamza, kasra - all of it
    '\u2000-\u206F',                                       # general punctuation: en/em dash, curly
                                                           # quotes, bullet, ellipsis, ZWNJ U+200C,
                                                           # ZWJ U+200D, LRM/RLM U+200E/U+200F,
                                                           # bidi embedding and isolate marks
    '\u20AC', '\u2122', '\u2248', '\u2260', '\u2264', '\u2265',
    '\u2190-\u21FF', '\u2500-\u257F', '\u25A0-\u25FF',     # arrows, box drawing, geometric shapes
    '\u2600-\u27BF',                                       # misc symbols and dingbats
    '\uFE0E', '\uFE0F', '\uFEFF'                           # variation selectors, BOM/ZWNBSP
)
# Supplementary planes (emoji) are allowed, but NOT through a regex alternation: a branch before a
# negated class makes .NET test both branches at every position (measured ~3x slower on a 170 KB
# document) and it lost the tie here anyway. Surrogate pairs are resolved in the loop below with
# ConvertToUtf32, which also lets the run count the emoji it accepted instead of hiding them.
$DisallowedPattern = '[^' + ($AllowedFragments -join '') + ']'
$DisallowedRex = New-Object System.Text.RegularExpressions.Regex($DisallowedPattern)
$NonAsciiRex = New-Object System.Text.RegularExpressions.Regex('[^\u0000-\u007F]')
$PersianBlockRex = New-Object System.Text.RegularExpressions.Regex('[\u0600-\u06FF]')
$ZwnjRex = New-Object System.Text.RegularExpressions.Regex('\u200C')
$NewlineRex = New-Object System.Text.RegularExpressions.Regex('\n')

# Captured device evidence and scraped page dumps legitimately hold any script in the world. They
# are specimens, not authored text: stage 3 counts them as SKIPPED and names them, never silently.
$CapturedScopes = @('docs/reports/raw/', 'app/src/test/resources/', 'app/src/androidTest/assets/')

# Characters proven intentional in a source file. The waiver needs an ASCII anchor on the offending
# line and only ever excuses Latin letters, so an injected Devanagari/CJK/Kana/Hangul character on
# that same line still breaks the gate.
$Waivers = @(
    [pscustomobject]@{
        Path   = 'app/src/main/java/com/example/domain/bidi/BidiEngine.kt'
        Anchor = 'Regex('
        Reason = 'the mojibake repair table matches these Latin-1 byte shapes on purpose'
    }
)

function Get-ScanFiles {
    $exts = @('.kt', '.kts', '.xml', '.md', '.gradle', '.toml', '.pro', '.sh', '.ps1', '.txt', '.html', '.css', '.java', '.json')
    $found = New-Object System.Collections.ArrayList
    foreach ($base in @('app\src', 'docs')) {
        $full = Join-Path $RepoRoot $base
        if (-not (Test-Path -LiteralPath $full)) { continue }
        foreach ($fi in (Get-ChildItem -LiteralPath $full -Recurse -File)) {
            if ($exts -notcontains $fi.Extension.ToLower()) { continue }
            $null = $found.Add($fi)
        }
    }
    return $found
}

function Read-Utf8Strict {
    param([string]$Path)
    $bytes = [System.IO.File]::ReadAllBytes($Path)
    $enc = New-Object System.Text.UTF8Encoding($false, $true)
    try {
        return [pscustomobject]@{ Ok = $true; Text = $enc.GetString($bytes); Bytes = $bytes.Length }
    } catch {
        return [pscustomobject]@{ Ok = $false; Text = ''; Bytes = $bytes.Length }
    }
}

function Resolve-Aapt {
    $sdkRoots = @()
    $props = Join-Path $RepoRoot 'local.properties'
    if (Test-Path -LiteralPath $props) {
        foreach ($line in (Get-Content -LiteralPath $props)) {
            if ($line -match '^\s*sdk\.dir\s*=\s*(.+)$') {
                $v = $matches[1].Trim().Replace('\:', ':').Replace('\\', '\')
                $sdkRoots += $v
            }
        }
    }
    if ($env:ANDROID_HOME) { $sdkRoots += $env:ANDROID_HOME }
    if ($env:ANDROID_SDK_ROOT) { $sdkRoots += $env:ANDROID_SDK_ROOT }
    if ($env:LOCALAPPDATA) { $sdkRoots += (Join-Path $env:LOCALAPPDATA 'Android\Sdk') }

    foreach ($root in $sdkRoots) {
        if (-not $root) { continue }
        $bt = Join-Path $root 'build-tools'
        if (-not (Test-Path -LiteralPath $bt)) { continue }
        $dirs = @(Get-ChildItem -LiteralPath $bt -Directory -ErrorAction SilentlyContinue |
            Where-Object { Test-Path -LiteralPath (Join-Path $_.FullName 'aapt.exe') } |
            Sort-Object -Property Name -Descending)
        foreach ($d in $dirs) {
            return [pscustomobject]@{ Found = $true; Path = (Join-Path $d.FullName 'aapt.exe'); BuildTools = $d.Name; SdkRoot = $root }
        }
    }
    return [pscustomobject]@{ Found = $false; Path = ''; BuildTools = ''; SdkRoot = '' }
}

# =============================================================================================
$summary = New-Object System.Collections.ArrayList
$startedAt = Get-Date

function WantStage { param([int]$N) return ($Stage -eq 0 -or $Stage -eq $N) }

Say ('AIChatExporter verify gate | started ' + $startedAt.ToString('yyyy-MM-dd HH:mm:ss') + ' | cwd ' + $RepoRoot)
Say ('PowerShell ' + $PSVersionTable.PSVersion + ' | stages: ' + $(if ($Stage -eq 0) { '1 2 3 4' } else { [string]$Stage }) + ' | offline: ' + $(if ($OfflineFlag) { 'yes' } else { 'no' }))

# ------------------------------------------------------------------ stage 1: assembleDebug ----
if (WantStage 1) {
    Rule 'STAGE 1/4  assembleDebug'
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    $r = Invoke-Gradle -TaskList @(':app:assembleDebug')
    $sw.Stop()
    $exit = [int]$r.Exit
    $pass = ($exit -eq 0 -and $r.Success)
    Say ('    exit=' + $exit + ' log_lines=' + $r.Lines + ' FROM-CACHE=' + $r.Cached + ' UP-TO-DATE=' + $r.UpToDate + ' failure_lines=' + $r.FailCount + ' elapsed=' + [Math]::Round($sw.Elapsed.TotalSeconds, 1) + 's')
    $null = $summary.Add([pscustomobject]@{
        S = 1; Name = 'assembleDebug'; Result = $(if ($pass) { 'PASS' } else { 'FAIL' })
        Numbers = ('exit={0} log={1} cache={2} uptodate={3} {4}s' -f $exit, $r.Lines, $r.Cached, $r.UpToDate, [Math]::Round($sw.Elapsed.TotalSeconds, 1))
    })
}

# ------------------------------------------------------------------ stage 2: unit tests ------
if (WantStage 2) {
    Rule 'STAGE 2/4  testDebugUnitTest (--rerun-tasks: a cached green cannot lie)'
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    $runFrom = Get-Date
    $r = Invoke-Gradle -TaskList @(':app:testDebugUnitTest', '--rerun-tasks')
    $sw.Stop()
    $exit = [int]$r.Exit
    $resultsDir = Join-Path $RepoRoot 'app\build\test-results\testDebugUnitTest'
    $tests = 0; $failures = 0; $errors = 0; $skipped = 0; $suites = 0; $stale = 0; $fresh = 0
    if (Test-Path -LiteralPath $resultsDir) {
        foreach ($fi in @(Get-ChildItem -LiteralPath $resultsDir -Filter '*.xml' -File)) {
            if ($fi.LastWriteTime -ge $runFrom) { $fresh += 1 } else { $stale += 1 }
            $xml = New-Object System.Xml.XmlDocument
            try {
                $xml.Load($fi.FullName)
            } catch {
                Say ('    UNREADABLE ' + (Rel $fi.FullName) + ' | ' + $_.Exception.Message)
                $errors += 1
                continue
            }
            foreach ($node in $xml.GetElementsByTagName('testsuite')) {
                $t = 0; $f = 0; $e = 0; $s = 0
                [void][int]::TryParse($node.GetAttribute('tests'), [ref]$t)
                [void][int]::TryParse($node.GetAttribute('failures'), [ref]$f)
                [void][int]::TryParse($node.GetAttribute('errors'), [ref]$e)
                [void][int]::TryParse($node.GetAttribute('skipped'), [ref]$s)
                $suites += 1; $tests += $t; $failures += $f; $errors += $e; $skipped += $s
                Say ('    ' + (Rel $fi.FullName) + ' | tests=' + $t + ' failures=' + $f + ' errors=' + $e + ' skipped=' + $s + ' | written ' + $fi.LastWriteTime.ToString('yyyy-MM-dd HH:mm:ss'))
            }
        }
    } else {
        Say ('    MISSING results dir: ' + (Rel $resultsDir))
    }
    $pass = ($exit -eq 0 -and $r.Success -and $suites -gt 0 -and $tests -gt 0 -and $failures -eq 0 -and $errors -eq 0 -and $fresh -gt 0)
    Say ('    exit=' + $exit + ' suites=' + $suites + ' tests=' + $tests + ' failures=' + $failures + ' errors=' + $errors + ' skipped=' + $skipped + ' fresh_xml=' + $fresh + ' stale_xml=' + $stale + ' elapsed=' + [Math]::Round($sw.Elapsed.TotalSeconds, 1) + 's')
    if ($fresh -eq 0 -and $stale -gt 0) { Say '    ! no XML was rewritten by this run, so --rerun-tasks did not take effect' }
    $null = $summary.Add([pscustomobject]@{
        S = 2; Name = 'testDebugUnitTest'; Result = $(if ($pass) { 'PASS' } else { 'FAIL' })
        Numbers = ('exit={0} tests={1} fail={2} err={3} skip={4} suites={5} fresh={6}' -f $exit, $tests, $failures, $errors, $skipped, $suites, $fresh)
    })
}

# ------------------------------------------------- stage 3: Persian/Arabic text integrity ----
if (WantStage 3) {
    Rule 'STAGE 3/4  Persian/Arabic text integrity over app\src and docs'
    $sw = [System.Diagnostics.Stopwatch]::StartNew()

    $baseline = @{}
    if (Test-Path -LiteralPath $BaselinePath) {
        foreach ($line in (Get-Content -LiteralPath $BaselinePath)) {
            if (-not $line) { continue }
            $parts = $line.Split('|')
            if ($parts.Count -ge 3) { $baseline[$parts[0] + '|' + $parts[1]] = [int]$parts[2] }
        }
        Say ('    baseline in force: ' + (Rel $BaselinePath) + ' (' + $baseline.Count + ' rows pre-existing)')
    }

    $files = Get-ScanFiles
    $scanned = 0; $skippedFiles = 0; $decodeFails = 0; $bomFiles = 0
    $nonAscii = 0; $persianChars = 0; $zwnj = 0; $waived = 0; $emojiAllowed = 0
    $keys = New-Object System.Collections.ArrayList
    $counts = @{}
    $samples = @{}
    $skipNames = New-Object System.Collections.ArrayList

    foreach ($fi in $files) {
        $rel = Rel $fi.FullName
        $captured = $false
        foreach ($scope in $CapturedScopes) {
            if ($rel.StartsWith($scope, [System.StringComparison]::OrdinalIgnoreCase)) { $captured = $true; break }
        }
        if ($captured) { $skippedFiles += 1; $null = $skipNames.Add($rel); continue }

        $read = Read-Utf8Strict -Path $fi.FullName
        if (-not $read.Ok) {
            $decodeFails += 1
            $key = $rel + '|NOT-UTF8'
            if (-not $counts.ContainsKey($key)) { $counts[$key] = 0; $samples[$key] = ($rel + ': NOT VALID UTF-8 (' + $read.Bytes + ' bytes)'); $null = $keys.Add($key) }
            $counts[$key] += 1
            continue
        }
        $scanned += 1
        $text = $read.Text
        $nonAscii += $NonAsciiRex.Matches($text).Count
        $persianChars += $PersianBlockRex.Matches($text).Count
        $zwnj += $ZwnjRex.Matches($text).Count

        if ($text.Length -gt 0 -and [int][char]$text[0] -eq 0xFEFF) {
            $bomFiles += 1
            $key = $rel + '|BOM'
            if (-not $counts.ContainsKey($key)) { $counts[$key] = 0; $samples[$key] = ($rel + ':1 UTF-8 BOM at byte 0'); $null = $keys.Add($key) }
            $counts[$key] += 1
        }

        $waiver = $null
        foreach ($w in $Waivers) { if ($w.Path -eq $rel) { $waiver = $w; break } }

        $skipUntil = -1
        foreach ($m in $DisallowedRex.Matches($text)) {
            $unit = [int][char]$m.Value[0]
            if ($unit -ge 0xDC00 -and $unit -le 0xDFFF -and $m.Index -lt $skipUntil) { continue }

            $code = $unit
            if ($unit -ge 0xD800 -and $unit -le 0xDBFF -and ($m.Index + 1) -lt $text.Length) {
                try {
                    # Real codepoint of the pair, so a plane-1 character is judged as one character
                    # and its low surrogate is not reported a second time.
                    $code = [System.Char]::ConvertToUtf32($text, $m.Index)
                    $skipUntil = $m.Index + 2
                } catch { $code = $unit }
            }
            if ($code -ge 0x1F000 -and $code -le 0x1FAFF) {
                $emojiAllowed += 1
                continue
            }
            $hex = 'U+{0:X4}' -f $code

            $lineStart = $text.LastIndexOf("`n", $m.Index) + 1
            $lineEnd = $text.IndexOf("`n", $m.Index)
            if ($lineEnd -lt 0) { $lineEnd = $text.Length }
            $lineRaw = $text.Substring($lineStart, ($lineEnd - $lineStart)).TrimEnd([char]0x000D)

            # The waiver excuses Latin-1/Latin-Extended shapes only, and only on a line that carries
            # the anchor, so an injected Devanagari/CJK/Kana/Hangul character still counts.
            $latinRange = ($code -ge 0x80 -and $code -le 0x24F)
            if ($waiver -and $latinRange -and $lineRaw.Contains($waiver.Anchor)) { $waived += 1; continue }

            $key = $rel + '|' + $hex
            if (-not $counts.ContainsKey($key)) {
                $counts[$key] = 0
                $lineNo = $NewlineRex.Matches($text.Substring(0, $lineStart)).Count + 1
                $trimmed = (Format-AsciiProbe $lineRaw).Trim()
                if ($trimmed.Length -gt 64) { $trimmed = $trimmed.Substring(0, 64) }
                $samples[$key] = ($rel + ':' + $lineNo + ' ' + $hex + ' | ' + $trimmed)
                $null = $keys.Add($key)
            }
            $counts[$key] += 1
        }
    }

    $preexisting = 0; $newKeys = 0
    $rows = New-Object System.Collections.ArrayList
    foreach ($key in $keys) {
        if ($baseline.ContainsKey($key)) { $preexisting += $counts[$key]; continue }
        $newKeys += 1
        $null = $rows.Add(($samples[$key] + '  [' + $counts[$key] + 'x]'))
    }

    Say ('    in scope=' + $files.Count + ' files | scanned=' + $scanned + ' | captured-artifact files skipped=' + $skippedFiles)
    if ($skipNames.Count -gt 0) {
        $n = [Math]::Min(5, $skipNames.Count)
        Say ('    skipped paths: ' + (($skipNames | Select-Object -First $n) -join ', ') + $(if ($skipNames.Count -gt $n) { ' (+' + ($skipNames.Count - $n) + ' more)' } else { '' }))
    }
    Say ('    non-ascii code units=' + $nonAscii + ' | persian/arabic block chars=' + $persianChars + ' | ZWNJ U+200C count=' + $zwnj)
    Say ('    proven-intentional waiver hits=' + $waived + ' | emoji-plane chars accepted=' + $emojiAllowed + ' | baseline pre-existing chars=' + $preexisting + ' | not-utf8=' + $decodeFails + ' | bom=' + $bomFiles)
    if ($rows.Count -gt 0) {
        Say ('    DISALLOWED CHARACTERS - ' + $rows.Count + ' file+codepoint rows:')
        $shown = 0
        foreach ($row in $rows) {
            if ($shown -ge 25) { Say ('      ... ' + ($rows.Count - $shown) + ' more rows'); break }
            Say ('      ' + $row)
            $shown += 1
        }
    }

    $findings = $newKeys
    if ($AcceptForeignBaseline) {
        if (-not (Test-Path -LiteralPath $WorkDir)) { $null = New-Item -ItemType Directory -Path $WorkDir -Force }
        $lines = @()
        $stamp = (Get-Date).ToString('yyyy-MM-dd HH:mm:ss')
        foreach ($key in $keys) {
            $parts = $key.Split('|')
            $lines += ($parts[0] + '|' + $parts[1] + '|' + $counts[$key] + '|recorded ' + $stamp)
        }
        Set-Content -LiteralPath $BaselinePath -Value $lines -Encoding ASCII
        Say ('    baseline written: ' + (Rel $BaselinePath) + ' (' + $lines.Count + ' rows); later runs count these as pre-existing')
        $findings = 0
    }

    $sw.Stop()
    $pass = ($findings -eq 0 -and $scanned -gt 0)
    Say ('    findings=' + $findings + ' | elapsed=' + [Math]::Round($sw.Elapsed.TotalSeconds, 1) + 's')
    if (-not $pass) {
        Say '    ! the scan is strict by design. Repair the characters, or run -AcceptForeignBaseline'
        Say '      once to record them as pre-existing so only NEW damage can break the gate.'
    }
    $null = $summary.Add([pscustomobject]@{
        S = 3; Name = 'persian-integrity'; Result = $(if ($pass) { 'PASS' } else { 'FAIL' })
        Numbers = ('files={0} zwnj={1} persian={2} findings={3} waived={4} baseline={5}' -f $scanned, $zwnj, $persianChars, $findings, $waived, $preexisting)
    })
}

# ------------------------------------------------------------------ stage 4: aapt badging ------
$stage1Failed = $false
foreach ($s in $summary) { if ($s.S -eq 1 -and $s.Result -eq 'FAIL') { $stage1Failed = $true } }
if (WantStage 4) {
    Rule 'STAGE 4/4  aapt dump badging'
    $aapt = Resolve-Aapt
    $apk = Join-Path $RepoRoot 'app\build\outputs\apk\debug\app-debug.apk'
    if (-not $aapt.Found) {
        Say '    SKIP: no aapt.exe was found. Searched sdk.dir from local.properties, ANDROID_HOME,'
        Say '    ANDROID_SDK_ROOT and %LOCALAPPDATA%\Android\Sdk\build-tools. A SKIP is not a PASS:'
        Say '    install build-tools, then re-run with -Stage 4.'
        $null = $summary.Add([pscustomobject]@{ S = 4; Name = 'aapt-badging'; Result = 'SKIP'; Numbers = 'aapt=absent' })
    } elseif (-not (Test-Path -LiteralPath $apk)) {
        if ($stage1Failed) {
            Say ('    SKIP: stage 1 did not produce an APK (' + (Rel $apk) + ')')
            $null = $summary.Add([pscustomobject]@{ S = 4; Name = 'aapt-badging'; Result = 'SKIP'; Numbers = 'apk=absent-after-failed-build' })
        } else {
            Say ('    FAIL: expected APK is missing: ' + (Rel $apk))
            $null = $summary.Add([pscustomobject]@{ S = 4; Name = 'aapt-badging'; Result = 'FAIL'; Numbers = 'apk=missing' })
        }
    } else {
        Say ('    aapt: ' + (Rel $aapt.Path) + ' | build-tools ' + $aapt.BuildTools)
        Say ('    $ aapt dump badging ' + (Rel $apk))
        $out = & $aapt.Path dump badging $apk 2>&1
        $aaptExit = $LASTEXITCODE
        $blob = ($out | ForEach-Object { "$_" }) -join "`n"
        $pkg = ''; $vc = ''; $vn = ''; $ts = ''; $sv = ''; $lbl = ''; $lines = 0
        if ($blob -match "package: name='([^']+)'") { $pkg = $matches[1] }
        if ($blob -match "versionCode='([^']*)'") { $vc = $matches[1] }
        if ($blob -match "versionName='([^']*)'") { $vn = $matches[1] }
        # aapt writes the SDK levels as "sdkVersion:'24'" / "targetSdkVersion:'36'" on their own
        # lines. Anchored with (?m)^ so that the package line's compileSdkVersion='36' cannot be
        # mistaken for minSdk (case-insensitive -match made that happen once during authoring).
        $mSv = [regex]::Match($blob, "(?m)^sdkVersion:'([^']*)'")
        if ($mSv.Success) { $sv = $mSv.Groups[1].Value }
        $mTs = [regex]::Match($blob, "(?m)^targetSdkVersion:'([^']*)'")
        if ($mTs.Success) { $ts = $mTs.Groups[1].Value }
        $mLbl = [regex]::Match($blob, "(?m)^application-label:'([^']*)'")
        if ($mLbl.Success) { $lbl = $mLbl.Groups[1].Value }
        $lines = @($blob -split "`n").Count
        $pkgOk = ($pkg -eq $ExpectedPackage)
        Say ('    package=' + $pkg + ' | versionCode=' + $vc + ' versionName=' + $vn + ' | minSdk=' + $sv + ' targetSdk=' + $ts + ' | badging_lines=' + $lines + ' | exit=' + $aaptExit)
        if ($lbl -and ($lbl -match '^[\x20-\x7E]*$')) { Say ('    application-label=' + $lbl) }
        if (-not $pkgOk) { Say ('    expected package: ' + $ExpectedPackage) }
        $pass = ($aaptExit -eq 0 -and $pkgOk)
        $null = $summary.Add([pscustomobject]@{
            S = 4; Name = 'aapt-badging'; Result = $(if ($pass) { 'PASS' } else { 'FAIL' })
            Numbers = ('exit={0} pkg={1} minSdk={2} targetSdk={3}' -f $aaptExit, $(if ($pkgOk) { 'match' } else { 'MISMATCH' }), $sv, $ts)
        })
    }
}

# ------------------------------------------------------------------ summary ------------------
Rule 'SUMMARY'
$rows = 0; $passes = 0; $fails = 0; $skips = 0
foreach ($s in $summary) {
    $rows += 1
    if ($s.Result -eq 'FAIL') { $fails += 1 } elseif ($s.Result -eq 'SKIP') { $skips += 1 } else { $passes += 1 }
}
$head = '{0,-4} {1,-20} {2,-6} {3}' -f 'Stg', 'Name', 'Result', 'Numbers'
Say $head
Say (('-' * $head.Length))
foreach ($s in $summary) { Say ('{0,-4} {1,-20} {2,-6} {3}' -f $s.S, $s.Name, $s.Result, $s.Numbers) }
Say ('' )
Say ('stages=' + $rows + ' PASS=' + $passes + ' FAIL=' + $fails + ' SKIP=' + $skips + ' | elapsed=' + [Math]::Round(((Get-Date) - $startedAt).TotalSeconds, 1) + 's')

# ------------------------------------------------------------------ device helpers -----------
Rule 'DEVICE SIDE (never from this script)'
foreach ($helper in @('tools\uidump.sh')) {
    if (Test-Path -LiteralPath (Join-Path $RepoRoot $helper)) {
        Say ('    present: ' + $helper + '  - adb uiautomator dump plus a text-node printer for the pinned device')
    } else {
        Say ('    absent:  ' + $helper)
    }
}
Say '    Device work is controller-only: this script runs no adb, no emulator, no install, and an'
Say '    agent must not run stages 1 or 2 either (Gradle belongs to the controller).'

if ($fails -gt 0) { Say ('RESULT: FAIL (' + $fails + ' stage(s) red)'); exit 1 }
if ($skips -gt 0) { Say ('RESULT: PASS with ' + $skips + ' SKIP (a skipped stage proved nothing)'); exit 0 }
Say 'RESULT: PASS (all executed stages green)'; exit 0
