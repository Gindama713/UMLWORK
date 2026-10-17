param([string]$GatewayUrl = 'http://127.0.0.1:18080')

$ErrorActionPreference = 'Stop'
$base = $GatewayUrl.TrimEnd('/')
function Call-Api($method, $path, $body = $null) {
    $args = @{ Uri = "$base$path"; Method = $method; SkipHttpErrorCheck = $true }
    if ($null -ne $body) {
        $args.Headers = @{ 'Idempotency-Key' = [guid]::NewGuid().ToString() }
        $args.ContentType = 'application/json; charset=utf-8'
        $args.Body = $body | ConvertTo-Json -Compress
    }
    $response = Invoke-WebRequest @args
    return @{ Status = [int]$response.StatusCode; Body = ($response.Content | ConvertFrom-Json) }
}
function Ok($response) {
    if ($response.Status -ne 200 -or $response.Body.code -ne 'OK') {
        throw "HTTP $($response.Status), code=$($response.Body.code)"
    }
    return $response.Body.data
}
function Availability($from, $to) {
    $path = '/api/v1/passes/reservation-availability?from=' + [uri]::EscapeDataString($from.ToString('o')) +
            '&to=' + [uri]::EscapeDataString($to.ToString('o'))
    return @(Ok (Call-Api GET $path))
}

$start = [DateTimeOffset]::UtcNow.AddDays(15)
$end = $start.AddHours(1)
$adjacentEnd = $end.AddHours(1)
$created = @()
try {
    $candidate = @(Availability $start $end | Where-Object available)[0]
    if (!$candidate) { throw 'No reservable space in the chosen future interval' }
    $spaceId = $candidate.spaceId
    $plate = '苏R' + [guid]::NewGuid().ToString('N').Substring(0, 7).ToUpperInvariant()
    $first = Ok (Call-Api POST '/api/v1/passes/reservations' @{
        plateNumber = $plate; spaceId = $spaceId; startTime = $start.ToString('o'); endTime = $end.ToString('o')
    })
    $created += $first.reservationId
    $blocked = @(Availability $start $end | Where-Object { $_.spaceId -eq $spaceId })[0]
    if ($blocked.available -or @($blocked.windows).Count -ne 1) { throw 'First hold is absent from timeline' }
    Write-Output 'PASS: new hold blocks its [start,end) window'

    $conflict = Call-Api POST '/api/v1/passes/reservations' @{
        plateNumber = ('苏S' + [guid]::NewGuid().ToString('N').Substring(0, 7).ToUpperInvariant())
        spaceId = $spaceId; startTime = $start.ToString('o'); endTime = $end.ToString('o')
    }
    if ($conflict.Status -ne 409 -or $conflict.Body.code -ne 'RESERVATION_CONFLICT') {
        throw "Overlap was not rejected: HTTP $($conflict.Status), code=$($conflict.Body.code)"
    }
    Write-Output 'PASS: overlapping reservation returns 409 RESERVATION_CONFLICT'

    $adjacent = Ok (Call-Api POST '/api/v1/passes/reservations' @{
        plateNumber = $plate; spaceId = $spaceId; startTime = $end.ToString('o'); endTime = $adjacentEnd.ToString('o')
    })
    $created += $adjacent.reservationId
    Write-Output 'PASS: adjacent [end,next) reservation succeeds'
} finally {
    foreach ($id in $created) { [void](Ok (Call-Api POST "/api/v1/passes/reservations/$id/cancel" @{})) }
}
$released = @(Availability $start $adjacentEnd | Where-Object { $_.spaceId -eq $spaceId })[0]
if (!$released.available -or @($released.windows).Count -ne 0) { throw 'Cancelled holds still block the timeline' }
Write-Output 'PASS: cancellation restores the visible empty window'
