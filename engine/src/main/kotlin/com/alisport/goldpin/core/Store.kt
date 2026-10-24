package com.alisport.goldpin.core

// ═══════════════════════════════════════════════════════════════════════════════
//  ذخیره و بازیابی کامل وضعیت — «هر چه روی چارت بود، دقیقاً همان برگردد»
//  شامل: تنظیمات · کندل‌ها · موتور (روند/پین‌ها/ستاپ‌ها/باکس‌های ناحیه/مارکرها/
//        شمارنده‌ها) · کارگزار (سفارش‌ها/معاملات/موجودی/افت سرمایه)
// ═══════════════════════════════════════════════════════════════════════════════
object Store {
    /** نسخهٔ هم‌ترازی تنظیمات با سند؛ فایل‌های با مقدار کمتر هنگام بارگذاری به پیش‌فرض‌های سند مهاجرت می‌کنند. */
    const val SPEC_REV = 9


    const val FORMAT = 3

    class Loaded(
        val cfg: Settings,
        val eng: Engine,
        val broker: PaperBroker,
        val candles: List<Candle>,
        val structureCandles: List<Candle>,
        val meta: Map<String, Any?>,
        val savedAt: Long
    )

    // ── نوشتن ──────────────────────────────────────────────────────────────────
    fun save(
        cfg: Settings,
        eng: Engine,
        broker: PaperBroker,
        candles: List<Candle>,
        meta: Map<String, Any?> = emptyMap(),
        savedAt: Long = System.currentTimeMillis(),
        candleFrom: Int = 0,
        structureCandles: List<Candle> = emptyList()
    ): String {
        val root = LinkedHashMap<String, Any?>()
        root["format"] = FORMAT
        root["savedAt"] = savedAt
        root["settings"] = settingsTo(cfg)
        root["meta"] = meta
        root["engine"] = engineTo(eng)
        root["broker"] = brokerTo(broker)

        val from = candleFrom.coerceIn(0, candles.size)
        val cs = ArrayList<Any?>(candles.size - from)
        for (i in from until candles.size) {
            val c = candles[i]
            cs.add(listOf(c.bi, c.t, c.o, c.h, c.l, c.c, c.v))
        }
        root["candleFrom"] = from
        root["candles"] = cs
        root["structureCandles"] = candleListTo(structureCandles)
        return Json.write(root)
    }

    private fun settingsTo(c: Settings): Map<String, Any?> = linkedMapOf(
        "tfMode" to c.tfMode, "tfS" to c.tfS, "tfM" to c.tfM, "tf1" to c.tf1, "tf2" to c.tf2,
        "vpRows" to c.vpRows, "vpVA" to c.vpVA, "vpSmooth" to c.vpSmooth, "distMode" to c.distMode,
        "minZoneRows" to c.minZoneRows, "drawZones" to c.drawZones, "showRejectedZones" to c.showRejectedZones,
        "clearUsedZones" to true, "maxZoneBoxes" to Int.MAX_VALUE, "showPocVA" to c.showPocVA,
        "maxSetups" to c.maxSetups, "midInvalidClose" to c.midInvalidClose,
        "hvScanFirstTouch" to c.hvScanFirstTouch, "hvMarkFirstIncrease" to c.hvMarkFirstIncrease,
        "rejectCandleNext" to c.rejectCandleNext,
        "mintick" to c.mintick, "slBufTicks" to c.slBufTicks, "entryBufTicks" to c.entryBufTicks, "minTPunits" to c.minTPunits,
        "slipTicks" to c.slipTicks,
        "strategy" to c.strategy, "tpUnits" to c.tpUnits, "maxSlUnits" to c.maxSlUnits,
        "minSlUnits" to c.minSlUnits,
        "trigger2Fade" to c.trigger2Fade, "trigger2TargetR" to c.trigger2TargetR, "minSlCostMult" to c.minSlCostMult,
        "useRiskPct" to c.useRiskPct, "riskPct" to c.riskPct, "equityPct" to c.equityPct,
        "maxLeverage" to c.maxLeverage, "levModel" to 2, "roundQty" to c.roundQty,
        "contractSize" to c.contractSize, "initialEquity" to c.initialEquity,
        // §۲۰ سند — مدیریت مالی و مدیریت زیان
        "mmMode" to c.mmMode, "minLotAuto" to c.minLotAuto, "minLotManual" to c.minLotManual,
        "divAuto" to c.divAuto, "divManual" to c.divManual,
        "cmsnAuto" to c.cmsnAuto, "cmsnManual" to c.cmsnManual,
        "cmsnAutoValue" to c.cmsnAutoValue, "cmsnAutoAt" to c.cmsnAutoAt,
        "crtEnabled" to c.crtEnabled, "crtPct" to c.crtPct,
        "mixedGrowthPct" to c.mixedGrowthPct,
        "lotStep" to c.lotStep, "lostResetAtFinalTp" to c.lostResetAtFinalTp,
        "specRev" to SPEC_REV
    )

