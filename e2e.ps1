# Test end-to-end user-service.
# ⚠️ PowerShell 5.1 làm rụng dấu " khi truyền JSON inline cho curl.exe
#    → LUÔN ghi body ra file và dùng `curl --data-binary @file`.
$base = "http://127.0.0.1:18081/api/users"
$dir = "c:\Ptit-learn\crypto-payment"
$log = "$dir\e2e-final.log"
"" | Set-Content $log
function Sec($t) { "`n########## $t ##########" | Add-Content $log }

$u = "alice$(Get-Random -Maximum 99999)"

function Post($path, $json, $extraHeader) {
    $bf = "$dir\_body.json"
    Set-Content -Path $bf -Value $json -Encoding UTF8 -NoNewline
    $a = @('-s', '--noproxy', '*', '-m', '25', '-w', "`nHTTP=%{http_code}",
           '-X', 'POST', "$base$path",
           '-H', 'Content-Type: application/json',
           '--data-binary', "@$bf")
    if ($extraHeader) { $a += @('-H', $extraHeader) }
    $r = & curl.exe @a 2>&1
    Remove-Item $bf -ErrorAction SilentlyContinue
    return $r
}
function Get2($path, $extraHeader) {
    $a = @('-s', '--noproxy', '*', '-m', '25', '-w', "`nHTTP=%{http_code}", "$base$path")
    if ($extraHeader) { $a += @('-H', $extraHeader) }
    return (& curl.exe @a 2>&1)
}

$reg = "{""username"":""$u"",""email"":""$u@example.com"",""password"":""secret123"",""fullName"":""Alice Nguyen""}"

Sec "1. REGISTER -> mong doi 201 + JWT"
$r1 = Post "/register" $reg $null
$r1 | Add-Content $log
$token = ""
if (($r1 -join "`n") -match '"accessToken"\s*:\s*"([^"]+)"') { $token = $Matches[1] }
"TOKEN_FOUND=$([bool]$token)" | Add-Content $log

Sec "2. REGISTER trung username -> mong doi 409 CONFLICT"
(Post "/register" $reg $null) | Add-Content $log

Sec "3. VALIDATION (username ngan, email sai, password ngan) -> mong doi 400 VALIDATION_ERROR"
(Post "/register" '{"username":"ab","email":"not-an-email","password":"123"}' $null) | Add-Content $log

Sec "4. LOGIN dung mat khau -> mong doi 200 + JWT"
(Post "/login" "{""username"":""$u"",""password"":""secret123""}" $null) | Add-Content $log

Sec "5. LOGIN sai mat khau -> mong doi 401 UNAUTHORIZED"
(Post "/login" "{""username"":""$u"",""password"":""wrong-pass""}" $null) | Add-Content $log

Sec "6. LOGIN user khong ton tai -> mong doi 401 (khong lo user co ton tai)"
(Post "/login" '{"username":"khong-ton-tai-xyz","password":"secret123"}' $null) | Add-Content $log

Sec "7. GET /me khong token -> mong doi 401"
(Get2 "/me" $null) | Add-Content $log

Sec "8. GET /me co Bearer token -> mong doi 200 + profile"
(Get2 "/me" "Authorization: Bearer $token") | Add-Content $log

Sec "9. GET /me token rac -> mong doi 401"
(Get2 "/me" "Authorization: Bearer abc.def.ghi") | Add-Content $log

"`nUSERNAME_USED=$u" | Add-Content $log
"=== E2E DONE ===" | Add-Content $log
