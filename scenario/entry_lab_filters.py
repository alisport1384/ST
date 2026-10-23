# -*- coding: utf-8 -*-
"""فیلترهای کیفیت برای ورود تریگر ۲ — کدام «کندل حجم زیاد» ارزش معامله دارد؟

منابع وب (VSA، breakout-confirmation، liquidity-sweep) می‌گویند:
  • حجم باید ≥۱٫۵ برابر میانگین باشد، نه فقط «بیشتر از کندل قبل»؛
  • کندل تأیید باید بدنهٔ ≥۵۰٪ دامنه و کلوز نزدیک سقف داشته باشد؛
  • در پنجرهٔ نقدینگی لندن/نیویورک کار شود؛
  • لبهٔ باکس حجم زیاد باید دور از «شکار حد ضرر» باشد.
این اسکریپت همان فیلترها را روی ستاپ‌های واقعی موتور می‌سنجد.
"""
import os
import statistics

import entry_lab as L

HERE = os.path.dirname(os.path.abspath(__file__))
DATA = os.environ.get("GOLDPIN_DATA", "/home/user/data")
bars = L.load_m1(os.path.join(DATA, "xau_m1.csv"))
raw = L.load_setups(os.path.join(HERE, "lab_setups.csv"))
usable = [s for s in raw if s["hvTop"] == s["hvTop"] and s["sl"] == s["sl"]]


def hv_bar(s):
    """کندل حجم زیاد = کندلی که باکسش ساخته؛ باکس روی همان کندل است، پس نزدیک‌ترین کندل تا لحظهٔ مسلح‌شدن."""
    top, bot = max(s["hvTop"], s["hvBot"]), min(s["hvTop"], s["hvBot"])
    for bi in range(s["armBi"], max(s["armBi"] - 6, 0), -1):
        b = bars[bi]
        if abs(b["h"] - top) < 1e-6 and abs(b["l"] - bot) < 1e-6:
            return b
    return None


def feats(s):
    b = hv_bar(s)
    out = {}
    if b:
        rng = b["h"] - b["l"]
        out["body"] = abs(b["c"] - b["o"]) / rng if rng > 0 else 0.0
        prev = [x["v"] for x in bars[max(0, b["bi"] - 20):b["bi"]]]
        out["volx"] = b["v"] / statistics.median(prev) if prev and statistics.median(prev) > 0 else float("nan")
    out["hour"] = (bars[s["armBi"]]["t"] // 3600000) % 24
    out["boxUSD"] = abs(s["hvTop"] - s["hvBot"])
    return out


FILTERS = {
    "همه (بدون فیلتر)": lambda f: True,
    "حجم ≥ ۱٫۵× میانه": lambda f: f.get("volx", 0) >= 1.5,
    "بدنه ≥ ۵۰٪ دامنه": lambda f: f.get("body", 0) >= 0.5,
    "حجم ≥۱٫۵× و بدنه ≥۵۰٪": lambda f: f.get("volx", 0) >= 1.5 and f.get("body", 0) >= 0.5,
    "درپنجرهٔ ۱۲–۱۶ UTC": lambda f: 12 <= f["hour"] < 16,
    "خارج از ۱۲–۱۶ UTC": lambda f: not (12 <= f["hour"] < 16),
    "باکس بزرگ‌تر از میانه": None,   # بعد از محاسبه پر می‌شود
}


def run(subset, name):
    rows = []
    for s in subset:
        d, arm_bi = s["dir"], s["armBi"]
        top, bot = s["hvTop"], s["hvBot"]
        edge = top + L.TICK if d == 1 else bot - L.TICK
        arm = bars[arm_bi]
        # روش سند
        r0 = L.simulate(edge, s["sl"], L.legs_for(edge, s["tp1"], s["tp2"], s["tpX"]), d, bars, arm_bi)
        # روش ترکیبی: لیمیت ۵ کندل، بعد بازار
        r6 = L.simulate(edge, s["sl"], L.legs_for(edge, s["tp1"], s["tp2"], s["tpX"]), d, bars, arm_bi, horizon=5)
        if r6 is None:
            b5 = bars[min(arm_bi + 5, len(bars) - 1)]
            px = b5["c"] + L.MKT_SLIP * L.TICK * d
            r6 = L.simulate(px, s["sl"], L.legs_for(px, s["tp1"], s["tp2"], s["tpX"]), d, bars, arm_bi + 5, limit_ok=False)
        # ورود بازار فوری
        px4 = arm["c"] + L.MKT_SLIP * L.TICK * d
        r4 = L.simulate(px4, s["sl"], L.legs_for(px4, s["tp1"], s["tp2"], s["tpX"]), d, bars, arm_bi, limit_ok=False)
        rows.append((r0["pnl"] if r0 else 0.0, r4["pnl"] if r4 else 0.0, r6["pnl"] if r6 else 0.0))
    if not rows:
        return None
    n = len(rows)
    return dict(name=name, n=n,
                a0=sum(r[0] for r in rows) / n,
                a4=sum(r[1] for r in rows) / n,
                a6=sum(r[2] for r in rows) / n,
                a6tot=sum(r[2] for r in rows))


med_box = statistics.median(abs(s["hvTop"] - s["hvBot"]) for s in usable)
FILTERS["باکس بزرگ‌تر از میانه"] = lambda f: f["boxUSD"] > med_box

feat = {s["id"]: feats(s) for s in usable}
lines = []
P = lines.append
P("═══ فیلترهای کیفیت ورود تریگر ۲ (دادهٔ واقعی، ۱۷۵ ستاپ) ═══")
P("میانهٔ ارتفاع باکس حجم زیاد: %.3f دلار · میانهٔ کندل M1 طلا در این پنجره: %.3f دلار" % (
    med_box, statistics.median(b["h"] - b["l"] for b in bars[:20000])))
P("")
head = f"{'فیلتر':<26}{'n':>5}{'R/ستاپ سند':>12}{'R/ستاپ بازار':>14}{'R/ستاپ ترکیبی':>15}{'مجموع R ترکیبی':>16}"
P(head)
P("─" * len(head))
best = None
for name, fn in FILTERS.items():
    sub = [s for s in usable if fn(feat[s["id"]])]
    r = run(sub, name)
    if not r:
        continue
    P(f"{name:<26}{r['n']:>5}{r['a0']:>12.3f}{r['a4']:>14.3f}{r['a6']:>15.3f}{r['a6tot']:>16.1f}")
    if best is None or r["a6"] > best["a6"]:
        best = r
P("")
P(f"بهترین ترکیب: «{best['name']}» با روش ورود ترکیبی — R/ستاپ {best['a6']:+.3f} روی {best['n']} ستاپ")

with open(os.path.join(HERE, "entry_lab_filters.txt"), "w", encoding="utf-8") as f:
    f.write("\n".join(lines) + "\n")
print("\n".join(lines))
