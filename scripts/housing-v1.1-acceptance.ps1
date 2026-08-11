param(
    [string]$SpringBaseUrl = "http://127.0.0.1:8080",
    [string]$ServiceToken = $env:AGENT_TOOL_SERVICE_TOKEN,
    [string]$OutputDirectory = "outputs/housing-v1.1-acceptance",
    [int]$PerformanceRuns = 20,
    [int]$TimeoutSeconds = 30
)

$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"
Set-StrictMode -Version Latest

if ([string]::IsNullOrWhiteSpace($ServiceToken)) {
    throw "ServiceToken or AGENT_TOOL_SERVICE_TOKEN is required"
}
if ($PerformanceRuns -lt 1) {
    throw "PerformanceRuns must be at least 1"
}

Add-Type -AssemblyName System.Net.Http

$baseUrl = $SpringBaseUrl.TrimEnd("/")
$outputPath = [IO.Path]::GetFullPath((Join-Path (Get-Location) $OutputDirectory))
[IO.Directory]::CreateDirectory($outputPath) | Out-Null
$zhongshanDistrict = -join ([char[]](0x4E2D, 0x5C71, 0x533A))

$client = New-Object System.Net.Http.HttpClient
$client.Timeout = [TimeSpan]::FromSeconds($TimeoutSeconds)
$client.DefaultRequestHeaders.Authorization = New-Object System.Net.Http.Headers.AuthenticationHeaderValue(
    "Bearer",
    $ServiceToken
)
$client.DefaultRequestHeaders.Add("X-Tenant-Id", "acceptance-tenant")
$client.DefaultRequestHeaders.Add("X-User-Id", "acceptance-user")
$client.DefaultRequestHeaders.Add("X-Run-Id", "housing-v1.1-acceptance")

function Write-JsonFile {
    param(
        [string]$Path,
        [object]$Value
    )

    $json = $Value | ConvertTo-Json -Depth 100
    [IO.File]::WriteAllText($Path, $json, [Text.UTF8Encoding]::new($false))
}

function Invoke-JsonRequest {
    param(
        [string]$Method,
        [string]$Path,
        [object]$Body,
        [string]$TraceId
    )

    $request = New-Object System.Net.Http.HttpRequestMessage(
        [System.Net.Http.HttpMethod]::new($Method),
        "$baseUrl$Path"
    )
    $request.Headers.Add("X-Trace-Id", $TraceId)
    if ($null -ne $Body) {
        $bodyJson = $Body | ConvertTo-Json -Depth 100 -Compress
        $request.Content = New-Object System.Net.Http.StringContent(
            $bodyJson,
            [Text.Encoding]::UTF8,
            "application/json"
        )
    }

    $timer = [Diagnostics.Stopwatch]::StartNew()
    try {
        $response = $client.SendAsync($request).GetAwaiter().GetResult()
        $responseText = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
        $timer.Stop()
        $parsed = if ([string]::IsNullOrWhiteSpace($responseText)) {
            $null
        } else {
            $responseText | ConvertFrom-Json
        }
        return [pscustomobject]@{
            httpStatus = [int]$response.StatusCode
            durationMs = $timer.ElapsedMilliseconds
            bodyText = $responseText
            json = $parsed
        }
    } finally {
        $request.Dispose()
    }
}

function Assert-Equal {
    param(
        [object]$Actual,
        [object]$Expected,
        [string]$Message
    )

    if ($Actual -ne $Expected) {
        throw "$Message. Expected '$Expected', got '$Actual'"
    }
}

function Assert-True {
    param(
        [bool]$Condition,
        [string]$Message
    )

    if (-not $Condition) {
        throw $Message
    }
}

function New-Preferences {
    param(
        [bool]$PriceEnabled,
        [Nullable[double]]$PriceWeight,
        [bool]$ConvenienceEnabled,
        [string]$ConvenienceLevel,
        [Nullable[double]]$ConvenienceWeight,
        [bool]$RoadEnabled,
        [string]$RoadLevel,
        [Nullable[double]]$RoadWeight
    )

    $price = [ordered]@{
        enabled = $PriceEnabled
        level = "PREFER_LOW"
        weight = if ($PriceEnabled) { [double]$PriceWeight } else { 0 }
    }
    $convenience = [ordered]@{
        enabled = $ConvenienceEnabled
        level = $ConvenienceLevel
    }
    if ($null -ne $ConvenienceWeight) {
        $convenience.weight = [double]$ConvenienceWeight
    }
    $road = [ordered]@{
        enabled = $RoadEnabled
        level = $RoadLevel
    }
    if ($null -ne $RoadWeight) {
        $road.weight = [double]$RoadWeight
    }
    return [ordered]@{
        price = $price
        convenience = $convenience
        roadWalkability = $road
    }
}

