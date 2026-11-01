# Run PowerShell as Administrator, then execute this script.
$ruleName = "RilzzCam TCP 4747"
$existing = Get-NetFirewallRule -DisplayName $ruleName -ErrorAction SilentlyContinue
if ($existing) {
    $existing | Remove-NetFirewallRule
}
New-NetFirewallRule `
    -DisplayName $ruleName `
    -Direction Inbound `
    -Action Allow `
    -Protocol TCP `
    -LocalPort 4747 `
    -Profile Private
Write-Host "Firewall rule created: TCP 4747 (Private network)." -ForegroundColor Green
