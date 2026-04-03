# -*- mode: python ; coding: utf-8 -*-


a = Analysis(
    ['C:\\Projects\\School\\CAPSTONE-Minecraft-AI-Agent\\ai_agent_project\\packaging\\installer\\bootstrapper.py'],
    pathex=[],
    binaries=[],
    datas=[('C:\\Projects\\School\\CAPSTONE-Minecraft-AI-Agent\\ai_agent_project\\dist\\bridge_payload.zip', '.')],
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
    name='Install_AI_Bridge',
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
    name='Install_AI_Bridge',
)
