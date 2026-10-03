param([string]$BaseUrl = 'https://msgdock.dpdns.org')
$ErrorActionPreference = 'Stop'
$runId = [guid]::NewGuid().ToString('N').Substring(0, 12)
$password = [Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(24))
$sessions = [Collections.Generic.List[string]]::new()
$devices = [Collections.Generic.List[object]]::new()
$checks = 0
function Api($method, $path, $body, $token = '', $native = $false) {
    $headers = @{}
    if ($token) { $headers.Authorization = "Bearer $token" }
    if ($native) { $headers['X-MsgDock-Client'] = 'native' }
    $args = @{ Uri = "$BaseUrl$path"; Method = $method; Headers = $headers; SkipHttpErrorCheck = $true; TimeoutSec = 30 }
    if ($null -ne $body) { $args.Body = $body | ConvertTo-Json -Compress; $args.ContentType = 'application/json' }
    $response = Invoke-WebRequest @args
    [pscustomobject]@{ Status = [int]$response.StatusCode; Body = ($response.Content | ConvertFrom-Json); Headers = $response.Headers }
}
function Check($condition, $label) {
    if (-not $condition) { throw "FAIL: $label" }
    $script:checks++
    Write-Output "PASS: $label"
}
try {
    $a = Api POST '/api/v1/auth/register' @{username="smoke_${runId}_a";password=$password} '' $true
    Check ($a.Status -eq 200) 'native register'
    $sessions.Add($a.Body.session_token)
    $b = Api POST '/api/v1/auth/register' @{username="smoke_${runId}_b";password=$password} '' $true
    Check ($b.Status -eq 200) 'second account register'
    $sessions.Add($b.Body.session_token)
    $login = Api POST '/api/v1/auth/login' @{identifier="smoke_${runId}_a";password=$password} '' $true
    Check ($login.Status -eq 200) 'password login'
    $sessions.Add($login.Body.session_token)
    $wrong = Api POST '/api/v1/auth/login' @{identifier="smoke_${runId}_a";password='not-the-password'}
    Check ($wrong.Status -eq 401) 'wrong password rejected'
    $web = Api POST '/api/v1/auth/login' @{identifier="smoke_${runId}_a";password=$password}
    $cookieHeader = $web.Headers['Set-Cookie'] -join ''
    Check ($web.Status -eq 200 -and -not $web.Body.session_token -and $cookieHeader -match 'HttpOnly' -and $cookieHeader -match 'Secure' -and $cookieHeader -match 'SameSite=Lax') 'Web secure cookie and no JS token'
    if ($cookieHeader -match 'msgdock_session=([^;]+)') { $sessions.Add($Matches[1]) }
    $device = Api POST '/api/v1/devices' @{name='MsgDock synthetic API test';type='android'} $a.Body.session_token
    Check ($device.Status -eq 201) 'device registration'
    $devices.Add(@{id=$device.Body.device.id;session=$a.Body.session_token})
    $message = @{client_message_id="smoke_$runId";sender='TEST';body='Synthetic MsgDock test code 583921';received_at=[DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()}
    $upload = Api POST '/api/v1/messages' $message $device.Body.device_token
    Check ($upload.Status -eq 201) 'device upload'
    $duplicate = Api POST '/api/v1/messages' $message $device.Body.device_token
    Check ($duplicate.Status -eq 200 -and $duplicate.Body.deduplicated -and $duplicate.Body.seq -eq $upload.Body.seq) 'idempotent retry'
    $own = Api GET '/api/v1/messages?after=0' $null $a.Body.session_token
    Check ($own.Status -eq 200 -and $own.Body.messages.Count -eq 1 -and $own.Body.messages[0].body -eq $message.body) 'own inbox and message content'
    $other = Api GET '/api/v1/messages?after=0' $null $b.Body.session_token
    Check ($other.Status -eq 200 -and $other.Body.messages.Count -eq 0) 'cross-account isolation'
    $idor = Api DELETE "/api/v1/devices/$($device.Body.device.id)" $null $b.Body.session_token
    Check ($idor.Status -eq 404) 'foreign device removal rejected'
    $wrongToken = Api POST '/api/v1/messages' $message $a.Body.session_token
    Check ($wrongToken.Status -eq 401) 'session cannot upload as a device'
    $after = Api GET "/api/v1/messages?after=$($upload.Body.seq)" $null $device.Body.device_token
    Check ($after.Status -eq 200 -and $after.Body.messages.Count -eq 0 -and $after.Body.next_seq -eq $upload.Body.seq) 'incremental cursor'
    $removed = Api DELETE "/api/v1/devices/$($device.Body.device.id)" $null $a.Body.session_token
    Check ($removed.Status -eq 200) 'device revocation'
    $devices.Clear()
    $revoked = Api GET '/api/v1/messages' $null $device.Body.device_token
    Check ($revoked.Status -eq 401) 'revoked device blocked'
    $null = Api POST '/api/v1/auth/logout' @{} $login.Body.session_token
    $loggedOut = Api GET '/api/v1/me' $null $login.Body.session_token
    Check ($loggedOut.Status -eq 401) 'session logout'
    Write-Output "RESULT: $checks checks passed; run=$runId"
    Write-Output "Synthetic users retained: smoke_${runId}_a, smoke_${runId}_b; credentials are discarded and tokens revoked."
} finally {
    foreach ($d in $devices) { try { $null = Api DELETE "/api/v1/devices/$($d.id)" $null $d.session } catch { Write-Warning 'Synthetic device revocation needs follow-up' } }
    foreach ($token in $sessions) { try { $null = Api POST '/api/v1/auth/logout' @{} $token } catch { Write-Warning 'Synthetic session revocation needs follow-up' } }
    $password = $null
}
