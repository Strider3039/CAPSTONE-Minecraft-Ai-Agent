from __future__ import annotations
from typing import Dict, Any

ALLOWED = {"noop","move_forward","move_back","turn_left","turn_right","jump","attack"}

# Ensure the action is valid and within allowed parameters
def ClampAction(msg: Dict[str, Any]) -> Dict[str, Any]:

    # Extract all the fields from the payload, with defaults
    p = (msg.get("payload") or {})
    look = p.get("look") or {}
    move = p.get("move") or {}
    jump = bool(p.get("jump", False))
    d_yaw   = float(look.get("dYaw", 0.0))
    d_pitch = float(look.get("dPitch", 0.0))
    fwd     = float(move.get("forward", 0.0))
    strafe  = float(move.get("strafe", 0.0))

    # Clamp to v0 schema limits
    d_yaw   = max(-45.0, min(45.0, d_yaw))
    d_pitch = max(-45.0, min(45.0, d_pitch))
    fwd     = max(-1.0,  min(1.0,  fwd))
    strafe  = max(-1.0,  min(1.0,  strafe))

    msg["payload"] = {
        "look": {"dYaw": d_yaw, "dPitch": d_pitch},
        "move": {"forward": fwd, "strafe": strafe},
        "jump": jump
    }

    return msg