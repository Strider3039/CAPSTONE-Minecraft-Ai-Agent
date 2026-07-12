# -----------------------------------------------------------------------------
# Config loading tests
#
# These test how YAML configs get loaded, merged, and validated, using the same
# path the bridge uses at startup. We fake config files in a temp dir so tests
# don't depend on your machine's APP_ENV or what's on disk.
#
# Catches broken merges (dev overlay wiping out sibling keys), missing files,
# wrong schema_version, and drift in default.yaml where expected runtime keys
# quietly disappear after someone edits the config.
# -----------------------------------------------------------------------------
import os, sys, textwrap
from pathlib import Path
import pytest

# Make project root importable (…/ai_agent_project)
sys.path.append(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from ai.utils import config as cfg_mod  # noqa: E402


def write_yaml(p: Path, content: str):
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(textwrap.dedent(content).strip() + "\n", encoding="utf-8")


def test_deepmerge_nested_dicts():
    """DeepMerge should override nested keys without wiping sibling keys in the same dict."""
    a = {"a": 1, "b": {"x": 1, "y": 2}}
    b = {"b": {"y": 99, "z": 3}, "c": 7}
    out = cfg_mod.DeepMerge(a, b)
    assert out == {"a": 1, "b": {"x": 1, "y": 99, "z": 3}, "c": 7}


def test_loadyaml_missing_file(tmp_path: Path):
    """A missing YAML file should return {} instead of raising."""
    out = cfg_mod.LoadYaml(tmp_path / "nope.yaml")
    assert out == {}  # missing file should just give you an empty dict


def test_loadconfig_happy_path(tmp_path: Path, monkeypatch: pytest.MonkeyPatch):
    """Make sure default.yaml loads, dev.yaml merges on top, and nothing important gets wiped."""
    conf_dir = tmp_path / "configs"

    write_yaml(conf_dir / "default.yaml", """
      schema_version: 2
      bridge:
        server: { host: 0.0.0.0, port: 8765 }
        queues: { obs_max: 128, act_max: 64 }
        logging: { level: INFO, json: true }
      runtime:
        policy: { tick_hz: 12, budget_ms: 80 }
      evaluation:
        worlds: ["flat_clear"]
        trials_per_world: 3
    """)
    write_yaml(conf_dir / "dev.yaml", """
      bridge: { logging: { level: DEBUG } }
    """)

    monkeypatch.setattr(cfg_mod, "CONF_DIR", conf_dir)
    monkeypatch.setenv("APP_ENV", "dev")

    cfg = cfg_mod.LoadConfig()
    assert cfg.bridge["server"]["port"] == 8765
    assert cfg.runtime["policy"]["tick_hz"] == 12
    assert cfg.evaluation["trials_per_world"] == 3
    assert cfg.bridge["logging"]["level"] == "DEBUG"
    assert cfg.bridge["queues"]["obs_max"] == 128


def test_loadconfig_schema_version_mismatch_raises(tmp_path: Path, monkeypatch: pytest.MonkeyPatch):
    """An old schema_version in default.yaml should fail fast instead of loading half-broken config."""
    conf_dir = tmp_path / "configs"
    write_yaml(conf_dir / "default.yaml", """
      schema_version: "1.1"
      bridge: {}
    """)

    monkeypatch.setattr(cfg_mod, "CONF_DIR", conf_dir)
    monkeypatch.delenv("APP_ENV", raising=False)

    with pytest.raises(ValueError) as ei:
        cfg_mod.LoadConfig()
    assert "Unsupported schema_version" in str(ei.value)


def test_config_attribute_access(tmp_path: Path, monkeypatch: pytest.MonkeyPatch):
    """You should be able to read config as cfg.bridge or cfg.bridge['server'] after loading."""
    conf_dir = tmp_path / "configs"
    write_yaml(conf_dir / "default.yaml", """
      schema_version: 2
      bridge:
        server: { host: 0.0.0.0, port: 8765 }
    """)

    monkeypatch.setattr(cfg_mod, "CONF_DIR", conf_dir)
    monkeypatch.delenv("APP_ENV", raising=False)
    cfg = cfg_mod.LoadConfig()

    assert cfg.bridge is not None
    assert cfg.bridge["server"]["port"] == 8765
    assert cfg.bridge.get("does_not_exist") is None


# Project config dir (ai_agent_project/configs) for 2.1 parameter tests
_CONFIG_DIR = Path(__file__).resolve().parent.parent / "configs"


def test_runtime_parameters_exposed(monkeypatch: pytest.MonkeyPatch):
    """Sanity-check that the real default.yaml still has all the runtime keys the bridge expects."""
    if not (_CONFIG_DIR / "default.yaml").exists():
        pytest.skip("default.yaml not found (run from repo with ai_agent_project/configs)")
    monkeypatch.setattr(cfg_mod, "CONF_DIR", _CONFIG_DIR)
    monkeypatch.delenv("APP_ENV", raising=False)
    cfg = cfg_mod.LoadConfig()
    runtime = cfg.get("runtime") or {}
    assert isinstance(runtime, dict), "runtime must be a dict"

    # observation settings
    obs = runtime.get("obs") or {}
    assert "rate_hz" in obs
    assert "entity_cap" in obs
    assert "include" in obs
    assert "quantization" in obs
    include = obs.get("include") or {}
    assert "pose" in include
    assert "entities_nearby" in include
    quant = obs.get("quantization") or {}
    assert "pos_decimals" in quant

    # policy loop and DQN knobs
    policy = runtime.get("policy") or {}
    assert "tick_hz" in policy
    assert "budget_ms" in policy
    assert "max_ray_dist" in policy
    assert "success_radius" in policy
    assert "stuck_speed_thresh" in policy
    assert "stuck_ticks" in policy
    assert "heading" in policy
    assert "action_rates" in policy
    assert "dqn" in policy
    assert "reward" in policy
    heading = policy.get("heading") or {}
    assert "max_look_deg" in heading
    assert "yaw_p_gain" in heading
    assert "stop_on_collision" in heading
    action_rates = policy.get("action_rates") or {}
    assert "look_hz" in action_rates
    assert "move_hz" in action_rates
    assert "jump_min_ms" in action_rates
    assert "interact_cooldown_ms" in action_rates

    # reward shaping
    reward = policy.get("reward") or {}
    assert "survival_reward" in reward
    assert "step_penalty" in reward
    assert "move_scale" in reward
    assert "max_steps_per_episode" in reward
    assert "blocks" in reward
    assert "mobs" in reward

    # DQN hyperparameters
    dqn = policy.get("dqn") or {}
    assert "epsilon_start" in dqn
    assert "epsilon_end" in dqn
    assert "gamma" in dqn
    assert "lr" in dqn
    assert "batch_size" in dqn

    # raycast config
    raycasts = runtime.get("raycasts") or {}
    assert "max_dist" in raycasts
    assert "count" in raycasts
    assert "fov_deg" in raycasts
    assert "front_clear_threshold" in raycasts
