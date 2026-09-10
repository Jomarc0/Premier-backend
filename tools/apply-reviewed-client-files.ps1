param([Parameter(Mandatory=$true)][string]$Stage)
$ErrorActionPreference='Stop'
$workspace=Split-Path $PSScriptRoot -Parent
$stageRoot=[IO.Path]::GetFullPath((Join-Path $workspace $Stage))
if(!$stageRoot.StartsWith((Join-Path $workspace 'target')+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){throw 'Stage must be inside target.'}
$clientRoot='C:\Users\Jomar\Premier'
$manifest=Get-Content -LiteralPath (Join-Path $stageRoot 'manifest.json') -Raw | ConvertFrom-Json
foreach($entry in $manifest) {
    $destination=[IO.Path]::GetFullPath((Join-Path $clientRoot $entry.file))
    if(!$destination.StartsWith($clientRoot+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){throw 'Invalid destination.'}
    if($entry.originalSha256) {
        if((Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash -ne $entry.originalSha256){throw "Source changed since review: $($entry.file)"}
    } elseif(Test-Path -LiteralPath $destination){throw "New file already exists: $($entry.file)"}
    if(!(Test-Path -LiteralPath (Join-Path $stageRoot ('files/'+$entry.file)))){throw 'Missing staged file.'}
}
foreach($entry in $manifest) {
    $source=Join-Path $stageRoot ('files/'+$entry.file)
    $destination=Join-Path $clientRoot $entry.file
    New-Item -ItemType Directory -Path (Split-Path $destination -Parent) -Force | Out-Null
    Copy-Item -LiteralPath $source -Destination $destination
    if((Get-FileHash -LiteralPath $source).Hash -ne (Get-FileHash -LiteralPath $destination).Hash){throw 'Verification failed.'}
    Write-Output "Applied and verified $($entry.file)"
}
