#!/usr/bin/env bash
# One-shot: PyInstaller bridge + Linux installer bundle.
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
bash "$SCRIPT_DIR/build_bridge_linux.sh"
bash "$SCRIPT_DIR/build_bridge_release_linux.sh"
