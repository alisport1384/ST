# -*- coding: utf-8 -*-
"""آزمون «معکوس‌سازی تریگر ۲» — همان رویداد ورود (بازگشت قیمت به باکس حجم زیاد)، جهت مخالف.

نکتهٔ مهم پیاده‌سازی: شرط پر شدن باید با جهت سند سنجیده شود (قیمت به لبهٔ باکس برگردد)؛
فقط جهت معامله معکوس می‌شود. حد ضرر دو گزینه دارد:
  • F_mirror: آینهٔ حد ضرر سند (فاصلهٔ R یکسان)
  • F_box   : پشت باکس (۳ تیک آن‌طرف لبه)
"""
import json
import os
import statistics

import exit_lab as E

HERE = os.path.dirname(os.path.abspath(__file__))
DATA = os.environ.get("GOLDPIN_DATA", "/home/user/data")
TICK = E.TICK
ENTRY_WINDOW, MAX_HOLD = 60, 240


def load(bars_file, setups_file):
    bars = E.load_m1(os.path.join(DATA if bars_file.endswith(".csv") and ("xau_m1" in bars_file or "btc_m1" in bars_file) else HERE, bars_file))
    setups = [s for s in E.load_setups(os.path.join(HERE, setups_file))
              if s["hvTop"] == s["hvTop"] and s["sl"] == s["sl"]]
    out = []
    for s in setups:
        d = s["dir"]
        entry = s["hvTop"] + TICK if d == 1 else s["hvBot"] - TICK
        R = abs(entry - s["sl"])
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
        out.append((s, entry, R, fill))
    return bars, out


def simulate(entry, sl, legs, d, bars, fill_bi):
    rem, realized = 1.0, 0.0
    sl_now = sl
    hit = [False] * len(legs)
    R = abs(entry - sl)
    for bi in range(fill_bi, min(fill_bi + MAX_HOLD + 1, len(bars))):
        b = bars[bi]
        if bi > fill_bi:
            sl_level = sl_now - E.SL_SLIP * TICK * d
            if (b["l"] <= sl_level) if d == 1 else (b["h"] >= sl_level):
                realized += rem * (sl_level - entry) / R * d
                return realized
            for i, (lvl, frac) in enumerate(legs):
                if hit[i]:
                    continue
                if (b["h"] >= lvl) if d == 1 else (b["l"] <= lvl):
                    hit[i] = True
                    q = min(frac, rem)
                    realized += q * (lvl - entry) / R * d
                    rem -= q
                    if rem <= 1e-9:
                        return realized
    exit_px = bars[min(fill_bi + MAX_HOLD, len(bars) - 1)]["c"]
    return realized + rem * (exit_px - entry) / R * d


def build_legs(mode, entry, sl, R, d, k):
    if mode == "single":
        return [(entry + k * R * d, 1.0)]
    if mode == "ladder":
        return [(entry + 1 * R * d, 0.33), (entry + 2 * R * d, 0.33), (entry + 3 * R * d, 0.34)]
    raise ValueError(mode)


def evaluate(bars, items, flip, stops, mode="single", k=1.0):
    vals = []
    for s, entry, R, fill in items:
        d = -s["dir"] if flip else s["dir"]
        if stops == "mirror":
            sl = entry - R * d
        else:  # پشت لبهٔ باکس
            # معاملهٔ فروش: حد ضرر بالای باکس · معاملهٔ خرید: پایین باکس
            sl = (s["hvTop"] + 2 * TICK) if d == -1 else (s["hvBot"] - 2 * TICK)
            if (sl - entry) * d >= 0:      # حد ضرر سمت اشتباه ⇒ رد
                vals.append(0.0)
                continue
            R2 = abs(entry - sl)
        legs = build_legs(mode, entry, sl, abs(entry - sl), d, k)
        vals.append(simulate(entry, sl, legs, d, bars, fill))
    return vals


