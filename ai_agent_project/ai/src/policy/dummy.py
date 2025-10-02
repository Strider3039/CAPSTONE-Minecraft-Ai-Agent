from __future__ import annotations
from typing import Dict, Any

def decide(obs: Dict[str, Any]) -> Dict[str, Any]:
    # Simple policy: always move forward, no look change, no jump
    return {
        "look": {"dYaw": 0.0, "dPitch": 0.0},
        "move": {"forward": 1.0, "strafe": 0.0},
        "jump": False,
    }