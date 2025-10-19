import os, sys, json
from pathlib import Path
import logging as py_logging  # stdlib
import pytest

# Make project root importable (…/ai_agent_project)
sys.path.append(os.path.dirname(os.path.abspath(__file__)))
from ai.src.utils import logging as app_logging  # noqa: E402


def test_setup_logging_json_stdout(tmp_path: Path, capsys):
    metrics_path = tmp_path / "logs" / "bridge_metrics.ndjson"
    cfg = {
        "level": "DEBUG",
        "json": True,
        "metrics": {"sink": {"path": str(metrics_path)}},
    }

    app_logging.SetupLogging(cfg)
    logger = py_logging.getLogger("test.json")
    logger.info("hello world", extra={"foo": "bar"})

    out_lines = capsys.readouterr().out.strip().splitlines()
    assert out_lines, "No logs captured to stdout"

    rec = json.loads(out_lines[-1])
    assert rec["level"] == "INFO"
    assert rec["name"] == "test.json"
    assert rec["msg"] == "hello world"
    assert rec["foo"] == "bar"

    # Metrics sink file exists (handler added) and contains JSON
    assert metrics_path.exists()
    with metrics_path.open("r", encoding="utf-8") as f:
        first = f.readline().strip()
        assert first
        json.loads(first)


def test_setup_logging_plain_stdout(capsys):
    app_logging.SetupLogging({"level": "INFO", "json": False})
    logger = py_logging.getLogger("plain.logger")
    logger.warning("warn message")

    out = capsys.readouterr().out
    assert "WARNING" in out and "plain.logger" in out and "warn message" in out


def test_log_level_effect_error_only(capsys):
    app_logging.SetupLogging({"level": "ERROR", "json": True})
    logger = py_logging.getLogger("lvl.test")
    logger.info("should not be emitted")
    assert capsys.readouterr().out.strip() == ""


def test_write_metric_appends_ndjson(tmp_path: Path):
    p = tmp_path / "metrics" / "bridge_metrics.ndjson"
    app_logging.WriteMetric(p, {"queue_obs_high_watermark": 7})
    app_logging.WriteMetric(p, {"queue_obs_high_watermark": 9, "obs_dropped": 1})

    lines = p.read_text(encoding="utf-8").strip().splitlines()
    assert len(lines) == 2
    r1, r2 = map(json.loads, lines)
    assert "timestamp" in r1 and "timestamp" in r2
    assert r1["queue_obs_high_watermark"] == 7
    assert r2["queue_obs_high_watermark"] == 9 and r2["obs_dropped"] == 1
