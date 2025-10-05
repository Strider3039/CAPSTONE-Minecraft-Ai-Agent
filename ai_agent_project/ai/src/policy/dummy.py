from __future__ import annotations
from typing import Dict, Any
import random

def decide(obs: Dict[str, Any]) -> Dict[str, Any]:
    """
    Dummy policy with simple randomized movement.
    This simulates exploration and ensures movement/rotation variety.
    """

    # Randomly vary yaw/pitch to simulate head movement
    dYaw = random.uniform(-10.0, 10.0)     # look left/right
    dPitch = random.uniform(-3.0, 3.0)     # look up/down

    # Random movement direction
    forward = random.choice([-1.0, 0.0, 1.0])   # backward, idle, forward
    strafe = random.choice([-1.0, 0.0, 1.0])    # left, idle, right

    # 5% chance to jump each tick
    jump = random.random() < 0.05

    return {
        "look": {"dYaw": dYaw, "dPitch": dPitch},
        "move": {"forward": forward, "strafe": strafe},
        "jump": jump
    }