def stats(vals, risk=1.0):
    eq, peak, mdd = 1.0, 1.0, 0.0
    curve = [1.0]
    for v in vals:
        eq *= (1 + risk / 100.0 * v)
        curve.append(eq)
        peak = max(peak, eq)
        mdd = max(mdd, (peak - eq) / peak)
    wins = [v for v in vals if v > 0.05]
    losses = [v for v in vals if v <= 0.05]
    return dict(eq=eq, mdd=mdd * 100, wr=len(wins) / len(vals) * 100, exp=statistics.mean(vals),
                pf=(sum(wins) / -sum(losses) if losses and sum(losses) < 0 else 99.0), curve=curve)


W = [("B", "xau_m1_b.csv", "lab_setups_b.csv"), ("A", "xau_m1.csv", "lab_setups.csv")]
D = {}
for label, bf, sf in W:
    bars, items = load(bf, sf)
    D[label] = {"bars_items": (bars, items)}
    D[label]["doc"] = evaluate(bars, items, False, "mirror", "single", 1.0)
    D[label]["fade_mirror_1R"] = evaluate(bars, items, True, "mirror", "single", 1.0)
    D[label]["fade_box_1R"] = evaluate(bars, items, True, "box", "single", 1.0)
    D[label]["fade_box_ladder"] = evaluate(bars, items, True, "box", "ladder")

lines = []
P = lines.append
P("═══ آزمون معکوس‌سازی تریگر ۲ (رویداد ورود یکسان، جهت مخالف) ═══")
P("")
for lbl in ("B", "A"):
    P(f"── پنجرهٔ {lbl} ──")
    P(f"{'پیکربندی':<22}{'n':>5}{'نرخ برد':>9}{'انتظار R':>10}{'PF':>7}{'سرمایه':>9}{'افت':>8}")
    for m in ("doc", "fade_mirror_1R", "fade_box_1R", "fade_box_ladder"):
        st = stats(D[lbl][m])
        P(f"{m:<22}{len(D[lbl][m]):>5}{st['wr']:>8.1f}%{st['exp']:>+10.3f}{st['pf']:>7.2f}{st['eq']:>8.2f}x{st['mdd']:>7.1f}%")
    P("")

comb = {m: D["B"][m] + D["A"][m] for m in ("doc", "fade_mirror_1R", "fade_box_1R", "fade_box_ladder")}
P("── ترکیبی (B سپس A) ──")
P(f"{'پیکربندی':<22}{'n':>5}{'نرخ برد':>9}{'انتظار R':>10}{'PF':>7}{'سرمایه':>9}{'افت':>8}")
for m in comb:
    st = stats(comb[m])
    P(f"{m:<22}{len(comb[m]):>5}{st['wr']:>8.1f}%{st['exp']:>+10.3f}{st['pf']:>7.2f}{st['eq']:>8.2f}x{st['mdd']:>7.1f}%")
P("")

P("── جاروب هدف ثابت kR برای «معکوس با حد ضرر پشت باکس» (ترکیبی) ──")
P(f"{'هدف':>7}{'نرخ برد':>10}{'انتظار R':>11}{'PF':>7}{'سرمایه':>9}{'افت':>8}")
best = None
for k in [0.5, 0.75, 1.0, 1.5, 2.0, 2.5, 3.0]:
    vals = []
    for label, bf, sf in W:
        bars, items = D[label]["bars_items"]
        vals += evaluate(bars, items, True, "box", "single", k)
    st = stats(vals)
    P(f"{k:>6.2f}R{st['wr']:>9.1f}%{st['exp']:>+11.3f}{st['pf']:>7.2f}{st['eq']:>8.2f}x{st['mdd']:>7.1f}%")
    if best is None or st["eq"] > best[1]:
        best = (k, st["eq"], st["exp"])
P("")
P(f"بهترین هدف: {best[0]:.2f}R ⇒ سرمایه {best[1]:.2f}x · انتظار {best[2]:+.3f}R")

curves = {m: stats(comb[m])["curve"] for m in comb}
json.dump(curves, open(os.path.join(HERE, "fade_curves.json"), "w"))
with open(os.path.join(HERE, "fade_lab.txt"), "w", encoding="utf-8") as f:
    f.write("\n".join(lines) + "\n")
print("\n".join(lines))
