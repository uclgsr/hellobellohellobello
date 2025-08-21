"""Tests for Quick Start Guide and Tutorial System."""

import pytest
from unittest.mock import Mock, patch, MagicMock
import tempfile
import os

# Skip GUI tests when libraries not available  
pytest_plugins = []
try:
    from PyQt6.QtWidgets import QApplication
    GUI_AVAILABLE = True
except ImportError:
    GUI_AVAILABLE = False


@pytest.mark.skipif(not GUI_AVAILABLE, reason="GUI libraries not available")
class TestQuickStartGuide:
    """Test quick start guide functionality."""
    
    @patch('pc_controller.src.core.quick_start_guide.QApplication')
    def test_tutorial_steps_creation(self, mock_app):
        """Test that tutorial steps are properly created."""
        from core.quick_start_guide import QuickStartGuide
        
        parent = Mock()
        guide = QuickStartGuide(parent)
        
        # Verify steps are created
        assert len(guide.steps) > 0
        assert guide.current_step == 0
        
        # Check first step
        first_step = guide.steps[0]
        assert "Welcome" in first_step.title
        assert len(first_step.content) > 50
        
    @patch('pc_controller.src.core.quick_start_guide.QApplication')
    def test_tutorial_navigation(self, mock_app):
        """Test tutorial navigation functionality."""
        from core.quick_start_guide import QuickStartGuide
        
        parent = Mock()
        guide = QuickStartGuide(parent)
        
        initial_step = guide.current_step
        
        # Test navigation (would normally be triggered by button clicks)
        guide._on_next()
        assert guide.current_step == initial_step + 1
        
        guide._on_previous() 
        assert guide.current_step == initial_step
        
    @patch('pc_controller.src.core.quick_start_guide.QApplication')
    def test_tutorial_signals(self, mock_app):
        """Test tutorial completion and skip signals."""
        from core.quick_start_guide import QuickStartGuide
        
        parent = Mock()
        guide = QuickStartGuide(parent)
        
        # Mock signal connections
        completed_callback = Mock()
        skipped_callback = Mock()
        
        guide.tutorial_completed.connect(completed_callback)
        guide.tutorial_skipped.connect(skipped_callback)
        
        # Test completion
        guide._on_finish()
        completed_callback.assert_called_once()
        
        # Test skipping
        guide._on_skip()
        skipped_callback.assert_called_once()


class TestFirstTimeSetupWizard:
    """Test first-time setup wizard functionality."""
    
    def test_wizard_initialization(self):
        """Test wizard initialization with settings."""
        from core.quick_start_guide import FirstTimeSetupWizard
        
        mock_settings = Mock()
        wizard = FirstTimeSetupWizard(mock_settings)
        
        assert wizard.settings == mock_settings
        assert not wizard._tutorial_shown
    
    def test_should_show_tutorial_logic(self):
        """Test tutorial display logic."""
        from core.quick_start_guide import FirstTimeSetupWizard
        
        mock_settings = Mock()
        
        # Test should show for new users
        mock_settings.get_boolean.return_value = False
        wizard = FirstTimeSetupWizard(mock_settings)
        assert wizard.should_show_tutorial()
        
        # Test should not show for returning users
        mock_settings.get_boolean.return_value = True
        wizard = FirstTimeSetupWizard(mock_settings)
        assert not wizard.should_show_tutorial()
    
    def test_tutorial_completion_handling(self):
        """Test tutorial completion state management."""
        from core.quick_start_guide import FirstTimeSetupWizard
        
        mock_settings = Mock()
        wizard = FirstTimeSetupWizard(mock_settings)
        
        # Test completion callback
        wizard._on_tutorial_completed()
        mock_settings.set_boolean.assert_called_with("tutorial_completed", True)
        
        # Test skip callback  
        mock_settings.reset_mock()
        wizard._on_tutorial_skipped()
        mock_settings.set_boolean.assert_called_with("tutorial_skipped", True)


