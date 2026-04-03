# Deprecated: use packaging\build_bridge_release.ps1 (bridge-only + README release folder).
# This stub forwards to the new script.
$ErrorActionPreference = "Stop"
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$root = Split-Path -Parent (Split-Path -Parent $here)
& (Join-Path $root "packaging\build_bridge_release.ps1")
