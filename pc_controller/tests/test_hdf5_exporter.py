"""Unit tests for HDF5 exporter (Phase 5).

Skips if pandas or h5py are not available in the environment.
"""
from __future__ import annotations

import os
import pytest
import tempfile
from pathlib import Path

pd = pytest.importorskip("pandas")
h5py = pytest.importorskip("h5py")

from pc_controller.src.data.hdf5_exporter import export_session_to_hdf5


def _write_csv(path: Path, header: str, rows: list[str]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8") as f:
        f.write(header + "\n")
        for r in rows:
            f.write(r + "\n")


def test_export_session_to_hdf5_structure_and_attrs() -> None:
    with tempfile.TemporaryDirectory() as td:
        root = Path(td)
        session = root / "20250101_020202"
        # Create some CSVs under device subfolders
        gsr_csv = session / "Pixel_7" / "gsr.csv"
        _write_csv(gsr_csv, "timestamp_ns,gsr_microsiemens", [
            "1000000000,1.11",
            "2000000000,1.22",
        ])
        # Another modality
        ppg_csv = session / "Pixel_7" / "ppg.csv"
        _write_csv(ppg_csv, "timestamp_ns,ppg_raw", [
            "1000000000,512",
            "2000000000,520",
        ])
        # PC-side CSV
        pc_csv = session / "PC" / "webcam.csv"
        _write_csv(pc_csv, "timestamp_ns,frame_id", [
            "1000000000,1",
            "1333333333,2",
        ])

        out_path = str(root / "export.h5")
        metadata = {"session_id": session.name}
        annotations = {"annotations": [{"ts_ms": 1000, "text": "start"}]}

        written = export_session_to_hdf5(str(session), out_path, metadata=metadata, annotations=annotations)
        assert os.path.exists(written)

        with h5py.File(written, "r") as hf:
            # Root attributes
            assert "session_metadata_json" in hf.attrs
            assert "annotations_json" in hf.attrs
            # Groups and datasets
            assert "/Pixel_7/gsr" in hf
            assert "/Pixel_7/ppg" in hf
            assert "/PC/webcam" in hf
            # Dataset shapes and presence
            gsr_grp = hf["/Pixel_7/gsr"]
            assert "timestamp_ns" in gsr_grp and "gsr_microsiemens" in gsr_grp
            assert gsr_grp["timestamp_ns"].shape[0] == 2
            ppg_grp = hf["/Pixel_7/ppg"]
            assert "ppg_raw" in ppg_grp
