$ErrorActionPreference = "Stop"
$scriptPath = Join-Path (Split-Path -Parent $PSScriptRoot) "adb_full_test.ps1"
$tokens = $null
$parseErrors = $null
[System.Management.Automation.Language.Parser]::ParseFile($scriptPath, [ref]$tokens, [ref]$parseErrors) | Out-Null
if ($parseErrors.Count -gt 0) {
    throw ($parseErrors | Out-String)
}

# 所有设备命令都由函数接管，测试不会连接 adb 或读取真实 Cookie
function global:adb {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Arguments)

    $command = $Arguments -join " "
    $global:neriAdbTestCommands.Add($command)
    switch -Wildcard ($command) {
        "shell pm list packages *" { return "package:$global:neriAdbTestInstalledPackage" }
        "shell cmd package resolve-activity *" { return "$global:neriAdbTestPackage/moe.ouom.neriplayer.activity.MainActivity" }
        "shell pm list instrumentation" { return "instrumentation:$global:neriAdbTestPackage.test/moe.ouom.neriplayer.testing.NeriPlayerInstrumentationTestRunner" }
        "shell am instrument *" { return "OK (1 test)" }
        "shell content query *" { return "Row: 0 _id=42, _display_name=neri_test_tone_20s.wav" }
        "shell dumpsys media_session" { return $global:neriAdbTestSessionDump }
        default { return "Success" }
    }
}

function global:Start-Sleep { param([int]$Milliseconds, [int]$Seconds) }
function global:New-Item { param($ItemType, [switch]$Force, $Path) }
function global:Test-Path { param($Path) return $true }
function global:Get-Content { param($Path, [switch]$Raw) return "mock-cookie" }
function global:Get-ChildItem {
    param($Path, $Filter, [switch]$File)
    $apkName = if ($Path -match "androidTest") { "debug-androidTest.apk" } elseif ($Path -match "release$") { "release.apk" } else { "debug.apk" }
    return [pscustomobject]@{ FullName = $apkName; LastWriteTime = [DateTime]::Now }
}
function global:Set-Content {
    param($Path, [Parameter(ValueFromPipeline = $true)]$Value)
    process { $global:neriAdbTestReport = $Value | ConvertFrom-Json }
}

function Assert-Command {
    param([string]$Expected)
    if ($global:neriAdbTestCommands -notcontains $Expected) {
        throw "expected adb command was not invoked: $Expected"
    }
}

