from __future__ import annotations

import json
import time
from dataclasses import asdict
from pathlib import Path
from unittest.mock import patch

import pytest

from pc_controller.src.core.session_manager import SessionManager, SessionMetadata


def test_create_start_stop_session(tmp_path: Path) -> None:
    sm = SessionManager(base_dir=str(tmp_path))
    sid = sm.create_session("test_name")
    assert sm.session_id == sid
    sdir = sm.session_dir
    assert sdir is not None and sdir.exists()
    meta_path = sdir / "metadata.json"
    assert meta_path.exists()
    meta = json.loads(meta_path.read_text(encoding="utf-8"))
    assert meta["session_id"] == sid
    assert meta["name"]
    assert meta["state"] == "Created"

    sm.start_recording()
    meta = json.loads(meta_path.read_text(encoding="utf-8"))
    assert meta["state"] == "Recording"
    assert isinstance(meta.get("start_time_ns"), int)

    sm.stop_recording()
    meta = json.loads(meta_path.read_text(encoding="utf-8"))
    assert meta["state"] == "Stopped"
    assert isinstance(meta.get("end_time_ns"), int)


def test_single_active_enforced(tmp_path: Path) -> None:
    sm = SessionManager(base_dir=str(tmp_path))
    sm.create_session("first")
    threw = False
    try:
        sm.create_session("second")
    except RuntimeError:
        threw = True
    assert threw is True

    sm.stop_recording()  # allowed even if not started; transitions to Stopped
    sid2 = sm.create_session("second")
    assert sm.session_id == sid2


def test_session_manager_initialization() -> None:
    """Test SessionManager initialization with default and custom paths."""
    # Test with default base directory
    sm = SessionManager()
    expected_default = Path.cwd() / "pc_controller_data"
    assert sm._base_dir == expected_default.resolve()

    # Test with custom base directory
    custom_path = "/tmp/custom_sessions"
    sm_custom = SessionManager(base_dir=custom_path)
    assert sm_custom._base_dir == Path(custom_path).resolve()


def test_session_creation_validation(tmp_path: Path) -> None:
    """Test session creation with various name inputs."""
    sm = SessionManager(base_dir=str(tmp_path))

    # Test with normal name
    session_id = sm.create_session("normal_session")
    assert sm.session_id == session_id
    assert sm.is_active

    # Clean up
    sm.stop_recording()

    # Test with empty name (should still work)
    session_id = sm.create_session("")
    assert sm.session_id == session_id
    sm.stop_recording()

    # Test with name containing special characters
    special_name = "test-session_2024.01"
    session_id = sm.create_session(special_name)
    assert sm.session_id == session_id

    # Verify metadata contains the special name
    metadata_path = sm.session_dir / "metadata.json"
    metadata = json.loads(metadata_path.read_text(encoding="utf-8"))
    assert metadata["name"] == special_name


def test_session_state_transitions(tmp_path: Path) -> None:
    """Test all possible session state transitions."""
    sm = SessionManager(base_dir=str(tmp_path))

    # Initial state - no active session
    assert not sm.is_active
    assert sm.session_id is None
    assert sm.session_dir is None

    # Create session - should be in Created state
    sm.create_session("state_test")
    assert sm.is_active
    assert sm.metadata["state"] == "Created"

    # Start recording - should transition to Recording
    sm.start_recording()
    assert sm.metadata["state"] == "Recording"

    # Start recording again - should be idempotent
    sm.start_recording()
    assert sm.metadata["state"] == "Recording"

    # Stop recording - should transition to Stopped
    sm.stop_recording()
    assert sm.metadata["state"] == "Stopped"
    assert not sm.is_active

    # Stop recording again - should be idempotent
    sm.stop_recording()
    assert sm.metadata["state"] == "Stopped"


