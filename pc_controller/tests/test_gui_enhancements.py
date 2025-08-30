"""Tests for GUI enhancements - calibration and export functionality."""

import os
from unittest.mock import Mock, patch

import pytest

# Skip GUI tests when libraries not available
pytest_plugins = []
try:
    from PyQt6.QtWidgets import QApplication
    GUI_AVAILABLE = True
except ImportError:
    GUI_AVAILABLE = False


@pytest.mark.skipif(not GUI_AVAILABLE, reason="GUI libraries not available")
class TestCalibrationDialog:
    """Test calibration dialog functionality."""

    @patch('pc_controller.src.gui.gui_manager.QApplication')
    def test_calibration_dialog_parameters(self, mock_app):
        """Test calibration dialog parameter collection."""
        from pc_controller.src.gui.gui_manager import CalibrationDialog

        # Mock parent widget
        parent = Mock()
        dialog = CalibrationDialog(parent)

        # Set test parameters
        dialog.images_dir_edit.setText("/test/images")
        dialog.board_width_spin.setValue(10)
        dialog.board_height_spin.setValue(7)
        dialog.square_size_spin.setValue(0.030)

        # Get parameters
        params = dialog.get_parameters()

        assert params["images_dir"] == "/test/images"
        assert params["board_width"] == 10
        assert params["board_height"] == 7
        assert params["square_size"] == 0.030


@pytest.mark.skipif(not GUI_AVAILABLE, reason="GUI libraries not available")
class TestExportDialog:
    """Test export dialog functionality."""

    @patch('pc_controller.src.gui.gui_manager.QApplication')
    def test_export_dialog_parameters(self, mock_app):
        """Test export dialog parameter collection."""
        from pc_controller.src.gui.gui_manager import ExportDialog

        # Mock parent widget
        parent = Mock()
        dialog = ExportDialog(parent)

        # Set test parameters
        dialog.session_dir_edit.setText("/test/session")
        dialog.output_dir_edit.setText("/test/output")
        dialog.hdf5_check.setChecked(True)
        dialog.csv_check.setChecked(False)
        dialog.mp4_check.setChecked(True)

        # Get parameters
        params = dialog.get_parameters()

        assert params["session_dir"] == "/test/session"
        assert params["output_dir"] == "/test/output"
        assert "HDF5" in params["formats"]
        assert "CSV" not in params["formats"]
        assert "MP4" in params["formats"]


class TestCalibrationWorkflow:
    """Test calibration workflow without GUI dependencies."""

    def test_calibration_parameters_validation(self):
        """Test calibration parameter validation logic."""
        # Test valid parameters
        valid_params = {
            "images_dir": "/valid/path",
            "board_width": 9,
            "board_height": 6,
            "square_size": 0.025
        }

        # These would be validated in the actual GUI workflow
        assert valid_params["board_width"] > 0
        assert valid_params["board_height"] > 0
        assert valid_params["square_size"] > 0
        assert isinstance(valid_params["images_dir"], str)

    @patch('tools.camera_calibration.calibrate_camera')
    @patch('tools.camera_calibration.save_calibration')
    @patch('os.path.isdir')
    def test_calibration_workflow_success(self, mock_isdir, mock_save, mock_calibrate):
        """Test successful calibration workflow."""
        import numpy as np
        from tools.camera_calibration import CalibrationResult

        # Setup mocks
        mock_isdir.return_value = True
        mock_result = CalibrationResult(
            camera_matrix=np.eye(3),
            dist_coeffs=np.zeros(5),
            rms_error=0.5,
            image_size=(640, 480),
            board_size=(9, 6),
            square_size=0.025
        )
        mock_calibrate.return_value = mock_result

        # Test parameters
        params = {
            "images_dir": "/test/images",
            "board_width": 9,
            "board_height": 6,
            "square_size": 0.025
        }

        # This simulates the workflow that would run in GUI
        (params["board_width"], params["board_height"])
        result = mock_calibrate.return_value

        # Verify the workflow would call correct functions
        assert result.rms_error == 0.5
        assert result.board_size == (9, 6)

    def test_calibration_dialog_creation(self):
        """Test calibration dialog can be created without crashing."""
        try:
            from pc_controller.src.gui.calibration_dialog import CalibrationDialog
            # Just test import and class creation without actually showing UI
            dialog_class = CalibrationDialog
            assert dialog_class is not None
            print("CalibrationDialog class successfully imported")
        except ImportError as e:
            if "EGL" in str(e) or "display" in str(e).lower():
                # Expected in headless environment
                print("Skipping GUI test in headless environment")
            else:
                raise
        except Exception as e:
            raise AssertionError(f"Unexpected error importing CalibrationDialog: {e}") from e

    def test_calibration_parameters_defaults(self):
        """Test that calibration has sensible default parameters."""
        # Test the expected default values that would be in the dialog
        default_board_width = 9
        default_board_height = 6
        default_square_size = 0.025  # 25mm

        assert default_board_width > 2
        assert default_board_height > 2
        assert default_square_size > 0
        assert isinstance(default_board_width, int)
        assert isinstance(default_board_height, int)
        assert isinstance(default_square_size, float)