    private fun settingsFrom(m: Map<String, Any?>): Settings {
        val c = Settings()
        if (m.isEmpty()) return c
        c.tfMode = m["tfMode"].asI(); c.tfS = m["tfS"].asI(); c.tfM = m["tfM"].asI()
        c.tf1 = m["tf1"].asI(); c.tf2 = m["tf2"].asI()
        c.vpRows = m["vpRows"].asI(); c.vpVA = m["vpVA"].asD(); c.vpSmooth = m["vpSmooth"].asI()
        c.distMode = m["distMode"].asI(); c.minZoneRows = m["minZoneRows"].asI()
        c.drawZones = m["drawZones"].asB(); c.showRejectedZones = m["showRejectedZones"].asB()
        // تنظیمات قدیمی ممکن است ابطال را خاموش یا سقف باکس را محدود کرده باشند؛
        // سند فعلی ابطال با کلوز و ذخیرهٔ نامحدود ناحیه‌ها را اجباری می‌داند.
        c.clearUsedZones = true; c.maxZoneBoxes = Int.MAX_VALUE
        c.showPocVA = m["showPocVA"].asB()
        c.maxSetups = m["maxSetups"].asI(); c.midInvalidClose = m["midInvalidClose"].asB()
        c.hvScanFirstTouch = m["hvScanFirstTouch"].asB(); c.hvMarkFirstIncrease = m["hvMarkFirstIncrease"].asB()
        // rejectCandleNext در فایل‌های نسخهٔ ۱٫۳ و قبل‌تر وجود ندارد → asB(null)=false = مقدار پیش‌فرض
        c.rejectCandleNext = m["rejectCandleNext"].asB()
        c.mintick = m["mintick"].asD(); c.slBufTicks = m["slBufTicks"].asD(); c.minTPunits = m["minTPunits"].asD()
        c.useRiskPct = m["useRiskPct"].asB(); c.riskPct = m["riskPct"].asD(); c.equityPct = m["equityPct"].asD()
        c.maxLeverage = m["maxLeverage"].asD(); c.roundQty = m["roundQty"].asB()
        c.contractSize = m["contractSize"].asD(); c.initialEquity = m["initialEquity"].asD()
        // کلیدهای جدید در فایل‌های قدیمی نیستند ⇒ مقدار پیش‌فرض Settings() می‌ماند (asB(null)=false درست نیست).
        if (m.containsKey("entryBufTicks")) c.entryBufTicks = m["entryBufTicks"].asD()
        // §۵: لغزش یک تیک؛ فایل‌های قدیمی کلید ندارند ⇒ پیش‌فرض سند (۱ تیک) می‌ماند.
        if (m.containsKey("slipTicks")) c.slipTicks = m["slipTicks"].asD()
        // استراتژی انتخابی: فایل‌های قدیمی کلید ندارند ⇒ استراتژی ۱ (سند) می‌ماند.
        if (m.containsKey("strategy") && Strategy.valid(m["strategy"].asI())) c.strategy = m["strategy"].asI()
        if (m.containsKey("tpUnits")) c.tpUnits = m["tpUnits"].asD()
        // §18-31 سند نهایی: سقف فاصلهٔ حد ضرر (N)؛ پیش‌فرض ویرایش جدید ۱۰ واحد است.
        // برای حفظ تنظیمات سفارشی، فایل قدیمی‌ای که N را از مقدار پیش‌فرض قبلی (۳) تغییر داده
        // باشد حفظ می‌شود؛ مقدار قدیمیِ پیش‌فرض ۳ به پیش‌فرض جدید ۱۰ مهاجرت می‌کند.
        if (m.containsKey("maxSlUnits")) c.maxSlUnits = m["maxSlUnits"].asD()
        if (m.containsKey("minSlUnits")) c.minSlUnits = m["minSlUnits"].asD()
        if (m.containsKey("trigger2Fade")) c.trigger2Fade = m["trigger2Fade"].asB()
        if (m.containsKey("trigger2TargetR")) c.trigger2TargetR = m["trigger2TargetR"].asD()
        if (m.containsKey("minSlCostMult")) c.minSlCostMult = m["minSlCostMult"].asD()
        if (m.containsKey("mmMode")) c.mmMode = m["mmMode"].asI()
        if (m.containsKey("minLotAuto")) c.minLotAuto = m["minLotAuto"].asB()
        if (m.containsKey("minLotManual")) c.minLotManual = m["minLotManual"].asD()
        if (m.containsKey("divAuto")) c.divAuto = m["divAuto"].asB()
        if (m.containsKey("divManual")) c.divManual = m["divManual"].asD()
        if (m.containsKey("cmsnAuto")) c.cmsnAuto = m["cmsnAuto"].asB()
        if (m.containsKey("cmsnManual")) c.cmsnManual = m["cmsnManual"].asD()
        if (m.containsKey("cmsnAutoValue")) c.cmsnAutoValue = m["cmsnAutoValue"].asD()
        if (m.containsKey("cmsnAutoAt")) c.cmsnAutoAt = m["cmsnAutoAt"].asL()
        if (m.containsKey("crtEnabled")) c.crtEnabled = m["crtEnabled"].asB()
        if (m.containsKey("crtPct")) c.crtPct = m["crtPct"].asD()
        if (m.containsKey("mixedGrowthPct")) c.mixedGrowthPct = m["mixedGrowthPct"].asD()
        // مهاجرت اهرم: پیش‌تر دو متغیر بود (§۵ = ۱۰ و §۲۰ = mmMaxLeverage). حالا یکی است و طبق §۵
        // پیش‌فرض ۱۰ است. فایل‌های قدیمیِ بدون نشانهٔ levModel که مقدار §۲۰ جدا داشتند، همان مقدار
        // کاربر (mmMaxLeverage) را می‌گیرند؛ بقیه دقیقاً همان عددِ ذخیره‌شده را نگه می‌دارند.
        if (!m.containsKey("levModel") && m.containsKey("mmMaxLeverage"))
            c.maxLeverage = m["mmMaxLeverage"].asD()
        if (m.containsKey("lotStep")) c.lotStep = m["lotStep"].asD()
        if (m.containsKey("lostResetAtFinalTp")) c.lostResetAtFinalTp = m["lostResetAtFinalTp"].asB()
        // مهاجرت به سند فعلی: فایل‌های قبل از specRev=۲ مقدارهای قدیمی اهرم/بافر سفارش/حداکثر ستاپ را داشتند
        // (۱۰ / ۲ تیک / ۶). سند: اهرم ۵۰۰ (§۵) ، بافر سفارش ۱ تیک (§۴-۸) ، حداکثر ستاپ ۱ (§۱۸-۱۹).
        val savedSpecRev = if (m.containsKey("specRev")) m["specRev"].asI() else 0
        if (savedSpecRev < 2) {
            c.maxLeverage = 500.0; c.entryBufTicks = 1.0; c.maxSetups = 1
        }
        // §18-31: سند نهایی مقدار پیش‌فرض N را از ۳ به ۱۰ تغییر داده است.
        // فایل‌های rev 0–2 که مقدار ۳ را فقط به‌عنوان پیش‌فرض قبلی ذخیره کرده‌اند مهاجرت می‌شوند؛
        // مقادیر سفارشی دیگر حفظ می‌شوند.
        if (savedSpecRev < 3 && (!m.containsKey("maxSlUnits") || m["maxSlUnits"].asD() == 3.0)) {
            c.maxSlUnits = 10.0
        }
        // specRev 4 (ویرایش جدید سند): پیش‌فرض‌های §۴-۵ و §۱۸-۳۱ عوض شدند —
        // «هموارسازی ۰» ، «مدل پخش حجم یکنواخت» و «N = ۳ واحد». فقط وقتی مقدار ذخیره‌شده
        // دقیقاً همان پیش‌فرض قدیمی باشد مهاجرت می‌کند؛ عدد سفارشی کاربر دست نمی‌خورد.
        if (savedSpecRev < 4) {
            if (c.vpSmooth == 1) c.vpSmooth = 0
            if (c.distMode == VolumeProfile.DIST_TRI_CLOSE) c.distMode = VolumeProfile.DIST_UNIFORM
            if (c.maxSlUnits == 10.0) c.maxSlUnits = 3.0
        }
        // specRev 6: پیش‌فرض §۱۸-۳۱ به ۳ بازگشت. فقط فایل‌هایی که مقدار ۲۰ دارند (پیش‌فرض نسخهٔ
        // ۱.۴.۳۱) به ۳ مهاجرت می‌کنند؛ هر عدد سفارشی دیگری (مثل ۷) دست‌نخورده می‌ماند.
        if (savedSpecRev < 6 && c.maxSlUnits == 20.0) c.maxSlUnits = 3.0
        // specRev 7 (سند ۷): پیش‌فرض‌های «حالت معامله» عوض شدند — تریگر ۲ معکوس **روشن**،
        // هدف ۴R و فیلتر کارمزد ۶ (§۵-۴ سند). اگر هر سه مقدار در فایل ذخیره‌شده دقیقاً
        // پیش‌فرض‌های نسخهٔ ۶ باشند (خاموش / ۰ / ۰)، یعنی کاربر آن‌ها را تغییر نداده است و
        // به پیش‌فرض‌های سند ۷ منتقل می‌شوند. اگر کاربر حتی یکی را دست‌کاری کرده باشد،
        // هر سه مقدار دست‌نخورده می‌مانند تا انتخاب او از بین نرود.
        if (savedSpecRev < 7 && !c.trigger2Fade && c.trigger2TargetR == 0.0 && c.minSlCostMult == 0.0) {
            c.trigger2Fade = true
            c.trigger2TargetR = 4.0
            c.minSlCostMult = 6.0
        }
        // specRev 8 (۱٫۴٫۴۰): «اسپردِ خودکارِ بروکر» دیگر به‌طور پیش‌فرض گرفته نمی‌شود و اگر
        // مقدارِ ذخیره‌شده از یک اسپرد خرده‌فروشی بزرگ (بیش از ۰٫۵ دلار) باشد، فیلتر کارمزد
        // §۵-۳ با ضریب ۶ همهٔ سفارش‌ها را رد می‌کند (فاصلهٔ حد ضرر این استراتژی ~۱٫۴–۲ دلار).
        // همین باعث می‌شد با روشن‌کردن مود Mixed / ManageAllLost «کل معاملات صفر» شود و
        // بعد از خاموش‌کردن مود هم برنگردد.
        //
        // پس: اگر فایل ذخیره‌شده اسپرد خودکار بزرگ دارد، «خودکار» خاموش می‌شود و همان عدد به
        //‌عنوان مقدار **دستی** نگه داشته می‌شود تا کاربر در تنظیمات ببیندش و خودش تصمیم بگیرد
        // (سند §۹۲۶: کارمزد واقعی بروکر را دستی وارد کنید). مقدار دستیِ کاربر هرگز عوض نمی‌شود.
        if (savedSpecRev < 8 && c.cmsnAuto && c.cmsnAutoValue > 0.5) {
            // اسپرد بروکر به‌عنوان «مرجع» می‌ماند ولی مبنای فیلتر نمی‌شود؛ مبنا می‌شود عدد
            // مرجع سند برای طلا (۰٫۲۰ دلار) اگر کاربر خودش عددی وارد نکرده باشد.
            if (c.cmsnManual <= 0.0) c.cmsnManual = 0.20
            c.cmsnAuto = false
            c.cmsnMigratedFromAuto = c.cmsnAutoValue
        }
        // specRev 9 (۱٫۴٫۴۲): تنظیمِ آزموده‌شده با داده ([Tuned]) جای پیش‌فرض سند ۷ می‌نشیند.
        // با کارمزد ۰٫۷ دلار، پیش‌فرض سند ۷ روی دادهٔ یک‌سالهٔ خود اپ **صفر سفارش** می‌دهد
        // (آستانهٔ فیلتر ۴٫۲ > سقف N=۳) و حد ضرر ۲ تیکی هم داخل نویز است. فقط فایل‌هایی مهاجرت
        // می‌شوند که مقدارهایشان دقیقاً همان پیش‌فرض دست‌نخوردهٔ سند ۷ باشد؛ تنظیم دستی کاربر
        // دست‌نخورده می‌ماند (و هر زمان بخواهد از بخش «تطبیق» در اپ برمی‌گردد).
        if (savedSpecRev < 9 && Tuned.isUntouchedDoc7(c)) {
            // کارمزد: اگر فایل همین حالا مبنای کارمزد دارد (اسپرد خودکار معقولِ بروکر، یا عدد
            // دستی کاربر — مثلاً همان ۰٫۲۰ که مهاجرت نسخهٔ ۸ گذاشته)، آن عدد واقعی بر حدسِ
            // مرجع (۰٫۷) اولویت دارد و دست‌نخورده می‌ماند. فقط وقتی هیچ مبنایی نبود، مرجع
            // آزموده‌شده می‌نشیند.
            val hasBasis = c.cmsnManual > 0.0 || (c.cmsnAuto && c.cmsnAutoValue > 0.0)
            Tuned.apply(c, if (hasBasis) 0.0 else Tuned.CMSN)
        }
        return c
    }

