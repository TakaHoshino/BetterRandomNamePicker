param(
    [string]$Configuration = 'Release'
)

$ErrorActionPreference = 'Stop'
$projectDir = Split-Path -Parent $PSCommandPath
$repo = Split-Path -Parent (Split-Path -Parent $projectDir)
$outputDir = Join-Path $projectDir "bin\$Configuration\net8.0-windows"
$package = Join-Path $projectDir 'BetterRNP.DemoPlugin.rnpp'

dotnet build (Join-Path $projectDir 'BetterRNP.DemoPlugin.csproj') -c $Configuration
if ($LASTEXITCODE -ne 0) {
    throw 'Plugin build failed.'
}

if (Test-Path -LiteralPath $package) {
    Remove-Item -LiteralPath $package -Force
}

Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive = [System.IO.Compression.ZipFile]::Open($package, 'Create')
try {
    [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile(
        $archive,
        (Join-Path $outputDir 'plugin.json'),
        'plugin.json') | Out-Null
    [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile(
        $archive,
        (Join-Path $outputDir 'BetterRNP.DemoPlugin.dll'),
        'BetterRNP.DemoPlugin.dll') | Out-Null
}
finally {
    $archive.Dispose()
}

Write-Output "RNPP_CREATED $package"
