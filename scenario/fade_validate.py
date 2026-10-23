# -*- coding: utf-8 -*-
"""اعتبارسنجی نسخهٔ معکوس روی سه پنجره (C هرگز در انتخاب دخالت نداشته)."""
import json, os, statistics
import fade_lab as F

HERE = os.path.dirname(os.path.abspath(__file__))
DATA = os.environ.get("GOLDPIN_DATA", "/home/user/data")
W = [("C (قدیمی‌ترین)", "xau_m1_c.csv", "lab_setups_c.csv"),
     ("B (وسط)", "xau_m1_b.csv", "lab_setups_b.csv"),
     ("A (جدیدترین)", "xau_m1.csv", "lab_setups.csv")]

lines = ["═══ اعتبارسنجی نسخهٔ معکوس تریگر ۲ روی سه پنجرهٔ مستقل ═══",
         "پیکربندی: ورود همان لبهٔ باکس سند · جهت مخالف · حد ضرر آینه‌ای ۱R · هدف ۱R", ""]
head = f"{'پنجره':<18}{'n':>5}{'نرخ برد':>9}{'انتظار R':>10}{'PF':>7}{'سرمایه':>9}{'افت':>8}{'سند (انتظار)':>15}"
lines += [head, "─" * len(head)]
allvals, alldoc, curves = [], [], [1.0]
eq = 1.0
for label, bf, sf in W:
    bars, items = F.load(bf, sf)
    fade = F.evaluate(bars, items, True, "mirror", "single", 1.0)
    doc = F.evaluate(bars, items, False, "mirror", "single", 1.0)
    st = F.stats(fade)
    d = F.stats(doc)
    lines.append(f"{label:<18}{len(fade):>5}{st['wr']:>8.1f}%{st['exp']:>+10.3f}{st['pf']:>7.2f}"
                 f"{st['eq']:>8.2f}x{st['mdd']:>7.1f}%{d['exp']:>+15.3f}")
    for v in fade:
        eq *= (1 + 0.01 * v)
        curves.append(eq)
    allvals += fade
    alldoc += doc

st = F.stats(allvals)
d = F.stats(alldoc)
lines += ["", f"مجموع سه پنجره: n={len(allvals)} · نرخ برد {st['wr']:.1f}% · انتظار {st['exp']:+.3f}R · "
              f"PF {st['pf']:.2f} · سرمایهٔ مرکب {st['eq']:.2f}x · حداکثر افت {st['mdd']:.1f}%"]
lines.append(f"همان داده با جهت سند: انتظار {d['exp']:+.3f}R · سرمایه {d['eq']:.2f}x · افت {d['mdd']:.1f}%")
json.dump(curves, open(os.path.join(HERE, "fade_validate_curve.json"), "w"))

# آزمون معناداری: bootstrap روی تفاضل زوجی
import random
random.seed(5)
diff = [f - o for f, o in zip(allvals, alldoc)]
means = []
for _ in range(6000):
    means.append(statistics.mean(random.choice(diff) for _ in diff))
means.sort()
lines.append(f"تفاضل زوجی (معکوس − سند): {statistics.mean(diff):+.3f}R · "
             f"بازهٔ ۹۵٪ [{means[150]:+.3f} , {means[5850]:+.3f}] ⇒ "
             f"{'معنادار' if means[150] > 0 else 'معنادار نیست'}")

with open(os.path.join(HERE, "fade_validate.txt"), "w", encoding="utf-8") as f:
    f.write("\n".join(lines) + "\n")
print("\n".join(lines))
