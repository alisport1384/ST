# -*- coding: utf-8 -*-
"""توزیع «حرکت مطلوب پیش از برخورد با حد ضرر» — پایهٔ انتخاب هدف.

محاسبهٔ صحیح (بدون ضریب جهت اشتباه):
  خرید: fav=(high−entry)/R ، adv=(low−entry)/R
  فروش: fav=(entry−low)/R  ، adv=(entry−high)/R
هر کندل: اگر adv ≤ −۱ ابتدا حد ضرر؛ وگرنه fav ثبت می‌شود.
"""
import os, statistics
import exit_lab as E

HERE = os.path.dirname(os.path.abspath(__file__))
DATA = os.environ.get("GOLDPIN_DATA", "/home/user/data")
TICK, HORIZON, ENTRY_WINDOW = E.TICK, 240, 60


def mfe_before_sl(bars, entry, R, d, fill_bi):
    best = -1.0
    for bi in range(fill_bi + 1, min(fill_bi + HORIZON + 1, len(bars))):
        b = bars[bi]
        fav = (b["h"] - entry) / R if d == 1 else (entry - b["l"]) / R
        adv = (b["l"] - entry) / R if d == 1 else (entry - b["h"]) / R
        if adv <= -1.0:
            return best
        best = max(best, fav)
    return best


def collect(stop_mode):
    res = {}
    for label, bf, sf in [("B", "xau_m1_b.csv", "lab_setups_b.csv"), ("A", "xau_m1.csv", "lab_setups.csv")]:
        bars = E.load_m1(os.path.join(DATA if ("xau_m1" in bf or "btc_m1" in bf) else HERE, bf))
        setups = [s for s in E.load_setups(os.path.join(HERE, sf)) if s["hvTop"] == s["hvTop"] and s["sl"] == s["sl"]]
        vals = []
        for s in setups:
            d = s["dir"]
            entry = s["hvTop"] + TICK if d == 1 else s["hvBot"] - TICK
            sl = E.stop_for(stop_mode, s, bars)
            if sl != sl:
                continue
            R = abs(entry - sl)
            if R < E.MIN_R:
                continue
            fill = None
            for bi in range(s["armBi"] + 1, min(s["armBi"] + ENTRY_WINDOW + 1, len(bars))):
                b = bars[bi]
                if (b["l"] <= entry) if d == 1 else (b["h"] >= entry):
                    fill = bi
                    break
            if fill is None:
                continue
            vals.append(mfe_before_sl(bars, entry, R, d, fill))
        res[label] = vals
    return res


def report(stop_mode):
    r = collect(stop_mode)
    allv = r["A"] + r["B"]
    out = [f"── حد ضرر: {stop_mode} (n={len(allv)}) ──",
           f"{'هدف':>7}{'P(هدف قبل از SL)':>20}{'انتظار R':>11}{'پنجرهٔ A':>10}{'پنجرهٔ B':>10}"]
    best = None
    for k in [0.5, 1, 1.5, 2, 2.5, 3, 4, 5, 6, 8, 10, 15]:
        p = sum(1 for x in allv if x >= k) / len(allv)
        pa = sum(1 for x in r["A"] if x >= k) / len(r["A"])
        pb = sum(1 for x in r["B"] if x >= k) / len(r["B"])
        exp = p * k - (1 - p)
        out.append(f"{k:>6.1f}R{p*100:>18.1f}%{exp:>11.3f}{pa*100:>9.1f}%{pb*100:>9.1f}%")
        if best is None or exp > best[1]:
            best = (k, exp)
    out.append(f"→ بهترین هدف واحد: {best[0]:.1f}R با انتظار {best[1]:+.3f}R")
    out.append(f"→ میانهٔ بیشترین حرکت مطلوب: {statistics.median(allv):.2f}R · میانگین: {statistics.mean(allv):.2f}R")
    return out, best


lines = ["═══ توزیع حرکت مطلوب پیش از حد ضرر، برای سه انتخاب حد ضرر ═══", ""]
best_all = []
for mode in ("doc", "lv", "atr1"):
    o, b = report(mode)
    lines += o + [""]
    best_all.append((mode, b[0], b[1]))
lines.append("═══ جمع‌بندی ═══")
for mode, k, e in best_all:
    lines.append(f"  حد ضرر {mode:<5}: بهترین هدف {k:.1f}R ⇒ انتظار {e:+.3f}R")
with open(os.path.join(HERE, "edge_test2.txt"), "w", encoding="utf-8") as f:
    f.write("\n".join(lines) + "\n")
print("\n".join(lines))
