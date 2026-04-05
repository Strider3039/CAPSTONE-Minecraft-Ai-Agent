"""Resolve filesystem roots for the bridge in development and PyInstaller bundles."""

from __future__ import annotations

import os
import pathlib
import sys


def is_frozen() -> bool:
    return bool(getattr(sys, "frozen", False))


def project_root() -> pathlib.Path:
    """Project root in development (`ai_agent_project/`)."""
    return pathlib.Path(__file__).resolve().parents[2]


def bundle_root() -> pathlib.Path:
    """Directory containing packaged read-only assets."""
    if is_frozen() and hasattr(sys, "_MEIPASS"):
        return pathlib.Path(sys._MEIPASS)
    return project_root()


def configs_dir() -> pathlib.Path:
    return bundle_root() / "configs"


def schemas_dir() -> pathlib.Path:
    return bundle_root() / "schemas"


def shared_dir() -> pathlib.Path:
    if is_frozen():
        return bundle_root() / "shared"
    return project_root() / "shared"


def exe_dir() -> pathlib.Path:
    """Directory containing the bridge executable (meaningful when frozen)."""
    return pathlib.Path(sys.executable).resolve().parent


def data_dir() -> pathlib.Path:
    """
    Writable tree for checkpoints, episode state, bridge logs/metrics, runtime overlay.
    In development: shared/Data under the project. When frozen: Data next to the .exe.

    Override (matches mod docs / CurseForge setups): set ``AI_AGENT_BRIDGE_DATA`` to the
    full path of the bridge ``Data`` folder so ``runtime_overrides.yaml`` aligns with the game.
    """
    override = os.getenv("AI_AGENT_BRIDGE_DATA", "").strip()
    if override:
        d = pathlib.Path(override).expanduser().resolve()
        d.mkdir(parents=True, exist_ok=True)
        return d
    if is_frozen():
        d = exe_dir() / "Data"
        d.mkdir(parents=True, exist_ok=True)
        return d
    d = shared_dir() / "Data"
    d.mkdir(parents=True, exist_ok=True)
    return d


def resource_search_roots() -> tuple[pathlib.Path, ...]:
    """
    Ordered roots for resolving relative checkpoint and other read-mostly assets.

    Frozen: prefer bundled read-only files under ``_MEIPASS``, then the writable
    ``Data`` directory (user seeds / ``save_initial_checkpoint`` output), then the
    exe folder for legacy layouts. **Do not** prefer ``exe_dir`` before ``bundle_root``:
    that produced bogus paths like ``.../bridge/policy/runs/...`` next to the exe.
    """
    dd = data_dir()
    if is_frozen():
        return (bundle_root(), dd, exe_dir())
    return (project_root(), dd)
