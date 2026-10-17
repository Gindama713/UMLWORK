param([string]$GatewayUrl = 'http://127.0.0.1:18080')

$ErrorActionPreference = 'Stop'
$base = $GatewayUrl.TrimEnd('/')

function Call-Api($method, $path, $body = $null, $key = $null) {
    $args = @{ Uri = "$base$path"; Method = $method; SkipHttpErrorCheck = $true }
    if ($null -ne $body) {
        $args.ContentType = 'application/json'
        $args.Body = $body | ConvertTo-Json -Compress
    }
    if ($key) { $args.Headers = @{ 'Idempotency-Key' = $key } }
    $response = Invoke-WebRequest @args
    return @{ Status = [int]$response.StatusCode; Body = ($response.Content | ConvertFrom-Json) }
}

function Require-Ok($response, $step) {
    if ($response.Status -ne 200 -or $response.Body.code -ne 'OK') {
        throw "$step failed: HTTP $($response.Status), code=$($response.Body.code)"
    }
    return $response.Body.data
}

$estimate = Require-Ok (Call-Api POST '/api/v1/billing/parking-estimates' @{
    entryTime = '2026-10-01T08:00:00+08:00'
    exitTime = '2026-10-01T10:00:00+08:00'
}) 'estimate'
if ($estimate.amountDueCents -ne 1000 -or !$estimate.rateVersion) {
    throw "Unexpected ordinary parking estimate: $($estimate.amountDueCents)"
}

$candidates = Require-Ok (Call-Api GET '/api/v1/spaces/recommendations?terminal=T1&type=NORMAL') 'recommendation'
if (!$candidates -or $candidates[0].status -ne 'AVAILABLE') { throw 'No available normal candidate' }
$chosen = $candidates[0]
$plate = '苏Z' + [guid]::NewGuid().ToString('N').Substring(0, 6).ToUpperInvariant()
$key = [guid]::NewGuid().ToString()
$payload = @{ plateNumber = $plate; spaceType = 'NORMAL'; preferredSpaceId = $chosen.spaceId }
$entry = Require-Ok (Call-Api POST '/api/v1/access/entries' $payload $key) 'preferred entry'
if ($entry.spaceId -ne $chosen.spaceId -or $entry.status -ne 'PARKED') {
    throw 'Preferred entry used a different space or failed to park'
}
$retry = Require-Ok (Call-Api POST '/api/v1/access/entries' $payload $key) 'idempotent entry retry'
if ($retry.parkingSessionId -ne $entry.parkingSessionId) { throw 'Retry created another session' }

$competitor = Call-Api POST '/api/v1/access/entries' @{
    plateNumber = '苏Y' + [guid]::NewGuid().ToString('N').Substring(0, 6).ToUpperInvariant()
    spaceType = 'NORMAL'
    preferredSpaceId = $chosen.spaceId
} ([guid]::NewGuid().ToString())
if ($competitor.Status -ne 409 -or $competitor.Body.code -ne 'SPACE_UNAVAILABLE') {
    throw "Stale recommendation was not rejected: HTTP $($competitor.Status), code=$($competitor.Body.code)"
}

$exit = Require-Ok (Call-Api POST "/api/v1/access/$($entry.parkingSessionId)/exit-requests" @{
    exitTime = [DateTimeOffset]::UtcNow.AddMinutes(1).ToString('o')
    exceptionType = 'NONE'
} ([guid]::NewGuid().ToString())) 'exit request'
$closed = Require-Ok (Call-Api POST "/api/v1/access/$($entry.parkingSessionId)/complete-exit" @{
    simulatedResult = 'SUCCESS'
} ([guid]::NewGuid().ToString())) 'complete exit'
if ($closed.status -ne 'CLOSED' -or !$closed.spaceReleased) { throw 'Test vehicle was not closed and released' }

Write-Output "PASS estimate=$($estimate.amountDueCents) rate=$($estimate.rateVersion)"
Write-Output "PASS preferred=$($chosen.number) session=$($entry.parkingSessionId) retry=same"
Write-Output 'PASS competing preferred space=409 SPACE_UNAVAILABLE; original exit=CLOSED'