function Invoke-ScriptScenario {
    param([string]$Variant, [switch]$MissingTarget, [switch]$PlaybackFromOtherPackage, [switch]$ConcurrentSessions, [switch]$TargetSessionFirst, [switch]$TargetStateNull)

    $global:neriAdbTestCommands = [System.Collections.Generic.List[string]]::new()
    $global:neriAdbTestPackage = if ($Variant -eq "debug") { "moe.ouom.neriplayer.debug" } else { "moe.ouom.neriplayer" }
    $global:neriAdbTestInstalledPackage = if ($MissingTarget) {
        if ($Variant -eq "debug") { "moe.ouom.neriplayer" } else { "moe.ouom.neriplayer.debug" }
    } else { $global:neriAdbTestPackage }
    $otherPackage = if ($Variant -eq "debug") { "moe.ouom.neriplayer" } else { "moe.ouom.neriplayer.debug" }
    $expectedState = if ($PlaybackFromOtherPackage) { $null } else { "state=PlaybackState {state=PLAYING(3)" }
    if ($ConcurrentSessions) {
        $targetState = if ($PlaybackFromOtherPackage) { "PAUSED(2)" } else { "PLAYING(3)" }
        $otherState = if ($PlaybackFromOtherPackage -or $TargetStateNull) { "PLAYING(3)" } else { "PAUSED(2)" }
        $expectedState = if ($TargetStateNull) { $null } else { "state=PlaybackState {state=$targetState" }
        $targetStateLine = if ($TargetStateNull) { "state=null" } else { "state=PlaybackState {state=$targetState, position=0, speed=1.0}" }
        $targetSession = "  TargetSession (userId=0)`n    ownerPid=1000, ownerUid=1000, userId=0`n    package=$global:neriAdbTestPackage`n    active=true`n    $targetStateLine"
        $otherSession = "  OtherSession (userId=0)`n    package=$otherPackage`n    state=PlaybackState {state=$otherState, position=0, speed=1.0}"
        $global:neriAdbTestSessionDump = if ($TargetSessionFirst) { "$targetSession`n$otherSession" } else { "$otherSession`n$targetSession" }
    } else {
        $playbackPackage = if ($PlaybackFromOtherPackage) { $otherPackage } else { $global:neriAdbTestPackage }
        $global:neriAdbTestSessionDump = "  Session (userId=0)`n    package=$playbackPackage`n    state=PlaybackState {state=PLAYING(3), position=0, speed=1.0}"
    }
    $global:neriAdbTestReport = $null
    $arguments = @{ SkipBuild = $true; ColdStartRuns = 1 }
    if ($Variant -eq "release") { $arguments.BuildVariant = $Variant }

    $failure = $null
    try { & $scriptPath @arguments | Out-Null } catch { $failure = $_.Exception.Message }
    if ($MissingTarget) {
        if ($failure -ne "$Variant app is not installed: $global:neriAdbTestPackage") {
            throw "missing $Variant target was not rejected: $failure"
        }
        if (@($global:neriAdbTestCommands | Where-Object { $_ -like "shell am force-stop *" -or $_ -like "shell am start *" }).Count -gt 0) {
            throw "an app was launched before checking the exact installed package"
        }
        return
    }
    if ($failure) { throw $failure }

    $package = $global:neriAdbTestPackage
    $apkName = "$Variant.apk"
    Assert-Command "install -r $apkName"
    Assert-Command "shell pm grant $package android.permission.POST_NOTIFICATIONS"
    Assert-Command "shell am force-stop $package"
    Assert-Command "shell am start -W -n $package/moe.ouom.neriplayer.activity.MainActivity"
    Assert-Command "shell dumpsys gfxinfo $package reset"
    Assert-Command "shell dumpsys meminfo $package"
    $expectedPlaying = -not ($PlaybackFromOtherPackage -or $TargetStateNull)
    if ($global:neriAdbTestReport.packageName -ne $package -or $global:neriAdbTestReport.externalPlayback.reachedPlaying -ne $expectedPlaying) {
        throw "report did not measure the selected application"
    }
    if ($global:neriAdbTestReport.externalPlayback.lastObservedState -ne $expectedState) {
        throw "report used another application's playback state"
    }
    if ($Variant -eq "debug") {
        Assert-Command "install -r debug-androidTest.apk"
        Assert-Command "shell am instrument -w -r -e class moe.ouom.neriplayer.testing.PermissionBootstrapTest $package.test/moe.ouom.neriplayer.testing.NeriPlayerInstrumentationTestRunner"
        Assert-Command "shell am broadcast -a moe.ouom.neriplayer.debug.CLEAR_AUTH -n $package/moe.ouom.neriplayer.testing.DebugCookieImportReceiver --es platform all"
        $imports = @($global:neriAdbTestCommands | Where-Object { $_ -like "shell am broadcast -a moe.ouom.neriplayer.debug.IMPORT_AUTH -n $package/moe.ouom.neriplayer.testing.DebugCookieImportReceiver *" })
        if ($imports.Count -ne 3) { throw "debug cookie imports targeted the wrong component" }
    } elseif (@($global:neriAdbTestCommands | Where-Object { $_ -like "shell am instrument *" -or $_ -like "*neriplayer.debug.*_AUTH*" }).Count -gt 0) {
        throw "release variant invoked debug-only helpers"
    }
}

Invoke-ScriptScenario -Variant "debug"
Invoke-ScriptScenario -Variant "release"
Invoke-ScriptScenario -Variant "debug" -MissingTarget
Invoke-ScriptScenario -Variant "release" -MissingTarget
Invoke-ScriptScenario -Variant "release" -ConcurrentSessions -PlaybackFromOtherPackage
Invoke-ScriptScenario -Variant "release" -ConcurrentSessions -PlaybackFromOtherPackage -TargetSessionFirst
Invoke-ScriptScenario -Variant "release" -ConcurrentSessions
Invoke-ScriptScenario -Variant "release" -ConcurrentSessions -TargetSessionFirst
Invoke-ScriptScenario -Variant "release" -PlaybackFromOtherPackage
Invoke-ScriptScenario -Variant "release" -ConcurrentSessions -TargetStateNull
Invoke-ScriptScenario -Variant "release" -ConcurrentSessions -TargetStateNull -TargetSessionFirst
Write-Output "PASS: 11 scenarios covering variants, missing targets, and isolated playback states"
