#!/usr/bin/env bash
# Build install_ai_bridge + _internal and assemble Minecraft_AI_Bridge_Release_Linux/.
# Prerequisites: run packaging/build_bridge_linux.sh first.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$PROJECT_ROOT"

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
  PY="$(_pick_venv_interpreter "$VIRTUAL_ENV")" || {
    echo "VIRTUAL_ENV=$VIRTUAL_ENV has no bin/python or bin/python3."
    exit 1
  }
elif [[ -n "${CONDA_PREFIX:-}" ]]; then
  PY="$(_pick_venv_interpreter "$CONDA_PREFIX")" || {
    echo "CONDA_PREFIX=$CONDA_PREFIX has no bin/python or bin/python3."
    exit 1
  }
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
DIST="$PROJECT_ROOT/packaging/dist"
WORK="$DIST/pyinstaller_installer_work_linux"
BRIDGE_ONEDIR="$DIST/minecraft_ai_bridge"
BRIDGE_BIN="$BRIDGE_ONEDIR/minecraft_ai_bridge"

if [[ ! -f "$BRIDGE_BIN" ]]; then
  echo "Missing $BRIDGE_BIN — run packaging/build_bridge_linux.sh first."
  exit 1
fi

pkill -f "minecraft_ai_bridge" 2>/dev/null || true
pkill -f "install_ai_bridge" 2>/dev/null || true
sleep 0.6

PAYLOAD_ZIP="$DIST/bridge_payload.zip"
STAGE_ROOT="$DIST/_bridge_payload_stage"
STAGE_BRIDGE="$STAGE_ROOT/bridge"
rm -rf "$STAGE_ROOT"
mkdir -p "$STAGE_BRIDGE"

shopt -s nullglob
for item in "$BRIDGE_ONEDIR"/*; do
  base="$(basename "$item")"
  [[ "$base" == "Data" || "$base" == "logs" ]] && continue
  cp -a "$item" "$STAGE_BRIDGE/"
done
shopt -u nullglob

rm -f "$PAYLOAD_ZIP"
(
  cd "$STAGE_ROOT"
  zip -r -q "$PAYLOAD_ZIP" bridge
)
rm -rf "$STAGE_ROOT"

BOOTSTRAPPER="$PROJECT_ROOT/packaging/installer/bootstrapper.py"
INSTALLER_SRC="$PROJECT_ROOT/packaging/installer"
INSTALLER_DIST="$DIST/installer_out_linux"
rm -rf "$INSTALLER_DIST" "$WORK"

# Unix add-data separator is ':' (see PyInstaller docs).
PAYLOAD_ABS="$(cd "$(dirname "$PAYLOAD_ZIP")" && pwd)/$(basename "$PAYLOAD_ZIP")"
"$PY" -m PyInstaller "$BOOTSTRAPPER" \
  --noconfirm \
  --add-data "${PAYLOAD_ABS}:." \
  --paths "$INSTALLER_SRC" \
  --hidden-import bridge_install_artifacts \
  --name install_ai_bridge \
  --workpath "$WORK" \
  --specpath "$WORK" \
  --distpath "$INSTALLER_DIST" \
  --collect-all tkinter

INSTALLER_DIR="$INSTALLER_DIST/install_ai_bridge"
if [[ ! -f "$INSTALLER_DIR/install_ai_bridge" ]]; then
  echo "PyInstaller did not produce: $INSTALLER_DIR/install_ai_bridge"
  exit 1
fi
chmod +x "$INSTALLER_DIR/install_ai_bridge"

RELEASE_DIR="$DIST/Minecraft_AI_Bridge_Release_Linux"
rm -rf "$RELEASE_DIR"
mkdir -p "$RELEASE_DIR"
cp -a "$INSTALLER_DIR"/* "$RELEASE_DIR/"
README_LINUX_SRC="$PROJECT_ROOT/packaging/dist/README_Linux.md"
if [[ ! -f "$README_LINUX_SRC" ]]; then
  echo "Missing $README_LINUX_SRC — add packaging/dist/README_Linux.md (tracked in git) before building the release."
  exit 1
fi
cp -f "$README_LINUX_SRC" "$RELEASE_DIR/README.md"

echo ""
echo "Release folder ready:"
echo "  $RELEASE_DIR"
echo "  - README.md"
echo "  - install_ai_bridge"
echo "  - _internal/   (required; ship the whole folder)"
echo "Zip Minecraft_AI_Bridge_Release_Linux for upload to EC2."
