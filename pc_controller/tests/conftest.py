"""Pytest configuration for Python tests.

Ensures the repository root is present on sys.path so that imports like
`pc_controller.src...` resolve regardless of invocation location.
"""
import sys
from pathlib import Path

# pc_controller/tests/conftest.py -> repo root is two levels up from pc_controller
REPO_ROOT = Path(__file__).resolve().parents[2]
if str(REPO_ROOT) not in sys.path:
    sys.path.insert(0, str(REPO_ROOT))
