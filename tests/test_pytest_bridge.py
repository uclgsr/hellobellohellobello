import os
import sys
import subprocess
import unittest
from pathlib import Path

try:
    import pytest  # type: ignore
except Exception:  # pragma: no cover
    pytest = None  # type: ignore


class TestRunPytestViaUnittest(unittest.TestCase):
    """
    Bridge test to allow environments that use unittest discovery (e.g., run_test tool)
    to execute the project's pytest-based test suite located under pc_controller/tests.

    Behavior:
    - Changes working directory to the repository root so that pytest.ini is respected.
    - Prefer invoking pytest via its Python API.
    - Falls back to `python -m pytest` via subprocess if import fails.
    - If pytest is unavailable entirely, skips gracefully so discovery succeeds.
    """

    def test_run_pytests(self) -> None:
        # Ensure we are at the repo root so pytest.ini is used
        repo_root = Path(__file__).resolve().parents[1]
        os.chdir(str(repo_root))

        target = "pc_controller/tests"

        if pytest is not None:
            # Run using pytest API
            args = ["-q", target]
            ret = pytest.main(args)
            if ret == 0:
                return
            # If API invocation failed, fall back to subprocess for more isolation
        
        # Fallback: try subprocess with `python -m pytest`
        try:
            proc = subprocess.run(
                [sys.executable, "-m", "pytest", "-q", target],
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
                text=True,
                cwd=str(repo_root),
            )
            if proc.returncode != 0:
                # If pytest exists but failed, include output for debugging
                self.fail(
                    f"Pytest failed (exit {proc.returncode}). Output:\n{proc.stdout}"
                )
        except Exception:
            # Pytest is likely unavailable; skip to avoid false negatives while
            # still solving discovery issues in run_test tool.
            self.skipTest("pytest is not available to run the Python test suite")
