# -*- coding: utf-8 -*-
"""آزمایشگاه «خروج و اندازهٔ ریسک» — آزمون توصیه‌های گزارش قبلی روی دادهٔ واقعی.

ورود در همهٔ پیکربندی‌ها همان قاعدهٔ سند است (لیمیت روی لبهٔ باکس حجم زیاد +۱ تیک)
تا تنها تفاوت، حد ضرر/حد سود باشد.

فرض‌های مشترک:
  • پنجرهٔ انتظار برای پر شدن: ۶۰ کندل (یک کندل ساختار)
  • عمر معامله پس از پر شدن: حداکثر ۲۴۰ کندل، سپس خروج بازار
  • لغزش: ۱ تیک روی پر شدن‌های بازار و حد ضرر
  • سایز: ریسک ۱٪ از سرمایهٔ جاری (بازده مرکب) — R هر معامله مستقل از اندازهٔ ریسک است
"""
import csv
import json
import math
import os
import statistics
from collections import OrderedDict

HERE = os.path.dirname(os.path.abspath(__file__))
DATA = os.environ.get("GOLDPIN_DATA", "/home/user/data")
TICK = 0.01
ENTRY_WINDOW = 60
MAX_HOLD = 240
SL_SLIP = 1
MKT_SLIP = 1
MIN_R = 0.03
RISK_PCT = 1.0


# ── داده ──────────────────────────────────────────────────────────────────────
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
                return float(r[k]) if r.get(k) not in ("", None) else float("nan")
            out.append(dict(id=int(r["setupId"]), dir=int(r["dir"]), armBi=int(r["armBi"]),
                            armT=int(r["armT"]), entry=g("entry"), sl=g("sl"),
                            tp1=g("tp1"), tp2=g("tp2"), tpX=g("tpX"),
                            hvTop=g("hvTop"), hvBot=g("hvBot"), lvTop=g("lvTop"), lvBot=g("lvBot"),
                            midRef=g("midRef"), trend=int(r["trend"]) if r.get("trend") else 0))
    return out


def atr(bars, bi, n=60):
    hs = bars[max(0, bi - n):bi]
    return statistics.median(b["h"] - b["l"] for b in hs) if hs else 0.0


# ── انتخاب حد ضرر ─────────────────────────────────────────────────────────────
def stop_for(mode, s, bars):
    d, arm = s["dir"], s["armBi"]
    edge = s["hvTop"] + TICK if d == 1 else s["hvBot"] - TICK
    box_risk = abs(edge - s["sl"])
    if mode == "doc":
        return s["sl"]
    if mode == "lv":
        lvb, lvt = min(s["lvBot"], s["lvTop"]), max(s["lvBot"], s["lvTop"])
        if lvt != lvt:
            return float("nan")
        return lvb - 2 * TICK if d == 1 else lvt + 2 * TICK
    if mode == "mid":
        if s["midRef"] != s["midRef"]:
            return float("nan")
        return s["midRef"] - 2 * TICK if d == 1 else s["midRef"] + 2 * TICK
    if mode == "atr1":
        r = max(box_risk, 1.0 * atr(bars, arm))
        return edge - r if d == 1 else edge + r
    if mode == "atr15":
        r = max(box_risk, 1.5 * atr(bars, arm))
        return edge - r if d == 1 else edge + r
    raise ValueError(mode)


def targets_for(mode, entry, sl, s, k=(1.0, 2.0, 3.0)):
    d = s["dir"]
    R = abs(entry - sl)
    if mode == "fib":
        legs = []
        if s["tpX"] == s["tpX"] and s["tp1"] == s["tp1"] and s["tp2"] == s["tp2"]:
            legs = [(s["tp1"], 0.33), (s["tp2"], 0.33), (s["tpX"], 0.34)]
        elif s["tpX"] == s["tpX"] and s["tp1"] == s["tp1"]:
            legs = [(s["tp1"], 0.5), (s["tpX"], 0.5)]
        elif s["tpX"] == s["tpX"]:
            legs = [(s["tpX"], 1.0)]
        elif s["tp1"] == s["tp1"] and s["tp2"] == s["tp2"]:
            legs = [(s["tp1"], 0.5), (s["tp2"], 0.5)]
        elif s["tp1"] == s["tp1"]:
            legs = [(s["tp1"], 1.0)]
        return sorted(legs, key=lambda x: abs(x[0] - entry))
    if mode == "risk3":
        return [(entry + k[0] * R * d, 0.33), (entry + k[1] * R * d, 0.33), (entry + k[2] * R * d, 0.34)]
    if mode == "risk2":
        return [(entry + k[0] * R * d, 0.5), (entry + k[0] * 3 * R * d, 0.5)]
    if mode == "risk1":
        return [(entry + k[0] * R * d, 1.0)]
    raise ValueError(mode)