    private fun aggTo(a: Agg, key: String, out: MutableMap<String, Any?>) {
        out["${key}_periods"] = a.periods
        out["${key}_cur"] = a.cur?.let { listOf(it.bi, it.t, it.o, it.h, it.l, it.c, it.v) }
        out["${key}_cl"] = a.cl?.let { listOf(it.bi, it.t, it.o, it.h, it.l, it.c, it.v) }
    }

    private fun aggFrom(m: Map<String, Any?>, key: String, a: Agg) {
        a.periods = m["${key}_periods"].asI()
        a.cur = candleFrom(m["${key}_cur"])
        // فایل‌های قدیمی ممکن است در اولین بازه periods=0 ذخیره کرده باشند؛
        // کندل جاری باید هنگام رسیدن کندل بعدی بسته شود.
        if (a.cur != null && a.periods < 1) a.periods = 1
        a.cl = candleFrom(m["${key}_cl"])
        a.closedNow = false
    }

    private fun candleFrom(v: Any?): Candle? {
        val l = v.asList()
        if (l.size < 7) return null
        return Candle(l[0].asI(), l[1].asL(), l[2].asD(), l[3].asD(), l[4].asD(), l[5].asD(), l[6].asD())
    }

    private fun candleListTo(l: List<Candle>): List<Any?> = l.map { c -> listOf(c.bi, c.t, c.o, c.h, c.l, c.c, c.v) }

