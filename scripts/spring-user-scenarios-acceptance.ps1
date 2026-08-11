param(
    [string]$SpringBaseUrl = "http://127.0.0.1:8080",
    [Parameter(Mandatory = $true)]
    [string]$Username,
    [Parameter(Mandatory = $true)]
    [string]$Password,
    [string]$OutputDirectory = "outputs/spring-comprehensive-2026-08-02/user-scenarios",
    [int]$TimeoutSeconds = 210
)

$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"
Set-StrictMode -Version Latest
Add-Type -AssemblyName System.Net.Http

$baseUrl = $SpringBaseUrl.TrimEnd("/")
$outputPath = [IO.Path]::GetFullPath((Join-Path (Get-Location) $OutputDirectory))
[IO.Directory]::CreateDirectory($outputPath) | Out-Null

function Write-Utf8File {
    param([string]$Path, [string]$Content)
    [IO.File]::WriteAllText($Path, $Content, [Text.UTF8Encoding]::new($false))
}

function ConvertTo-JsonText {
    param([object]$Value)
    return $Value | ConvertTo-Json -Depth 30
}

function Get-OptionalProperty {
    param([object]$Value, [string]$Name)
    if ($null -eq $Value) { return $null }
    $property = $Value.PSObject.Properties[$Name]
    if ($null -eq $property) { return $null }
    return $property.Value
}

function Get-ContractError {
    param([object]$Value)
    $error = Get-OptionalProperty $Value "error"
    return [pscustomobject]@{
        Code = Get-OptionalProperty $error "code"
        Message = Get-OptionalProperty $error "message"
    }
}

function New-Client {
    param([bool]$UseCookies = $true)
    $handler = [Net.Http.HttpClientHandler]::new()
    $handler.UseCookies = $UseCookies
    if ($UseCookies) {
        $handler.CookieContainer = [Net.CookieContainer]::new()
    }
    $client = [Net.Http.HttpClient]::new($handler)
    $client.Timeout = [TimeSpan]::FromSeconds($TimeoutSeconds)
    return [pscustomobject]@{ Client = $client; Handler = $handler }
}

function Invoke-Json {
    param(
        [Net.Http.HttpClient]$Client,
        [string]$Method,
        [string]$Path,
        [object]$Body,
        [hashtable]$Headers = @{}
    )
    $request = [Net.Http.HttpRequestMessage]::new(
        [Net.Http.HttpMethod]::new($Method),
        "$baseUrl$Path"
    )
    foreach ($entry in $Headers.GetEnumerator()) {
        $request.Headers.TryAddWithoutValidation($entry.Key, [string]$entry.Value) | Out-Null
    }
    if ($null -ne $Body) {
        $json = ConvertTo-JsonText $Body
        $request.Content = [Net.Http.StringContent]::new($json, [Text.Encoding]::UTF8, "application/json")
    }
    $timer = [Diagnostics.Stopwatch]::StartNew()
    try {
        $response = $Client.SendAsync($request).GetAwaiter().GetResult()
        $text = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
        $contentType = [string]$response.Content.Headers.ContentType
        return [pscustomobject]@{
            Status = [int]$response.StatusCode
            ContentType = $contentType
            DurationMs = $timer.ElapsedMilliseconds
            Text = $text
            Json = if (-not [string]::IsNullOrWhiteSpace($text) -and $contentType -match "json") {
                $text | ConvertFrom-Json
            } else { $null }
        }
    } finally {
        $request.Dispose()
    }
}

function Read-SseEvents {
    param([string]$Text)
    $events = New-Object System.Collections.Generic.List[object]
    $eventName = "message"
    foreach ($line in ($Text -split "`r?`n")) {
        if ($line.StartsWith("event:")) {
            $eventName = $line.Substring(6).Trim()
        } elseif ($line.StartsWith("data:")) {
            $jsonText = $line.Substring(5).Trim()
            try {
                $events.Add([pscustomobject]@{
                    Name = $eventName
                    Envelope = $jsonText | ConvertFrom-Json
                })
            } catch {
                $events.Add([pscustomobject]@{
                    Name = $eventName
                    Envelope = $null
                })
            }
        }
    }
    return [object[]]$events
}

