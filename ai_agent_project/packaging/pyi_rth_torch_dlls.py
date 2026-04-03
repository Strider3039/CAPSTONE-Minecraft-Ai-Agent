"""PyInstaller runtime hook: register torch/lib on DLL search path (Windows)."""
import os
import pathlib
import sys

if getattr(sys, "frozen", False) and hasattr(sys, "_MEIPASS") and sys.platform == "win32":
    # Reduce OpenMP duplicate-runtime failures during c10.dll init (WinError 1114).
    # Do not set MKL_THREADING_LAYER=GNU: Windows CPU wheels use Intel OpenMP; GNU confuses MKL init.
    os.environ.setdefault("KMP_DUPLICATE_LIB_OK", "TRUE")
    os.environ.setdefault("OMP_NUM_THREADS", "1")

    import ctypes
    from ctypes import wintypes

    base = pathlib.Path(sys._MEIPASS)

    _LOAD_LIBRARY_SEARCH_DEFAULT_DIRS = 0x00001000
    _LOAD_LIBRARY_SEARCH_DLL_LOAD_DIR = 0x00000100
    _LOAD_LIB_FLAGS = _LOAD_LIBRARY_SEARCH_DEFAULT_DIRS | _LOAD_LIBRARY_SEARCH_DLL_LOAD_DIR

    def _add_dir(p: pathlib.Path) -> None:
        if not p.is_dir():
            return
        try:
            os.add_dll_directory(str(p))
        except (OSError, AttributeError):
            pass

    def _win_load_first(dll_path: pathlib.Path) -> None:
        """Match torch's LoadLibraryEx strategy so dependent DLLs resolve from the same folder."""
        if not dll_path.is_file():
            return
        try:
            k = ctypes.WinDLL("kernel32", use_last_error=True)
            k.LoadLibraryExW.argtypes = [
                wintypes.LPCWSTR,
                wintypes.HANDLE,
                wintypes.DWORD,
            ]
            k.LoadLibraryExW.restype = wintypes.HMODULE
            if k.LoadLibraryExW(str(dll_path), None, _LOAD_LIB_FLAGS):
                return
        except OSError:
            pass
        try:
            ctypes.CDLL(str(dll_path))
        except OSError:
            pass

    def _path_without_bad_native(path_env: str) -> str:
        """Drop CUDA/NVIDIA and common Intel oneAPI entries that can preload a second OpenMP/MKL."""
        bad = (
            "cuda",
            "nvidia",
            "nvidia corporation",
            "cudnn",
            "\\intel\\oneapi",
            "\\intel\\compilers",
        )
        parts: list[str] = []
        for raw in path_env.split(os.pathsep):
            p = raw.strip()
            if not p:
                continue
            low = p.lower()
            if any(b in low for b in bad):
                continue
            parts.append(p)
        return os.pathsep.join(parts)

    # _internal root often holds MSVC + other native deps collected by PyInstaller.
    _add_dir(base)
    _sys_path = _path_without_bad_native(os.environ.get("PATH", ""))
    try:
        os.environ["PATH"] = str(base) + os.pathsep + _sys_path
    except OSError:
        pass

    for _crt in ("vcruntime140.dll", "vcruntime140_1.dll", "msvcp140.dll"):
        _p = base / _crt
        if _p.is_file():
            _win_load_first(_p)

    torch_lib = base / "torch" / "lib"
    torch_bin = base / "torch" / "bin"
    extras: list[pathlib.Path] = []
    for sub in (torch_lib, torch_bin):
        if sub.is_dir():
            extras.append(sub)
            _add_dir(sub)

    # Same order torch relies on: OpenMP and global deps must be in-process before c10 (glob loads c10 early).
    for _name in (
        "libiomp5md.dll",
        "libiompstubs5md.dll",
        "libomp140.x86_64.dll",
        "torch_global_deps.dll",
    ):
        _win_load_first(torch_lib / _name)

    if extras:
        prefix = os.pathsep.join(str(p) for p in extras)
        os.environ["PATH"] = prefix + os.pathsep + os.environ.get("PATH", "")
