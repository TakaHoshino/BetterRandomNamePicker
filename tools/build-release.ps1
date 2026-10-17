param(
    [string]$Configuration = 'Release',
    [string]$Runtime = 'win-x64'
)

$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
$project = Join-Path $repo 'src\BetterRNP\BetterRNP.csproj'
[xml]$projectXml = Get-Content -LiteralPath $project
$version = [string]$projectXml.Project.PropertyGroup.Version
$tag = "v$version"
$artifacts = Join-Path $repo 'artifacts'
$frameworkDir = Join-Path $artifacts 'framework-dependent'
$selfContainedDir = Join-Path $artifacts 'self-contained'

New-Item -ItemType Directory -Path $artifacts -Force | Out-Null
foreach ($directory in @($frameworkDir, $selfContainedDir)) {
    if (Test-Path -LiteralPath $directory) {
        Remove-Item -LiteralPath $directory -Recurse -Force
    }
}

dotnet publish $project -c $Configuration -r $Runtime --self-contained false `
    -p:PublishSingleFile=false -p:DebugType=None -p:DebugSymbols=false -o $frameworkDir
if ($LASTEXITCODE -ne 0) {
    throw 'Framework-dependent publish failed.'
}

dotnet publish $project -c $Configuration -r $Runtime --self-contained true `
    -p:PublishSingleFile=true -p:IncludeNativeLibrariesForSelfExtract=true `
    -p:DebugType=None -p:DebugSymbols=false -o $selfContainedDir
if ($LASTEXITCODE -ne 0) {
    throw 'Self-contained publish failed.'
}

Copy-Item -LiteralPath (Join-Path $repo 'README.md') -Destination $frameworkDir -Force
Copy-Item -LiteralPath (Join-Path $repo 'README.md') -Destination $selfContainedDir -Force

$frameworkZip = Join-Path $artifacts "BetterRNP_${Runtime}_framework-dependent_$tag.zip"
$selfContainedZip = Join-Path $artifacts "BetterRNP_${Runtime}_self-contained_$tag.zip"
Compress-Archive -Path (Join-Path $frameworkDir '*') -DestinationPath $frameworkZip -Force
Compress-Archive -Path (Join-Path $selfContainedDir '*') -DestinationPath $selfContainedZip -Force

Write-Output "FRAMEWORK_ZIP $frameworkZip"
Write-Output "SELF_CONTAINED_ZIP $selfContainedZip"
