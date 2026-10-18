$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
$exe = Join-Path $repo 'src\BetterRNP\bin\Release\net8.0-windows\BetterRNP.exe'
if (-not (Test-Path -LiteralPath $exe)) {
    throw "Build output not found: $exe"
}

$primary = Start-Process -FilePath $exe -PassThru
try {
    for ($attempt = 0; $attempt -lt 20; $attempt++) {
        Start-Sleep -Milliseconds 250
        $primary.Refresh()
        if ($primary.HasExited) {
            throw 'Primary BetterRNP instance exited unexpectedly.'
        }
        if ($primary.MainWindowHandle -ne [IntPtr]::Zero) {
            break
        }
    }
    if ($primary.MainWindowHandle -eq [IntPtr]::Zero) {
        throw 'Primary BetterRNP window was not created.'
    }

    $secondary = Start-Process -FilePath $exe -PassThru
    if (-not $secondary.WaitForExit(5000)) {
        Stop-Process -Id $secondary.Id -Force
        throw 'Secondary BetterRNP instance did not exit.'
    }
    Start-Sleep -Milliseconds 500
    $primary.Refresh()
    if ($primary.HasExited) {
        throw 'Primary BetterRNP instance exited after the secondary launch.'
    }

    Write-Output "SINGLE_INSTANCE_OK secondary_exit=$($secondary.ExitCode)"
}
finally {
    if (-not $primary.HasExited) {
        Stop-Process -Id $primary.Id -Force
    }
}
