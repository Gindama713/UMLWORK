param(
    [string]$NacosUrl = 'http://127.0.0.1:8848',
    [string]$BillingUrl = 'http://127.0.0.1:18083'
)
$ErrorActionPreference = 'Stop'
$nacos = $NacosUrl.TrimEnd('/') + '/nacos/v1/cs/configs'
$billing = $BillingUrl.TrimEnd('/') + '/internal/v1/billing/bills'
$remote = Invoke-WebRequest "$($nacos)?dataId=billing-service.properties&group=DEFAULT_GROUP" -SkipHttpErrorCheck
if ([int]$remote.StatusCode -ne 200) { throw 'Publish billing-service.properties to Nacos before this check' }
$original = $remote.Content
if (!$original.Contains('parking.pricing.day-first-cents=600') -or
        !$original.Contains('parking.pricing.version=v1-demo-20260925')) {
    throw 'This check expects the v1 demo baseline; remote configuration was left unchanged'
}
$changed = $original.Replace('parking.pricing.version=v1-demo-20260925',
    'parking.pricing.version=v2-refresh-check').Replace('parking.pricing.day-first-cents=600',
    'parking.pricing.day-first-cents=650')
function Publish($text) {
    $r = Invoke-WebRequest $nacos -Method Post -Body @{dataId='billing-service.properties';group='DEFAULT_GROUP';content=$text;type='properties'} -ContentType 'application/x-www-form-urlencoded' -SkipHttpErrorCheck
    if ([int]$r.StatusCode -ne 200 -or $r.Content -ne 'true') { throw "Nacos publish failed: $([int]$r.StatusCode)" }
}
function Bill() {
    $body = @{parkingSessionId=[guid]::NewGuid().ToString();entryTime='2026-10-01T08:00:00+08:00';exitTime='2026-10-01T10:00:00+08:00';benefitType='NONE';prepaidCents=0;chargingCents=0;exceptionType='NONE'}
    $r = Invoke-WebRequest $billing -Method Post -Body (ConvertTo-Json $body -Compress) -ContentType 'application/json; charset=utf-8' -Headers @{'Idempotency-Key'=[guid]::NewGuid().ToString()} -SkipHttpErrorCheck
    if ([int]$r.StatusCode -ne 200) { throw "Billing failed HTTP $([int]$r.StatusCode): $($r.Content)" }
    ($r.Content | ConvertFrom-Json).data
}
$before = Bill
"before bill=$($before.billId) parking=$($before.parkingBaseCents) version=$($before.rateVersion)"
if ($before.parkingBaseCents -ne 1000 -or $before.rateVersion -ne 'v1-demo-20260925') { throw 'Baseline Nacos pricing differs from demo values' }
try {
    Publish $changed
    $updated = $null
    for ($i=0; $i -lt 10; $i++) {
        Start-Sleep -Seconds 2
        $updated = Bill
        if ($updated.rateVersion -eq 'v2-refresh-check') { break }
    }
    "after bill=$($updated.billId) parking=$($updated.parkingBaseCents) version=$($updated.rateVersion)"
    if ($updated.parkingBaseCents -ne 1050 -or $updated.rateVersion -ne 'v2-refresh-check') { throw 'Nacos change did not affect new bill' }
    $old = Invoke-RestMethod "http://127.0.0.1:18083/api/v1/billing/bills/$($before.billId)"
    if ($old.data.parkingBaseCents -ne 1000 -or $old.data.rateVersion -ne 'v1-demo-20260925') { throw 'Old bill snapshot changed' }
    'PASS: new bill changed without restart; old bill remained immutable'
} finally {
    Publish $original
    'Nacos demo pricing restored'
}