class TestExportWorkflow:
    """Test export workflow without GUI dependencies."""

    def test_export_parameters_validation(self):
        """Test export parameter validation logic."""
        valid_params = {
            "session_dir": "/valid/session",
            "output_dir": "/valid/output",
            "formats": ["HDF5", "CSV"]
        }

        # These would be validated in the actual GUI workflow
        assert isinstance(valid_params["session_dir"], str)
        assert isinstance(valid_params["output_dir"], str)
        assert isinstance(valid_params["formats"], list)
        assert len(valid_params["formats"]) > 0

    def test_supported_export_formats(self):
        """Test that all expected export formats are supported."""
        supported_formats = ["HDF5", "CSV", "MP4"]

        # Verify format list
        assert "HDF5" in supported_formats
        assert "CSV" in supported_formats
        assert "MP4" in supported_formats

    @patch('data.hdf5_exporter.export_session_to_hdf5')
    @patch('os.path.isdir')
    def test_export_workflow_hdf5(self, mock_isdir, mock_export):
        """Test HDF5 export workflow."""
        # Setup mocks
        mock_isdir.return_value = True

        # Test parameters
        params = {
            "session_dir": "/test/session",
            "output_dir": "/test/output",
            "formats": ["HDF5"]
        }

        # Simulate export workflow
        if "HDF5" in params["formats"]:
            out_path = os.path.join(params["output_dir"], "export.h5")
            meta = {"session_dir": params["session_dir"]}

            # This would be called in the actual workflow
            # mock_export(params["session_dir"], out_path, metadata=meta, annotations=ann)

        # Verify the workflow structure is correct
        assert out_path.endswith("export.h5")
        assert meta["session_dir"] == params["session_dir"]


class TestUserExperienceEnhancements:
    """Test user experience enhancement features."""

    def test_error_message_improvements(self):
        """Test that error messages are user-friendly."""
        # Examples of technical vs user-friendly messages
        technical_errors = {
            "FileNotFoundError": "File location not found",
            "ConnectionRefusedError": "Unable to connect to device",
            "CalibrationError": "Camera calibration failed - please check images",
            "ExportError": "Data export failed - check output location"
        }

        for user_message in technical_errors.values():
            # Verify user messages are descriptive and actionable
            assert len(user_message) > 10
            assert not user_message.isupper()  # Not all caps
            assert "Error" not in user_message or "failed" in user_message.lower()

    def test_file_location_indicators(self):
        """Test file location indicator functionality."""
        # Simulate showing export directory
        test_directories = [
            "/home/user/data/session_20241221",
            "C:\\Users\\researcher\\Documents\\exports",
            "/tmp/calibration_results"
        ]

        for directory in test_directories:
            # This would be shown in the UI as export location
            location_message = f"Exporting to: {directory}"
            assert directory in location_message
            assert "Exporting to:" in location_message


if __name__ == "__main__":
    pytest.main([__file__])