    private fun candleListFrom(v: Any?): MutableList<Candle> {
        val out = ArrayList<Candle>()
        for (e in v.asList()) candleFrom(e)?.let { out.add(it) }
        return out
    }

    private fun engineTo(e: Engine): Map<String, Any?> {
        val o = LinkedHashMap<String, Any?>()
        aggTo(e.aggS, "S", o); aggTo(e.aggM, "M", o); aggTo(e.agg1, "T1", o)
        o["structBars"] = candleListTo(e.structBars)
        o["microS"] = candleListTo(e.microS)
        o["pins"] = e.pins.map { listOf(it.kind, it.hi, it.lo, it.impBi, it.startBi, it.ref, it.status) }
        o["setups"] = e.setups.map { setupTo(it) }
        o["zones"] = e.zones.map { listOf(it.id, it.dir, it.idx, it.top, it.bot, it.createdBi, it.createdT, it.loIdx, it.hiIdx, it.endBi, it.endT, it.status) }
        o["t1Zones"] = e.t1Zones.map { listOf(it.id, it.dir, it.idx, it.top, it.bot, it.createdBi, it.createdT, it.loIdx, it.hiIdx, it.endBi, it.endT, it.status) }
        o["t2Zones"] = e.t2Zones.map { listOf(it.id, it.dir, it.idx, it.top, it.bot, it.createdBi, it.createdT, it.loIdx, it.hiIdx, it.endBi, it.endT, it.status) }
        o["markers"] = e.markers.map { listOf(it.t, it.bi, it.price, it.kind, it.text) }
        o["events"] = e.events.takeLast(400)
        o["cnt"] = listOf(e.cnt.setup, e.cnt.touch, e.cnt.mid, e.cnt.lv, e.cnt.hv, e.cnt.entry, e.cnt.done, e.cnt.zoneCount, e.cnt.zoneRejected)
        o["trend"] = e.trend
        o["HH"] = e.HH; o["HL"] = e.HL; o["LL"] = e.LL; o["LH"] = e.LH
        o["tempBoxTop"] = e.tempBoxTop; o["tempBoxBot"] = e.tempBoxBot
        o["hlPinLo"] = e.hlPinLo; o["hlPinRef"] = e.hlPinRef; o["hlPinHi"] = e.hlPinHi; o["hlPinSeq"] = e.hlPinSeq
        o["hhPinHi"] = e.hhPinHi; o["hhPinRef"] = e.hhPinRef; o["hhPinLo"] = e.hhPinLo; o["hhPinSeq"] = e.hhPinSeq
        o["lhPinHi"] = e.lhPinHi; o["lhPinRef"] = e.lhPinRef; o["lhPinLo"] = e.lhPinLo; o["lhPinSeq"] = e.lhPinSeq
        o["llPinLo"] = e.llPinLo; o["llPinRef"] = e.llPinRef; o["llPinHi"] = e.llPinHi; o["llPinSeq"] = e.llPinSeq
        o["seqStart"] = e.seqStart
        o["bullBU1Low"] = e.bullBU1Low; o["bullBEHigh"] = e.bullBEHigh
        o["bullBU2Low"] = e.bullBU2Low; o["bullBU2Bi"] = e.bullBU2Bi
        o["bearBE1High"] = e.bearBE1High; o["bearBULow"] = e.bearBULow
        o["bearBE2High"] = e.bearBE2High; o["bearBE2Bi"] = e.bearBE2Bi
        o["bullSetup"] = e.bullSetup; o["bearSetup"] = e.bearSetup
        o["expectedReady"] = e.expectedReady
        o["lowestBUpin"] = e.lowestBUpin; o["highestBE"] = e.highestBE
        o["lastBUpin"] = e.lastBUpin; o["lastBEpin"] = e.lastBEpin
        o["buActive"] = e.buActive; o["beActive"] = e.beActive
        o["pinSeq"] = e.pinSeq; o["buSeq"] = e.buSeq; o["beSeq"] = e.beSeq
        o["lastBUpinRef"] = e.lastBUpinRef; o["lastBUpinHi"] = e.lastBUpinHi
        o["lastBEpinRef"] = e.lastBEpinRef; o["lastBEpinLo"] = e.lastBEpinLo
        o["lastZoneTop"] = e.lastZoneTop; o["lastZoneBot"] = e.lastZoneBot
        o["zoneRefH"] = e.zoneRefH; o["zoneRefL"] = e.zoneRefL
        o["lastPocPx"] = e.lastPocPx; o["lastProfBars"] = e.lastProfBars
        o["lastEntryPx"] = e.lastEntryPx; o["lastSlPx"] = e.lastSlPx; o["lastTpPx"] = e.lastTpPx
        o["lastResult"] = e.lastResult
        o["prevT1Vol"] = e.prevT1Vol; o["prevChartVol"] = e.prevChartVol
        o["zoneSeq"] = e.zoneSeq; o["setupSeq"] = e.setupSeq; o["processed"] = e.processed
        o["curBi"] = e.curBi; o["curT"] = e.curT
        return o
    }

