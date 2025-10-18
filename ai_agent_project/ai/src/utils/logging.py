from __future__ import annotations
import json, logging, sys, time, pathlib
from typing import Any, Dict, Optional

# Custom logger that outputs JSON formatted logs
# utils/logging.py
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

        # Include exception info if present
        if record.exc_info:
            payload["exc_info"] = self.formatException(record.exc_info)

        # Merge extras added via `extra=...`
        for k, v in record.__dict__.items():
            if k in self._EXCLUDE:
                continue
            # avoid overwriting core keys
            if k in payload:
                continue
            # best-effort JSON-serializable filter
            try:
                json.dumps(v)
            except TypeError:
                v = str(v)
            payload[k] = v

        return json.dumps(payload)

def SetupLogging(cfg: Optional[Dict[str, Any]] = None, level: str = "INFO", json_logs: bool = True) -> logging.Logger:
    """
    Configure root logger.
    You can pass cfg.bridge.logging dict or rely on defaults.
    """
    if cfg and "level" in cfg:
        level = cfg["level"]
    if cfg and "json" in cfg:
        json_logs = bool(cfg["json"])

    root = logging.getLogger()
    root.handlers.clear()
    root.setLevel(getattr(logging, level.upper(), logging.INFO))

    # always stream to stdout
    h = logging.StreamHandler(sys.stdout)
    h.setFormatter(JsonFormatter() if json_logs else
                   logging.Formatter("%(asctime)s [%(levelname)s] %(name)s: %(message)s"))
    root.addHandler(h)

    # optional file sink (from cfg.bridge.metrics.sink)
    metrics_path = None
    try:
        metrics_path = cfg.get("metrics", {}).get("sink", {}).get("path") if cfg else None
    except Exception:
        pass
    if metrics_path:
        path = pathlib.Path(metrics_path)
        path.parent.mkdir(parents=True, exist_ok=True)
        fh = logging.FileHandler(path, mode="a", encoding="utf-8")
        fh.setFormatter(JsonFormatter())
        root.addHandler(fh)

    root.info("Logging initialized", extra={"level": level, "json": json_logs})
    return root

def WriteMetric(path: str | pathlib.Path, data: Dict[str, Any]) -> None:
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