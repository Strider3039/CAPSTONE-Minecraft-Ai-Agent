from typing import Any, Dict, Tuple
import math

import numpy as np

OBS_DIM = 47 

MAX_RAYS = 16

def SafeGetPayload(msg: Dict[str, Any]) -> Dict[str, Any]:
    """
    Helper to extract the payload from the top-level observation message.
    Assumes the message has already been validated against the schema,
    but is defensive against missing keys.
    """

    payload = msg.get("payload")
    if payload is None or not isinstance(payload, dict):
        raise ValueError("Observation message missing 'payload' field or it is not a dict.")
    return payload

def AngleSinCos(angle_deg: float) -> Tuple[float, float]:
    """
    Convert an angle in degrees to its sine and cosine components.
    """

    rad = math.radians(angle_deg)
    return math.sin(rad), math.cos(rad)

def EncodeObservation(obsMsg: Dict[str, Any], maxRayDist: float) -> np.ndarray:
    """
    Convert a validated Observation JSON dict into a fixed-length (OBS_DIM,) float32 vector.

    Layout (indices):
      0-2   : pose (x, y, z)
      3-6   : sin/cos yaw, sin/cos pitch
      7-38  : rays (16 rays * [hit, normalized_dist])
      39    : front_clear (bool -> 0/1)
      40-42 : collision flags (is_grounded, is_colliding, no_progress)
      43    : world.time_of_day / 24000.0
      44-46 : weather one-hot: [clear, rain, thunder]
    """

    if maxRayDist <= 0:
        raise ValueError("maxRayDist must be positive.")
    
    vec = np.zeros(OBS_DIM, dtype=np.float32)
    payload = SafeGetPayload(obsMsg)

    # Pose
    pose = payload.get("pose", {})
    x = float(pose.get("x", 0.0))
    y = float(pose.get("y", 0.0))
    z = float(pose.get("z", 0.0))
    yaw = float(pose.get("yaw", 0.0))
    pitch = float(pose.get("pitch", 0.0))

    vec[0] = x
    vec[1] = y
    vec[2] = z

    yawSin, yawCos = AngleSinCos(yaw)
    pitchSin, pitchCos = AngleSinCos(pitch)

    vec[3] = yawSin
    vec[4] = yawCos
    vec[5] = pitchSin
    vec[6] = pitchCos

    # Rays
    rays = payload.get("rays", [])

    if not isinstance(rays, list):
        raise ValueError("'rays' field must be a list.")

    for i in range(min(len(rays), MAX_RAYS)):
        ray = rays[i] or {}
        hit = bool(ray.get("hit", False))

        # Observation schema uses 'dist'; accept legacy 'distance' as fallback.
        if "dist" in ray:
            dist = float(ray.get("dist", 0.0))
        else:
            dist = float(ray.get("distance", 0.0))

        # Clamp distance
        dist_clamped = min(max(dist, 0.0), maxRayDist)
        dist_norm = dist_clamped / maxRayDist

        baseIdx = 7 + i * 2
        vec[baseIdx] = 1.0 if hit else 0.0
        vec[baseIdx + 1] = dist_norm

    # Front clear
    front_clear = bool(payload.get("front_clear", False))
    vec[39] = 1.0 if front_clear else 0.0

    # Collision flags
    collision = payload.get("collision", {})
    is_grounded = bool(collision.get("is_grounded", False))
    is_colliding = bool(collision.get("is_colliding", False))
    no_progress = bool(collision.get("no_progress", False))

    vec[40] = 1.0 if is_grounded else 0.0
    vec[41] = 1.0 if is_colliding else 0.0
    vec[42] = 1.0 if no_progress else 0.0

    # World info
    world = payload.get("world", {})
    time_of_day = float(world.get("time_of_day", 0.0))

    # Clamp and normalize time_of_day
    time_norm = max(0.0, min(time_of_day, 24000.0)) / 24000.0
    vec[43] = time_norm

    weather = world.get("weather", "clear")
    vec[44] = 1.0 if weather == "clear" else 0.0
    vec[45] = 1.0 if weather == "rain" else 0.0
    vec[46] = 1.0 if weather == "thunder" else 0.0

    return vec