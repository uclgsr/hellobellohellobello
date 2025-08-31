"""Pytest configuration for Python tests.

Ensures the repository root is present on sys.path so that imports like
`pc_controller.src...` resolve regardless of invocation location.

Also emits explicit per-test start and result markers to improve CI/console
visibility of which test is running and its outcome.
"""

import os
import sys
from datetime import datetime
from pathlib import Path

import pytest

# pc_controller/tests/conftest.py -> repo root is two levels up from pc_controller
REPO_ROOT = Path(__file__).resolve().parents[2]
if str(REPO_ROOT) not in sys.path:
    sys.path.insert(0, str(REPO_ROOT))

# Reduce noisy OpenCV WARN logs (e.g., imread of missing files) to avoid cluttering CI output.
# Set env before importing cv2 so the native C++ logger honors it.

os.environ.setdefault("OPENCV_LOG_LEVEL", "ERROR")
# Set Qt platform to offscreen for headless testing
os.environ.setdefault('QT_QPA_PLATFORM', 'offscreen')
# Disable OpenGL to prevent EGL issues in CI environments
os.environ.setdefault("QT_QUICK_BACKEND", "software")
os.environ.setdefault("LIBGL_ALWAYS_SOFTWARE", "1")
os.environ.setdefault("QT_LOGGING_RULES", "*=false")

try:
    import cv2  # type: ignore
    # Prefer new logging API
    try:
        if hasattr(cv2, "utils") and hasattr(cv2.utils, "logging"):
            cv2.utils.logging.setLogLevel(cv2.utils.logging.LOG_LEVEL_ERROR)
    except Exception:
        pass
    # Fallback to legacy API if present
    try:
        if hasattr(cv2, "setLogLevel") and hasattr(cv2, "LOG_LEVEL_ERROR"):
            cv2.setLogLevel(cv2.LOG_LEVEL_ERROR)
        if hasattr(cv2, "LOG_LEVEL_SILENT"):
            # Some builds need SILENT to suppress native WARN messages.
            cv2.setLogLevel(cv2.LOG_LEVEL_SILENT)
    except Exception:
        pass
except Exception:
    # If OpenCV isn't available or the logging API is missing, ignore silently.
    pass


def _ts() -> str:
    """Return a human-readable local timestamp for log lines."""
    return datetime.now().strftime("%Y-%m-%d %H:%M:%S")


def pytest_runtest_logstart(nodeid: str, location: tuple[str, int, str] | None) -> None:
    """Log the start of each test node.

    Parameters
    ----------
    nodeid: str
        The pytest node id of the test (e.g., path::TestClass::test_name)
    location: Optional[Tuple[str, int, str]]
        File path, line, and test name as provided by pytest.
    """
    print(f"[TEST_START] {_ts()} {nodeid}")


def pytest_runtest_logreport(report: pytest.TestReport) -> None:
    """Log the final result of each test's call phase.

    Parameters
    ----------
    report: pytest.TestReport
        The test report emitted by pytest for each phase (setup/call/teardown).
    """
    if report.when == "call":
        outcome = report.outcome.upper()
        duration = getattr(report, "duration", 0.0)
        print(f"[TEST_RESULT] {_ts()} {report.nodeid} -> {outcome} ({duration:.3f}s)")


def pytest_collection_modifyitems(config, items):
    """Add timeout markers and handle GUI tests appropriately."""
    for item in items:
        # Add timeout marker to tests that don't have one
        if not item.get_closest_marker("timeout"):
            item.add_marker(pytest.mark.timeout(30))

        # Skip GUI tests if PyQt6 not available in headless environment
        if any(keyword in item.name.lower() for keyword in ["gui", "preview", "ui_smoke"]):
            # Check if we're in a CI environment where GUI isn't available
            is_ci = os.environ.get("CI") or os.environ.get("GITHUB_ACTIONS")

            try:
                # Try to import PyQt6 and test basic functionality
                import PyQt6.QtCore
                import PyQt6.QtWidgets

                # Test if we can use Qt in current environment
                app = PyQt6.QtWidgets.QApplication.instance()
                if app is None:
                    test_app = PyQt6.QtWidgets.QApplication([])
                    test_app.setAttribute(
                        test_app.ApplicationAttribute.AA_DontShowIconsInMenus, True
                    )
                    # Keep app alive for other tests

            except Exception as e:
                if is_ci:
                    # In CI, mark as expected skip
                    item.add_marker(pytest.mark.skip(reason=f"GUI testing skipped in CI: {e}"))
                else:
                    # In dev environment, this might be a real issue
                    item.add_marker(pytest.mark.skip(reason=f"GUI testing unavailable: {e}"))
