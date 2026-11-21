$ErrorActionPreference = 'Stop'
$researchDir = Join-Path $PSScriptRoot 'beatsync-components-2026-10-27'
$headers = @{ 'User-Agent'='ListenTogether-component-research'; 'Accept'='application/vnd.github+json' }
$requests = @(
    @{name='java-websocket-readme';url='https://api.github.com/repos/TooTallNate/Java-WebSocket/readme';kind='github-readme'},
    @{name='quickjs-readme';url='https://api.github.com/repos/bellard/quickjs/readme';kind='github-readme'},
    @{name='ableton-link-metadata';url='https://api.github.com/repos/Ableton/link';kind='json'},
    @{name='bun-metadata';url='https://api.github.com/repos/oven-sh/bun';kind='json'},
    @{name='ableton-link-readme';url='https://raw.githubusercontent.com/Ableton/link/master/README.md';kind='text'},
    @{name='android-nsd-source';url='https://android.googlesource.com/platform/packages/modules/Connectivity/+/refs/heads/main/framework/src/android/net/nsd/NsdManager.java?format=TEXT';kind='base64'},
    @{name='java-websocket-release';url='https://api.github.com/repos/TooTallNate/Java-WebSocket/releases/latest';kind='json'},
    @{name='ktor-release';url='https://api.github.com/repos/ktorio/ktor/releases/latest';kind='json'},
    @{name='zipline-release';url='https://api.github.com/repos/cashapp/zipline/releases/latest';kind='json'},
    @{name='quickjs-kt-release';url='https://api.github.com/repos/dokar3/quickjs-kt/releases/latest';kind='json'},
    @{name='oboe-release';url='https://api.github.com/repos/google/oboe/releases/latest';kind='json'},
    @{name='media3-release';url='https://api.github.com/repos/androidx/media/releases/latest';kind='json'},
    @{name='okhttp-release';url='https://api.github.com/repos/square/okhttp/releases/latest';kind='json'},
    @{name='java-websocket-pom';url='https://raw.githubusercontent.com/TooTallNate/Java-WebSocket/master/pom.xml';kind='text'},
    @{name='zipline-quickjs-api';url='https://raw.githubusercontent.com/cashapp/zipline/trunk/zipline/src/commonMain/kotlin/app/cash/zipline/QuickJs.kt';kind='text'}
)
$results = $requests | ForEach-Object -Parallel {
    $item = $_; $dest = $using:researchDir; $hdr = $using:headers
    try {
        if ($item.kind -eq 'json' -or $item.kind -eq 'github-readme') {
            $result = Invoke-RestMethod -Uri $item.url -Headers $hdr -TimeoutSec 25
            if ($item.kind -eq 'github-readme') { $body = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($result.content)); $ext='.txt' }
            else { $body = $result | ConvertTo-Json -Depth 12; $ext='.json' }
        } else {
            $body = (Invoke-WebRequest -Uri $item.url -Headers $hdr -TimeoutSec 25).Content
            if ($item.kind -eq 'base64') { $body=[Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($body)) }
            $ext='.txt'
        }
        $file = $item.name + $ext
        $body | Set-Content -LiteralPath (Join-Path $dest $file) -Encoding utf8
        [pscustomobject]@{name=$item.name;url=$item.url;file=$file;fetchedAt=[DateTimeOffset]::UtcNow.ToString('o')}
    } catch { [pscustomobject]@{name=$item.name;url=$item.url;error=$_.Exception.Message} }
} -ThrottleLimit 4
$results | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $researchDir 'supplement-sources.json') -Encoding utf8
$results | ConvertTo-Json -Depth 4
