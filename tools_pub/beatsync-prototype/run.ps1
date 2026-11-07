param(
    [ValidateSet('All','Js','Network','AndroidBuild')][string]$Phase='All',
    [string]$JavaHome=$env:JAVA_HOME,
    [string]$AndroidSdk=$env:ANDROID_HOME,
    [switch]$FullAndroidComparison
)
$ErrorActionPreference='Stop'
if (-not $JavaHome -or -not (Test-Path -LiteralPath (Join-Path $JavaHome 'bin/java.exe'))) {
    throw 'Pass -JavaHome with a JDK 21 directory.'
}
$env:JAVA_HOME=$JavaHome
$workspaceRoot=(Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../..')).Path
$wrapper=Join-Path $workspaceRoot 'gradlew.bat'
function Assert-NativeSuccess([string]$step) { if ($LASTEXITCODE -ne 0) { throw "$step failed (exit $LASTEXITCODE)." } }
Push-Location $PSScriptRoot
try {
    if ($Phase -in @('All','Js','AndroidBuild')) {
        if (-not (Test-Path -LiteralPath 'node_modules/esbuild')) {
            & npm.cmd ci --ignore-scripts
            Assert-NativeSuccess 'npm ci'
        }
        & npm.cmd run bundle
        Assert-NativeSuccess 'bundle'
        & npm.cmd run oracle
        Assert-NativeSuccess 'oracle'
        Push-Location 'js'
        try {
            & ../node_modules/@oven/bun-windows-x64/bin/bun.exe test upstream/ntp.test.ts 2>&1 | Tee-Object -FilePath '../results/bun-original-tests.txt'
            Assert-NativeSuccess 'Bun original tests'
        } finally { Pop-Location }
    }
    if ($Phase -in @('All','Js')) {
        & $wrapper -p jvm "-Dorg.gradle.java.installations.paths=$JavaHome" run --args=js ziplineProbe --console=plain
        Assert-NativeSuccess 'embedded engines'
    }
    if ($Phase -in @('All','Network')) {
        & $wrapper -p jvm "-Dorg.gradle.java.installations.paths=$JavaHome" run --args=network --console=plain
        Assert-NativeSuccess 'WebSocket comparison'
        & node js/analyze.mjs
        Assert-NativeSuccess 'summary'
    }
    if ($Phase -eq 'AndroidBuild') {
        if (-not $AndroidSdk) { throw 'Pass -AndroidSdk with an SDK containing platform 37.' }
        $env:ANDROID_HOME=$AndroidSdk
        $candidateArg = if ($FullAndroidComparison) { "-Pcandidate=false" } else { "-Pcandidate=true" }
        & $wrapper -p android-smoke $candidateArg assembleDebug assembleRelease --console=plain
        Assert-NativeSuccess 'Android build'
    }
} finally { Pop-Location }
