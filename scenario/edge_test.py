# -*- coding: utf-8 -*-
"""آزمون «لبه» (edge) مستقل از هر قاعدهٔ خروج.

پرسش: از لحظهٔ پر شدن ورود، احتمال اینکه قیمت ابتدا +kR برود و بعد −1R (یا برعکس) چقدر است؟
اگر P(+1R before −1R) ≈ ۵۰٪ باشد، هیچ ترکیب حد ضرر/حد سودی نمی‌تواند سیستم را مثبت کند،
چون خودِ سیگنال اطلاعاتی دربارهٔ جهت آینده ندارد.
"""
import os, statistics
import exit_lab as E

HERE = os.path.dirname(os.path.abspath(__file__))
DATA = os.environ.get("GOLDPIN_DATA", "/home/user/data")
TICK = E.TICK
HORIZON = 240
ENTRY_WINDOW = 60
LEVELS = [0.5, 1.0, 1.5, 2.0, 3.0]


def first_touch(bars, entry, R, d, start_bi):
    """برمی‌گرداند: BEST = بیشترین R مطلوب پیش از برخورد −1R، و برخورد با −1R یا نه."""
    for bi in range(start_bi + 1, min(start_bi + HORIZON + 1, len(bars))):
        b = bars[bi]
        fav = (b["h"] - entry) / R * d if d == 1 else (entry - b["l"]) / R * d
        adv = (b["l"] - entry) / R * d if d == 1 else (entry - b["h"]) / R * d
        if adv <= -1.0:
            return -1.0, "SL", bi - start_bi
        if fav >= 3.0:
            return 3.0, "3R", bi - start_bi
    b = bars[min(start_bi + HORIZON, len(bars) - 1)]
    return (b["c"] - entry) / R * d, "TIME", HORIZON


rows = []
for label, bf, sf in [("B", "xau_m1_b.csv", "lab_setups_b.csv"), ("A", "xau_m1.csv", "lab_setups.csv")]:
    bars = E.load_m1(os.path.join(DATA if ("xau_m1" in bf or "btc_m1" in bf) else HERE, bf))
    setups = [s for s in E.load_setups(os.path.join(HERE, sf)) if s["hvTop"] == s["hvTop"] and s["sl"] == s["sl"]]
    for s in setups:
        d = s["dir"]
        entry = s["hvTop"] + TICK if d == 1 else s["hvBot"] - TICK
        R = abs(entry - s["sl"])
        if R < E.MIN_R:
            continue
        # لحظهٔ پر شدن
        fill_bi = None
        for bi in range(s["armBi"] + 1, min(s["armBi"] + ENTRY_WINDOW + 1, len(bars))):
            b = bars[bi]
            if (b["l"] <= entry) if d == 1 else (b["h"] >= entry):
                fill_bi = bi
                break
        if fill_bi is None:
            continue
        best, reason, dur = first_touch(bars, entry, R, d, fill_bi)
        rows.append(dict(win=label, id=s["id"], d=d, R=R, best=best, reason=reason, dur=dur))

lines = []
P = lines.append
P("═══ آزمون لبهٔ سیگنال تریگر ۲ (مستقل از قاعدهٔ خروج) ═══")
P(f"n = {len(rows)} معاملهٔ پر شده · افق {HORIZON} کندل · R = فاصلهٔ ورود تا حد ضرر سند")
P("")
P(f"{'هدف':<8}{'P(هدف پیش از حد ضرر)':>24}{'در جهت سند':>14}{'در جهت مخالف':>16}")
P("─" * 62)
for k in LEVELS:
    hit = sum(1 for r in rows if r["best"] >= k - 1e-9)
    P(f"{k:>4.1f}R{'':<3}{hit/len(rows)*100:>20.1f}%{'':<4}{'':>14}{'':>16}")
P("")
P("توزیع نتیجهٔ «اولین برخورد» در جهت سند:")
for reason in ("SL", "3R", "TIME"):
    c = sum(1 for r in rows if r["reason"] == reason)
    P(f"  {reason:<6}{c:>5}  ({c/len(rows)*100:.1f}%)")
P("")
per = {}
for label in ("A", "B"):
    sub = [r for r in rows if r["win"] == label]
    per[label] = sub
    P(f"پنجرهٔ {label}: n={len(sub)} · P(+1R قبل از −1R) = {sum(1 for r in sub if r['best']>=1.0)/len(sub)*100:.1f}% · "
      f"P(+2R قبل از −1R) = {sum(1 for r in sub if r['best']>=2.0)/len(sub)*100:.1f}% · "
      f"میانهٔ بهترین حرکت مطلوب = {statistics.median([min(r['best'],3.0) for r in sub if r['best']>0] or [0]):.2f}R")
P("")
mx = [min(r["best"], 3.0) for r in rows if r["best"] > 0]
P(f"میانهٔ MFE (بر حسب R) وقتی معامله حد ضرر نمی‌خورد: {statistics.median(mx) if mx else 0:.2f}R")
P(f"سهم معاملاتی که حتی ۰٫۵R هم در جهت سند نرفتند: {sum(1 for r in rows if r['best']<0.5)/len(rows)*100:.1f}%")

with open(os.path.join(HERE, "edge_test.txt"), "w", encoding="utf-8") as f:
    f.write("\n".join(lines) + "\n")
print("\n".join(lines))
