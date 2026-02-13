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
    env = env or os.getenv("APP_ENV", "prod")

    base = LoadYaml(CONF_DIR / "default.yaml")
    if not base:
        raise FileNotFoundError(f"Missing default.yaml in {CONF_DIR}")

    # Support dev/prod/evaluation overrides by name
    env_path = CONF_DIR / f"{env}.yaml"
    env_cfg = LoadYaml(env_path)

    merged = DeepMerge(base, env_cfg) if env_cfg else base

    # Optional: load evaluation only when env == "evaluation" OR keep separate entirely
    # (I recommend separate runner; but if you want it merged, do this:)
    if env == "evaluation":
        eval_cfg = LoadYaml(CONF_DIR / "evaluation.yaml")
        if eval_cfg:
            # either merge under evaluation: or require evaluation.yaml already has evaluation:
            if "evaluation" in eval_cfg:
                merged = DeepMerge(merged, eval_cfg)
            else:
                merged = DeepMerge(merged, {"evaluation": eval_cfg})

    # Validate schema version (root-level in unified)
    schema_ver = merged.get("schema_version", None)
    if schema_ver not in (None, 2):
        raise ValueError(f"Unsupported schema_version {schema_ver}, expected 2")

    return Config(merged)