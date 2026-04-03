"""Resolve filesystem roots for the bridge in development and PyInstaller bundles."""

from __future__ import annotations

import pathlib
import sys


def is_frozen() -> bool:
    return bool(getattr(sys, "frozen", False))


def bundle_root() -> pathlib.Path:
    """Directory containing packaged read-only assets (the `ai` package tree and bundled `shared/`)."""
    if is_frozen() and hasattr(sys, "_MEIPASS"):
        return pathlib.Path(sys._MEIPASS)
    return pathlib.Path(__file__).resolve().parents[3]


def shared_dir() -> pathlib.Path:
    return bundle_root() / "shared"


def exe_dir() -> pathlib.Path:
    """Directory containing the bridge executable (meaningful when frozen)."""
    return pathlib.Path(sys.executable).resolve().parent


def data_dir() -> pathlib.Path:
    """
    Writable tree for checkpoints, episode state, bridge logs/metrics, runtime overlay.
    In development: shared/Data under the project. When frozen: Data next to the .exe.
    """
    if is_frozen():
        d = exe_dir() / "Data"
        d.mkdir(parents=True, exist_ok=True)
        return d
    d = shared_dir() / "Data"
    d.mkdir(parents=True, exist_ok=True)
    return d


def resource_search_roots() -> tuple[pathlib.Path, ...]:
    """Ordered roots for resolving relative checkpoint and other optional resource paths."""
    if is_frozen():
        return (exe_dir(), bundle_root())
    return (bundle_root(),)
