# -*- mode: python ; coding: utf-8 -*-
# Optional manual build (after dist/bridge_payload.zip exists). Prefer: packaging/build_bridge_release.ps1
import pathlib

ROOT = pathlib.Path(SPECPATH).resolve().parent
_payload = ROOT / "dist" / "bridge_payload.zip"
if not _payload.is_file():
    raise FileNotFoundError(f"Run packaging/build_bridge_release.ps1 (or stage {_payload}) first.")

a = Analysis(
    [str(ROOT / "packaging" / "installer" / "bootstrapper.py")],
    pathex=[str(ROOT)],
    binaries=[],
    datas=[(str(_payload), ".")],
    hiddenimports=[],
    hookspath=[],
    hooksconfig={},
    runtime_hooks=[],
    excludes=[],
    noarchive=False,
    optimize=0,
)
pyz = PYZ(a.pure)

exe = EXE(
    pyz,
    a.scripts,
    [],
    exclude_binaries=True,
    name="Install_AI_Bridge",
    debug=False,
    bootloader_ignore_signals=False,
    strip=False,
    upx=True,
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
    a.datas,
    strip=False,
    upx=True,
    upx_exclude=[],
    name="Install_AI_Bridge",
)
