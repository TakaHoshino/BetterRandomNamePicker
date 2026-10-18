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
$frameworkDir = Join-Path $artifacts "framework-dependent_$tag"
$selfContainedDir = Join-Path $artifacts "self-contained_$tag"

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

function Get-SignToolPath {
    if (-not [string]::IsNullOrWhiteSpace($env:BETTERRNP_SIGNTOOL)) {
        if (-not (Test-Path -LiteralPath $env:BETTERRNP_SIGNTOOL)) {
            throw "BETTERRNP_SIGNTOOL not found: $($env:BETTERRNP_SIGNTOOL)"
        }
        return (Resolve-Path -LiteralPath $env:BETTERRNP_SIGNTOOL).Path
    }

    $command = Get-Command signtool.exe -ErrorAction SilentlyContinue
    if ($null -ne $command) {
        return $command.Source
    }

    $kitsRoot = 'C:\Program Files (x86)\Windows Kits\10\bin'
    if (Test-Path -LiteralPath $kitsRoot) {
        $candidate = Get-ChildItem -LiteralPath $kitsRoot -Filter signtool.exe -Recurse |
            Where-Object { $_.FullName -match '\\x64\\signtool\.exe$' } |
            Sort-Object FullName -Descending |
            Select-Object -First 1
        if ($null -ne $candidate) {
            return $candidate.FullName
        }
    }

    return $null
}

function Invoke-CodeSigning {
    param(
        [string[]]$Files
    )

    $certificatePath = $env:BETTERRNP_SIGN_PFX
    $certificateThumbprint = $env:BETTERRNP_SIGN_THUMBPRINT
    if ([string]::IsNullOrWhiteSpace($certificatePath) -and
        [string]::IsNullOrWhiteSpace($certificateThumbprint)) {
        $developmentPfx = Join-Path $repo '.signing\BetterRNP-Development.pfx'
        if (Test-Path -LiteralPath $developmentPfx) {
            $developmentSigner = Join-Path $repo 'tools\sign-dev.ps1'
            $developmentPassword = if ([string]::IsNullOrWhiteSpace($env:BETTERRNP_DEV_SIGN_PASSWORD)) {
                'BetterRNP-Development'
            } else {
                $env:BETTERRNP_DEV_SIGN_PASSWORD
            }
            foreach ($file in $Files) {
                & powershell -NoProfile -ExecutionPolicy Bypass `
                    -File $developmentSigner -File $file -Password $developmentPassword
                if ($LASTEXITCODE -ne 0) {
                    throw "Development code signing failed: $file"
                }
            }

            $developmentCertificate = New-Object System.Security.Cryptography.X509Certificates.X509Certificate2(
                $developmentPfx,
                $developmentPassword,
                [System.Security.Cryptography.X509Certificates.X509KeyStorageFlags]'Exportable')
            try {
                $certificateAsset = Join-Path $artifacts 'BetterRNP-Development-Certificate.cer'
                [System.IO.File]::WriteAllBytes(
                    $certificateAsset,
                    $developmentCertificate.Export(
                        [System.Security.Cryptography.X509Certificates.X509ContentType]::Cert))
                Copy-Item -LiteralPath $certificateAsset -Destination $frameworkDir -Force
                Copy-Item -LiteralPath $certificateAsset -Destination $selfContainedDir -Force
                Write-Output "DEVELOPMENT_CERTIFICATE $certificateAsset"
            }
            finally {
                $developmentCertificate.Dispose()
            }
            Write-Output 'DEV_SIGNING_USED'
            return
        }
        Write-Warning 'CODE_SIGNING_SKIPPED: set BETTERRNP_SIGN_PFX or BETTERRNP_SIGN_THUMBPRINT.'
        return
    }

    $signTool = Get-SignToolPath
    if ([string]::IsNullOrWhiteSpace($signTool)) {
        throw 'signtool.exe was not found. Set BETTERRNP_SIGNTOOL.'
    }

    $timestampUrl = if ([string]::IsNullOrWhiteSpace($env:BETTERRNP_TIMESTAMP_URL)) {
        'http://timestamp.digicert.com'
    } else {
        $env:BETTERRNP_TIMESTAMP_URL
    }

    $arguments = @('sign', '/fd', 'SHA256', '/td', 'SHA256', '/tr', $timestampUrl)
    if (-not [string]::IsNullOrWhiteSpace($certificatePath)) {
        if (-not (Test-Path -LiteralPath $certificatePath)) {
            throw "Code-signing certificate not found: $certificatePath"
        }
        $arguments += @('/f', (Resolve-Path -LiteralPath $certificatePath).Path)
        if (-not [string]::IsNullOrWhiteSpace($env:BETTERRNP_SIGN_PASSWORD)) {
            $arguments += @('/p', $env:BETTERRNP_SIGN_PASSWORD)
        }
    }
    else {
        $arguments += @('/sha1', $certificateThumbprint)
    }

    foreach ($file in $Files) {
        & $signTool @arguments $file
        if ($LASTEXITCODE -ne 0) {
            throw "Code signing failed: $file"
        }
    }
}

Invoke-CodeSigning -Files @(
    (Join-Path $frameworkDir 'BetterRNP.exe'),
    (Join-Path $selfContainedDir 'BetterRNP.exe')
)

Copy-Item -LiteralPath (Join-Path $repo 'README.md') -Destination $frameworkDir -Force
Copy-Item -LiteralPath (Join-Path $repo 'README.md') -Destination $selfContainedDir -Force
Copy-Item -LiteralPath (Join-Path $repo 'LICENSE') -Destination $frameworkDir -Force
Copy-Item -LiteralPath (Join-Path $repo 'LICENSE') -Destination $selfContainedDir -Force
Copy-Item -LiteralPath (Join-Path $repo 'NOTICE') -Destination $frameworkDir -Force
Copy-Item -LiteralPath (Join-Path $repo 'NOTICE') -Destination $selfContainedDir -Force

$frameworkZip = Join-Path $artifacts "BetterRNP_${Runtime}_framework-dependent_$tag.zip"
$selfContainedZip = Join-Path $artifacts "BetterRNP_${Runtime}_self-contained_$tag.zip"
Compress-Archive -Path (Join-Path $frameworkDir '*') -DestinationPath $frameworkZip -Force
Compress-Archive -Path (Join-Path $selfContainedDir '*') -DestinationPath $selfContainedZip -Force

$sdkDir = Join-Path $artifacts 'sdk'
dotnet pack (Join-Path $repo 'src\BetterRNP.PluginSdk\BetterRNP.PluginSdk.csproj') -c $Configuration -o $sdkDir
if ($LASTEXITCODE -ne 0) {
    throw 'Plugin SDK pack failed.'
}

Write-Output "FRAMEWORK_ZIP $frameworkZip"
Write-Output "SELF_CONTAINED_ZIP $selfContainedZip"
Write-Output "PLUGIN_SDK $sdkDir"
