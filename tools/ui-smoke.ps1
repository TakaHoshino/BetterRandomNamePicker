param(
    [string]$AppPath = '',
    [string]$OutputName = 'ui-smoke.png'
)

$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
$exe = Join-Path $repo 'src\BetterRNP\bin\Release\net8.0-windows\BetterRNP.exe'
$dll = Join-Path $repo 'src\BetterRNP\bin\Release\net8.0-windows\BetterRNP.dll'
if (-not (Test-Path -LiteralPath $exe)) {
    throw "Build output not found: $exe"
}

Add-Type -AssemblyName System.Drawing
Add-Type @"
using System;
using System.Runtime.InteropServices;

public static class BetterRnpNative
{
    [StructLayout(LayoutKind.Sequential)]
    public struct RECT { public int Left, Top, Right, Bottom; }

    [DllImport("user32.dll", CharSet = CharSet.Unicode)]
    public static extern IntPtr FindWindow(string className, string windowName);

    [DllImport("user32.dll")]
    public static extern bool GetWindowRect(IntPtr window, out RECT rect);

    [DllImport("user32.dll")]
    public static extern bool SetForegroundWindow(IntPtr window);

    [DllImport("user32.dll")]
    public static extern bool PrintWindow(IntPtr window, IntPtr hdc, uint flags);
}
"@

if ([string]::IsNullOrWhiteSpace($AppPath)) {
    $process = Start-Process -FilePath 'dotnet' -ArgumentList $dll -PassThru
}
else {
    if (-not (Test-Path -LiteralPath $AppPath)) {
        throw "Application not found: $AppPath"
    }
    $process = Start-Process -FilePath $AppPath -PassThru
}
try {
    $window = [IntPtr]::Zero
    for ($attempt = 0; $attempt -lt 20; $attempt++) {
        Start-Sleep -Milliseconds 250
        $process.Refresh()
        if ($process.HasExited) {
            throw "BetterRNP exited during startup (code $($process.ExitCode))."
        }
        $window = $process.MainWindowHandle
        if ($window -ne [IntPtr]::Zero) {
            break
        }
    }

    if ($window -eq [IntPtr]::Zero) {
        throw 'The main BetterRNP window was not created.'
    }

    [BetterRnpNative]::SetForegroundWindow($window) | Out-Null
    Start-Sleep -Milliseconds 1500
    $rect = New-Object BetterRnpNative+RECT
    [BetterRnpNative]::GetWindowRect($window, [ref]$rect) | Out-Null
    $width = $rect.Right - $rect.Left
    $height = $rect.Bottom - $rect.Top
    $bitmap = New-Object System.Drawing.Bitmap($width, $height)
    $graphics = [System.Drawing.Graphics]::FromImage($bitmap)
    $hdc = $graphics.GetHdc()
    try {
        # PW_RENDERFULLCONTENT captures DirectComposition/WPF content reliably.
        [BetterRnpNative]::PrintWindow($window, $hdc, 2) | Out-Null
    }
    finally {
        $graphics.ReleaseHdc($hdc)
    }
    $output = Join-Path $repo $OutputName
    $bitmap.Save($output, [System.Drawing.Imaging.ImageFormat]::Png)
    $graphics.Dispose()
    $bitmap.Dispose()

    Write-Output "UI_SMOKE_OK $output"
}
finally {
    if (-not $process.HasExited) {
        $process.CloseMainWindow() | Out-Null
        Start-Sleep -Milliseconds 300
    }
    if (-not $process.HasExited) {
        Stop-Process -Id $process.Id -Force
    }
}
