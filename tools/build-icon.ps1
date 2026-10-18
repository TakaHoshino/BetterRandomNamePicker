param(
    [string]$SourcePng = ''
)

$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
$iconDir = Join-Path $repo 'assets\icon'
$pngDir = Join-Path $iconDir 'png'
$sourcePath = if ([string]::IsNullOrWhiteSpace($SourcePng)) {
    Join-Path $iconDir 'betterrnp-icon-1024.png'
} else {
    (Resolve-Path -LiteralPath $SourcePng).Path
}
if (-not (Test-Path -LiteralPath $sourcePath)) {
    throw "Icon source PNG not found: $sourcePath"
}

New-Item -ItemType Directory -Path $pngDir -Force | Out-Null
Add-Type -AssemblyName System.Drawing

$sizes = @(1024, 512, 256, 128, 64, 48, 32, 24, 16)
$source = [System.Drawing.Bitmap]::FromFile($sourcePath)
try {
    if ($source.GetPixel(0, 0).A -ne 0) {
        Write-Warning 'The source PNG does not have a transparent corner.'
    }

    foreach ($size in $sizes) {
        $bitmap = New-Object System.Drawing.Bitmap(
            $size,
            $size,
            [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
        try {
            $graphics = [System.Drawing.Graphics]::FromImage($bitmap)
            try {
                $graphics.Clear([System.Drawing.Color]::Transparent)
                $graphics.CompositingMode = [System.Drawing.Drawing2D.CompositingMode]::SourceCopy
                $graphics.CompositingQuality = [System.Drawing.Drawing2D.CompositingQuality]::HighQuality
                $graphics.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
                $graphics.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
                $graphics.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::HighQuality
                $graphics.DrawImage($source, 0, 0, $size, $size)
            }
            finally {
                $graphics.Dispose()
            }

            $output = Join-Path $pngDir "betterrnp-icon-$size.png"
            $bitmap.Save($output, [System.Drawing.Imaging.ImageFormat]::Png)
        }
        finally {
            $bitmap.Dispose()
        }
    }
}
finally {
    $source.Dispose()
}

$icoSizes = @(16, 24, 32, 48, 64, 128, 256)
$icoPath = Join-Path $iconDir 'betterrnp-icon.ico'
if (Test-Path -LiteralPath $icoPath) {
    Remove-Item -LiteralPath $icoPath -Force
}

$images = foreach ($size in $icoSizes) {
    $path = Join-Path $pngDir "betterrnp-icon-$size.png"
    [PSCustomObject]@{
        Size = $size
        Bytes = [System.IO.File]::ReadAllBytes($path)
    }
}

$stream = [System.IO.File]::Open($icoPath, [System.IO.FileMode]::Create)
$writer = New-Object System.IO.BinaryWriter($stream)
try {
    $writer.Write([UInt16]0)
    $writer.Write([UInt16]1)
    $writer.Write([UInt16]$images.Count)

    $offset = 6 + (16 * $images.Count)
    foreach ($image in $images) {
        $dimension = if ($image.Size -ge 256) { 0 } else { $image.Size }
        $writer.Write([byte]$dimension)
        $writer.Write([byte]$dimension)
        $writer.Write([byte]0)
        $writer.Write([byte]0)
        $writer.Write([UInt16]1)
        $writer.Write([UInt16]32)
        $writer.Write([UInt32]$image.Bytes.Length)
        $writer.Write([UInt32]$offset)
        $offset += $image.Bytes.Length
    }

    foreach ($image in $images) {
        $writer.Write($image.Bytes)
    }
}
finally {
    $writer.Dispose()
    $stream.Dispose()
}

$assetsDir = Join-Path $repo 'src\BetterRNP\Assets'
New-Item -ItemType Directory -Path $assetsDir -Force | Out-Null
Copy-Item -LiteralPath $icoPath -Destination (Join-Path $assetsDir 'BetterRNP.ico') -Force

Write-Output "ICON_SVG $(Join-Path $iconDir 'betterrnp-icon.svg')"
Write-Output "ICON_ICO $icoPath"
Write-Output "ICON_PNG_DIR $pngDir"
