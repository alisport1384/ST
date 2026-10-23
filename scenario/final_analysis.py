# -*- coding: utf-8 -*-
"""تحلیل نهایی: جاروب هدف، آزمون حساسیت هزینه، و نمودار رشد سرمایه."""
import json, os, statistics
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
import fade_lab as F
import exit_lab as E

HERE = os.path.dirname(os.path.abspath(__file__))
DATA = os.environ.get("GOLDPIN_DATA", "/home/user/data")
W = [("C", "xau_m1_c.csv", "lab_setups_c.csv"),
     ("B", "xau_m1_b.csv", "lab_setups_b.csv"),
     ("A", "xau_m1.csv", "lab_setups.csv")]
loaded = {lbl: F.load(bf, sf) for lbl, bf, sf in W}


def eq_stats(vals):
    eq, peak, mdd, curve = 1.0, 1.0, 0.0, [1.0]
    for v in vals:
        eq *= 1 + 0.01 * v
        curve.append(eq)
        peak = max(peak, eq)
        mdd = max(mdd, (peak - eq) / peak)
    wins = [v for v in vals if v > 0.05]
    losses = [v for v in vals if v <= 0.05]
    return dict(eq=eq, mdd=mdd * 100, wr=len(wins) / len(vals) * 100, exp=statistics.mean(vals),
                pf=sum(wins) / -sum(losses) if losses and sum(losses) < 0 else 99.0, curve=curve)


# ── ۱) جاروب هدف روی سه پنجره ────────────────────────────────────────────────
lines = ["═══ جاروب هدف برای نسخهٔ معکوس (سه پنجره) ═══",
         f"{'هدف':>7}" + "".join(f"{lbl:>10}" for lbl, _, _ in W) + f"{'تجمیعی':>10}{'سرمایه':>9}{'افت':>8}",
         "─" * 62]
best = None
for k in [0.5, 0.75, 1.0, 1.25, 1.5, 2.0, 2.5, 3.0]:
    per, allv = {}, []
    for lbl, bf, sf in W:
        bars, items = loaded[lbl]
        v = F.evaluate(bars, items, True, "mirror", "single", k)
        per[lbl] = statistics.mean(v)
        allv += v
    st = eq_stats(allv)
    lines.append(f"{k:>6.2f}R" + "".join(f"{per[lbl]:>+10.3f}" for lbl, _, _ in W)
                 + f"{st['exp']:>+10.3f}{st['eq']:>8.2f}x{st['mdd']:>7.1f}%")
    if best is None or st["eq"] > best[1]:
        best = (k, st["eq"])

# ── ۲) حساسیت به هزینه ──────────────────────────────────────────────────────
lines += ["", "═══ حساسیت به هزینه (هدف ۱R) ═══",
          f"{'سناریو':<34}{'انتظار R':>11}{'نرخ برد':>9}{'سرمایه':>9}{'افت':>8}"]
base = None
for sl_slip in (1, 2, 4):
    for entry_bad in (0, 1, 2):
        E.SL_SLIP = sl_slip
        allv = []
        for lbl, bf, sf in W:
            bars, items = loaded[lbl]
            v = []
            for s, entry, R, fill in items:
                d = -s["dir"]
                e2 = entry + entry_bad * E.TICK * d   # ورود بدتر
                sl = e2 - R * d
                v.append(F.simulate(e2, sl, [(e2 + 1 * R * d, 1.0)], d, bars, fill))
            allv += v
        st = eq_stats(allv)
        if base is None:
            base = st
        lines.append(f"{f'لغزش SL={sl_slip} تیک، ورود {entry_bad} تیک بدتر':<34}"
                     f"{st['exp']:>+11.3f}{st['wr']:>8.1f}%{st['eq']:>8.2f}x{st['mdd']:>7.1f}%")
E.SL_SLIP = 1

# ── ۳) نمودار ───────────────────────────────────────────────────────────────
doc_all, fade_all = [], []
for lbl, bf, sf in W:
    bars, items = loaded[lbl]
    doc_all += F.evaluate(bars, items, False, "mirror", "single", 1.0)
    fade_all += F.evaluate(bars, items, True, "mirror", "single", 1.0)

sd = eq_stats(doc_all)
sf_ = eq_stats(fade_all)
plt.figure(figsize=(10, 5.2), dpi=150)
plt.plot(sd["curve"], lw=1.8, color="#c0392b", label=f"Trigger-2 as documented  ({sd['eq']:.2f}x, DD {sd['mdd']:.0f}%)")
plt.plot(sf_["curve"], lw=1.8, color="#1e8449", label=f"Inverted trigger-2, 1R/1R  ({sf_['eq']:.2f}x, DD {sf_['mdd']:.0f}%)")
plt.axhline(1.0, color="#888", lw=0.8, ls="--")
plt.title("Equity curve — 388 real gold setups (3 independent 120-day windows), 1% risk per trade")
plt.xlabel("trade #")
plt.ylabel("equity (multiple of start)")
plt.grid(alpha=0.25)
plt.legend(loc="lower left", fontsize=9)
plt.tight_layout()
plt.savefig(os.path.join(HERE, "equity_compare.png"))
plt.close()

lines += ["", "═══ خلاصه ═══",
          f"سند  : انتظار {sd['exp']:+.3f}R · برد {sd['wr']:.1f}% · سرمایه {sd['eq']:.2f}x · افت {sd['mdd']:.0f}%",
          f"معکوس: انتظار {sf_['exp']:+.3f}R · برد {sf_['wr']:.1f}% · سرمایه {sf_['eq']:.2f}x · افت {sf_['mdd']:.0f}%",
          f"نمودار: equity_compare.png · بهترین هدف در جاروب: {best[0]:.2f}R"]

with open(os.path.join(HERE, "final_analysis.txt"), "w", encoding="utf-8") as f:
    f.write("\n".join(lines) + "\n")
print("\n".join(lines))
