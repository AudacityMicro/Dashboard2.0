param([switch]$Test,[string]$OutputDirectory='')
$ErrorActionPreference='Stop'
$framework=Join-Path $env:WINDIR 'Microsoft.NET/Framework64/v4.0.30319'
$compiler=Join-Path $framework 'csc.exe'
$out=if($OutputDirectory) { [IO.Path]::GetFullPath($OutputDirectory) } else { Join-Path $PSScriptRoot 'bin' }
New-Item -ItemType Directory -Force -Path $out | Out-Null
$lib=Join-Path $PSScriptRoot 'lib'
New-Item -ItemType Directory -Force -Path $lib | Out-Null
$packages=@(
 @('aforge','AForge','0795462ABCFFF94E1347CC8500A82EDFAA6E0367A8FC11D1A854949764B47861'),
 @('aforge.video','AForge.Video','D6ADFEF653E8469B0FDB4EED54501185B11788BD2E952EE3C1223B18D95A7C5E'),
 @('aforge.video.directshow','AForge.Video.DirectShow','0049BCF3E448689C238AE60522FA13956A58AD67A086A18AF414B2D36939ACEC')
)
Add-Type -AssemblyName System.IO.Compression.FileSystem
foreach($package in $packages) {
 $archive=Join-Path $lib ($package[0]+'.nupkg')
 if(-not(Test-Path -LiteralPath $archive)) { Invoke-WebRequest -Uri "https://api.nuget.org/v3-flatcontainer/$($package[0])/2.2.5/$($package[0]).2.2.5.nupkg" -OutFile $archive }
 if((Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash -ne $package[2]) { throw 'Camera dependency checksum mismatch' }
 $zip=[IO.Compression.ZipFile]::OpenRead($archive)
 try {
  $entry=$zip.GetEntry("lib/$($package[1]).dll")
  $inputStream=$entry.Open(); $outputStream=[IO.File]::Create((Join-Path $lib ($package[1]+'.dll')))
  try { $inputStream.CopyTo($outputStream) } finally { $inputStream.Dispose(); $outputStream.Dispose() }
 } finally { $zip.Dispose() }
}
$refs=@('System.dll','System.Xaml.dll','System.Core.dll','System.Net.Http.dll','System.Web.dll','System.Web.Extensions.dll','System.Security.dll','System.Drawing.dll','System.Windows.Forms.dll') | ForEach-Object { '/r:'+ (Join-Path $framework $_) }
$speech=Get-ChildItem -LiteralPath "$env:WINDIR/Microsoft.NET/assembly/GAC_MSIL/System.Speech" -Filter System.Speech.dll -Recurse | Select-Object -First 1 -ExpandProperty FullName
$refs+='/r:'+$speech
$refs+=Get-ChildItem -LiteralPath $lib -Filter '*.dll' | ForEach-Object { '/r:'+ $_.FullName }
$refs+=@('PresentationCore.dll','PresentationFramework.dll','WindowsBase.dll','WindowsFormsIntegration.dll') | ForEach-Object { '/r:'+ (Join-Path "$framework/WPF" $_) }
$sources=Get-ChildItem -LiteralPath $PSScriptRoot -Filter '*.cs' | ForEach-Object FullName
& $compiler /nologo /target:winexe /platform:anycpu /optimize+ /main:DashboardWindows.Program "/out:$out/Dashboard.exe" @refs @sources
if($LASTEXITCODE -ne 0) { throw 'Windows build failed' }
Copy-Item -LiteralPath "$PSScriptRoot/Dashboard.exe.config" -Destination $out
Get-ChildItem -LiteralPath $lib -Filter '*.dll' | Copy-Item -Destination $out
if($Test) {
 & $compiler /nologo /target:exe "/out:$out/Tests.exe" "/r:$out/Dashboard.exe" @refs (Resolve-Path -LiteralPath "$PSScriptRoot/tests/Tests.cs").Path
 if($LASTEXITCODE -ne 0) { throw 'Test build failed' }
 & "$out/Tests.exe"
 if($LASTEXITCODE -ne 0) { throw 'Windows tests failed' }
}
