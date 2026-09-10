$ErrorActionPreference = 'Stop'
$backendRoot = Split-Path $PSScriptRoot -Parent
$firmwareRoot = 'C:\Users\Jomar\OneDrive\Documents\Arduino\Premier\premier'
$manifest = Get-Content -LiteralPath (Join-Path $backendRoot 'docs/firmware-source-manifest.json') -Raw | ConvertFrom-Json
foreach ($entry in $manifest) {
    if ($entry.file -notin @('premier.ino','terminal_config.h')) { throw 'Unexpected patch target.' }
    $destination = Join-Path $firmwareRoot $entry.file
    $source = Join-Path $backendRoot ('target/firmware-source/premier/' + $entry.file)
    if ($entry.originalSha256) {
        if ((Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash -ne $entry.originalSha256) { throw "Source changed since review: $($entry.file)" }
    } elseif (Test-Path -LiteralPath $destination) { throw "New file already exists: $($entry.file)" }
    if ((Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash -ne $entry.updatedSha256) { throw "Staged result changed since review: $($entry.file)" }
}
foreach ($entry in $manifest) {
    Copy-Item -LiteralPath (Join-Path $backendRoot ('target/firmware-source/premier/' + $entry.file)) -Destination (Join-Path $firmwareRoot $entry.file)
    if ((Get-FileHash -LiteralPath (Join-Path $firmwareRoot $entry.file) -Algorithm SHA256).Hash -ne $entry.updatedSha256) { throw 'Verification failed.' }
    Write-Output "Applied and hash-verified $($entry.file)"
}
