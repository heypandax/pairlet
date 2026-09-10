# Brand an unsigned candidate MSI without changing its installation or upgrade identity.
# Run immediately after jpackage and BEFORE signing/checksums. Never run on a published MSI.
param([Parameter(Mandatory=$true)][string]$Path)
$ErrorActionPreference = 'Stop'
$msiPath = (Resolve-Path $Path).Path
if ((Get-AuthenticodeSignature $msiPath).SignerCertificate) {
    throw 'Refusing to modify a signed MSI; brand before signing.'
}
$installer = New-Object -ComObject WindowsInstaller.Installer
$database = $installer.OpenDatabase($msiPath, 1) # transacted: commit only after invariants pass
function Read-Rows([string]$sql) {
    $view = $database.OpenView($sql)
    # COM methods can emit return values into PowerShell's success stream. Only the row array
    # belongs in this function's output; extra values make a single property look ambiguous.
    [void]$view.Execute()
    $rows = @()
    while ($null -ne ($record = $view.Fetch())) {
        $row = @()
        for ($i = 1; $i -le $record.FieldCount; $i++) { $row += $record.StringData($i) }
        $rows += ,$row
    }
    [void]$view.Close()
    return ,$rows
}
function Execute-Sql([string]$sql) {
    $view = $database.OpenView($sql)
    [void]$view.Execute()
    [void]$view.Close()
}
function Property-Value([string]$name) {
    $rows = Read-Rows "SELECT ``Value`` FROM ``Property`` WHERE ``Property`` = '$name'"
    if ($rows.Count -ne 1) { throw "Missing/ambiguous MSI property $name (rows: $($rows.Count))" }
    return $rows[0][0]
}
# Read from the SHA-256-verified v1.9.8 MSI, not generated from the new product name.
$expectedUpgrade = '{230D5F5E-4C7A-3DE9-98EE-6E492CCCB7D0}'
if ((Property-Value 'UpgradeCode') -ne $expectedUpgrade) { throw 'MSI UpgradeCode drifted from v1.9.8' }
if ((Property-Value 'ProductName') -notin @('CC Pocket', 'CC Pairlet')) { throw 'Unexpected product name' }
$properties = @('UpgradeCode','ProductCode','ProductVersion','Manufacturer','ALLUSERS')
$beforeProperties = @{}
foreach ($name in $properties) { $beforeProperties[$name] = Property-Value $name }
# Tables controlling payload, destination, registration and upgrade behavior must remain byte-for-byte
# equivalent at the row level. Shortcut display names are the sole exception, handled separately.
$tables = @('Directory','Component','File','Registry','Upgrade','Media','MsiFileHash')
$beforeTables = @{}
foreach ($table in $tables) { $beforeTables[$table] = ConvertTo-Json -InputObject (Read-Rows "SELECT * FROM ``$table``") -Depth 5 -Compress }
Execute-Sql "UPDATE ``Property`` SET ``Value`` = 'CC Pairlet' WHERE ``Property`` = 'ProductName'"
$shortcutTables = Read-Rows "SELECT ``Name`` FROM ``_Tables`` WHERE ``Name`` = 'Shortcut'"
if ($shortcutTables.Count -ne 1) { throw 'Candidate MSI must contain the requested system shortcuts' }
if ($shortcutTables.Count -gt 0) {
    $shortcuts = Read-Rows 'SELECT `Shortcut`, `Name`, `Target`, `Component_`, `Directory_` FROM `Shortcut`'
    $branded = 0
    foreach ($row in $shortcuts) {
        # The launcher and destination stay CC Pocket; only its system-search/Start-menu label changes.
        $longName = ($row[1] -split '\|')[-1]
        if ($longName -in @('CC Pocket', 'CC Pocket.lnk', 'CC Pairlet', 'CC Pairlet.lnk')) {
            $displayName = if ($longName.EndsWith('.lnk')) { 'CC Pairlet.lnk' } else { 'CC Pairlet' }
            $id = $row[0].Replace("'", "''")
            Execute-Sql "UPDATE ``Shortcut`` SET ``Name`` = '$displayName' WHERE ``Shortcut`` = '$id'"
            $branded++
        }
    }
    if ($branded -eq 0) { throw 'No recognizable application shortcut was branded' }
    $after = Read-Rows 'SELECT `Shortcut`, `Name`, `Target`, `Component_`, `Directory_` FROM `Shortcut`'
    if ($after.Count -ne $shortcuts.Count) { throw 'Shortcut count changed' }
    for ($i=0; $i -lt $after.Count; $i++) {
        foreach ($j in @(0,2,3,4)) { if ($after[$i][$j] -ne $shortcuts[$i][$j]) { throw 'Shortcut target changed' } }
        if ((($after[$i][1] -split '\|')[-1]) -in @('CC Pocket', 'CC Pocket.lnk')) { throw 'Legacy shortcut label remained' }
    }
}
foreach ($name in $properties) {
    if ((Property-Value $name) -ne $beforeProperties[$name]) { throw "MSI identity changed: $name" }
}
foreach ($table in $tables) {
    $after = ConvertTo-Json -InputObject (Read-Rows "SELECT * FROM ``$table``") -Depth 5 -Compress
    if ($after -ne $beforeTables[$table]) { throw "MSI table changed: $table" }
}
$database.Commit()
Write-Host "CC Pairlet display metadata applied; upgrade identity and package layout preserved: $msiPath"
