from __future__ import annotations
from typing import Dict, Any

ALLOWED = {"noop","move_forward","move_back","turn_left","turn_right","jump","attack"}

# Ensure the action is valid and within allowed parameters
def ClampAction(msg: Dict[str, Any]) -> Dict[str, Any]:
    payload = msg.get("payload", {}) or {}
    kind = payload.get("kind", "noop")
    args = payload.get("args", {}) or {}

    if kind not in ALLOWED:
        kind = "noop"
        args = {}

    if kind in {"move_forward", "move_back"}:
        dur = args.get("seconds", 0.2)
        args["seconds"] = max(0.0, min(.5, float(dur)))


