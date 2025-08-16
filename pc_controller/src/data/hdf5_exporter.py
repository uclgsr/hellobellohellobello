"""HDF5 Exporter (Phase 5).

Compiles all CSVs in a session directory into a single HDF5 file
organized by device and modality.
"""
from __future__ import annotations

from typing import Dict
import json
import os
import glob

import h5py
import pandas as pd


def export_session_to_hdf5(session_dir: str, output_path: str, metadata: Dict | None = None, annotations: Dict | None = None) -> str:
    """Export a session directory to an HDF5 file.

    The HDF5 layout is /<device>/<modality>/ with datasets derived from CSV columns.
    If a timestamp column is present (timestamp_ns, ts_ns, timestamp, time_ns),
    it will be used as the index and stored as a separate dataset named 'timestamp_ns'.

    Returns the output_path written.
    """
    os.makedirs(os.path.dirname(output_path) or ".", exist_ok=True)
    with h5py.File(output_path, "w") as hf:
        # Root attributes
        if metadata is not None:
            hf.attrs["session_metadata_json"] = json.dumps(metadata)
        if annotations is not None:
            hf.attrs["annotations_json"] = json.dumps(annotations)
        # Walk CSVs
        for csv_path in glob.glob(os.path.join(session_dir, "**", "*.csv"), recursive=True):
            rel = os.path.relpath(csv_path, session_dir)
            parts = rel.replace("\\", "/").split("/")
            device = parts[0] if len(parts) >= 2 else "PC"
            modality_name = os.path.splitext(parts[-1])[0]
            group = hf.require_group(f"/{device}/{modality_name}")
            df = pd.read_csv(csv_path)
            # Determine timestamp column if any
            ts_col = None
            for cand in ("timestamp_ns", "ts_ns", "timestamp", "time_ns"):
                if cand in df.columns:
                    ts_col = cand
                    break
            if ts_col is not None:
                ts_vals = pd.to_numeric(df[ts_col], errors="coerce").dropna().astype("int64")
                group.create_dataset("timestamp_ns", data=ts_vals.to_numpy())
                data_cols = [c for c in df.columns if c != ts_col]
            else:
                data_cols = list(df.columns)
            # Store each remaining column
            for col in data_cols:
                series = pd.to_numeric(df[col], errors="coerce") if df[col].dtype.kind not in ("i", "u", "f") else df[col]
                # Drop NaNs for numeric, otherwise store as-as bytes
                try:
                    data = series.dropna().to_numpy()
                    group.create_dataset(col, data=data)
                except Exception:
                    # Fallback: store as strings
                    data = df[col].astype(str).to_numpy(dtype="S")
                    group.create_dataset(col, data=data)
    return output_path