# ── شبیه‌سازی یک معامله ───────────────────────────────────────────────────────
def trade(entry, sl, legs, d, bars, arm_bi, be_after_first=True, trail=None):
    R = abs(entry - sl)
    if R < MIN_R or not legs:
        return None
    filled = False
    rem, realized = 1.0, 0.0
    sl_now = sl
    hit = [False] * len(legs)
    mfe = 0.0
    for bi in range(arm_bi + 1, min(arm_bi + MAX_HOLD + 1, len(bars))):
        b = bars[bi]
        if not filled:
            if bi - arm_bi > ENTRY_WINDOW:
                return None
            if (b["l"] <= entry) if d == 1 else (b["h"] >= entry):
                filled = True
            else:
                continue
        mfe = max(mfe, (b["h"] - entry) / R if d == 1 else (entry - b["l"]) / R)
        sl_level = sl_now - SL_SLIP * TICK * d
        if (b["l"] <= sl_level) if d == 1 else (b["h"] >= sl_level):
            realized += rem * (sl_level - entry) / R * d
            return dict(R=R, pnl=realized, mfe=mfe, reason="SL")
        for i, (lvl, frac) in enumerate(legs):
            if hit[i]:
                continue
            if (b["h"] >= lvl) if d == 1 else (b["l"] <= lvl):
                hit[i] = True
                q = min(frac, rem)
                realized += q * (lvl - entry) / R * d
                rem -= q
                if i == 0 and be_after_first:
                    sl_now = entry
                if trail and rem > 1e-9:
                    prog = (lvl - entry) / R * d
                    if trail == "step":
                        sl_now = entry + max(0.0, math.floor(prog) - 1) * R * d
                    elif trail == "half":
                        sl_now = entry + max(0.0, prog / 2) * R * d
                if rem <= 1e-9:
                    return dict(R=R, pnl=realized, mfe=mfe, reason="TP")
    if not filled:
        return None
    exit_px = bars[min(arm_bi + MAX_HOLD, len(bars) - 1)]["c"]
    realized += rem * (exit_px - entry) / R * d
    return dict(R=R, pnl=realized, mfe=mfe, reason="TIME")


# ── پیکربندی‌ها ───────────────────────────────────────────────────────────────
CONFIGS = OrderedDict()
CONFIGS["E0_doc"] = dict(sl="doc", tg="fib")
CONFIGS["E1_risk_1_2_3"] = dict(sl="doc", tg="risk3")
CONFIGS["E2_risk_1_2_4"] = dict(sl="doc", tg="risk3", k=(1.0, 2.0, 4.0))
CONFIGS["E3_risk_1R_only"] = dict(sl="doc", tg="risk1")
CONFIGS["E4_risk_1_3_two_step"] = dict(sl="doc", tg="risk2")
CONFIGS["E5_slLV_risk_1_2_3"] = dict(sl="lv", tg="risk3")
CONFIGS["E6_slLV_fib"] = dict(sl="lv", tg="fib")
CONFIGS["E7_slMid_risk_1_2_3"] = dict(sl="mid", tg="risk3")
CONFIGS["E8_slATR1_risk_1_2_3"] = dict(sl="atr1", tg="risk3")
CONFIGS["E9_slATR15_risk_1_2_3"] = dict(sl="atr15", tg="risk3")
CONFIGS["E10_risk_1_2_3_trail_step"] = dict(sl="doc", tg="risk3", trail="step")
CONFIGS["E11_risk_1_2_3_trendFilter"] = dict(sl="doc", tg="risk3", trend_filter=True)
CONFIGS["E12_slLV_risk_1_2_3_trendFilter"] = dict(sl="lv", tg="risk3", trend_filter=True)


def run_window(label, bars_file, setups_file):
    bars = load_m1(os.path.join(DATA if bars_file.endswith(".csv") and ("xau_m1" in bars_file or "btc_m1" in bars_file) else HERE, bars_file))
    setups = [s for s in load_setups(os.path.join(HERE, setups_file))
              if s["hvTop"] == s["hvTop"] and s["sl"] == s["sl"]]
    out = {}
    trades_log = {}
    for name, c in CONFIGS.items():
        seq = []
        for s in setups:
            if c.get("trend_filter") and s["trend"] != s["dir"]:
                continue
            d = s["dir"]
            entry = s["hvTop"] + TICK if d == 1 else s["hvBot"] - TICK
            sl = stop_for(c["sl"], s, bars)
            if sl != sl:
                continue
            legs = targets_for(c["tg"], entry, sl, s, c.get("k", (1.0, 2.0, 3.0)))
            r = trade(entry, sl, legs, d, bars, s["armBi"],
                      be_after_first=True, trail=c.get("trail"))
            seq.append(dict(t=s["armT"], R=r["pnl"] if r else 0.0,
                            reason=r["reason"] if r else "NOFILL", filled=bool(r)))
        out[name] = seq
        trades_log[name] = seq
    return label, out


