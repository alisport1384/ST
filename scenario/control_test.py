# -*- coding: utf-8 -*-
"""آزمون شاهد: آیا جهت سیگنال تریگر ۲ بهتر از «سکه انداختن» است؟

سه گروه، با ورود/حد ضرر/هندسهٔ یکسان:
  ۱) جهت سند
  ۲) جهت مخالف (فِید)
  ۳) جهت تصادفی ۵۰/۵۰ در همان لحظه‌ها (۱۰۰ تکرار)
معیار: احتمال رسیدن +۱R پیش از −۱R.
"""
import os, random, statistics
import exit_lab as E

HERE = os.path.dirname(os.path.abspath(__file__))
DATA = os.environ.get("GOLDPIN_DATA", "/home/user/data")
TICK, ENTRY_WINDOW, HORIZON, MIN_R = E.TICK, 60, 240, E.MIN_R


def p_hit(bars, entry, R, d, fill_bi, k=1.0):
    for bi in range(fill_bi + 1, min(fill_bi + HORIZON + 1, len(bars))):
        b = bars[bi]
        fav = (b["h"] - entry) / R if d == 1 else (entry - b["l"]) / R
        adv = (b["l"] - entry) / R if d == 1 else (entry - b["h"]) / R
        if adv <= -1.0:
            return False
        if fav >= k:
            return True
    return False


def run(bars_file, setups_file):
    bars = E.load_m1(os.path.join(DATA if bars_file.endswith(".csv") and ("xau_m1" in bars_file or "btc_m1" in bars_file) else HERE, bars_file))
    setups = [s for s in E.load_setups(os.path.join(HERE, setups_file))
              if s["hvTop"] == s["hvTop"] and s["sl"] == s["sl"]]
    doc, fade = [], []
    for s in setups:
        d = s["dir"]
        entry = s["hvTop"] + TICK if d == 1 else s["hvBot"] - TICK
        R = abs(entry - s["sl"])
        if R < MIN_R:
            continue
        fill = None
        for bi in range(s["armBi"] + 1, min(s["armBi"] + ENTRY_WINDOW + 1, len(bars))):
            b = bars[bi]
            if (b["l"] <= entry) if d == 1 else (b["h"] >= entry):
                fill = bi
                break
        if fill is None:
            continue
        # جهت مخالف: حد ضرر آینه‌ای نسبت به ورود
        entry_f = entry
        sl_f = entry + R * d          # آینهٔ حد ضرر
        ok_doc = p_hit(bars, entry, R, d, fill)
        ok_fade = p_hit(bars, entry_f, R, -d, fill)
        doc.append(ok_doc)
        fade.append(ok_fade)
    random.seed(3)
    n = len(doc)
    rnd = []
    for _ in range(200):
        rnd.append(sum(1 for _ in range(n) if random.random() < 0.5) / n)
    return doc, fade, rnd


lines = ["═══ آزمون شاهد: سیگنال در برابر سکه ═══",
         "معیار: P(+1R پیش از −1R) با هندسهٔ یکسان", ""]
for label, bf, sf in [("B", "xau_m1_b.csv", "lab_setups_b.csv"), ("A", "xau_m1.csv", "lab_setups.csv")]:
    if not os.path.exists(os.path.join(HERE, sf)):
        continue
    doc, fade, rnd = run(bf, sf)
    n = len(doc)
    pd_, pf_ = sum(doc) / n * 100, sum(fade) / n * 100
    lines.append(f"پنجرهٔ {label}: n={n}")
    lines.append(f"  جهت سند      : {pd_:5.1f}%")
    lines.append(f"  جهت مخالف    : {pf_:5.1f}%")
    lines.append(f"  سکهٔ تصادفی  : {statistics.mean(rnd)*100:5.1f}% (بازهٔ ۹۵٪: {sorted(rnd)[9]*100:.1f}–{sorted(rnd)[-10]*100:.1f}%)")
    lines.append(f"  ⇒ جهت سند {'بهتر از' if pd_>50 else 'بدتر از'} سکه · جهت مخالف {'بهتر از' if pf_>50 else 'بدتر از'} سکه")
    lines.append("")

with open(os.path.join(HERE, "control_test.txt"), "w", encoding="utf-8") as f:
    f.write("\n".join(lines) + "\n")
print("\n".join(lines))
