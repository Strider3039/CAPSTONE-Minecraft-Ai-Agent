#!/usr/bin/env bash
# Build the Forge mod JAR and a PyInstaller onedir for the Python bridge (Linux).
# Run from repo:   bash packaging/build_bridge_linux.sh
# Prerequisites:  Python 3.10+, JDK 17 (for Gradle), build-essential, python3-venv optional.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$PROJECT_ROOT"

PY="${PYTHON:-python3}"
command -v "$PY" >/dev/null || {
  echo "Need python3 (or set PYTHON=...)."
  exit 1
}

pkill -f "minecraft_ai_bridge" 2>/dev/null || true
sleep 0.5

if [[ "${SKIP_MOD_BUILD:-}" != "1" ]]; then
  FORGE_DIR="$PROJECT_ROOT/bot/forge"
  chmod +x "$FORGE_DIR/gradlew" 2>/dev/null || true
  (cd "$FORGE_DIR" && ./gradlew build --no-daemon)
  echo "Mod JAR: check bot/forge/build/libs/ (prefer *-all.jar for players)."
else
  echo "SKIP_MOD_BUILD=1: skipping Gradle."
fi

REQ_LINUX_CPU="$PROJECT_ROOT/requirements/bridge-packaging-linux-cpu.txt"
REQ_DEFAULT="$PROJECT_ROOT/requirements/bridge-build.txt"
if [[ -f "$REQ_LINUX_CPU" ]] && [[ "$(uname -s)" == "Linux" ]]; then
  echo "Using CPU-only PyTorch pin: requirements/bridge-packaging-linux-cpu.txt"
  "$PY" -m pip install -r "$REQ_LINUX_CPU"
else
  "$PY" -m pip install -r "$REQ_DEFAULT"
fi

STAGE_DATA="$PROJECT_ROOT/.bridge_packaging_staging/Data"
rm -rf "$STAGE_DATA"
mkdir -p "$STAGE_DATA"
export AI_AGENT_BRIDGE_DATA="$STAGE_DATA"
(
  cd "$PROJECT_ROOT"
  "$PY" "ai/rl/dqn/save_initial_checkpoint.py"
)
unset AI_AGENT_BRIDGE_DATA

PACKAGING_DIR="$PROJECT_ROOT/packaging"
SPEC="$PACKAGING_DIR/bridge.spec"
"$PY" -m PyInstaller "$SPEC" --noconfirm \
  --workpath "$PACKAGING_DIR/build/pyinstaller_linux" \
  --distpath "$PACKAGING_DIR/dist"

DIST_BRIDGE="$PACKAGING_DIR/dist/minecraft_ai_bridge"
EXAMPLE_SRC="$PROJECT_ROOT/configs/ai_agent_bridge_data_path.example.txt"
if [[ -f "$EXAMPLE_SRC" ]] && [[ -d "$DIST_BRIDGE" ]]; then
  mkdir -p "$DIST_BRIDGE/Data"
  cp -f "$EXAMPLE_SRC" "$DIST_BRIDGE/Data/ai_agent_bridge_data_path.example.txt"
fi

echo "Bridge folder: $DIST_BRIDGE"
echo "Writable data lives in Data/ next to the minecraft_ai_bridge binary."
echo "Linux release (installer): packaging/build_bridge_release_linux.sh"
