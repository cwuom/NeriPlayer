param(
    [string]$DeviceId = "",
    [ValidateSet("debug", "release")]
    [string]$BuildVariant = "debug",
    [switch]$SkipBuild,
    [int]$ColdStartRuns = 5,
    [int]$PlaybackPollTimeoutMs = 10000
)

$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $PSScriptRoot
$packageName = "moe.ouom.neriplayer"
$mainActivity = "moe.ouom.neriplayer.activity.MainActivity"
$receiverComponent = "$packageName/.testing.DebugCookieImportReceiver"
$instrumentationRunner = "$packageName.test/androidx.test.runner.AndroidJUnitRunner"
$permissionBootstrapClass = "moe.ouom.neriplayer.testing.PermissionBootstrapTest"
$reportDir = Join-Path $repoRoot ".report"
$timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
$reportPath = Join-Path $reportDir "adb-$BuildVariant-perf-$timestamp.json"
$hostPlaybackFixturePath = Join-Path $reportDir "neri_test_tone_20s.wav"
$devicePlaybackFixturePath = "/sdcard/Music/neri_test_tone_20s.wav"
$playbackFixtureDisplayName = [System.IO.Path]::GetFileName($hostPlaybackFixturePath)

New-Item -ItemType Directory -Force -Path $reportDir | Out-Null

function Invoke-Adb {
    param(
        [Parameter(ValueFromRemainingArguments = $true)]
        [string[]]$Args
    )

    if ($DeviceId) {
        & adb -s $DeviceId @Args
    } else {
        & adb @Args
    }
}

function Ensure-InteractiveDeviceState {
    Invoke-Adb -Args @("shell", "cmd", "power", "wakeup", "0") | Out-Null
    try {
        Invoke-Adb -Args @("shell", "wm", "dismiss-keyguard") | Out-Null
    } catch {
        Write-Warning "dismiss keyguard failed: $($_.Exception.Message)"
    }
    Start-Sleep -Milliseconds 800
}

function Get-LatestReleaseApk {
    $releaseDir = Join-Path $repoRoot "app\build\outputs\apk\release"
    return Get-ChildItem -Path $releaseDir -Filter *.apk -File |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1
}

function Get-LatestDebugApk {
    $debugDir = Join-Path $repoRoot "app\build\outputs\apk\debug"
    return Get-ChildItem -Path $debugDir -Filter *.apk -File |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1
}

function Get-LatestDebugAndroidTestApk {
    $androidTestDir = Join-Path $repoRoot "app\build\outputs\apk\androidTest\debug"
    if (-not (Test-Path $androidTestDir)) {
        return $null
    }
    return Get-ChildItem -Path $androidTestDir -Filter *.apk -File |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1
}

function Install-ApkToTarget {
    param(
        [Parameter(Mandatory = $true)]
        [string]$ApkPath
    )

    if ($DeviceId) {
        & adb -s $DeviceId install -r $ApkPath | Out-Null
    } else {
        & adb install -r $ApkPath | Out-Null
    }
}

function Ensure-PlaybackFixture {
    if (-not (Test-Path $hostPlaybackFixturePath)) {
        @"
import math
import struct
import wave
from pathlib import Path

path = Path(r"$hostPlaybackFixturePath")
path.parent.mkdir(parents=True, exist_ok=True)
framerate = 44100
seconds = 20
amplitude = 0.25
frequency = 440.0
with wave.open(str(path), "wb") as wav:
    wav.setnchannels(1)
    wav.setsampwidth(2)
    wav.setframerate(framerate)
    for i in range(framerate * seconds):
        sample = int(amplitude * 32767 * math.sin(2 * math.pi * frequency * i / framerate))
        wav.writeframesraw(struct.pack("<h", sample))
"@ | python - | Out-Null
    }

    Invoke-Adb -Args @("push", $hostPlaybackFixturePath, $devicePlaybackFixturePath) | Out-Null
    Invoke-Adb -Args @(
        "shell", "am", "broadcast",
        "-a", "android.intent.action.MEDIA_SCANNER_SCAN_FILE",
        "-d", "file://$devicePlaybackFixturePath"
    ) | Out-Null
    Start-Sleep -Seconds 2

    $rows = Invoke-Adb -Args @(
        "shell", "content", "query",
        "--uri", "content://media/external/audio/media",
        "--projection", "_id:_display_name"
    )
    $row = $rows |
        ForEach-Object { $_.ToString() } |
        Select-String -Pattern ([regex]::Escape($playbackFixtureDisplayName)) |
        Select-Object -First 1
    if ($null -eq $row) {
        throw "playback fixture not found in MediaStore: $playbackFixtureDisplayName"
    }
    $rowText = $row.ToString()
    if ($rowText -notmatch "_id=(\d+)") {
        throw "unable to parse MediaStore id from row: $rowText"
    }
    return "content://media/external/audio/media/$($Matches[1])"
}

