# Native Windows Installer regression test. Creates databases only; never installs a product.
$ErrorActionPreference = 'Stop'
$testRoot = Join-Path ([IO.Path]::GetTempPath()) ('pairlet-msi-test-' + [guid]::NewGuid())
[void](New-Item -ItemType Directory -Path $testRoot)
$brandScript = Join-Path $PSScriptRoot 'brand-windows-msi.ps1'
$installer = New-Object -ComObject WindowsInstaller.Installer

function New-Fixture([string]$path, [string]$upgrade) {
    $db = $installer.OpenDatabase($path, 3)
    $commands = @(
        'CREATE TABLE `Property` (`Property` CHAR(72) NOT NULL, `Value` CHAR(255) PRIMARY KEY `Property`)',
        "INSERT INTO ``Property`` (``Property``, ``Value``) VALUES ('UpgradeCode', '$upgrade')",
        "INSERT INTO ``Property`` (``Property``, ``Value``) VALUES ('ProductName', 'CC Pocket')",
        "INSERT INTO ``Property`` (``Property``, ``Value``) VALUES ('ProductCode', '{10000000-0000-0000-0000-000000000001}')",
        "INSERT INTO ``Property`` (``Property``, ``Value``) VALUES ('ProductVersion', '2.0.0')",
        "INSERT INTO ``Property`` (``Property``, ``Value``) VALUES ('Manufacturer', 'Pairlet test')",
        "INSERT INTO ``Property`` (``Property``, ``Value``) VALUES ('ALLUSERS', '1')",
        'CREATE TABLE `Shortcut` (`Shortcut` CHAR(72) NOT NULL, `Name` CHAR(255), `Target` CHAR(255), `Component_` CHAR(72), `Directory_` CHAR(72) PRIMARY KEY `Shortcut`)',
        "INSERT INTO ``Shortcut`` (``Shortcut``, ``Name``, ``Target``, ``Component_``, ``Directory_``) VALUES ('app', 'CC Pocket', '[#launcher]', 'main', 'menu')"
    )
    foreach ($table in @('Directory','Component','File','Registry','Upgrade','Media','MsiFileHash')) {
        $commands += "CREATE TABLE ``$table`` (``Fixture`` CHAR(72) NOT NULL PRIMARY KEY ``Fixture``)"
        $commands += "INSERT INTO ``$table`` (``Fixture``) VALUES ('unchanged')"
    }
    foreach ($sql in $commands) {
        $view = $db.OpenView($sql)
        [void]$view.Execute()
        [void]$view.Close()
        [void][Runtime.InteropServices.Marshal]::FinalReleaseComObject($view)
    }
    [void]$db.Commit()
    [void][Runtime.InteropServices.Marshal]::FinalReleaseComObject($db)
}

function Read-Scalar([string]$path, [string]$sql) {
    $db = $installer.OpenDatabase($path, 0)
    $view = $db.OpenView($sql)
    [void]$view.Execute()
    $record = $view.Fetch()
    $value = $record.StringData(1)
    [void]$view.Close()
    foreach ($com in @($record, $view, $db)) {
        [void][Runtime.InteropServices.Marshal]::FinalReleaseComObject($com)
    }
    return $value
}

try {
    $valid = Join-Path $testRoot 'valid candidate.msi'
    New-Fixture $valid '{230D5F5E-4C7A-3DE9-98EE-6E492CCCB7D0}'
    # Repeat to verify that rerunning a packaging hook preserves an already branded candidate.
    foreach ($attempt in 1..2) {
        & powershell -NoProfile -File $brandScript -Path $valid
        if ($LASTEXITCODE -ne 0) { throw "Branding valid MSI failed on attempt $attempt" }
        if ((Read-Scalar $valid "SELECT ``Value`` FROM ``Property`` WHERE ``Property`` = 'ProductName'") -ne 'CC Pairlet') {
            throw 'Product name was not committed'
        }
        if ((Read-Scalar $valid 'SELECT `Name` FROM `Shortcut`') -ne 'CC Pairlet') { throw 'Shortcut was not branded' }
        if ((Read-Scalar $valid 'SELECT `Target` FROM `Shortcut`') -ne '[#launcher]') { throw 'Shortcut target changed' }
    }
    $invalid = Join-Path $testRoot 'wrong identity.msi'
    New-Fixture $invalid '{20000000-0000-0000-0000-000000000002}'
    & powershell -NoProfile -File $brandScript -Path $invalid
    if ($LASTEXITCODE -eq 0) { throw 'MSI with a different upgrade identity was accepted' }
    if ((Read-Scalar $invalid "SELECT ``Value`` FROM ``Property`` WHERE ``Property`` = 'ProductName'") -ne 'CC Pocket') {
        throw 'Rejected MSI was modified'
    }
    Write-Host 'MSI branding OK: native COM rows, committed labels, stable target, repeat invocation and rejected identity'
    exit 0
} finally {
    [void][Runtime.InteropServices.Marshal]::FinalReleaseComObject($installer)
    Remove-Item -Recurse -Force $testRoot
}
