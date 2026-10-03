# 点载也转为独立脚本调用，避免 mock 进入调用方作用域
if ($MyInvocation.InvocationName -eq ".") {
    & $PSCommandPath
    return
}

$ErrorActionPreference = "Stop"
$scriptPath = Join-Path (Split-Path -Parent $PSScriptRoot) "adb_full_test.ps1"
$tokens = $null
$parseErrors = $null
[System.Management.Automation.Language.Parser]::ParseFile($scriptPath, [ref]$tokens, [ref]$parseErrors) | Out-Null
if ($parseErrors.Count -gt 0) {
    throw ($parseErrors | Out-String)
}

# 所有设备命令都由函数接管，测试不会连接 adb 或读取真实 Cookie
function script:adb {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Arguments)

    $command = $Arguments -join " "
    $neriAdbTestCommands.Add($command)
    switch -Wildcard ($command) {
        "shell pm list packages *" { return "package:$neriAdbTestInstalledPackage" }
        "shell cmd package resolve-activity *" { return "$neriAdbTestPackage/moe.ouom.neriplayer.activity.MainActivity" }
        "shell pm list instrumentation" {
            if ($neriAdbTestBootstrapMode -eq "missing") { return "instrumentation:unrelated.test/unrelated.Runner" }
            return "instrumentation:$neriAdbTestPackage.test/moe.ouom.neriplayer.testing.NeriPlayerInstrumentationTestRunner"
        }
        "shell am instrument *" {
            switch ($neriAdbTestBootstrapMode) {
                "failed" { return "INSTRUMENTATION_STATUS: performance_startup_ready=$neriAdbTestPackage`nFAILURES!!!" }
                "no-marker" { return "OK (1 test)" }
                "wrong-package" { return "INSTRUMENTATION_STATUS: performance_startup_ready=moe.ouom.neriplayer`nOK (1 test)" }
                "command-failed" { throw "adb command failed" }
                default { return "INSTRUMENTATION_STATUS: performance_startup_ready=$neriAdbTestPackage`nOK (1 test)" }
            }
        }
        "shell content query *" { return "Row: 0 _id=42, _display_name=neri_test_tone_20s.wav" }
        "shell dumpsys media_session" { return $neriAdbTestSessionDump }
        default { return "Success" }
    }
}

function script:Start-Sleep { param([int]$Milliseconds, [int]$Seconds) }
function script:New-Item { param($ItemType, [switch]$Force, $Path) }
function script:Test-Path { param($Path) return $true }
function script:Get-Content { param($Path, [switch]$Raw) return "mock-cookie" }
function script:Get-ChildItem {
    param($Path, $Filter, [switch]$File)
    if ($Path -match "androidTest" -and $neriAdbTestBootstrapMode -eq "missing-apk") { return $null }
    $apkName = if ($Path -match "androidTest") { "debug-androidTest.apk" } elseif ($Path -match "release$") { "release.apk" } else { "debug.apk" }
    return [pscustomobject]@{ FullName = $apkName; LastWriteTime = [DateTime]::Now }
}
function script:Set-Content {
    param($Path, [Parameter(ValueFromPipeline = $true)]$Value)
    # mock 从被测脚本的父作用域读取 fixture，用共享对象回传报告
    process { $neriAdbTestReport.Value = $Value | ConvertFrom-Json }
}

function Assert-Command {
    param([string]$Expected)
    if ($script:neriAdbTestCommands -notcontains $Expected) {
        throw "expected adb command was not invoked: $Expected"
    }
}

