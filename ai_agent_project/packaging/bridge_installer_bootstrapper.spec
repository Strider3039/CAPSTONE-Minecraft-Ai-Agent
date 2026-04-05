# -*- mode: python ; coding: utf-8 -*-
from pathlib import Path

block_cipher = None

# Compute payload path from the spec file location (PyInstaller may change CWD).
SPEC_DIR = Path(SPECPATH).resolve().parent  # .../ai_agent_project/packaging/installer
AI_ROOT = SPEC_DIR.parent.parent  # .../ai_agent_project
payload_zip = AI_ROOT / "dist" / "bridge_payload.zip"
HERE = SPEC_DIR

a = Analysis(
    [str((HERE / "bootstrapper.py").resolve())],
    pathex=[str(HERE.resolve())],
    binaries=[],
    datas=[(str(payload_zip), ".")],
    hiddenimports=[],
    hookspath=[],
    runtime_hooks=[],
    excludes=[],
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
    name="Install_AI_Bridge",
    debug=False,
    bootloader_ignore_signals=False,
    strip=False,
    upx=False,
    console=True,
    disable_windowed_traceback=False,
)

coll = COLLECT(exe, a.binaries, a.zipfiles, a.datas, strip=False, name="Install_AI_Bridge")