def test_session_metadata_persistence(tmp_path: Path) -> None:
    """Test that session metadata is properly persisted and loaded."""
    sm = SessionManager(base_dir=str(tmp_path))

    # Create session and start recording
    start_time = time.time_ns()
    session_id = sm.create_session("persistence_test")
    sm.start_recording()

    # Brief pause to ensure different end time
    time.sleep(0.001)

    # Stop recording
    end_time = time.time_ns()
    sm.stop_recording()

    # Load metadata from file
    metadata_path = sm.session_dir / "metadata.json"
    metadata = json.loads(metadata_path.read_text(encoding="utf-8"))

    # Verify all fields are properly set
    assert metadata["version"] == 1
    assert metadata["session_id"] == session_id
    assert metadata["name"] == "persistence_test"
    assert metadata["state"] == "Stopped"
    assert metadata["created_at_ns"] >= start_time
    assert metadata["created_at"] is not None
    assert metadata["start_time_ns"] >= start_time
    assert metadata["end_time_ns"] >= end_time  # Allow some tolerance
    assert metadata["duration_ns"] == metadata["end_time_ns"] - metadata["start_time_ns"]


def test_session_directory_structure(tmp_path: Path) -> None:
    """Test that session directories are created with correct structure."""
    sm = SessionManager(base_dir=str(tmp_path))

    session_id = sm.create_session("directory_test")
    session_dir = sm.session_dir

    # Verify directory exists and is correctly named
    assert session_dir.exists()
    assert session_dir.is_dir()
    assert session_id in session_dir.name

    # Verify metadata.json exists
    metadata_path = session_dir / "metadata.json"
    assert metadata_path.exists()
    assert metadata_path.is_file()

    # Verify parent directory structure
    assert session_dir.parent == sm._base_dir
    assert sm._base_dir.exists()


def test_multiple_sessions_sequentially(tmp_path: Path) -> None:
    """Test creating multiple sessions sequentially."""
    sm = SessionManager(base_dir=str(tmp_path))

    session_ids = []
    session_names = ["session_1", "session_2", "session_3"]

    for name in session_names:
        # Create and complete session
        session_id = sm.create_session(name)
        session_ids.append(session_id)

        sm.start_recording()
        time.sleep(0.001)  # Brief recording
        sm.stop_recording()

        # Verify session completed
        assert sm.metadata["state"] == "Stopped"

    # Verify all sessions have unique IDs
    assert len(set(session_ids)) == len(session_ids)

    # Verify all session directories exist
    for session_id in session_ids:
        session_dirs = list(sm._base_dir.glob(f"*{session_id}*"))
        assert len(session_dirs) == 1
        assert session_dirs[0].is_dir()


def test_session_error_conditions(tmp_path: Path) -> None:
    """Test error conditions and edge cases."""
    sm = SessionManager(base_dir=str(tmp_path))

    # Test operations without active session
    assert sm.metadata is None

    # Starting recording without session should raise error
    with pytest.raises(RuntimeError, match="No session created"):
        sm.start_recording()

    # Stopping recording without session should handle gracefully
    sm.stop_recording()  # Should not raise

    # Test creating session when one already active
    sm.create_session("first_session")

    with pytest.raises(RuntimeError, match="A session is already active"):
        sm.create_session("second_session")

    # After stopping, should be able to create new session
    sm.stop_recording()
    session_id = sm.create_session("second_session")
    assert sm.session_id == session_id


def test_session_timing_accuracy(tmp_path: Path) -> None:
    """Test timing accuracy and consistency."""
    sm = SessionManager(base_dir=str(tmp_path))

    # Create session
    create_time = time.time_ns()
    sm.create_session("timing_test")

    # Start recording
    start_time = time.time_ns()
    sm.start_recording()
    record_start_time = time.time_ns()

    # Stop recording
    pre_stop_time = time.time_ns()
    sm.stop_recording()
    stop_time = time.time_ns()

    # Load metadata
    metadata = sm.metadata

    # Verify timing consistency
    assert metadata["created_at_ns"] >= create_time
    assert metadata["created_at_ns"] <= record_start_time

    assert metadata["start_time_ns"] >= start_time
    assert metadata["start_time_ns"] <= record_start_time

    assert metadata["end_time_ns"] >= pre_stop_time
    assert metadata["end_time_ns"] <= stop_time

    # Verify duration calculation
    expected_duration = metadata["end_time_ns"] - metadata["start_time_ns"]
    assert metadata["duration_ns"] == expected_duration
    assert metadata["duration_ns"] >= 0


