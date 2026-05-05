# -*- mode: python ; coding: utf-8 -*-
from pathlib import Path

block_cipher = None

# Compute payload path from the spec file location (PyInstaller may change CWD).
SPEC_DIR = Path(SPECPATH).resolve().parent  # .../ai_agent_project/packaging
INSTALLER_DIR = (SPEC_DIR / "installer").resolve()
payload_zip = SPEC_DIR / "dist" / "bridge_payload.zip"
BOOTSTRAPPER = (INSTALLER_DIR / "bootstrapper.py").resolve()

a = Analysis(
    [str(BOOTSTRAPPER)],
    pathex=[str(SPEC_DIR.parent), str(INSTALLER_DIR)],
    binaries=[],
    datas=[(str(payload_zip), ".")],
    hiddenimports=["bridge_install_artifacts"],
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