function New-HousingArguments {
    param(
        [string]$Mode,
        [object[]]$Districts,
        [hashtable]$HardFilters,
        [object]$Preferences,
        [hashtable]$RoadCriteria,
        [Nullable[int]]$BufferMeters,
        [bool]$IncludeRoads,
        [bool]$IncludeBuffers,
        [int]$Limit = 20
    )

    $spatial = [ordered]@{ relation = "WITHIN_ROAD_BUFFER" }
    if ($null -ne $BufferMeters) {
        $spatial.bufferMeters = [int]$BufferMeters
    }
    return [ordered]@{
        mode = $Mode
        districts = $Districts
        hardFilters = $HardFilters
        preferences = $Preferences
        roadCriteria = $RoadCriteria
        spatial = $spatial
        display = [ordered]@{
            includeRoads = $IncludeRoads
            includeBuffers = $IncludeBuffers
        }
        limit = $Limit
    }
}

function Invoke-HousingCase {
    param(
        [string]$CaseId,
        [string]$Description,
        [object]$Arguments,
        [string]$ToolCallId = ([guid]::NewGuid().ToString()),
        [int]$ExpectedHttpStatus = 200,
        [string]$ExpectedErrorCode = $null,
        [switch]$SkipFixture
    )

    $requestBody = [ordered]@{
        toolCallId = $ToolCallId
        arguments = $Arguments
        dryRun = $false
    }
    $response = Invoke-JsonRequest `
        -Method "POST" `
        -Path "/internal/agent-tools/tools/searchHousingCandidates/invoke" `
        -Body $requestBody `
        -TraceId "acceptance-$($CaseId.ToLowerInvariant())"

    Assert-Equal $response.httpStatus $ExpectedHttpStatus "$CaseId HTTP status"
    if ($ExpectedHttpStatus -eq 200) {
        Assert-Equal $response.json.data.status "SUCCEEDED" "$CaseId Tool status"
    } else {
        Assert-Equal $response.json.error.code $ExpectedErrorCode "$CaseId error code"
    }

    if (-not $SkipFixture) {
        Write-JsonFile -Path (Join-Path $outputPath "$CaseId-request.json") -Value $requestBody
        [IO.File]::WriteAllText(
            (Join-Path $outputPath "$CaseId-response.json"),
            $response.bodyText,
            [Text.UTF8Encoding]::new($false)
        )
    }
    $responseData = if ($response.json.PSObject.Properties["data"]) {
        $response.json.data
    } else {
        $null
    }
    $responseError = if ($response.json.PSObject.Properties["error"]) {
        $response.json.error
    } else {
        $null
    }
    return [pscustomobject]@{
        caseId = $CaseId
        description = $Description
        toolCallId = $ToolCallId
        httpStatus = $response.httpStatus
        wallDurationMs = $response.durationMs
        toolStatus = if ($null -ne $responseData) { $responseData.status } else { $null }
        toolDurationMs = if ($null -ne $responseData) { $responseData.durationMs } else { $null }
        result = if ($null -ne $responseData) { $responseData.result } else { $null }
        error = $responseError
        rawResponse = $response
    }
}

function Get-PercentileValue {
    param(
        [long[]]$Values,
        [double]$Percentile
    )

    $sorted = @($Values | Sort-Object)
    $index = [Math]::Ceiling($sorted.Count * $Percentile) - 1
    return $sorted[[Math]::Max(0, $index)]
}

