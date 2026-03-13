# Pytest configuration for ai_agent_project tests.
# Run from repo root with: .venv38\Scripts\python -m pytest ai_agent_project/tests/ -v
# (Requires: pip install pytest pytest-asyncio; for connection_lifecycle tests, torch as well.)

import pytest

def pytest_configure(config):
    config.addinivalue_line(
        "markers", "asyncio: mark test as an asyncio coroutine (pytest-asyncio)."
    )