def equity_stats(seq):
    eq = 1.0
    peak = 1.0
    mdd = 0.0
    curve = [1.0]
    for t in seq:
        eq *= (1.0 + RISK_PCT / 100.0 * t["R"])
        curve.append(eq)
        peak = max(peak, eq)
        mdd = max(mdd, (peak - eq) / peak)
    rs = [t["R"] for t in seq if t["filled"]]
    wins = [r for r in rs if r > 0.05]
    losses = [r for r in rs if r <= 0.05]
    gp = sum(wins)
    gl = -sum(losses) if losses else 0.0
    return dict(n_filled=len(rs), trades=len(seq), wr=len(wins) / len(rs) * 100 if rs else 0,
                exp=statistics.mean(rs) if rs else 0.0,
                total=sum(rs), pf=(gp / gl if gl else float("inf")),
                eq=eq, mdd=mdd * 100, curve=curve,
                med=statistics.median(rs) if rs else 0.0,
                avg_win=statistics.mean(wins) if wins else 0.0,
                avg_loss=statistics.mean(losses) if losses else 0.0)


def main():
    labels = []
    data = {}
    for label, bf, sf in [("B (قدیمی‌تر)", "xau_m1_b.csv", "lab_setups_b.csv"),
                          ("A (جدیدتر)", "xau_m1.csv", "lab_setups.csv")]:
        lbl, res = run_window(label, bf, sf)
        data[label] = res
        labels.append(label)

    # ترکیب زمانی: B سپس A
    combined = {}
    for name in CONFIGS:
        combined[name] = data[labels[0]][name] + data[labels[1]][name]

    blocks = [("پنجرهٔ B", {n: data[labels[0]][n] for n in CONFIGS}),
              ("پنجرهٔ A", {n: data[labels[1]][n] for n in CONFIGS}),
              ("ترکیبی (B→A)", combined)]

    lines = []
    P = lines.append
    P("═══ آزمایشگاه خروج/ریسک — آزمون توصیه‌ها روی دادهٔ واقعی طلا ═══")
    P(f"ورود ثابت = قاعدهٔ سند (لیمیت لبهٔ باکس +۱ تیک) · انتظار پر شدن {ENTRY_WINDOW} کندل · "
      f"حداکثر عمر {MAX_HOLD} کندل · ریسک {RISK_PCT}٪ سرمایه با بازده مرکب")
    P("")
    summary = {}
    for bl, res in blocks:
        P(f"── {bl} ──")
        head = f"{'پیکربندی':<32}{'معامله':>7}{'پر':>6}{'نرخ برد':>9}{'انتظار R':>10}{'PF':>7}{'میانه':>8}{'سرمایه':>9}{'حداکثر افت':>12}"
        P(head)
        P("─" * len(head))
        for name in CONFIGS:
            st = equity_stats(res[name])
            summary[(bl, name)] = st
            P(f"{name:<32}{st['trades']:>7}{st['n_filled']:>6}{st['wr']:>8.1f}%{st['exp']:>10.3f}"
              f"{st['pf']:>7.2f}{st['med']:>8.2f}{st['eq']:>8.2f}x{st['mdd']:>11.1f}%")
        P("")

    # نمودار سرمایه (ترکیبی)
    curves = {n: summary[("ترکیبی (B→A)", n)]["curve"] for n in CONFIGS}
    json.dump({k: v for k, v in curves.items()},
              open(os.path.join(HERE, "equity_curves.json"), "w"))

    # بهترین‌ها
    P("── رتبه‌بندی بر اساس سرمایهٔ نهایی (ترکیبی) ──")
    rank = sorted(CONFIGS, key=lambda n: -summary[("ترکیبی (B→A)", n)]["eq"])
    for i, n in enumerate(rank, 1):
        st = summary[("ترکیبی (B→A)", n)]
        P(f"{i:>2}. {n:<32} سرمایه {st['eq']:.2f}x · انتظار {st['exp']:+.3f}R · برد {st['wr']:.1f}% · افت {st['mdd']:.1f}%")
    P("")
    # پایداری: علامت انتظار در هر دو پنجره
    P("── پایداری بین دو پنجره (انتظار R) ──")
    P(f"{'پیکربندی':<32}{'B':>10}{'A':>10}{'هر دو مثبت؟':>14}")
    stable = []
    for n in CONFIGS:
        b = summary[("پنجرهٔ B", n)]["exp"]
        a = summary[("پنجرهٔ A", n)]["exp"]
        ok = "بله" if (b > 0 and a > 0) else "خیر"
        if b > 0 and a > 0:
            stable.append(n)
        P(f"{n:<32}{b:>+10.3f}{a:>+10.3f}{ok:>14}")
    P("")
    P("پیکربندی‌هایی که در هر دو پنجره انتظار مثبت دارند: " + (", ".join(stable) if stable else "هیچ‌کدام"))

    with open(os.path.join(HERE, "exit_lab_report.txt"), "w", encoding="utf-8") as f:
        f.write("\n".join(lines) + "\n")
    print("\n".join(lines))


if __name__ == "__main__":
    main()
