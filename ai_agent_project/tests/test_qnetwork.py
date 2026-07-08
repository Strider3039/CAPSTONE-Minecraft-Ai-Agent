# -----------------------------------------------------------------------------
# QNetwork tests (placeholder)
#
# Intended for forward-pass / shape checks on the Q-network. No tests are defined
# yet — this file only imports the model and encoding helpers. Add tests here if
# you change QNetwork architecture and want fast feedback before running training.
# -----------------------------------------------------------------------------
import math
import unittest
import numpy as np
import os
import sys

sys.path.append(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from ai.policy.obs_encoding import EncodeObservation, OBS_DIM
from ai.rl.dqn.model import QNetwork
from ai.policy.action_space import ActionSchema

