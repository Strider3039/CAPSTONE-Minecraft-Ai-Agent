"""
Shared install-time files for the packaged bridge.

Used by:
  - Install_AI_Bridge / install_ai_bridge (bootstrapper.py) after extracting the bundle
  - packaging/build_bridge.ps1 and build_bridge_linux.sh after PyInstaller onedir build

Creates under ``<bridge_dir>/``:
  - ``Data/runtime_overrides.yaml`` — if missing (preserves existing on reinstall)
  - ``for_Minecraft_config/ai_agent_bridge_data_path.txt`` — always rewritten; copy this file
    to ``<Minecraft instance>/config/ai_agent_bridge_data_path.txt`` so the Forge mod UI matches
    this bridge's ``Data`` folder (see AiBotConfigScreen + HOT_RELOAD_AND_PATHS.md).
"""

from __future__ import annotations

import sys
from pathlib import Path

DEFAULT_RUNTIME_OVERLAY_TEXT = (
    "# Bridge runtime overrides\n"
    "# Edit this file to tune the AI bridge without opening Minecraft.\n"
    "# Reinstalling the bridge keeps this file when it already exists.\n"
    "# Restart the bridge after editing to guarantee the new values are loaded.\n"
    "control_mode: PLAYER\n"
    "policy:\n"
    "  dqn:\n"
    "    epsilon_start: 1.0\n"
    "  reward:\n"
    "    survival_reward: 0.001\n"
    "    step_penalty: -0.001\n"
    "    move_scale: 1.0\n"
    "    max_move_reward: 0.1\n"
    "    no_progress_penalty: -0.02\n"
    "    front_clear_bonus: 0.005\n"
    "    item_pickup_reward: 0.05\n"
    "    max_steps_per_episode: 2000\n"
    "    blocks: {}\n"
    "    mobs: {}\n"
)


def write_bridge_data_artifacts(bridge_dir: Path) -> dict[str, Path | bool]:
    """
    Ensure ``Data/runtime_overrides.yaml`` exists and write the Minecraft config drop-in
    with the absolute path to this install's ``Data`` folder.

    Returns paths plus ``created_overlay`` (True if a new ``runtime_overrides.yaml`` was written).
    """
    root = bridge_dir.resolve()
    data_dir = root / "Data"
    data_dir.mkdir(parents=True, exist_ok=True)

    overlay_path = data_dir / "runtime_overrides.yaml"
    created_overlay = False
    if not overlay_path.exists():
        overlay_path.write_text(DEFAULT_RUNTIME_OVERLAY_TEXT, encoding="utf-8")
        created_overlay = True

    drop_dir = root / "for_Minecraft_config"
    drop_dir.mkdir(parents=True, exist_ok=True)
    drop_file = drop_dir / "ai_agent_bridge_data_path.txt"
    data_abs = str(data_dir.resolve())
    drop_text = (
        "# Generated for this bridge install — copy this entire file to:\n"
        "#   <Your Minecraft instance folder>/config/ai_agent_bridge_data_path.txt\n"
        "# Create the config folder if it does not exist.\n"
        "# The mod uses the first non-blank line that does not start with '#' as the path.\n"
        "\n"
        f"{data_abs}\n"
        "\n"
    )
    drop_file.write_text(drop_text, encoding="utf-8")

    return {
        "bridge_dir": root,
        "data_dir": data_dir,
        "overlay_path": overlay_path,
        "minecraft_drop_in": drop_file,
        "created_overlay": created_overlay,
    }


def main() -> int:
    if len(sys.argv) < 2:
        print("Usage: bridge_install_artifacts.py <path-to-minecraft_ai_bridge-folder>", file=sys.stderr)
        return 2
    bridge = Path(sys.argv[1])
    if not bridge.is_dir():
        print(f"Not a directory: {bridge}", file=sys.stderr)
        return 2
    paths = write_bridge_data_artifacts(bridge)
    print(f"[bridge_install_artifacts] data_dir={paths['data_dir']}")
    print(f"[bridge_install_artifacts] overlay_path={paths['overlay_path']}")
    print(f"[bridge_install_artifacts] minecraft_drop_in={paths['minecraft_drop_in']}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
