param([string]$GatewayUrl = 'http://127.0.0.1:18080')
# 【AI-辅助】真实服务联调；创建一条演示停车记录，不删除历史数据。
$ErrorActionPreference = 'Stop'
function Call-Api([string]$Path, [string]$Method = 'GET', $Body = $null, [string]$Key = '') {
    $parameters = @{ Uri = "$GatewayUrl$Path"; Method = $Method; SkipHttpErrorCheck = $true }
    if ($Method -ne 'GET') {
        if (!$Key) { $Key = [guid]::NewGuid().ToString() }
        $parameters.Headers = @{ 'Idempotency-Key' = $Key }
        $parameters.ContentType = 'application/json; charset=utf-8'
        $parameters.Body = ConvertTo-Json -InputObject $Body -Depth 6 -Compress
    }
    $response = Invoke-WebRequest @parameters
    [pscustomobject]@{ Status = [int]$response.StatusCode; Envelope = ($response.Content | ConvertFrom-Json) }
}
function Ok($Response) {
    if ($Response.Status -ne 200 -or $Response.Envelope.code -ne 'OK') { throw ($Response.Envelope | ConvertTo-Json -Compress) }
    $Response.Envelope.data
}
function Check([bool]$Condition, [string]$Message) {
    if (!$Condition) { throw "FAILED: $Message" }
    Write-Output "PASS: $Message"
}
function Conflict($Response, [string]$Code) {
    Check ($Response.Status -eq 409 -and $Response.Envelope.code -eq $Code) $Code
}
$entryTime = [DateTimeOffset]::Now.AddHours(-2).ToString('o')
$exitTime = [DateTimeOffset]::Now.ToString('o')
$plate = '苏A' + [guid]::NewGuid().ToString('N').Substring(0, 8).ToUpperInvariant()
$session = Ok (Call-Api '/api/v1/access/entries' 'POST' @{ plateNumber = $plate; spaceType = 'CHARGING'; entryTime = $entryTime })
Write-Output "ParkingSession: $($session.parkingSessionId) / $plate"
$sessionId = $session.parkingSessionId
$chargers = Ok (Call-Api "/api/v1/spaces/chargers?spaceId=$($session.spaceId)")
$charger = @($chargers)[0]
$charge = Ok (Call-Api '/api/v1/spaces/charging-sessions' 'POST' @{ chargerId = $charger.chargerId; parkingSessionId = $sessionId; startTime = $entryTime })
$exitKey = [guid]::NewGuid().ToString()
$exitBody = @{ exitTime = $exitTime; exceptionType = 'NONE' }
Conflict (Call-Api "/api/v1/access/$sessionId/exit-requests" 'POST' $exitBody $exitKey) 'STATE_CONFLICT'
$finished = Ok (Call-Api "/api/v1/spaces/charging-sessions/$($charge.chargingSessionId)/finish" 'POST' @{ endTime = $exitTime; energyKwh = '2.000' })
$exit = Ok (Call-Api "/api/v1/access/$sessionId/exit-requests" 'POST' $exitBody $exitKey)
$bill = Ok (Call-Api "/api/v1/billing/bills/$($exit.billId)")
Check ($bill.chargingCents -eq $finished.chargingCents) 'Charging fee matches immutable bill'
Check ($bill.amountDueCents -eq ($bill.parkingDueCents + $bill.chargingCents + $bill.exceptionCents)) 'Bill total matches its items'
Conflict (Call-Api '/api/v1/spaces/charging-sessions' 'POST' @{ chargerId = $charger.chargerId; parkingSessionId = $sessionId; startTime = $exitTime }) 'STATE_CONFLICT'
Conflict (Call-Api "/api/v1/access/$sessionId/exit-requests" 'POST' @{ exitTime = [DateTimeOffset]::Parse($exitTime).AddMinutes(1).ToString('o'); exceptionType = 'NONE' } $exitKey) 'IDEMPOTENCY_CONFLICT'
$failed = Ok (Call-Api "/api/v1/access/$sessionId/complete-exit" 'POST' @{ simulatedResult = 'FAILURE' })
Check ($failed.status -eq 'EXIT_PENDING_PAYMENT' -and !$failed.spaceReleased) 'Failed payment keeps vehicle inside'
$recovered = Ok (Call-Api "/api/v1/access/locate?plateNumber=$([uri]::EscapeDataString($plate))")
Check ($recovered.billId -eq $bill.billId -and $recovered.exitTime) 'Locate restores pending settlement'
$paymentKey = [guid]::NewGuid().ToString()
$closed = Ok (Call-Api "/api/v1/access/$sessionId/complete-exit" 'POST' @{ simulatedResult = 'SUCCESS' } $paymentKey)
Check ($closed.status -eq 'CLOSED' -and $closed.spaceReleased) 'Successful payment closes and releases'
$retry = Ok (Call-Api "/api/v1/access/$sessionId/complete-exit" 'POST' @{ simulatedResult = 'SUCCESS' } $paymentKey)
Check ($retry.status -eq 'CLOSED') 'Repeated completion does not charge again'
$invoice = Ok (Call-Api "/api/v1/billing/bills/$($bill.billId)/invoice-requests" 'POST' @{ invoiceTitle = '机场停车联调演示用户' })
Check ($invoice.status -eq 'REQUESTED') 'Invoice request accepted'
$spaces = Ok (Call-Api '/api/v1/spaces')
Check (($spaces | Where-Object spaceId -eq $session.spaceId).status -eq 'AVAILABLE') 'Resource is available after exit'
$query = 'from=' + [uri]::EscapeDataString($entryTime) + '&to=' + [uri]::EscapeDataString([DateTimeOffset]::Now.ToString('o')) + '&granularity=HOUR'
$preview = Ok (Call-Api "/api/v1/analytics/traffic-preview?$query")
Check (!$preview.reportId -and $preview.totalEntries -ge 1) 'Read-only traffic preview contains this entry'
Write-Output "Bill: $($bill.billId); parking=$($bill.parkingDueCents); charging=$($bill.chargingCents); due=$($bill.amountDueCents) cents"
