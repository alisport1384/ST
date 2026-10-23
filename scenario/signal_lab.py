# -*- coding: utf-8 -*-
"""جست‌وجوی «شرط سیگنال»: آیا زیرمجموعه‌ای از ستاپ‌های تریگر ۲ لبهٔ واقعی دارد؟

روش درست (برای پرهیز از داده‌کاوی):
  ۱) همهٔ شرایط کاندید روی پنجرهٔ A (درون‌نمونه) سنجیده می‌شوند.
  ۲) هر شرطی که در A انتظار مثبت دارد، روی پنجرهٔ B (خارج از نمونه) آزمون می‌شود.
  ۳) فقط شرطی «واقعی» شمرده می‌شود که در هر دو مثبت بماند.
"""
import os
import statistics

import exit_lab as E

HERE = os.path.dirname(os.path.abspath(__file__))
DATA = os.environ.get("GOLDPIN_DATA", "/home/user/data")
TICK = E.TICK


def atr(bars, bi, n=60):
    hs = bars[max(0, bi - n):bi]
    return statistics.median(b["h"] - b["l"] for b in hs) if hs else 0.0


def conds(s, bars):
    """شرایط کاندید — همه در لحظهٔ مسلح شدن قابل دانستن‌اند (بدون نگاه به آینده)."""
    bi = s["armBi"]
    d = s["dir"]
    b = bars[bi]
    a = atr(bars, bi)
    top, bot = max(s["hvTop"], s["hvBot"]), min(s["hvTop"], s["hvBot"])
    box = top - bot
    hour = (b["t"] // 3600000) % 24
    dow = ((b["t"] // 86400000) + 3) % 7   # 0=دوشنبه
    hv = None
    for x in range(bi, max(bi - 5, 0), -1):
        if abs(bars[x]["h"] - top) < 1e-6 and abs(bars[x]["l"] - bot) < 1e-6:
            hv = bars[x]
            break
    body = abs(hv["c"] - hv["o"]) / (hv["h"] - hv["l"]) if hv and hv["h"] > hv["l"] else 0.0
    if hv is None or box <= 0:
        close_side = 0.5
    else:
        close_side = (hv["c"] - bot) / box if d == 1 else (top - hv["c"]) / box
    nxt = bars[min(bi + 1, len(bars) - 1)]
    two_bar = (nxt["c"] > top) if d == 1 else (nxt["c"] < bot)
    return dict(
        dir=d, hour=hour, dow=dow, box=box, atr=a, ratio=box / a if a else 0,
        body=body, close_side=close_side if close_side == close_side else 0.5,
        two_bar=two_bar, trend_align=(s["trend"] == d),
        price=b["c"],
    )


CANDIDATES = {
    "همه (خط پایه)": lambda c: True,
    "فقط هم‌جهت با روند H1": lambda c: c["trend_align"],
    "فقط خلاف روند H1": lambda c: not c["trend_align"],
    "فقط خرید": lambda c: c["dir"] == 1,
    "فقط فروش": lambda c: c["dir"] == -1,
    "باکس ≥ ۱٫۵×ATR": lambda c: c["ratio"] >= 1.5,
    "باکس < ۱٫۵×ATR": lambda c: c["ratio"] < 1.5,
    "بدنهٔ HV ≥ ۶۰٪": lambda c: c["body"] >= 0.6,
    "کلوز HV در یک‌سوم بالایی جهت": lambda c: c["close_side"] >= 0.66,
    "تأیید دو-کندلی": lambda c: c["two_bar"],
    "ساعت ۱۲–۱۶ UTC": lambda c: 12 <= c["hour"] < 16,
    "ساعت ۰–۸ UTC": lambda c: c["hour"] < 8,
    "ساعت ۸–۱۲ UTC": lambda c: 8 <= c["hour"] < 12,
    "ساعت ۱۶–۲۴ UTC": lambda c: 16 <= c["hour"] < 24,
    "روزهای دوشنبه–چهارشنبه": lambda c: c["dow"] <= 2,
}


def evaluate(bars_file, setups_file):
    bars = E.load_m1(os.path.join(DATA if bars_file.endswith(".csv") and ("xau_m1" in bars_file or "btc_m1" in bars_file) else HERE, bars_file))
    setups = [s for s in E.load_setups(os.path.join(HERE, setups_file))
              if s["hvTop"] == s["hvTop"] and s["sl"] == s["sl"]]
    rec = []
    for s in setups:
        c = conds(s, bars)
        d = s["dir"]
        entry = s["hvTop"] + TICK if d == 1 else s["hvBot"] - TICK
        R = abs(entry - s["sl"])
        if R < E.MIN_R:
            continue
        # پیکربندی مرجع: حد ضرر سند، هدف ثابت ۳R (بهترین ناحیه در جاروب هدف)
        r3 = E.trade(entry, s["sl"], [(entry + 3 * R * d, 1.0)], d, bars, s["armBi"], be_after_first=False)
        # پیکربندی سند
        legs = E.targets_for("fib", entry, s["sl"], s)
        rf = E.trade(entry, s["sl"], legs, d, bars, s["armBi"], be_after_first=True)
        rec.append(dict(c=c, r3=r3["pnl"] if r3 else 0.0, fib=rf["pnl"] if rf else 0.0,
                        filled=bool(rf)))
    return rec


A = evaluate("xau_m1.csv", "lab_setups.csv")
B = evaluate("xau_m1_b.csv", "lab_setups_b.csv")

lines = ["═══ جست‌وجوی شرط سیگنال (انتخاب روی A، اعتبارسنجی روی B) ═══",
         "هدف ثابت ۳R (بدون پله) · حد ضرر سند · R = فاصلهٔ ورود تا حد ضرر", "",
         f"{'شرط':<28}{'n(A)':>6}{'انتظار ۳R در A':>17}{'n(B)':>6}{'انتظار در B':>13}{'پایدار؟':>10}", "─" * 82]
survivors = []
for name, fn in CANDIDATES.items():
    sa = [r["r3"] for r in A if fn(r["c"])]
    sb = [r["r3"] for r in B if fn(r["c"])]
    if len(sa) < 10 or len(sb) < 10:
        continue
    ea, eb = statistics.mean(sa), statistics.mean(sb)
    ok = "بله" if (ea > 0 and eb > 0) else "خیر"
    if ea > 0 and eb > 0:
        survivors.append((name, len(sa) + len(sb), ea, eb))
    lines.append(f"{name:<28}{len(sa):>6}{ea:>+17.3f}{len(sb):>6}{eb:>+13.3f}{ok:>10}")

lines += ["", "── همان شرایط با پیکربندی سند (پله‌های فیب) — فقط برای مقایسه ──",
          f"{'شرط':<28}{'n(A)':>6}{'انتظار در A':>15}{'n(B)':>6}{'انتظار در B':>13}", "─" * 70]
for name, fn in CANDIDATES.items():
    sa = [r["fib"] for r in A if fn(r["c"])]
    sb = [r["fib"] for r in B if fn(r["c"])]
    if len(sa) < 10 or len(sb) < 10:
        continue
    lines.append(f"{name:<28}{len(sa):>6}{statistics.mean(sa):>+15.3f}{len(sb):>6}{statistics.mean(sb):>+13.3f}")

lines += ["", "═══ نتیجه ═══"]
if survivors:
    for name, n, ea, eb in survivors:
        lines.append(f"  ✔ «{name}» در هر دو پنجره مثبت: A {ea:+.3f}R · B {eb:+.3f}R (n={n})")
else:
    lines.append("  ✘ هیچ شرطی در هر دو پنجره مثبت نماند ⇒ سیگنال تریگر ۲ در این داده لبهٔ قابل‌اتکا ندارد.")

with open(os.path.join(HERE, "signal_lab.txt"), "w", encoding="utf-8") as f:
    f.write("\n".join(lines) + "\n")
print("\n".join(lines))
