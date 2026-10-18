param(
    [int]$Seconds = 20,
    [string]$Text = 'BetterRNP Tray Probe'
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing

$notifyIcon = New-Object System.Windows.Forms.NotifyIcon
$notifyIcon.Icon = [System.Drawing.SystemIcons]::Application
$notifyIcon.Text = $Text

$timer = New-Object System.Windows.Forms.Timer
$timer.Interval = $Seconds * 1000
$timer.add_Tick({
    $notifyIcon.Visible = $false
    $notifyIcon.Dispose()
    $timer.Dispose()
    [System.Windows.Forms.Application]::Exit()
})

$notifyIcon.Visible = $true
$timer.Start()
Write-Output "TRAY_PROBE_VISIBLE $($notifyIcon.Visible) $Text"
[System.Windows.Forms.Application]::Run()
