import json, logging, sys, time, pathlib
from typing import Any, Dict, Optional, Union

class JsonFormatter(logging.Formatter):
    _EXCLUDE = {
        "name","msg","args","levelname","levelno","pathname","filename","module",
        "exc_info","exc_text","stack_info","lineno","funcName","created","msecs",
        "relativeCreated","thread","threadName","processName","process"
    }

    def format(self, record: logging.LogRecord) -> str:
        payload = {
            "timestamp": time.time(),
            "level": record.levelname,
            "name": record.name,
            "msg": record.getMessage(),
        }
        if record.exc_info:
            payload["exc_info"] = self.formatException(record.exc_info)

        for k, v in record.__dict__.items():
            if k in self._EXCLUDE or k in payload:
                continue
            try:
                json.dumps(v)
            except TypeError:
                v = str(v)
            payload[k] = v

        return json.dumps(payload, separators=(",", ":"))

def SetupLogging(
    cfg: Optional[Dict[str, Any]] = None,
    *,
    logs_base: Optional[pathlib.Path] = None,
) -> logging.Logger:
    """
    Configure root logger from unified YAML:
      cfg == cfg.bridge["logging"] (dict)

    Relative ``file.path`` is resolved under ``logs_base`` (bridge ``Data/`` when set)
    so packaged exes do not write next to the process CWD.
    """
    cfg = cfg or {}
    level = str(cfg.get("level", "INFO")).upper()
    json_logs = bool(cfg.get("json", True))

    root = logging.getLogger()
    root.handlers.clear()
    root.setLevel(getattr(logging, level, logging.INFO))

    # stdout handler
    sh = logging.StreamHandler(sys.stdout)
    sh.setFormatter(
        JsonFormatter() if json_logs else
        logging.Formatter("%(asctime)s [%(levelname)s] %(name)s: %(message)s")
    )
    root.addHandler(sh)

    # optional log file sink (NOT metrics)
    file_cfg = cfg.get("file", {}) if isinstance(cfg.get("file", {}), dict) else {}
    if bool(file_cfg.get("enabled", False)):
        path_str = file_cfg.get("path")
        if path_str:
            path = pathlib.Path(path_str)
            if not path.is_absolute():
                base = logs_base
                if base is None:
                    try:
                        from ai.utils.runtime_paths import data_dir as _data_dir

                        base = _data_dir()
                    except Exception:
                        base = pathlib.Path.cwd()
                path = (base / path).resolve()
            path.parent.mkdir(parents=True, exist_ok=True)
            fh = logging.FileHandler(path, mode="a", encoding="utf-8")
            fh.setFormatter(JsonFormatter() if json_logs else sh.formatter)
            root.addHandler(fh)

    root.info("Logging initialized", extra={"level": level, "json": json_logs})
    return root

def WriteMetric(path: Union[str, pathlib.Path], data: Dict[str, Any]) -> None:
    """
    Append a single metric record as one JSON line (NDJSON).
    Safe to call from async tasks.
    """
    path = pathlib.Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    data["timestamp"] = time.time()
    line = json.dumps(data, separators=(",", ":")) + "\n"
    with path.open("a", encoding="utf-8") as f:
        f.write(line)