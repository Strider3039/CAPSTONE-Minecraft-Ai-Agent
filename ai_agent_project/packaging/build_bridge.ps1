# Build the Forge mod JAR and a PyInstaller onedir for the Python bridge.
$ErrorActionPreference = "Stop"
$ProjectRoot = Split-Path -Parent $PSScriptRoot
Set-Location $ProjectRoot

# Release file locks so PyInstaller can replace packaging\dist\minecraft_ai_bridge
Get-Process -Name "minecraft_ai_bridge" -ErrorAction SilentlyContinue | Stop-Process -Force
Start-Sleep -Milliseconds 500

Push-Location (Join-Path $ProjectRoot "bot\forge")
try {
  if (Test-Path ".\gradlew.bat") {
    .\gradlew.bat build --no-daemon
  } else {
    throw "gradlew.bat not found under bot/forge"
  }
} finally {
  Pop-Location
}

$jar = Get-ChildItem -Path (Join-Path $ProjectRoot "bot\forge\build\libs") -Filter "*.jar" -ErrorAction SilentlyContinue |
  Where-Object { $_.Name -notmatch "sources|javadoc" } |
  Sort-Object LastWriteTime -Descending |
  Select-Object -First 1

if (-not $jar) {
  Write-Warning "No mod JAR found under bot/forge/build/libs (build may have failed)."
} else {
  Write-Host "Mod JAR (prefer *-all.jar for players; embeds WebSocket dependency): $($jar.FullName)"
}

$reqDefault = Join-Path $ProjectRoot "requirements/bridge-build.txt"
$reqWinCpu = Join-Path $ProjectRoot "requirements/bridge-packaging-win-cpu.txt"
if ($env:OS -like "*Windows*" -and (Test-Path $reqWinCpu)) {
  Write-Host "Using CPU-only PyTorch for packaging (requirements/bridge-packaging-win-cpu.txt) - avoids c10.dll WinError 1114 on PCs without matching CUDA drivers."
  python -m pip install -r $reqWinCpu
} else {
  python -m pip install -r $reqDefault
}

# Bundle a fresh dqn_initial.pt so the frozen bridge resolves checkpoints/dqn_initial.pt from _MEIPASS
# (matches configs/default.yaml and ai.rl.dqn.save_initial_checkpoint).
$StageData = Join-Path $ProjectRoot ".bridge_packaging_staging\Data"
Remove-Item -Path $StageData -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Path $StageData -Force | Out-Null
$env:AI_AGENT_BRIDGE_DATA = $StageData
try {
  Push-Location $ProjectRoot
  python "ai\rl\dqn\save_initial_checkpoint.py"
} finally {
  Pop-Location
  Remove-Item Env:\AI_AGENT_BRIDGE_DATA -ErrorAction SilentlyContinue
}

$PackagingDir = Join-Path $ProjectRoot "packaging"
$spec = Join-Path $PackagingDir "bridge.spec"
pyinstaller $spec --noconfirm --workpath (Join-Path $PackagingDir "build\pyinstaller") --distpath (Join-Path $PackagingDir "dist")

# Ship example for Minecraft config UI ↔ bridge Data/runtime_overrides.yaml sync (see README + HOT_RELOAD_AND_PATHS.md).
$distBridge = Join-Path $PackagingDir "dist\minecraft_ai_bridge"
$exampleSrc = Join-Path $ProjectRoot "configs\ai_agent_bridge_data_path.example.txt"
if ((Test-Path $exampleSrc) -and (Test-Path $distBridge)) {
  $distData = Join-Path $distBridge "Data"
  New-Item -ItemType Directory -Path $distData -Force | Out-Null
  Copy-Item -Force $exampleSrc (Join-Path $distData "ai_agent_bridge_data_path.example.txt")
}

$bridgeInternal = Join-Path $PackagingDir "dist\minecraft_ai_bridge\_internal"
$bundledMsvcp = Join-Path $bridgeInternal "msvcp140.dll"
if ($env:OS -like "*Windows*" -and (Test-Path $bundledMsvcp)) {
  # Torch 2.10 CPU on some Windows systems fails c10.dll init (WinError 1114) when
  # PyInstaller's copied msvcp140.dll is loaded from _internal. Prefer the system
  # VC++ redistributable copy instead; README already calls out the prerequisite.
  Remove-Item -Force $bundledMsvcp
  Write-Host "Removed bundled msvcp140.dll from bridge _internal to avoid PyTorch c10.dll WinError 1114."
}

Write-Host "Bridge folder: $(Join-Path $PackagingDir 'dist\minecraft_ai_bridge')"
Write-Host "When frozen, writable data (checkpoints, logs, overlay) goes in Data\ next to minecraft_ai_bridge.exe."
Write-Host "CurseForge + packaged bridge: add config/ai_agent_bridge_data_path.txt (see Data/ai_agent_bridge_data_path.example.txt) or set -Dai_agent.bridge_data= to that Data folder so the mod UI reads/writes the same runtime_overrides.yaml as Python."
Write-Host "Do not use collect_all('torch') in bridge.spec; it duplicates DLLs and can break PyTorch on Windows."
Write-Host "Portable bridge-only release (README + Install_AI_Bridge.exe): packaging\build_bridge_release.ps1"
Write-Host "Linux / EC2: packaging\build_all_linux.sh (see packaging\release_bundle\README_LINUX.md)"
