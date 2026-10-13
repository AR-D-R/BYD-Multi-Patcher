param(
    [Parameter(Mandatory=$true)][string]$Apkm,
    [Parameter(Mandatory=$true)][string]$JavaHome,
    [string]$OutputDir = "$PSScriptRoot/results"
)
$ErrorActionPreference = 'Stop'
$project = (Resolve-Path "$PSScriptRoot/../..").Path
$source = "$project/app/src/main/java/com/bydspotifymanager/app"
New-Item -ItemType Directory -Force "$OutputDir/classes" | Out-Null
& "$JavaHome/bin/javac.exe" -d "$OutputDir/classes" `
    "$source/WazePatchEngine.java" "$source/BuildConfigData.java" `
    "$source/IoUtil.java" "$source/DexUtil.java" "$source/Spotify89BinaryUtil.java" `
    "$PSScriptRoot/ApkSignerUtil.java" "$PSScriptRoot/WazeValidation.java"
if ($LASTEXITCODE -ne 0) { throw 'Validation harness compilation failed' }
& "$JavaHome/bin/java.exe" -cp "$OutputDir/classes" `
    com.bydspotifymanager.app.WazeValidation $Apkm $OutputDir
if ($LASTEXITCODE -ne 0) { throw 'Waze binary validation failed' }
