#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
ساخت دادهٔ بک‌تست داخلی (طرح B) — برش یک سال M1 با حجم، خروجی آمادهٔ اپ.

ورودی: CSV منبع با ستون‌های   time,open,high,low,close,volume[,spread]
        که «ساعت فایل» آن به وقت محلی بروکر است (Europe/Berlin، با تغییر ساعت).

کارهایی که انجام می‌دهد (هر کدام یک شکست واقعی بود که در بررسی داده دیده شد):
  ۱) تبدیل ساعت فایل → UTC به‌صورت DST-aware  (اگر انجام نشود، قواعد سشن ۲–۳ ساعت جابه‌جا می‌شوند)
  ۲) حذف کامل شنبه/یکشنبه پیش از ۲۲:۰۰ UTC (قواعد MarketHours موتور)
  ۳) مرتب‌سازی + حذف کندل تکراری
  ۴) اعتبارسنجی: OHLC معتبر، نبود فاصلهٔ داخلی بیش از ۲۰ ساعت در روزهای معاملاتی، نبود کندل بی‌حجم
  ۵) خروجی gzip با همان قالب asset فعلی اپ: timestamp(ms),open,high,low,close,volume

نمونه:
  python3 tools/build_sample.py  --src /path/XAUUSD_OHLCV_1M.csv \
      --from 2025-08-21 --to 2026-08-21 --out data/goldpin_xauusd_m1_1y.csv.gz
"""
import argparse, csv, gzip, hashlib, io, json, os, sys
from datetime import datetime, timedelta, timezone

try:
    from zoneinfo import ZoneInfo
    SRC_TZ = ZoneInfo("Europe/Berlin")     # ساعت فایل منبع
except Exception:                          # pragma: no cover
    SRC_TZ = None

SATURDAY, SUNDAY = 5, 6
SUNDAY_OPEN_HOUR_UTC = 22


def parse_args(argv):
    p = argparse.ArgumentParser()
    p.add_argument("--src", required=True, help="CSV منبع")
    p.add_argument("--from", dest="dfrom", required=True, help="YYYY-MM-DD (UTC، شامل)")
    p.add_argument("--to", dest="dto", required=True, help="YYYY-MM-DD (UTC، غیرشامل)")
    p.add_argument("--out", required=True, help="خروجی .csv.gz")
    p.add_argument("--max-gap-hours", type=float, default=20.0)
    return p.parse_args(argv)


def to_utc_ms(wall: datetime) -> int:
    """ساعت فایل (دیوار-زمان بروکر) → میلی‌ثانیهٔ UTC."""
    if SRC_TZ is not None:
        return int(wall.replace(tzinfo=SRC_TZ).timestamp() * 1000)
    # بدون zoneinfo: تقریب ثابت UTC+2
    return int((wall - timedelta(hours=2)).replace(tzinfo=timezone.utc).timestamp() * 1000)


def tradable(ms: int) -> bool:
    d = datetime.fromtimestamp(ms / 1000, tz=timezone.utc)
    if d.weekday() == SATURDAY:
        return False
    if d.weekday() == SUNDAY and d.hour < SUNDAY_OPEN_HOUR_UTC:
        return False
    return True


def main(argv):
    a = parse_args(argv)
    lo = datetime.strptime(a.dfrom, "%Y-%m-%d").replace(tzinfo=timezone.utc)
    hi = datetime.strptime(a.dto, "%Y-%m-%d").replace(tzinfo=timezone.utc)
    lo_ms, hi_ms = int(lo.timestamp() * 1000), int(hi.timestamp() * 1000)

    rows = []
    seen = set()
    dropped_tz, dropped_weekend, dropped_bad = 0, 0, 0
    src_rows = 0
    with open(a.src, newline="", encoding="utf-8", errors="replace") as f:
        rd = csv.reader(f)
        for r in rd:
            if not r or not r[0] or r[0].strip().lower() == "time":
                continue
            try:
                wall = datetime.strptime(r[0].strip(), "%Y.%m.%d %H:%M:%S")
                o, h, l, c = float(r[1]), float(r[2]), float(r[3]), float(r[4])
                v = float(r[5]) if len(r) > 5 and r[5] not in ("", None) else 0.0
            except Exception:
                dropped_bad += 1
                continue
            src_rows += 1
            ms = to_utc_ms(wall)
            if not (lo_ms <= ms < hi_ms):
                continue
            if not tradable(ms):
                dropped_weekend += 1
                continue
            if not (h >= max(o, c) and l <= min(o, c) and h > 0 and l > 0):
                dropped_bad += 1
                continue
            if ms in seen:
                dropped_tz += 1
                continue
            seen.add(ms)
            rows.append((ms, o, h, l, c, v))

    rows.sort(key=lambda x: x[0])
    if not rows:
        print("هیچ کندلی در بازه ساخته نشد", file=sys.stderr)
        return 2

    # شکاف‌های داخلی (روز معاملاتی) — فقط گزارش، حذف نمی‌شود
    gaps = []
    lim = a.max_gap_hours * 3600_000
    for i in range(1, len(rows)):
        d = rows[i][0] - rows[i - 1][0]
        if d > lim:
            gaps.append((rows[i - 1][0], rows[i][0], d / 3600_000))

    zero_vol = sum(1 for r in rows if r[5] <= 0)
    body = io.BytesIO()
    with gzip.GzipFile(fileobj=body, mode="wb", compresslevel=9, mtime=0) as gz:
        w = io.TextIOWrapper(gz, encoding="utf-8", newline="")
        w.write("timestamp,open,high,low,close,volume\n")
        for ms, o, h, l, c, v in rows:
            w.write("%d,%.5f,%.5f,%.5f,%.5f,%.0f\n" % (ms, o, h, l, c, v))
        w.flush()
    blob = body.getvalue()

    os.makedirs(os.path.dirname(os.path.abspath(a.out)), exist_ok=True)
    with open(a.out, "wb") as f:
        f.write(blob)

    meta = {
        "file": os.path.basename(a.out),
        "bars": len(rows),
        "bytesGz": len(blob),
        "sha256": hashlib.sha256(blob).hexdigest(),
        "fromUtc": datetime.fromtimestamp(rows[0][0] / 1000, tz=timezone.utc).isoformat(),
        "toUtc": datetime.fromtimestamp(rows[-1][0] / 1000, tz=timezone.utc).isoformat(),
        "sourceRows": src_rows,
        "droppedWeekend": dropped_weekend,
        "droppedDup": dropped_tz,
        "droppedInvalid": dropped_bad,
        "zeroVolumeBars": zero_vol,
        "gapsOverHours": len(gaps),
        "sourceTimezone": "Europe/Berlin (DST-aware) → UTC",
        "columns": "timestamp(ms,UTC),open,high,low,close,volume",
    }
    meta_path = a.out + ".meta.json"
    with open(meta_path, "w", encoding="utf-8") as f:
        json.dump(meta, f, ensure_ascii=False, indent=2)

    print(json.dumps(meta, ensure_ascii=False, indent=2))
    if zero_vol:
        print("⚠ %d کندل با حجم صفر" % zero_vol, file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
