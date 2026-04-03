from __future__ import annotations

import os
import shutil
import subprocess
import sys
import traceback
from pathlib import Path


def _embedded_path(rel_name: str) -> Path:
    """Bundled asset path (PyInstaller: sys._MEIPASS; dev: next to this file)."""
    if getattr(sys, "frozen", False) and hasattr(sys, "_MEIPASS"):
        return Path(sys._MEIPASS) / rel_name
    return Path(__file__).resolve().parent / rel_name


def _default_install_parent() -> Path:
    """Parent directory that will contain ``bridge`` (OS-appropriate default)."""
    if sys.platform == "win32":
        base = os.environ.get("LOCALAPPDATA") or os.environ.get("APPDATA") or str(Path.home())
        return Path(base) / "MinecraftAIAgentBridge"
    if sys.platform == "darwin":
        return Path.home() / "Library" / "Application Support" / "MinecraftAIAgentBridge"
    xdg = os.environ.get("XDG_DATA_HOME", "").strip()
    if xdg:
        return Path(xdg) / "MinecraftAIAgentBridge"
    return Path.home() / ".local" / "share" / "MinecraftAIAgentBridge"


def _initial_dir_for_picker() -> str | None:
    h = str(Path.home())
    if sys.platform == "win32":
        cand = os.environ.get("LOCALAPPDATA") or os.environ.get("USERPROFILE") or h
        return cand if Path(cand).is_dir() else (h if Path(h).is_dir() else None)
    return h if Path(h).is_dir() else None


def _pick_folder_tkinter() -> Path | None:
    """Folder dialog via Tk (works on Windows, macOS, and Linux when Tcl/Tk is available)."""
    try:
        import tkinter as tk
        from tkinter import filedialog
    except Exception:
        return None

    root = tk.Tk()
    root.withdraw()
    try:
        root.attributes("-topmost", True)
    except Exception:
        pass
    init = _initial_dir_for_picker()
    try:
        sel = filedialog.askdirectory(
            title='Choose install location — a "bridge" folder will be created here',
            initialdir=init,
            mustexist=True,
        )
    finally:
        root.destroy()

    sel = (sel or "").strip()
    return Path(sel) if sel else None


def _pick_folder_zenity() -> Path | None:
    if sys.platform == "win32" or sys.platform == "darwin":
        return None
    if not shutil.which("zenity"):
        return None
    try:
        r = subprocess.run(
            [
                "zenity",
                "--file-selection",
                "--directory",
                "--title=Choose install location (bridge subfolder will be created)",
            ],
            capture_output=True,
            text=True,
            timeout=120,
        )
        if r.returncode != 0:
            return None
        line = (r.stdout or "").strip().splitlines()
        path = line[0].strip() if line else ""
        return Path(path) if path else None
    except (OSError, subprocess.TimeoutExpired):
        return None


def _pick_folder_kdialog() -> Path | None:
    if sys.platform == "win32" or sys.platform == "darwin":
        return None
    if not shutil.which("kdialog"):
        return None
    try:
        start = _initial_dir_for_picker() or str(Path.home())
        r = subprocess.run(
            ["kdialog", "--getexistingdirectory", start, "Choose install location"],
            capture_output=True,
            text=True,
            timeout=120,
        )
        if r.returncode != 0:
            return None
        path = (r.stdout or "").strip()
        return Path(path) if path else None
    except (OSError, subprocess.TimeoutExpired):
        return None


def _pick_install_folder() -> Path | None:
    """
    Native-style pickers: Tkinter everywhere first, then Zenity/KDialog on Linux if Tk is missing.
    """
    p = _pick_folder_tkinter()
    if p is not None:
        return p
    p = _pick_folder_zenity()
    if p is not None:
        return p
    return _pick_folder_kdialog()


def _fallback_type_install_path() -> Path | None:
    try:
        print(
            "[Installer] Enter the full path for installation "
            "(a 'bridge' subfolder will be created), or press Enter for default:"
        )
        raw = input().strip().strip('"')
        if raw:
            return Path(raw)
    except EOFError:
        pass
    return None