class TestTutorialSteps:
    """Test individual tutorial steps and content."""
    
    def test_tutorial_step_structure(self):
        """Test tutorial step data structure."""
        from core.quick_start_guide import TutorialStep
        
        step = TutorialStep(
            title="Test Step",
            content="Test content",
            action_text="Test Action",
            skip_allowed=True
        )
        
        assert step.title == "Test Step"
        assert step.content == "Test content"
        assert step.action_text == "Test Action"
        assert step.skip_allowed
        assert step.action_callback is None
    
    def test_tutorial_step_with_callback(self):
        """Test tutorial step with action callback."""
        from core.quick_start_guide import TutorialStep
        
        def test_callback():
            return "callback executed"
        
        step = TutorialStep(
            title="Test Step",
            content="Test content",
            action_callback=test_callback
        )
        
        assert step.action_callback is not None
        assert step.action_callback() == "callback executed"


class TestTutorialContent:
    """Test tutorial content quality and completeness."""
    
    @patch('pc_controller.src.core.quick_start_guide.QuickStartGuide')
    def test_tutorial_content_coverage(self, mock_guide_class):
        """Test that tutorial covers all essential topics."""
        from core.quick_start_guide import QuickStartGuide
        
        # Create actual instance to test content
        mock_guide_class.side_effect = lambda parent: QuickStartGuide.__new__(QuickStartGuide)
        guide = QuickStartGuide.__new__(QuickStartGuide)
        guide.steps = guide._create_tutorial_steps()
        
        # Check essential topics are covered
        all_content = " ".join([step.content.lower() for step in guide.steps])
        
        essential_topics = [
            "network", "wifi", "connection",
            "calibration", "camera", "recording",
            "export", "data", "session"
        ]
        
        for topic in essential_topics:
            assert topic in all_content, f"Tutorial missing coverage of: {topic}"
    
    @patch('pc_controller.src.core.quick_start_guide.QuickStartGuide')
    def test_tutorial_actionable_content(self, mock_guide_class):
        """Test that tutorial provides actionable instructions."""
        from core.quick_start_guide import QuickStartGuide
        
        mock_guide_class.side_effect = lambda parent: QuickStartGuide.__new__(QuickStartGuide)
        guide = QuickStartGuide.__new__(QuickStartGuide)
        guide.steps = guide._create_tutorial_steps()
        
        # Check for actionable language
        actionable_keywords = ["click", "select", "choose", "start", "connect", "configure"]
        
        content_with_actions = 0
        for step in guide.steps:
            content_lower = step.content.lower()
            if any(keyword in content_lower for keyword in actionable_keywords):
                content_with_actions += 1
        
        # Most steps should have actionable content
        assert content_with_actions >= len(guide.steps) * 0.7


class TestTutorialIntegration:
    """Test tutorial integration with main application."""
    
    def test_integration_helper_functions(self):
        """Test integration helper functions."""
        from core.quick_start_guide import integrate_quick_start_guide
        
        mock_gui = Mock()
        mock_settings = Mock()
        
        integration = integrate_quick_start_guide(mock_gui, mock_settings)
        
        # Check integration structure
        assert 'show_tutorial_if_first_time' in integration
        assert 'show_tutorial_on_demand' in integration
        assert 'setup_wizard' in integration
        
        # Check functions are callable
        assert callable(integration['show_tutorial_if_first_time'])
        assert callable(integration['show_tutorial_on_demand'])
    
    @patch('core.quick_start_guide.QuickStartGuide')
    def test_on_demand_tutorial_display(self, mock_guide):
        """Test on-demand tutorial display."""
        from core.quick_start_guide import FirstTimeSetupWizard
        
        mock_settings = Mock()
        wizard = FirstTimeSetupWizard(mock_settings)
        
        mock_parent = Mock()
        wizard.show_tutorial_on_demand(mock_parent)
        
        # Verify tutorial was created and shown
        mock_guide.assert_called_once_with(mock_parent)
        mock_guide.return_value.exec.assert_called_once()


class TestTutorialPersistence:
    """Test tutorial state persistence."""
    
    def test_tutorial_flag_file_creation(self):
        """Test tutorial flag file handling."""
        with tempfile.TemporaryDirectory() as temp_dir:
            flag_file = os.path.join(temp_dir, ".tutorial_completed")
            
            # File should not exist initially
            assert not os.path.exists(flag_file)
            
            # Simulate flag file creation
            with open(flag_file, 'w') as f:
                f.write("Tutorial offered on first run")
            
            # File should now exist
            assert os.path.exists(flag_file)
            
            # Content should be readable
            with open(flag_file, 'r') as f:
                content = f.read()
                assert "Tutorial offered" in content


if __name__ == "__main__":
    pytest.main([__file__])