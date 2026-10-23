# -*- coding: utf-8 -*-
"""دانلود پنجرهٔ دوم (۱۲۰ روز قدیمی‌تر، خارج از نمونه) برای آزمون تکرارپذیری."""
import json, os, time, urllib.request
BASE = "https://data-api.binance.vision/api/v3/klines"
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "xau_m1_b.csv")
end_ms = int(time.time()*1000) - 130*86400*1000
start_ms = end_ms - 120*86400*1000
rows, cur = [], start_ms
while cur < end_ms:
    url = f"{BASE}?symbol=PAXGUSDT&interval=1m&startTime={cur}&endTime={end_ms}&limit=1000"
    for a in range(5):
        try:
            with urllib.request.urlopen(url, timeout=20) as r:
                batch = json.load(r); break
        except Exception:
            time.sleep(1.5*(a+1))
    else:
        raise SystemExit("fail")
    if not batch: break
    rows.extend(batch); cur = batch[-1][0] + 60_000; time.sleep(0.12)
seen, out = set(), []
for k in rows:
    t = int(k[0])
    if t in seen: continue
    seen.add(t); out.append((t, float(k[1]), float(k[2]), float(k[3]), float(k[4]), float(k[5])))
out.sort()
with open(OUT,"w",encoding="utf-8") as f:
    f.write("time,open,high,low,close,volume\n")
    for t,o,h,l,c,v in out: f.write(f"{t},{o:.4f},{h:.4f},{l:.4f},{c:.4f},{v:.6f}\n")
print(f"OK {len(out)} → {OUT}")