    private fun engineFrom(m: Map<String, Any?>, cfg: Settings): Engine {
        val e = Engine(cfg)
        aggFrom(m, "S", e.aggS); aggFrom(m, "M", e.aggM); aggFrom(m, "T1", e.agg1)
        e.structBars.addAll(candleListFrom(m["structBars"]))
        e.microS.addAll(candleListFrom(m["microS"]))
        for (p in m["pins"].asList()) {
            val l = p.asList(); if (l.size < 7) continue
            e.pins.add(Pin(l[0].asI(), l[1].asD(), l[2].asD(), l[3].asI(), l[4].asI(), l[5].asD(), l[6].asI()))
        }
        for (s in m["setups"].asList()) e.setups.add(setupFrom(s.asMap()))
        for (z in m["zones"].asList()) {
            val l = z.asList(); if (l.size < 12) continue
            e.zones.add(ZoneBox(l[0].asL(), l[1].asI(), l[2].asI(), l[3].asD(), l[4].asD(), l[5].asI(), l[6].asL(), l[7].asI(), l[8].asI(), l[9].asI(), l[10].asL(), l[11].asI()))
        }
        for (z in m["t1Zones"].asList()) {
            val l = z.asList(); if (l.size < 12) continue
            e.t1Zones.add(ZoneBox(l[0].asL(), l[1].asI(), l[2].asI(), l[3].asD(), l[4].asD(), l[5].asI(), l[6].asL(), l[7].asI(), l[8].asI(), l[9].asI(), l[10].asL(), l[11].asI()))
        }
        for (z in m["t2Zones"].asList()) {
            val l = z.asList(); if (l.size < 12) continue
            e.t2Zones.add(ZoneBox(l[0].asL(), l[1].asI(), l[2].asI(), l[3].asD(), l[4].asD(), l[5].asI(), l[6].asL(), l[7].asI(), l[8].asI(), l[9].asI(), l[10].asL(), l[11].asI()))
        }
        for (mk in m["markers"].asList()) {
            val l = mk.asList(); if (l.size < 5) continue
            e.markers.add(Marker(l[0].asL(), l[1].asI(), l[2].asD(), l[3].asI(), l[4].asS()))
        }
        for (ev in m["events"].asList()) e.events.add(ev.asS())
        val cn = m["cnt"].asList()
        if (cn.size >= 9) {
            e.cnt.setup = cn[0].asI(); e.cnt.touch = cn[1].asI(); e.cnt.mid = cn[2].asI()
            e.cnt.lv = cn[3].asI(); e.cnt.hv = cn[4].asI(); e.cnt.entry = cn[5].asI()
            e.cnt.done = cn[6].asI(); e.cnt.zoneCount = cn[7].asI(); e.cnt.zoneRejected = cn[8].asI()
        }
        e.trend = m["trend"].asI()
        e.HH = m["HH"].asD(); e.HL = m["HL"].asD(); e.LL = m["LL"].asD(); e.LH = m["LH"].asD()
        e.tempBoxTop = m["tempBoxTop"].asD(); e.tempBoxBot = m["tempBoxBot"].asD()
        e.hlPinLo = m["hlPinLo"].asD(); e.hlPinRef = m["hlPinRef"].asD(); e.hlPinHi = m["hlPinHi"].asD()
        e.hlPinSeq = if (m.containsKey("hlPinSeq")) m["hlPinSeq"].asI() else -1
        e.hhPinHi = m["hhPinHi"].asD(); e.hhPinRef = m["hhPinRef"].asD(); e.hhPinLo = m["hhPinLo"].asD()
        e.hhPinSeq = if (m.containsKey("hhPinSeq")) m["hhPinSeq"].asI() else -1
        e.lhPinHi = m["lhPinHi"].asD(); e.lhPinRef = m["lhPinRef"].asD(); e.lhPinLo = m["lhPinLo"].asD()
        e.lhPinSeq = if (m.containsKey("lhPinSeq")) m["lhPinSeq"].asI() else -1
        e.llPinLo = m["llPinLo"].asD(); e.llPinRef = m["llPinRef"].asD(); e.llPinHi = m["llPinHi"].asD()
        e.llPinSeq = if (m.containsKey("llPinSeq")) m["llPinSeq"].asI() else -1
        e.seqStart = m["seqStart"].asI()
        e.bullBU1Low = m["bullBU1Low"].asD(); e.bullBEHigh = m["bullBEHigh"].asD()
        e.bullBU2Low = m["bullBU2Low"].asD(); e.bullBU2Bi = m["bullBU2Bi"].asI()
        e.bearBE1High = m["bearBE1High"].asD(); e.bearBULow = m["bearBULow"].asD()
        e.bearBE2High = m["bearBE2High"].asD(); e.bearBE2Bi = m["bearBE2Bi"].asI()
        e.bullSetup = m["bullSetup"].asB(); e.bearSetup = m["bearSetup"].asB()
        e.expectedReady = m["expectedReady"].asI()
        e.lowestBUpin = m["lowestBUpin"].asD(); e.highestBE = m["highestBE"].asD()
        e.lastBUpin = m["lastBUpin"].asD(); e.lastBEpin = m["lastBEpin"].asD()
        e.buActive = m["buActive"].asB(); e.beActive = m["beActive"].asB()
        e.pinSeq = m["pinSeq"].asI()
        e.buSeq = if (m.containsKey("buSeq")) m["buSeq"].asI() else -1
        e.beSeq = if (m.containsKey("beSeq")) m["beSeq"].asI() else -1
        e.lastBUpinRef = m["lastBUpinRef"].asD(); e.lastBUpinHi = m["lastBUpinHi"].asD()
        e.lastBEpinRef = m["lastBEpinRef"].asD(); e.lastBEpinLo = m["lastBEpinLo"].asD()
        e.lastZoneTop = m["lastZoneTop"].asD(); e.lastZoneBot = m["lastZoneBot"].asD()
        if (m.containsKey("zoneRefH")) e.zoneRefH = m["zoneRefH"].asD()
        if (m.containsKey("zoneRefL")) e.zoneRefL = m["zoneRefL"].asD()
        e.lastPocPx = m["lastPocPx"].asD(); e.lastProfBars = m["lastProfBars"].asI()
        e.lastEntryPx = m["lastEntryPx"].asD(); e.lastSlPx = m["lastSlPx"].asD(); e.lastTpPx = m["lastTpPx"].asD()
        e.lastResult = m["lastResult"].asS()
        e.prevT1Vol = m["prevT1Vol"].asD(); e.prevChartVol = m["prevChartVol"].asD()
        e.zoneSeq = m["zoneSeq"].asL(); e.setupSeq = m["setupSeq"].asL(); e.processed = m["processed"].asI()
        e.curBi = m["curBi"].asI(); e.curT = m["curT"].asL()
        // پاک‌سازی مارکرهای پینِ مردهٔ ذخیره‌های قدیمی (§18-4): ذخیره‌هایی که پیش از اصلاح
        // تناوب مارکرها ساخته شده‌اند ممکن است دو مارکر هم‌جهتِ پشت‌سرهم داشته باشند.
        e.prunePinMarkerAlternation()
        e.rebuildActiveZoneIndex()
        return e
    }

