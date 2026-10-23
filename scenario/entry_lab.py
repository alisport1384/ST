# -*- coding: utf-8 -*-
"""آزمایشگاه ورود «تریگر ۲» — مقایسهٔ عددی روش‌های ورود روی دادهٔ واقعی طلا.

ورودی: lab_setups.csv (ستاپ‌های واقعیِ موتور) · xau_m1.csv (همان کندل‌ها)
اصل کار: فقط «قاعدهٔ ورود» عوض می‌شود؛ حد ضرر، پله‌های سود و مدیریت §۴-۸/§۴-۹ ثابت می‌ماند.
"""
import csv
import os
import random
import statistics
from collections import defaultdict

HERE = os.path.dirname(os.path.abspath(__file__))
DATA = os.environ.get("GOLDPIN_DATA", "/home/user/data")
TICK = 0.01
BUFFER_BARS = 60
SL_SLIP = 1
MKT_SLIP = 1
MIN_R = 0.03          # اگر فاصلهٔ حد ضرر کمتر از ۳ تیک باشد ستاپ قابل‌سنجش نیست


def load_m1(path):
    bars = []
    with open(path, encoding="utf-8") as f:
        for i, ln in enumerate(f):
            if i == 0 or not ln.strip():
                continue
            p = ln.strip().split(",")
            bars.append(dict(bi=len(bars), t=int(p[0]), o=float(p[1]), h=float(p[2]),
                             l=float(p[3]), c=float(p[4]), v=float(p[5])))
    return bars


def load_setups(path):
    out = []
    with open(path, encoding="utf-8") as f:
        for r in csv.DictReader(f):
            def g(k):
                return float(r[k]) if r[k] not in ("", None) else float("nan")
            out.append(dict(id=int(r["setupId"]), dir=int(r["dir"]), armBi=int(r["armBi"]),
                            entry=g("entry"), sl=g("sl"), tp1=g("tp1"), tp2=g("tp2"), tpX=g("tpX"),
                            hvTop=g("hvTop"), hvBot=g("hvBot"), lvTop=g("lvTop"), lvBot=g("lvBot"), engineR=g("tradeR")))
    return out


def legs_for(entry_px, tp1, tp2, tpx):
    if tpx == tpx and tp1 == tp1 and tp2 == tp2:
        legs = [(tp1, 0.33), (tp2, 0.33), (tpx, 0.34)]
    elif tpx == tpx and tp1 == tp1:
        legs = [(tp1, 0.5), (tpx, 0.5)]
    elif tpx == tpx:
        legs = [(tpx, 1.0)]
    elif tp1 == tp1 and tp2 == tp2:
        legs = [(tp1, 0.5), (tp2, 0.5)]
    elif tp1 == tp1:
        legs = [(tp1, 1.0)]
    else:
        legs = []
    legs.sort(key=lambda x: abs(x[0] - entry_px))
    return legs


def simulate(entry_px, sl, legs, d, bars, start_bi, limit_ok=True, horizon=BUFFER_BARS):
    """شبیه‌سازی: پر شدن → SL/پله‌ها با اولویت SL در هر کندل → انتقال SL به سر‌به‌سر بعد از پلهٔ اول."""
    R = abs(entry_px - sl)
    if R < MIN_R:
        return None
    filled = False
    rem, realized, sl_now, be = 1.0, 0.0, sl, False
    hit = [False] * len(legs)
    mfe = 0.0
    end = min(start_bi + horizon + 1, len(bars))
    for bi in range(start_bi + 1, end):
        b = bars[bi]
        if not filled:
            if limit_ok:
                if (b["l"] <= entry_px) if d == 1 else (b["h"] >= entry_px):
                    filled = True
                else:
                    continue
            else:
                filled = True
        mfe = max(mfe, (b["h"] - entry_px) / R if d == 1 else (entry_px - b["l"]) / R)
        sl_level = sl_now - SL_SLIP * TICK * d
        if (b["l"] <= sl_level) if d == 1 else (b["h"] >= sl_level):
            realized += rem * (sl_level - entry_px) / R * d
            return dict(pnl=realized, mfe=mfe, bars=bi - start_bi, reason="BE" if be else "SL")
        for i, (lvl, frac) in enumerate(legs):
            if hit[i]:
                continue
            if (b["h"] >= lvl) if d == 1 else (b["l"] <= lvl):
                hit[i] = True
                q = min(frac, rem)
                realized += q * (lvl - entry_px) / R * d
                rem -= q
                if i == 0:
                    sl_now, be = entry_px, True
                if rem <= 1e-9:
                    return dict(pnl=realized, mfe=mfe, bars=bi - start_bi, reason="TP")
    if not filled:
        return None
    exit_px = bars[min(end - 1, len(bars) - 1)]["c"]
    realized += rem * (exit_px - entry_px) / R * d
    return dict(pnl=realized, mfe=mfe, bars=end - 1 - start_bi, reason="EXPIRY")


