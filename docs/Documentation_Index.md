# Multi-Modal Physiological Sensing Platform - Documentation Index

This document provides a comprehensive index of all documentation in the repository, organized by category and purpose.

## 📋 Quick Navigation

- [User Documentation](#user-documentation) - For end users and researchers
- [Developer Documentation](#developer-documentation) - For developers and contributors
- [API Documentation](#api-documentation) - Technical API references
- [Testing Documentation](#testing-documentation) - Testing guides and validation
- [Project Management](#project-management) - Project planning and analysis
- [Academic Documentation](#academic-documentation) - Thesis and academic content
- [AI Development Guidelines](#ai-development-guidelines) - AI agent instructions

## 📖 User Documentation

**Primary User Guides:**
- [`User_Manual.md`](markdown/User_Manual.md) - Complete end-user guide for operating the platform
- [`README.md`](../README.md) - Repository overview and quick start guide

**Hardware Setup:**
- [`Hardware_Validation_Protocol.md`](markdown/Hardware_Validation_Protocol.md) - Standard procedures for hardware validation
- [`Flash_Sync_Validation.md`](markdown/Flash_Sync_Validation.md) - Time synchronization validation procedures

**Operational Documentation:**
- [`PROTOCOL.md`](../PROTOCOL.md) - Complete communication protocol specification
- [`BACKUP_STRATEGY.md`](../BACKUP_STRATEGY.md) - Data backup and recovery procedures
- [`Troubleshooting_Guide.md`](Troubleshooting_Guide.md) - Diagnostic procedures and solutions

## 🔧 Developer Documentation

**Setup and Development:**
- [`Developer_Guide.md`](markdown/Developer_Guide.md) - Technical implementation details and development procedures
- [`Production_Deployment_Guide.md`](Production_Deployment_Guide.md) - Enterprise deployment and security hardening

**Architecture and Design:**
- [`1_high_level_design.md`](markdown/1_high_level_design.md) - System architecture overview
- [`2_detail_design.md`](markdown/2_detail_design.md) - Detailed design specifications
- [`3_phase_by_phase.md`](markdown/3_phase_by_phase.md) - Implementation phases overview

**Phase Implementation Details:**
- [`4_1_phase.md`](markdown/4_1_phase.md) - Phase 1 implementation
- [`4_2_phase.md`](markdown/4_2_phase.md) - Phase 2 implementation
- [`4_3_phase.md`](markdown/4_3_phase.md) - Phase 3 implementation
- [`4_4_phase.md`](markdown/4_4_phase.md) - Phase 4 implementation
- [`4_5_phase.md`](markdown/4_5_phase.md) - Phase 5 implementation
- [`4_6_phase.md`](markdown/4_6_phase.md) - Phase 6 implementation

## 🔌 API Documentation

**Core APIs:**
- [`TLS_API_Documentation.md`](TLS_API_Documentation.md) - TLS security implementation with API reference
- [`Heartbeat_API_Documentation.md`](Heartbeat_API_Documentation.md) - Fault tolerance system documentation

## 🧪 Testing Documentation

**Testing Strategies:**
- [`TEST_PLAN.md`](../TEST_PLAN.md) - Comprehensive testing strategy and procedures
- [`System_Test_Checklist.md`](markdown/System_Test_Checklist.md) - System validation checklist
- [`System_Validation_Report.md`](markdown/System_Validation_Report.md) - Validation results and reports

**Testing Guides:**
- [`guide_unit_testing.md`](markdown/guide_unit_testing.md) - Unit testing procedures
- [`guide_integration_testing.md`](markdown/guide_integration_testing.md) - Integration testing guide
- [`guide_system_testing.md`](markdown/guide_system_testing.md) - System testing procedures
- [`guide_running_tests_with_hardware.md`](markdown/guide_running_tests_with_hardware.md) - Hardware testing workflow

## 📊 Project Management

**Project Analysis:**
- [`IMPLEMENTATION_SUMMARY.md`](project_management/IMPLEMENTATION_SUMMARY.md) - Implementation status summary
- [`MISSING_FEATURES_ANALYSIS.md`](project_management/MISSING_FEATURES_ANALYSIS.md) - Feature gap analysis
- [`Chapter5_Evaluation_and_Testing.md`](project_management/Chapter5_Evaluation_and_Testing.md) - Evaluation chapter content

**Project Planning:**
- [`Backlog.md`](markdown/Backlog.md) - Development backlog and tasks
- [`CHANGELOG.md`](../CHANGELOG.md) - Version history and changes

## 📚 Academic Documentation

**LaTeX Thesis Content:**
- [`latex/main.tex`](latex/main.tex) - Main thesis document
- [`latex/1.tex`](latex/1.tex) - Introduction and Objectives
- [`latex/2.tex`](latex/2.tex) - Background and Literature Review
- [`latex/3.tex`](latex/3.tex) - Requirements and Analysis
- [`latex/4.tex`](latex/4.tex) - Design and Implementation
- [`latex/5.tex`](latex/5.tex) - Evaluation and Testing
- [`latex/6.tex`](latex/6.tex) - Conclusions and Future Work
- [`latex/chapters_1-3_draft.md`](latex/chapters_1-3_draft.md) - Draft content for chapters 1-3 (comprehensive)

**Appendices:**
- [`latex/appendix_A.tex`](latex/appendix_A.tex) through [`latex/appendix_H.tex`](latex/appendix_H.tex) - Various appendices
- [`latex/appendix_Z.tex`](latex/appendix_Z.tex) - **Consolidated Figures, Diagrams, and Visual Content** (primary visual reference)

**Project Guidelines:**
- [`ProjectGuidelines_2024-25.md`](markdown/ProjectGuidelines_2024-25.md) - University project guidelines
- [`cs_project_marking_form_MEng_1819.md`](markdown/cs_project_marking_form_MEng_1819.md) - MEng marking criteria

## 🤖 AI Development Guidelines

**AI Agent Instructions:**
- [`AI_Agent_Guidelines.md`](ai_guidelines/AI_Agent_Guidelines.md) - Primary AI agent instructions and coding standards
- [`Platform_Setup_Guide.md`](ai_guidelines/Platform_Setup_Guide.md) - Platform setup and testing procedures
- [`GEMINI.md`](ai_guidelines/GEMINI.md) - Gemini-specific instructions and context
- [`gemini_plan.md`](ai_guidelines/gemini_plan.md) - Strategic development plan
- [`gemini_prompts.md`](ai_guidelines/gemini_prompts.md) - AI prompting guidelines

## 🗂️ Documentation Organization Notes

### Recent Consolidation Changes

This documentation structure reflects a recent consolidation effort to improve organization:

1. **LaTeX Appendices**: Successfully removed deprecated `appendix_I.tex` and updated all cross-references to point to the consolidated `appendix_Z.tex`
2. **Root-Level Cleanup**: Moved scattered documentation files into organized subdirectories
3. **AI Guidelines**: Centralized all AI development instructions in `docs/ai_guidelines/`
4. **Project Management**: Grouped analysis and planning documents in `docs/project_management/`
5. **Academic Content**: Properly organized thesis content including draft chapters

### Visual Content Reference

⚠️ **Important**: All figures, diagrams, tables, and visual materials are now consolidated in **`latex/appendix_Z.tex`**. When referencing visual content, always use this appendix as the single source of truth.

### Cross-References

For consistent referencing:
- **Figures**: Reference as "see Figure X.Y in Appendix Z"
- **APIs**: Use the dedicated API documentation in the root docs folder
- **Testing**: Follow the structured testing guides in `markdown/guide_*.md`

## 📝 Maintenance

This index should be updated when:
- New documentation files are added
- Existing files are moved or renamed
- Major structural changes are made to the documentation

Last updated: [Current Date - to be updated by maintainer]