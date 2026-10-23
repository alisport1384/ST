# -*- coding: utf-8 -*-
"""آیا «فاصلهٔ حد ضرر نسبت به نویز» تعیین‌کنندهٔ نتیجه است؟
فرضیه: وقتی ارتفاع باکس حجم زیاد (≈ فاصلهٔ ورود تا حد ضرر) کوچک‌تر از نویز M1 باشد،
هر ورودی استاپ می‌شود؛ وقتی بزرگ‌تر باشد، قاعدهٔ سند نفس می‌کشد."""
import os, statistics
import entry_lab as L

HERE = os.path.dirname(os.path.abspath(__file__))
DATA = os.environ.get("GOLDPIN_DATA", "/home/user/data")
WINDOWS = [("A", "xau_m1.csv", "lab_setups.csv"), ("B", "xau_m1_b.csv", "lab_setups_b.csv")]
BUCKETS = [(0.0, 1.0), (1.0, 1.5), (1.5, 2.5), (2.5, 99.0)]

def atr(bars, bi, n=60):
    hs = bars[max(0, bi - n):bi]
    return statistics.median(b["h"] - b["l"] for b in hs) if hs else 0.0

lines = ["═══ نسبت حد ضرر به نویز M1 ═══",
         f"{'پنجره':<6}{'نسبت SL/ATR':<14}{'n':>5}{'R/ستاپ سند':>12}{'نرخ برد':>9}{'R/ستاپ بازار':>14}", "─" * 62]
for label, bf, sf in WINDOWS:
    bars = L.load_m1(os.path.join(DATA if ("xau_m1" in bf or "btc_m1" in bf) else HERE, bf))
    setups = [s for s in L.load_setups(os.path.join(HERE, sf)) if s["hvTop"] == s["hvTop"] and s["sl"] == s["sl"]]
    recs = []
    for s in setups:
        d, top, bot, arm = s["dir"], s["hvTop"], s["hvBot"], s["armBi"]
        risk = abs((top + L.TICK if d == 1 else bot - L.TICK) - s["sl"])
        a = atr(bars, arm)
        ratio = risk / a if a > 0 else 0
        edge = top + L.TICK if d == 1 else bot - L.TICK
        r0 = L.simulate(edge, s["sl"], L.legs_for(edge, s["tp1"], s["tp2"], s["tpX"]), d, bars, arm)
        px4 = bars[arm]["c"] + L.MKT_SLIP * L.TICK * d
        r4 = L.simulate(px4, s["sl"], L.legs_for(px4, s["tp1"], s["tp2"], s["tpX"]), d, bars, arm, limit_ok=False)
        recs.append((ratio, r0["pnl"] if r0 else 0.0, (r0 and r0["pnl"] > 0.05), r4["pnl"] if r4 else 0.0))
    for lo, hi in BUCKETS:
        sub = [r for r in recs if lo <= r[0] < hi]
        if not sub:
            continue
        n = len(sub)
        lines.append(f"{label:<6}{f'{lo}–{hi}×':<14}{n:>5}{sum(r[1] for r in sub)/n:>12.3f}"
                     f"{sum(1 for r in sub if r[2])/n*100:>8.0f}%{sum(r[3] for r in sub)/n:>14.3f}")
    allR = [r[1] for r in recs]
    lines.append(f"{label:<6}{'همه':<14}{len(allR):>5}{sum(allR)/len(allR):>12.3f}"
                 f"{sum(1 for r in recs if r[2])/len(recs)*100:>8.0f}%{sum(r[3] for r in recs)/len(recs):>14.3f}")
    lines.append("")

# آزمون همبستگی: آیا نسبت بالاتر ⇒ نتیجهٔ بهتر؟
import math
for label, bf, sf in WINDOWS:
    bars = L.load_m1(os.path.join(DATA if ("xau_m1" in bf or "btc_m1" in bf) else HERE, bf))
    setups = [s for s in L.load_setups(os.path.join(HERE, sf)) if s["hvTop"] == s["hvTop"] and s["sl"] == s["sl"]]
    xs, ys = [], []
    for s in setups:
        d, top, bot, arm = s["dir"], s["hvTop"], s["hvBot"], s["armBi"]
        edge = top + L.TICK if d == 1 else bot - L.TICK
        risk = abs(edge - s["sl"]); a = atr(bars, arm)
        if a <= 0: continue
        r = L.simulate(edge, s["sl"], L.legs_for(edge, s["tp1"], s["tp2"], s["tpX"]), d, bars, arm)
        xs.append(risk / a); ys.append(r["pnl"] if r else 0.0)
    n = len(xs); mx, my = statistics.mean(xs), statistics.mean(ys)
    num = sum((x - mx) * (y - my) for x, y in zip(xs, ys))
    den = math.sqrt(sum((x - mx) ** 2 for x in xs) * sum((y - my) ** 2 for y in ys))
    lines.append(f"همبستگی نسبت SL/ATR با R در پنجرهٔ {label}: r = {num/den:+.3f} (n={n})")

with open(os.path.join(HERE, "entry_noise_ratio.txt"), "w", encoding="utf-8") as f:
    f.write("\n".join(lines) + "\n")
print("\n".join(lines))
