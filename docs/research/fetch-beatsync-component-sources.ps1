$ErrorActionPreference = 'Stop'
$researchDir = Join-Path $PSScriptRoot 'beatsync-components-2026-10-27'
$repoRecords = Get-Content -LiteralPath (Join-Path $researchDir 'github-repositories.json') -Raw | ConvertFrom-Json
$readmeResults = $repoRecords | Where-Object { -not $_.error } | ForEach-Object -Parallel {
    $r = $_
    $dest = $using:researchDir
    foreach ($name in @('README.md','README.rst','README')) {
        $url = "https://raw.githubusercontent.com/$($r.canonical)/$($r.defaultBranch)/$name"
        try {
            $body = (Invoke-WebRequest -Uri $url -Headers @{ 'User-Agent' = 'ListenTogether-component-research' } -TimeoutSec 25).Content
            $file = $r.repo.Replace('/','--') + '-' + $name.Replace('.','_') + '.txt'
            $body | Set-Content -LiteralPath (Join-Path $dest $file) -Encoding utf8
            [pscustomobject]@{repo=$r.repo; url=$url; file=$file; fetchedAt=[DateTimeOffset]::UtcNow.ToString('o')}
            break
        } catch { if ($name -eq 'README') { [pscustomobject]@{repo=$r.repo; error=$_.Exception.Message} } }
    }
} -ThrottleLimit 4
$readmeResults | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $researchDir 'readme-sources.json') -Encoding utf8
$docs = @(
    @{name='android-nsd';url='https://developer.android.com/develop/connectivity/wifi/use-nsd'},
    @{name='android-local-network';url='https://developer.android.com/privacy-and-security/local-network-permission'},
    @{name='android-media3-hello';url='https://developer.android.com/media/media3/exoplayer/hello-world'},
    @{name='ktor-server-engines';url='https://ktor.io/docs/server-engines.html'},
    @{name='ktor-server-websockets';url='https://ktor.io/docs/server-websockets.html'},
    @{name='ktor-supported-platforms';url='https://ktor.io/docs/supported-platforms.html'}
)
$docResults = $docs | ForEach-Object -Parallel {
    $item = $_
    $dest = $using:researchDir
    try {
        $html = (Invoke-WebRequest -Uri $item.url -Headers @{ 'User-Agent' = 'ListenTogether-component-research' } -TimeoutSec 30).Content
        $text = [regex]::Replace($html, '(?is)<(script|style)\b[^>]*>.*?</\1>', '')
        $text = [regex]::Replace($text, '(?i)</(p|div|li|h[1-6]|tr)>', "`n")
        $text = [System.Net.WebUtility]::HtmlDecode([regex]::Replace($text, '<[^>]+>', ' '))
        $text = [regex]::Replace($text, '[ \t]+', ' ')
        $text | Set-Content -LiteralPath (Join-Path $dest ($item.name + '.txt')) -Encoding utf8
        [pscustomobject]@{name=$item.name;url=$item.url;file=($item.name+'.txt');fetchedAt=[DateTimeOffset]::UtcNow.ToString('o')}
    } catch { [pscustomobject]@{name=$item.name;url=$item.url;error=$_.Exception.Message} }
} -ThrottleLimit 4
$docResults | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $researchDir 'official-doc-sources.json') -Encoding utf8
@{readmes=$readmeResults;docs=$docResults} | ConvertTo-Json -Depth 5
