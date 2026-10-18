param(
    [Parameter(Mandatory = $true)][string]$File,
    [string]$PfxPath = '',
    [string]$Password = 'BetterRNP-Development'
)

$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
if ([string]::IsNullOrWhiteSpace($PfxPath)) {
    $PfxPath = Join-Path $repo '.signing\BetterRNP-Development.pfx'
}
if (-not (Test-Path -LiteralPath $PfxPath)) {
    throw "Development signing certificate not found: $PfxPath"
}

Import-Module Microsoft.PowerShell.Security -ErrorAction Stop

$certificate = New-Object System.Security.Cryptography.X509Certificates.X509Certificate2(
    (Resolve-Path -LiteralPath $PfxPath).Path,
    $Password,
    [System.Security.Cryptography.X509Certificates.X509KeyStorageFlags]'Exportable,PersistKeySet')

try {
    Set-AuthenticodeSignature `
        -FilePath $File `
        -Certificate $certificate `
        -HashAlgorithm SHA256 | Out-Null
}
finally {
    $certificate.Dispose()
}

$verified = Get-AuthenticodeSignature -LiteralPath $File
Write-Output "DEV_SIGNED $File"
Write-Output "DEV_SIGN_STATUS $($verified.Status)"
if ($verified.Status -ne 'Valid') {
    Write-Warning 'The development certificate is not trusted on this machine. Run tools/create-dev-signing-cert.ps1 -InstallTrust.'
}