    private fun setupTo(s: Setup): Map<String, Any?> = linkedMapOf(
        "id" to s.id, "dir" to s.dir, "stage" to s.stage, "zTop" to s.zTop, "zBot" to s.zBot,
        "zIdx" to s.zIdx, "zBi" to s.zBi, "zT" to s.zT, "touchBi" to s.touchBi, "touchMidStart" to s.touchMidStart,
        "midRef" to s.midRef, "midBi" to s.midBi, "midT" to s.midT, "markH" to s.markH, "markL" to s.markL, "markBi" to s.markBi,
        "lvH" to s.lvH, "lvL" to s.lvL, "lvBi" to s.lvBi, "lvT" to s.lvT, "lvConfBi" to s.lvConfBi, "lvConfT" to s.lvConfT, "lvZoneId" to s.lvZoneId,
        "hvH" to s.hvH, "hvL" to s.hvL, "hvBi" to s.hvBi, "hvT" to s.hvT, "hvScanBi" to s.hvScanBi, "hvConfBi" to s.hvConfBi, "hvConfT" to s.hvConfT, "hvZoneId" to s.hvZoneId, "slBlockedHvBi" to s.slBlockedHvBi,
        "midConfirmBi" to s.midConfirmBi, "runMax" to s.runMax, "zGone" to s.zGone, "zUsed" to s.zUsed, "touchT" to s.touchT,
        "lvTouched" to s.lvTouched, "lvUsed" to s.lvUsed, "lvReEntered" to s.lvReEntered, "lvUses" to s.lvUses, "slMidDone" to s.slMidDone,
        "entry" to s.entry, "sl" to s.sl, "tp1" to s.tp1, "tp2" to s.tp2, "tpx" to s.tpx,
        "be" to s.be, "tp2Hit" to s.tp2Hit, "note" to s.note, "orderId" to s.orderId,
        "armBi" to s.armBi, "armT" to s.armT, "armsSinceS" to s.armsSinceS, "prevEntry" to s.prevEntry,
        "rearmAllowed" to s.rearmAllowed, "priceExpiryRef" to s.priceExpiryRef, "entryBi" to s.entryBi, "entryT" to s.entryT,
        "fib0" to s.fib0, "fib1" to s.fib1, "fib382" to s.fib382, "fib500" to s.fib500, "fib1272" to s.fib1272, "fibValid" to s.fibValid
    )

