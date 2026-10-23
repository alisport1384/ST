# -*- coding: utf-8 -*-
"""دانلود کندل‌های ۱ دقیقه‌ای طلا (PAXG/USDT — توکن پشتوانهٔ فیزیکی طلا) از بایننس.

چرا PAXG؟ API عمومی و بدون کلید، تاریخچهٔ ۱ دقیقه‌ای بلند، و حجم واقعی معاملات
(برخلاف دادهٔ یاهو که حجم فیوچرز را با تأخیر می‌دهد). قیمت آن با اونس جهانی هم‌حرکت است.
"""
import json, os, sys, time, urllib.request

BASE = "https://data-api.binance.vision/api/v3/klines"
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "xau_m1.csv")
DAYS = int(sys.argv[1]) if len(sys.argv) > 1 else 120


def fetch(symbol, start_ms, end_ms):
    url = f"{BASE}?symbol={symbol}&interval=1m&startTime={start_ms}&endTime={end_ms}&limit=1000"
    for attempt in range(5):
        try:
            with urllib.request.urlopen(url, timeout=20) as r:
                return json.load(r)
        except Exception as e:
            time.sleep(1.5 * (attempt + 1))
    raise RuntimeError("download failed: " + url)


def main():
    end = int(time.time() * 1000)
    start = end - DAYS * 86400 * 1000
    rows = []
    cur = start
    while cur < end:
        batch = fetch("PAXGUSDT", cur, end)
        if not batch:
            break
        rows.extend(batch)
        cur = batch[-1][0] + 60_000
        if len(rows) % 20_000 < 1000:
            print(f"  {len(rows)} کندل …", flush=True)
        time.sleep(0.12)
    seen, out = set(), []
    for k in rows:
        t = int(k[0])
        if t in seen:
            continue
        seen.add(t)
        out.append((t, float(k[1]), float(k[2]), float(k[3]), float(k[4]), float(k[5])))
    out.sort()
    with open(OUT, "w", encoding="utf-8") as f:
        f.write("time,open,high,low,close,volume\n")
        for t, o, h, l, c, v in out:
            f.write(f"{t},{o:.4f},{h:.4f},{l:.4f},{c:.4f},{v:.6f}\n")
    print(f"OK {len(out)} کندل → {OUT}")


main()
