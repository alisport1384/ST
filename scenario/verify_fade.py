# -*- coding: utf-8 -*-
"""بازبینی مستقل یافتهٔ «معکوس‌سازی» با پیاده‌سازی از صفر (بدون استفاده از simulate پروژه)."""
import csv, os, statistics

HERE = os.path.dirname(os.path.abspath(__file__))
DATA = os.environ.get("GOLDPIN_DATA", "/home/user/data")
TICK = 0.01
HOLD = 240


def bars_of(path):
    out = []
    with open(path, encoding="utf-8") as f:
        for i, ln in enumerate(f):
            if i == 0: continue
            p = ln.strip().split(",")
            out.append((int(p[0]), float(p[1]), float(p[2]), float(p[3]), float(p[4])))
    return out


def setups_of(path):
    out = []
    with open(path, encoding="utf-8") as f:
        for r in csv.DictReader(f):
            if not r["hvTop"] or not r["sl"]: continue
            out.append((int(r["armBi"]), int(r["dir"]), float(r["hvTop"]), float(r["hvBot"]), float(r["sl"])))
    return out


def run(bf, sf, k, flip, sl_slip=1):
    bars = bars_of(bf); setups = setups_of(sf)
    vals = []
    for arm, d, top, bot, docsl in setups:
        entry = top + TICK if d == 1 else bot - TICK
        R = abs(entry - docsl)
        if R < 0.03: continue
        fill = None
        for i in range(arm + 1, min(arm + 61, len(bars))):
            t, o, h, l, c = bars[i]
            if (l <= entry) if d == 1 else (h >= entry):
                fill = i; break
        if fill is None: continue
        dd = -d if flip else d
        sl = entry - R * dd
        tp = entry + k * R * dd
        out = None
        for i in range(fill + 1, min(fill + HOLD + 1, len(bars))):
            t, o, h, l, c = bars[i]
            # اول حد ضرر
            s_lvl = sl - sl_slip * TICK * dd
            if (l <= s_lvl) if dd == 1 else (h >= s_lvl):
                out = (s_lvl - entry) / R * dd; break
            if (h >= tp) if dd == 1 else (l <= tp):
                out = k; break
        if out is None:
            t, o, h, l, c = bars[min(fill + HOLD, len(bars) - 1)]
            out = (c - entry) / R * dd
        vals.append(out)
    return vals


print(f"{'پنجره':<8}{'k':>5}{'جهت':>8}{'n':>5}{'نرخ برد':>9}{'انتظار R':>11}{'PF':>7}")
for lbl, bf, sf in [("C", "xau_m1_c.csv", "lab_setups_c.csv"),
                    ("B", "xau_m1_b.csv", "lab_setups_b.csv"),
                    ("A", "xau_m1.csv", "lab_setups.csv")]:
    for k in (1.0, 3.0):
        for flip in (False, True):
            v = run(bf, sf, k, flip)
            w = [x for x in v if x > 0.05]; l = [x for x in v if x <= 0.05]
            pf = sum(w) / -sum(l) if l and sum(l) < 0 else 99
            print(f"{lbl:<8}{k:>5.1f}{'معکوس' if flip else 'سند':>8}{len(v):>5}"
                  f"{len(w)/len(v)*100:>8.1f}%{statistics.mean(v):>+11.3f}{pf:>7.2f}")