    private fun setupFrom(m: Map<String, Any?>): Setup {
        val s = Setup(m["id"].asL(), m["dir"].asI(), m["stage"].asI(), m["zTop"].asD(), m["zBot"].asD(), m["zIdx"].asI(), m["zBi"].asI())
        s.zT = m["zT"].asL()
        s.touchBi = m["touchBi"].asI(); s.touchMidStart = m["touchMidStart"].asL()
        s.midRef = m["midRef"].asD(); s.midBi = m["midBi"].asI(); s.midT = m["midT"].asL()
        s.markH = m["markH"].asD(); s.markL = m["markL"].asD(); s.markBi = m["markBi"].asI()
        s.lvH = m["lvH"].asD(); s.lvL = m["lvL"].asD(); s.lvBi = m["lvBi"].asI(); s.lvT = m["lvT"].asL(); s.lvConfBi = m["lvConfBi"].asI(); s.lvConfT = m["lvConfT"].asL()
        s.lvZoneId = if (m.containsKey("lvZoneId")) m["lvZoneId"].asL() else -1L
        s.hvH = m["hvH"].asD(); s.hvL = m["hvL"].asD(); s.hvBi = m["hvBi"].asI(); s.hvT = m["hvT"].asL()
        s.hvScanBi = m["hvScanBi"].asI(); s.hvConfBi = m["hvConfBi"].asI(); s.hvConfT = m["hvConfT"].asL()
        s.hvZoneId = if (m.containsKey("hvZoneId")) m["hvZoneId"].asL() else -1L
        s.slBlockedHvBi = if (m.containsKey("slBlockedHvBi")) m["slBlockedHvBi"].asI() else -1
        s.midConfirmBi = m["midConfirmBi"].asI(); s.runMax = m["runMax"].asD(); s.zGone = m["zGone"].asB()
        s.zUsed = m["zUsed"].asB(); s.touchT = m["touchT"].asL()
        s.lvTouched = m["lvTouched"].asB(); s.lvUsed = m["lvUsed"].asB(); s.lvReEntered = m["lvReEntered"].asB()
        s.lvUses = m["lvUses"].asI(); s.slMidDone = m["slMidDone"].asB()
        s.entry = m["entry"].asD(); s.sl = m["sl"].asD(); s.tp1 = m["tp1"].asD(); s.tp2 = m["tp2"].asD()
        s.tpx = m["tpx"].asD(); s.be = m["be"].asB()
        // tp2Hit در فایل‌های ذخیره‌شدهٔ نسخه‌های قبل وجود ندارد → false پیش‌فرض
        s.tp2Hit = m["tp2Hit"].asB()
        s.note = m["note"].asS(); s.orderId = m["orderId"].asL()
        s.armBi = if (m.containsKey("armBi")) m["armBi"].asI() else -1
        s.armT = m["armT"].asL()
        s.armsSinceS = m["armsSinceS"].asI()
        s.prevEntry = m["prevEntry"].asD()
        s.rearmAllowed = m["rearmAllowed"].asB()
        s.priceExpiryRef = m["priceExpiryRef"].asD() // فایل‌های قدیمی: NaN و در اولین بررسی از آخرین پین پر می‌شود.
        s.entryBi = if (m.containsKey("entryBi")) m["entryBi"].asI() else -1
        s.entryT = m["entryT"].asL()
        if (m.containsKey("fib0")) s.fib0 = m["fib0"].asD()
        if (m.containsKey("fib1")) s.fib1 = m["fib1"].asD()
        if (m.containsKey("fib382")) s.fib382 = m["fib382"].asD()
        if (m.containsKey("fib500")) s.fib500 = m["fib500"].asD()
        if (m.containsKey("fib1272")) s.fib1272 = m["fib1272"].asD()
        if (m.containsKey("fibValid")) s.fibValid = m["fibValid"].asB()
        return s
    }

    private fun brokerTo(b: PaperBroker): Map<String, Any?> {
        val o = LinkedHashMap<String, Any?>()
        o["balance"] = b.balance; o["equity"] = b.equity
        // balModel=2: balance فقط معاملات کاملاً بسته‌شده را دارد (نسخه‌های قبل: پله‌های خروج/کمیسیون معاملهٔ باز هم داخلش بود)
        o["balModel"] = 2
        o["maxEquity"] = b.maxEquity; o["maxDrawdown"] = b.maxDrawdown
        o["orderSeq"] = b.orderSeq; o["tradeSeq"] = b.tradeSeq
        // §۲۰: وضعیت مدیریت زیان (فایل‌های قدیمی این کلیدها را ندارند ⇒ ۰)
        o["mmLost"] = b.mm.lost; o["mmCmsnLost"] = b.mm.cmsnLost; o["mmNaked"] = b.mm.nakedCapital()
        // ۱۹ ستون اول مثل قبل؛ ستون ۲۰ و ۲۱ (§۲۰): توضیح محاسبهٔ حجم، فاز ManageCM
        o["orders"] = b.orders.map { listOf(it.id, it.setupId, it.dir, it.type, it.price, it.sl, it.tp1, it.tp2, it.tpX, it.qty, it.zoneTop, it.zoneBot, it.placedT, it.placedBi, it.status, it.filledT ?: 0L, it.filledBi ?: -1, it.closedT ?: 0L, it.cancelReason ?: "", it.mmNote, it.mmCm, it.hvTop, it.hvBot, it.touchDir) }
        o["trades"] = b.trades.map { t ->
            linkedMapOf<String, Any?>(
                "id" to t.id, "orderId" to t.orderId, "setupId" to t.setupId, "dir" to t.dir,
                "entryT" to t.entryT, "entryBi" to t.entryBi, "entry" to t.entry,
                "sl0" to t.sl0, "tp1" to t.tp1, "tp2" to t.tp2, "tpX" to t.tpX, "qty" to t.qty,
                "zoneTop" to t.zoneTop, "zoneBot" to t.zoneBot, "contractSize" to t.contractSize,
                "exitT" to (t.exitT ?: 0L), "exitBi" to (t.exitBi ?: -1), "exitPx" to (t.exitPx ?: Double.NaN),
                "reason" to t.reason, "be" to t.be,
                "hvTop" to t.hvTop, "hvBot" to t.hvBot,
                "commission" to t.commission, "mmCm" to t.mmCm,
                "fills" to t.fills.map { listOf(it.t, it.bi, it.price, it.qty, it.kind, it.pnl) }
            )
        }
        o["pendingIds"] = b.pendingOrders.map { it.id }
        o["openIds"] = b.openTrades.map { it.id }
        // سازگاری با نسخه‌های قبل (تک‌سفارش):
        o["pendingId"] = b.pendingOrder?.id ?: -1L
        o["openId"] = b.openTrade?.id ?: -1L
        return o
    }

