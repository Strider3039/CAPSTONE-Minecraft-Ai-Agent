# tests/test_config.py
import importlib.util
from pathlib import Path
import textwrap
import pytest

def find_project_root(start: Path) -> Path:
    p = start.resolve()
    for q in [p, *p.parents]:
        if (q / "utils" / "config.py").exists():
            return q
    raise RuntimeError("Could not find project root (wanted utils/config.py)")

def load_module_by_path(name: str, path: Path):
    spec = importlib.util.spec_from_file_location(name, str(path))
    if not spec or not spec.loader:
        raise RuntimeError(f"Cannot load module {name} from {path}")
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)  # type: ignore[attr-defined]
    return mod

@pytest.fixture(scope="module")
def cfg_mod():
    # This file lives at ai/src/app/tests/test_config.py
    project_root = find_project_root(Path(__file__))
    return load_module_by_path("app_config", project_root / "utils" / "config.py")

def write_yaml(p: Path, content: str):
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(textwrap.dedent(content).strip() + "\n", encoding="utf-8")

def test_deepmerge_nested_dicts(cfg_mod):
    a = {"a": 1, "b": {"x": 1, "y": 2}}
    b = {"b": {"y": 99, "z": 3}, "c": 7}
    out = cfg_mod.DeepMerge(a, b)
    assert out == {"a": 1, "b": {"x": 1, "y": 99, "z": 3}, "c": 7}

def test_loadyaml_missing_file(cfg_mod, tmp_path: Path):
    missing = tmp_path / "nope.yaml"
    out = cfg_mod.LoadYaml(missing)
    assert out == {}

def test_loadconfig_happy_path(cfg_mod, tmp_path: Path, monkeypatch: pytest.MonkeyPatch):
    conf_dir = tmp_path / "shared" / "config"
    (conf_dir).mkdir(parents=True, exist_ok=True)

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

    # Monkeypatch module globals used by LoadConfig()
    monkeypatch.setattr(cfg_mod, "CONF_DIR", conf_dir)
    monkeypatch.setenv("APP_ENV", "dev")

    cfg = cfg_mod.LoadConfig()
    assert cfg.bridge["server"]["port"] == 8765
    assert cfg.runtime["policy"]["tick_hz"] == 12
    assert cfg.evaluation["trials_per_world"] == 3
    assert cfg.bridge["logging"]["level"] == "DEBUG"
    assert cfg.bridge["queues"]["obs_max"] == 128

def test_loadconfig_schema_version_mismatch_raises(cfg_mod, tmp_path: Path, monkeypatch: pytest.MonkeyPatch):
    conf_dir = tmp_path / "shared" / "config"
    conf_dir.mkdir(parents=True)

    write_yaml(conf_dir / "bridge.yaml", """
    schema_version: "1.1"
    server: { host: 0.0.0.0, port: 8765 }
    """)
    write_yaml(conf_dir / "runtime.yaml", "{}")
    write_yaml(conf_dir / "evaluation.yaml", "{}")
    write_yaml(conf_dir / "default.yaml", "{}")

    monkeypatch.setattr(cfg_mod, "CONF_DIR", conf_dir)
    monkeypatch.delenv("APP_ENV", raising=False)

    with pytest.raises(ValueError):
        cfg_mod.LoadConfig()

def test_config_attribute_access(cfg_mod, tmp_path: Path, monkeypatch: pytest.MonkeyPatch):
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
    assert cfg.bridge is not None
    assert cfg.bridge["server"]["port"] == 8765
    assert cfg.bridge.get("does_not_exist") is None
