param(
    [string]$Output = 'screen.png'
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing
Add-Type -AssemblyName System.Windows.Forms

$bounds = [System.Windows.Forms.Screen]::PrimaryScreen.Bounds
$bitmap = New-Object System.Drawing.Bitmap($bounds.Width, $bounds.Height)
$graphics = [System.Drawing.Graphics]::FromImage($bitmap)
try {
    $graphics.CopyFromScreen(
        $bounds.Left,
        $bounds.Top,
        0,
        0,
        $bitmap.Size)
    $path = [System.IO.Path]::GetFullPath($Output)
    $bitmap.Save($path, [System.Drawing.Imaging.ImageFormat]::Png)
    Write-Output "SCREENSHOT $path"
}
finally {
    $graphics.Dispose()
    $bitmap.Dispose()
}