function Import-Cookies {
    param(
        [string]$Platform,
        [string]$FileName
    )

    $cookiePath = Join-Path $repoRoot ".ck\$FileName"
    if (-not (Test-Path $cookiePath)) {
        return @{
            platform = $Platform
            skipped = $true
            reason = "missing file"
        }
    }

    $cookie = Get-Content $cookiePath -Raw
    $cookieBase64 = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($cookie))
    $output = Invoke-Adb -Args @(
        "shell", "am", "broadcast",
        "-a", "moe.ouom.neriplayer.debug.IMPORT_AUTH",
        "-n", $receiverComponent,
        "--es", "platform", $Platform,
        "--es", "cookie_base64", $cookieBase64
    )

    return @{
        platform = $Platform
        skipped = $false
        output = ($output -join "`n")
    }
}

function Measure-ExternalAudioPlayback {
    param(
        [string]$PlaybackUri
    )

    Invoke-Adb -Args @("logcat", "-c") | Out-Null
    Invoke-Adb -Args @("shell", "am", "force-stop", $packageName) | Out-Null
    Ensure-InteractiveDeviceState

    $launchStart = Get-Date
    $launch = Invoke-Adb -Args @(
        "shell", "am", "start", "-W",
        "--grant-read-uri-permission",
        "-a", "android.intent.action.VIEW",
        "-d", $PlaybackUri,
        "-t", "audio/wav",
        "-n", "$packageName/$mainActivity"
    )

    $playingAtMs = $null
    $lastState = $null
    $pollCount = [Math]::Ceiling($PlaybackPollTimeoutMs / 250.0)
    for ($i = 0; $i -lt $pollCount; $i++) {
        Start-Sleep -Milliseconds 250
        $sessionDump = (Invoke-Adb -Args @("shell", "dumpsys", "media_session")) -join "`n"
        $stateMatch = [regex]::Match($sessionDump, "state=PlaybackState \{state=([A-Z_]+)\((\d+)\)")
        if ($stateMatch.Success) {
            $lastState = $stateMatch.Value
        }
        if (
            $sessionDump.Contains("package=$packageName") -and
            $sessionDump.Contains("state=PlaybackState {state=PLAYING(3)")
        ) {
            $playingAtMs = [int]((Get-Date) - $launchStart).TotalMilliseconds
            break
        }
    }

    $reachedPlaying = $playingAtMs -ne $null
    $logSample = (Invoke-Adb -Args @("logcat", "-d", "-v", "brief")) |
        Select-String -Pattern "MainActivity|NERI-APS|NERI-Player|FATAL EXCEPTION" |
        Select-Object -First 120 |
        ForEach-Object { $_.ToString() }

    return @{
        launchOutput = ($launch -join "`n")
        reachedPlaying = $reachedPlaying
        playingLatencyMs = if ($reachedPlaying) { $playingAtMs } else { $null }
        lastObservedState = $lastState
        logSample = ($logSample -join "`n")
        fixtureUri = $PlaybackUri
    }
}

function Invoke-PermissionBootstrap {
    if ($BuildVariant -ne "debug") {
        return
    }

    $instrumentations = (Invoke-Adb -Args @("shell", "pm", "list", "instrumentation")) -join "`n"
    if ($instrumentations -notmatch [regex]::Escape($instrumentationRunner)) {
        Write-Warning "permission bootstrap skipped because instrumentation is not installed: $instrumentationRunner"
        return
    }

    Ensure-InteractiveDeviceState
    $bootstrapOutput = Invoke-Adb -Args @(
        "shell", "am", "instrument",
        "-w", "-r",
        "-e", "class", $permissionBootstrapClass,
        $instrumentationRunner
    )
    $bootstrapText = ($bootstrapOutput -join "`n")
    if ($bootstrapText -notmatch "OK \(") {
        throw "permission bootstrap failed: $bootstrapText"
    }
}

if (-not $SkipBuild) {
    Push-Location $repoRoot
    try {
        if ($BuildVariant -eq "release") {
            & .\gradlew.bat assembleRelease
        } else {
            & .\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest
        }
    } finally {
        Pop-Location
    }
}

if ($BuildVariant -eq "release") {
    $releaseApk = Get-LatestReleaseApk
    if ($null -eq $releaseApk) {
        throw "release apk was not found under app/build/outputs/apk/release"
    }
    Install-ApkToTarget -ApkPath $releaseApk.FullName
} else {
    $debugApk = Get-LatestDebugApk
    if ($null -eq $debugApk) {
        throw "debug apk was not found under app/build/outputs/apk/debug"
    }
    Install-ApkToTarget -ApkPath $debugApk.FullName
    $debugAndroidTestApk = Get-LatestDebugAndroidTestApk
    if ($null -ne $debugAndroidTestApk) {
        Install-ApkToTarget -ApkPath $debugAndroidTestApk.FullName
    } else {
        Write-Warning "debug androidTest apk was not found under app/build/outputs/apk/androidTest/debug"
    }
}

