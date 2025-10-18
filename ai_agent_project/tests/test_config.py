import os
import sys
import textwrap
import types
import importlib
from pathlib import Path
import pytest

from ai.src.utils import config as cfg_mod

def write_yaml(p: Path, content: str):
    p.write_text(textwrap.dedent(content).strip() + "\n", encoding="utf-8")


def test_deepmerge_nested_dicts():
    a = {"a": 1, "b": {"x": 1, "y": 2}}
    b = {"b": {"y": 99, "z": 3}, "c": 7}
    out = cfg_mod.DeepMerge(a, b)
    assert out == {"a": 1, "b": {"x": 1, "y": 99, "z": 3}, "c": 7}

def test_loadyaml_missing_file(tmp_path: Path):
    missing = tmp_path / "nope.yaml"
    out = cfg_mod.LoadYaml(missing)
    assert out == {}  # returns empty dict on missing


def test_loadconfig_happy_path(tmp_path: Path, monkeypatch: pytest.MonkeyPatch):
    conf_dir = tmp_path / "shared" / "config"
    conf_dir.mkdir(parents=True)

    # bridge.yaml — note schema_version "1" to match your current check
    write_yaml(conf_dir / "bridge.yaml", """
    server: { host: 0.0.0.0, port: 8765 }
    schema_version: "1"
    queues: { obs_max: 128, act_max: 64 }
    """)

    write_yaml(conf_dir / "runtime.yaml", """
    policy: { tick_hz: 12, budget_ms: 80 }
    """)

    write_yaml(conf_dir / "evaluation.yaml", """
    worlds: ["flat_clear"]
    trials_per_world: 3
    """)

    # defaults and dev can be empty or minimal
    write_yaml(conf_dir / "default.yaml", """
    bridge:
      logging: { level: INFO, json: true }
    """)
    write_yaml(conf_dir / "dev.yaml", """
    bridge:
      logging: { level: DEBUG }
    """)

    # Point module’s CONF_DIR at our temp config dir
    monkeypatch.setattr(cfg_mod, "CONF_DIR", conf_dir)
    monkeypatch.setenv("APP_ENV", "dev")

    cfg = cfg_mod.LoadConfig()
    # attribute-style access sanity checks
    assert cfg.bridge["server"]["port"] == 8765
    assert cfg.runtime["policy"]["tick_hz"] == 12
    assert cfg.evaluation["trials_per_world"] == 3
    # dev override merged on top of default
    assert cfg.bridge["logging"]["level"] == "DEBUG"
    # queues carried through
    assert cfg.bridge["queues"]["obs_max"] == 128


def test_loadconfig_schema_version_mismatch_raises(tmp_path: Path, monkeypatch: pytest.MonkeyPatch):
    conf_dir = tmp_path / "shared" / "config"
    conf_dir.mkdir(parents=True)

    # Intentionally set 1.1 to trigger your current check (expects "1")
    write_yaml(conf_dir / "bridge.yaml", """
    schema_version: "1.1"
    server: { host: 0.0.0.0, port: 8765 }
    """)

    write_yaml(conf_dir / "runtime.yaml", "{}")
    write_yaml(conf_dir / "evaluation.yaml", "{}")
    write_yaml(conf_dir / "default.yaml", "{}")

    monkeypatch.setattr(cfg_mod, "CONF_DIR", conf_dir)
    monkeypatch.delenv("APP_ENV", raising=False)

    with pytest.raises(ValueError) as ei:
        cfg_mod.LoadConfig()
    assert "Unsupported schema_version" in str(ei.value)


def test_config_attribute_access(tmp_path: Path, monkeypatch: pytest.MonkeyPatch):
    conf_dir = tmp_path / "shared" / "config"
    conf_dir.mkdir(parents=True)

    write_yaml(conf_dir / "bridge.yaml", """
    schema_version: "1"
    server: { host: 0.0.0.0, port: 8765 }
    """)
    write_yaml(conf_dir / "runtime.yaml", "{}")
    write_yaml(conf_dir / "evaluation.yaml", "{}")
    write_yaml(conf_dir / "default.yaml", "{}")

    monkeypatch.setattr(cfg_mod, "CONF_DIR", conf_dir)

    cfg = cfg_mod.LoadConfig()
    # attribute access uses __getattr__ = dict.get
    assert cfg.bridge is not None
    assert cfg.bridge["server"]["port"] == 8765
    # accessing missing key returns None (dict.get behavior)
    assert cfg.bridge.get("does_not_exist") is None