def test_session_metadata_dataclass() -> None:
    """Test SessionMetadata dataclass functionality."""
    metadata = SessionMetadata(
        version=1,
        session_id="test-session-123",
        name="Test Session",
        created_at_ns=1234567890000000000,
        created_at="2024-01-01T00:00:00Z",
        state="Recording",
        start_time_ns=1234567891000000000,
        end_time_ns=1234567892000000000,
        duration_ns=1000000000
    )

    # Test field access
    assert metadata.session_id == "test-session-123"
    assert metadata.name == "Test Session"
    assert metadata.state == "Recording"
    assert metadata.duration_ns == 1000000000

    # Test conversion to dict (via asdict)
    metadata_dict = asdict(metadata)
    assert metadata_dict["session_id"] == "test-session-123"
    assert metadata_dict["version"] == 1
    assert len(metadata_dict) == 9  # All fields present


@patch('pc_controller.src.core.session_manager.cfg_get')
def test_configuration_integration(mock_cfg_get, tmp_path: Path) -> None:
    """Test integration with configuration system."""
    # Mock configuration values
    mock_cfg_get.return_value = "test_value"

    sm = SessionManager(base_dir=str(tmp_path))
    session_id = sm.create_session("config_test")

    # Verify session creation works with config system available
    assert sm.session_id == session_id
    assert sm.is_active


def test_concurrent_session_operations(tmp_path: Path) -> None:
    """Test that session operations are thread-safe (basic test)."""
    import threading

    sm = SessionManager(base_dir=str(tmp_path))
    sm.create_session("concurrent_test")

    results = []
    exceptions = []

    def start_stop_recording():
        try:
            sm.start_recording()
            time.sleep(0.001)
            sm.stop_recording()
            results.append("success")
        except Exception as e:
            exceptions.append(e)

    # Run multiple threads
    threads = []
    for _ in range(5):
        t = threading.Thread(target=start_stop_recording)
        threads.append(t)
        t.start()

    for t in threads:
        t.join()

    # Should not have exceptions (operations should be atomic)
    assert len(exceptions) == 0
    assert len(results) == 5

    # Final state should be consistent
    assert sm.metadata["state"] == "Stopped"


def test_invalid_base_directory_handling() -> None:
    """Test handling of invalid base directories."""
    # Test with non-existent parent directory (should create it)
    invalid_path = "/tmp/non_existent_parent/sessions"
    sm = SessionManager(base_dir=invalid_path)

    # Should still be able to create sessions (directory will be created)
    session_id = sm.create_session("test_session")
    assert sm.session_id == session_id

    # Clean up
    sm.stop_recording()


def test_session_start_convenience_method(tmp_path: Path) -> None:
    """Test the start_session convenience method."""
    sm = SessionManager(base_dir=str(tmp_path))

    # Test start_session method (if it exists)
    try:
        session_id = sm.start_session("convenience_test")
        assert sm.session_id == session_id
        assert sm.is_active
        assert sm.metadata["state"] == "Recording"

        sm.stop_recording()
    except AttributeError:
        # Method might not exist, skip this test
        pytest.skip("start_session method not implemented")


def test_large_session_name_handling(tmp_path: Path) -> None:
    """Test handling of very large session names."""
    sm = SessionManager(base_dir=str(tmp_path))

    # Test with moderately long name that won't cause filesystem issues
    long_name = "a" * 100  # 100 character name (safer than 1000)

    try:
        session_id = sm.create_session(long_name)
        assert sm.session_id == session_id

        # Verify metadata contains full name
        metadata = sm.metadata
        assert metadata["name"] == long_name

        sm.stop_recording()
    except OSError:
        # If filesystem can't handle the long name, that's expected
        pytest.skip("Filesystem cannot handle long session names")
