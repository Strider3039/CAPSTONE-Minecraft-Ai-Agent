#!/usr/bin/env bash
# Build the Forge mod JAR and a PyInstaller onedir for the Python bridge (Linux).
# Run from repo:   bash packaging/build_bridge_linux.sh
# Prerequisites:  Python 3.9+ (jsonschema stack needs rfc3987-syntax), JDK 17 (Gradle), build-essential, venv (see README_LINUX.md).
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$PROJECT_ROOT"

# Resolve Python (PEP 668: Ubuntu's system python3 blocks pip). Subshells only see *exported* VIRTUAL_ENV.
_pick_venv_interpreter() {
  local root="$1"
  [[ -z "$root" ]] && return 1
  for c in "${root}/bin/python" "${root}/bin/python3"; do
    [[ -x "$c" ]] && echo "$c" && return 0
  done
  return 1
}

if [[ -n "${PYTHON:-}" ]]; then
  PY="$PYTHON"
elif [[ -n "${VIRTUAL_ENV:-}" ]]; then
  if ! PY="$(_pick_venv_interpreter "$VIRTUAL_ENV")"; then
    echo "VIRTUAL_ENV=$VIRTUAL_ENV but that tree has no executable bin/python or bin/python3. Recreate the venv (see packaging/release_bundle/README_LINUX.md)."
    exit 1
  fi
elif [[ -n "${CONDA_PREFIX:-}" ]]; then
  if ! PY="$(_pick_venv_interpreter "$CONDA_PREFIX")"; then
    echo "CONDA_PREFIX=$CONDA_PREFIX has no bin/python or bin/python3."
    exit 1
  fi
else
  PY=""
  for candidate_root in "$PROJECT_ROOT/.venv" "$PROJECT_ROOT/../.venv38" "$PROJECT_ROOT/../.venv"; do
    if PY="$(_pick_venv_interpreter "$candidate_root")"; then
      break
    fi
    PY=""
  done
  [[ -n "$PY" ]] || PY="python3"
fi

command -v "$PY" >/dev/null || {
  echo "Need a usable Python. Use a venv: python3 -m venv .venv && source .venv/bin/activate"
  echo "Or: PYTHON=/path/to/venv/bin/python3 ./packaging/build_all_linux.sh"
  exit 1
}
echo "Bridge packaging Python: $PY"

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
