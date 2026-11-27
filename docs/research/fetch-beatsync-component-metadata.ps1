$ErrorActionPreference = 'Stop'
$researchDir = Join-Path $PSScriptRoot 'beatsync-components-2026-10-27'
$repos = @('ktorio/ktor','TooTallNate/Java-WebSocket','netty/netty','square/okhttp','NanoHttpd/nanohttpd','Kotlin/kotlinx.serialization','square/moshi','google/gson','protocolbuffers/protobuf','Kotlin/kotlinx.coroutines','jmdns/jmdns','cashapp/zipline','dokar3/quickjs-kt','koush/AndroidAsync','bellard/quickjs','androidx/media','google/oboe','badaix/snapcast','apache/commons-net','instacart/truetime-android','freeman-jiang/beatsync','sendspin/sendspin')
$repoResults = $repos | ForEach-Object -Parallel {
    $repo = $_
    try {
        $r = Invoke-RestMethod -Uri "https://api.github.com/repos/$repo" -Headers @{ 'User-Agent' = 'ListenTogether-component-research'; 'Accept' = 'application/vnd.github+json' } -TimeoutSec 25
        [pscustomobject]@{repo=$repo; canonical=$r.full_name; stars=$r.stargazers_count; forks=$r.forks_count; archived=$r.archived; disabled=$r.disabled; pushedAt=$r.pushed_at; updatedAt=$r.updated_at; defaultBranch=$r.default_branch; license=$r.license.spdx_id; description=$r.description; url=$r.html_url; fetchedAt=[DateTimeOffset]::UtcNow.ToString('o')}
    } catch { [pscustomobject]@{repo=$repo; error=$_.Exception.Message} }
} -ThrottleLimit 4
$repoResults | Sort-Object repo | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $researchDir 'github-repositories.json') -Encoding utf8
$repoResults | Sort-Object stars -Descending | Select-Object repo, stars, archived, pushedAt, error | Format-Table -AutoSize
