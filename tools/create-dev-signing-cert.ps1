param(
    [string]$Subject = 'CN=BetterRNP Development, O=BetterRNP',
    [string]$Password = 'BetterRNP-Development',
    [string]$OutputDir = '',
    [switch]$InstallTrust
)

$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
if ([string]::IsNullOrWhiteSpace($OutputDir)) {
    $OutputDir = Join-Path $repo '.signing'
}
New-Item -ItemType Directory -Path $OutputDir -Force | Out-Null

$pfxPath = Join-Path $OutputDir 'BetterRNP-Development.pfx'
$cerPath = Join-Path $OutputDir 'BetterRNP-Development.cer'

$certificate = Get-ChildItem Cert:\CurrentUser\My |
    Where-Object {
        $_.Subject -eq $Subject -and
        $_.HasPrivateKey -and
        $_.NotAfter -gt (Get-Date).AddDays(30)
    } |
    Sort-Object NotAfter -Descending |
    Select-Object -First 1

if ($null -eq $certificate) {
    $certificate = New-SelfSignedCertificate `
        -Type CodeSigningCert `
        -Subject $Subject `
        -KeyUsage DigitalSignature `
        -KeySpec Signature `
        -KeyExportPolicy Exportable `
        -KeyLength 3072 `
        -HashAlgorithm SHA256 `
        -FriendlyName 'BetterRNP Development Code Signing' `
        -CertStoreLocation 'Cert:\CurrentUser\My' `
        -NotAfter (Get-Date).AddYears(10)
}

$securePassword = ConvertTo-SecureString -String $Password -Force -AsPlainText
Export-PfxCertificate -Cert $certificate -FilePath $pfxPath -Password $securePassword | Out-Null
Export-Certificate -Cert $certificate -FilePath $cerPath | Out-Null

if ($InstallTrust) {
    $certutil = Join-Path $env:SystemRoot 'System32\certutil.exe'
    & $certutil -user -addstore -f Root $cerPath | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw 'Failed to add the development certificate to CurrentUser\Root.'
    }
    & $certutil -user -addstore -f TrustedPublisher $cerPath | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw 'Failed to add the development certificate to CurrentUser\TrustedPublisher.'
    }
}

Write-Output "DEV_CERT_THUMBPRINT $($certificate.Thumbprint)"
Write-Output "DEV_CERT_PFX $pfxPath"
Write-Output "DEV_CERT_CER $cerPath"
Write-Output "DEV_CERT_TRUSTED $($InstallTrust.IsPresent)"
