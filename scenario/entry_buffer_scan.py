# -*- coding: utf-8 -*-
"""جاروب «بافر ورود» — چند تیک داخل باکس حجم زیاد، لیمیت بگذاریم؟
اگر رابطه یکنواخت باشد، یافته قابل‌اعتمادتر از یک عدد تک‌افتاده است."""
import os, statistics
import entry_lab as L

HERE = os.path.dirname(os.path.abspath(__file__))
DATA = os.environ.get("GOLDPIN_DATA", "/home/user/data")
WINDOWS = [("A", "xau_m1.csv", "lab_setups.csv"), ("B", "xau_m1_b.csv", "lab_setups_b.csv")]
BUF = [0, 1, 2, 3, 5, 8, 13]
rows = []
for label, bf, sf in WINDOWS:
    bars = L.load_m1(os.path.join(DATA if ("xau_m1" in bf or "btc_m1" in bf) else HERE, bf))
    setups = [s for s in L.load_setups(os.path.join(HERE, sf)) if s["hvTop"] == s["hvTop"] and s["sl"] == s["sl"]]
    for b in BUF:
        vals, fills = [], 0
        for s in setups:
            d, top, bot = s["dir"], s["hvTop"], s["hvBot"]
            edge = top + L.TICK if d == 1 else bot - L.TICK
            px = edge - b * L.TICK * d   # b تیک به داخل باکس
            r = L.simulate(px, s["sl"], L.legs_for(px, s["tp1"], s["tp2"], s["tpX"]), d, bars, s["armBi"], limit_ok=True)
            vals.append(r["pnl"] if r else 0.0)
            fills += 1 if r else 0
        rows.append((label, b, len(vals), fills / len(vals) * 100, sum(vals) / len(vals), statistics.median(vals)))

lines = ["═══ جاروب بافر ورود (تیک داخل باکس) ═══",
         f"{'پنجره':<6}{'بافر':>6}{'n':>5}{'نرخ پر':>9}{'R/ستاپ':>10}{'میانه':>9}", "─" * 45]
for label, b, n, fr, mean, med in rows:
    lines.append(f"{label:<6}{b:>6}{n:>5}{fr:>8.1f}%{mean:>10.3f}{med:>9.3f}")
with open(os.path.join(HERE, "entry_buffer_scan.txt"), "w", encoding="utf-8") as f:
    f.write("\n".join(lines) + "\n")
print("\n".join(lines))