try {
    $health = Invoke-JsonRequest `
        -Method "GET" `
        -Path "/internal/agent-tools/health" `
        -Body $null `
        -TraceId "acceptance-health"
    Assert-Equal $health.httpStatus 200 "Tool health HTTP status"
    Assert-Equal $health.json.data.status "READY" "Tool health status"
    Write-JsonFile -Path (Join-Path $outputPath "health.json") -Value $health.json

    $catalog = Invoke-JsonRequest `
        -Method "GET" `
        -Path "/internal/agent-tools/catalog" `
        -Body $null `
        -TraceId "acceptance-catalog"
    Assert-Equal $catalog.httpStatus 200 "Catalog HTTP status"
    Assert-Equal $catalog.json.data.version "2026-07-29.1" "Catalog version"
    Write-JsonFile -Path (Join-Path $outputPath "catalog.json") -Value $catalog.json

    $cases = New-Object System.Collections.Generic.List[object]

    $a01 = Invoke-HousingCase -CaseId "A01" -Description "Price hard filter with default convenience/road weights" -Arguments (
        New-HousingArguments -Mode "RANK" -Districts @() -HardFilters @{ priceMax = 12000 } -Preferences (
            New-Preferences -PriceEnabled $false -PriceWeight $null `
                -ConvenienceEnabled $true -ConvenienceLevel "PREFER_HIGH" -ConvenienceWeight $null `
                -RoadEnabled $true -RoadLevel "PREFER_HIGH" -RoadWeight $null
        ) -RoadCriteria @{} -BufferMeters $null -IncludeRoads $true -IncludeBuffers $true
    )
    Assert-Equal $a01.result.resolvedCriteria.priceMax 12000 "A01 priceMax"
    Assert-Equal $a01.result.resolvedCriteria.bufferMeters 100 "A01 default buffer"
    Assert-True ($a01.result.resolvedCriteria.defaultsApplied -contains "PREFERENCE_WEIGHTS") "A01 default weights missing"
    Assert-True ($a01.result.resolvedCriteria.defaultsApplied -contains "BUFFER_METERS") "A01 default buffer marker missing"
    Assert-True (@($a01.result.housingCandidates).Count -gt 0) "A01 returned no housing candidates"
    Assert-Equal $a01.result.housingCandidates[0].scores.weights.convenience 0.5 "A01 convenience weight"
    Assert-Equal $a01.result.housingCandidates[0].scores.weights.roadWalkability 0.5 "A01 road weight"
    $cases.Add($a01)

    $a02Arguments = New-HousingArguments -Mode "BUFFER_FILTER" -Districts @() -HardFilters @{} -Preferences (
        New-Preferences -PriceEnabled $false -PriceWeight $null `
            -ConvenienceEnabled $false -ConvenienceLevel "PREFER_HIGH" -ConvenienceWeight 0 `
            -RoadEnabled $true -RoadLevel "HIGH" -RoadWeight 1
    ) -RoadCriteria @{} -BufferMeters $null -IncludeRoads $true -IncludeBuffers $true
    $a02 = Invoke-HousingCase -CaseId "A02" -Description "Supported-region P75 with default 100m buffer" -Arguments $a02Arguments
    Assert-Equal $a02.result.statisticsScope.type "SUPPORTED_REGION" "A02 statistics scope"
    Assert-Equal $a02.result.resolvedCriteria.roadWsThresholdPercentile 75 "A02 percentile"
    Assert-Equal $a02.result.resolvedCriteria.bufferMeters 100 "A02 buffer"
    Assert-True (@($a02.result.roadFeatures).Count -gt 0) "A02 returned no roads"
    Assert-True (@($a02.result.bufferOverlays).Count -gt 0) "A02 returned no buffers"
    $cases.Add($a02)

    $a03 = Invoke-HousingCase -CaseId "A03" -Description "Explicit WS 75 and 300m without percentile/default buffer" -Arguments (
        New-HousingArguments -Mode "BUFFER_FILTER" -Districts @() -HardFilters @{} -Preferences (
            New-Preferences -PriceEnabled $false -PriceWeight $null `
                -ConvenienceEnabled $false -ConvenienceLevel "PREFER_HIGH" -ConvenienceWeight 0 `
                -RoadEnabled $true -RoadLevel "PREFER_HIGH" -RoadWeight 1
        ) -RoadCriteria @{ wsMin = 75 } -BufferMeters 300 -IncludeRoads $true -IncludeBuffers $true
    )
    Assert-Equal $a03.result.resolvedCriteria.roadWsThreshold 75 "A03 WS threshold"
    Assert-True ($null -eq $a03.result.resolvedCriteria.roadWsThresholdPercentile) "A03 unexpectedly used a percentile"
    Assert-Equal $a03.result.resolvedCriteria.bufferMeters 300 "A03 buffer"
    Assert-True (-not ($a03.result.resolvedCriteria.defaultsApplied -contains "BUFFER_METERS")) "A03 applied default buffer"
    $cases.Add($a03)

    $a04 = Invoke-HousingCase -CaseId "A04" -Description "Zhongshan rank with explicit convenience priority" -Arguments (
        New-HousingArguments -Mode "RANK" -Districts @($zhongshanDistrict) -HardFilters @{ priceMax = 15000 } -Preferences (
            New-Preferences -PriceEnabled $false -PriceWeight $null `
                -ConvenienceEnabled $true -ConvenienceLevel "PREFER_HIGH" -ConvenienceWeight 0.8 `
                -RoadEnabled $true -RoadLevel "PREFER_HIGH" -RoadWeight 0.2
        ) -RoadCriteria @{} -BufferMeters $null -IncludeRoads $false -IncludeBuffers $false
    )
    Assert-Equal $a04.result.statisticsScope.type "DISTRICT" "A04 statistics scope"
    Assert-Equal $a04.result.statisticsScope.districts[0] $zhongshanDistrict "A04 district"
    Assert-True (@($a04.result.housingCandidates).Count -gt 0) "A04 returned no housing candidates"
    Assert-Equal $a04.result.housingCandidates[0].scores.weights.convenience 0.8 "A04 convenience weight"
    Assert-Equal $a04.result.housingCandidates[0].scores.weights.roadWalkability 0.2 "A04 road weight"
    $cases.Add($a04)

    $a05Arguments = New-HousingArguments -Mode "BUFFER_FILTER" -Districts @() -HardFilters @{} -Preferences (
        New-Preferences -PriceEnabled $false -PriceWeight $null `
            -ConvenienceEnabled $false -ConvenienceLevel "PREFER_HIGH" -ConvenienceWeight 0 `
            -RoadEnabled $true -RoadLevel "VERY_HIGH" -RoadWeight 1
    ) -RoadCriteria @{} -BufferMeters $null -IncludeRoads $true -IncludeBuffers $true
    $a05 = Invoke-HousingCase -CaseId "A05" -Description "Supported-region P90" -Arguments $a05Arguments
    Assert-Equal $a05.result.statisticsScope.type "SUPPORTED_REGION" "A05 statistics scope"
    Assert-Equal $a05.result.resolvedCriteria.roadWsThresholdPercentile 90 "A05 percentile"
    Assert-True (-not [bool]$a05.result.resolvedCriteria.relaxationApplied) "A05 silently relaxed constraints"
    $cases.Add($a05)

    $a06 = Invoke-HousingCase -CaseId "A06" -Description "Prefer low price without inventing priceMax" -Arguments (
        New-HousingArguments -Mode "RANK" -Districts @() -HardFilters @{} -Preferences (
            New-Preferences -PriceEnabled $true -PriceWeight 0.5 `
                -ConvenienceEnabled $true -ConvenienceLevel "PREFER_HIGH" -ConvenienceWeight 0.5 `
                -RoadEnabled $false -RoadLevel "PREFER_HIGH" -RoadWeight 0
        ) -RoadCriteria @{} -BufferMeters $null -IncludeRoads $false -IncludeBuffers $false
    )
    Assert-True ($null -eq $a06.result.resolvedCriteria.priceMax) "A06 invented priceMax"
    Assert-True (@($a06.result.housingCandidates).Count -gt 0) "A06 returned no housing candidates"
    Assert-True ($null -ne $a06.result.housingCandidates[0].scores.priceAffordabilityPercentile) "A06 missing price percentile"
    Assert-True ($null -ne $a06.result.housingCandidates[0].scores.conveniencePercentile) "A06 missing convenience percentile"
    $cases.Add($a06)

    $a07 = Invoke-HousingCase -CaseId "A07" -Description "Reject 10000m buffer without clamping" -Arguments (
        New-HousingArguments -Mode "BUFFER_FILTER" -Districts @() -HardFilters @{} -Preferences (
            New-Preferences -PriceEnabled $false -PriceWeight $null `
                -ConvenienceEnabled $false -ConvenienceLevel "PREFER_HIGH" -ConvenienceWeight 0 `
                -RoadEnabled $true -RoadLevel "HIGH" -RoadWeight 1
        ) -RoadCriteria @{} -BufferMeters 10000 -IncludeRoads $true -IncludeBuffers $true
    ) -ExpectedHttpStatus 400 -ExpectedErrorCode "INVALID_BUFFER_DISTANCE"
    $cases.Add($a07)

    $a08 = Invoke-HousingCase -CaseId "A08" -Description "Empty housing keeps qualified roads and buffers" -Arguments (
        New-HousingArguments -Mode "BUFFER_FILTER" -Districts @($zhongshanDistrict) -HardFilters @{ priceMax = 0 } -Preferences (
            New-Preferences -PriceEnabled $false -PriceWeight $null `
                -ConvenienceEnabled $false -ConvenienceLevel "PREFER_HIGH" -ConvenienceWeight 0 `
                -RoadEnabled $true -RoadLevel "VERY_HIGH" -RoadWeight 1
        ) -RoadCriteria @{} -BufferMeters 20 -IncludeRoads $true -IncludeBuffers $true
    )
    Assert-Equal (@($a08.result.housingCandidates).Count) 0 "A08 housing count"
    Assert-True (@($a08.result.roadFeatures).Count -gt 0) "A08 returned no roads"
    Assert-True (@($a08.result.bufferOverlays).Count -gt 0) "A08 returned no buffers"
    Assert-True ($a08.result.warnings -contains "NO_HOUSING_IN_BUFFER") "A08 warning missing"
    $cases.Add($a08)

    $a09ToolCallId = [guid]::NewGuid().ToString()
    $a09First = Invoke-HousingCase -CaseId "A09-first" -Description "Idempotency baseline" `
        -Arguments (New-HousingArguments -Mode "RANK" -Districts @() -HardFilters @{} -Preferences (
            New-Preferences -PriceEnabled $true -PriceWeight 1 `
                -ConvenienceEnabled $false -ConvenienceLevel "PREFER_HIGH" -ConvenienceWeight 0 `
                -RoadEnabled $false -RoadLevel "PREFER_HIGH" -RoadWeight 0
        ) -RoadCriteria @{} -BufferMeters $null -IncludeRoads $false -IncludeBuffers $false) `
        -ToolCallId $a09ToolCallId
    $a09Retry = Invoke-HousingCase -CaseId "A09-retry" -Description "Same toolCallId and arguments" `
        -Arguments (New-HousingArguments -Mode "RANK" -Districts @() -HardFilters @{} -Preferences (
            New-Preferences -PriceEnabled $true -PriceWeight 1 `
                -ConvenienceEnabled $false -ConvenienceLevel "PREFER_HIGH" -ConvenienceWeight 0 `
                -RoadEnabled $false -RoadLevel "PREFER_HIGH" -RoadWeight 0
        ) -RoadCriteria @{} -BufferMeters $null -IncludeRoads $false -IncludeBuffers $false) `
        -ToolCallId $a09ToolCallId
    $a09FirstData = $a09First.rawResponse.json.data | ConvertTo-Json -Depth 100 -Compress
    $a09RetryData = $a09Retry.rawResponse.json.data | ConvertTo-Json -Depth 100 -Compress
    Assert-Equal $a09RetryData $a09FirstData "A09 retry execution data"
    $cases.Add($a09Retry)

    $a10 = Invoke-HousingCase -CaseId "A10" -Description "Same toolCallId with different arguments conflicts" `
        -Arguments (New-HousingArguments -Mode "RANK" -Districts @() -HardFilters @{} -Preferences (
            New-Preferences -PriceEnabled $true -PriceWeight 1 `
                -ConvenienceEnabled $false -ConvenienceLevel "PREFER_HIGH" -ConvenienceWeight 0 `
                -RoadEnabled $false -RoadLevel "PREFER_HIGH" -RoadWeight 0
        ) -RoadCriteria @{} -BufferMeters $null -IncludeRoads $false -IncludeBuffers $false -Limit 19) `
        -ToolCallId $a09ToolCallId -ExpectedHttpStatus 409 -ExpectedErrorCode "TOOL_CALL_CONFLICT"
    $cases.Add($a10)

    $a11Disabled = Invoke-HousingCase -CaseId "A11-disabled-road" `
        -Description "BUFFER_FILTER rejects disabled road preference" -Arguments (
            New-HousingArguments -Mode "BUFFER_FILTER" -Districts @() -HardFilters @{} -Preferences (
                New-Preferences -PriceEnabled $false -PriceWeight $null `
                    -ConvenienceEnabled $false -ConvenienceLevel "PREFER_HIGH" -ConvenienceWeight 0 `
                    -RoadEnabled $false -RoadLevel "HIGH" -RoadWeight 0
            ) -RoadCriteria @{} -BufferMeters $null -IncludeRoads $true -IncludeBuffers $true
        ) -ExpectedHttpStatus 400 -ExpectedErrorCode "INVALID_HOUSING_SEARCH_ARGUMENT"
    $cases.Add($a11Disabled)

    $a11UnknownArguments = New-HousingArguments -Mode "BUFFER_FILTER" -Districts @() -HardFilters @{} -Preferences (
        New-Preferences -PriceEnabled $false -PriceWeight $null `
            -ConvenienceEnabled $false -ConvenienceLevel "PREFER_HIGH" -ConvenienceWeight 0 `
            -RoadEnabled $true -RoadLevel "HIGH" -RoadWeight 1
    ) -RoadCriteria @{} -BufferMeters $null -IncludeRoads $true -IncludeBuffers $true
    $a11UnknownArguments.roadCriteria = @{ newWalkingMin = 80 }
    $a11Unknown = Invoke-HousingCase -CaseId "A11-new-walking" `
        -Description "Reject newWalking as a substitute for road WS" -Arguments $a11UnknownArguments `
        -ExpectedHttpStatus 400 -ExpectedErrorCode "INVALID_HOUSING_SEARCH_ARGUMENT"
    $cases.Add($a11Unknown)

    $performanceRows = New-Object System.Collections.Generic.List[object]
    foreach ($scenario in @(
        [pscustomobject]@{ name = "P75"; arguments = $a02Arguments },
        [pscustomobject]@{ name = "P90"; arguments = $a05Arguments }
    )) {
        $performanceArguments = $scenario.arguments
        $performanceArguments.display.includeRoads = $false
        $performanceArguments.display.includeBuffers = $false
        foreach ($iteration in 1..$PerformanceRuns) {
            $value = Invoke-HousingCase -CaseId "PERF-$($scenario.name)-$iteration" `
                -Description "Warm-path performance" -Arguments $performanceArguments -SkipFixture
            $performanceRows.Add([pscustomobject]@{
                scenario = $scenario.name
                iteration = $iteration
                wallDurationMs = $value.wallDurationMs
                toolDurationMs = $value.toolDurationMs
                matchedHousingCount = $value.result.summary.matchedHousingCount
                returnedHousingCount = $value.result.summary.returnedHousingCount
                matchedRoadCount = $value.result.summary.matchedRoadCount
            })
        }
    }

    $performanceSummary = foreach ($scenarioName in @("P75", "P90")) {
        $rows = @($performanceRows | Where-Object scenario -eq $scenarioName)
        $wall = [long[]]@($rows | Select-Object -ExpandProperty wallDurationMs)
        $tool = [long[]]@($rows | Select-Object -ExpandProperty toolDurationMs)
        [pscustomobject]@{
            scenario = $scenarioName
            runs = $rows.Count
            wallP50Ms = Get-PercentileValue -Values $wall -Percentile 0.50
            wallP95Ms = Get-PercentileValue -Values $wall -Percentile 0.95
            toolP50Ms = Get-PercentileValue -Values $tool -Percentile 0.50
            toolP95Ms = Get-PercentileValue -Values $tool -Percentile 0.95
            toolTargetMs = 3000
            passed = (Get-PercentileValue -Values $tool -Percentile 0.95) -lt 3000
        }
    }

    $caseSummary = @($cases | ForEach-Object {
        $caseErrorCode = if ($null -ne $_.error) { $_.error.code } else { $null }
        [pscustomobject]@{
            caseId = $_.caseId
            description = $_.description
            httpStatus = $_.httpStatus
            toolStatus = $_.toolStatus
            errorCode = $caseErrorCode
            wallDurationMs = $_.wallDurationMs
            toolDurationMs = $_.toolDurationMs
        }
    })
    $summary = [ordered]@{
        generatedAt = [DateTimeOffset]::UtcNow.ToString("O")
        springBaseUrl = $baseUrl
        catalogVersion = $catalog.json.data.version
        snapshot = $health.json.data.housingSnapshot
        cases = $caseSummary
        performance = $performanceSummary
        performanceRows = $performanceRows
        passed = $true
    }
    Write-JsonFile -Path (Join-Path $outputPath "summary.json") -Value $summary
    $summary | ConvertTo-Json -Depth 100
} finally {
    $client.Dispose()
}
