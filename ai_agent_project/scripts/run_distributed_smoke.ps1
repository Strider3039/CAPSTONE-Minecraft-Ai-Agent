# Manual / semi-automated smoke: bridge + Minecraft client with aligned Data and overlay.
# 1) Picks a free port, sets AI_AGENT_BRIDGE_DATA to a shared Data folder, starts bridge (exe or Python).
# 2) Launches Gradle runClient with -Dai_agent.bridge_uri and overlay path so the mod and bridge agree.
#
# Usage (from repo):
#   cd ai_agent_project
#   .\scripts\run_distributed_smoke.ps1
# Optional:
#   .\scripts\run_distributed_smoke.ps1 -UsePythonBridge    # python -m bridge.server instead of exe
#   .\scripts\run_distributed_smoke.ps1 -BridgePort 8765
#
# After the game loads, load a world; watch console for "WS connecting". Edit Data\runtime_overrides.yaml
# while running — the bridge should log runtime_overlay_reloaded_from_file and policy should pick up changes.

param(
  [int]$BridgePort = 0,
  [switch]$UsePythonBridge
)

$ErrorActionPreference = "Stop"
# scripts/ -> ai_agent_project/
$ProjectRoot = Split-Path -Parent $PSScriptRoot
Set-Location $ProjectRoot

if ($BridgePort -le 0) {
  $listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, 0)
  $listener.Start()
  $BridgePort = $listener.LocalEndpoint.Port
  $listener.Stop()
}

$DataDir = Join-Path $ProjectRoot "shared\Data"
New-Item -ItemType Directory -Path $DataDir -Force | Out-Null
$OverlayPath = Join-Path $DataDir "runtime_overrides.yaml"

$env:AI_AGENT_BRIDGE_PORT = "$BridgePort"
$env:AI_AGENT_BRIDGE_DATA = $DataDir

Write-Host "Bridge port: $BridgePort"
Write-Host "AI_AGENT_BRIDGE_DATA -> $DataDir"
Write-Host "Mod should use: -Dai_agent.bridge_uri=ws://127.0.0.1:$BridgePort"
Write-Host "Overlay file (edit while running to test hot reload): $OverlayPath"

$bridgeProc = $null
if ($UsePythonBridge) {
  Write-Host "Starting Python bridge (new window)..."
  $argList = @("/c", "set AI_AGENT_BRIDGE_PORT=$BridgePort&& set AI_AGENT_BRIDGE_DATA=$DataDir&& cd /d `"$ProjectRoot`" && python -m bridge.server")
  Start-Process cmd.exe -ArgumentList $argList -WindowStyle Normal
} else {
  $exe = Join-Path $ProjectRoot "packaging\dist\minecraft_ai_bridge\minecraft_ai_bridge.exe"
  if (-not (Test-Path $exe)) {
    Write-Warning "Packaged bridge not found at $exe — use -UsePythonBridge or run packaging\build_bridge.ps1 first."
    exit 1
  }
  Write-Host "Starting packaged bridge (new window)..."
  Start-Process -FilePath $exe -WorkingDirectory (Split-Path $exe) -PassThru -WindowStyle Normal | Out-Null
}

Start-Sleep -Seconds 2

$forgeRoot = Join-Path $ProjectRoot "bot\forge"
if (-not (Test-Path (Join-Path $forgeRoot "gradlew.bat"))) {
  throw "gradlew.bat not found under bot/forge"
}

$jvmBridge = "-Dai_agent.bridge_uri=ws://127.0.0.1:$BridgePort"
$jvmOverlay = "-Dai_agent.runtime_overlay=$OverlayPath"
Write-Host "Launching runClient with Gradle properties aiAgentBridgeUri / aiAgentRuntimeOverlay"

Push-Location $forgeRoot
try {
  .\gradlew.bat runClient --no-daemon `
    "-PaiAgentBridgeUri=$jvmBridge" `
    "-PaiAgentRuntimeOverlay=$jvmOverlay"
} finally {
  Pop-Location
}
