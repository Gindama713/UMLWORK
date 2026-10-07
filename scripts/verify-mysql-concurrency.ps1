param(
    [string]$GatewayUrl = 'http://127.0.0.1:18080',
    [string]$SpaceUrl = 'http://127.0.0.1:18081',
    [string]$MySqlExe = 'mysql.exe',
    [string]$MySqlHost = '127.0.0.1',
    [string]$MySqlUser = 'root',
    [string]$BillingSchema = 'parking_billing'
)
# 【AI-辅助】只用于真实 MySQL 联调；创建隔离演示记录，不删除历史数据。
$ErrorActionPreference = 'Stop'
if (!$env:BILLING_DB_PASSWORD) { throw 'Set BILLING_DB_PASSWORD in the environment; do not put it in this script.' }

function Post([string]$Path, $Body, [string]$Key = [guid]::NewGuid().ToString()) {
    $url = if ($Path.StartsWith('http')) { $Path } else { "$GatewayUrl$Path" }
    $r = Invoke-WebRequest $url -Method Post -Body (ConvertTo-Json $Body -Compress -Depth 5) `
        -ContentType 'application/json; charset=utf-8' -Headers @{ 'Idempotency-Key' = $Key } -SkipHttpErrorCheck
    [pscustomobject]@{ Status = [int]$r.StatusCode; Envelope = ($r.Content | ConvertFrom-Json) }
}
function Require-Data ($Response) {
    if ($Response.Status -ne 200 -or $Response.Envelope.code -ne 'OK') {
        throw "HTTP $($Response.Status): $($Response.Envelope | ConvertTo-Json -Compress)"
    }
    $Response.Envelope.data
}
function Assert([bool]$Condition, [string]$Label) {
    if (!$Condition) { throw "FAILED: $Label" }
    Write-Output "PASS: $Label"
}
function Race($Requests) {
    $deadline = [DateTimeOffset]::UtcNow.AddSeconds(3)
    @($Requests | ForEach-Object -Parallel {
        $request = $_
        while ([DateTimeOffset]::UtcNow -lt $using:deadline) { Start-Sleep -Milliseconds 5 }
        $client = [System.Net.Http.HttpClient]::new()
        try {
            $message = [System.Net.Http.HttpRequestMessage]::new('POST', $request.Url)
            $message.Headers.Add('Idempotency-Key', $request.Key)
            $message.Content = [System.Net.Http.StringContent]::new($request.Json,
                [System.Text.Encoding]::UTF8, 'application/json')
            $response = $client.SendAsync($message).GetAwaiter().GetResult()
            $content = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult() | ConvertFrom-Json
            [pscustomobject]@{ Status = [int]$response.StatusCode; Code = $content.code; Data = $content.data }
        } finally { $client.Dispose() }
    } -ThrottleLimit $Requests.Count)
}
function Requests([string]$Path, $Bodies) {
    @($Bodies | ForEach-Object {
        $url = if ($Path.StartsWith('http')) { $Path } else { "$GatewayUrl$Path" }
        [pscustomobject]@{ Url = $url; Key = [guid]::NewGuid().ToString();
            Json = (ConvertTo-Json $_ -Compress -Depth 5) }
    })
}
function New-Space([string]$Type) {
    $number = 'QA-' + [guid]::NewGuid().ToString('N').Substring(0, 8).ToUpperInvariant()
    Require-Data (Post '/api/v1/spaces' @{ type = $Type; floor = 'B3'; zone = 'QA'; number = $number })
}

# 窗口 1：并发占用同一车位。每个请求有不同停车记录和幂等键；条件更新只能让一条成功。
$space = New-Space 'NORMAL'
$ids = @(1..8 | ForEach-Object { [guid]::NewGuid().ToString() })
$winner = $null
try {
    $results = Race (Requests "$SpaceUrl/internal/v1/spaces/$($space.spaceId)/occupy" @($ids | ForEach-Object { @{ parkingSessionId = $_ } }))
    $success = @($results | Where-Object { $_.Status -eq 200 -and $_.Code -eq 'OK' })
    $conflicts = @($results | Where-Object { $_.Status -eq 409 -and $_.Code -eq 'SPACE_UNAVAILABLE' })
    Assert ($success.Count -eq 1 -and $conflicts.Count -eq 7) 'MySQL concurrent occupy: exactly one winner, seven SPACE_UNAVAILABLE'
    $winner = $success[0].Data.parkingSessionId
} finally {
    if ($winner) { [void](Require-Data (Post "$SpaceUrl/internal/v1/spaces/$($space.spaceId)/release" @{ parkingSessionId = $winner })) }
}

# 窗口 2：同一预约车位、同一左闭右开时间窗并发创建；pass 自有库的车位锁串行化冲突检查。
$space = New-Space 'RESERVATION'
$start = [DateTimeOffset]::Now.AddHours(2).ToString('o')
$end = [DateTimeOffset]::Now.AddHours(3).ToString('o')
$bodies = @(1..8 | ForEach-Object {
    @{ plateNumber = '苏A' + [guid]::NewGuid().ToString('N').Substring(0, 8).ToUpperInvariant();
        spaceId = $space.spaceId; startTime = $start; endTime = $end }
})
$results = Race (Requests '/api/v1/passes/reservations' $bodies)
$success = @($results | Where-Object { $_.Status -eq 200 -and $_.Code -eq 'OK' })
$conflicts = @($results | Where-Object { $_.Status -eq 409 -and $_.Code -eq 'RESERVATION_CONFLICT' })
Assert ($success.Count -eq 1 -and $conflicts.Count -eq 7) 'MySQL concurrent reservation: exactly one winner, seven RESERVATION_CONFLICT'
[void](Require-Data (Post "/api/v1/passes/reservations/$($success[0].Data.reservationId)/cancel" @{}))

# 窗口 3：同一账单不同请求键并发成功支付；行锁和唯一流水应保证只有一笔成功记录。
$plate = '苏A' + [guid]::NewGuid().ToString('N').Substring(0, 8).ToUpperInvariant()
$entryTime = [DateTimeOffset]::Now.AddHours(-2).ToString('o')
$exitTime = [DateTimeOffset]::Now.ToString('o')
$session = Require-Data (Post '/api/v1/access/entries' @{ plateNumber = $plate; spaceType = 'NORMAL'; entryTime = $entryTime })
$exit = Require-Data (Post "/api/v1/access/$($session.parkingSessionId)/exit-requests" `
    @{ exitTime = $exitTime; exceptionType = 'NONE' })
