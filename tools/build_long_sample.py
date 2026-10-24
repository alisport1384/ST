#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Build large XAUUSD M1 backtest windows without millions of Python objects.

Requires pandas and numpy. Source columns: time,open,high,low,close,volume[,spread],
where source wall-clock timestamps use Europe/Berlin. Output is gzip CSV in the app's
repository format: timestamp(ms,UTC),open,high,low,close,volume.
"""
import argparse
import gzip
import hashlib
import io
import json
import os
from datetime import datetime, timezone

import numpy as np
import pandas as pd


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--src", required=True, help="Kaggle XAUUSD_OHLCV_1M.csv")
    parser.add_argument("--from", dest="date_from", required=True, help="Inclusive UTC date YYYY-MM-DD")
    parser.add_argument("--to", dest="date_to", required=True, help="Exclusive UTC date YYYY-MM-DD")
    parser.add_argument("--out", required=True, help="Output .csv.gz path")
    args = parser.parse_args()

    lo = datetime.strptime(args.date_from, "%Y-%m-%d").replace(tzinfo=timezone.utc)
    hi = datetime.strptime(args.date_to, "%Y-%m-%d").replace(tzinfo=timezone.utc)
    lo_ms = int(lo.timestamp() * 1000)
    hi_ms = int(hi.timestamp() * 1000)

    columns = []
    source_rows = dropped_bad = dropped_weekend = kept_count = 0
    for chunk in pd.read_csv(
        args.src,
        usecols=["time", "open", "high", "low", "close", "volume"],
        dtype={"time": "string"},
        chunksize=500_000,
    ):
        wall = pd.to_datetime(chunk["time"], format="%Y.%m.%d %H:%M:%S", errors="coerce")
        # ambiguous=True matches datetime.replace(tzinfo=ZoneInfo(...), fold=0),
        # which is how the established short-range builder handles the DST fallback.
        berlin = wall.dt.tz_localize("Europe/Berlin", ambiguous=True, nonexistent="shift_forward")
        times = (berlin.astype("int64") // 1_000_000).to_numpy(dtype=np.int64)
        values = [
            pd.to_numeric(chunk[name], errors="coerce").to_numpy(dtype=np.float64, na_value=np.nan)
            for name in ("open", "high", "low", "close", "volume")
        ]
        opens, highs, lows, closes, volumes = values

        valid_date = wall.notna().to_numpy()
        valid_numbers = np.isfinite(opens) & np.isfinite(highs) & np.isfinite(lows) & np.isfinite(closes) & np.isfinite(volumes)
        valid_row = valid_date & valid_numbers
        source_rows += int(valid_row.sum())
        dropped_bad += int((~valid_row).sum())

        in_range = valid_row & (times >= lo_ms) & (times < hi_ms)
        day = times // 86_400_000
        weekday = (day + 3) % 7  # 1970-01-01 was Thursday; Monday is zero.
        hour = (times // 3_600_000) % 24
        weekend = (weekday == 5) | ((weekday == 6) & (hour < 22))
        dropped_weekend += int((in_range & weekend).sum())
        tradable = in_range & ~weekend

        good_ohlc = (
            (highs >= np.maximum(opens, closes))
            & (lows <= np.minimum(opens, closes))
            & (highs > 0)
            & (lows > 0)
        )
        dropped_bad += int((tradable & ~good_ohlc).sum())
        selected = np.flatnonzero(tradable & good_ohlc)
        if len(selected):
            # Five decimals are the app CSV precision; integer columns keep the full
            # 20-year working set compact while sorting and de-duplicating.
            block = [times[selected].copy()]
            block.extend(np.rint(price[selected] * 100_000).astype(np.int32) for price in (opens, highs, lows, closes))
            block.append(np.rint(volumes[selected]).astype(np.int32))
            columns.append(block)
            kept_count += len(selected)
        print(f"source rows {source_rows:,}; selected rows {kept_count:,}", flush=True)

    if not columns:
        parser.error("no valid candles in the requested interval")
    data = [np.concatenate([block[i] for block in columns]) for i in range(6)]
    columns = None
    order = np.argsort(data[0], kind="stable")
    data = [column[order] for column in data]
    order = None

    times = data[0]
    keep = np.empty(len(times), dtype=bool)
    keep[0] = True
    keep[1:] = times[1:] != times[:-1]
    duplicate_count = int((~keep).sum())
    data = [column[keep] for column in data]
    times, opens, highs, lows, closes, volumes = data

    output_path = os.path.abspath(args.out)
    os.makedirs(os.path.dirname(output_path), exist_ok=True)
    temporary_path = output_path + ".tmp"
    with open(temporary_path, "wb") as raw:
        with gzip.GzipFile(filename="", fileobj=raw, mode="wb", compresslevel=9, mtime=0) as compressed:
            with io.TextIOWrapper(compressed, encoding="utf-8", newline="") as writer:
                writer.write("timestamp,open,high,low,close,volume\n")
                for i in range(len(times)):
                    writer.write(
                        "%d,%.5f,%.5f,%.5f,%.5f,%.0f\n"
                        % (int(times[i]), int(opens[i]) / 100_000, int(highs[i]) / 100_000,
                           int(lows[i]) / 100_000, int(closes[i]) / 100_000, int(volumes[i]))
                    )
    os.replace(temporary_path, output_path)

    digest = hashlib.sha256()
    with open(output_path, "rb") as compressed:
        for block in iter(lambda: compressed.read(1 << 20), b""):
            digest.update(block)
    gaps = int(np.sum(np.diff(times) > 20 * 3_600_000))
    metadata = {
        "file": os.path.basename(output_path),
        "bars": int(len(times)),
        "bytesGz": os.path.getsize(output_path),
        "sha256": digest.hexdigest(),
        "fromUtc": datetime.fromtimestamp(int(times[0]) / 1000, tz=timezone.utc).isoformat(),
        "toUtc": datetime.fromtimestamp(int(times[-1]) / 1000, tz=timezone.utc).isoformat(),
        "sourceRows": source_rows,
        "droppedWeekend": dropped_weekend,
        "droppedDup": duplicate_count,
        "droppedInvalid": dropped_bad,
        "zeroVolumeBars": int(np.sum(volumes <= 0)),
        "gapsOverHours": gaps,
        "sourceTimezone": "Europe/Berlin (DST-aware) → UTC",
        "columns": "timestamp(ms,UTC),open,high,low,close,volume",
    }
    with open(output_path + ".meta.json", "w", encoding="utf-8") as meta_file:
        json.dump(metadata, meta_file, ensure_ascii=False, indent=2)
    print(json.dumps(metadata, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
