from __future__ import annotations
import os, pathlib, yaml
from typing import Any, Dict

ROOT = pathlib.Path(__file__).resolve().parents[3]  # ai/src/utils -> ai/
SHARED = ROOT.parent / "ai_agent_project" / "shared"
CONF_DIR = SHARED / "config" 

# Assign config 
class Config(dict):
    __getattr__ = dict.get      # cfg.server, cfg.logging

# Merge the two python-created yaml dictionaries
def DeepMerge(a: Dict[str, Any], b: Dict[str, Any]) -> Dict[str, Any]:
    out = dict(a)
    for i, j in b.items():
        if isinstance(out.get(i), dict) and isinstance(j, dict):
            out[i] = DeepMerge(out[i], j)
        else:
            out[i] = j
    return out

# Helper function for loading a yaml file.
def LoadYaml(path: pathlib.Path) -> Dict[str, Any]:
    if not path.exists():
        return {}
    with open(path, "r", encoding="utf-8") as f:
        return yaml.safe_load(f) or {}
    
def LoadConfig(env: str | None = None) -> Config:
    """Load and merge bridge/runtime/evaluation configs."""
    env = env or os.getenv("APP_ENV", "dev")

    bridge_cfg = LoadYaml(CONF_DIR / "bridge.yaml")
    runtime_cfg = LoadYaml(CONF_DIR / "runtime.yaml")
    eval_cfg = LoadYaml(CONF_DIR / "evaluation.yaml")

    # optional defaults/dev overrides (same logic as before)
    default_cfg = LoadYaml(CONF_DIR / "default.yaml")
    dev_cfg = LoadYaml(CONF_DIR / "dev.yaml") if env == "dev" else {}

    combined = {
        "bridge": bridge_cfg,
        "runtime": runtime_cfg,
        "evaluation": eval_cfg,
    }
    merged = DeepMerge(default_cfg, combined)
    if dev_cfg:
        merged = DeepMerge(merged, dev_cfg)

    # validate schema version if present
    schema_ver = merged.get("bridge", {}).get("schema_version")
    if schema_ver and schema_ver != "1":
        raise ValueError(f"Unsupported schema_version {schema_ver}, expected '1'")

    return Config(merged)