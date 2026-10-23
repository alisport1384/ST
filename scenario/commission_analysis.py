# -*- coding: utf-8 -*-
"""اثر کمیسیون/اسپرد روی گزینهٔ ۳ — و انتخاب هدف kR مقاوم.

هزینهٔ رفت‌وبرگشت بر حسب دلار به‌ازای هر اونس (c) به «R» تبدیل می‌شود: هزینه = c / R
چون R خودش ~۱ دلار است، هزینهٔ ۰٫۲۰ دلاری معادل ۰٫۲R است — یعنی بزرگ‌ترین تهدید این طرح.
"""
import csv, os, statistics

HERE = os.path.dirname(os.path.abspath(__file__))
DATA = os.environ.get("GOLDPIN_DATA", "/home/user/data")
TICK = 0.01
HOLD = 240
W = [("C", "xau_m1_c.csv", "lab_setups_c.csv"),
     ("B", "xau_m1_b.csv", "lab_setups_b.csv"),
     ("A", "xau_m1.csv", "lab_setups.csv")]


def bars_of(p):
    out = []
    with open(p, encoding="utf-8") as f:
        for i, ln in enumerate(f):
            if i == 0: continue
            q = ln.strip().split(",")
            out.append((int(q[0]), float(q[1]), float(q[2]), float(q[3]), float(q[4])))
    return out


def setups_of(p):
    out = []
    with open(p, encoding="utf-8") as f:
        for r in csv.DictReader(f):
            if not r["hvTop"] or not r["sl"]: continue
            out.append((int(r["armBi"]), int(r["dir"]), float(r["hvTop"]), float(r["hvBot"]), float(r["sl"])))
    return out


def run(bf, sf, k, flip, cost):
    bars, setups = bars_of(bf), setups_of(sf)
    vals, rs = [], []
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
        sl, tp = entry - R * dd, entry + k * R * dd
        out = None
        for i in range(fill + 1, min(fill + HOLD + 1, len(bars))):
            t, o, h, l, c = bars[i]
            s_lvl = sl - TICK * dd
            if (l <= s_lvl) if dd == 1 else (h >= s_lvl):
                out = (s_lvl - entry) / R * dd; break
            if (h >= tp) if dd == 1 else (l <= tp):
                out = k; break
        if out is None:
            t, o, h, l, c = bars[min(fill + HOLD, len(bars) - 1)]
            out = (c - entry) / R * dd
        vals.append(out - cost / R)
        rs.append(R)
    return vals, rs


allR = []
raw = {}
for lbl, bf, sf in W:
    _, rr = run(bf, sf, 1.0, True, 0.0)
    allR += rr
print("توزیع فاصلهٔ حد ضرر (R) بر حسب دلار:")
for q in (10, 25, 50, 75, 90):
    print(f"  صدک {q}: {sorted(allR)[int(q/100*len(allR))-1]:.3f}")
print(f"  میانگین: {statistics.mean(allR):.3f} · میانه: {statistics.median(allR):.3f}\n")

lines = ["═══ اثر کمیسیون/اسپرد روی گزینهٔ ۳ ═══",
         f"R (فاصلهٔ حد ضرر): میانه {statistics.median(allR):.2f}$ · میانگین {statistics.mean(allR):.2f}$", "",
         "انتظار خالص (R) پس از کسر هزینهٔ رفت‌وبرگشت — مجموع سه پنجره (۳۸۸ ستاپ)", "",
         f"{'هزینه':>10}" + "".join(f"{'هدف '+str(k)+'R':>13}" for k in (1.0, 1.5, 2.0, 3.0)) + f"{'سند (فیب)':>13}"]
lines.append("─" * 74)
for cost in (0.0, 0.05, 0.10, 0.15, 0.20, 0.30, 0.40):
    row = f"{cost:>9.2f}$"
    for k in (1.0, 1.5, 2.0, 3.0):
        vals = []
        for lbl, bf, sf in W:
            v, _ = run(bf, sf, k, True, cost)
            vals += v
        row += f"{statistics.mean(vals):>+13.3f}"
    vd = []
    for lbl, bf, sf in W:
        v, _ = run(bf, sf, 1.0, False, cost)
        vd += v
    row += f"{statistics.mean(vd):>+13.3f}"
    lines.append(row)

lines += ["", "نرخ برد لازم برای سربه‌سر با هزینه (هدف kR):  P* = (1 + cost/R) / (1 + k)"]
for cost in (0.0, 0.10, 0.20):
    row = f"  هزینه {cost:.2f}$: "
    for k in (1.0, 1.5, 2.0, 3.0):
        cR = cost / statistics.median(allR)
        row += f"k={k}: {(1 + cR) / (1 + k) * 100:.0f}%   "
    lines.append(row)
lines += ["", "نرخ برد واقعی نسخهٔ معکوس در سه پنجره: ۵۵٪ · ۶۰٪ · ۶۴٪"]

with open(os.path.join(HERE, "commission_analysis.txt"), "w", encoding="utf-8") as f:
    f.write("\n".join(lines) + "\n")
print("\n".join(lines))
