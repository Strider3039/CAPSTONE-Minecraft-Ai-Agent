# tests/test_logging.py
import json
import logging as py_logging  # stdlib
import importlib.util
from pathlib import Path
import pytest

def find_project_root(start: Path) -> Path:
    p = start.resolve()
    for q in [p, *p.parents]:
        if (q / "utils" / "logging.py").exists():
            return q
    raise RuntimeError("Could not find project root (wanted utils/logging.py)")

def load_module_by_path(name: str, path: Path):
    spec = importlib.util.spec_from_file_location(name, str(path))
    if not spec or not spec.loader:
        raise RuntimeError(f"Cannot load module {name} from {path}")
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)  # type: ignore[attr-defined]
    return mod

@pytest.fixture(scope="module")
def app_logging():
    project_root = find_project_root(Path(__file__))
    return load_module_by_path("app_logging", project_root / "utils" / "logging.py")

def test_setup_logging_json_stdout(app_logging, tmp_path, capsys):
    metrics_path = tmp_path / "logs" / "bridge_metrics.ndjson"
    cfg = {
        "level": "DEBUG",
        "json": True,
        "metrics": {"sink": {"path": str(metrics_path)}},
    }

    app_logging.SetupLogging(cfg)
    logger = py_logging.getLogger("test.json")

    logger.info("hello world", extra={"foo": "bar"})
    captured = capsys.readouterr().out.strip().splitlines()
    assert captured, "No logs captured to stdout"

    rec = json.loads(captured[-1])
    assert rec["level"] == "INFO"
    assert rec["name"] == "test.json"
    assert rec["msg"] == "hello world"
    assert rec["foo"] == "bar"

    assert metrics_path.exists()
    with metrics_path.open("r", encoding="utf-8") as f:
        first_line = f.readline().strip()
        assert first_line
        json.loads(first_line)

def test_setup_logging_plain_stdout(app_logging, capsys):
    cfg = {"level": "INFO", "json": False}
    app_logging.SetupLogging(cfg)
    logger = py_logging.getLogger("plain.logger")

    logger.warning("warn message")
    out = capsys.readouterr().out
    assert "WARNING" in out
    assert "plain.logger" in out
    assert "warn message" in out

def test_log_level_effect_error_only(app_logging, capsys):
    cfg = {"level": "ERROR", "json": True}
    app_logging.SetupLogging(cfg)
    logger = py_logging.getLogger("lvl.test")

    logger.info("should not be emitted")
    out = capsys.readouterr().out.strip()
    assert out == ""

def test_write_metric_appends_ndjson(app_logging, tmp_path):
    p = tmp_path / "metrics" / "bridge_metrics.ndjson"
    app_logging.WriteMetric(p, {"queue_obs_high_watermark": 7})
    app_logging.WriteMetric(p, {"queue_obs_high_watermark": 9, "obs_dropped": 1})

    lines = p.read_text(encoding="utf-8").strip().splitlines()
    assert len(lines) == 2

    rec1 = json.loads(lines[0])
    rec2 = json.loads(lines[1])
    assert "timestamp" in rec1 and "timestamp" in rec2
    assert rec1["queue_obs_high_watermark"] == 7
    assert rec2["queue_obs_high_watermark"] == 9
    assert rec2["obs_dropped"] == 1