$results = Race (Requests "/api/v1/billing/bills/$($exit.billId)/payments" `
    @(1..8 | ForEach-Object { @{ simulatedResult = 'SUCCESS' } }))
$success = @($results | Where-Object { $_.Status -eq 200 -and $_.Code -eq 'OK' })
$paymentIds = @($success | ForEach-Object { $_.Data.paymentId } | Select-Object -Unique)
if ($success.Count -ne 8 -or $paymentIds.Count -ne 1) {
    $results | ForEach-Object { Write-Output "payment response: HTTP=$($_.Status) code=$($_.Code) id=$($_.Data.paymentId)" }
}
Assert ($success.Count -eq 8 -and $paymentIds.Count -eq 1) 'Concurrent payment retries return one payment ID'
$env:MYSQL_PWD = $env:BILLING_DB_PASSWORD
try {
    $query = "SELECT COUNT(*) FROM payment WHERE bill_id='$($exit.billId)' AND payment_status='SUCCESS'"
    $count = & $MySqlExe "--host=$MySqlHost" "--user=$MySqlUser" "--database=$BillingSchema" `
        --batch --skip-column-names "--execute=$query"
    if ($LASTEXITCODE -ne 0) { throw 'MySQL payment count query failed' }
    Assert ([int]$count -eq 1) 'MySQL payment table contains exactly one successful row'
} finally { Remove-Item Env:MYSQL_PWD -ErrorAction SilentlyContinue }
$closed = Require-Data (Post "/api/v1/access/$($session.parkingSessionId)/complete-exit" @{ simulatedResult = 'SUCCESS' })
Assert ($closed.status -eq 'CLOSED' -and $closed.spaceReleased) 'Already-paid session closes and releases once'

