$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
$exe = Join-Path $repo 'src\BetterRNP\bin\Release\net8.0-windows\BetterRNP.exe'
if (-not (Test-Path -LiteralPath $exe)) {
    throw "Application not found: $exe"
}
Start-Process -FilePath "$env:SystemRoot\explorer.exe" -ArgumentList ('"' + $exe + '"')
Write-Output "LAUNCHED_VIA_EXPLORER $exe"
