# -*- coding: utf-8 -*-
"""تحلیل تجمیعی دو پنجرهٔ مستقل + آزمون معناداری زوجی (bootstrap) برای روش‌های ورود.

هدف: جدا کردن «اثر واقعی قاعدهٔ ورود» از «نوسان رژیم بازار».
"""
import os
import random
import statistics

import entry_lab as L

HERE = os.path.dirname(os.path.abspath(__file__))
DATA = os.environ.get("GOLDPIN_DATA", "/home/user/data")
WINDOWS = [
    ("پنجرهٔ A (۱۲۰ روز اخیر)", "xau_m1.csv", "lab_setups.csv"),
    ("پنجرهٔ B (۱۲۰ روز قبل‌تر)", "xau_m1_b.csv", "lab_setups_b.csv"),
]

random.seed(11)


def boot_paired(diffs, n=8000):
    if len(diffs) < 8:
        return (float("nan"), float("nan"), float("nan"))
    means = []
    for _ in range(n):
        means.append(sum(random.choice(diffs) for _ in diffs) / len(diffs))
    means.sort()
    lo, hi = means[int(0.025 * n)], means[int(0.975 * n)]
    neg = sum(1 for m in means if m <= 0) / n
    p = 2 * min(neg, 1 - neg)
    return lo, hi, p


def variants_for(s, bars):
    """قیمت ورود + حد ضرر هر روش (حد ضرر پیش‌فرض = حد ضرر سند)."""
    d, top, bot = s["dir"], s["hvTop"], s["hvBot"]
    arm = bars[s["armBi"]]
    edge = top + L.TICK if d == 1 else bot - L.TICK
    lvt, lvb = s.get("lvTop", float("nan")), s.get("lvBot", float("nan"))
    out = {
        "A0_edge": (edge, s["sl"], True, 60),
        "A1_edge_d3": (edge - 3 * L.TICK * d, s["sl"], True, 60),
        "A4_market": (arm["c"] + L.MKT_SLIP * L.TICK * d, s["sl"], False, 60),
        "A5_next_close": (None, s["sl"], False, 60),
        "A6_hybrid": (edge, s["sl"], "hybrid", 5),
    }
    return out


def simulate_variant(name, s, bars):
    d, arm_bi = s["dir"], s["armBi"]
    top, bot = s["hvTop"], s["hvBot"]
    edge = top + L.TICK if d == 1 else bot - L.TICK
    lvt, lvb = s["lvTop"], s["lvBot"]
    if name == "A0_edge":
        px, sl, lim = edge, s["sl"], True
        return L.simulate(px, sl, L.legs_for(px, s["tp1"], s["tp2"], s["tpX"]), d, bars, arm_bi, limit_ok=lim)
    if name == "A1_edge_d3":
        px = edge - 3 * L.TICK * d
        return L.simulate(px, s["sl"], L.legs_for(px, s["tp1"], s["tp2"], s["tpX"]), d, bars, arm_bi, limit_ok=True)
    if name == "A4_market":
        px = bars[arm_bi]["c"] + L.MKT_SLIP * L.TICK * d
        return L.simulate(px, s["sl"], L.legs_for(px, s["tp1"], s["tp2"], s["tpX"]), d, bars, arm_bi, limit_ok=False)
    if name == "A5_next_close":
        nb = bars[min(arm_bi + 1, len(bars) - 1)]
        px = nb["c"] + L.MKT_SLIP * L.TICK * d
        return L.simulate(px, s["sl"], L.legs_for(px, s["tp1"], s["tp2"], s["tpX"]), d, bars, arm_bi + 1, limit_ok=False)
    if name == "A6_hybrid":
        r = L.simulate(edge, s["sl"], L.legs_for(edge, s["tp1"], s["tp2"], s["tpX"]), d, bars, arm_bi,
                       limit_ok=True, horizon=5)
        if r is not None:
            return r
        b5 = bars[min(arm_bi + 5, len(bars) - 1)]
        px = b5["c"] + L.MKT_SLIP * L.TICK * d
        return L.simulate(px, s["sl"], L.legs_for(px, s["tp1"], s["tp2"], s["tpX"]), d, bars, arm_bi + 5, limit_ok=False)
    if name == "A8_fastfail":
        # قاعدهٔ سند + «شکست سریع»: اگر کندلِ پرشدن، کل باکس را در جهت مخالف ببندد،
        # همان کندل با کلوز خارج می‌شویم (به‌جای انتظار برای حد ضرر پشت باکس).
        px, sl = edge, s["sl"]
        r = L.simulate(px, sl, L.legs_for(px, s["tp1"], s["tp2"], s["tpX"]), d, bars, arm_bi, limit_ok=True)
        if r is None:
            return None
        # بازبینی: کندل پرشدن را پیدا کن
        for bi in range(arm_bi + 1, min(arm_bi + 61, len(bars))):
            b = bars[bi]
            if (b["l"] <= px) if d == 1 else (b["h"] >= px):
                through = (b["c"] < bot) if d == 1 else (b["c"] > top)
                if through and r["reason"] in ("SL", "EXPIRY"):
                    loss = (b["c"] - px) / abs(px - sl) * d
                    return dict(pnl=loss, mfe=r["mfe"], bars=bi - arm_bi, reason="FASTFAIL")
                return r
        return r
    if name == "A7_lv_retest":
        # ورود روی لبهٔ نزدیک باکس ولوم کم، حد ضرر پشت باکس ولوم کم (ریسک پهن‌تر و بیرون از نویز)
        if lvt != lvt or lvb != lvb:
            return None
        px = lvb + L.TICK if d == 1 else lvt - L.TICK
        sl = (lvb if d == 1 else lvt) - 2 * L.TICK * d
        return L.simulate(px, sl, L.legs_for(px, s["tp1"], s["tp2"], s["tpX"]), d, bars, arm_bi, limit_ok=True)
    return None


