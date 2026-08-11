param(
    [string]$AgentBaseUrl = "http://127.0.0.1:18087",
    [string]$ServiceToken = "agent-smoke-token",
    [string]$OutputDirectory = "outputs/agent-v1.1-sse-acceptance/latest",
    [int]$PerformanceRuns = 20,
    [int]$TimeoutSeconds = 180
)

$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"
Add-Type -AssemblyName System.Net.Http

if ($PerformanceRuns -lt 1) {
    throw "PerformanceRuns must be positive"
}

function Decode-Utf8Base64 {
    param([string]$Value)
    return [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($Value))
}

function Get-Percentile {
    param(
        [long[]]$Values,
        [double]$Percentile
    )
    $sorted = @($Values | Sort-Object)
    $index = [Math]::Max(0, [Math]::Ceiling($Percentile * $sorted.Count) - 1)
    return [long]$sorted[$index]
}

function New-RunRequest {
    param([string]$Query)
    return [ordered]@{
        conversationId = [guid]::NewGuid().ToString()
        messageId = [guid]::NewGuid().ToString()
        query = $Query
        context = [ordered]@{
            locale = "zh-CN"
            map = [ordered]@{
                visibleLayerIds = @(0, 1, 2, 3, 4, 5)
                zoom = 13
                extent = $null
            }
            businessObjectIds = @()
        }
        user = [ordered]@{
            userId = "acceptance-user"
            tenantId = "acceptance-tenant"
            roles = @("USER")
        }
    }
}

