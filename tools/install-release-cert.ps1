param(
    [Parameter(Mandatory = $true)][string]$CertificatePath
)

$ErrorActionPreference = 'Stop'
$certificatePath = (Resolve-Path -LiteralPath $CertificatePath).Path
$certificate = New-Object System.Security.Cryptography.X509Certificates.X509Certificate2($certificatePath)
try {
    if ($certificate.Subject -notlike '*BetterRNP*') {
        throw "The certificate does not appear to be a BetterRNP certificate: $($certificate.Subject)"
    }
    Write-Host "Certificate subject: $($certificate.Subject)"
    Write-Host "Thumbprint: $($certificate.Thumbprint)"
} finally {
    $certificate.Dispose()
}

$certutil = Join-Path $env:SystemRoot 'System32\certutil.exe'
& $certutil -user -addstore -f Root $certificatePath | Out-Null
if ($LASTEXITCODE -ne 0) {
    throw 'Failed to add the certificate to CurrentUser\Root.'
}
& $certutil -user -addstore -f TrustedPublisher $certificatePath | Out-Null
if ($LASTEXITCODE -ne 0) {
    throw 'Failed to add the certificate to CurrentUser\TrustedPublisher.'
}

Write-Output 'RELEASE_CERT_TRUSTED'
Write-Warning 'This is a self-signed development certificate. Only install it if you obtained it from this repository''s official release page.'