NAMES = ["A0_edge", "A1_edge_d3", "A4_market", "A5_next_close", "A6_hybrid", "A7_lv_retest", "A8_fastfail"]

pooled = {n: [] for n in NAMES}
per_window = {}
for label, bars_file, setups_file in WINDOWS:
    bars = L.load_m1(os.path.join(DATA if bars_file.endswith(".csv") and ("xau_m1" in bars_file or "btc_m1" in bars_file) else HERE, bars_file))
    setups = [s for s in L.load_setups(os.path.join(HERE, setups_file))
              if s["hvTop"] == s["hvTop"] and s["sl"] == s["sl"]]
    per_window[label] = {}
    for n in NAMES:
        vals = []
        for s in setups:
            r = simulate_variant(n, s, bars)
            vals.append(r["pnl"] if r else 0.0)
        per_window[label][n] = vals
        pooled[n].extend(vals)

lines = []
P = lines.append
P("═══ تحلیل تجمیعی دو پنجرهٔ مستقل — روش ورود تریگر ۲ ═══")
P("")
head = f"{'روش':<16}" + "".join(f"{lbl[:16]:>18}" for lbl, _, _ in WINDOWS) + f"{'تجمیعی':>12}"
P(head)
P("─" * len(head))
for n in NAMES:
    row = f"{n:<16}"
    for lbl, _, _ in WINDOWS:
        v = per_window[lbl][n]
        row += f"{(sum(v)/len(v)):>+18.3f}"
    v = pooled[n]
    row += f"{(sum(v)/len(v)):>+12.3f}"
    P(row + "   (R/ستاپ)   میانه تجمیعی: %+.3f" % statistics.median(v))
P("")
P(f"تعداد ستاپ: " + " · ".join(f"{lbl.split('(')[0].strip()} = {len(per_window[lbl]['A0_edge'])}" for lbl, _, _ in WINDOWS)
  + f" · تجمیعی = {len(pooled['A0_edge'])}")
P("")
P("── آزمون زوجی نسبت به قاعدهٔ سند (A0) روی نمونهٔ تجمیعی ──")
P(f"{'روش':<16}{'Δ میانگین R':>14}{'CI95':>22}{'p تقریبی':>11}{'نتیجه':>16}")
P("─" * 79)
base = pooled["A0_edge"]
for n in NAMES[1:]:
    diffs = [a - b for a, b in zip(pooled[n], base)]
    lo, hi, p = boot_paired(diffs)
    verdict = "معنادار نیست" if p > 0.05 else ("بهتر" if statistics.mean(diffs) > 0 else "بدتر")
    P(f"{n:<16}{statistics.mean(diffs):>+14.3f}{f'[{lo:+.2f} , {hi:+.2f}]':>22}{p:>11.3f}{verdict:>16}")

P("")
P("── چرا نتیجه بین دو پنجره برمی‌گردد؟ (اثر رژیم) ──")
for label, bars_file, setups_file in WINDOWS:
    v = per_window[label]
    a0 = v["A0_edge"]
    wins = sum(1 for x in a0 if x > 0.05)
    P(f"{label}: نرخ برد قاعدهٔ سند {wins/len(a0)*100:.0f}% · R/ستاپ {sum(a0)/len(a0):+.3f} · "
      f"اختلاف بازار−سند {sum(v['A4_market'])/len(v['A4_market']) - sum(a0)/len(a0):+.3f}R")
P("")
P("جمع‌بندی عددی:")
best_pool = max(NAMES, key=lambda n: sum(pooled[n]) / len(pooled[n]))
P(f"  بهترین روش در نمونهٔ تجمیعی: {best_pool} با {sum(pooled[best_pool])/len(pooled[best_pool]):+.3f}R/ستاپ "
  f"(قاعدهٔ سند: {sum(base)/len(base):+.3f}R/ستاپ)")

with open(os.path.join(HERE, "entry_lab_pooled.txt"), "w", encoding="utf-8") as f:
    f.write("\n".join(lines) + "\n")
print("\n".join(lines))