function Invoke-SseRun {
    param(
        [pscustomobject]$Case,
        [bool]$SaveFixture,
        [object]$RequestOverride = $null
    )

    $request = if ($null -ne $RequestOverride) {
        $RequestOverride
    } else {
        New-RunRequest -Query (Decode-Utf8Base64 $Case.queryBase64)
    }
    $requestJson = $request | ConvertTo-Json -Depth 12
    $traceId = "agent-v11-$($Case.id.ToLowerInvariant())-$([guid]::NewGuid().ToString('N'))"
    $headers = @{
        Authorization = "Bearer $ServiceToken"
        "X-Trace-Id" = $traceId
        "X-Tenant-Id" = $request.user.tenantId
        "X-User-Id" = $request.user.userId
    }
    $timer = [Diagnostics.Stopwatch]::StartNew()
    $response = Invoke-WebRequest `
        -UseBasicParsing `
        -Method Post `
        -Uri "$($AgentBaseUrl.TrimEnd('/'))/api/v1/runs/stream" `
        -Headers $headers `
        -ContentType "application/json; charset=utf-8" `
        -Body ([Text.Encoding]::UTF8.GetBytes($requestJson)) `
        -TimeoutSec $TimeoutSeconds
    $durationMs = $timer.ElapsedMilliseconds
    $rawSse = [string]$response.Content

    $eventNames = New-Object System.Collections.Generic.List[string]
    $events = New-Object System.Collections.Generic.List[object]
    $currentEvent = $null
    foreach ($line in ($rawSse -split "`r?`n")) {
        if ($line.StartsWith("event: ")) {
            $currentEvent = $line.Substring(7).Trim()
            $eventNames.Add($currentEvent)
        } elseif ($line.StartsWith("data: ")) {
            $envelope = $line.Substring(6) | ConvertFrom-Json
            if ($envelope.schemaVersion -ne "1.1") {
                throw "$($Case.id) returned non-v1.1 SSE envelope"
            }
            $events.Add([pscustomobject]@{ name = $currentEvent; envelope = $envelope })
        }
    }

    if ($SaveFixture) {
        [IO.File]::WriteAllText(
            (Join-Path $OutputDirectory "$($Case.id).request.json"),
            $requestJson,
            (New-Object Text.UTF8Encoding($false))
        )
        [IO.File]::WriteAllText(
            (Join-Path $OutputDirectory "$($Case.id).sse.txt"),
            $rawSse,
            (New-Object Text.UTF8Encoding($false))
        )
    }

    if ($response.StatusCode -ne 200) {
        throw "$($Case.id) returned HTTP $($response.StatusCode)"
    }
    $terminalEvent = if ($eventNames.Count -gt 0) { $eventNames[$eventNames.Count - 1] } else { $null }
    if ($terminalEvent -ne $Case.expectedTerminal) {
        throw "$($Case.id) did not end with $($Case.expectedTerminal): $($eventNames -join ',')"
    }

    $toolEvent = $events | Where-Object { $_.name -eq "tool.completed" } | Select-Object -Last 1
    $mapEvent = $events | Where-Object { $_.name -eq "map.result" } | Select-Object -Last 1
    $failedEvent = $events | Where-Object { $_.name -eq "run.failed" } | Select-Object -Last 1
    if ($Case.expectedTerminal -eq "run.completed" -and ($null -eq $toolEvent -or $null -eq $mapEvent)) {
        throw "$($Case.id) did not emit tool.completed and map.result"
    }
    $errorCode = if ($failedEvent) { [string]$failedEvent.envelope.payload.error.code } else { $null }
    if ($Case.expectedErrorCode -and $errorCode -ne $Case.expectedErrorCode) {
        throw "$($Case.id) returned error $errorCode instead of $($Case.expectedErrorCode)"
    }
    $toolDurationMs = if ($toolEvent) { [long]$toolEvent.envelope.payload.durationMs } else { $null }
    $sseBytes = [Text.Encoding]::UTF8.GetByteCount($rawSse)
    $runId = [string]$response.Headers["X-Run-Id"]

    return [pscustomobject]@{
        caseId = $Case.id
        status = [int]$response.StatusCode
        runId = $runId
        durationMs = [long]$durationMs
        toolDurationMs = $toolDurationMs
        toolCallId = if ($toolEvent) { [string]$toolEvent.envelope.payload.toolCallId } else { $null }
        orchestrationDurationMs = if ($null -ne $toolDurationMs) {
            [Math]::Max(0, [long]$durationMs - $toolDurationMs)
        } else {
            [long]$durationMs
        }
        sseBytes = [long]$sseBytes
        eventCount = $eventNames.Count
        events = [object[]]@($eventNames)
        terminalEvent = $terminalEvent
        errorCode = $errorCode
        resultSetCount = if ($mapEvent) { @($mapEvent.envelope.payload.resultSets).Count } else { 0 }
        overlayCount = if ($mapEvent) { @($mapEvent.envelope.payload.overlays).Count } else { 0 }
        warnings = if ($mapEvent) {
            [object[]]@($mapEvent.envelope.payload.warnings)
        } else {
            [object[]]@()
        }
    }
}

$cases = @(
    [pscustomobject]@{
        id = "A01"
        queryBase64 = "5biu5oiR5oyR5LiA5aWX5oi/5Lu3MTIwMDDku6XlhoXvvIzkvr/liKnluqblkozpgZPot6/mraXooYzmjIfmlbDpq5jkuIDngrnnmoTmiL/lrZA="
        performance = $false
        expectedTerminal = "run.completed"
        expectedErrorCode = $null
    },
    [pscustomobject]@{
        id = "A02"
        queryBase64 = "5pi+56S65q2l6KGM5oyH5pWw5b+F6aG76auY55qE6YGT6Lev6ZmE6L+R55qE5bCP5Yy6"
        performance = $true
        expectedTerminal = "run.completed"
        expectedErrorCode = $null
    },
    [pscustomobject]@{
        id = "A03"
        queryBase64 = "5pi+56S6IFdTIOS4jeS9juS6jiA3NSDnmoTpgZPot68gMzAwIOexs+mZhOi/keeahOWwj+WMug=="
        performance = $false
        expectedTerminal = "run.completed"
        expectedErrorCode = $null
    },
    [pscustomobject]@{
        id = "A04"
        queryBase64 = "5biu5oiR5oyJ5L6/5Yip5bqm5YWr5oiQ44CB6YGT6Lev5q2l6KGM5Lik5oiQ55qE55u45a+55p2D6YeN5oyR5Lit5bGx5Yy65oi/5a2Q"
        performance = $false
        expectedTerminal = "run.completed"
        expectedErrorCode = $null
    },
    [pscustomobject]@{
        id = "A05"
        queryBase64 = "5pi+56S65q2l6KGM5oyH5pWw5b6I6auY55qE6YGT6Lev6ZmE6L+R55qE5bCP5Yy6"
        performance = $true
        expectedTerminal = "run.completed"
        expectedErrorCode = $null
    },
    [pscustomobject]@{
        id = "A06"
        queryBase64 = "5biu5oiR5om+5Lu35qC85bC96YeP5L2O55qE5oi/5a2Q"
        performance = $false
        expectedTerminal = "run.completed"
        expectedErrorCode = $null
    },
    [pscustomobject]@{
        id = "A07"
        queryBase64 = "5pi+56S65q2l6KGM5oyH5pWw5b+F6aG76auY55qE6YGT6LevIDEwMDAwIOexs+mZhOi/keeahOWwj+WMug=="
        performance = $false
        expectedTerminal = "run.failed"
        expectedErrorCode = "INVALID_BUFFER_DISTANCE"
    },
    [pscustomobject]@{
        id = "A08"
        queryBase64 = "5pi+56S65q2l6KGM5oyH5pWw5b+F6aG76auY55qE6YGT6Lev6ZmE6L+R77yM5oi/5Lu35LiN6LaF6L+HIDEg55qE5L2P5a6F"
        performance = $false
        expectedTerminal = "run.completed"
        expectedErrorCode = $null
    },
    [pscustomobject]@{
        id = "A11-disabled-road"
        queryBase64 = "5biu5oiR5oyJ5L6/5Yip5bqm5p2D6YeNIDEwMCUg5oyR5oi/5a2Q"
        performance = $false
        expectedTerminal = "run.failed"
        expectedErrorCode = "INVALID_HOUSING_SEARCH_ARGUMENT"
    },
    [pscustomobject]@{
        id = "A11-new-walking"
        queryBase64 = "5biu5oiR55So5paw5q2l6KGM5pu/5Luj6YGT6LevIFdTIOaJvumZhOi/keWwj+WMug=="
        performance = $false
        expectedTerminal = "run.failed"
        expectedErrorCode = "INVALID_HOUSING_SEARCH_ARGUMENT"
    }
)

New-Item -ItemType Directory -Force -Path $OutputDirectory | Out-Null

$health = Invoke-WebRequest -UseBasicParsing -Uri "$($AgentBaseUrl.TrimEnd('/'))/healthz" -TimeoutSec 10
$ready = Invoke-WebRequest -UseBasicParsing -Uri "$($AgentBaseUrl.TrimEnd('/'))/readyz" -TimeoutSec 30
if ($health.StatusCode -ne 200 -or $ready.StatusCode -ne 200) {
    throw "Agent health or readiness is not HTTP 200"
}
$readyBody = $ready.Content | ConvertFrom-Json
if ($readyBody.status -ne "READY" -or $readyBody.toolHealth.status -ne "READY") {
    throw "Agent or Spring Tool is not READY"
}

$fixtureResults = @()
$performanceResults = @()
foreach ($case in $cases) {
    $fixtureResults += Invoke-SseRun -Case $case -SaveFixture $true
    if ($case.performance) {
        # The fixture request warms the complete Agent and Tool path. Only the
        # following runs participate in warm-path percentiles.
        $samples = @()
        foreach ($index in 1..$PerformanceRuns) {
            $samples += Invoke-SseRun -Case $case -SaveFixture $false
        }
        $performanceResults += [pscustomobject]@{
            caseId = $case.id
            runs = $PerformanceRuns
            succeeded = @($samples | Where-Object { $_.status -eq 200 }).Count
            totalP50Ms = Get-Percentile -Values @($samples.durationMs) -Percentile 0.50
            totalP95Ms = Get-Percentile -Values @($samples.durationMs) -Percentile 0.95
            toolP50Ms = Get-Percentile -Values @($samples.toolDurationMs) -Percentile 0.50
            toolP95Ms = Get-Percentile -Values @($samples.toolDurationMs) -Percentile 0.95
            orchestrationP50Ms = Get-Percentile -Values @($samples.orchestrationDurationMs) -Percentile 0.50
            orchestrationP95Ms = Get-Percentile -Values @($samples.orchestrationDurationMs) -Percentile 0.95
            sseBytesP50 = Get-Percentile -Values @($samples.sseBytes) -Percentile 0.50
            sseBytesP95 = Get-Percentile -Values @($samples.sseBytes) -Percentile 0.95
        }
    }
}

$a09Request = New-RunRequest -Query (Decode-Utf8Base64 $cases[1].queryBase64)
$a09FirstCase = [pscustomobject]@{
    id = "A09-first"
    queryBase64 = $cases[1].queryBase64
    expectedTerminal = "run.completed"
    expectedErrorCode = $null
}
$a09RetryCase = [pscustomobject]@{
    id = "A09-retry"
    queryBase64 = $cases[1].queryBase64
    expectedTerminal = "run.completed"
    expectedErrorCode = $null
}
$a09First = Invoke-SseRun -Case $a09FirstCase -SaveFixture $true -RequestOverride $a09Request
$a09Retry = Invoke-SseRun -Case $a09RetryCase -SaveFixture $true -RequestOverride $a09Request
if ($a09First.runId -ne $a09Retry.runId -or $a09First.toolCallId -ne $a09Retry.toolCallId) {
    throw "A09 did not reuse the same runId and toolCallId"
}
$fixtureResults += $a09First
$fixtureResults += $a09Retry

$a10Request = $a09Request | ConvertTo-Json -Depth 12 | ConvertFrom-Json
$a10Request.query = Decode-Utf8Base64 $cases[4].queryBase64
$a10Json = $a10Request | ConvertTo-Json -Depth 12
$a10Path = Join-Path $OutputDirectory "A10.request.json"
[IO.File]::WriteAllText($a10Path, $a10Json, (New-Object Text.UTF8Encoding($false)))
$a10Client = New-Object System.Net.Http.HttpClient
$a10Client.Timeout = [TimeSpan]::FromSeconds($TimeoutSeconds)
$a10Client.DefaultRequestHeaders.Authorization = New-Object System.Net.Http.Headers.AuthenticationHeaderValue(
    "Bearer",
    $ServiceToken
)
$a10Client.DefaultRequestHeaders.Add("X-Trace-Id", "agent-v11-a10-$([guid]::NewGuid().ToString('N'))")
$a10Client.DefaultRequestHeaders.Add("X-Tenant-Id", $a10Request.user.tenantId)
$a10Client.DefaultRequestHeaders.Add("X-User-Id", $a10Request.user.userId)
$a10Content = New-Object System.Net.Http.StringContent(
    $a10Json,
    [Text.Encoding]::UTF8,
    "application/json"
)
try {
    $a10Response = $a10Client.PostAsync(
        "$($AgentBaseUrl.TrimEnd('/'))/api/v1/runs/stream",
        $a10Content
    ).GetAwaiter().GetResult()
    $a10ResponseText = $a10Response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
} finally {
    $a10Content.Dispose()
    $a10Client.Dispose()
}
[IO.File]::WriteAllText(
    (Join-Path $OutputDirectory "A10.response.json"),
    $a10ResponseText,
    (New-Object Text.UTF8Encoding($false))
)
$a10Body = $a10ResponseText | ConvertFrom-Json
if ([int]$a10Response.StatusCode -ne 409 -or $a10Body.error.code -ne "MESSAGE_CONFLICT") {
    throw "A10 did not return HTTP 409 MESSAGE_CONFLICT"
}

$summary = [ordered]@{
    generatedAt = (Get-Date).ToUniversalTime().ToString("o")
    agentBaseUrl = $AgentBaseUrl
    catalogVersion = $readyBody.toolHealth.catalogVersion
    snapshotStatus = $readyBody.toolHealth.housingSnapshot.status
    fixtures = $fixtureResults
    idempotency = [ordered]@{
        runId = $a09First.runId
        toolCallId = $a09First.toolCallId
        firstSseBytes = $a09First.sseBytes
        retrySseBytes = $a09Retry.sseBytes
    }
    conflict = [ordered]@{
        httpStatus = [int]$a10Response.StatusCode
        errorCode = $a10Body.error.code
    }
    performance = $performanceResults
}
$summaryJson = $summary | ConvertTo-Json -Depth 20
[IO.File]::WriteAllText(
    (Join-Path $OutputDirectory "summary.json"),
    $summaryJson,
    (New-Object Text.UTF8Encoding($false))
)
$summaryJson
