param(
    [string]$GatewayUrl = 'http://127.0.0.1:18080',
    [ValidateRange(1, 12)][int]$ClosedCount = 6,
    [switch]$IncludeActive
)
# 【AI-辅助】按正式 API 追加演示停车记录；每次运行都会新增已关闭记录，不会删除或改写历史。
$ErrorActionPreference = 'Stop'

function Call-Api([string]$Path, [string]$Method = 'GET', $Body = $null) {
    $parameters = @{ Uri = "$GatewayUrl$Path"; Method = $Method; SkipHttpErrorCheck = $true }
    if ($Method -ne 'GET') {
        $parameters.Headers = @{ 'Idempotency-Key' = [guid]::NewGuid().ToString() }
        $parameters.ContentType = 'application/json; charset=utf-8'
        $parameters.Body = ConvertTo-Json -InputObject $Body -Depth 5 -Compress
    }
    $response = Invoke-WebRequest @parameters
    $envelope = $response.Content | ConvertFrom-Json
    if ($response.StatusCode -ne 200 -or $envelope.code -ne 'OK') {
        throw "$Method $Path -> HTTP $($response.StatusCode): $($envelope.code) $($envelope.message)"
    }
    $envelope.data
}

$now = [DateTimeOffset]::Now
for ($index = 0; $index -lt $ClosedCount; $index++) {
    $plate = '苏A' + [guid]::NewGuid().ToString('N').Substring(0, 5).ToUpperInvariant()
    $entryTime = $now.AddHours(-22 + (18 * $index / $ClosedCount))
    $exitTime = $entryTime.AddMinutes(75)
    $session = Call-Api '/api/v1/access/entries' 'POST' @{ plateNumber = $plate; spaceType = 'NORMAL'; entryTime = $entryTime.ToString('o') }
    $intent = Call-Api "/api/v1/access/$($session.parkingSessionId)/exit-requests" 'POST' @{ exitTime = $exitTime.ToString('o'); exceptionType = 'NONE' }
    $closed = Call-Api "/api/v1/access/$($session.parkingSessionId)/complete-exit" 'POST' @{ simulatedResult = 'SUCCESS' }
    if ($closed.status -ne 'CLOSED' -or !$closed.spaceReleased) { throw "未完成出场：$plate / $($session.parkingSessionId) / $($intent.billId)" }
    Write-Output "CLOSED $plate $($session.spaceNumber) $($session.parkingSessionId)"
}

if ($IncludeActive) {
    foreach ($plate in @('苏AUX101', '苏AUX102')) {
        $existing = Invoke-WebRequest -Uri "$GatewayUrl/api/v1/access/locate?plateNumber=$([uri]::EscapeDataString($plate))" -SkipHttpErrorCheck
        if ($existing.StatusCode -eq 200) { Write-Output "PARKED existing $plate"; continue }
        if ($existing.StatusCode -ne 404) { throw "查询在场车辆失败：HTTP $($existing.StatusCode) / $plate" }
        $session = Call-Api '/api/v1/access/entries' 'POST' @{ plateNumber = $plate; spaceType = 'NORMAL'; entryTime = $now.AddMinutes(-25).ToString('o') }
        Write-Output "PARKED $plate $($session.spaceNumber) $($session.parkingSessionId)"
    }
}

$from = [uri]::EscapeDataString($now.AddHours(-24).ToString('o'))
$to = [uri]::EscapeDataString([DateTimeOffset]::Now.ToString('o'))
$preview = Call-Api "/api/v1/analytics/traffic-preview?from=$from&to=$to&granularity=HOUR"
if ($preview.totalEntries -lt $ClosedCount) { throw "车流预览少于本次已关闭记录：$($preview.totalEntries) < $ClosedCount" }
Write-Output "TRAFFIC last24h=$($preview.totalEntries); buckets=$(@($preview.buckets).Count)"
