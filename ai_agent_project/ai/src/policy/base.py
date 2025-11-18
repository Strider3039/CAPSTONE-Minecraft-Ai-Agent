from __future__ import annotations
from abc import ABC, abstractmethod
from typing import Any, Dict, TypedDict


class ObservationMsg(TypedDict, total=False):
    """
    Minimal structural hint for a Minecraft observation message.
    """
    proto: str
    kind: str
    seq: int
    payload: Dict[str, Any]


class ActionMsg(TypedDict, total=False):
    """
    Minimal structural hint for a Minecraft action message.
    """
    proto: str
    kind: str
    seq: int
    timestamp: float
    action_id: str
    payload: Dict[str, Any]


class Policy(ABC):
    """
    Base class for all policies (scripted or RL).
    """

    @abstractmethod
    def act(self, obs_msg: ObservationMsg) -> ActionMsg:
        """
        Compute and return an action given a Minecraft observation.
        Must return a dictionary matching action.schema.json.
        """
        raise NotImplementedError
