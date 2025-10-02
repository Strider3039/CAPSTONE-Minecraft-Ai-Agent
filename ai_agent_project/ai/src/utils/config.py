from __future__ import annotations
import os, pathlib, yaml
from typing import Any, Dict

ROOT = pathlib.Path(__file__).resolve().parents[3]  # ai/src/utils -> ai/
SHARED = ROOT.parent / "ai_agent_project" / "shared"
DEF = SHARED / "config" / "default.yaml"
DEV = SHARED / "config" / "dev.yaml"

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

# Load the config information from the yaml files
def LoadConfig(env: str | None = None) -> Config:
    # Assign environment variable or default to dev
    env = env or os.getenv("APP_ENV", "dev")
    
    # Parse the yaml files into python dictionaries
    with open(DEF, "r", encoding="utf-8") as f:
        base = yaml.safe_load(f) or {}
    if env == "dev" and DEV.exists():
        with open(DEV, "r", encoding="utf-8") as f:
            base = DeepMerge(base, yaml.safe_load(f) or {})

    return Config(base)