$installState = (Invoke-Adb -Args @("shell", "pm", "list", "packages", $packageName)) -join "`n"
if ($installState -notmatch $packageName) {
    throw "$BuildVariant app is not installed: $packageName"
}

$resolvedActivity = (Invoke-Adb -Args @(
    "shell", "cmd", "package", "resolve-activity",
    "--brief",
    "$packageName/$mainActivity"
)) -join "`n"
if (
    $resolvedActivity -notmatch [regex]::Escape("$packageName/$mainActivity") -and
    $resolvedActivity -notmatch [regex]::Escape("$packageName/.activity.MainActivity")
) {
    throw "main activity is not resolvable on target device: $resolvedActivity"
}

try {
    & {
        Invoke-Adb -Args @(
            "shell", "pm", "grant",
            $packageName,
            "android.permission.POST_NOTIFICATIONS"
        )
    } 2>$null | Out-Null
} catch {
    Write-Warning "grant POST_NOTIFICATIONS failed; falling back to appops"
    Invoke-Adb -Args @(
        "shell", "cmd", "appops", "set",
        $packageName,
        "POST_NOTIFICATION",
        "allow"
    ) | Out-Null
}

Ensure-InteractiveDeviceState
Invoke-PermissionBootstrap

$imports = @()
if ($BuildVariant -eq "debug") {
    Invoke-Adb -Args @(
        "shell", "am", "broadcast",
        "-a", "moe.ouom.neriplayer.debug.CLEAR_AUTH",
        "-n", $receiverComponent,
        "--es", "platform", "all"
    ) | Out-Null

    $imports = @(
        Import-Cookies -Platform "bili" -FileName "bili-cookie.txt"
        Import-Cookies -Platform "netease" -FileName "netease-cookie.txt"
        Import-Cookies -Platform "youtube" -FileName "youtube-cookie.txt"
    )
} else {
    $imports = @(
        @{
            platform = "all"
            skipped = $true
            reason = "release variant has no debug auth receiver"
        }
    )
}

$coldStarts = @()
for ($i = 1; $i -le $ColdStartRuns; $i++) {
    Invoke-Adb -Args @("shell", "am", "force-stop", $packageName) | Out-Null
    Start-Sleep -Milliseconds 1200
    Ensure-InteractiveDeviceState
    $launch = Invoke-Adb -Args @("shell", "am", "start", "-W", "-n", "$packageName/$mainActivity")
    $coldStarts += @{
        run = $i
        output = ($launch -join "`n")
    }
}

Invoke-Adb -Args @("shell", "dumpsys", "gfxinfo", $packageName, "reset") | Out-Null
Invoke-Adb -Args @("shell", "am", "force-stop", $packageName) | Out-Null
Ensure-InteractiveDeviceState
Invoke-Adb -Args @("shell", "am", "start", "-W", "-n", "$packageName/$mainActivity") | Out-Null
Start-Sleep -Seconds 5

$fixtureUri = Ensure-PlaybackFixture
$externalPlayback = Measure-ExternalAudioPlayback -PlaybackUri $fixtureUri
$deviceProps = @{
    brand = ((Invoke-Adb -Args @("shell", "getprop", "ro.product.brand")) -join "").Trim()
    model = ((Invoke-Adb -Args @("shell", "getprop", "ro.product.model")) -join "").Trim()
    androidRelease = ((Invoke-Adb -Args @("shell", "getprop", "ro.build.version.release")) -join "").Trim()
    sdkInt = ((Invoke-Adb -Args @("shell", "getprop", "ro.build.version.sdk")) -join "").Trim()
    abi = ((Invoke-Adb -Args @("shell", "getprop", "ro.product.cpu.abi")) -join "").Trim()
}

$report = @{
    generatedAt = (Get-Date).ToString("o")
    packageName = $packageName
    buildVariant = $BuildVariant
    deviceId = if ($DeviceId) { $DeviceId } else { "default" }
    device = $deviceProps
    imports = $imports
    coldStarts = $coldStarts
    externalPlayback = $externalPlayback
    meminfo = (Invoke-Adb -Args @("shell", "dumpsys", "meminfo", $packageName)) -join "`n"
    gfxinfo = (Invoke-Adb -Args @("shell", "dumpsys", "gfxinfo", $packageName)) -join "`n"
}

$report | ConvertTo-Json -Depth 6 | Set-Content -Path $reportPath
Write-Output "report=$reportPath"
