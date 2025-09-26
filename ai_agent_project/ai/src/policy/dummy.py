from __future__ import annotations
from typing import Dict, Any
import itertools

# Create a dummy policy that cycles through predefined actions
cyle = itertools.cycle([
    {"kind": "move_forward", "args": {"seconds": .2}},
    {"kind": "turn_right", "args": {"deg": 15}},
    {"kind": "move_forward", "args": {"seconds": .2}},
    {"kind": "turn_left", "args": {"deg": 15}},
])

# Example of using observation data
# If health is low, stop moving
def decide(observation: Dict[str, Any]) -> Dict[str, Any]:

    health = observation.get("payload", {}).get("health", 1.0)
    if isinstance(health, (int, float)) and health < .2:
        return {"kind": "noop", "args": {}}
    return next(cyle)