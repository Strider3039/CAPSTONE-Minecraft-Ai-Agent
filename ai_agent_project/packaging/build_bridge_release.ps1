# Build a distributable folder: README + bridge-only installer (no mod deployment).
# Prerequisites: run packaging\build_bridge.ps1 first so dist\minecraft_ai_bridge exists.
$ErrorActionPreference = "Stop"
$ProjectRoot = Split-Path -Parent $PSScriptRoot
Set-Location $ProjectRoot

# File locks on dist\minecraft_ai_bridge (e.g. base_library.zip) break Compress-Archive if the bridge is still running.
foreach ($procName in @("minecraft_ai_bridge", "Install_AI_Bridge")) {
  Get-Process -Name $procName -ErrorAction SilentlyContinue | Stop-Process -Force
}
Start-Sleep -Milliseconds 600

$dist = Join-Path $ProjectRoot "dist"
$work = Join-Path $dist "pyinstaller_installer_work"
$bridgeOnedir = Join-Path $dist "minecraft_ai_bridge"
$bridgeExe = Join-Path $bridgeOnedir "minecraft_ai_bridge.exe"

if (-not (Test-Path $bridgeExe)) {
  throw "Missing $bridgeExe - run packaging\build_bridge.ps1 first."
}

$payloadZip = Join-Path $dist "bridge_payload.zip"
$stageRoot = Join-Path $dist "_bridge_payload_stage"
$stageBridge = Join-Path $stageRoot "bridge"

if (Test-Path $stageRoot) {
  Remove-Item -Recurse -Force $stageRoot
}
New-Item -ItemType Directory -Path $stageBridge -Force | Out-Null
# Do not ship a dev machine's Data/ or logs/ inside the payload zip.
Get-ChildItem -Path $bridgeOnedir -Force | Where-Object {
  $_.Name -ne "Data" -and $_.Name -ne "logs"
} | ForEach-Object {
  Copy-Item -Path $_.FullName -Destination $stageBridge -Recurse -Force
}

Start-Sleep -Milliseconds 400

if (Test-Path $payloadZip) {
  Remove-Item -Force $payloadZip
}
Write-Host "Creating bridge_payload.zip (bridge only, no mod)..."
$zipOk = $false
for ($i = 0; $i -lt 4; $i++) {
  try {
    Compress-Archive -Path $stageBridge -DestinationPath $payloadZip -Force -ErrorAction Stop
    $zipOk = $true
    break
  } catch {
    Write-Warning "Compress-Archive attempt $($i+1) failed: $($_.Exception.Message)"
    Start-Sleep -Milliseconds 800
  }
}
if (-not $zipOk) {
  throw "Could not create bridge_payload.zip (files may be locked). Close minecraft_ai_bridge.exe, antivirus scan, or retry."
}
Remove-Item -Recurse -Force $stageRoot

Write-Host "Building Install_AI_Bridge.exe (PyInstaller)..."
$bootstrapper = Join-Path $ProjectRoot "packaging\installer\bootstrapper.py"
$installerDist = Join-Path $dist "installer_out"
pyinstaller $bootstrapper `
  --noconfirm `
  --add-data "$payloadZip;." `
  --name "Install_AI_Bridge" `
  --workpath $work `
  --specpath $work `
  --distpath $installerDist `
  --collect-all tkinter

$installerBundleDir = Join-Path $installerDist "Install_AI_Bridge"
$builtInstaller = Join-Path $installerBundleDir "Install_AI_Bridge.exe"
if (-not (Test-Path $builtInstaller)) {
  throw "PyInstaller did not produce: $builtInstaller"
}

$releaseDir = Join-Path $dist "Minecraft_AI_Bridge_Release"
if (Test-Path $releaseDir) {
  Remove-Item -Recurse -Force $releaseDir
}
New-Item -ItemType Directory -Path $releaseDir -Force | Out-Null

# PyInstaller onedir: exe + _internal must stay together; copying only the exe breaks LoadLibrary(python312.dll).
Copy-Item -Path (Join-Path $installerBundleDir "*") -Destination $releaseDir -Recurse -Force
Copy-Item -Path (Join-Path $ProjectRoot "packaging\release_bundle\README.md") -Destination (Join-Path $releaseDir "README.md") -Force

Write-Host ""
Write-Host "Release folder ready:"
Write-Host "  $releaseDir"
Write-Host "  - README.md"
Write-Host "  - Install_AI_Bridge.exe"
Write-Host "  - _internal\   (required; ship the whole folder when zipping)"
Write-Host "Zip the entire Minecraft_AI_Bridge_Release folder to share. The installer does not install the Forge mod."
