"""Unit tests for HDF5 exporter (Phase 5).

Skips if pandas or h5py are not available in the environment.
"""
from __future__ import annotations

import os
import tempfile
from pathlib import Path

import pytest

pd = pytest.importorskip("pandas")
h5py = pytest.importorskip("h5py")

# Import after dependency availability checks
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
        # RGB JPEG index with string column
        rgb_csv = session / "Pixel_7" / "rgb.csv"
        _write_csv(rgb_csv, "timestamp_ns,filename", [
            "1000000000,frames/frame_1.jpg",
            "2000000000,frames/frame_2.jpg",
        ])
        # PC-side CSV
        pc_csv = session / "PC" / "webcam.csv"
        _write_csv(pc_csv, "timestamp_ns,frame_id", [
            "1000000000,1",
            "1333333333,2",
        ])

        out_path = str(root / "export.h5")
        metadata = {
            "session_id": session.name,
            "clock_offsets_ns": {"Pixel_7": 1234},
            # Provide clock_sync so exporter writes /sync/stats_json
            "clock_sync": {
                "Pixel_7": {
                    "offset_ns": 1234,
                    "min_delay_ns": 1000000,
                    "std_dev_ns": 1000,
                    "trials": 10,
                }
            },
        }
        annotations = {"annotations": [{"ts_ms": 1000, "text": "start"}]}

        written = export_session_to_hdf5(
            str(session), out_path, metadata=metadata, annotations=annotations
        )
        assert os.path.exists(written)

        with h5py.File(written, "r") as hf:
            # Root attributes
            assert "session_metadata_json" in hf.attrs
            assert "annotations_json" in hf.attrs
            # Groups and datasets
            assert "/Pixel_7/gsr" in hf
            assert "/Pixel_7/ppg" in hf
            assert "/Pixel_7/rgb" in hf
            assert "/PC/webcam" in hf
            # Dataset shapes and presence
            gsr_grp = hf["/Pixel_7/gsr"]
            assert "timestamp_ns" in gsr_grp and "gsr_microsiemens" in gsr_grp
            assert gsr_grp["timestamp_ns"].shape[0] == 2
            # Compression and attrs
            assert gsr_grp["timestamp_ns"].compression == "gzip"
            assert gsr_grp["timestamp_ns"].attrs.get("units") == "ns"
            assert gsr_grp["gsr_microsiemens"].compression == "gzip"
            assert gsr_grp["gsr_microsiemens"].attrs.get("units") == "microsiemens"
            # Sample rate attribute at group and dataset level (1 Hz for this test data)
            assert "sample_rate_hz" in gsr_grp.attrs
            assert abs(float(gsr_grp.attrs["sample_rate_hz"]) - 1.0) < 1e-6
            assert abs(
                float(gsr_grp["gsr_microsiemens"].attrs.get("sample_rate_hz", -1.0)) - 1.0
            ) < 1e-6
            ppg_grp = hf["/Pixel_7/ppg"]
            assert "ppg_raw" in ppg_grp
            assert ppg_grp["ppg_raw"].compression == "gzip"
            # RGB group string column shouldn't get per-dataset sample_rate_hz
            rgb_grp = hf["/Pixel_7/rgb"]
            assert "filename" in rgb_grp
            assert "sample_rate_hz" in rgb_grp.attrs
            assert "sample_rate_hz" not in rgb_grp["filename"].attrs
            # Sync group presence
            if "/sync" in hf:
                sync = hf["/sync"]
                assert "clock_offsets_ns" in sync
                assert "stats_json" in sync
                s = sync["stats_json"][()]
                assert isinstance(s, bytes | str)
