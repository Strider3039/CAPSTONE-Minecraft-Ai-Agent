# -*- mode: python ; coding: utf-8 -*-
"""PyInstaller spec: onedir bundle for the WebSocket bridge (see packaging/build_bridge.ps1)."""
import pathlib

from PyInstaller.utils.hooks import collect_data_files, collect_submodules

block_cipher = None


def _find_ai_project_root() -> pathlib.Path:
    start = pathlib.Path(SPECPATH).resolve().parent
    for d in (start, *start.parents):
        if (d / "bridge" / "server.py").is_file():
            return d
    raise RuntimeError(
        f"Cannot locate ai_agent_project (no bridge/server.py) starting from {start}"
    )


PROJECT = _find_ai_project_root()

_rth_torch = str(PROJECT / "packaging" / "pyi_rth_torch_dlls.py")
_jsonschema_extra = collect_data_files("jsonschema") + collect_data_files("rfc3987_syntax")

# Seed weights for online_dqn (see packaging/build_bridge.ps1 — save_initial_checkpoint into this tree)
_pack_ckpt_dir = PROJECT / ".bridge_packaging_staging" / "Data" / "checkpoints"
_checkpoint_datas = (
    [(str(_pack_ckpt_dir), "checkpoints")]
    if _pack_ckpt_dir.is_dir() and any(_pack_ckpt_dir.glob("*.pt"))
    else []
)

a = Analysis(
    [str(PROJECT / "bridge" / "server.py")],
    pathex=[str(PROJECT)],
    binaries=[],
    datas=[
        (str(PROJECT / "configs"), "configs"),
        (str(PROJECT / "schemas"), "schemas"),
    ]
    + list(_checkpoint_datas)
    + list(_jsonschema_extra),
    hiddenimports=list(collect_submodules("ai")) + list(collect_submodules("bridge"))
    + [
        "websockets",
        "websockets.legacy",
        "websockets.asyncio",
        "jsonschema",
        "yaml",
        "loguru",
        "numpy",
    ],
    hookspath=[],
    hooksconfig={},
    runtime_hooks=[_rth_torch],
    # Torch pulls optional UI stacks; multiple Qt bindings abort the build on Windows.
    excludes=[
        "PyQt5",
        "PyQt6",
        "PySide2",
        "PySide6",
        "tkinter",
        "_tkinter",
    ],
    win_no_prefer_redirects=False,
    win_private_assemblies=False,
    cipher=block_cipher,
    noarchive=False,
)

pyz = PYZ(a.pure, a.zipped_data, cipher=block_cipher)

exe = EXE(
    pyz,
    a.scripts,
    [],
    exclude_binaries=True,
    name="minecraft_ai_bridge",
    debug=False,
    bootloader_ignore_signals=False,
    strip=False,
    upx=False,
    console=True,
    disable_windowed_traceback=False,
    argv_emulation=False,
    target_arch=None,
    codesign_identity=None,
    entitlements_file=None,
)

coll = COLLECT(
    exe,
    a.binaries,
    a.zipfiles,
    a.datas,
    name="minecraft_ai_bridge",
)
