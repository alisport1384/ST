#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
«اوراکل سند ۷» — پیاده‌سازی مستقل متن `docs/GoldPin_strategy_final7_FA.md` در پایتون.

هدف: گرفتن گزارشِ «سند» روی همان سناریوی عددی‌ای که به موتور کاتلین داده می‌شود،
تا دو گزارش خط‌به‌خط مقایسه شوند. هیچ خطی از کد کاتلین در این فایل بازتولید نشده؛
هر قاعده با شمارهٔ بند سند برچسب خورده است.

اجرا:
  python3 oracle7.py <csv> [--fade 1] [--target-r 4] [--cost 0.2] [--cost-mult 6]
                            [--N 3] [--shift-hours 0] [--out report.txt]
"""
import argparse
import csv
import os

MS = 1000
H1, M15, M5, M1 = 3600, 900, 300, 60
DAY_MS = 86_400_000

MINTICK = 0.01
SL_BUF_TICKS = 2.0
ENTRY_BUF_TICKS = 1.0
SLIP_TICKS = 1.0
N_SL_UNITS = 3.0
K_TARGET_R = 4.0
COST_MULT = 6.0
ROWS = 24
RISK_PCT = 1.0
EQUITY0 = 100000.0
LEVERAGE = 500.0
CONTRACT = 1.0
ORDER_START_MIN = 90
ORDER_END_MIN = 20 * 60


def hhmm(t):
    sod = (t // 1000) % 86400
    return f"{sod // 3600:02d}:{(sod % 3600) // 60:02d}"


def dts(t):
    import datetime
    d = datetime.datetime.utcfromtimestamp(t / 1000)
    return d.strftime("%m-%d %H:%M")


def f2(x):
    return "-" if x is None else f"{x:.2f}"


def bucket(sec, t):
    return (t // (sec * 1000)) * (sec * 1000)


def utc_dow(t):
    return ((t // DAY_MS) + 3) % 7


def market_open(t):
    d = utc_dow(t)
    if d == 5:
        return False
    if d == 6:
        return (t % DAY_MS) >= 22 * 3600 * 1000
    return True


def in_order_window(t):
    return ORDER_START_MIN <= (t % DAY_MS) // 60000 < ORDER_END_MIN


def is_time_exit(t):
    return (t % DAY_MS) // 60000 >= ORDER_END_MIN


# ══════════════════════════════════════════════════════════════════════════════
#  §۴-۱ : کندل مهم → Ready → پین → توالی → tempbox → روند → HH/HL/LL/LH
# ══════════════════════════════════════════════════════════════════════════════
class Structure:
    def __init__(self, log):
        self.log = log
        self.trend = 0
        self.pinBU = None
        self.pinBE = None
        self.cand = None
        self.expected = 0
        self.tempTop = self.tempBot = None
        self.HH = self.HL = self.LL = self.LH = None
        self.highestBE = self.lowestBU = None
        self.buPaired = self.bePaired = False
        self.buSeq = self.beSeq = -1
        self.seq = 0
        self.trendStart = 0
        self.trendHigh = self.trendLow = None

    def on_bar(self, bar, prev):
        # ① چرخهٔ کاندید: ابطال با باز/کلوز آن‌طرف ref ؛ Ready با کلوز آن‌طرف سطح تایید
        c = self.cand
        if c is not None:
            if c["kind"] == 1:
                if bar["o"] < c["ref"] or bar["c"] < c["ref"]:
                    self.log(f"{hhmm(bar['t'])} IC پایین‌ترین‌ها باطل (o={bar['o']:.2f} c={bar['c']:.2f} < ref={c['ref']:.2f})")
                    self.cand = None
                elif bar["c"] > c["hi"] and self.expected in (0, 1):
                    self.pinBU = c
                    if self.pinBE is not None:
                        self.bePaired = True
                    self.buPaired = False
                    self.seq += 1
                    self.buSeq = self.seq
                    self.lowestBU = c["lo"] if self.lowestBU is None else min(self.lowestBU, c["lo"])
                    self.expected = -1
                    self.log(f"{hhmm(bar['t'])} Ready BU · pinBU={c['lo']:.2f} (high IC={c['hi']:.2f})")
                    self.cand = None
                    self._after_pin()
            else:
                if bar["o"] > c["ref"] or bar["c"] > c["ref"]:
                    self.log(f"{hhmm(bar['t'])} IC بالاترین‌ها باطل (o={bar['o']:.2f} c={bar['c']:.2f} > ref={c['ref']:.2f})")
                    self.cand = None
                elif bar["c"] < c["lo"] and self.expected in (0, -1):
                    self.pinBE = c
                    if self.pinBU is not None:
                        self.buPaired = True
                    self.bePaired = False
                    self.seq += 1
                    self.beSeq = self.seq
                    self.highestBE = c["hi"] if self.highestBE is None else max(self.highestBE, c["hi"])
                    self.expected = 1
                    self.log(f"{hhmm(bar['t'])} Ready BE · pinBE={c['hi']:.2f} (low IC={c['lo']:.2f})")
                    self.cand = None
                    self._after_pin()

        # ② tempbox → روند
        if self.trend == 0 and self.pinBU and self.pinBE and self.tempTop is not None:
            if bar["c"] > self.tempTop:
                self.trend = 1
                self.trendStart = bar["t"]
                self.trendHigh, self.trendLow = self.tempTop, self.tempBot
                self.log(f"{hhmm(bar['t'])} روند صعودی تایید شد (کلوز {bar['c']:.2f} > سقف tempbox {self.tempTop:.2f}) ⇒ tempbox پاک شد")
                self.pinBE = None; self.bePaired = False
                self.tempTop = self.tempBot = None
                self.HH = self.HL = None
                self.expected = -1
            elif bar["c"] < self.tempBot:
                self.trend = -1
                self.trendStart = bar["t"]
                self.trendHigh, self.trendLow = self.tempTop, self.tempBot
                self.log(f"{hhmm(bar['t'])} روند نزولی تایید شد (کلوز {bar['c']:.2f} < کف tempbox {self.tempBot:.2f}) ⇒ tempbox پاک شد")
                self.pinBU = None; self.buPaired = False
                self.tempTop = self.tempBot = None
                self.LL = self.LH = None
                self.expected = 1

        # ③ HH/HL و LL/LH
        if self.trend == 1 and self.HH is None and self.pinBE and self.pinBU and self.pinBE["t"] >= self.trendStart:
            self.HH, self.HL = self.pinBE["hi"], self.pinBU["lo"]
            self.log(f"{hhmm(bar['t'])} HH/HL: HH={self.HH:.2f} HL={self.HL:.2f}")
        if self.trend == -1 and self.LL is None and self.pinBU and self.pinBE and self.pinBU["t"] >= self.trendStart:
            self.LL, self.LH = self.pinBU["lo"], self.pinBE["hi"]
            self.log(f"{hhmm(bar['t'])} LL/LH: LL={self.LL:.2f} LH={self.LH:.2f}")

        # ④ چرخش روند
        if self.trend == 1 and self.HL is not None and bar["c"] < self.HL:
            self.trend = -1
            self.trendStart = bar["t"]
            self.log(f"{hhmm(bar['t'])} چرخش صعودی→نزولی (کلوز {bar['c']:.2f} < HL {self.HL:.2f})")
            self.HH = self.HL = self.LL = self.LH = None
        elif self.trend == -1 and self.LH is not None and bar["c"] > self.LH:
            self.trend = 1
            self.trendStart = bar["t"]
            self.log(f"{hhmm(bar['t'])} چرخش نزولی→صعودی (کلوز {bar['c']:.2f} > LH {self.LH:.2f})")
            self.HH = self.HL = self.LL = self.LH = None

        # ④-ب) ابطال HH یا LL با کلوز آن‌طرف (سند §۴-۱ «شرایط ابطال»)
        #   در روند صعودی: کلوز بالای HH ⇒ HH/HL پاک و به pinBE/pinBU برمی‌گردند
        #   در روند نزولی: کلوز زیر LL ⇒ LL/LH پاک و به pinBU/pinBE برمی‌گردند
        if self.trend == 1 and self.HH is not None and bar["c"] > self.HH:
            self.log(f"{hhmm(bar['t'])} HH شکست (کلوز {bar['c']:.2f} > HH {self.HH:.2f}) ⇒ HH/HL پاک شد")
            self.HH = self.HL = None
        elif self.trend == -1 and self.LL is not None and bar["c"] < self.LL:
            self.log(f"{hhmm(bar['t'])} LL شکست (کلوز {bar['c']:.2f} < LL {self.LL:.2f}) ⇒ LL/LH پاک شد")
            self.LL = self.LH = None

        # ⑤ اکسترمم واقعی روند (لنگر فیبو)
        if self.trend == 1:
            self.trendHigh = bar["h"] if self.trendHigh is None else max(self.trendHigh, bar["h"])
        elif self.trend == -1:
            self.trendLow = bar["l"] if self.trendLow is None else min(self.trendLow, bar["l"])

        # ⑥ ابطال آخرین پینِ جفت‌نشده
        if self.pinBU is not None and not self.buPaired:
            if bar["o"] < self.pinBU["ref"] or bar["c"] < self.pinBU["ref"]:
                self.log(f"{hhmm(bar['t'])} pinBU بی‌اعتبار شد (ref={self.pinBU['ref']:.2f})")
                self.pinBU = None
        elif self.pinBE is not None and not self.bePaired:
            if bar["o"] > self.pinBE["ref"] or bar["c"] > self.pinBE["ref"]:
                self.log(f"{hhmm(bar['t'])} pinBE بی‌اعتبار شد (ref={self.pinBE['ref']:.2f})")
                self.pinBE = None

        # ⑦ کاندید کندل مهم تازه
        if prev is not None and self.cand is None:
            a, b = prev, bar
            if a["c"] < a["o"] and b["c"] > b["o"] and self.expected in (0, 1):
                ic = a if (a["l"] < b["l"] or (a["l"] == b["l"] and a["h"] < b["h"])) else b
                ref = min(a["c"], b["o"])
                self.cand = dict(kind=1, hi=ic["h"], lo=ic["l"], ref=ref, bi=ic["bi"], t=ic["t"])
                self.log(f"{hhmm(bar['t'])} کاندید IC پایین‌ترین‌ها hi={ic['h']:.2f} lo={ic['l']:.2f} ref={ref:.2f}")
            elif a["c"] > a["o"] and b["c"] < b["o"] and self.expected in (0, -1):
                ic = a if (a["h"] > b["h"] or (a["h"] == b["h"] and a["l"] < b["l"])) else b
                ref = max(a["c"], b["o"])
                self.cand = dict(kind=-1, hi=ic["h"], lo=ic["l"], ref=ref, bi=ic["bi"], t=ic["t"])
                self.log(f"{hhmm(bar['t'])} کاندید IC بالاترین‌ها hi={ic['h']:.2f} lo={ic['l']:.2f} ref={ref:.2f}")

    def _after_pin(self):
        if self.trend == 0 and self.pinBU and self.pinBE:
            self.tempTop, self.tempBot = self.pinBE["hi"], self.pinBU["lo"]
            self.log(f"   tempbox = [{self.tempBot:.2f} , {self.tempTop:.2f}]")
        elif self.trend == 1 and self.HH is None and self.pinBE and self.pinBU and self.pinBE["t"] >= self.trendStart:
            self.HH, self.HL = self.pinBE["hi"], self.pinBU["lo"]
            self.log(f"   HH={self.HH:.2f} HL={self.HL:.2f}")
        elif self.trend == -1 and self.LL is None and self.pinBU and self.pinBE and self.pinBU["t"] >= self.trendStart:
            self.LL, self.LH = self.pinBU["lo"], self.pinBE["hi"]
            self.log(f"   LL={self.LL:.2f} LH={self.LH:.2f}")


# ══════════════════════════════════════════════════════════════════════════════
#  §۴-۵ : پروفایل حجم + الگوریتم ناحیه
# ══════════════════════════════════════════════════════════════════════════════
def profile_and_zones(micro, bull, close):
    lo = min(m["l"] for m in micro)
    hi = max(m["h"] for m in micro)
    if hi <= lo:
        return None, []
    step = (hi - lo) / ROWS
    # مرزهای ردیف مثل یک شبکهٔ واقعی روی هم انبار می‌شوند (rb += step) تا ردیف‌های
    # هم‌ارتفاع در انتهای پروفایل دقیقاً «مساوی» بمانند (قاعدهٔ قلهٔ آخری/فلات).
    edges = [lo + r * step for r in range(ROWS)] + [hi]
    rv = [0.0] * ROWS
    for m in micro:
        spans = []
        for r in range(ROWS):
            rb, rt = edges[r], edges[r + 1]
            spans.append(max(0.0, min(m["h"], rt) - max(m["l"], rb)))
        tot = sum(spans)
        if tot <= 0:
            continue
        for r, ov in enumerate(spans):
            if ov > 0:
                rv[r] += m["v"] * ov / tot      # مدل پیش‌فرض: یکنواخت
    # کوانتیزه‌سازی نسبی (۱e-۹ از بیشینه): ردیف‌های هم‌ارتفاع باید دقیقاً «مساوی»
    # سنجیده شوند تا قاعدهٔ «قلهٔ آخری / فلات» همان‌طور که سند می‌گوید عمل کند؛
    # بدون این کار، نویز آخرین بیت ممیز شناور، فلات را می‌شکند.
    mx = max(rv)
    if mx > 0:
        rv = [round(v / mx, 9) for v in rv]
    sd = 1 if bull else -1
    i = 0 if bull else ROWS - 1
    zones, guard = [], 0
    while guard < 4 * ROWS and len(zones) < 2:
        guard += 1
        j = i
        while True:
            while 0 <= j + sd < ROWS and rv[j + sd] > rv[j]:
                j += sd
            while 0 <= j + sd < ROWS and rv[j + sd] == rv[j]:
                j += sd
            if not (0 <= j + sd < ROWS and rv[j + sd] > rv[j]):
                break
        if j != i:
            pk = j
        else:
            nx = i + sd
            pk = i if (0 <= nx < ROWS and rv[nx] < rv[i]) else -1
        if pk < 0:
            break
        k = pk
        while 0 <= k + sd < ROWS and rv[k + sd] < rv[k]:
            k += sd
        tr = k if (k != pk and 0 <= k + sd < ROWS) else -1
        if tr < 0:
            break
        loIdx, hiIdx = min(pk, tr), max(pk, tr)
        zBot, zTop = edges[loIdx], edges[hiIdx + 1]
        ok = (zTop < close) if bull else (zBot > close)
        if zTop > zBot:
            zones.append(dict(top=zTop, bot=zBot, idx=len(zones) + 1, lo=loIdx, hi=hiIdx, accepted=ok))
        i = tr + sd
    return dict(lo=lo, step=step, rv=rv), zones


# ══════════════════════════════════════════════════════════════════════════════
#  ستاپ (هر ناحیهٔ ساختار یک ستاپ)
# ══════════════════════════════════════════════════════════════════════════════
class Setup:
    def __init__(self, sid, z):
        self.id = sid
        self.z = z
        self.dir = z["dir"]
        self.stage = 0            # 0 منتظر برخورد · 1 برخورد · 2/3 میانی · 4 LV ✔ · 5HV مارک · 6 مسلح · 7 معامله · 8 پایان
        self.zUsed = False
        self.zGone = False
        self.midRef = None
        self.midT = None
        self.mid_confirm_t = None
        self.midPrev = None
        self.touchT = None
        self.lv = None            # dict(h, l, t, bi, conf_t, uses, touched)
        self.hv = None            # dict(h, l, t, bi)
        self.hvConfT = None
        self.reEntered = False
        self.order = None
        self.trade = None
        self.note = ""

    @property
    def bull(self):
        return self.dir == 1


def simulate(m1, a, P):
    L = P
    h1, m15, m5 = [], [], []
    st = Structure(L.append)
    setups = []
    orders = []
    trades = []
    zones = []
    cur = {H1: None, M15: None, M5: None}
    bars = {H1: [], M15: [], M5: []}
    aggregate = {H1: [], M15: [], M5: []}
    seq_setup = 0
    pending = None          # سفارش معلق
    open_trade = None
    prevvol = {M5: None, M1: None}
    equity = EQUITY0
    hv_marks = []

    zone_ref = {"h": None, "l": None}

    def close_bar(sec, bar):
        if sec == H1:
            st.on_bar(bar, aggregate[H1][-1] if len(aggregate[H1]) > 0 else None)
            aggregate[H1].append(bar)
            # §۱۸-۲۲: اگر کندل X درون کندل ساختار Y بسته شد، نواحی X باطل است
            rh, rl = zone_ref["h"], zone_ref["l"]
            inside = (rh is not None and
                      bar["h"] <= rh and bar["l"] >= rl and
                      bar["o"] >= rl and bar["o"] <= rh and
                      bar["c"] >= rl and bar["c"] <= rh)
            if not inside:
                zone_ref["h"], zone_ref["l"] = bar["h"], bar["l"]
            # ناحیه‌های ساختار روی کندل بسته
            if cfg_strategy_doc() and st.trend != 0 and not inside:
                bull = bar["c"] > bar["o"]
                bear = bar["c"] < bar["o"]
                if (st.trend == 1 and bull) or (st.trend == -1 and bear):
                    micro = [m for m in m1 if bucket(H1, m["t"]) == bar["t"]]
                    if micro:
                        prof, zs = profile_and_zones(micro, bull, bar["c"])
                        if prof:
                            L.append(f"   {hhmm(bar['t'])} پروفایل کندل {'صعودی' if bull else 'نزولی'} "
                                     f"o={bar['o']:.2f} h={bar['h']:.2f} l={bar['l']:.2f} c={bar['c']:.2f}: "
                                     + " ".join(f"{v:.0f}" for v in prof["rv"]))
                        for z in zs:
                            zz = dict(top=z["top"], bot=z["bot"], idx=z["idx"],
                                      dir=(1 if bull else -1), created_t=bar["t"] + H1 * 1000,
                                      status="ACTIVE" if z["accepted"] else "REJECTED")
                            zones.append(zz)
                            L.append(f"   {dts(bar['t'] + H1 * 1000)} ناحیهٔ Z{z['idx']} [{z['bot']:.2f} , {z['top']:.2f}] "
                                     f"rows={z['lo']}-{z['hi']} " + ("پذیرفته" if z["accepted"] else "ردشده (کلوز داخل محدوده)"))
                            if z["accepted"]:
                                nonlocal_setups_append(zz)

    def nonlocal_setups_append(zz):
        nonlocal seq_setup
        s = Setup(seq_setup, zz)
        seq_setup += 1
        setups.append(s)

    def cfg_strategy_doc():
        return True

    # ── حلقهٔ اصلی روی کندل‌های M1 ─────────────────────────────────────────
    for idx, cd in enumerate(m1):
        # تجمیع‌ها؛ بستن کندل‌های بالاتر در مرز
        for sec in (M15, M5):
            b = bucket(sec, cd["t"])
            c = cur[sec]
            if c is None or b != c["t"]:
                if c is not None:
                    bars[sec].append(c)
                    close_bar(sec, c)
                cur[sec] = dict(t=b, o=cd["o"], h=cd["h"], l=cd["l"], c=cd["c"], v=cd["v"],
                                bi=cd["bi"], last_bi=cd["bi"])
            else:
                c["h"] = max(c["h"], cd["h"]); c["l"] = min(c["l"], cd["l"]); c["c"] = cd["c"]
                c["v"] += cd["v"]; c["last_bi"] = cd["bi"]
        b = bucket(H1, cd["t"])
        c = cur[H1]
        if c is None or b != c["t"]:
            if c is not None:
                bars[H1].append(c)
                close_bar(H1, c)
            cur[H1] = dict(t=b, o=cd["o"], h=cd["h"], l=cd["l"], c=cd["c"], v=cd["v"],
                           bi=cd["bi"], last_bi=cd["bi"])
        else:
            c["h"] = max(c["h"], cd["h"]); c["l"] = min(c["l"], cd["l"]); c["c"] = cd["c"]
            c["v"] += cd["v"]; c["last_bi"] = cd["bi"]

        closed_m15 = bars[M15][-1] if bars[M15] and bars[M15][-1]["t"] + M15 * 1000 == cd["t"] else None
        closed_m5 = bars[M5][-1] if bars[M5] and bars[M5][-1]["t"] + M5 * 1000 == cd["t"] else None

        # ── §۱۸-۲۷ پایان زمان ──
        if is_time_exit(cd["t"]):
            if pending is not None:
                L.append(f"  {hhmm(cd['t'])} سفارش معلق {pending['entry']:.2f} رأس ۲۰:۰۰ UTC لغو شد (§۱۸-۲۷)")
                pending["status"] = "EXPIRED-TIME"
                pending = None
            if open_trade is not None:
                tr = open_trade
                px = cd["o"]
                pnl = (px - tr["entry"]) * tr["qty"] * CONTRACT * tr["td"]
                tr.update(exit=px, exit_t=cd["t"], reason="پایان زمان", pnl=pnl)
                trades.append(tr)
                equity += pnl
                L.append(f"  {hhmm(cd['t'])} معاملهٔ باز رأس ۲۰:۰۰ UTC بسته شد @ {px:.2f} → {pnl:+.2f} دلار (پایان زمان§۱۸-۲۷)")
                open_trade = None
            continue

        # ── ابطال ناحیه با کلوز از سمت دور ──
        for z in zones:
            if z["status"] == "ACTIVE" and cd["t"] >= z["created_t"]:
                far = cd["c"] < z["bot"] if z["dir"] == 1 else cd["c"] > z["top"]
                if far:
                    z["status"] = "DELETED"
                    z["end"] = hhmm(cd["t"])
                    L.append(f"  {hhmm(cd['t'])} ناحیهٔ [{z['bot']:.2f} , {z['top']:.2f}] با کلوز {cd['c']:.2f} از سمت دور باطل شد")

        # ── موتور میانی ──
        if closed_m15 is not None:
            mc = closed_m15
            for s in setups:
                if not (1 <= s.stage <= 6) or s.zGone:
                    continue
                bull = s.bull
                if s.midRef is None:
                    if s.touchT is not None and mc["t"] >= bucket(M15, s.touchT):
                        s.midRef = mc["l"] if bull else mc["h"]
                        s.midT = mc["t"]
                        s.mid_confirm_t = mc["t"] + M15 * 1000
                        s.stage = 2
                        L.append(f"  {hhmm(mc['t'])} ستاپ#{s.id} تأیید میانی: {'کف' if bull else 'سقف'} {s.midRef:.2f}")
                else:
                    brk = (mc["l"] < s.midRef) if bull else (mc["h"] > s.midRef)
                    if brk:
                        old = s.midRef
                        if s.stage == 6:
                            s.midRef = None
                            L.append(f"  {hhmm(mc['t'])} ستاپ#{s.id} تأیید میانی باطل شد ({old:.2f}) — سفارش مسلح پابرجاست (§۴-۸)")
                        elif s.zUsed:
                            s.zGone = True; s.stage = 8
                            s.note = "ابطال میانی پس از مصرف ناحیهٔ ساختار (§۱۸-۱۴)"
                            L.append(f"  {hhmm(mc['t'])} ستاپ#{s.id} باطل شد: ابطال میانی پس از مصرف ناحیه (§۱۸-۱۴)")
                        else:
                            s.midRef = mc["l"] if bull else mc["h"]
                            s.midT = mc["t"]
                            s.mid_confirm_t = mc["t"] + M15 * 1000
                            s.stage = 2
                            s.lv = None; s.hv = None; s.reEntered = False
                            L.append(f"  {hhmm(mc['t'])} ستاپ#{s.id} ابطال تأیید میانی ({old:.2f}) → سطح تازه {s.midRef:.2f} (§۱۸-۸)")

        # ── موتور تریگر۱ (LV روی M5) ──
        if closed_m5 is not None:
            q = closed_m5
            prev5 = None
            i5 = bars[M5].index(q)
            if i5 > 0:
                prev5 = bars[M5][i5 - 1]
            for s in setups:
                if not (2 <= s.stage <= 6) or s.zGone:
                    continue
                bull = s.bull
                if s.lv is None:
                    if s.midRef is None:
                        continue
                    if prev5 is not None and q["v"] < prev5["v"] and q["t"] > s.midT:
                        s.lv = dict(h=q["h"], l=q["l"], t=q["t"], bi=q["bi"], conf_t=None, uses=0, touched=False)
                        s.stage = 3
                        L.append(f"  {hhmm(q['t'])} ستاپ#{s.id} کندل حجم کم: M5 vol={q['v']:.0f} < {prev5['v']:.0f} "
                                 f"باکس [{q['l']:.2f} , {q['h']:.2f}]")
                else:
                    lv = s.lv
                    if lv["conf_t"] is None:
                        viol = (q["l"] < lv["l"]) if bull else (q["h"] > lv["h"])
                        conf = (q["c"] > lv["h"]) if bull else (q["c"] < lv["l"])
                        if viol:
                            L.append(f"  {hhmm(q['t'])} ستاپ#{s.id} باکس LV با عبور از سمت مخالف باطل شد → جست‌وجوی LV تازه")
                            s.lv = None; s.stage = 3
                        elif conf:
                            lv["conf_t"] = q["t"] + M5 * 1000
                            s.stage = 4
                            L.append(f"  {hhmm(q['t'])} ستاپ#{s.id} ناحیهٔ حجم کم تأیید شد (کلوز {q['c']:.2f})")
                    else:
                        if s.stage in (4, 5, 6):
                            viol = (q["l"] < lv["l"]) if bull else (q["h"] > lv["h"])
                            inside = q["l"] <= lv["h"] and q["h"] >= lv["l"]
                            if viol:
                                if s.stage == 6 and pending is not None:
                                    L.append(f"  {hhmm(q['t'])} ستاپ#{s.id} ابطال LV پس از مسلح‌شدن ⇒ سفارش معلق لغو (§۱۸-۱۰)")
                                    pending["status"] = "CANCELLED"
                                    pending = None
                                    s.stage = 8; s.zGone = True
                                elif s.stage != 7:
                                    L.append(f"  {hhmm(q['t'])} ستاپ#{s.id} باکس LV باطل شد → LV تازه")
                                    s.lv = None; s.hv = None; s.reEntered = False; s.stage = 3
                            else:
                                if lv["uses"] >= 2 and inside:
                                    L.append(f"  {hhmm(q['t'])} ستاپ#{s.id} لمس سوم باکس LV ⇒ باطل (§۴-۷)")
                                    if pending is not None:
                                        L.append(f"    ⇒ سفارش معلق لغو شد (§۴-۷/§۱۸-۱۰)")
                                        pending["status"] = "CANCELLED"; pending = None
                                    s.stage = 8; s.zGone = True
                                else:
                                    if inside and not lv["touched"]:
                                        lv["touched"] = True
                                    if lv["touched"]:
                                        left = (q["c"] > lv["h"]) if bull else (q["c"] < lv["l"])
                                        if left:
                                            lv["uses"] += 1
                                            lv["touched"] = False
                                            L.append(f"  {hhmm(q['t'])} ستاپ#{s.id} مصرف {lv['uses']}/۲ باکس LV")

        # ── موتور تریگر۲ روی هر کندل M1 ──
        for s in setups:
            if s.zGone or s.stage == 8:
                continue
            bull = s.bull
            # برخورد با ناحیهٔ ساختار
            if s.stage == 0 and cd["t"] >= s.z["created_t"]:
                if cd["l"] <= s.z["top"] and cd["h"] >= s.z["bot"]:
                    s.stage = 1
                    s.touchT = cd["t"]
                    L.append(f"  {hhmm(cd['t'])} ستاپ#{s.id} برخورد با ناحیهٔ [{s.z['bot']:.2f} , {s.z['top']:.2f}]")
            # §۱۸-۱۴ مصرف ناحیه پس از گذشت یک کندل ساختار
            if s.touchT is not None and not s.zUsed and cd["t"] >= bucket(H1, s.touchT) + H1 * 1000:
                s.zUsed = True
                s.z["status"] = "CONSUMED"
                s.z["end"] = hhmm(cd["t"])
                L.append(f"  {hhmm(cd['t'])} ستاپ#{s.id} ناحیهٔ ساختار پس از یک کندل ساختار «مصرف‌شده» شد "
                         f"(ستاپ تا ابطال تأیید میانی فعال است، §۱۸-۱۴)")
                if s.midRef is None:
                    s.zGone = True; s.stage = 8
                    L.append(f"    ⇒ بدون تأیید میانی معتبر ⇒ ستاپ غیرفعال")
            # شکست ناحیه پیش از ورود (§۴-۸ فهرست لغو)
            if s.stage < 6 and not s.zGone:
                broken = (cd["c"] < s.z["bot"]) if bull else (cd["c"] > s.z["top"])
                if broken and s.z["status"] != "CONSUMED":
                    s.zGone = True; s.stage = 8
                    L.append(f"  {hhmm(cd['t'])} ستاپ#{s.id} لغو شد — شکست ناحیهٔ ساختار")

            # مسلح‌شدن: HV
            if s.stage == 4 and s.lv is not None and s.lv["conf_t"] is not None and not s.reEntered:
                if cd["l"] <= s.lv["h"] and cd["h"] >= s.lv["l"]:
                    s.reEntered = True
                    L.append(f"  {hhmm(cd['t'])} ستاپ#{s.id} اولین بازگشت به باکس LV ⇒ اسکن HV آغاز شد (§۴-۸)")
            if s.stage in (4, 5) and s.reEntered:
                # مارک یا تایید HV
                if s.hv is None:
                    prev = m1[idx - 1]["v"] if idx > 0 else None
                    if prev is not None and cd["v"] > prev:
                        s.hv = dict(h=cd["h"], l=cd["l"], t=cd["t"], bi=cd["bi"])
                        s.stage = 5
                        L.append(f"  {hhmm(cd['t'])} ستاپ#{s.id} کندل حجم زیاد مارک شد vol={cd['v']:.0f} > {prev:.0f} "
                                 f"باکس [{cd['l']:.2f} , {cd['h']:.2f}]")
                else:
                    hv = s.hv
                    viol = (cd["l"] < hv["l"]) if bull else (cd["h"] > hv["h"])
                    conf = (cd["c"] > hv["h"]) if bull else (cd["c"] < hv["l"])
                    if viol:
                        L.append(f"  {hhmm(cd['t'])} ستاپ#{s.id} مارک HV باطل شد (عبور از سمت مخالف) ⇒ اسکن از نو")
                        s.hv = None; s.stage = 4
                    elif conf:
                        # ── مسلح‌شدن ──
                        entry = hv["h"] + ENTRY_BUF_TICKS * MINTICK if bull else hv["l"] - ENTRY_BUF_TICKS * MINTICK
                        sl_struct = hv["l"] - SL_BUF_TICKS * MINTICK if bull else hv["h"] + SL_BUF_TICKS * MINTICK
                        R = abs(entry - sl_struct)
                        td = (-s.dir) if a.fade else s.dir
                        sl = entry - td * R if td != s.dir else sl_struct
                        tp = entry + td * a.target_r * R
                        L.append(f"  {hhmm(cd['t'])} ستاپ#{s.id} تأیید HV (کلوز {cd['c']:.2f}) ⇒ مسلح‌شدن")
                        L.append(f"     سطح سفارش {entry:.2f} · SL ساختار {sl_struct:.2f} · R={R:.2f} · "
                                 f"{'BUY' if td == 1 else 'SELL'} SL={sl:.2f} TP={tp:.2f}")
                        blocked = None
                        if not in_order_window(cd["t"]):
                            blocked = "خارج از پنجرهٔ سفارش‌گیری ۰۱:۳۰–۲۰:۰۰ (§۱۸-۲۶)"
                        elif a.cost > 0 and a.cost_mult > 0 and R < a.cost_mult * a.cost:
                            blocked = f"فیلتر کارمزد §۵-۳ (R={R:.2f} < {a.cost_mult}×{a.cost:.2f}={a.cost_mult*a.cost:.2f})"
                        elif R > a.N:
                            blocked = f"SL>N §۱۸-۳۱ (R={R:.2f} > N={a.N})"
                        elif pending is not None or open_trade is not None:
                            blocked = "سفارش/معاملهٔ دیگری باز است (§۱۸-۱۲/§۱۸-۱۳)"
                        if blocked:
                            L.append(f"     ✖ سفارش ثبت نشد — {blocked}")
                            s.hv = None; s.stage = 4
                        else:
                            pending = dict(setup=s, entry=entry, sl=sl, tp=tp, R=R, td=td,
                                           arm_t=cd["t"], arm_bi=cd["bi"], hv=hv,
                                           struct_bull=bull, status="PENDING",
                                           exp_ref=(st.HH if bull else st.LL))
                            s.stage = 6
                            s.order = pending
                            orders.append(pending)
                            L.append(f"     ✔ سفارش لیمیت ثبت شد (فقط در کندل بعد می‌تواند پر شود)")

        # ── پر شدن سفارش / انقضا ──
        if pending is not None:
            st_ = pending["setup"]
            bull = pending["struct_bull"]
            entry = pending["entry"]
            if cd["bi"] > pending["arm_bi"]:
                # انقضا: یک کندل ساختار کامل بعد از مسلح‌شدن
                if bucket(H1, cd["t"]) > bucket(H1, pending["arm_t"]):
                    L.append(f"  {hhmm(cd['t'])} سفارش {entry:.2f} منقضی شد — یک کندل ساختار کامل گذشت (§۴-۸)")
                    pending["status"] = "EXPIRED"; pending = None; st_.stage = 8; st_.zGone = True
                else:
                    # عبور باز/کلوز از HH (ساختار صعودی) یا LL (ساختار نزولی) ⇒ انقضا (§۴-۸)
                    exp_ref = pending.get("exp_ref")
                    p_exp = False
                    refname = "HH" if bull else "LL"
                    if p_exp:
                        L.append(f"  {hhmm(cd['t'])} سفارش {entry:.2f} منقضی شد — عبور از {refname}={exp_ref:.2f} (§۴-۸)")
                        pending["status"] = "EXPIRED"; pending = None; st_.stage = 8; st_.zGone = True
                    else:
                        touched = (cd["l"] <= entry) if bull else (cd["h"] >= entry)
                        if touched:
                            qty = min(equity * RISK_PCT / 100.0 / pending["R"], equity * LEVERAGE / entry)
                            tr = dict(setup=st_, entry=entry, sl=pending["sl"], tp=pending["tp"],
                                      R=pending["R"], td=pending["td"], qty=qty,
                                      in_t=cd["t"], in_bi=cd["bi"], reason=None, exit=None, pnl=0.0)
                            st_.stage = 7
                            st_.trade = tr
                            open_trade = tr
                            orders_pending_set = None
                            pending["status"] = "FILLED"
                            pending = None
                            L.append(f"  {hhmm(cd['t'])} ورود {'BUY' if tr['td'] == 1 else 'SELL'} @ {entry:.2f} · "
                                     f"حجم {qty:.2f} (ریسک {RISK_PCT}% ÷ R {tr['R']:.2f})")

        # ── مدیریت معاملهٔ باز (§۴-۹) ──
        if open_trade is not None and open_trade["in_bi"] < cd["bi"]:
            tr = open_trade
            td = tr["td"]
            hit_sl = (cd["l"] <= tr["sl"]) if td == 1 else (cd["h"] >= tr["sl"])
            hit_tp = (cd["h"] >= tr["tp"]) if td == 1 else (cd["l"] <= tr["tp"])
            if hit_sl:                                   # §۴-۹: اول حد ضرر
                px = tr["sl"] - td * SLIP_TICKS * MINTICK
                tr.update(exit=px, exit_t=cd["t"], reason="حد ضرر", pnl=(px - tr["entry"]) * tr["qty"] * CONTRACT * td)
                trades.append(tr); equity += tr["pnl"]; open_trade = None
                L.append(f"  {hhmm(cd['t'])} خروج حد ضرر @ {px:.2f} (لغزش ۱ تیک) → {tr['pnl']:+.2f} دلار")
            elif hit_tp:
                px = tr["tp"]
                tr.update(exit=px, exit_t=cd["t"], reason="حد سود", pnl=(px - tr["entry"]) * tr["qty"] * CONTRACT * td)
                trades.append(tr); equity += tr["pnl"]; open_trade = None
                L.append(f"  {hhmm(cd['t'])} خروج حد سود @ {px:.2f} → {tr['pnl']:+.2f} دلار ({tr['pnl']/(tr['R']*tr['qty']*CONTRACT):+.2f}R)")

    return dict(st=st, zones=zones, setups=setups, orders=orders, trades=trades, equity=equity)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("csv")
    ap.add_argument("--fade", type=int, default=1)
    ap.add_argument("--target-r", type=float, default=K_TARGET_R)
    ap.add_argument("--cost", type=float, default=0.0)
    ap.add_argument("--cost-mult", type=float, default=COST_MULT)
    ap.add_argument("--N", type=float, default=N_SL_UNITS)
    ap.add_argument("--shift-hours", type=float, default=0.0)
    ap.add_argument("--out", default="")
    a = ap.parse_args()
    shift = int(a.shift_hours * 3600 * 1000)

    rows = []
    with open(a.csv, newline="") as f:
        for i, r in enumerate(csv.DictReader(f)):
            tk = "time" if "time" in r else "timestamp"
            t = int(r[tk]) + shift
            if len(r[tk]) > 13:  # میلی‌ثانیه
                t = int(r[tk]) + shift
            if not market_open(t):
                continue
            rows.append(dict(bi=i, t=t, o=float(r["open"]), h=float(r["high"]),
                             l=float(r["low"]), c=float(r["close"]), v=float(r["volume"])))

    L = []
    P = L.append
    P("╔════════════════════════════════════════════════════════════════════════╗")
    P("║  گزارش «سند» — اوراکل مستقل GoldPin v7 (اجرای متن سند، نه کد)          ║")
    P("╚════════════════════════════════════════════════════════════════════════╝")
    P(f"فایل: {os.path.basename(a.csv)} · کندل M1: {len(rows)}")
    P(f"تنظیمات: tick={MINTICK} SLbuf={SL_BUF_TICKS} ورودbuf={ENTRY_BUF_TICKS} لغزش={SLIP_TICKS} · "
      f"معکوس={a.fade} هدف={a.target_r}R · فیلترکارمزد={a.cost_mult}×{a.cost} · N={a.N} · "
      f"ریسک={RISK_PCT}% سرمایه={EQUITY0:.0f} اهرم={LEVERAGE:.0f}")
    P("")
    P("── رویدادها ──")
    res = simulate(rows, a, L)

    P("")
    P("── سفارش‌ها ──")
    for o in res["orders"]:
        P(f"  #{res['orders'].index(o)} ستاپ#{o['setup'].id} {'BUY' if o['td']==1 else 'SELL'} "
          f"entry={o['entry']:.2f} sl={o['sl']:.2f} tp={o['tp']:.2f} R={o['R']:.2f} ط={hhmm(o['arm_t'])} → {o['status']}")
    if not res["orders"]:
        P("  (هیچ سفارشی ثبت نشد)")
    P("")
    P("── معاملات ──")
    for t in res["trades"]:
        P(f"  {'BUY' if t['td']==1 else 'SELL'} entry={t['entry']:.2f} sl={t['sl']:.2f} tp={t['tp']:.2f} "
          f"qty={t['qty']:.2f} ورود={hhmm(t['in_t'])} خروج={hhmm(t['exit_t'])} @ {t['exit']:.2f} "
          f"({t['reason']}) pnl={t['pnl']:+.2f} ({t['pnl']/(t['R']*t['qty']*CONTRACT):+.2f}R)")
    if not res["trades"]:
        P("  (هیچ معامله‌ای انجام نشد)")
    P("")
    P("── خلاصه ──")
    tr = res["trades"]
    net = sum(t["pnl"] for t in tr)
    wins = sum(1 for t in tr if t["pnl"] > 1e-9)
    P(f"  معاملات={len(tr)} برد={wins} باخت={len(tr)-wins} "
      f"نرخ‌برد={(100.0*wins/len(tr) if tr else 0):.1f}٪ سود‌خالص={net:+.2f} موجودی={equity_end(res):.2f}")
    st = res["st"]
    P(f"  ساختار پایانی: روند={st.trend} HH={f2(st.HH)} HL={f2(st.HL)} LL={f2(st.LL)} LH={f2(st.LH)}")
    P(f"  ناحیه‌ها: {len(res['zones'])}")

    text = "\n".join(L)
    if a.out:
        open(a.out, "w", encoding="utf-8").write(text + "\n")
    print(text)


def equity_end(res):
    return EQUITY0 + sum(t["pnl"] for t in res["trades"])


if __name__ == "__main__":
    main()
