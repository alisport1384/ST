#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
«اوراکل سند» — پیاده‌سازی مستقل قواعد GoldPin_strategy_final5_FA.md (پایتون، از صفر)
روی همان فایل scenario/m1.csv که کد را با آن آزمودیم. هدف: گرفتن گزارش «سند» برای مقایسه.

هر بخش با شمارهٔ بند سند برچسب خورده است تا معلوم باشد کدام قاعده از کدام خط سند می‌آید.
این فایل هیچ خطی از کد کاتلین را بازتولید نمی‌کند؛ فقط متن سند پیاده شده است.
"""
import csv
import json
import os

HERE = os.path.dirname(os.path.abspath(__file__))
DATA = os.environ.get("GOLDPIN_DATA", "/home/user/data")
MS = 1000
H1, M15, M5, M1 = 3600, 900, 300, 60
MINTICK = 0.01
SL_BUF_TICKS = 2      # §۴-۸: کف ناحیه حجم زیاد − ۲ تیک
ENTRY_BUF_TICKS = 1   # §۴-۸: سقف باکس HV + ۱ تیک
MIN_TP_UNITS = 10.0   # §۴-۸: فالبک حد سود نهایی
N_SL_UNITS = 3.0      # §۱۸-۳۱: سفارش با SL>N ثبت نمی‌شود
ROWS = 24             # §۴-۵
RISK_PCT = 1.0        # §۵
EQUITY0 = 100000.0    # §۵
LEVERAGE = 500.0      # §۵
CONTRACT = 1.0        # §۵


def load(path):
    out = []
    with open(path, newline="") as f:
        r = csv.DictReader(f)
        for i, row in enumerate(r):
            out.append(dict(
                bi=i, t=int(row["time"]),
                o=float(row["open"]), h=float(row["high"]),
                l=float(row["low"]), c=float(row["close"]), v=float(row["volume"])
            ))
    return out


def bucket(sec, t):
    ms = sec * 1000
    return (t // ms) * ms


def aggregate(m1, sec):
    """ساخت تایم‌فریم بالاتر از کندل‌های ریز (سند §۲: تایم‌فریم‌های بالاتر از همان کندل‌های ریز ساخته می‌شوند)."""
    bars = []
    cur = None
    for cd in m1:
        b = bucket(sec, cd["t"])
        if cur is None or cur["t"] != b:
            if cur is not None:
                bars.append(cur)
            cur = dict(t=b, o=cd["o"], h=cd["h"], l=cd["l"], c=cd["c"], v=cd["v"], bi=cd["bi"], last_bi=cd["bi"])
        else:
            cur["h"] = max(cur["h"], cd["h"])
            cur["l"] = min(cur["l"], cd["l"])
            cur["c"] = cd["c"]
            cur["v"] += cd["v"]
            cur["last_bi"] = cd["bi"]
    if cur is not None:
        bars.append(cur)
    return bars


# ══════════════════════════════════════════════════════════════════════════════
#  §۴-۱ / §۴-۳ / §۴-۴ : کندل مهم ، Ready ، پین ، توالی ، tempbox ، روند ، HH/HL
# ══════════════════════════════════════════════════════════════════════════════
class Structure:
    def __init__(self):
        self.trend = 0
        self.pinBU = None      # dict(hi, lo, ref, bi)
        self.pinBE = None
        self.cand = None       # کاندید IC در انتظار تعیین تکلیف (§۱۸-۶)
        self.expected = 0      # 1 ⇒ پین بعدی باید BU باشد، -1 ⇒ BE ، 0 ⇒ آزاد
        self.tempTop = None
        self.tempBot = None
        self.HH = None
        self.HL = None
        self.LL = None
        self.LH = None
        self.highestBE = None
        self.lowestBU = None
        self.events = []

    def log(self, msg):
        self.events.append(msg)

    def on_bar(self, bar, prev):
        # ── چرخهٔ کاندید: ابطال با باز/کلوز آن‌طرف سطح ابطال ؛ Ready با کلوز آن‌طرف سطح تایید ──
        c = self.cand
        if c is not None:
            if c["kind"] == 1:   # پایین‌ترین‌ها: ابطال با min(close(a),open(b)) ، تایید با high کندل مهم
                if bar["o"] < c["ref"] or bar["c"] < c["ref"]:
                    self.log(f"{hhmm(bar['t'])} کاندید IC پایین‌ترین‌ها باطل شد (o={bar['o']:.2f} c={bar['c']:.2f} < ref={c['ref']:.2f})")
                    self.cand = None
                elif bar["c"] > c["hi"]:
                    if self.expected in (0, 1):
                        self.pinBU = c
                        self.lowestBU = c["lo"] if self.lowestBU is None else min(self.lowestBU, c["lo"])
                        self.expected = -1
                        self.log(f"{hhmm(bar['t'])} Ready BU · pinBU={c['lo']:.2f} (IC high={c['hi']:.2f})")
                        self.cand = None
                        self._after_pin()
            else:                # بالاترین‌ها: ابطال با max(close(a),open(b)) ، تایید با low کندل مهم
                if bar["o"] > c["ref"] or bar["c"] > c["ref"]:
                    self.log(f"{hhmm(bar['t'])} کاندید IC بالاترین‌ها باطل شد (o={bar['o']:.2f} c={bar['c']:.2f} > ref={c['ref']:.2f})")
                    self.cand = None
                elif bar["c"] < c["lo"]:
                    if self.expected in (0, -1):
                        self.pinBE = c
                        self.highestBE = c["hi"] if self.highestBE is None else max(self.highestBE, c["hi"])
                        self.expected = 1
                        self.log(f"{hhmm(bar['t'])} Ready BE · pinBE={c['hi']:.2f} (IC low={c['lo']:.2f})")
                        self.cand = None
                        self._after_pin()

        # ── tempbox و روند ──
        if self.trend == 0 and self.pinBU and self.pinBE:
            self.tempTop = self.pinBE["hi"]
            self.tempBot = self.pinBU["lo"]
            if bar["c"] > self.tempTop:
                self.trend = 1
                self.log(f"{hhmm(bar['t'])} روند صعودی تایید شد (کلوز {bar['c']:.2f} > سقف tempbox {self.tempTop:.2f}) ⇒ tempbox پاک شد")
                self.tempTop = self.tempBot = None
                self.HH = self.HL = None
                # §۱۸-۴: پینی که شکست خورد (pinBE سازندهٔ tempbox) دور ریخته می‌شود؛
                # پس آخرین پین معتبر pinBU است و طبق §۱۸-۱ پین بعدی باید pinBE باشد.
                self.pinBE = None
                self.expected = -1
            elif bar["c"] < self.tempBot:
                self.trend = -1
                self.log(f"{hhmm(bar['t'])} روند نزولی تایید شد (کلوز {bar['c']:.2f} < کف tempbox {self.tempBot:.2f}) ⇒ tempbox پاک شد")
                self.tempTop = self.tempBot = None
                self.LL = self.LH = None
                self.pinBU = None
                self.expected = 1

        # ── HH/HL و LL/LH بعد از تعیین روند (§۴-۳) ──
        if self.trend == 1 and self.HH is None and self.pinBE and self.pinBU:
            self.HH = self.pinBE["hi"]
            self.HL = self.pinBU["lo"]
            self.log(f"{hhmm(bar['t'])} HH/HL تعریف شد: HH={self.HH:.2f} HL={self.HL:.2f}")
        if self.trend == -1 and self.LL is None and self.pinBU and self.pinBE:
            self.LL = self.pinBU["lo"]
            self.LH = self.pinBE["hi"]
            self.log(f"{hhmm(bar['t'])} LL/LH تعریف شد: LL={self.LL:.2f} LH={self.LH:.2f}")

        # ── چرخش روند (§۴-۳) ──
        if self.trend == 1 and self.HL is not None and bar["c"] < self.HL:
            self.trend = -1
            self.log(f"{hhmm(bar['t'])} چرخش صعودی→نزولی (کلوز {bar['c']:.2f} < کف HL {self.HL:.2f})")
        elif self.trend == -1 and self.LH is not None and bar["c"] > self.LH:
            self.trend = 1
            self.log(f"{hhmm(bar['t'])} چرخش نزولی→صعودی (کلوز {bar['c']:.2f} > سقف LH {self.LH:.2f})")

        # ── ثبت کاندید کندل مهم (§۴-۱): دو کندل مخالفِ پشت‌سرهم ──
        if prev is not None and self.cand is None:
            a, b = prev, bar
            if a["c"] < a["o"] and b["c"] > b["o"]:
                # کندل مهم در پایین‌ترین‌ها: کمترین کف ؛ اگر کف برابر، کمترین سقف
                if a["l"] < b["l"] or (a["l"] == b["l"] and a["h"] < b["h"]):
                    ic = a
                else:
                    ic = b
                ref = min(a["c"], b["o"])
                if self.expected in (0, 1):
                    self.cand = dict(kind=1, hi=ic["h"], lo=ic["l"], ref=ref, bi=ic["bi"])
                    self.log(f"{hhmm(bar['t'])} کاندید کندل مهم پایین‌ترین‌ها: IC={{hi={ic['h']:.2f} lo={ic['l']:.2f}}} ref=min(close(a)={a['c']:.2f},open(b)={b['o']:.2f})={ref:.2f}")
            elif a["c"] > a["o"] and b["c"] < b["o"]:
                # کندل مهم در بالاترین‌ها: بیشترین سقف ؛ اگر سقف برابر، کمترین کف
                if a["h"] > b["h"] or (a["h"] == b["h"] and a["l"] < b["l"]):
                    ic = a
                else:
                    ic = b
                ref = max(a["c"], b["o"])
                if self.expected in (0, -1):
                    self.cand = dict(kind=-1, hi=ic["h"], lo=ic["l"], ref=ref, bi=ic["bi"])
                    self.log(f"{hhmm(bar['t'])} کاندید کندل مهم بالاترین‌ها: IC={{hi={ic['h']:.2f} lo={ic['l']:.2f}}} ref=max(close(a)={a['c']:.2f},open(b)={b['o']:.2f})={ref:.2f}")

    def _after_pin(self):
        """بعد از هر پین، tempbox یا HH/HL یا LL/LH تازه سنجیده می‌شود."""
        if self.trend == 0 and self.pinBU and self.pinBE:
            self.tempTop = self.pinBE["hi"]
            self.tempBot = self.pinBU["lo"]
        elif self.trend == 1 and self.pinBE and self.pinBU and self.HH is None:
            self.HH, self.HL = self.pinBE["hi"], self.pinBU["lo"]
            self.log(f"HH/HL تعریف شد: HH={self.HH:.2f} HL={self.HL:.2f}")
        elif self.trend == -1 and self.pinBU and self.pinBE and self.LL is None:
            self.LL, self.LH = self.pinBU["lo"], self.pinBE["hi"]
            self.log(f"LL/LH تعریف شد: LL={self.LL:.2f} LH={self.LH:.2f}")


# ══════════════════════════════════════════════════════════════════════════════
#  §۴-۵ : پروفایل حجم فیکس‌رنج + الگوریتم ناحیه
# ══════════════════════════════════════════════════════════════════════════════
def profile_and_zones(micro, bull, close):
    lo = min(m["l"] for m in micro)
    hi = max(m["h"] for m in micro)
    if hi <= lo:
        return None, []
    step = (hi - lo) / ROWS
    rv = [0.0] * ROWS
    for m in micro:
        spans = []
        for r in range(ROWS):
            rb, rt = lo + r * step, lo + (r + 1) * step
            ov = max(0.0, min(m["h"], rt) - max(m["l"], rb))
            spans.append(ov)
        tot = sum(spans)
        if tot <= 0:
            continue
        for r, ov in enumerate(spans):
            if ov > 0:
                rv[r] += m["v"] * ov / tot          # مدل یکنواخت ⇒ وزن برابر
    # ── الگوریتم ناحیه (صعودی: از کف به سقف · نزولی: قرینه) ──
    sd = 1 if bull else -1
    i = 0 if bull else ROWS - 1
    zones, guard = [], 0
    while guard < 4 * ROWS and len(zones) < 2:
        guard += 1
        j, climbed = i, False
        while True:
            while 0 <= j + sd < ROWS and rv[j + sd] > rv[j]:
                j += sd
                climbed = True
            while 0 <= j + sd < ROWS and rv[j + sd] == rv[j]:
                j += sd
            if not (0 <= j + sd < ROWS and rv[j + sd] > rv[j]):
                break
        if j != i:
            pk, climbed = j, True
        else:
            nx = i + sd
            pk = i if (0 <= nx < ROWS and rv[nx] < rv[i]) else -1
            climbed = pk >= 0
        if pk < 0 or not climbed:
            break
        k = pk
        while 0 <= k + sd < ROWS and rv[k + sd] < rv[k]:
            k += sd
        tr = k if (k != pk and 0 <= k + sd < ROWS) else -1
        if tr < 0:
            break
        loIdx, hiIdx = min(pk, tr), max(pk, tr)
        zBot, zTop = lo + loIdx * step, lo + (hiIdx + 1) * step
        ok = (zTop < close) if bull else (zBot > close)
        if zTop > zBot:
            zones.append(dict(top=zTop, bot=zBot, idx=len(zones) + 1, lo=loIdx, hi=hiIdx, accepted=ok))
        i = tr + sd
    return (lo, step, rv), zones


def hhmm(t):
    sod = (t // 1000) % 86400
    return f"{sod // 3600:02d}:{(sod % 3600) // 60:02d}"


def f2(x):
    return "-" if x is None else f"{x:.2f}"


# ══════════════════════════════════════════════════════════════════════════════
#  اجرای زنجیره روی داده
# ══════════════════════════════════════════════════════════════════════════════
def main():
    m1 = load(os.path.join(HERE, "m1.csv"))
    h1 = aggregate(m1, H1)
    m15 = aggregate(m1, M15)
    m5 = aggregate(m1, M5)

    out = []
    P = out.append

    P("═══ گزارش سند (اوراکل مستقل) روی سناریوی کنترل‌شده ═══")
    P(f"کندل‌های M1: {len(m1)} · ساختار H1 · میانی M15 · تریگر۱ M5 · تریگر۲ M1")
    P(f"mintick={MINTICK} slBuf={SL_BUF_TICKS}tick entryBuf={ENTRY_BUF_TICKS}tick N={N_SL_UNITS} leverage={LEVERAGE} risk={RISK_PCT}% equity={EQUITY0} contract={CONTRACT}")
    P("")

    # ── ساختار ──
    st = Structure()
    prev = None
    for bar in h1:
        st.on_bar(bar, prev)
        prev = bar
    P("── زنجیرهٔ ساختار (کندل مهم → Ready → پین → tempbox → روند → HH/HL) ──")
    out.extend("  " + e for e in st.events)
    P(f"  نتیجه: روند={st.trend} · pinBU={f2(st.pinBU['lo'] if st.pinBU else None)} · pinBE={f2(st.pinBE['hi'] if st.pinBE else None)} "
      f"· HH={f2(st.HH)} · HL={f2(st.HL)} · highestBE={f2(st.highestBE)}")
    P("")

    # ── ناحیهٔ ساختار: هر کندل H1 هم‌جهت با روند ──
    P("── ناحیه‌های ساختار (§۴-۵) ──")
    st2 = Structure()
    prev = None
    trend_at = {}
    for bar in h1:
        st2.on_bar(bar, prev)
        trend_at[bar["t"]] = st2.trend
        prev = bar

    zones_found = []
    for bar in h1:
        bull = bar["c"] > bar["o"]
        bear = bar["c"] < bar["o"]
        tr = trend_at[bar["t"]]
        if not ((tr == 1 and bull) or (tr == -1 and bear)):
            continue
        micro = [m for m in m1 if bucket(H1, m["t"]) == bar["t"]]
        prof, zs = profile_and_zones(micro, bull, bar["c"])
        if prof:
            P(f"  کندل {hhmm(bar['t'])} (o={bar['o']:.2f} h={bar['h']:.2f} l={bar['l']:.2f} c={bar['c']:.2f}) ردیف‌ها:")
            P("    " + " ".join(f"{v:.0f}" for v in prof[2]))
        for z in zs:
            zones_found.append((bar, z))
            P(f"    ناحیه Z{z['idx']} = [{z['bot']:.2f} , {z['top']:.2f}] rows={z['lo']}-{z['hi']} "
              f"{'پذیرفته' if z['accepted'] else 'ردشده (کلوز داخل محدوده)'}")
            if not z["accepted"]:
                continue
            P(f"    ← ستاپ منتظر برخورد روی این ناحیه ساخته شد (t={hhmm(bar['t'])})")
    P("")

    # ── ادامهٔ زنجیره روی ناحیهٔ پذیرفته‌شدهٔ اول ──
    target = None
    for bar, z in zones_found:
        if z["accepted"]:
            target = (bar, z)
            break
    if target is None:
        P("هیچ ناحیهٔ پذیرفته‌شده‌ای ساخته نشد.")
        write(out)
        return
    zbar, z = target
    zTop, zBot = z["top"], z["bot"]
    P(f"ناحیهٔ مرجع: [{zBot:.2f} , {zTop:.2f}] از کندل {hhmm(zbar['t'])}")
    P("")

    # ── برخورد و تأیید میانی (§۴-۶) ──
    P("── برخورد و تأیید میانی (§۴-۶) ──")
    touch = None
    for cd in m1:
        if cd["t"] < zbar["t"] + H1 * 1000:
            continue
        if cd["l"] <= zTop and cd["h"] >= zBot:
            touch = cd
            break
    P(f"  برخورد در {hhmm(touch['t'])} (کندل M1: o={touch['o']:.2f} h={touch['h']:.2f} l={touch['l']:.2f} c={touch['c']:.2f})")
    tbucket = bucket(M15, touch["t"])
    # §۴-۶: هر کندل میانی که بعد از برخورد بسته شود، سطح تایید می‌دهد؛
    # هر عبور یک‌تیکی از آن سطح، تایید را باطل می‌کند و «کندل بعدی» دوباره سنجیده می‌شود.
    midRef = None
    mid = None
    cur_m15 = None
    events_mid = []
    for cd in m1:
        if cd["t"] < tbucket:
            continue
        b = bucket(M15, cd["t"])
        if cur_m15 is not None and b != cur_m15:
            # کندل میانی قبلی بسته شد
            bar = next(x for x in m15 if x["t"] == cur_m15)
            if midRef is None:
                midRef = bar["l"]
                mid = bar
                events_mid.append(f"{hhmm(bar['t'])} تایید میانی: کف کندل = {midRef:.2f} (کلوز {bar['c']:.2f})")
        cur_m15 = b
        if midRef is not None and cd["l"] < midRef:
            events_mid.append(f"{hhmm(cd['t'])} عبور یک‌تیکی از سطح ابطال {midRef:.2f} (low={cd['l']:.2f}) ⇒ تایید میانی باطل؛ کندل بعدی سنجیده می‌شود")
            midRef = None
    for e in events_mid:
        P("  " + e)
    P(f"  سطح ابطال تایید میانی در پایان = {midRef:.2f} (کف کندل میانی {hhmm(mid['t'])})")
    P("")

    # ── ناحیهٔ حجم کم (§۴-۷) ──
    P("── ناحیهٔ حجم کم / تریگر۱ (§۴-۷) ──")
    m5_after = [b for b in m5 if b["t"] >= bucket(M5, touch["t"])]
    lv = None
    for i in range(1, len(m5_after)):
        if m5_after[i]["t"] < mid["t"] + M15 * 1000:
            continue
        if m5_after[i]["v"] < m5_after[i - 1]["v"]:
            lv, lvi = m5_after[i], i
            break
    P(f"  اولین کندل M5 با حجم کمتر از کندل قبلی: {hhmm(lv['t'])} · vol={lv['v']:.0f} < prev={m5_after[lvi-1]['v']:.0f} "
      f"· box=[{lv['l']:.2f} , {lv['h']:.2f}]")
    # تایید: اولین کلوز بالای سقف، بدون عبور از کف
    lvConf = None
    for b in m5_after[lvi + 1:]:
        if b["l"] < lv["l"]:
            P(f"  {hhmm(b['t'])}: عبور از کف باکس LV ⇒ باکس باطل و جست‌وجوی باکس تازه")
            break
        if b["c"] > lv["h"]:
            lvConf = b
            break
    P(f"  تایید ناحیهٔ حجم کم: M5 {hhmm(lvConf['t'])} با کلوز {lvConf['c']:.2f} > سقف {lv['h']:.2f}")
    P("")

    # ── حجم زیاد (§۴-۸) ──
    P("── ناحیهٔ حجم زیاد / تریگر۲ (§۴-۸) ──")
    conf_last_bi = lvConf["last_bi"]
    back = None
    for cd in m1:
        if cd["bi"] <= conf_last_bi:
            continue
        if cd["l"] <= lv["h"] and cd["h"] >= lv["l"]:
            back = cd
            break
    P(f"  اولین برگشت قیمت به باکس حجم کم: {hhmm(back['t'])} (سند §۴-۸: «شروع اسکن از اولین برگشت و برخورد یا ورود قیمت به باکس حجم کم»)")
    hv, hvi = None, None
    prevv = None
    for i, cd in enumerate(m1):
        if cd["bi"] < back["bi"]:
            prevv = cd["v"]
            continue
        if prevv is not None and cd["v"] > prevv:
            hv, hvi = cd, i
            break
        prevv = cd["v"]
    P(f"  اولین کندل بستهٔ M1 با حجم بیشتر از کندل قبلی: {hhmm(hv['t'])} · vol={hv['v']:.0f} > {prevv:.0f} "
      f"· box=[{hv['l']:.2f} , {hv['h']:.2f}]")
    hvConf = None
    for cd in m1[hvi + 1:]:
        if cd["l"] < hv["l"]:
            P(f"  {hhmm(cd['t'])}: عبور از کف باکس HV ⇒ مارک باطل")
            break
        if cd["c"] > hv["h"]:
            hvConf = cd
            break
    P(f"  تایید ناحیهٔ حجم زیاد: {hhmm(hvConf['t'])} با کلوز {hvConf['c']:.2f} > سقف {hv['h']:.2f}")
    entry = hv["h"] + ENTRY_BUF_TICKS * MINTICK
    sl = hv["l"] - SL_BUF_TICKS * MINTICK
    P(f"  سطح سفارش خرید = سقف باکس + ۱ تیک = {hv['h']:.2f} + {ENTRY_BUF_TICKS*MINTICK:.2f} = {entry:.2f}")
    P(f"  حد ضرر خرید = کف ناحیهٔ حجم زیاد − ۲ تیک = {hv['l']:.2f} − {SL_BUF_TICKS*MINTICK:.2f} = {sl:.2f}")
    P(f"  فاصلهٔ حد ضرر از سطح سفارش = {entry-sl:.2f} ≤ N={N_SL_UNITS} ⇒ سفارش ثبت می‌شود (§۱۸-۳۱)")
    P("")

    # ── حدهای سود (§۴-۸ فیبوناچی) ──
    P("── فیبوناچی و حدهای سود (§۴-۸ و §۱۸-۳۲) ──")
    a0 = st.highestBE
    a1 = midRef
    R = a0 - a1
    P(f"  لنگر صفر (جدیدترین HH/pinBE) = {a0:.2f} · لنگر ۱۰۰٪ (کف تایید میانی) = {a1:.2f} · R = {R:.2f}")
    tp1 = a1 + 0.382 * R
    tp2 = a1 + 0.500 * R
    tpx = a1 + 1.272 * R
    P(f"  TP1 = {a1:.2f} + 0.382×{R:.2f} = {tp1:.2f}")
    P(f"  TP2 = {a1:.2f} + 0.500×{R:.2f} = {tp2:.2f}")
    P(f"  TPX = {a1:.2f} + 1.272×{R:.2f} = {tpx:.2f}")
    if tpx - entry < MIN_TP_UNITS:
        P("  ⚠ فاصلهٔ TPX از ورود کمتر از ۱۰ ⇒ فالبک فعال می‌شد")
        tpx = entry + MIN_TP_UNITS
        tp1 = entry + MIN_TP_UNITS * 0.382
        tp2 = entry + MIN_TP_UNITS * 0.500
        P(f"  (فالبک) TP1={tp1:.2f} TP2={tp2:.2f} TPX={tpx:.2f} و فیبوناچی حذف")
    else:
        P(f"  فاصلهٔ TPX از ورود = {tpx-entry:.2f} ≥ ۱۰ ⇒ فیبوناچی معتبر می‌ماند")
    P("")

    # ── پر شدن و مدیریت ──
    P("── پر شدن سفارش و مدیریت معامله (§۴-۸ و §۴-۹) ──")
    fill = None
    for cd in m1:
        if cd["bi"] <= hvConf["bi"]:
            continue
        if cd["l"] <= entry:
            fill = cd
            break
    P(f"  پر شدن سفارش در {hhmm(fill['t'])} روی سطح {entry:.2f} (کندل پرشدن: l={fill['l']:.2f})")
    qty = EQUITY0 * RISK_PCT / 100.0 / (entry - sl)
    cap = EQUITY0 * LEVERAGE / entry
    P(f"  حجم طبق §۵: ریسک ۱٪ از {EQUITY0:.0f} ÷ فاصلهٔ حد ضرر {entry-sl:.2f} = {qty:.4f} (سقف اهرم = {cap:.2f} ⇒ اعمال نمی‌شود)")
    q = [qty * 0.33, qty * 0.33, qty - 2 * (qty * 0.33)]
    P(f"  پله‌ها: ۳۳٪ = {q[0]:.4f} · ۳۳٪ = {q[1]:.4f} · ۳۴٪ = {q[2]:.4f} (سند §۴-۹)")

    hits = []
    be = False
    tp1_done = tp2_done = False
    exit_t = None
    exit_p = None
    reason = None
    pnl = 0.0
    for cd in m1:
        if cd["bi"] <= fill["bi"]:
            continue
        # ترتیب سند: حد ضرر → حد سود نهایی → حد سود اول → حد سود دوم
        sl_now = entry if be else sl
        if cd["l"] <= sl_now:
            reason = "خروج سر‌به‌سر" if be else "حد ضرر"
            exit_t, exit_p = cd["t"], sl_now
            break
        if cd["h"] >= tpx:
            if not tp1_done and tp1 <= cd["h"]:
                hits.append(("TP1", tp1, q[0])); tp1_done = True; be = True
            if not tp2_done and tp2 <= cd["h"]:
                hits.append(("TP2", tp2, q[1])); tp2_done = True
            reason = "حد سود نهایی (1.272)"
            exit_t, exit_p = cd["t"], tpx
            break
        if not tp1_done and cd["h"] >= tp1:
            hits.append(("TP1", tp1, q[0])); tp1_done = True; be = True
        if tp1_done and not tp2_done and cd["h"] >= tp2:
            hits.append(("TP2", tp2, q[1])); tp2_done = True
    for (k, px, qq) in hits:
        pnl += (px - entry) * qq * CONTRACT
        P(f"  {k} لمس شد → {qq:.4f} واحد در {px:.2f} بسته شد")
    if exit_p is not None:
        used = sum(h[2] for h in hits)
        rem = qty - used
        pnl += (exit_p - entry) * rem * CONTRACT
        P(f"  {reason} → باقی‌ماندهٔ {rem:.4f} واحد در {exit_p:.2f} بسته شد ({hhmm(exit_t)})")
    P(f"  نتیجهٔ دلاری معامله = {pnl:.2f} · موجودی نهایی = {EQUITY0 + pnl:.2f}")
    P("")
    P("── جمع‌بندی سند ──")
    P("ستاپ=1 · برخورد=1 · تایید میانی=1 · LV=1 · HV=1 · ورود=1 · پایان=1")
    write(out)


def write(lines):
    path = os.path.join(HERE, "spec_report.txt")
    with open(path, "w", encoding="utf-8") as f:
        f.write("\n".join(lines) + "\n")
    print("\n".join(lines))
    print(f"\nSPEC REPORT → {path}")


if __name__ == "__main__":
    main()