    private fun brokerFrom(m: Map<String, Any?>, cfg: Settings, e: Engine): PaperBroker {
        val b = PaperBroker(cfg)
        b.engine = e
        b.balance = m["balance"].asD(); b.equity = m["equity"].asD()
        b.maxEquity = m["maxEquity"].asD(); b.maxDrawdown = m["maxDrawdown"].asD()
        b.orderSeq = m["orderSeq"].asL(); b.tradeSeq = m["tradeSeq"].asL()
        if (m.containsKey("mmLost")) b.mm.lost = m["mmLost"].asD()
        if (m.containsKey("mmCmsnLost")) b.mm.cmsnLost = m["mmCmsnLost"].asD()
        if (m.containsKey("mmNaked")) b.mm.restoreNaked(m["mmNaked"].asD())
        for (x in m["orders"].asList()) {
            val l = x.asList(); if (l.size < 19) continue
            val or = Order(l[0].asL(), l[1].asL(), l[2].asI(), l[3].asS(), l[4].asD(), l[5].asD(), l[6].asD(),
                l[7].asD(), l[8].asD(), l[9].asD(), l[10].asD(), l[11].asD(), l[12].asL(), l[13].asI(), l[14].asI())
            or.touchDir = if (l.size >= 24) l[23].asI() else l[2].asI()
            or.filledT = if (l[15].asL() == 0L) null else l[15].asL()
            or.filledBi = if (l[16].asI() < 0) null else l[16].asI()
            or.closedT = if (l[17].asL() == 0L) null else l[17].asL()
            or.cancelReason = l[18].asS().ifEmpty { null }
            if (l.size >= 21) { or.mmNote = l[19].asS(); or.mmCm = l[20].asB() }
            if (l.size >= 23) { or.hvTop = l[21].asD(); or.hvBot = l[22].asD() }
            b.orders.add(or)
        }
        for (x in m["trades"].asList()) {
            val t = x.asMap()
            val tr = Trade(t["id"].asL(), t["orderId"].asL(), t["setupId"].asL(), t["dir"].asI(),
                t["entryT"].asL(), t["entryBi"].asI(), t["entry"].asD(), t["sl0"].asD(),
                t["tp1"].asD(), t["tp2"].asD(), t["tpX"].asD(), t["qty"].asD(),
                t["zoneTop"].asD(), t["zoneBot"].asD(), t["contractSize"].asD())
            for (f in t["fills"].asList()) {
                val l = f.asList(); if (l.size < 6) continue
                tr.fills.add(Fill(l[0].asL(), l[1].asI(), l[2].asD(), l[3].asD(), l[4].asS(), l[5].asD()))
            }
            if (t.containsKey("hvTop")) tr.hvTop = t["hvTop"].asD()
            if (t.containsKey("hvBot")) tr.hvBot = t["hvBot"].asD()
            if (t.containsKey("commission")) tr.commission = t["commission"].asD()
            if (t.containsKey("mmCm")) tr.mmCm = t["mmCm"].asB()
            if (t["exitT"].asL() != 0L) {
                tr.exitT = t["exitT"].asL(); tr.exitBi = t["exitBi"].asI()
                tr.exitPx = t["exitPx"].asD(); tr.reason = t["reason"].asS(); tr.be = t["be"].asB()
            }
            b.trades.add(tr)
        }
        // پشتیبانی از چند سفارش/پوزیشن (v1.4.9+):
        val pids = m["pendingIds"]
        if (pids != null) {
            for (pid in pids.asList()) {
                val id = pid.asL()
                b.orders.firstOrNull { it.id == id }?.let { b.restorePending(it) }
            }
        } else {
            val pid = m["pendingId"].asL(); if (pid >= 0) b.restorePending(b.orders.firstOrNull { it.id == pid })
        }
        val oids = m["openIds"]
        if (oids != null) {
            for (oid in oids.asList()) {
                val id = oid.asL()
                b.trades.firstOrNull { it.id == id }?.let { b.restoreOpen(it) }
            }
        } else {
            val oid = m["openId"].asL(); if (oid >= 0) b.restoreOpen(b.trades.firstOrNull { it.id == oid })
        }
        // مهاجرت: فایل‌های قبل از balModel=2 سود/زیان پله‌های خروج‌شده و کمیسیونِ معاملات هنوز-باز را در balance داشتند؛
        // الان این‌ها فقط با بسته شدن معامله وارد balance می‌شوند ⇒ همان مقدار را از balance برمی‌داریم (وگرنه دوبار شمرده می‌شود).
        if (!m.containsKey("balModel")) {
            for (t in b.openTrades) {
                // فقط balance؛ equity همان می‌ماند (= balance + سود/زیان باز، که مجموعش در هر دو مدل یکی است)
                b.balance -= t.fills.sumOf { it.pnl } - t.commission
            }
        }
        return b
    }

    // ── خواندن ─────────────────────────────────────────────────────────────────
    fun load(json: String): Loaded {
        val root = Json.parse(json).asMap()
        val cfg = settingsFrom(root["settings"].asMap())
        val eng = engineFrom(root["engine"].asMap(), cfg)
        val broker = brokerFrom(root["broker"].asMap(), cfg, eng)
        eng.broker = broker
        val from = root["candleFrom"].asI()
        val candles = ArrayList<Candle>()
        var idx = from
        for (x in root["candles"].asList()) {
            val c = candleFrom(x) ?: continue
            candles.add(Candle(idx++, c.t, c.o, c.h, c.l, c.c, c.v))
        }
        val structureCandles = candleListFrom(root["structureCandles"])
        return Loaded(cfg, eng, broker, candles, structureCandles, root["meta"].asMap(), root["savedAt"].asL())
    }
}
