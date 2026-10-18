param(
    [string]$Name = 'BetterRNP'
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName UIAutomationClient
Add-Type -AssemblyName UIAutomationTypes

$condition = New-Object System.Windows.Automation.PropertyCondition(
    [System.Windows.Automation.AutomationElement]::NameProperty,
    $Name)
$matches = [System.Windows.Automation.AutomationElement]::RootElement.FindAll(
    [System.Windows.Automation.TreeScope]::Descendants,
    $condition)

Write-Output "MATCH_COUNT $($matches.Count)"
foreach ($element in $matches) {
    $rect = $element.Current.BoundingRectangle
    Write-Output (
        '{0} | class={1} | automation={2} | x={3} y={4} w={5} h={6}' -f
        $element.Current.ControlType.ProgrammaticName,
        $element.Current.ClassName,
        $element.Current.AutomationId,
        [int]$rect.X,
        [int]$rect.Y,
        [int]$rect.Width,
        [int]$rect.Height)
}
