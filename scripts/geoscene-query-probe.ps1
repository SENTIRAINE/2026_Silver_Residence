param(
    [string]$SpringBaseUrl = "http://127.0.0.1:8080",
    [int]$TimeoutSeconds = 10,
    [switch]$IncludeRealData
)

$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"

function Invoke-Probe {
    param(
        [int]$LayerId,
        [string]$Phase,
        [string]$Url
    )

    $timer = [Diagnostics.Stopwatch]::StartNew()
    try {
        $response = Invoke-WebRequest -UseBasicParsing -Uri $Url -TimeoutSec $TimeoutSeconds
        [pscustomobject]@{
            layerId = $LayerId
            phase = $Phase
            ok = $response.StatusCode -eq 200
            status = [int]$response.StatusCode
            durationMs = $timer.ElapsedMilliseconds
            bytes = $response.RawContentLength
            error = $null
        }
    } catch {
        [pscustomobject]@{
            layerId = $LayerId
            phase = $Phase
            ok = $false
            status = if ($_.Exception.Response) { [int]$_.Exception.Response.StatusCode } else { $null }
            durationMs = $timer.ElapsedMilliseconds
            bytes = 0
            error = $_.Exception.GetType().Name
        }
    }
}

$base = $SpringBaseUrl.TrimEnd("/")
$results = @()
foreach ($layerId in 0..5) {
    $results += Invoke-Probe -LayerId $layerId -Phase "metadata" `
        -Url "$base/api/map/geoscene/$layerId`?f=json"
    $results += Invoke-Probe -LayerId $layerId -Phase "empty-count" `
        -Url "$base/api/map/geoscene/$layerId/query?where=1%3D0&returnCountOnly=true&f=json"
}

if ($IncludeRealData) {
    foreach ($layerId in 0..5) {
        $timer = [Diagnostics.Stopwatch]::StartNew()
        try {
            $countResponse = Invoke-WebRequest -UseBasicParsing `
                -Uri "$base/api/map/geoscene/$layerId/query?where=1%3D1&returnCountOnly=true&f=json" `
                -TimeoutSec $TimeoutSeconds
            $countBody = $countResponse.Content | ConvertFrom-Json
            $expectedCount = [int]$countBody.count
            $results += [pscustomobject]@{
                layerId = $layerId
                phase = "real-count"
                ok = $countResponse.StatusCode -eq 200 -and -not $countBody.error
                status = [int]$countResponse.StatusCode
                durationMs = $timer.ElapsedMilliseconds
                bytes = $countResponse.RawContentLength
                count = $expectedCount
                error = if ($countBody.error) { $countBody.error | ConvertTo-Json -Compress } else { $null }
            }
        } catch {
            $results += [pscustomobject]@{
                layerId = $layerId
                phase = "real-count"
                ok = $false
                status = if ($_.Exception.Response) { [int]$_.Exception.Response.StatusCode } else { $null }
                durationMs = $timer.ElapsedMilliseconds
                bytes = 0
                count = $null
                error = $_.Exception.GetType().Name
            }
            continue
        }

        $timer.Restart()
        try {
            $featureResponse = Invoke-WebRequest -UseBasicParsing `
                -Uri "$base/api/map/geoscene/$layerId/query?where=1%3D1&outFields=*&returnGeometry=true&f=json" `
                -TimeoutSec $TimeoutSeconds
            $featureBody = $featureResponse.Content | ConvertFrom-Json
            $actualCount = @($featureBody.features).Count
            $results += [pscustomobject]@{
                layerId = $layerId
                phase = "real-full"
                ok = $featureResponse.StatusCode -eq 200 -and `
                    -not $featureBody.error -and `
                    -not $featureBody.exceededTransferLimit -and `
                    $actualCount -eq $expectedCount
                status = [int]$featureResponse.StatusCode
                durationMs = $timer.ElapsedMilliseconds
                bytes = $featureResponse.RawContentLength
                count = $actualCount
                error = if ($featureBody.error) { $featureBody.error | ConvertTo-Json -Compress } else { $null }
            }
        } catch {
            $results += [pscustomobject]@{
                layerId = $layerId
                phase = "real-full"
                ok = $false
                status = if ($_.Exception.Response) { [int]$_.Exception.Response.StatusCode } else { $null }
                durationMs = $timer.ElapsedMilliseconds
                bytes = 0
                count = $null
                error = $_.Exception.GetType().Name
            }
        }
    }
}

$results | ConvertTo-Json
if ($results.Where({ -not $_.ok }).Count -gt 0) {
    exit 1
}