def _choose_install_parent() -> Path:
    """
    Parent folder that will contain ``bridge/`` (with the bridge executable).

    - ``AI_AGENT_BRIDGE_HOME``: use this path, no prompt.
    - ``AI_AGENT_SILENT_INSTALL=1``: default OS path, no prompt.
    - Else: graphical folder picker (Tk → Zenity → KDialog), then stdin, then default.
    """
    env_home = os.environ.get("AI_AGENT_BRIDGE_HOME", "").strip()
    if env_home:
        print(f"[Installer] Using AI_AGENT_BRIDGE_HOME -> {env_home}")
        return Path(env_home)

    silent = os.environ.get("AI_AGENT_SILENT_INSTALL", "").strip().lower() in ("1", "true", "yes")
    if silent:
        p = _default_install_parent()
        print(f"[Installer] Silent install -> {p}")
        return p

    picked = _pick_install_folder()
    if picked is not None:
        print(f"[Installer] You chose: {picked}")
        return picked

    typed = _fallback_type_install_path()
    if typed is not None:
        print(f"[Installer] Using typed path -> {typed}")
        return typed

    p = _default_install_parent()
    print(f"[Installer] Using default location -> {p}")
    return p


def _open_install_folder(path: Path) -> None:
    path = path.resolve()
    if not path.is_dir():
        return
    try:
        if sys.platform == "win32":
            os.startfile(path)
        elif sys.platform == "darwin":
            subprocess.Popen(["open", str(path)])
        else:
            subprocess.Popen(["xdg-open", str(path)])
    except Exception as e:
        print(f"[Installer] Could not open install folder: {e}")


def _sync_bridge_bundle(src: Path, dst: Path) -> None:
    if not src.is_dir():
        raise FileNotFoundError(f"Missing bridge folder in payload: {src}")

    dst.mkdir(parents=True, exist_ok=True)
    preserve = {"data", "logs"}

    for item in src.iterdir():
        name_lower = item.name.lower()
        if name_lower in preserve:
            target = dst / item.name
            if not target.exists():
                if item.is_dir():
                    shutil.copytree(item, target)
                else:
                    shutil.copy2(item, target)
            continue

        target = dst / item.name
        if target.exists():
            if target.is_dir():
                shutil.rmtree(target)
            else:
                target.unlink()
        if item.is_dir():
            shutil.copytree(item, target)
        else:
            shutil.copy2(item, target)


def main() -> int:
    payload_zip = _embedded_path("bridge_payload.zip")
    if not payload_zip.exists():
        print(f"[Installer] Missing embedded payload zip: {payload_zip}")
        return 2

    dest_root = _choose_install_parent()
    bridge_dir = dest_root / "bridge"
    bridge_exe = bridge_dir / "minecraft_ai_bridge.exe"

    print("[Installer] AI Bridge only (Forge mod is not installed by this program).")
    print(f"[Installer] Installing bridge to: {bridge_dir}")
    dest_root.mkdir(parents=True, exist_ok=True)

    tmp_extract = dest_root / "_extract_tmp"
    if tmp_extract.exists():
        shutil.rmtree(tmp_extract, ignore_errors=True)
    tmp_extract.mkdir(parents=True, exist_ok=True)

    print("[Installer] Extracting payload...")
    shutil.unpack_archive(str(payload_zip), str(tmp_extract))

    src_bridge = tmp_extract / "bridge"
    if not src_bridge.is_dir():
        print(
            f"[Installer] Invalid payload: expected a top-level 'bridge' folder under the zip, got: "
            f"{list(tmp_extract.iterdir())}"
        )
        shutil.rmtree(tmp_extract, ignore_errors=True)
        return 3

    _sync_bridge_bundle(src_bridge, bridge_dir)

    if tmp_extract.exists():
        shutil.rmtree(tmp_extract, ignore_errors=True)

    print("[Installer] Done.")
    print(f"[Installer] Run the bridge: {bridge_exe}")
    print(f"[Installer] Writable data (checkpoints, episode state, runtime_overrides.yaml): {bridge_dir / 'Data'}")
    print(f"[Installer] Logs / metrics: under {bridge_dir} (e.g. logs under Data as configured).")

    _open_install_folder(bridge_dir)

    if bridge_exe.exists():
        print("[Installer] Launching bridge...")
        subprocess.Popen([str(bridge_exe)], cwd=str(bridge_exe.parent))
        print("[Installer] Bridge launched.")
    else:
        print(f"[Installer] Note: bridge exe not found at: {bridge_exe}")
        print("[Installer] Start it manually from the install directory.")

    return 0


if __name__ == "__main__":
    _exit = 1
    try:
        _exit = main()
    except Exception as e:
        print(f"[Installer] Failed: {e}")
        traceback.print_exc()
        _exit = 1
    if getattr(sys, "frozen", False):
        try:
            input("\n[Installer] Press Enter to close...")
        except (EOFError, KeyboardInterrupt):
            pass
    raise SystemExit(_exit)