function Invoke-ScriptScenario {
    param(
        [string]$Variant,
        [switch]$MissingTarget,
        [switch]$PlaybackFromOtherPackage,
        [switch]$ConcurrentSessions,
        [switch]$TargetSessionFirst,
        [switch]$TargetStateNull,
        [ValidateSet("ready", "failed", "missing", "no-marker", "wrong-package", "command-failed", "missing-apk")]
        [string]$BootstrapMode = "ready"
    )

    $script:neriAdbTestCommands = [System.Collections.Generic.List[string]]::new()
    $script:neriAdbTestBootstrapMode = $BootstrapMode
    $script:neriAdbTestPackage = if ($Variant -eq "debug") { "moe.ouom.neriplayer.debug" } else { "moe.ouom.neriplayer" }
    $script:neriAdbTestInstalledPackage = if ($MissingTarget) {
        if ($Variant -eq "debug") { "moe.ouom.neriplayer" } else { "moe.ouom.neriplayer.debug" }
    } else { $script:neriAdbTestPackage }
    $otherPackage = if ($Variant -eq "debug") { "moe.ouom.neriplayer" } else { "moe.ouom.neriplayer.debug" }
    $expectedState = if ($PlaybackFromOtherPackage) { $null } else { "state=PlaybackState {state=PLAYING(3)" }
    if ($ConcurrentSessions) {
        $targetState = if ($PlaybackFromOtherPackage) { "PAUSED(2)" } else { "PLAYING(3)" }
        $otherState = if ($PlaybackFromOtherPackage -or $TargetStateNull) { "PLAYING(3)" } else { "PAUSED(2)" }
        $expectedState = if ($TargetStateNull) { $null } else { "state=PlaybackState {state=$targetState" }
        $targetStateLine = if ($TargetStateNull) { "state=null" } else { "state=PlaybackState {state=$targetState, position=0, speed=1.0}" }
        $targetSession = "  TargetSession (userId=0)`n    ownerPid=1000, ownerUid=1000, userId=0`n    package=$script:neriAdbTestPackage`n    active=true`n    $targetStateLine"
        $otherSession = "  OtherSession (userId=0)`n    package=$otherPackage`n    state=PlaybackState {state=$otherState, position=0, speed=1.0}"
        $script:neriAdbTestSessionDump = if ($TargetSessionFirst) { "$targetSession`n$otherSession" } else { "$otherSession`n$targetSession" }
    } else {
        $playbackPackage = if ($PlaybackFromOtherPackage) { $otherPackage } else { $script:neriAdbTestPackage }
        $script:neriAdbTestSessionDump = "  Session (userId=0)`n    package=$playbackPackage`n    state=PlaybackState {state=PLAYING(3), position=0, speed=1.0}"
    }
    $script:neriAdbTestReport = @{ Value = $null }
    $arguments = @{ SkipBuild = $true; ColdStartRuns = 1 }
    if ($Variant -eq "release") { $arguments.BuildVariant = $Variant }

    $failure = $null
    try { & $scriptPath @arguments | Out-Null } catch { $failure = $_.Exception.Message }
    if ($MissingTarget) {
        if ($failure -ne "$Variant app is not installed: $script:neriAdbTestPackage") {
            throw "missing $Variant target was not rejected: $failure"
        }
        if (@($script:neriAdbTestCommands | Where-Object { $_ -like "shell am force-stop *" -or $_ -like "shell am start *" }).Count -gt 0) {
            throw "an app was launched before checking the exact installed package"
        }
        return
    }
    if ($Variant -eq "debug" -and $BootstrapMode -ne "ready") {
        $expectedFailure = switch ($BootstrapMode) {
            "missing" { "startup bootstrap instrumentation is not installed:" }
            "missing-apk" { "debug androidTest apk is required for startup bootstrap" }
            "command-failed" { "adb command failed" }
            default { "startup bootstrap failed:" }
        }
        if ($failure -notlike "$expectedFailure*") {
            throw "invalid bootstrap was not rejected ($BootstrapMode): $failure"
        }
        if (@($script:neriAdbTestCommands | Where-Object {
            $_ -like "shell am force-stop *" -or $_ -like "shell am start *" -or $_ -like "shell dumpsys *"
        }).Count -gt 0 -or $null -ne $script:neriAdbTestReport.Value) {
            throw "invalid startup bootstrap was followed by metrics or a report"
        }
        return
    }
    if ($failure) { throw $failure }

    $package = $script:neriAdbTestPackage
    $apkName = "$Variant.apk"
    Assert-Command "install -r $apkName"
    Assert-Command "shell pm grant $package android.permission.POST_NOTIFICATIONS"
    Assert-Command "shell am force-stop $package"
    Assert-Command "shell am start -W -n $package/moe.ouom.neriplayer.activity.MainActivity"
    Assert-Command "shell dumpsys gfxinfo $package reset"
    Assert-Command "shell dumpsys meminfo $package"
    $expectedPlaying = -not ($PlaybackFromOtherPackage -or $TargetStateNull)
    if ($script:neriAdbTestReport.Value.packageName -ne $package -or $script:neriAdbTestReport.Value.externalPlayback.reachedPlaying -ne $expectedPlaying) {
        throw "report did not measure the selected application"
    }
    if ($script:neriAdbTestReport.Value.externalPlayback.lastObservedState -ne $expectedState) {
        throw "report used another application's playback state"
    }
    if ($Variant -eq "debug") {
        Assert-Command "install -r debug-androidTest.apk"
        $bootstrapCommand = "shell am instrument -w -r -e class moe.ouom.neriplayer.testing.PerformanceStartupBootstrapTest -e preparePerformanceStartup true $package.test/moe.ouom.neriplayer.testing.NeriPlayerInstrumentationTestRunner"
        Assert-Command $bootstrapCommand
        $bootstrapIndex = $script:neriAdbTestCommands.IndexOf($bootstrapCommand)
        $firstLaunchIndex = $script:neriAdbTestCommands.IndexOf("shell am force-stop $package")
        if ($bootstrapIndex -ge $firstLaunchIndex) { throw "metrics started before startup readiness was verified" }
        Assert-Command "shell am broadcast -a moe.ouom.neriplayer.debug.CLEAR_AUTH -n $package/moe.ouom.neriplayer.testing.DebugCookieImportReceiver --es platform all"
        $imports = @($script:neriAdbTestCommands | Where-Object { $_ -like "shell am broadcast -a moe.ouom.neriplayer.debug.IMPORT_AUTH -n $package/moe.ouom.neriplayer.testing.DebugCookieImportReceiver *" })
        if ($imports.Count -ne 3) { throw "debug cookie imports targeted the wrong component" }
    } elseif (@($script:neriAdbTestCommands | Where-Object { $_ -like "shell am instrument *" -or $_ -like "*neriplayer.debug.*_AUTH*" }).Count -gt 0) {
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
Invoke-ScriptScenario -Variant "debug" -BootstrapMode "failed"
Invoke-ScriptScenario -Variant "debug" -BootstrapMode "missing"
Invoke-ScriptScenario -Variant "debug" -BootstrapMode "no-marker"
Invoke-ScriptScenario -Variant "debug" -BootstrapMode "wrong-package"
Invoke-ScriptScenario -Variant "debug" -BootstrapMode "command-failed"
Invoke-ScriptScenario -Variant "debug" -BootstrapMode "missing-apk"
Write-Output "PASS: 17 scenarios covering variants, startup readiness, and isolated playback states"
