import os, sys, textwrap
from pathlib import Path
import pytest

# Make project root importable (…/ai_agent_project)
sys.path.append(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from ai.src.utils import config as cfg_mod  # noqa: E402


def write_yaml(p: Path, content: str):
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(textwrap.dedent(content).strip() + "\n", encoding="utf-8")


def test_deepmerge_nested_dicts():
    a = {"a": 1, "b": {"x": 1, "y": 2}}
    b = {"b": {"y": 99, "z": 3}, "c": 7}
    out = cfg_mod.DeepMerge(a, b)
    assert out == {"a": 1, "b": {"x": 1, "y": 99, "z": 3}, "c": 7}


def test_loadyaml_missing_file(tmp_path: Path):
    out = cfg_mod.LoadYaml(tmp_path / "nope.yaml")
    assert out == {}  # safely returns empty dict


def test_loadconfig_happy_path(tmp_path: Path, monkeypatch: pytest.MonkeyPatch):
    conf_dir = tmp_path / "shared" / "config"

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
    write_yaml(conf_dir / "default.yaml", """
      bridge: { logging: { level: INFO, json: true } }
    """)
    write_yaml(conf_dir / "dev.yaml", """
      bridge: { logging: { level: DEBUG } }
    """)

    # Point loader at our temp dir + use dev env
    monkeypatch.setattr(cfg_mod, "CONF_DIR", conf_dir)
    monkeypatch.setenv("APP_ENV", "dev")

    cfg = cfg_mod.LoadConfig()
    assert cfg.bridge["server"]["port"] == 8765
    assert cfg.runtime["policy"]["tick_hz"] == 12
    assert cfg.evaluation["trials_per_world"] == 3
    # dev overrides default
    assert cfg.bridge["logging"]["level"] == "DEBUG"
    # merged values present
    assert cfg.bridge["queues"]["obs_max"] == 128


def test_loadconfig_schema_version_mismatch_raises(tmp_path: Path, monkeypatch: pytest.MonkeyPatch):
    conf_dir = tmp_path / "shared" / "config"
    write_yaml(conf_dir / "bridge.yaml", 'schema_version: "1.1"')
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
    write_yaml(conf_dir / "bridge.yaml", """
      schema_version: "1"
      server: { host: 0.0.0.0, port: 8765 }
    """)
    write_yaml(conf_dir / "runtime.yaml", "{}")
    write_yaml(conf_dir / "evaluation.yaml", "{}")
    write_yaml(conf_dir / "default.yaml", "{}")

    monkeypatch.setattr(cfg_mod, "CONF_DIR", conf_dir)
    cfg = cfg_mod.LoadConfig()

    # attribute-style access works (via __getattr__ = dict.get)
    assert cfg.bridge is not None
    assert cfg.bridge["server"]["port"] == 8765
    assert cfg.bridge.get("does_not_exist") is None