def boot_ci(vals, n=4000, lo=2.5, hi=97.5):
    if len(vals) < 8:
        return (float("nan"), float("nan"))
    random.seed(7)
    means = []
    for _ in range(n):
        means.append(sum(random.choice(vals) for _ in vals) / len(vals))
    means.sort()
    return means[int(lo / 100 * n)], means[int(hi / 100 * n)]


def main():
    bars = load_m1(os.path.join(HERE, os.environ.get("LAB_BARS", "xau_m1.csv")))
    raw = load_setups(os.path.join(HERE, os.environ.get("LAB_SETUPS", "lab_setups.csv")))
    usable = [s for s in raw if s["hvTop"] == s["hvTop"] and s["sl"] == s["sl"]]

    def entry_prices(s):
        d, top, bot = s["dir"], s["hvTop"], s["hvBot"]
        arm = bars[s["armBi"]]
        edge = top + TICK if d == 1 else bot - TICK
        deep3 = edge - 3 * TICK * d
        mid = (top + bot) / 2.0
        far = bot + TICK if d == 1 else top - TICK
        return dict(
            A0_edge=(edge, True),
            A1_edge_d3=(deep3, True),
            A2_mid=(mid, True),
            A3_far=(far, True),
            A4_market=(arm["c"] + MKT_SLIP * TICK * d, False),
        )

    NAMES = ["A0_edge", "A1_edge_d3", "A2_mid", "A3_far", "A4_market", "A5_next_close", "A6_hybrid"]

    per = {n: [] for n in NAMES}          # R per setup (0 = پر نشد)
    fills = {n: 0 for n in NAMES}
    wins = {n: 0 for n in NAMES}
    reasons = defaultdict(lambda: defaultdict(int))
    mfes = defaultdict(list)
    imps = []                             # بهبود قیمت ورود نسخهٔ بعدی نسبت به قاعدهٔ سند (R)
    engine_pairs = []

    for s in usable:
        d = s["dir"]
        arm_bi = s["armBi"]
        legs_doc = legs_for(s["entry"], s["tp1"], s["tp2"], s["tpX"])
        res_map = {}

        for name, (px, is_limit) in entry_prices(s).items():
            legs = legs_for(px, s["tp1"], s["tp2"], s["tpX"])
            r = simulate(px, s["sl"], legs, d, bars, arm_bi, limit_ok=is_limit)
            res_map[name] = (r, px)

        # A5: ورود بازار در کلوز کندل بعدی
        nb = bars[min(arm_bi + 1, len(bars) - 1)]
        px5 = nb["c"] + MKT_SLIP * TICK * d
        r5 = simulate(px5, s["sl"], legs_for(px5, s["tp1"], s["tp2"], s["tpX"]), d, bars, arm_bi + 1, limit_ok=False)
        res_map["A5_next_close"] = (r5, px5)

        # A6: ترکیبی — لیمیت روی لبه برای ۵ کندل، بعد ورود بازار (post-only سپس taker)
        px0 = res_map["A0_edge"][1]
        r6 = simulate(px0, s["sl"], legs_for(px0, s["tp1"], s["tp2"], s["tpX"]), d, bars, arm_bi, limit_ok=True, horizon=5)
        if r6 is None:
            b5 = bars[min(arm_bi + 5, len(bars) - 1)]
            px6 = b5["c"] + MKT_SLIP * TICK * d
            r6 = simulate(px6, s["sl"], legs_for(px6, s["tp1"], s["tp2"], s["tpX"]), d, bars, arm_bi + 5, limit_ok=False)
            res_map["A6_hybrid"] = (r6, px6)
        else:
            res_map["A6_hybrid"] = (r6, px0)

        for n in NAMES:
            r, _ = res_map[n]
            r_val = r["pnl"] if r else 0.0
            per[n].append(r_val)
            if r:
                fills[n] += 1
                reasons[n][r["reason"]] += 1
                mfes[n].append(r["mfe"])
                if r["pnl"] > 0.05:
                    wins[n] += 1
        if s["engineR"] == s["engineR"]:
            engine_pairs.append((per["A0_edge"][-1], s["engineR"]))
        if res_map["A0_edge"][0] and res_map["A5_next_close"][0]:
            R0 = abs(res_map["A0_edge"][1] - s["sl"])
            imps.append((res_map["A0_edge"][1] - res_map["A5_next_close"][1]) * d / R0)

    lines = []
    P = lines.append
    P("═══ آزمایشگاه ورود تریگر ۲ — دادهٔ واقعی طلا (PAXG/USDT، کندل ۱ دقیقه‌ای) ═══")
    P(f"ستاپ‌های مسلح‌شدهٔ موتور: {len(usable)} · کندل‌های M1: {len(bars)} (≈۱۲۰ روز)")
    P(f"انقضای سفارش {BUFFER_BARS} کندل · لغزش ورود بازار/استاپ {MKT_SLIP} تیک · لغزش SL {SL_SLIP} تیک")
    P("R = فاصلهٔ ورود تا حد ضرر همان روش (ریسک ثابت ۱٪) — پس همهٔ ستون‌ها با هم قابل مقایسه‌اند.")
    P("")
    head = f"{'روش ورود':<14}{'پر':>6}{'نرخ پر':>8}{'برد':>6}{'نرخ برد':>9}{'R/ستاپ':>9}{'±CI95':>8}{'R/معامله':>10}{'MFE':>7}{'SL%':>6}"
    P(head)
    P("─" * len(head))
    summary = {}
    for n in NAMES:
        vals = per[n]
        f = fills[n]
        tot = sum(vals)
        lo, hi = boot_ci(vals)
        summary[n] = dict(n=len(vals), filled=f, fill_rate=f / len(vals) * 100, wins=wins[n],
                          wr=wins[n] / f * 100 if f else 0, total=tot, per_setup=tot / len(vals),
                          ci=(lo, hi), per_trade=tot / f if f else 0,
                          mfe=statistics.mean(mfes[n]) if mfes[n] else 0,
                          sl_share=reasons[n]["SL"] / f * 100 if f else 0)
        x = summary[n]
        P(f"{n:<14}{f:>6}{x['fill_rate']:>7.1f}%{x['wins']:>6}{x['wr']:>8.1f}%{x['per_setup']:>9.3f}"
          f"{'':>2}[{lo:+.2f},{hi:+.2f}]{x['per_trade']:>8.3f}{x['mfe']:>7.2f}{x['sl_share']:>5.0f}%")
    P("")
    if engine_pairs:
        a = [p[0] for p in engine_pairs]
        b = [p[1] for p in engine_pairs]
        P(f"هم‌خوانی شبیه‌ساز با موتور روی روش سند: میانگین اختلاف {statistics.mean(x - y for x, y in zip(a, b)):+.3f}R "
          f"(میانه {statistics.median(x - y for x, y in zip(a, b)):+.3f}R) · موتور: مجموع {sum(b):+.1f}R روی {len(b)} معامله")
    P(f"بهبود قیمت ورود «کلوز کندل بعد» نسبت به قاعدهٔ سند: میانگین {statistics.mean(imps):+.2f}R "
      f"({sum(1 for i in imps if i > 0) / len(imps) * 100:.0f}٪ موارد بهتر)")
    P("")
    P("── پایداری در چهار پنجرهٔ ۳۰ روزه (R/ستاپ) ──")
    span = len(bars) // 4
    P(f"{'روش':<14}" + "".join(f"{'W'+str(i+1):>9}" for i in range(4)))
    for n in NAMES:
        buckets = [[] for _ in range(4)]
        idx = 0
        for s in usable:
            w = min(bars[s["armBi"]]["bi"] // span, 3)
            buckets[w].append(per[n][idx])
            idx += 1
        P(f"{n:<14}" + "".join(f"{(sum(b)/len(b)):>9.2f}" if b else f"{'—':>9}" for b in buckets))
    P("")
    P("── منابع پایان معامله (٪) ──")
    for n in NAMES:
        rr = reasons[n]
        tot = sum(rr.values()) or 1
        P(f"{n:<14} SL={rr['SL']/tot*100:5.1f}%  سر‌به‌سر={rr['BE']/tot*100:5.1f}%  پله‌ها={rr['TP']/tot*100:5.1f}%  انقضا={rr['EXPIRY']/tot*100:5.1f}%")

    with open(os.path.join(HERE, os.environ.get("LAB_REPORT", "entry_lab_report.txt")), "w", encoding="utf-8") as f:
        f.write("\n".join(lines) + "\n")
    print("\n".join(lines))


if __name__ == "__main__":
    main()
