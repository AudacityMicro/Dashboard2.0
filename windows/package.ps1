param([string]$BuildDirectory='',[Parameter(Mandatory=$true)][string]$Destination)
$ErrorActionPreference='Stop'
if(-not $BuildDirectory) { $BuildDirectory=Join-Path $PSScriptRoot 'bin' }
$taskBuild=[IO.Path]::GetFullPath($BuildDirectory)
$taskVersion=[Diagnostics.FileVersionInfo]::GetVersionInfo((Join-Path $taskBuild 'Dashboard.exe')).ProductVersion
if($taskVersion -notmatch '^\d+\.\d+\.\d+(?:-preview\.\d+)?$') { throw 'Build has no valid version metadata' }
$taskStage=Join-Path $PSScriptRoot ('bin/package-'+[guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $taskStage -Force | Out-Null
$taskFiles=@('Dashboard.exe','Dashboard.exe.config','Dashboard.Update.exe','AForge.dll','AForge.Video.dll','AForge.Video.DirectShow.dll','README.md','licenses/AForge-LICENSE.txt','licenses/GPL-3.0.txt','licenses/LGPL-3.0.txt')
$taskHashes=[ordered]@{}
foreach($taskName in $taskFiles) {
 $taskSource=if($taskName -eq 'README.md' -or $taskName.StartsWith('licenses/')) { Join-Path $PSScriptRoot $taskName } else { Join-Path $taskBuild $taskName }
 $taskTarget=Join-Path $taskStage $taskName
 New-Item -ItemType Directory -Path (Split-Path -Parent $taskTarget) -Force | Out-Null
 Copy-Item -LiteralPath $taskSource -Destination $taskTarget
 $taskHashes[$taskName]=(Get-FileHash -LiteralPath $taskTarget -Algorithm SHA256).Hash.ToLowerInvariant()
}
$taskManifest=@{schemaVersion=1;version='v'+$taskVersion;files=$taskHashes} | ConvertTo-Json -Depth 5
[IO.File]::WriteAllText((Join-Path $taskStage 'dashboard-update.json'),$taskManifest,[Text.UTF8Encoding]::new($false))
$taskDestination=[IO.Path]::GetFullPath($Destination)
New-Item -ItemType Directory -Path (Split-Path -Parent $taskDestination) -Force | Out-Null
Compress-Archive -Path "$taskStage/*" -DestinationPath $taskDestination -Force
Write-Output $taskDestination
