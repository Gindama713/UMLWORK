param(
    [string]$GatewayUrl = 'http://127.0.0.1:18080',
    [ValidateRange(1, 60)][int]$ClosedCount = 24,
    [switch]$IncludeActive,
    [ValidateRange(1, 16)][int]$ActiveCount = 8
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
$from = [uri]::EscapeDataString($now.AddHours(-24).ToString('o'))
$baselineTo = [uri]::EscapeDataString($now.ToString('o'))
$baseline = Call-Api "/api/v1/analytics/traffic-preview?from=$from&to=$baselineTo&granularity=HOUR"
# ponytail: 只保证本批次回填的停车时段不复用同一车位；跨批次历史占位需要专门的历史查询接口。
$normalSpaces = @(Call-Api '/api/v1/spaces?type=NORMAL' | Where-Object { $_.status -eq 'AVAILABLE' })
$simultaneous = [math]::Ceiling($ClosedCount * 75.0 / (18 * 60))
if ($normalSpaces.Count -lt $simultaneous) { throw "可用普通车位不足以构造不重叠的历史停车：$($normalSpaces.Count) < $simultaneous" }
$batch = [guid]::NewGuid().ToString('N').Substring(0, 4).ToUpperInvariant()
for ($index = 0; $index -lt $ClosedCount; $index++) {
    $plate = '苏A' + $batch + ('{0:D2}' -f ($index + 1))
    $entryTime = $now.AddHours(-22 + (18 * $index / $ClosedCount))
    $exitTime = $entryTime.AddMinutes(75)
    $space = $normalSpaces[$index % $normalSpaces.Count]
    $session = Call-Api '/api/v1/access/entries' 'POST' @{ plateNumber = $plate; spaceType = 'NORMAL'; preferredSpaceId = $space.spaceId; entryTime = $entryTime.ToString('o') }
    $intent = Call-Api "/api/v1/access/$($session.parkingSessionId)/exit-requests" 'POST' @{ exitTime = $exitTime.ToString('o'); exceptionType = 'NONE' }
    $closed = Call-Api "/api/v1/access/$($session.parkingSessionId)/complete-exit" 'POST' @{ simulatedResult = 'SUCCESS' }
    if ($closed.status -ne 'CLOSED' -or !$closed.spaceReleased) { throw "未完成出场：$plate / $($session.parkingSessionId) / $($intent.billId)" }
    $missing = Invoke-WebRequest -Uri "$GatewayUrl/api/v1/access/locate?plateNumber=$([uri]::EscapeDataString($plate))" -SkipHttpErrorCheck
    if ($missing.StatusCode -ne 404 -or ($missing.Content | ConvertFrom-Json).code -ne 'NOT_FOUND') {
        throw "已出场车辆仍被当作在场：$plate / HTTP $($missing.StatusCode)"
    }
    Write-Output "CLOSED $plate $($session.spaceNumber) $($session.parkingSessionId)"
}

$activeAdded = 0
if ($IncludeActive) {
    for ($index = 1; $index -le $ActiveCount; $index++) {
        $plate = '苏AUX' + (100 + $index)
        $existing = Invoke-WebRequest -Uri "$GatewayUrl/api/v1/access/locate?plateNumber=$([uri]::EscapeDataString($plate))" -SkipHttpErrorCheck
        if ($existing.StatusCode -eq 200) { Write-Output "PARKED existing $plate"; continue }
        if ($existing.StatusCode -ne 404) { throw "查询在场车辆失败：HTTP $($existing.StatusCode) / $plate" }
        $available = @(Call-Api '/api/v1/spaces?type=NORMAL' | Where-Object { $_.status -eq 'AVAILABLE' })
        $space = $available | Where-Object { $_.floor -eq 'B3' } | Select-Object -First 1
        if (!$space) { $space = $available | Select-Object -First 1 }
        if (!$space) { throw '没有可用普通车位供在场演示车辆使用' }
        $session = Call-Api '/api/v1/access/entries' 'POST' @{ plateNumber = $plate; spaceType = 'NORMAL'; preferredSpaceId = $space.spaceId; entryTime = $now.AddMinutes(-25).ToString('o') }
        $located = Call-Api "/api/v1/access/locate?plateNumber=$([uri]::EscapeDataString($plate))"
        if ($located.parkingSessionId -ne $session.parkingSessionId -or $located.spaceId -ne $space.spaceId) {
            throw "在场车辆寻车位置与登记不一致：$plate"
        }
        $activeAdded++
        Write-Output "PARKED $plate $($session.spaceNumber) $($session.parkingSessionId)"
    }
}

$to = [uri]::EscapeDataString([DateTimeOffset]::Now.ToString('o'))
$preview = Call-Api "/api/v1/analytics/traffic-preview?from=$from&to=$to&granularity=HOUR"
$expected = $baseline.totalEntries + $ClosedCount + $activeAdded
if ($preview.totalEntries -lt $expected) { throw "车流预览少于基线加本次入场：$($preview.totalEntries) < $expected" }
Write-Output "TRAFFIC last24h=$($preview.totalEntries); added=$($ClosedCount + $activeAdded); buckets=$(@($preview.buckets).Count)"
