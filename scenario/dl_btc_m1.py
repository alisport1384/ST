import json, os, time, urllib.request
BASE="https://data-api.binance.vision/api/v3/klines"
OUT=os.path.join(os.path.dirname(os.path.abspath(__file__)),"btc_m1.csv")
end=int(time.time()*1000); start=end-60*86400*1000
rows,cur=[],start
while cur<end:
    url=f"{BASE}?symbol=BTCUSDT&interval=1m&startTime={cur}&endTime={end}&limit=1000"
    with urllib.request.urlopen(url,timeout=20) as r: b=json.load(r)
    if not b: break
    rows+=b; cur=b[-1][0]+60000; time.sleep(0.1)
out=[]; seen=set()
for k in rows:
    t=int(k[0])
    if t in seen: continue
    seen.add(t); out.append((t,float(k[1]),float(k[2]),float(k[3]),float(k[4]),float(k[5])))
out.sort()
with open(OUT,"w",encoding="utf-8") as f:
    f.write("time,open,high,low,close,volume\n")
    for t,o,h,l,c,v in out: f.write(f"{t},{o:.2f},{h:.2f},{l:.2f},{c:.2f},{v:.6f}\n")
print("OK",len(out))