function Invoke-AssistantCase {
    param(
        [Net.Http.HttpClient]$Client,
        [pscustomobject]$Case,
        [string]$ConversationId
    )
    $messageId = [guid]::NewGuid().ToString()
    $body = [ordered]@{
        conversationId = $ConversationId
        messageId = $messageId
        query = $Case.Query
        context = [ordered]@{
            locale = "zh-CN"
            map = [ordered]@{
                visibleLayerIds = @(0, 1, 2, 3, 4, 5)
                zoom = 13
                extent = $null
            }
            businessObjectIds = @()
        }
    }
    $requestText = ConvertTo-JsonText $body
    Write-Utf8File (Join-Path $outputPath "$($Case.Id).request.json") $requestText

    $response = Invoke-Json -Client $Client -Method "POST" -Path "/api/assistant/runs/stream" `
        -Body $body -Headers @{ Accept = "text/event-stream"; "X-Trace-Id" = "spring-user-$($Case.Id)-$messageId" }
    Write-Utf8File (Join-Path $outputPath "$($Case.Id).sse.txt") $response.Text
    $events = @(Read-SseEvents $response.Text)
    $terminal = $events | Where-Object { $_.Name -in @("run.completed", "run.failed", "run.cancelled", "preflight.failed") } | Select-Object -Last 1
    $route = $events | Where-Object { $_.Name -eq "route.selected" } | Select-Object -Last 1
    $map = $events | Where-Object { $_.Name -eq "map.result" } | Select-Object -Last 1
    $answer = $events | Where-Object { $_.Name -eq "run.completed" } | Select-Object -Last 1
    $terminalPayload = if ($terminal) { Get-OptionalProperty $terminal.Envelope "payload" } else { $null }
    $terminalError = Get-OptionalProperty $terminalPayload "error"
    $errorCode = [string](Get-OptionalProperty $terminalError "code")
    $answerPayload = if ($answer) { Get-OptionalProperty $answer.Envelope "payload" } else { $null }
    $answerText = [string](Get-OptionalProperty $answerPayload "answer")
    $routePayload = if ($route) { Get-OptionalProperty $route.Envelope "payload" } else { $null }
    $mapPayload = if ($map) { Get-OptionalProperty $map.Envelope "payload" } else { $null }
    return [pscustomobject]@{
        caseId = $Case.Id
        category = $Case.Category
        query = $Case.Query
        httpStatus = $response.Status
        contentType = $response.ContentType
        durationMs = $response.DurationMs
        events = [object[]]@($events | ForEach-Object Name)
        terminalEvent = if ($terminal) { $terminal.Name } else { $null }
        intent = [string](Get-OptionalProperty $routePayload "intent")
        errorCode = $errorCode
        answerLength = $answerText.Length
        answer = $answerText
        resultSetCount = if ($map) { @((Get-OptionalProperty $mapPayload "resultSets")).Count } else { 0 }
        overlayCount = if ($map) { @((Get-OptionalProperty $mapPayload "overlays")).Count } else { 0 }
        warnings = if ($map) { [object[]]@((Get-OptionalProperty $mapPayload "warnings")) } else { [object[]]@() }
    }
}

$session = New-Client
$anonymous = New-Client
try {
    $login = Invoke-Json -Client $session.Client -Method "POST" -Path "/user/login" -Body @{
        username = $Username
        password = $Password
    }
    if ($login.Status -ne 200 -or $login.Json.code -ne 1) {
        throw "Test user login failed: HTTP $($login.Status)"
    }

    $wrongPassword = Invoke-Json -Client $anonymous.Client -Method "POST" -Path "/user/login" -Body @{
        username = $Username
        password = "definitely-wrong-password"
    }
    $missingCredentials = Invoke-Json -Client $anonymous.Client -Method "POST" -Path "/user/login" -Body @{}
    $unknownRoute = Invoke-Json -Client $anonymous.Client -Method "GET" -Path "/api/does-not-exist" -Body $null
    $mapConfig = Invoke-Json -Client $anonymous.Client -Method "GET" -Path "/api/map/config" -Body $null
    $rawWhere = Invoke-Json -Client $anonymous.Client -Method "POST" -Path "/api/map/query-features" -Body @{
        layerId = 0
        filters = @()
        where = "1=1 OR 1=1"
        outFields = @("OBJECTID", "name")
        returnGeometry = $false
        resultRecordCount = 10
    }
    $catalogWithoutToken = Invoke-Json -Client $anonymous.Client -Method "GET" -Path "/internal/agent-tools/catalog" `
        -Body $null -Headers @{ "X-Trace-Id" = "spring-security-no-token" }

    $anonymousBody = [ordered]@{
        conversationId = [guid]::NewGuid().ToString()
        messageId = [guid]::NewGuid().ToString()
        query = "帮我找个走路方便的小区"
        context = [ordered]@{ locale = "zh-CN"; businessObjectIds = @() }
    }
    $anonymousAssistant = Invoke-Json -Client $anonymous.Client -Method "POST" -Path "/api/assistant/runs/stream" `
        -Body $anonymousBody -Headers @{ Accept = "text/event-stream" }
    Write-Utf8File (Join-Path $outputPath "B07-anonymous-assistant.sse.txt") $anonymousAssistant.Text

    $wrongPasswordError = Get-ContractError $wrongPassword.Json
    $missingCredentialsError = Get-ContractError $missingCredentials.Json
    $unknownRouteError = Get-ContractError $unknownRoute.Json
    $rawWhereError = Get-ContractError $rawWhere.Json
    $catalogWithoutTokenError = Get-ContractError $catalogWithoutToken.Json
    $boundaryRows = @(
        [pscustomobject]@{ caseId = "B01-wrong-password"; httpStatus = $wrongPassword.Status; code = Get-OptionalProperty $wrongPassword.Json "code"; errorCode = $wrongPasswordError.Code; message = if ($wrongPasswordError.Message) { $wrongPasswordError.Message } else { Get-OptionalProperty $wrongPassword.Json "msg" } },
        [pscustomobject]@{ caseId = "B02-missing-credentials"; httpStatus = $missingCredentials.Status; code = Get-OptionalProperty $missingCredentials.Json "code"; errorCode = $missingCredentialsError.Code; message = if ($missingCredentialsError.Message) { $missingCredentialsError.Message } else { Get-OptionalProperty $missingCredentials.Json "msg" } },
        [pscustomobject]@{ caseId = "B03-unknown-route"; httpStatus = $unknownRoute.Status; code = $null; errorCode = $unknownRouteError.Code; message = $unknownRouteError.Message },
        [pscustomobject]@{ caseId = "B04-map-config"; httpStatus = $mapConfig.Status; code = $null; errorCode = $null; message = $null },
        [pscustomobject]@{ caseId = "B05-raw-where"; httpStatus = $rawWhere.Status; code = $null; errorCode = $rawWhereError.Code; message = $rawWhereError.Message },
        [pscustomobject]@{ caseId = "B06-catalog-no-token"; httpStatus = $catalogWithoutToken.Status; code = $null; errorCode = $catalogWithoutTokenError.Code; message = $catalogWithoutTokenError.Message },
        [pscustomobject]@{ caseId = "B07-anonymous-assistant"; httpStatus = $anonymousAssistant.Status; code = $null; errorCode = (@(Read-SseEvents $anonymousAssistant.Text) | Select-Object -Last 1).Envelope.payload.error.code; message = $null }
    )

    $cases = @(
        [pscustomobject]@{ Id = "U01-standard"; Category = "标准找房"; Query = "中山区房价不超过一万五，便利一点的房子给我看看" },
        [pscustomobject]@{ Id = "U02-elderly-mobility"; Category = "老年人口语"; Query = "我年纪大了腿脚不好，想找走路方便点的地方住，钱别太贵，最好一万二一平以内" },
        [pscustomobject]@{ Id = "U03-vague-needs"; Category = "模糊需求"; Query = "帮我看看住哪儿合适，别太吵，买菜方便点" },
        [pscustomobject]@{ Id = "U04-filler-words"; Category = "口头语赘词"; Query = "那个啥，我就想找个便宜点儿的，出门溜达路好走的小区" },
        [pscustomobject]@{ Id = "U05-typos"; Category = "错别字"; Query = "中山去 房价一万五一内 步行指树高点" },
        [pscustomobject]@{ Id = "U06-colloquial-distance"; Category = "口语距离"; Query = "道路步行指数高点附近有啥小区，百来米就行" },
        [pscustomobject]@{ Id = "U07-invalid-distance"; Category = "越界条件"; Query = "高步行指数道路一万米以内的小区" },
        [pscustomobject]@{ Id = "U08-rag-explanation"; Category = "非专业指标问答"; Query = "这上面的步行指数是啥意思？我看不懂" },
        [pscustomobject]@{ Id = "U09-couple-complex"; Category = "复合养老需求"; Query = "我和老伴住，房价别过一万三，便利度高一点，附近路走着舒服，先给我挑几个，再说说为什么" },
        [pscustomobject]@{ Id = "U10-total-budget"; Category = "口径不明确"; Query = "手里就二百万，想买个合适养老的房子" },
        [pscustomobject]@{ Id = "U11-highly-vague"; Category = "高度含糊"; Query = "嗯……那个……就那种好的，住着省心的" },
        [pscustomobject]@{ Id = "U12-short-request"; Category = "极短表达"; Query = "便宜，好走路" },
        [pscustomobject]@{ Id = "U13-long-query"; Category = "Spring 输入边界"; Query = ("老" * 4001) }
    )

    $conversationId = [guid]::NewGuid().ToString()
    $scenarioRows = New-Object System.Collections.Generic.List[object]
    foreach ($case in $cases) {
        $scenarioRows.Add((Invoke-AssistantCase -Client $session.Client -Case $case -ConversationId $conversationId))
    }

    $summary = [ordered]@{
        generatedAt = [DateTimeOffset]::UtcNow.ToString("o")
        springBaseUrl = $baseUrl
        login = [ordered]@{
            httpStatus = $login.Status
            code = $login.Json.code
            passwordReturned = $null -ne $login.Json.data.password
        }
        boundaryCases = $boundaryRows
        userScenarios = [object[]]$scenarioRows
    }
    $summaryText = ConvertTo-JsonText $summary
    Write-Utf8File (Join-Path $outputPath "summary.json") $summaryText
    $summaryText
} finally {
    $session.Client.Dispose()
    $session.Handler.Dispose()
    $anonymous.Client.Dispose()
    $anonymous.Handler.Dispose()
}
