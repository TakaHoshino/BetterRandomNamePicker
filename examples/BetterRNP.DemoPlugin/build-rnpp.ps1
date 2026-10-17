param(
    [string]$Configuration = 'Release'
)

$ErrorActionPreference = 'Stop'
$projectDir = Split-Path -Parent $PSCommandPath
$outputDir = Join-Path $projectDir "bin\$Configuration\net8.0-windows"
$generated = Join-Path $outputDir 'com.betterrnp.demo.rnpp'
$package = Join-Path $projectDir 'BetterRNP.DemoPlugin.rnpp'

dotnet build (Join-Path $projectDir 'BetterRNP.DemoPlugin.csproj') -c $Configuration
if ($LASTEXITCODE -ne 0) {
    throw 'Plugin build failed.'
}

if (-not (Test-Path -LiteralPath $generated)) {
    throw "Automatic .rnpp packaging did not produce: $generated"
}

Copy-Item -LiteralPath $generated -Destination $package -Force
Write-Output "RNPP_CREATED $package"
