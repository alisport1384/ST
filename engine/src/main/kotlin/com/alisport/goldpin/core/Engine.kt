package com.alisport.goldpin.core

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

// ═══════════════════════════════════════════════════════════════════════════════
//  موتور PinReady — پورت کامل اسکریپت Pine «PinReady_FRVP_Strategy»
//  زنجیره: کندل مهم → Ready(BU/BE) → pinBU/pinBE → روند(HH/HL/LL/LH)
//          → ناحیهٔ فیکس‌رنج (الگوریتم v2) → تایید میانی → ناحیهٔ ولوم کم (تریگر۱)
//          → ناحیهٔ ولوم زیاد (تریگر۲ / چارت) → ورود / حدضرر / حدسود / سر‌به‌سر
//
//  ⚠ موتور فقط با کلوز کندل‌ها تصمیم می‌گیرد (بدون ریپینت). کندل باز فقط پیش‌نمایش است.
// ═══════════════════════════════════════════════════════════════════════════════

class Settings {
    var tfMode: Int = 1
    var tfS: Int = Tf.H1
    var tfM: Int = Tf.M15
    var tf1: Int = Tf.M5
    var tf2: Int = Tf.M1

    var vpRows: Int = 24
    var vpVA: Double = 10.0
    /** §۴-۵ سند (ویرایش جدید): هموارسازی پیش‌فرض **۰** (بازهٔ مجاز ۰ تا ۳). */
    var vpSmooth: Int = 0
    /** §۴-۵ سند (ویرایش جدید): مدل پیش‌فرض پخش حجم «یکنواخت» است. */
    var distMode: Int = VolumeProfile.DIST_UNIFORM
    var minZoneRows: Int = 1
    var drawZones: Boolean = true
    var showRejectedZones: Boolean = true
    var clearUsedZones: Boolean = true
    var maxZoneBoxes: Int = Int.MAX_VALUE              // §7: نامحدود (از ۱٫۴٫۹)
    var showPocVA: Boolean = true

    /**
     * سند §۱۸-۱۹ و §۴-۸: حداکثر ستاپ هم‌زمان = ۱ (پیش‌فرض) — یعنی حداکثر این تعداد سفارش (معلق یا باز) هم‌زمان؛
     * (استراتژی ۲: به‌ازای هر جهت). ناحیه‌های در انتظار لمس با این عدد حذف نمی‌شوند؛ سقف پیگیری آن‌ها [TRACKED_SETUPS_MIN] است.
     */
    var maxSetups: Int = 1
    /**
     * `true`  ⇒ تایید میانی فقط با **بسته شدن** کندل میانی آن‌طرف سطح باطل می‌شود.
     * `false` ⇒ با **عبور شمع** باطل می‌شود، حتی یک تیک و حتی اگر برگردد و آن‌طرف بسته نشود.
     *
     * پیش‌فرض: `false` (عبور شمع کافی است) — مطابق سند نهایی.
     */
    var midInvalidClose: Boolean = false       // true=شکست با کلوز ، false=با سایه
    var hvScanFirstTouch: Boolean = true      // true=از اولین برگشت به باکس ولوم کم
    var hvMarkFirstIncrease: Boolean = true   // true=اولین افزایش حجم نسبت به کندل قبل ، false=RunningMax
    var rejectCandleNext: Boolean = false

    var mintick: Double = 0.01
    var slBufTicks: Double = 2.0
    /**
     * §۵ سند: «اپ اندروید: بدون کارمزد (لغزش ۱ تیک)» — لیکن سفارش‌های لیمیت ورود و پله‌های
     * حد سود در سطح خودشان پر می‌شوند و §۱۸-۲۴ تأکید می‌کند خروج سر‌به‌سر باید **دقیقاً** صفر
     * هزینه شود؛ پس لغزش فقط روی **خروج با حد ضرر** (بازارِ استاپ) اعمال می‌شود و در جهت ضرر
     * است. مقدار قابل تغییر است؛ ۰ = بدون لغزش.
     */
    var slipTicks: Double = 1.0
    /** قیمت واقعی پر شدن خروج: حد ضرر با یک تیک لغزش در جهت ضرر؛ خروج سر‌به‌سر/حد سود بدون لغزش (§۵ و §۱۸-۲۴). */
    fun exitFill(bull: Boolean, level: Double, breakeven: Boolean): Double {
        if (breakeven || slipTicks <= 0.0 || level.isNaN()) return level
        val slip = slipTicks * mintick
        return if (bull) level - slip else level + slip
    }
    /** §4-8 سند: سطح سفارش = سقف باکس HV + ۱ تیک بافر (خرید) / کف باکس HV − ۱ تیک بافر (فروش). */
    var entryBufTicks: Double = 1.0
    /** سطح سفارش (§4-8): خرید = سقف باکس HV + بافر؛ فروش = کف باکس HV − بافر؛ همیشه عددی مثبت. */
    fun entryLevel(bull: Boolean, hvH: Double, hvL: Double): Double {
        val buf = entryBufTicks * mintick
        return if (bull) hvH + buf else maxOf(mintick, hvL - buf)
    }
    var minTPunits: Double = 10.0

    /**
     * استراتژی انتخاب‌شده ([Strategy]).
     *  ۱ = سند (پیش‌فرض): روند + کندل مهم/پین؛ ناحیه فقط روی کندل هم‌جهت با روند؛ حد سود فیبوناچی (TP1 ۳۸٪ / TP2 ۵۰٪ / TPX ۱٫۲۷۲).
     *  ۲ = استراتژی دوم: تعیین روند حذف است؛ روی **همهٔ** کندل‌های ساختار (سبز ⇒ ناحیهٔ خرید، قرمز ⇒ ناحیهٔ فروش) با همان
     *      الگوریتم و ضوابط (حداکثر ۲ ناحیه) ناحیه ثبت می‌شود؛ حد سود فقط [tpUnits] واحد از ورود (بدون فیبو، بدون TP1/TP2)؛
     *      هم‌زمان یک سفارش خرید و یک سفارش فروش مجاز است ولی از هر جهت فقط یکی (معلق یا باز).
     *      بقیهٔ قواعد (برخورد، تایید میانی، LV، HV، سطح سفارش، حد ضرر، مدیریت معامله) دقیقاً همان استراتژی ۱.
     */
    var strategy: Int = Strategy.DOC
    /** استراتژی ۲: حد سود نهایی = ورود ± این مقدار (واحد قیمت). پیش‌فرض ۱۰. */
    var tpUnits: Double = 10.0

    /**
     * §18-31 سند: سفارش‌هایی که فاصلهٔ حد ضررشان از سطح سفارش بیشتر از N است ثبت نمی‌شوند
     * (سفارش باطل است؛ علت: SL>N). HV و ستاپ سر جایشان می‌مانند و ستاپ تا پر/منقضی/باطل‌شدن
     * HV ادامه دارد، ولی روی آن HV سفارشی ثبت نمی‌شود و در بک‌تست هم سفارشی حساب نمی‌شود.
     * N در تنظیمات قابل تغییر است؛ **پیش‌فرض سند: ۳ واحد قیمت** (§18-31).
     */
    var maxSlUnits: Double = 3.0          // §18-31: N = ۳ (تصمیم مالک — نسخهٔ ۷ هم همان ۳)

    /**
     * **حداقل** فاصلهٔ حد ضرر از سطح سفارش (واحد قیمت). ۰ = خاموش (پیش‌فرض؛ رفتار گذشته).
     *
     * چرا لازم شد: فیلتر کارمزد §۵-۳ فقط وقتی فعال است که مود §۲۰ روشن باشد (در مود خاموش
     * کمیسیون صفر است و `SlCostFilterTest` همین را قفل کرده). با کارمزد واقعی ۰٫۷ دلار،
     * معامله‌ای که R کوچک دارد در **هر** مودی بازنده است؛ این کلید، مستقل از مود مالی،
     * ستاپ‌های کم‌فاصله را حذف می‌کند تا سفارش‌ها با هزینهٔ واقعی مثبت بمانند.
     */
    var minSlUnits: Double = 0.0

    // ── سند ۶ (تریگر ۲ معکوس) ─────────────────────────────────────────────────
    /** `true` ⇒ جهت معاملهٔ تریگر ۲ برعکسِ جهت ساختار است (سند ۶ / گزینهٔ ۳).
     *  زنجیرهٔ ساختار، نواحی، LV/HV و لحظهٔ مسلح‌شدن دقیقاً مثل سند ۵ می‌مانند؛ فقط
     *  جهت سفارش و هندسهٔ حد ضرر/حد سود آینه می‌شود. پیش‌فرض `false` = رفتار سند ۵. */
    var trigger2Fade: Boolean = true       // نسخهٔ ۷: پیش‌فرض = تریگر ۲ معکوس
    /** هدف تریگر ۲ بر حسب R (فاصلهٔ حد ضرر). ۰ = پله‌های فیبوی سند ۵.
     *  برای حالت معکوس، مقدار پیشنهادی ۳ تا ۵ (بر پایهٔ آزمون عددی؛ با کارمزد ۰٫۲ دلاری ۴). */
    var trigger2TargetR: Double = 4.0      // نسخهٔ ۷: هدف پیش‌فرض ۴R
    /** فیلتر کارمزد: فاصلهٔ حد ضرر باید دست‌کم این ضریب × اسپرد/کارمزد هر واحد باشد.
     *  ۰ = خاموش. پیشنهاد آزمون: ۶ (اگر R کوچک‌تر از ۶ برابر هزینه باشد، معامله بی‌معناست). */
    var minSlCostMult: Double = 6.0        // نسخهٔ ۷: R ≥ ۶× هزینهٔ رفت‌وبرگشت

    var useRiskPct: Boolean = true
    var riskPct: Double = 1.0
    var equityPct: Double = 100.0
    /**
     * سقف اهرم — **تنها** متغیر اهرم، برای همهٔ مودها (§۵ و §۲۰).
     * §۵ سند: «اهرم پیش‌فرض: **۵۰۰** — کاربر می‌تواند تغییر دهد.» (یک متغیر برای همهٔ مودها.)
     */
    var maxLeverage: Double = 500.0
    var roundQty: Boolean = false
    // §4-8/§۱۴ سند نهایی: انقضای سفارش معلق با عبور باز/کلوز از آخرین pinBE/pinBU
    // (سطح هنگام مسلح‌شدن ذخیره می‌شود) یا گذشت یک کندل ساختار کامل بعد از مسلح‌شدن.
    // سقف زمانی «لغو پس از N کندل» طبق تصمیم مالک حذف شد (سند §۱۴: لازم نیست).
    var contractSize: Double = 1.0
    var initialEquity: Double = 100000.0     // §5: سرمایهٔ اولیه ۱۰۰٬۰۰۰ (تصمیم نهایی)

    // ── §۲۰ سند: مدیریت مالی و مدیریت زیان (پیش‌فرض: خاموش ⇒ همان حجم §۵) ──
    /** [MoneyMode]: ۰ = خاموش (§۵) · ۱ = ManageAllLost · ۲ = Mixed. پیش‌فرض ۰ (تصمیم مالک). */
    var mmMode: Int = MoneyMode.NONE
    /** MinLot: true = خودکار (NakedCapital × risk)/100 ؛ false = دستی ([minLotManual]). */
    var minLotAuto: Boolean = false        // نسخهٔ ۷: MinLot خاموش
    var minLotManual: Double = 10.0        // نسخهٔ ۷: مقدار دستی ۱۰
    /** div: true = |ورودِ سفارش − حدسود نهایی| ؛ false = دستی ([divManual]). */
    var divAuto: Boolean = true
    var divManual: Double = 10.0
    /**
     * cmsn (اسپرد تحمیلی به هر معامله، واحد قیمت): true = اسپرد بروکر ([cmsnAutoValue]) ؛ false = [cmsnManual].
     * اسپرد بروکر یک‌بار از فید گرفته و **داخل تنظیمات ذخیره** می‌شود (نه یک متغیر پنهان زمان اجرا)؛ پس همهٔ
     * معاملات (تاریخچه و زنده) با یک عدد حساب می‌شوند و هر بازسازی دقیقاً همان نتیجه را می‌دهد.
     */
    /**
     * پیش‌فرض **false** (۱٫۴٫۴۰): §۵-۱ سند می‌گوید مود پیش‌فرض اپ «بدون کارمزد» است و
     * §۹۲۶ سند هم صریح می‌گوید کارمزد واقعی را کاربر **دستی** وارد کند (برای طلا ~۰٫۲).
     * پیش‌تر این کلید true بود و اپ بلافاصله اسپرد خرده‌فروشی Swissquote را می‌گرفت
     * (در مهر ۱۴۰۵ روی XAU/USD حدود **۰٫۶۶** = بیش از ۳ برابر مقدار مرجع سند). با ضریب
     * فیلتر ۶ آستانهٔ فاصلهٔ حد ضرر ۳٫۹۶ می‌شد، در حالی که فاصلهٔ واقعی این استراتژی
     * حدود ۱٫۴ تا ۲ دلار است ⇒ **همهٔ** سفارش‌ها رد می‌شدند و با روشن‌کردن مود §۲۰
     * «کل معاملات صفر» می‌شد (باگ گزارش‌شدهٔ کاربر).
     */
    var cmsnAuto: Boolean = false
    var cmsnManual: Double = 0.0
    /** آخرین اسپرد دریافت‌شده از بروکر (۰ = هنوز دریافت نشده ⇒ cmsnManual) و زمان دریافت (میلی‌ثانیه). */
    var cmsnAutoValue: Double = 0.0
    var cmsnAutoAt: Long = 0L
    /**
     * اگر [Store] هنگام بارگذاری فایل قدیمی، «اسپرد خودکارِ بزرگ» را به «دستی ۰٫۲۰» تبدیل
     * کرده باشد، مقدار قبلی این‌جا می‌آید (۰ = مهاجرتی رخ نداده). فقط برای **اطلاع‌دادن** به
     * کاربر است و ذخیره نمی‌شود.
     */
    var cmsnMigratedFromAuto: Double = 0.0
    /**
     * §۲۰ بند ۵ (crt): «در شرایط حساس اگر کپیتال از یک مقدار مشخص کمتر شد ورودی‌های حجم را crt
     * محاسبه می‌کند — پیش‌فرض ۱۰ درصد کل capital» و «crt در مود ManageAllLost فقط فعال است».
     * مقدار آستانه (درصد از Capital) و کلید روشن/خاموش در تنظیمات قابل تغییرند.
     */
    var crtEnabled: Boolean = true
    var crtPct: Double = 10.0

    /** Mixed: M = NakedCapital × (1 + mixedGrowthPct/100). سند: ۲۰٪. */
    var mixedGrowthPct: Double = 20.0
    /** گام حجم در مودهای §۲۰ — مثال‌های سند به ۰٫۰۱ کف شده‌اند. ۰ = بدون گرد کردن. */
    var lotStep: Double = 0.01
    /** true = با رسیدن معامله به حد سود نهایی، Lost صفر می‌شود (سند: lost = 0.0 بعد از TP)؛ false = فقط به اندازهٔ سود واقعی کم می‌شود. */
    var lostResetAtFinalTp: Boolean = false  // نسخهٔ ۷: خاموش

    fun copy(): Settings = Settings().also {
        it.tfMode = tfMode; it.tfS = tfS; it.tfM = tfM; it.tf1 = tf1; it.tf2 = tf2
        it.vpRows = vpRows; it.vpVA = vpVA; it.vpSmooth = vpSmooth; it.distMode = distMode
        it.minZoneRows = minZoneRows; it.drawZones = drawZones; it.showRejectedZones = showRejectedZones
        it.clearUsedZones = clearUsedZones; it.maxZoneBoxes = maxZoneBoxes; it.showPocVA = showPocVA
        it.maxSetups = maxSetups; it.midInvalidClose = midInvalidClose
        it.hvScanFirstTouch = hvScanFirstTouch; it.hvMarkFirstIncrease = hvMarkFirstIncrease
        it.rejectCandleNext = rejectCandleNext
        it.mintick = mintick; it.slBufTicks = slBufTicks; it.entryBufTicks = entryBufTicks; it.minTPunits = minTPunits
        it.slipTicks = slipTicks
        it.strategy = strategy; it.tpUnits = tpUnits
        it.maxSlUnits = maxSlUnits
        it.minSlUnits = minSlUnits
        it.trigger2Fade = trigger2Fade; it.trigger2TargetR = trigger2TargetR; it.minSlCostMult = minSlCostMult
        it.useRiskPct = useRiskPct; it.riskPct = riskPct; it.equityPct = equityPct
        it.maxLeverage = maxLeverage; it.roundQty = roundQty
        it.contractSize = contractSize; it.initialEquity = initialEquity
        it.mmMode = mmMode; it.minLotAuto = minLotAuto; it.minLotManual = minLotManual
        it.divAuto = divAuto; it.divManual = divManual
        it.cmsnAuto = cmsnAuto; it.cmsnManual = cmsnManual
        it.cmsnAutoValue = cmsnAutoValue; it.cmsnAutoAt = cmsnAutoAt
        it.crtEnabled = crtEnabled; it.crtPct = crtPct
        it.mixedGrowthPct = mixedGrowthPct
        it.lotStep = lotStep; it.lostResetAtFinalTp = lostResetAtFinalTp
    }
}

class Counters {
    var setup = 0; var touch = 0; var mid = 0; var lv = 0; var hv = 0; var entry = 0; var done = 0
    var zoneCount = 0; var zoneRejected = 0
    /** چند سفارش با «فیلتر کارمزد» (§۵-۳) رد شد — برای هشدار قابل‌فهم در رابط کاربری. */
    var slCostBlocked = 0
    /** چند سفارش با سقف N رد شد (SL>N، §۱۸-۳۱). */
    var slNBlocked = 0
    /** چند سفارش با «حداقل فاصلهٔ حد ضرر» رد شد (SL<minSlUnits). */
    var slMinBlocked = 0
    /** چند مسلح‌شدن به‌خاطر پنجرهٔ زمانی (۰۱:۳۰–۲۰:۰۰ UTC) رد شد (§۱۸-۲۶). */
    var windowBlocked = 0
}

class Engine(val cfg: Settings) {

    // ── وضعیت ──────────────────────────────────────────────────────────────────
    val aggS = Agg(); val aggM = Agg(); val agg1 = Agg()
    val structBars = ArrayList<Candle>()
    val pins = ArrayList<Pin>()
    val setups = ArrayList<Setup>()
    val microS = ArrayList<Candle>()

    val zones = ArrayList<ZoneBox>()          // همهٔ باکس‌های ساختار با چرخهٔ عمر (برای ذخیره/بازیابی دقیق)
    val t1Zones = ArrayList<ZoneBox>()        // باکس‌های تاییدشدهٔ تریگر ۱ (ولوم کم) در حافظه
    val t2Zones = ArrayList<ZoneBox>()        // باکس‌های تاییدشدهٔ تریگر ۲ (ولوم زیاد) در حافظه
    val markers = ArrayList<Marker>()         // برچسب‌های استراتژی روی چارت
    val events = ArrayList<String>()          // گزارش رویدادها
    val cnt = Counters()

    /**
     * نمونهٔ فاصله‌های حد ضرر ارزیابی‌شده (سقف ۴٬۰۰۰ عدد) تا رابط کاربری بتواند بگوید
     * «فیلتر کارمزد با میانهٔ X در برابر آستانهٔ Y همه را رد کرد». فقط آمار است، هیچ اثری بر تصمیم ندارد.
     */
    private val slDistSamples = ArrayList<Double>(1024)
    private val SL_SAMPLE_CAP = 4000

    /** میانهٔ فاصلهٔ حد ضرر سفارش‌های ارزیابی‌شده در این اجرا (NaN = هیچ نمونه‌ای نیست). */
    fun slDistMedian(): Double {
        if (slDistSamples.isEmpty()) return Double.NaN
        val a = slDistSamples.sorted()
        return if (a.size % 2 == 1) a[a.size / 2] else (a[a.size / 2 - 1] + a[a.size / 2]) / 2.0
    }

    /** هزینهٔ مؤثر هر واحد که فیلتر کارمزد با آن حساب می‌کند (۰ = مود §۲۰ خاموش ⇒ بدون کارمزد). */
    fun slCostPerUnit(): Double {
        val mm = broker?.mm ?: return 0.0
        return if (mm.active) mm.spread() else 0.0
    }

    /** آستانهٔ فعلی فیلتر کارمزد (ضریب × هزینهٔ هر واحد)؛ ۰ = فیلتر عملاً خاموش. */
    fun slCostThreshold(): Double {
        if (cfg.minSlCostMult <= 0.0) return 0.0
        val c = slCostPerUnit()
        return if (c > 0.0) cfg.minSlCostMult * c else 0.0
    }

    var trend = 0
    var HH = Double.NaN; var HL = Double.NaN; var LL = Double.NaN; var LH = Double.NaN
    var tempBoxTop = Double.NaN; var tempBoxBot = Double.NaN
    var hlPinLo = Double.NaN; var hlPinRef = Double.NaN; var hlPinHi = Double.NaN; var hlPinSeq = -1
    var hhPinHi = Double.NaN; var hhPinRef = Double.NaN; var hhPinLo = Double.NaN; var hhPinSeq = -1
    var lhPinHi = Double.NaN; var lhPinRef = Double.NaN; var lhPinLo = Double.NaN; var lhPinSeq = -1
    var llPinLo = Double.NaN; var llPinRef = Double.NaN; var llPinHi = Double.NaN; var llPinSeq = -1
    var seqStart = 0
    var bullBU1Low = Double.NaN; var bullBEHigh = Double.NaN; var bullBU2Low = Double.NaN; var bullBU2Bi = -1
    var bearBE1High = Double.NaN; var bearBULow = Double.NaN; var bearBE2High = Double.NaN; var bearBE2Bi = -1
    var bullSetup = false; var bearSetup = false
    var expectedReady = 0
    var lowestBUpin = Double.NaN; var highestBE = Double.NaN
    /**
     * بالاترین سقف / پایین‌ترین کف **از زمان تغییر روند تا این لحظه** (روی کندل‌های بستهٔ ساختار).
     *
     * چرا لازم است: لنگر صفر فیبوناچی طبق §4-8 «بالاترین سقف HH (صعودی‌ترین pinBE بعد از تغییر روند)»
     * است. اما وقتی قیمت از آخرین pinBE بالاتر می‌رود و پین تازه‌ای هنوز ثبت نشده، آن لنگر **کهنه**
     * می‌ماند و زیر «کف تأیید میانی» می‌افتد؛ در نتیجه کد فیبو را نامعتبر می‌دانست و به حد سود
     * جایگزین ۱۰ واحد می‌رفت (اندازه‌گیری: ۱۵٪ ستاپ‌ها). با این دو اکسترمم، لنگر صفر همیشه
     * بالاترین سقف واقعی روند است و فیبو معتبر می‌ماند.
     */
    var trendHigh = Double.NaN; var trendLow = Double.NaN
    var lastBUpin = Double.NaN; var lastBEpin = Double.NaN

    // ── چرخهٔ عمر پین‌ها و روندِ توالی‌محور (سند نهایی §4-3/§4-4) ────────────────
    /** `lastBUpin`/`lastBEpin` همراه با ref و لبهٔ دیگر، هنگام ابطال کامل پاک می‌شوند؛
     *  `buActive`/`beActive` مشخص می‌کنند پین ثبت‌شده هنوز معتبر است یا نه. */
    var buActive = false; var beActive = false
    /** شمارهٔ توالی: با هر پینِ تاییدشده یکی زیاد می‌شود تا بدانیم کدام بعد از کدام آمده. */
    var pinSeq = 0; var buSeq = -1; var beSeq = -1
    /** `ref` و لبهٔ دیگرِ کندل مهم — برای بند ۳ (ابطال بعد از Ready). */
    var lastBUpinRef = Double.NaN; var lastBUpinHi = Double.NaN
    var lastBEpinRef = Double.NaN; var lastBEpinLo = Double.NaN

    var lastZoneTop = Double.NaN; var lastZoneBot = Double.NaN
    var lastPocPx = Double.NaN; var lastProfBars = 0
    var profileOnLastS: Profile? = null

    var lastEntryPx = Double.NaN; var lastSlPx = Double.NaN; var lastTpPx = Double.NaN
    var lastResult = "-"
    var stageFa = "-"

    var prevT1Vol = Double.NaN
    var zoneSeq = 0L
    var setupSeq = 0L
    var processed = 0
    var curBi = -1
    var curT = 0L
    var curVol = 0.0
    var prevChartVol = Double.NaN

    internal data class T2Bar(val bar: Candle, val prevVol: Double)
    internal data class T1Bar(
        val bar: Candle,
        val prevVol: Double,
        val startBi: Int,
        val closeBi: Int,
        val lowBi: Int,
        val highBi: Int
    )
    internal val t2History = ArrayList<T2Bar>()
    internal val t1History = ArrayList<T1Bar>()

    /** §18-22: سقف و کف کندل ساختار مرجع Y برای ابطال نواحی کندل‌های درونی X و X+n */
    var zoneRefH = Double.NaN
    var zoneRefL = Double.NaN

    // پیش‌نمایش زنده (کندل باز) — فقط برای نمایش، بدون تغییر وضعیت
    val livePreview = ArrayList<String>()
    var liveCandle: Candle? = null

    var broker: PaperBroker? = null

    /** قلاب همگام‌سازی: بعد از پردازش هر کندل بسته فراخوانی می‌شود (برای کارگزار کاغذی). */
    var barCommitHook: ((Candle) -> Unit)? = null

    // ── قلاب‌های لاگر و هشدار (اپ آن‌ها را وصل می‌کند؛ موتور خالص می‌ماند) ──
    /** (دسته ، پیام ، داده) */
    var logSink: ((String, String, String?) -> Unit)? = null
    /** (نوع هشدار AlertKind ، عنوان ، توضیح) */
    var alertSink: ((String, String, String) -> Unit)? = null

    private fun lg(cat: String, msg: String, data: String? = null) {
        logSink?.invoke(cat, msg, data)
    }

    private fun alarm(kind: String, title: String, body: String) {
        alertSink?.invoke(kind, title, body)
    }

    val tf2: Int get() = cfg.tf2

    fun reset() {
        aggS.reset(); aggM.reset(); agg1.reset()
        structBars.clear(); pins.clear(); setups.clear(); microS.clear()
        zones.clear(); t1Zones.clear(); t2Zones.clear(); markers.clear(); events.clear()
        trend = 0; HH = Double.NaN; HL = Double.NaN; LL = Double.NaN; LH = Double.NaN
        tempBoxTop = Double.NaN; tempBoxBot = Double.NaN
        hlPinLo = Double.NaN; hlPinRef = Double.NaN; hlPinHi = Double.NaN; hlPinSeq = -1
        hhPinHi = Double.NaN; hhPinRef = Double.NaN; hhPinLo = Double.NaN; hhPinSeq = -1
        lhPinHi = Double.NaN; lhPinRef = Double.NaN; lhPinLo = Double.NaN; lhPinSeq = -1
        llPinLo = Double.NaN; llPinRef = Double.NaN; llPinHi = Double.NaN; llPinSeq = -1
        seqStart = 0
        bullBU1Low = Double.NaN; bullBEHigh = Double.NaN; bullBU2Low = Double.NaN; bullBU2Bi = -1
        bearBE1High = Double.NaN; bearBULow = Double.NaN; bearBE2High = Double.NaN; bearBE2Bi = -1
        bullSetup = false; bearSetup = false; expectedReady = 0
        lowestBUpin = Double.NaN; highestBE = Double.NaN; lastBUpin = Double.NaN; lastBEpin = Double.NaN
        trendHigh = Double.NaN; trendLow = Double.NaN
        buActive = false; beActive = false
        pinSeq = 0; buSeq = -1; beSeq = -1
        lastBUpinRef = Double.NaN; lastBUpinHi = Double.NaN
        lastBEpinRef = Double.NaN; lastBEpinLo = Double.NaN
        lastZoneTop = Double.NaN; lastZoneBot = Double.NaN; lastPocPx = Double.NaN; lastProfBars = 0
        profileOnLastS = null
        lastEntryPx = Double.NaN; lastSlPx = Double.NaN; lastTpPx = Double.NaN
        lastResult = "-"; stageFa = "-"
        prevT1Vol = Double.NaN; zoneSeq = 0; setupSeq = 0
        processed = 0; curBi = -1; curT = 0; curVol = 0.0; prevChartVol = Double.NaN
        // شمارنده‌ها هم باید با هر «اجرای از صفر» پاک شوند؛ وگرنه آمار بازسازیِ قبلی روی
        // آمار جدید جمع می‌شود و گزارش/هشدار «فیلتر کارمزد» عدد اشتباه نشان می‌دهد.
        cnt.setup = 0; cnt.touch = 0; cnt.mid = 0; cnt.lv = 0; cnt.hv = 0
        cnt.entry = 0; cnt.done = 0; cnt.zoneRejected = 0
        cnt.slCostBlocked = 0; cnt.slNBlocked = 0; cnt.windowBlocked = 0
        slDistSamples.clear()
        t2History.clear(); t1History.clear()
        zoneRefH = Double.NaN; zoneRefL = Double.NaN
        livePreview.clear(); liveCandle = null
        times.clear()
    }

    // ── ورود داده ──────────────────────────────────────────────────────────────
    /**
     * پردازش لیست کندل‌های تایم‌فریم تریگر۲ (چارت).
     * همهٔ کندل‌ها به‌جز آخری «بسته» در نظر گرفته می‌شوند؛ آخری فقط پیش‌نمایش زنده است.
     */
    fun feed(candles: List<Candle>, lastIsClosed: Boolean = false) {
        if (candles.isEmpty()) return
        val lastIdx = candles.size - 1
        var i = processed
        while (i <= lastIdx) {
            val cd = candles[i]
            val isLast = i == lastIdx
            if (MarketHours.isClosed(cd.t)) {
                // تعطیلات شنبه و یکشنبه (ساعت استاندارد بازار): کندل آخر هفته هیچ تصمیمی
                // نمی‌سازد و وارد تجمیع هم نمی‌شود؛ فقط شمارنده جلو می‌رود تا دوباره دیده نشود.
                // سشن یکشنبه‌شب (از ۲۲:۰۰ UTC) باز است و جزئی از دوشنبه حساب می‌شود.
                processed = i + 1
            } else if (!isLast || lastIsClosed) {
                processClosed(cd, i, candles.getOrNull(i - 1))
                processed = i + 1
            } else {
                updateLive(cd, i, candles.getOrNull(i - 1))
            }
            i++
        }
    }

    /** بارگذاری کامل از صفر (برای بک‌تست). همهٔ کندل‌ها بسته‌اند. */
    fun runAll(candles: List<Candle>) {
        reset()
        feed(candles, lastIsClosed = true)
    }

    /**
     * پیش‌بارگذاری توالی‌های ساختار از تاریخچهٔ قدیمی‌ترِ تایم‌فریم ساختار.
     * این بخش فقط پین‌ها، توالی و روند را بازسازی می‌کند؛ بدون کندل‌های تریگر۲
     * هیچ پروفایل/باکس دقیقی ساخته نمی‌شود. کندل‌های هم‌پوشان با شروع تاریخچهٔ
     * تریگر۲ کنار گذاشته می‌شوند تا با اجرای اصلی دوباره پردازش نشوند.
     *
     * ورودی باید از قبل به [cfg.tfS] تجمیع شده و مرتب زمانی باشد.
     * ایندکس منفی باعث می‌شود تاریخچهٔ seed پیش از ایندکس‌های عادی تریگر۲ بماند،
     * در حالی که مارکرها با زمان واقعی روی هر تایم‌فریم چارت نگاشت می‌شوند.
     */
    fun seedStructureHistory(structureCandles: List<Candle>, firstTrigger2Time: Long): Int {
        if (structureCandles.isEmpty() || firstTrigger2Time <= 0L || processed != 0 ||
            structBars.isNotEmpty() || markers.isNotEmpty()) return 0

        val firstModeBucket = Tf.bucket(cfg.tfS, firstTrigger2Time)
        val history = structureCandles.asSequence()
            .sortedBy { it.t }
            .map { c ->
                val bucket = Tf.bucket(cfg.tfS, c.t)
                Candle(c.bi, bucket, c.o, c.h, c.l, c.c, c.v)
            }
            .filter { it.t < firstModeBucket }
            .distinctBy { it.t }
            .toList()
        if (history.isEmpty()) return 0

        val firstBi = -history.size
        for ((index, src) in history.withIndex()) {
            val bi = firstBi + index
            val bar = Candle(bi, src.t, src.o, src.h, src.l, src.c, src.v)
            times[bi] = bar.t
            curBi = bi
            curT = bar.t + cfg.tfS * 1000L
            curVol = bar.v
            structureEngine(bar, buildZones = false)
        }
        return history.size
    }

    // ── پیش‌نمایش کندل باز (بدون تغییر وضعیت) ────────────────────────────────────
    /**
     * ⚠ این متد **هیچ** تغییری در وضعیت موتور نمی‌دهد.
     *
     * پیش‌تر کندل باز هم در تجمیع‌کننده‌ها ([aggS]/[aggM]/[agg1]) گام می‌زد. چون فید زنده
     * هر چند ثانیه همان کندل باز را دوباره می‌فرستد، حجم آن کندل به ازای هر تیک یک‌بار
     * دیگر جمع می‌شد (۲۰ تیک → حجم ~۲ برابر). آن حجم بادکرده بعداً در `agg?.cl` کپی می‌شد
     * و `prevT1Vol` را خراب می‌کرد → شرط «کندل ولوم کم» بیش از حد راحت پاس می‌شد و
     * نتیجهٔ لایو با بک‌تست یکی نبود.
     *
     * حالا تجمیع فقط در [processClosed] و فقط با کندل بسته انجام می‌شود (بدون ریپینت).
     */
    private fun updateLive(cd: Candle, i: Int, prev: Candle?) {
        curBi = i; curT = cd.t; curVol = cd.v; liveCandle = cd
        times[i] = cd.t
        livePreview.clear()
        prevChartVol = prev?.v ?: Double.NaN
        // وضعیت‌های در انتظار (فقط خواندنی)
        for (s in setups) {
            if (s.stage >= 1 && s.stage <= 6 && !s.zGone) {
                val d = if (s.dir == 1) "صعودی" else "نزولی"
                livePreview.add("ستاپ #${s.id} $d · مرحله: ${stageFa(s.stage)}")
            }
        }
        // آیا کندل جاری در حال مارک شدن به‌عنوان کندل ولوم زیاد است؟
        for (s in setups) {
            if (s.stage >= 4 && s.stage <= 5 && s.lvReEntered && s.hvBi < 0) {
                val inc = cd.v > (prev?.v ?: 0.0)
                if (inc) livePreview.add("کندل ولوم زیاد در حال مارک (حجم ${fmtV(cd.v)} > قبلی)")
            }
        }
    }

    private fun tfS() = cfg.tfS
    private fun tfM() = cfg.tfM
    private fun tf1() = cfg.tf1

    private fun aggStep(a: Agg, i: Int, cd: Candle, isNew: Boolean) {
        a.step(isNew, i, cd.t, cd.o, cd.h, cd.l, cd.c, cd.v)
    }

    // ── پردازش کندل بسته ───────────────────────────────────────────────────────
    private fun processClosed(cd: Candle, i: Int, prev: Candle?) {
        curBi = i; curT = cd.t; curVol = cd.v
        times[i] = cd.t
        // ⚠ سقف قبلی ۲۰۰٬۰۰۰ بود؛ بک‌تست یک‌سالهٔ M1 (۳۵۲ هزار کندل) وسط اجرا از این سقف
        // می‌گذشت و `times` پاک می‌شد ⇒ مارکرهای کندل‌های قدیمی دیگر `t` نداشتند و زمانشان
        // غلط ثبت می‌شد. حالا سقف ۱ میلیون است (≈۲٫۵ سال M1) و پاک‌سازی عملاً هرگز رخ نمی‌دهد.
        if (times.size > 1_000_000) times.clear()
        val isNewS = Tf.isNewBarStart(tfS(), cd.t, prev?.t)
        val isNewM = Tf.isNewBarStart(tfM(), cd.t, prev?.t)
        val isNew1 = Tf.isNewBarStart(tf1(), cd.t, prev?.t)

        aggStep(aggS, i, cd, isNewS)
        aggStep(aggM, i, cd, isNewM)
        aggStep(agg1, i, cd, isNew1)

        if (agg1.closedNow && agg1.cl != null) {
            val q1 = agg1.cl!!
            val endBi = maxOf(q1.bi, i - 1)
            var lowBi = q1.bi
            var lowV = Double.POSITIVE_INFINITY
            var highBi = q1.bi
            var highV = Double.NEGATIVE_INFINITY
            for (idx in t2History.size - 1 downTo 0) {
                val b = t2History[idx].bar
                if (b.bi < q1.bi) break
                if (b.bi <= endBi) {
                    if (b.l < lowV) { lowV = b.l; lowBi = b.bi }
                    if (b.h > highV) { highV = b.h; highBi = b.bi }
                }
            }
            t1History.add(T1Bar(q1, prevT1Vol, q1.bi, endBi, lowBi, highBi))
            while (t1History.size > 1000) t1History.removeAt(0)
        }

        // ترتیب دقیقاً مثل Pine: ساختار → میانی → تریگر۱ → تریگر۲
        if (aggS.closedNow) {
            aggS.cl?.let { structureEngine(it) }
        }
        if (aggM.closedNow) middleEngine(aggM.cl!!)
        if (aggS.closedNow) {
            // §4-5 / §18-14: پس از بسته شدن کندل ساختار (و پس از بررسی آخرین کندل میانی آن در middleEngine):
            //  • ناحیهٔ ساختاری که لمس شده و یک کندل ساختار کامل از لمس گذشته باشد مصرف‌شده (CONSUMED) می‌شود؛
            //  • ستاپ آن تا زمانی که تایید میانی باطل نشده فعال می‌ماند (§18-14).
            val it2 = setups.iterator()
            while (it2.hasNext()) {
                val s = it2.next()
                if (!s.zUsed && s.touchT > 0L) {
                    val sbT = aggS.cl?.t ?: 0L
                    if (sbT >= Tf.bucket(cfg.tfS, s.touchT)) {
                        s.zUsed = true
                        markStructureZoneStatus(s, ZoneStatus.CONSUMED, i, cd.t)
                        if (!s.zGone && s.stage in 1..7) {
                            if (s.stage == 1 || s.midRef.isNaN()) {
                                // بدون تایید میانی معتبر چیزی برای ادامه نیست ⇒ ستاپ غیرفعال تا لمس ناحیهٔ بعدی
                                s.zGone = true
                                s.stage = 8
                                s.note = "ناحیهٔ ساختار مصرف شد (یک کندل ساختار پس از لمس) — بدون تایید میانی"
                                events.add("[${s.id}] ناحیهٔ ساختار مصرف شد — ستاپ غیرفعال")
                            } else if (!MarketHours.isOrderWindow(cd.t) && s.stage < 7) {
                                // §18-28 سند: ناحیهٔ ساختاری که در زمان غیرفعال بودن سفارش‌گیری
                                // (۲۰:۰۰ تا ۰۱:۳۰ UTC) باطل/مصرف شود حذف می‌شود — برخلاف §18-14
                                // ستاپش تا صبح زنده نمی‌ماند و همان شب خاتمه می‌یابد تا بعد از
                                // ۰۱:۳۰ روی این ناحیه هیچ ستاپ/سفارش تازه‌ای ساخته نشود.
                                if (s.stage == 6) broker?.onSetupInvalidated(s, curT, curBi)
                                s.zGone = true
                                s.stage = 8
                                s.note = "ناحیهٔ ساختار خارج از پنجرهٔ سفارش‌گیری مصرف شد — ستاپ خاتمه یافت (§18-28 سند)"
                                events.add("[${s.id}] ناحیهٔ ساختار خارج از پنجرهٔ سفارش‌گیری مصرف شد — ستاپ خاتمه یافت (§18-28)")
                            } else {
                                s.note = "ناحیهٔ ساختار مصرف شد — ستاپ تا ابطال تایید میانی فعال است"
                                events.add("[${s.id}] ناحیهٔ ساختار مصرف شد؛ ستاپ تا ابطال تایید میانی فعال می‌ماند")
                            }
                            addMarker(i, if (s.dir == 1) s.zBot else s.zTop, 10, "ناحیهٔ ساختار مصرف شد (§18-14)")
                            lg("ZONE", "ناحیهٔ ساختار پس از لمس و گذشت یک کندل ساختار کامل باطل شد · ستاپ #${s.id}",
                                "zone=${f2(s.zBot)}-${f2(s.zTop)} stage=${s.stage} bar=$i")
                        }
                    }
                }
                val structureElapsed = fullStructureElapsed(s.armBi, i, cfg.tfS, cfg.tf2)
                if (structureElapsed && s.stage == 4 && s.rearmAllowed) {
                    s.stage = 8; s.note = "گذشت یک کندل ساختار کامل پس از مسلح‌شدن"
                    s.rearmAllowed = false
                    closeT1Box(s, ZoneStatus.CONSUMED, i, cd.t)
                    closeT2Box(s, ZoneStatus.DELETED, i, cd.t)
                    broker?.onSetupInvalidated(s, curT, curBi)
                    events.add("[${s.id}] یک کندل ساختار کامل پس از مسلح‌شدن گذشت — مسلح‌مجدد ممنوع")
                    lg("ENGINE", "یک کندل ساختار کامل گذشت → مسلح‌مجدد ستاپ #${s.id} ممنوع",
                        "bar=$i")
                } else if (structureElapsed && s.stage == 7 && s.rearmAllowed) {
                    s.rearmAllowed = false
                    lg("ENGINE", "یک کندل ساختار کامل در زمان معامله گذشت → مسلح‌مجدد بعدی ممنوع · ستاپ #${s.id}",
                        "bar=$i")
                }
            }
        }
        if (agg1.closedNow) trigger1Engine(agg1.cl!!)
        t2History.add(T2Bar(cd, prevChartVol))
        while (t2History.size > 4000) t2History.removeAt(0)
        trigger2Engine(cd)

        // ریزکندل‌های تریگر۲ داخل کندل ساختار جاری
        if (isNewS) microS.clear()
        if (microS.size > 3000) microS.removeAt(0)
        microS.add(cd)

        // چرخهٔ عمر باکس‌های ناحیه (حذف با کلوز از سمت دور)
        // §4-5: ابطال با کلوز سمت دور قانون استراتژی است، نه گزینهٔ قابل‌خاموش‌کردن.
        for (zb in zones) {
            if (zb.status != ZoneStatus.ACTIVE) continue
            val dead = if (zb.dir == 1) cd.c < zb.bot else cd.c > zb.top
            if (dead) {
                zb.status = ZoneStatus.DELETED
                zb.endBi = i; zb.endT = cd.t
            }
        }
        // چرخهٔ عمر باکس‌های LV/HV (تریگر ۱ و ۲): این باکس‌ها قبلاً فقط با فراخوانی صریح ستاپ
        // بسته می‌شدند؛ اگر ستاپ پاک/جایگزین/بازسازی می‌شد، باکس بی‌مالک می‌ماند و تا ابد
        // ACTIVE روی چارت کشیده می‌شد. هر باکس ACTIVE که هیچ ستاپی مالکش نیست، همین‌جا بسته می‌شود.
        sweepOrphanBoxes(i, cd.t)
        if (agg1.closedNow && agg1.cl != null) prevT1Vol = agg1.cl!!.v
        prevChartVol = cd.v
        cnt.zoneCount = zones.size
        barCommitHook?.invoke(cd)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  ⑩ موتور ساختار (کندل مهم · Ready · pin · روند · ناحیه فیکس‌رنج)
    // ═══════════════════════════════════════════════════════════════════════════
    private fun updateStructureZoneRef(sb: Candle): Boolean {
        val insideRef = !zoneRefH.isNaN() && !zoneRefL.isNaN() &&
            sb.h <= zoneRefH && sb.l >= zoneRefL &&
            sb.o >= zoneRefL && sb.o <= zoneRefH &&
            sb.c >= zoneRefL && sb.c <= zoneRefH
        if (!insideRef) {
            zoneRefH = sb.h
            zoneRefL = sb.l
        }
        return insideRef
    }

    private fun structureEngine(sb: Candle) = structureEngine(sb, buildZones = true)

    private fun structureEngine(sb: Candle, buildZones: Boolean) {
        structBars.add(sb)
        while (structBars.size > 400) structBars.removeAt(0)
        val nS = structBars.size
        val insideRef = updateStructureZoneRef(sb)

        // استراتژی ۲: تعیین روند (کندل مهم · Ready · پین · HH/HL/LL/LH) حذف است؛ مستقیم ناحیه‌ها ثبت می‌شوند.
        if (cfg.strategy == Strategy.NO_TREND) {
            // در پیش‌بارگذاری تاریخچهٔ ساختار (buildZones=false) دیتای تریگر۲ نداریم ⇒ هیچ کاری لازم نیست.
            if (buildZones) registerZones(sb, insideRef)
            return
        }

        // ── چرخهٔ عمر کاندیدها : ابطال / Ready ──
        // §18-3/§18-6: در هر لحظه حداکثر یک کاندید IC وجود دارد؛ ثبت کاندید جدید در انتهای
        // همین متد و فقط پس از تعیین تکلیف کاندید قبلی و با رعایت توالی انجام می‌شود.
        // §4-1 سند نهایی: مرگ کاندید با «باز یا کلوز» آن‌طرف ref، مقایسه اکید
        // (تساوی نمی‌کُشد). Ready فقط با کلوز بالای سقف / زیر کف.
        for (p in pins) {
            if (p.status == 0 && sb.bi > p.startBi) {
                val dead = if (p.kind == 1) (sb.o < p.ref || sb.c < p.ref)
                          else          (sb.o > p.ref || sb.c > p.ref)
                if (dead) p.status = -1
                else {
                    val rdy = if (p.kind == 1) sb.c > p.hi else sb.c < p.lo
                    if (rdy) p.status = 1
                }
            }
        }

        // ── انتخاب Ready با تناوب سخت BU↔BE ──
        var readyBuNow = false
        var readyBeNow = false
        if (pins.isNotEmpty()) {
            if (expectedReady == 0 || expectedReady == 1) {
                val iBU = bestReady(pins, 1)
                if (iBU >= 0) {
                    val pBU = pins[iBU]
                    run {
                        readyBuNow = true
                        lastBUpin = pBU.lo
                        lastBUpinRef = pBU.ref; lastBUpinHi = pBU.hi
                        buActive = true; buSeq = ++pinSeq
                        lowestBUpin = if (lowestBUpin.isNaN()) pBU.lo else min(lowestBUpin, pBU.lo)
                        addMarker(pBU.impBi, pBU.lo, 1, "کندل مهم پایین‌ترین\nReady BU · pinBU")
                        lg("ENGINE", "Ready BU (pinBU) تایید شد", "lo=${f2(pBU.lo)} hi=${f2(pBU.hi)} bar=${sb.bi}")
                        alarm(AlertKind.PIN, "کندل مهم · Ready BU",
                            "کف ${f2(pBU.lo)} — کندل مهم پایین‌ترین‌ها تایید شد (${Tf.label(cfg.tfS)})")
                        expectedReady = -1
                        pins.removeAt(iBU)
                        if (trend == 0 && beActive && !lastBEpin.isNaN()) {
                            tempBoxTop = lastBEpin; tempBoxBot = lastBUpin
                        } else if (trend == -1 && !lastBEpin.isNaN() && LL.isNaN()) {
                            LL = lastBUpin; LH = lastBEpin
                            lhPinHi = lastBEpin; lhPinRef = lastBEpinRef; lhPinLo = lastBEpinLo; lhPinSeq = beSeq
                            llPinLo = lastBUpin; llPinRef = lastBUpinRef; llPinHi = lastBUpinHi; llPinSeq = buSeq
                            addStructLabel("LL", MarkerKind.PIN_BU, LL, pBU.impBi)
                            addStructLabel("LH", MarkerKind.PIN_BE, LH, -1)
                            lg("ENGINE", "LL / LH تعریف شد", "LL=${f2(LL)} LH=${f2(LH)} bar=${sb.bi}")
                        }
                    }
                }
            }
            if ((expectedReady == 0 || expectedReady == -1) && pins.isNotEmpty()) {
                val iBE = bestReady(pins, -1)
                if (iBE >= 0) {
                    val pBE = pins[iBE]
                    run {
                        readyBeNow = true
                        lastBEpin = pBE.hi
                        lastBEpinRef = pBE.ref; lastBEpinLo = pBE.lo
                        beActive = true; beSeq = ++pinSeq
                        highestBE = if (highestBE.isNaN()) pBE.hi else max(highestBE, pBE.hi)
                        addMarker(pBE.impBi, pBE.hi, -1, "کندل مهم بالاترین\nReady BE · pinBE")
                        lg("ENGINE", "Ready BE (pinBE) تایید شد", "hi=${f2(pBE.hi)} lo=${f2(pBE.lo)} bar=${sb.bi}")
                        alarm(AlertKind.PIN, "کندل مهم · Ready BE",
                            "سقف ${f2(pBE.hi)} — کندل مهم بالاترین‌ها تایید شد (${Tf.label(cfg.tfS)})")
                        expectedReady = 1
                        pins.removeAt(iBE)
                        if (trend == 0 && buActive && !lastBUpin.isNaN()) {
                            tempBoxTop = lastBEpin; tempBoxBot = lastBUpin
                        } else if (trend == 1 && !lastBUpin.isNaN() && HH.isNaN()) {
                            HH = lastBEpin; HL = lastBUpin
                            hlPinLo = lastBUpin; hlPinRef = lastBUpinRef; hlPinHi = lastBUpinHi; hlPinSeq = buSeq
                            hhPinHi = lastBEpin; hhPinRef = lastBEpinRef; hhPinLo = lastBEpinLo; hhPinSeq = beSeq
                            addStructLabel("HH", MarkerKind.PIN_BE, HH, pBE.impBi)
                            addStructLabel("HL", MarkerKind.PIN_BU, HL, -1)
                            lg("ENGINE", "HH / HL تعریف شد", "HH=${f2(HH)} HL=${f2(HL)} bar=${sb.bi}")
                        }
                    }
                }
            }
        }

        // کاندیدِ Ready که تناوب اجازهٔ ثبتش را نداد هرگز ثبت نمی‌شود؛ کاندیدهای مرده هم دور ریخته می‌شوند.
        // کاندیدهای هنوز تاییدنشده (status=0) می‌مانند.
        pins.removeAll { it.status != 0 }
        while (pins.size > 400) pins.removeAt(0)

        // ── اکسترمم جاری روند (پایهٔ لنگر صفر فیبوناچی، §4-8) ──
        // قبل از منطق پین/روند به‌روز می‌شود تا لنگر هیچ‌وقت از قیمتِ واقعیِ روند عقب نماند.
        if (trend == 1) { if (trendHigh.isNaN() || sb.h > trendHigh) trendHigh = sb.h }
        else if (trend == -1) { if (trendLow.isNaN() || sb.l < trendLow) trendLow = sb.l }

        // ── تعیین / ادامه / تغییر روند و ابطال پین‌ها (§4-1 سند نهایی) ──
        var evTrendUp = false; var evTrendDn = false
        var evChgUp = false; var evChgDn = false
        when (trend) {
            0 -> {
                if (!tempBoxTop.isNaN() && !tempBoxBot.isNaN()) {
                    if (sb.c > tempBoxTop) {
                        val top = tempBoxTop; val bot = tempBoxBot
                        trend = 1; evTrendUp = true
                        highestBE = top; lowestBUpin = bot
                        trendHigh = top; trendLow = bot
                        tempBoxTop = Double.NaN; tempBoxBot = Double.NaN
                        HH = Double.NaN; HL = Double.NaN
                        // در شروع روند صعودی، pinBU پایهٔ HL بعدی می‌ماند و منتظر اولین pinBE (HH) هستیم.
                        // pinBE موقت با همین شکست مصرف شده است: طبق سند «بعد از تعیین روند tempbox پاک
                        // می‌شود» و این پین دیگر وجود ندارد، پس مارکرش هم دور ریخته می‌شود (§18-4)؛
                        // وگرنه روی چارت کنار pinBE بعدی (HH) دو «کندل مهم بالاترین» پشت‌سرهم می‌ماند.
                        removePinMarker(MarkerKind.PIN_BE, top)
                        beActive = false
                        buActive = true
                        if (buSeq < beSeq) buSeq = ++pinSeq
                        expectedReady = -1
                        lg("ENGINE", "روند صعودی تثبیت شد (توالی)",
                            "tempBoxTop=${f2(top)} tempBoxBot=${f2(bot)} close=${f2(sb.c)}")
                        alarm(AlertKind.TREND, "روند صعودی شد",
                            "توالی کامل · کلوز ${f2(sb.c)} بالای سقف tempbox ${f2(top)}")
                    } else if (sb.c < tempBoxBot) {
                        val top = tempBoxTop; val bot = tempBoxBot
                        trend = -1; evTrendDn = true
                        highestBE = top; lowestBUpin = bot
                        trendHigh = top; trendLow = bot
                        tempBoxTop = Double.NaN; tempBoxBot = Double.NaN
                        LL = Double.NaN; LH = Double.NaN
                        // در شروع روند نزولی، pinBE پایهٔ LH بعدی می‌ماند و منتظر اولین pinBU (LL) هستیم.
                        // قرینهٔ حالت صعودی: pinBU موقت با همین شکست مصرف شده و مارکرش دور ریخته می‌شود (§18-4).
                        removePinMarker(MarkerKind.PIN_BU, bot)
                        buActive = false
                        beActive = true
                        if (beSeq < buSeq) beSeq = ++pinSeq
                        expectedReady = 1
                        lg("ENGINE", "روند نزولی تثبیت شد (توالی)",
                            "tempBoxBot=${f2(bot)} tempBoxTop=${f2(top)} close=${f2(sb.c)}")
                        alarm(AlertKind.TREND, "روند نزولی شد",
                            "توالی کامل · کلوز ${f2(sb.c)} زیر کف tempbox ${f2(bot)}")
                    }
                }
                if (trend == 0) {
                    if (!readyBuNow && buActive && !lastBUpinRef.isNaN() && beSeq <= buSeq) {
                        val crossedRef = sb.o < lastBUpinRef || sb.c < lastBUpinRef
                        val crossedLow = !lastBUpin.isNaN() && (sb.o < lastBUpin || sb.c < lastBUpin)
                        if (crossedRef || crossedLow) {
                            val oldPin = lastBUpin; val oldRef = lastBUpinRef
                            clearBUpin()
                            tempBoxTop = Double.NaN; tempBoxBot = Double.NaN
                            expectedReady = if (beActive) 1 else 0
                            addMarker(sb.bi, oldPin, 10, "pinBU بی‌اعتبار شد")
                            lg("ENGINE", "pinBU بی‌اعتبار و حذف شد — پین کامل پاک شد",
                                "ref=${f2(oldRef)} lo=${f2(oldPin)} o=${f2(sb.o)} c=${f2(sb.c)}")
                        }
                    }
                    if (!readyBeNow && beActive && !lastBEpinRef.isNaN() && buSeq <= beSeq) {
                        val crossedRef = sb.o > lastBEpinRef || sb.c > lastBEpinRef
                        val crossedHigh = !lastBEpin.isNaN() && (sb.o > lastBEpin || sb.c > lastBEpin)
                        if (crossedRef || crossedHigh) {
                            val oldPin = lastBEpin; val oldRef = lastBEpinRef
                            clearBEpin()
                            tempBoxTop = Double.NaN; tempBoxBot = Double.NaN
                            expectedReady = if (buActive) -1 else 0
                            addMarker(sb.bi, oldPin, 10, "pinBE بی‌اعتبار شد")
                            lg("ENGINE", "pinBE بی‌اعتبار و حذف شد — پین کامل پاک شد",
                                "ref=${f2(oldRef)} hi=${f2(oldPin)} o=${f2(sb.o)} c=${f2(sb.c)}")
                        }
                    }
                }
            }
            1 -> {
                // ۱) چرخش روند از صعودی به نزولی: بسته شدن حتی یک تیک زیر HL (شامل تبصرهٔ هم‌زمانی ReadyBE و شکست HL)
                if (!HL.isNaN() && sb.c < HL) {
                    val oldHL = HL; val oldHH = HH
                    trend = -1; evChgDn = true
                    highestBE = oldHH; lowestBUpin = oldHL
                    removeStructLabel("HH", oldHH); removeStructLabel("HL", oldHL)
                    HH = Double.NaN; HL = Double.NaN; LL = Double.NaN; LH = Double.NaN
                    clearBUpin()
                    // §4-1 (ویرایش جدید): اگر در همین کندل ReadyBE/pinBE تازه تایید شده باشد،
                    // همان پین فعال می‌ماند («هم ReadyBE و هم pinBE تایید است»)؛ بازگرداندن پین
                    // قدیمیِ HH آن را دور می‌ریخت.
                    if (!hhPinHi.isNaN() && !readyBeNow) {
                        lastBEpin = hhPinHi; lastBEpinRef = hhPinRef; lastBEpinLo = hhPinLo
                        beActive = true; beSeq = ++pinSeq
                    }
                    expectedReady = 1
                    lg("ENGINE", "چرخش روند به نزولی (کلوز زیر HL · توالی کامل)",
                        "HL=${f2(oldHL)} HH=${f2(oldHH)} close=${f2(sb.c)}")
                    alarm(AlertKind.TREND, "چرخش روند به نزولی",
                        "کلوز ${f2(sb.c)} زیر کف HL ${f2(oldHL)} بسته شد")
                } else {
                    // ۲) ادامهٔ روند صعودی: بسته شدن بالای HH ⇒ برگشت HH به pinBE و HL به pinBU
                    //    (§روند سند: «HH به pinBE برمی‌گردد و HL به pinBU برمی‌گردد» — پین‌ها پاک نمی‌شوند،
                    //     فقط برچسب ساختاری برداشته می‌شود و توالی با pinBU بعدی ادامه می‌یابد.)
                    var hhBroke = false
                    if (!HH.isNaN() && sb.c > HH) {
                        hhBroke = true
                        removeStructLabel("HH", HH); removeStructLabel("HL", HL)
                        HH = Double.NaN; HL = Double.NaN
                        if (buSeq < beSeq) {
                            // pinBE هنوز آخرین پین است: همان به‌عنوان pinBE می‌ماند و HL به pinBU قبلی برمی‌گردد؛
                            // پین بعدی طبق توالی pinBU است.
                            if (!hlPinLo.isNaN()) {
                                lastBUpin = hlPinLo; lastBUpinRef = hlPinRef; lastBUpinHi = hlPinHi
                                buActive = true; buSeq = hlPinSeq
                            }
                            beActive = true
                            expectedReady = 1
                        } else {
                            // pinBU بعد از HH ثبت شده است: هر دو پین سر جای خود می‌مانند و پین بعدی pinBE است.
                            expectedReady = -1
                        }
                    }
                    // ۳) ابطال pinBE بدون ثبت pinBU بعد از آن: HH و HL پاک می‌شوند و HL به pinBU برمی‌گردد
                    //    (در کندل شکست HH ابطال پین سنجیده نمی‌شود؛ آن رویداد تابع قاعدهٔ ادامهٔ روند است.)
                    if (!hhBroke && !readyBeNow && beActive && !lastBEpinRef.isNaN() && buSeq <= beSeq) {
                        val crossedRef = sb.o > lastBEpinRef || sb.c > lastBEpinRef
                        val crossedHigh = !lastBEpin.isNaN() && (sb.o > lastBEpin || sb.c > lastBEpin)
                        if (crossedRef || crossedHigh) {
                            val oldPin = lastBEpin; val oldRef = lastBEpinRef
                            // فقط اگر pinBE باطل‌شده خودِ HH باشد، HH/HL پاک می‌شوند (سند §روند)
                            val wasHH = !HH.isNaN() && hhPinSeq == beSeq
                            clearBEpin()
                            if (wasHH) {
                                removeStructLabel("HH", HH); removeStructLabel("HL", HL)
                                HH = Double.NaN; HL = Double.NaN
                                if (!hlPinLo.isNaN()) {
                                    lastBUpin = hlPinLo; lastBUpinRef = hlPinRef; lastBUpinHi = hlPinHi
                                    buActive = true; buSeq = hlPinSeq
                                }
                            }
                            expectedReady = if (buActive) -1 else 0
                            addMarker(sb.bi, oldPin, 10, "pinBE بی‌اعتبار شد")
                            lg("ENGINE", "pinBE بی‌اعتبار و حذف شد — پین کامل پاک شد",
                                "ref=${f2(oldRef)} hi=${f2(oldPin)} o=${f2(sb.o)} c=${f2(sb.c)}")
                        }
                    }
                    // ۴) ابطال pinBU بدون ثبت pinBE بعد از آن
                    if (!readyBuNow && buActive && !lastBUpinRef.isNaN() && beSeq <= buSeq) {
                        val crossedRef = sb.o < lastBUpinRef || sb.c < lastBUpinRef
                        val crossedLow = !lastBUpin.isNaN() && (sb.o < lastBUpin || sb.c < lastBUpin)
                        val closedBelowPin = !lastBUpin.isNaN() && sb.c < lastBUpin
                        if (crossedRef || crossedLow) {
                            val oldPin = lastBUpin; val oldRef = lastBUpinRef
                            clearBUpin()
                            expectedReady = if (beActive) 1 else 0
                            addMarker(sb.bi, oldPin, 10, "pinBU بی‌اعتبار شد")
                            lg("ENGINE", "pinBU بی‌اعتبار و حذف شد — پین کامل پاک شد",
                                "ref=${f2(oldRef)} lo=${f2(oldPin)} o=${f2(sb.o)} c=${f2(sb.c)}")
                            if (closedBelowPin) {
                                lg("ENGINE", "کلوز زیر pinBU ولی توالی کامل نبود — فقط pinBU حذف شد، روند صعودی ماند",
                                    "pinBU=${f2(oldPin)} close=${f2(sb.c)} buSeq=$buSeq beSeq=$beSeq")
                            }
                        }
                    }
                }
            }
            -1 -> {
                // ۱) چرخش روند از نزولی به صعودی: بسته شدن حتی یک تیک بالای LH (شامل تبصرهٔ هم‌زمانی ReadyBU و شکست LH)
                if (!LH.isNaN() && sb.c > LH) {
                    val oldLH = LH; val oldLL = LL
                    trend = 1; evChgUp = true
                    highestBE = oldLH; lowestBUpin = oldLL
                    removeStructLabel("LL", oldLL); removeStructLabel("LH", oldLH)
                    HH = Double.NaN; HL = Double.NaN; LL = Double.NaN; LH = Double.NaN
                    clearBEpin()
                    // قرینهٔ حالت بالا: ReadyBU/pinBU تازه‌ی همین کندل حفظ می‌شود.
                    if (!llPinLo.isNaN() && !readyBuNow) {
                        lastBUpin = llPinLo; lastBUpinRef = llPinRef; lastBUpinHi = llPinHi
                        buActive = true; buSeq = ++pinSeq
                    }
                    expectedReady = -1
                    lg("ENGINE", "چرخش روند به صعودی (کلوز بالای LH · توالی کامل)",
                        "LH=${f2(oldLH)} LL=${f2(oldLL)} close=${f2(sb.c)}")
                    alarm(AlertKind.TREND, "چرخش روند به صعودی",
                        "کلوز ${f2(sb.c)} بالای سقف LH ${f2(oldLH)} بسته شد")
                } else {
                    // ۲) ادامهٔ روند نزولی: بسته شدن زیر LL ⇒ برگشت LL به pinBU و LH به pinBE
                    //    (§روند سند: «LL به pinBU برمی‌گردد و LH به pinBE برمی‌گردد» — پین‌ها پاک نمی‌شوند.)
                    var llBroke = false
                    if (!LL.isNaN() && sb.c < LL) {
                        llBroke = true
                        removeStructLabel("LL", LL); removeStructLabel("LH", LH)
                        LL = Double.NaN; LH = Double.NaN
                        if (beSeq < buSeq) {
                            // pinBU هنوز آخرین پین است: همان به‌عنوان pinBU می‌ماند و LH به pinBE قبلی برمی‌گردد؛
                            // پین بعدی طبق توالی pinBE است.
                            if (!lhPinHi.isNaN()) {
                                lastBEpin = lhPinHi; lastBEpinRef = lhPinRef; lastBEpinLo = lhPinLo
                                beActive = true; beSeq = lhPinSeq
                            }
                            buActive = true
                            expectedReady = -1
                        } else {
                            // pinBE بعد از LL ثبت شده است: هر دو پین سر جای خود می‌مانند و پین بعدی pinBU است.
                            expectedReady = 1
                        }
                    }
                    // ۳) ابطال pinBU بدون ثبت pinBE بعد از آن: LL و LH پاک می‌شوند و LH به pinBE برمی‌گردد
                    //    (در کندل شکست LL ابطال پین سنجیده نمی‌شود؛ آن رویداد تابع قاعدهٔ ادامهٔ روند است.)
                    if (!llBroke && !readyBuNow && buActive && !lastBUpinRef.isNaN() && beSeq <= buSeq) {
                        val crossedRef = sb.o < lastBUpinRef || sb.c < lastBUpinRef
                        val crossedLow = !lastBUpin.isNaN() && (sb.o < lastBUpin || sb.c < lastBUpin)
                        if (crossedRef || crossedLow) {
                            val oldPin = lastBUpin; val oldRef = lastBUpinRef
                            // فقط اگر pinBU باطل‌شده خودِ LL باشد، LL/LH پاک می‌شوند
                            val wasLL = !LL.isNaN() && llPinSeq == buSeq
                            clearBUpin()
                            if (wasLL) {
                                removeStructLabel("LL", LL); removeStructLabel("LH", LH)
                                LL = Double.NaN; LH = Double.NaN
                                if (!lhPinHi.isNaN()) {
                                    lastBEpin = lhPinHi; lastBEpinRef = lhPinRef; lastBEpinLo = lhPinLo
                                    beActive = true; beSeq = lhPinSeq
                                }
                            }
                            expectedReady = if (beActive) 1 else 0
                            addMarker(sb.bi, oldPin, 10, "pinBU بی‌اعتبار شد")
                            lg("ENGINE", "pinBU بی‌اعتبار و حذف شد — پین کامل پاک شد",
                                "ref=${f2(oldRef)} lo=${f2(oldPin)} o=${f2(sb.o)} c=${f2(sb.c)}")
                        }
                    }
                    // ۴) ابطال pinBE بدون ثبت pinBU بعد از آن (در کندل شکست LL سنجیده نمی‌شود)
                    if (!llBroke && !readyBeNow && beActive && !lastBEpinRef.isNaN() && buSeq <= beSeq) {
                        val crossedRef = sb.o > lastBEpinRef || sb.c > lastBEpinRef
                        val crossedHigh = !lastBEpin.isNaN() && (sb.o > lastBEpin || sb.c > lastBEpin)
                        val closedAbovePin = !lastBEpin.isNaN() && sb.c > lastBEpin
                        if (crossedRef || crossedHigh) {
                            val oldPin = lastBEpin; val oldRef = lastBEpinRef
                            clearBEpin()
                            expectedReady = if (buActive) -1 else 0
                            addMarker(sb.bi, oldPin, 10, "pinBE بی‌اعتبار شد")
                            lg("ENGINE", "pinBE بی‌اعتبار و حذف شد — پین کامل پاک شد",
                                "ref=${f2(oldRef)} hi=${f2(oldPin)} o=${f2(sb.o)} c=${f2(sb.c)}")
                            if (closedAbovePin) {
                                lg("ENGINE", "کلوز بالای pinBE ولی توالی کامل نبود — فقط pinBE حذف شد، روند نزولی ماند",
                                    "pinBE=${f2(oldPin)} close=${f2(sb.c)} buSeq=$buSeq beSeq=$beSeq")
                            }
                        }
                    }
                }
            }
        }

        if (evTrendUp || evTrendDn || evChgUp || evChgDn) {
            markers.removeAll { it.text.startsWith("روند فعلی:") }
        }
        if (evTrendUp) addMarkerAtTime(curT, sb.bi, sb.c, MarkerKind.TREND_UP, "شروع روند صعودی ▲")
        if (evTrendDn) addMarkerAtTime(curT, sb.bi, sb.c, MarkerKind.TREND_DOWN, "شروع روند نزولی ▼")
        if (evChgUp) addMarkerAtTime(curT, sb.bi, sb.c, MarkerKind.TREND_UP, "چرخش روند: نزولی ← صعودی ▲")
        if (evChgDn) addMarkerAtTime(curT, sb.bi, sb.c, MarkerKind.TREND_DOWN, "چرخش روند: صعودی ← نزولی ▼")

        if (evChgDn || evTrendDn) cancelDir(1)
        if (evChgUp || evTrendUp) cancelDir(-1)

        // ── ثبت کاندید کندل مهم (§4-1 + §18-3/§18-4/§18-6) ──
        // • کاندیدی که با توالی نمی‌خواند دور ریخته می‌شود (§18-4).
        // • تا وقتی کاندید قبلی تعیین تکلیف نشده (Ready یا ابطال)، کاندید جدید پذیرفته نمی‌شود (§18-6).
        // • فقط کاندیدِ هم‌جهت با توالی مورد انتظار ثبت می‌شود (§18-1/§18-3).
        if (expectedReady != 0) pins.removeAll { it.kind != expectedReady }
        if (nS >= 2 && pins.isEmpty()) {
            val a = structBars[nS - 2]
            val b = structBars[nS - 1]
            if (a.c < a.o && b.c > b.o && expectedReady != -1) {          // کندل مهم در پایین‌ترین‌ها
                val (impH, impL, impBi) = pickLow(a, b)
                // §4-1: سطح ابطال پایین‌ترین کندل‌ها = min(close(a), open(b))
                val refLo = min(a.c, b.o)
                pins.add(Pin(1, impH, impL, impBi, b.bi, refLo, 0))
                lg("ENGINE", "کاندید کندل مهم پایین‌ترین‌ها ثبت شد",
                    "kind=BU hi=${f2(impH)} lo=${f2(impL)} ref=${f2(refLo)} bar=${b.bi}")
            } else if (a.c > a.o && b.c < b.o && expectedReady != 1) {    // کندل مهم در بالاترین‌ها
                val (impH2, impL2, impBi2) = pickHigh(a, b)
                // §4-1: سطح ابطال بالاترین کندل‌ها = max(close(a), open(b))
                val refHi = max(a.c, b.o)
                pins.add(Pin(-1, impH2, impL2, impBi2, b.bi, refHi, 0))
                lg("ENGINE", "کاندید کندل مهم بالاترین‌ها ثبت شد",
                    "kind=BE hi=${f2(impH2)} lo=${f2(impL2)} ref=${f2(refHi)} bar=${b.bi}")
            }
        }

        // در پیش‌بارگذاری تاریخچهٔ ساختار، دیتای تریگر۲ نداریم؛ بنابراین فقط
        // توالی/روند ثبت می‌شود و هیچ باکس پروفایل حدسی ساخته نمی‌شود.
        if (!buildZones) return

        for (s in setups) {
            if (s.stage in 2..5 && !s.midRef.isNaN()) updateSetupFib(s)
        }

        registerZones(sb, insideRef)
    }

    /**
     * پروفایل حجم فیکس‌رنج روی کندل ساختار بستهٔ [sb] + ثبت ناحیه‌ها و ستاپ‌هایشان (حداکثر ۲ ناحیه در هر کندل، §۴-۵ سند).
     * فقط روی کندل بستهٔ ساختار صدا زده می‌شود (§۱۸-۱۵).
     */
    private fun registerZones(sb: Candle, insideRef: Boolean) {
        // ── پروفایل حجم فیکس‌رنج + ثبت ناحیه‌ها ──
        val bullC = sb.c > sb.o
        val bearC = sb.c < sb.o
        // استراتژی ۱ (سند): فقط کندل هم‌جهت با روند، و جهت ناحیه = جهت روند.
        // استراتژی ۲: روند نیست؛ هر کندل سبز ناحیهٔ خرید و هر کندل قرمز ناحیهٔ فروش می‌سازد (دوجی = بدون جهت = هیچ).
        val noTrend = cfg.strategy == Strategy.NO_TREND
        val zoneDir = if (noTrend) (if (bullC) 1 else if (bearC) -1 else 0) else trend
        val dirOK = if (noTrend) zoneDir != 0 else ((trend == 1 && bullC) || (trend == -1 && bearC))
        val prof = VolumeProfile.build(microS, cfg.vpRows, cfg.vpVA, cfg.distMode, cfg.vpSmooth, cfg.mintick)
        profileOnLastS = prof
        lastProfBars = microS.size
        if (cfg.showPocVA && !prof.poc.isNaN()) lastPocPx = prof.poc
        if (insideRef) {
            if (dirOK) {
                lg("ZONE", "نواحی کندل ساختار درونی باطل شد (§18-22)",
                    "bar=${sb.bi} o=${f2(sb.o)} h=${f2(sb.h)} l=${f2(sb.l)} c=${f2(sb.c)} ref=[${f2(zoneRefL)}..${f2(zoneRefH)}]")
            }
            return
        }
        if (dirOK && !prof.step.isNaN() && prof.step > 0) {
            // §4-5: یک پیمایش برای هر کندل ساختار؛ حداکثر ۲ ناحیه در کل و نامزدهای ردشده هم
            // سهمیه را مصرف می‌کنند (شمارهٔ Z1/Z2 بین پذیرفته‌ها و ردشده‌ها مشترک است).
            val cand = VolumeProfile.zoneCandidates(prof, cfg.vpRows, bullC, sb.c, cfg.minZoneRows)
            cnt.zoneRejected += cand.rejected.size
            // گزینهٔ نمایش نباید تاریخچهٔ ناحیه‌های ردشده را از وضعیت ذخیره حذف کند.
            for (rz in cand.rejected) {
                addZoneBox(zoneDir, rz, sb, ZoneStatus.REJECTED)
                lg("ZONE", "ناحیهٔ ردشده (کلوز داخل محدوده بود)",
                    "dir=${if (bullC) "BULL" else "BEAR"} top=${f2(rz.top)} bot=${f2(rz.bot)} rows=${rz.loIdx}-${rz.hiIdx} bar=${sb.bi}")
            }
            val zs = cand.accepted
            for (z in zs) {
                if (z.top > z.bot) {
                    addZoneBox(zoneDir, z, sb, ZoneStatus.ACTIVE)
                    lastZoneTop = z.top; lastZoneBot = z.bot
                    cnt.setup++
                    val st = Setup(setupSeq++, zoneDir, 0, z.top, z.bot, z.idx, sb.bi)
                    st.zT = sb.t
                    st.note = "ثبت شد"
                    setups.add(st)
                    lg("ZONE", "ناحیهٔ فیکس‌رنج ثبت شد · Z${z.idx}",
                        "dir=${if (bullC) "BULL" else "BEAR"} top=${f2(z.top)} bot=${f2(z.bot)} rows=${z.loIdx}-${z.hiIdx} bar=${sb.bi} setup=${st.id}")
                    alarm(AlertKind.ZONE_NEW, "ناحیهٔ فیکس‌رنج Z${z.idx} ساخته شد",
                        "${if (bullC) "صعودی" else "نزولی"} · ${f2(z.bot)} تا ${f2(z.top)} · ستاپ #${st.id}")
                }
            }
            pruneSetups()
        }
    }

    private fun markStructureZoneStatus(s: Setup, status: Int, endBi: Int = curBi, endT: Long = curT) {
        zones.firstOrNull {
            it.dir == s.dir && it.idx == s.zIdx && it.status == ZoneStatus.ACTIVE &&
                it.top == s.zTop && it.bot == s.zBot &&
                (if (s.zT > 0L) it.createdT == s.zT else it.createdBi == s.zBi)
        }
            ?.let { it.status = status; it.endBi = endBi; it.endT = endT }
    }

    private fun pruneSetups() {
        // §4-5 و §18-19: تعداد ناحیه‌های ساختار نامحدود است و هر ناحیه تا زمانی که طبق قواعد
        // باطل/مصرف نشود باقی می‌ماند؛ بنابراین فقط پروندهٔ ستاپ‌های پایان‌یافته (stage=8) برای
        // جلوگیری از رشد بی‌نهایت حافظه پاک‌سازی می‌شود.
        val trackedCap = max(TRACKED_SETUPS_MIN, cfg.maxSetups)
        while (setups.size > trackedCap) {
            val pick = setups.indexOfFirst { it.stage == 8 }
            if (pick < 0) break
            // باگ «باکس باطل‌شده روی چارت می‌ماند»: اگر ستاپِ پایان‌یافته حذف می‌شد، باکس‌های
            // LV/HV آن هیچ مالکی نداشتند و تا ابد ACTIVE می‌ماندند (روی چارت کشیده می‌شدند).
            val dead = setups[pick]
            closeT1Box(dead, ZoneStatus.DELETED, curBi, curT)
            closeT2Box(dead, ZoneStatus.DELETED, curBi, curT)
            setups.removeAt(pick)
        }
    }

    /** §18-10: لغو سفارش مسلح‌شده (معلق) به‌دلیل ابطال/مصرف ناحیهٔ ولوم کم. معاملهٔ باز دست نمی‌خورد (§18-11). */
    private fun cancelArmedForLv(s: Setup, reason: String) {
        broker?.onSetupInvalidated(s, curT, curBi)
        closeT1Box(s, ZoneStatus.DELETED, curBi, curT)
        closeT2Box(s, ZoneStatus.DELETED, curBi, curT)
        s.hvBi = -1; s.hvT = 0L; s.hvConfBi = -1; s.hvConfT = 0L; s.runMax = Double.NaN
        s.orderId = -1
        s.prevEntry = Double.NaN
        s.rearmAllowed = false
        events.add("[${s.id}] سفارش معلق لغو شد — $reason")
        lg("ENGINE", "سفارش معلق لغو شد ($reason) · ستاپ #${s.id}", "bar=$curBi")
        alarm(AlertKind.CANCEL, "سفارش معلق لغو شد", "ستاپ #${s.id} · $reason")
    }

    /**
     * باکس‌های LV/HV بی‌مالک را می‌بندد.
     *
     * قاعدهٔ سند: هر باکس تریگر ۱/۲ فقط تا وقتی معتبر است که ستاپش زنده باشد. ولی بستن باکس‌ها
     * دستی و پراکنده بود (هر شاخهٔ ابطال/پایان خودش صدا می‌زد) و بعضی شاخه‌ها جا می‌افتاد؛ نتیجه:
     * باکس باطل‌شده روی چارت می‌ماند و کاربر فکر می‌کند ناحیه هنوز معتبر است.
     * این جارو تضمین می‌کند هیچ باکس ACTIVE بدون مالک باقی نماند.
     */
    private fun sweepOrphanBoxes(bi: Int, t: Long) {
        if (t1Zones.isEmpty() && t2Zones.isEmpty()) return
        val lvOwned = HashSet<Long>()
        val hvOwned = HashSet<Long>()
        for (s in setups) {
            if (s.lvZoneId >= 0) lvOwned.add(s.lvZoneId)
            if (s.hvZoneId >= 0) hvOwned.add(s.hvZoneId)
        }
        var n = 0
        for (z in t1Zones) {
            if (z.status != ZoneStatus.ACTIVE || z.id in lvOwned) continue
            z.status = ZoneStatus.DELETED; z.endBi = bi; z.endT = t; n++
        }
        for (z in t2Zones) {
            if (z.status != ZoneStatus.ACTIVE || z.id in hvOwned) continue
            z.status = ZoneStatus.DELETED; z.endBi = bi; z.endT = t; n++
        }
        if (n > 0) lg("ENGINE", "باکس بی‌مالک بسته شد", "تعداد=$n bar=$bi")
    }

    private fun closeT1Box(s: Setup, status: Int, endBi: Int = curBi, endT: Long = curT) {
        if (s.lvZoneId >= 0) {
            t1Zones.firstOrNull { it.id == s.lvZoneId && it.status == ZoneStatus.ACTIVE }?.let {
                it.status = status; it.endBi = endBi; it.endT = endT
            }
            s.lvZoneId = -1L
        }
    }

    private fun closeT2Box(s: Setup, status: Int, endBi: Int = curBi, endT: Long = curT) {
        if (s.hvZoneId >= 0) {
            t2Zones.firstOrNull { it.id == s.hvZoneId && it.status == ZoneStatus.ACTIVE }?.let {
                it.status = status; it.endBi = endBi; it.endT = endT
            }
            s.hvZoneId = -1L
        }
    }

    private fun addZoneBox(dir: Int, z: Zone, sb: Candle, status: Int) {
        zones.add(ZoneBox(zoneSeq++, dir, z.idx, z.top, z.bot, sb.bi, sb.t, z.loIdx, z.hiIdx, sb.bi, sb.t, status))
    }

    /**
     * §4-5 سند: «**تعداد ناحیه‌ها نامحدود است.** ناحیه‌هایی که باطل نشده‌اند باید ثبت شوند و روی
     * حافظه‌ی گوشی ذخیره شوند، تا در تایم‌فریم‌های کوچک‌تر دوباره فراخوانی شوند.»
     *
     * بازسازی موتور روی یک پنجرهٔ تاریخچهٔ کوتاه‌تر نباید ناحیه‌های قدیمی را از حافظه/چارت حذف کند.
     * این متد باکس‌های ساختار، LV و HV را از وضعیت قبلی برمی‌گرداند و ستاپ‌های ساختاریِ هنوز
     * استفاده‌نشده (تا پیش از مسلح‌شدن) را هم نگه می‌دارد. ستاپی که قبلاً به سفارش رسیده (stage 6/7)
     * هرگز از نو ساخته نمی‌شود؛ قاعدهٔ سند می‌گوید برای هر LV فقط یک سفارش مجاز است.
     *
     * باکس‌های داخل پنجره را موتور از داده می‌سازد؛ باکس‌های قبلی با هویت زمانی/قیمتی یکتا می‌شوند،
     * نه با شناسهٔ محلی که بعد از reset از صفر شروع می‌شود. برای ناحیهٔ فعال، کندل‌های پنجرهٔ تازه
     * نیز بررسی می‌شوند تا کلوزِ باطل‌کننده‌ای که بعد از [fromT] رخ داده جا نماند.
     *
     * @return تعداد باکس‌های بازگردانده‌شده
     */
    fun carryOverZones(
        prevZones: List<ZoneBox>,
        prevT1: List<ZoneBox>,
        prevT2: List<ZoneBox>,
        fromT: Long,
        prevZoneSeq: Long = -1L,
        prevSetups: List<Setup> = emptyList(),
        currentCandles: List<Candle> = emptyList()
    ): Int {
        if (fromT <= 0L) return 0
        val allOld = prevZones + prevT1 + prevT2
        val nextZoneId = maxOf(
            zoneSeq,
            prevZoneSeq,
            (allOld.maxOfOrNull { it.id + 1L } ?: 0L)
        )
        zoneSeq = nextZoneId

        val structureIds = HashMap<Long, Long>()
        val t1Ids = HashMap<Long, Long>()
        val t2Ids = HashMap<Long, Long>()
        var n = 0
        n += carryZoneList(prevZones, zones, fromT, structureIds, currentCandles, checkStructureInvalidation = true)
        n += carryZoneList(prevT1, t1Zones, fromT, t1Ids, currentCandles, checkStructureInvalidation = false)
        n += carryZoneList(prevT2, t2Zones, fromT, t2Ids, currentCandles, checkStructureInvalidation = false)

        val firstBi = currentCandles.firstOrNull()?.bi ?: -1
        val lastBi = currentCandles.lastOrNull()?.bi ?: -1
        // §4-5: هر ناحیهٔ ابطال‌نشده‌ای که در این اجرا ستاپ ندارد (چه قدیمی‌تر از پنجره، چه
        // داخل پنجره ولی بازسازی‌نشده) باید ستاپ منتظرِ برخورد بگیرد؛ وگرنه باکس روی چارت
        // می‌ماند ولی هیچ‌وقت معامله نمی‌شود.
        val oldStructure = prevZones.filter { it.createdT > 0L && it.status != ZoneStatus.CAP }
        for (oldZone in oldStructure) {
            val matches = zones.filter { sameZone(it, oldZone) }
            // اگر همان ناحیه در این اجرا چند وضعیت داشته باشد، نسخهٔ فعال مرجع است.
            val restored = matches.firstOrNull { it.status == ZoneStatus.ACTIVE } ?: matches.firstOrNull() ?: continue
            if (restored.status != ZoneStatus.ACTIVE) continue
            // ستاپ قبلی همان ناحیه (اگر بوده) با **هویت** پیدا می‌شود، نه هندسه؛ و از میان
            // نسخه‌های تکراری، ستاپِ زنده (stage ۰..۶) مرجع است.
            val oldSetup = prevSetups.firstOrNull { setupMatchesZone(it, oldZone) && it.stage in 0..6 && !it.zGone }
                ?: prevSetups.firstOrNull { setupMatchesZone(it, oldZone) }
            if (oldSetup == null) {
                // فایل قدیمی/محدود که فقط باکس را داشت: باکس معتبر را به یک ستاپ تازهٔ منتظر برخورد تبدیل کن.
                if (setups.none { setupMatchesZone(it, restored) }) {
                    val s = Setup(setupSeq++, restored.dir, 0, restored.top, restored.bot, restored.idx, -1)
                    s.zT = restored.createdT
                    s.note = "ناحیهٔ قدیمیِ بازیابی‌شده · منتظر برخورد"
                    setups.add(s)
                }
            } else if (oldSetup.stage in 0..5 && !oldSetup.zGone) {
                if (setups.any { setupMatchesZone(it, restored) }) continue
                val copy = oldSetup.copyWithId(setupSeq++)
                // ایندکس‌های محلیِ سری قبلی پس از دانلود/بازیابی معنی ندارند؛ state و timestampها
                // حفظ می‌شوند و همهٔ مقایسه‌های کندلی بعدی از کندل تازهٔ بعد از بازسازی ادامه می‌یابند.
                copy.zBi = -1
                copy.touchBi = -1
                copy.midBi = -1
                copy.midConfirmBi = if (!copy.midRef.isNaN()) lastBi else -1
                copy.markBi = if (oldSetup.markBi >= 0) maxOf(0, firstBi) else -1
                copy.lvBi = if (oldSetup.lvBi >= 0) maxOf(0, firstBi) else -1
                copy.lvConfBi = if (oldSetup.lvConfBi >= 0) lastBi else -1
                copy.hvBi = if (oldSetup.hvBi >= 0) maxOf(0, firstBi) else -1
                copy.hvScanBi = -1
                copy.hvConfBi = -1
                copy.armBi = -1
                copy.entryBi = -1
                copy.orderId = -1L
                copy.lvZoneId = t1Ids[oldSetup.lvZoneId] ?: -1L
                copy.hvZoneId = t2Ids[oldSetup.hvZoneId] ?: -1L
                if (copy.zT <= 0L) copy.zT = restored.createdT
                setups.add(copy)
            }
            // stage 6/7: باکس باقی می‌ماند، اما برای LV قبلی سفارش/معاملهٔ تکراری ایجاد نمی‌شود.
        }
        setupSeq = maxOf(setupSeq, (prevSetups.maxOfOrNull { it.id + 1L } ?: 0L))
        if (n > 0) {
            cnt.zoneCount = zones.size
            lg("ZONE", "باکس‌ها و ستاپ‌های قدیمیِ پنجرهٔ داده بازیابی شدند (§4-5 سند)",
                "تعداد=$n ساختار=${zones.size} تریگر۱=${t1Zones.size} تریگر۲=${t2Zones.size} ستاپ=${setups.size} از=$fromT")
        }
        return n
    }

    /**
     * آیا ستاپ [s] به همین ناحیه [z] تعلق دارد؟ (هویت، نه هندسه — §4-5)
     * ستاپ‌های ساختهٔ موتور `zT` را از کندل ساختار می‌گیرند؛ ستاپ‌های بازیابی‌شدهٔ قدیمی ممکن است
     * فقط `zBi` داشته باشند.
     */
    private fun setupMatchesZone(s: Setup, z: ZoneBox): Boolean =
        s.dir == z.dir && s.zIdx == z.idx &&
            (if (z.createdT > 0L) s.zT == z.createdT else (s.zT <= 0L && s.zBi == z.createdBi))

    /**
     * هویت یک ناحیه در بازسازی: «همان کندل ساختار + همان شمارهٔ Z1/Z2 + همان جهت».
     *
     * §4-5 سند: هندسهٔ باکس جزئی از هویت ناحیه **نیست**. اگر همان کندل ساختار در اجرای تازه
     * پروفایل دقیق‌تری (دادهٔ ریز بیشتر) بدهد و سقف/کف کمی جابه‌جا شود، همچنان همان ناحیه است و
     * نباید دو نسخه از آن روی چارت/در فایل ذخیره شود. مقایسهٔ قبلی که سقف/کف را هم شرط می‌کرد،
     * هر بازسازی با دادهٔ کمی متفاوت یک «ناحیهٔ تازه» می‌ساخت و باکس‌های قدیمی تکثیر می‌شدند.
     *
     * شناسهٔ محلی ([ZoneBox.id]) هم معیار نیست: بعد از `reset` از صفر شروع می‌شود.
     */
    private fun sameZone(a: ZoneBox, b: ZoneBox): Boolean =
        a.dir == b.dir && a.idx == b.idx &&
            (if (a.createdT > 0L && b.createdT > 0L) a.createdT == b.createdT else a.createdBi == b.createdBi)

    private fun carryZoneList(
        src: List<ZoneBox>,
        dst: ArrayList<ZoneBox>,
        fromT: Long,
        idMap: MutableMap<Long, Long>,
        currentCandles: List<Candle>,
        checkStructureInvalidation: Boolean
    ): Int {
        var n = 0
        for (z in src) {
            // باکس‌های CAP فایل‌های قدیمی نیز تاریخچه‌اند؛ نباید در بازسازی گم شوند.
            // وضعیت CAP حفظ می‌شود اما به معاملهٔ جدید تبدیل نمی‌شود (وضعیت اصلی ناشناخته است).
            // §4-5 سند: باکس‌های قدیمی حتی وقتی تاریخ ساختشان **داخل** پنجرهٔ دادهٔ تازه است هم
            // نباید دوباره اضافه/حذف شوند؛ اگر موتور در این اجرا خودش همان ناحیه را نساخته باشد
            // (مثلاً چون دادهٔ ریز آن بازه در دسترس نبوده)، نسخهٔ ذخیره‌شده حفظ می‌شود.
            if (z.createdT <= 0L) continue
            val existing = dst.firstOrNull { sameZone(it, z) }
            if (existing != null) {
                idMap[z.id] = existing.id
                continue
            }
            val copy = ZoneBox(
                zoneSeq++, z.dir, z.idx, z.top, z.bot, -1, z.createdT, z.loIdx, z.hiIdx,
                -1, z.endT, z.status
            )
            // §4-5: ابطال یک **قاعدهٔ استراتژی** است («کلوز کندل تریگر۲ از سمت دور»)، نه گزینهٔ
            // نمایشی؛ پس بازپخش ابطال روی پنجرهٔ تازه بدون توجه به `clearUsedZones` انجام می‌شود
            // تا باکسِ کهنه‌ای که در فاصلهٔ دانلود باطل شده، دوباره «فعال/قابل‌معامله» برنگردد.
            if (checkStructureInvalidation && copy.status == ZoneStatus.ACTIVE) {
                val broken = currentCandles.firstOrNull { bar ->
                    bar.t >= fromT && if (copy.dir == 1) bar.c < copy.bot else bar.c > copy.top
                }
                if (broken != null) {
                    copy.status = ZoneStatus.DELETED
                    copy.endBi = broken.bi
                    copy.endT = broken.t
                }
            }
            dst.add(copy)
            idMap[z.id] = copy.id
            n++
        }
        return n
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  ⑪ موتور میانی (تایید میانی · کندل مارک‌شده · ابطال)
    // ═══════════════════════════════════════════════════════════════════════════
    private fun middleEngine(mc: Candle) {
        val mcCloseBi = maxOf(mc.bi, curBi - 1)
        for (s in setups) {
            if (s.stage < 1 || s.stage >= 7) continue
            // §4-8 (ویرایش جدید سند): «لغو سفارش معلق: اگر ستاپ قبل از پر شدن باطل شود (شکست ناحیهٔ
            // ساختار، تغییر روند)» — ابطال تایید میانی دیگر در فهرست لغو نیست. پس وقتی سفارش
            // مسلح شده (stage=6)، زنجیرهٔ LV/HV و سفارش دست‌نخورده می‌مانند و فقط تایید میانی پاک می‌شود.
            if (s.stage == 6) {
                val brokenArmed = if (cfg.midInvalidClose) {
                    if (s.dir == 1) mc.c < s.midRef else mc.c > s.midRef
                } else {
                    if (s.dir == 1) mc.l < s.midRef else mc.h > s.midRef
                }
                if (!s.midRef.isNaN() && brokenArmed) {
                    val old = s.midRef
                    s.midRef = Double.NaN; s.midBi = -1; s.midT = 0L; s.markBi = -1
                    clearSetupFib(s)
                    addMarker(mc.bi, old, 10, "تایید میانی باطل شد (سفارش مسلح پابرجاست)")
                    lg("ENGINE", "تایید میانی باطل شد — سفارش مسلح لغو نشد (§4-8 سند) · ستاپ #${s.id}",
                        "سطح قبلی=${f2(old)} bar=${mc.bi}")
                }
                continue
            }
            if (s.midRef.isNaN() && (s.touchMidStart <= 0L || mc.t >= s.touchMidStart)) {
                // ✔ تایید میانی
                s.midRef = if (s.dir == 1) mc.l else mc.h
                s.midBi = mc.bi
                s.midT = mc.t
                s.midConfirmBi = mcCloseBi
                s.markBi = -1; s.markH = Double.NaN; s.markL = Double.NaN
                s.stage = 2
                cnt.mid++
                s.lvReEntered = false
                updateSetupFib(s)
                lg("ENGINE", "تایید میانی انجام شد · ستاپ #${s.id}",
                    if (s.dir == 1) "کف تایید=${f2(s.midRef)} bar=${mc.bi}" else "سقف تایید=${f2(s.midRef)} bar=${mc.bi}")
                addMarker(mc.bi, s.midRef, 3, if (s.dir == 1) "کف تایید میانی" else "سقف تایید میانی")
                if (cfg.tf1 < cfg.tfM) {
                    scanLvFromMidExtreme(s, mc, mcCloseBi, s.dir == 1)
                }
            } else if (!s.midRef.isNaN()) {
                // ✖ ابطال تایید میانی — قبل از مارک چک می‌شود، چون کندل مارک هم
                //   می‌تواند (حتی در همان کندل پس از تایید، در پاس‌های بعدی) سطح را بشکند.
                //   سند ۶-۱۲: هر عبور شمع، حتی یک تیک، تایید را باطل می‌کند — شامل کندل بعد
                //   از تایید هم می‌شود (قبلاً این کندل از بررسی ابطال معاف بود).
                val broken = if (cfg.midInvalidClose) {
                    if (s.dir == 1) mc.c < s.midRef else mc.c > s.midRef
                } else {
                    if (s.dir == 1) mc.l < s.midRef else mc.h > s.midRef
                }
                if (broken) {
                    val old = s.midRef
                    //  ابطال میانی تایید را باطل می‌کند. اگر ستاپ مسلح شده باشد (stage=6) این
                    //  شاخه اصلاً اجرا نمی‌شود (بالای حلقه) — سند ۴-۸ لغو سفارش مسلح را فقط با
                    //  شکست ناحیهٔ ساختار، تغییر روند، ابطال/مصرف LV و انقضا می‌پذیرد.
                    closeT1Box(s, ZoneStatus.DELETED, curBi, curT)
                    closeT2Box(s, ZoneStatus.DELETED, curBi, curT)
                    s.midRef = Double.NaN; s.midBi = -1; s.midT = 0L; s.markBi = -1
                    clearSetupFib(s)
                    s.lvBi = -1; s.lvT = 0L; s.lvConfBi = -1; s.lvConfT = 0L; s.lvH = Double.NaN; s.lvL = Double.NaN
                    s.hvBi = -1; s.hvT = 0L; s.hvConfBi = -1; s.hvConfT = 0L; s.hvScanBi = -1; s.runMax = Double.NaN
                    s.lvTouched = false; s.lvUsed = false; s.lvReEntered = false; s.lvUses = 0
                    s.orderId = -1
                    addMarker(mc.bi, old, 10, "تایید میانی باطل شد")
                    lg("ENGINE", "تایید میانی باطل شد · ستاپ #${s.id}", "سطح قبلی=${f2(old)} bar=${mc.bi}")
                    // §18-8: همین کندل میانی که تایید را باطل کرد، به شرط معتبر بودن ناحیهٔ ساختار،
                    // خودش تایید میانی جدید است و مارک می‌شود.
                    if (s.zUsed) {
                        // §18-14: ناحیهٔ ساختار مصرف شده بود ⇒ با ابطال تایید میانی ستاپ هم باطل می‌شود
                        s.zGone = true
                        s.stage = 8
                        markStructureZoneStatus(s, ZoneStatus.CONSUMED, curBi, curT)
                        s.note = "ابطال تایید میانی پس از مصرف ناحیهٔ ساختار — ستاپ باطل"
                        events.add("[${s.id}] تایید میانی باطل شد و ناحیهٔ ساختار مصرف شده بود — ستاپ باطل")
                        lg("ENGINE", "ستاپ #${s.id} باطل شد (ابطال میانی پس از مصرف ناحیهٔ ساختار، §18-14)", "bar=${mc.bi}")
                    } else if (!s.zGone) {
                        s.midRef = if (s.dir == 1) mc.l else mc.h
                        s.midBi = mc.bi
                        s.midT = mc.t
                        s.midConfirmBi = mcCloseBi
                        s.touchMidStart = mc.t
                        s.stage = 2
                        cnt.mid++
                        updateSetupFib(s)
                        lg("ENGINE", "تایید میانی انجام شد (کندل باطل‌کننده، §18-8) · ستاپ #${s.id}",
                            if (s.dir == 1) "کف تایید=${f2(s.midRef)} bar=${mc.bi}" else "سقف تایید=${f2(s.midRef)} bar=${mc.bi}")
                        addMarker(mc.bi, s.midRef, 3, if (s.dir == 1) "کف تایید میانی" else "سقف تایید میانی")
                        if (cfg.tf1 < cfg.tfM) {
                            scanLvFromMidExtreme(s, mc, mcCloseBi, s.dir == 1)
                        }
                    } else {
                        s.touchMidStart = aggM.cur?.t ?: mc.t
                        s.stage = 1
                    }
                } else if (s.markBi < 0) {
                    // ◆ مارک کردن کندل بعد از تایید (فقط نشانه) — فقط اگر ابطال اتفاق نیفتاده باشد
                    s.markH = mc.h; s.markL = mc.l; s.markBi = mc.bi
                    if (s.stage == 2) s.stage = 3
                }
            }
        }
    }

    /**
     * §18-16 و §18-23: اسکن کندل‌های بستهٔ تریگر۱ از کف/سقف تایید میانی تا جایی که قیمت هست ([mcCloseBi])
     * برای یافتن اولین کندل بسته با حجم کمتر از کندل بستهٔ قبلی که باطل نشده است.
     * در صورت پیدا نشدن، اسکن برای ناحیهٔ جدید روی کندل‌های بعدی ادامه می‌یابد.
     */
    private fun scanLvFromMidExtreme(s: Setup, mc: Candle, mcCloseBi: Int, bull: Boolean) {
        if (t1History.isEmpty()) return
        var extT = mc.t
        var bestPrice = if (bull) Double.POSITIVE_INFINITY else Double.NEGATIVE_INFINITY
        for (idx in t2History.indices) {
            val b = t2History[idx].bar
            if (b.bi < mc.bi) continue
            if (b.bi > mcCloseBi) break
            if (bull) {
                if (b.l < bestPrice) { bestPrice = b.l; extT = b.t }
            } else {
                if (b.h > bestPrice) { bestPrice = b.h; extT = b.t }
            }
        }
        val extBucketT = Tf.bucket(cfg.tf1, extT)
        val allFromExt = t1History.filter { it.bar.t >= extBucketT && it.closeBi <= mcCloseBi }
        if (allFromExt.isEmpty()) return

        fun evaluateCandidate(cIdx: Int): Boolean {
            val cand = allFromExt[cIdx]
            if (cand.prevVol.isNaN() || cand.bar.v >= cand.prevVol) return false
            var confirmed = false
            var confBi = -1
            var confT = 0L
            var uses = 0
            var touched = false
            for (j in (cIdx + 1) until allFromExt.size) {
                val r = allFromExt[j]
                val q = r.bar
                if (!confirmed) {
                    val viol = if (bull) q.l < cand.bar.l else q.h > cand.bar.h
                    val conf = if (bull) q.c > cand.bar.h else q.c < cand.bar.l
                    if (viol) return false
                    if (conf) {
                        confirmed = true
                        confBi = r.closeBi
                        confT = q.t
                    }
                } else {
                    val violAfter = if (bull) q.l < cand.bar.l else q.h > cand.bar.h
                    if (violAfter) return false
                    val inside = q.l <= cand.bar.h && q.h >= cand.bar.l
                    if (uses >= 2 && inside) return false
                    if (inside && !touched) touched = true
                    if (touched && (if (bull) q.c > cand.bar.h else q.c < cand.bar.l)) {
                        uses++
                        touched = false
                    }
                }
            }
            if (confirmed) {
                val lastT1CloseBi = allFromExt.lastOrNull()?.closeBi ?: confBi
                for (idx in t2History.indices) {
                    val b = t2History[idx].bar
                    if (b.bi <= confBi) continue
                    if (b.bi > mcCloseBi) break
                    val violBar = if (bull) b.l < cand.bar.l else b.h > cand.bar.h
                    if (violBar) return false
                    if (b.bi > lastT1CloseBi && uses >= 2 && b.l <= cand.bar.h && b.h >= cand.bar.l) return false
                }
            }
            s.lvBi = cand.bar.bi
            s.lvT = cand.bar.t
            s.lvH = cand.bar.h
            s.lvL = cand.bar.l
            s.lvUses = uses
            s.lvTouched = touched
            s.lvUsed = false
            if (confirmed) {
                s.lvConfBi = confBi
                s.lvConfT = confT
                s.stage = 4
                cnt.lv++
                closeT1Box(s, ZoneStatus.DELETED, curBi, curT)
                val zb1 = ZoneBox(zoneSeq++, s.dir, 1, s.lvH, s.lvL, s.lvBi, s.lvT, 0, 0, confBi, curT, ZoneStatus.ACTIVE)
                t1Zones.add(zb1)
                s.lvZoneId = zb1.id
                addMarker(s.lvBi, if (bull) s.lvH else s.lvL, 5, "ناحیهٔ ولوم کم ✔ (تریگر۱)")
                lg("ENGINE", "ناحیهٔ ولوم کم تایید شد (§18-16) · ستاپ #${s.id}",
                    "box=${f2(s.lvL)}-${f2(s.lvH)} bar=${s.lvBi} مرحله=${s.stage}")
                alarm(AlertKind.LV, "ناحیهٔ ولوم کم تایید شد",
                    "ستاپ #${s.id} · باکس ${f2(s.lvL)} تا ${f2(s.lvH)} · منتظر کندل ولوم زیاد (تریگر ۲)")
                scanHvFromLvExtreme(s, mcCloseBi, bull)
            } else {
                s.lvConfBi = -1
                s.lvConfT = 0L
                s.stage = 3
                addMarker(s.lvBi, if (bull) s.lvL else s.lvH, 4, "کندل ولوم کم (تریگر۱)")
                lg("ENGINE", "کندل ولوم کم مارک شد (§18-16) · ستاپ #${s.id}",
                    "vol=${f2(cand.bar.v)} < prev=${f2(cand.prevVol)} hi=${f2(s.lvH)} lo=${f2(s.lvL)} bar=${s.lvBi}")
            }
            return true
        }

        // ابتدا کندل‌های پس از باکتِ کف/سقف (یا همان باکت اگر هم‌جهت با حرکت برگشتی بسته شده باشد)
        for (i in allFromExt.indices) {
            val r = allFromExt[i]
            val dirMatch = if (bull) r.bar.c >= r.bar.o else r.bar.c <= r.bar.o
            if (r.bar.t > extBucketT || dirMatch) {
                if (evaluateCandidate(i)) return
            }
        }
        for (i in allFromExt.indices) {
            if (allFromExt[i].bar.t == extBucketT) {
                if (evaluateCandidate(i)) return
            }
        }
    }

    /**
     * §18-17: اسکن کندل‌های بستهٔ تریگر۲ از کف/سقف ناحیهٔ تاییدشدهٔ LV تا جایی که قیمت هست ([upToBi])
     * برای یافتن اولین کندل بسته با حجم بیشتر از کندل بستهٔ قبلی که باطل نشده و لمس نشده است.
     * در صورت پیدا شدن (و تایید شدن)، مسلح می‌شود؛ در صورت پیدا نشدن، اسکن برای ناحیهٔ جدید ادامه می‌یابد.
     */
    private fun scanHvFromLvExtreme(s: Setup, upToBi: Int, bull: Boolean) {
        // §۴-۸ سند (پیش‌فرض): «اسکن کندل حجم زیاد بعد از تایید شدن باکس حجم کم، **از اولین برگشت و
        // برخورد یا ورود قیمت به باکس حجم کم** شروع می‌شود». پیش‌تر این تابع `lvReEntered` را
        // همان لحظهٔ تایید LV روی true می‌گذاشت؛ نتیجه: اسکن از لحظهٔ تایید آغاز می‌شد، «اولین
        // برگشت» هرگز سنجیده نمی‌شد و کلید «اسکن ولوم زیاد از اولین برگشت به باکس» در تنظیمات
        // هیچ اثری نداشت. حالا در حالت پیش‌فرض (hvScanFirstTouch=true) این تابع کاری نمی‌کند و
        // شروع اسکن فقط با بازگشت قیمت به باکس LV در [trigger2Engine] رقم می‌خورد.
        if (cfg.hvScanFirstTouch) return
        s.lvReEntered = true
        s.hvScanBi = curBi
        if (t2History.isEmpty()) return
        if (cfg.tf2 >= cfg.tf1 && (s.lvConfBi < 0 || upToBi <= s.lvConfBi)) return

        val lvRec = t1History.lastOrNull { it.bar.t == s.lvT && it.bar.bi == s.lvBi }
            ?: t1History.lastOrNull { it.bar.t == s.lvT }
        val lvExtBi = if (cfg.tf2 >= cfg.tf1) {
            s.lvConfBi + 1
        } else if (lvRec != null) {
            if (bull) lvRec.lowBi else lvRec.highBi
        } else {
            var eb = s.lvBi
            var best = if (bull) Double.POSITIVE_INFINITY else Double.NEGATIVE_INFINITY
            for (idx in t2History.indices) {
                val b = t2History[idx].bar
                if (b.bi < s.lvBi) continue
                if (b.bi > upToBi) break
                if (Tf.bucket(cfg.tf1, b.t) != s.lvT) continue
                if (bull) {
                    if (b.l < best) { best = b.l; eb = b.bi }
                } else {
                    if (b.h > best) { best = b.h; eb = b.bi }
                }
            }
            eb
        }

        val bars = t2History.filter { it.bar.bi >= lvExtBi && it.bar.bi <= upToBi }
        if (bars.isEmpty()) return
        val expRef = if (cfg.strategy == Strategy.NO_TREND) Double.NaN else if (bull) HH else LL

        fun evaluateHvCandidate(cIdx: Int): Boolean {
            val cand = bars[cIdx]
            if (cand.prevVol.isNaN() || cand.bar.v <= cand.prevVol) return false
            var confirmed = false
            var confCandle: Candle? = null
            var confBi = -1
            val eLvl = cfg.entryLevel(bull, cand.bar.h, cand.bar.l)
            for (j in (cIdx + 1) until bars.size) {
                val b = bars[j].bar
                if (!confirmed) {
                    val viol = if (bull) b.l < cand.bar.l else b.h > cand.bar.h
                    val conf = if (bull) b.c > cand.bar.h else b.c < cand.bar.l
                    if (viol) return false
                    if (conf) {
                        confirmed = true
                        confCandle = b
                        confBi = b.bi
                    }
                } else {
                    val hvViol = if (bull) b.l < cand.bar.l else b.h > cand.bar.h
                    val touched = if (bull) b.l <= eLvl else b.h >= eLvl
                    val priceExp = priceExpiryCrossed(bull, b.o, b.c, expRef)
                    val timeExp = fullStructureElapsed(confBi, b.bi, cfg.tfS, cfg.tf2)
                    if (hvViol || touched || priceExp || timeExp) return false
                }
            }
            s.hvBi = cand.bar.bi
            s.hvT = cand.bar.t
            s.hvH = cand.bar.h
            s.hvL = cand.bar.l
            s.runMax = cand.bar.v
            s.stage = 5
            addMarker(s.hvBi, if (bull) s.hvL else s.hvH, 6, "کندل ولوم زیاد (مارک)")
            lg("ENGINE", "کندل ولوم زیاد مارک شد (§18-17) · ستاپ #${s.id}",
                "vol=${f2(cand.bar.v)} hi=${f2(s.hvH)} lo=${f2(s.hvL)} bar=${s.hvBi} مرحله=5")
            if (confirmed && confCandle != null) {
                val savedBi = curBi
                val savedT = curT
                curBi = upToBi
                curT = confCandle.t
                val orderBar = Candle(upToBi, confCandle.t, confCandle.o, confCandle.h, confCandle.l, confCandle.c, confCandle.v)
                armSetup(s, orderBar, bull)
                curBi = savedBi
                curT = savedT
            }
            return true
        }

        for (i in bars.indices) {
            val r = bars[i]
            val dirMatch = if (bull) r.bar.c >= r.bar.o else r.bar.c <= r.bar.o
            if (r.bar.bi > lvExtBi || dirMatch) {
                if (evaluateHvCandidate(i)) return
            }
        }
        for (i in bars.indices) {
            if (bars[i].bar.bi == lvExtBi) {
                if (evaluateHvCandidate(i)) return
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  ⑫ موتور تریگر ۱ · ناحیهٔ ولوم کم (یک‌بارمصرف)
    // ═══════════════════════════════════════════════════════════════════════════
    private fun trigger1Engine(q1: Candle) {
        for (s in setups) {
            if (s.stage < 2 || s.stage > 7 || s.midRef.isNaN() || q1.bi <= s.midConfirmBi) continue
            val bullL = s.dir == 1

            // هنگام معامله هم چرخهٔ یک‌بارمصرف تریگر۱ را دنبال می‌کنیم، فقط برای
            // جلوگیری از مسلح‌مجدد اگر باکس در این فاصله مصرف شود یا نقض شود؛ معامله را دست نمی‌زنیم.
            if (s.stage == 7) {
                if (s.lvBi >= 0 && !s.lvUsed) {
                    val lvViol = s.lvConfBi >= 0 && (if (bullL) q1.l < s.lvL else q1.h > s.lvH)
                    if (lvViol) {
                        s.lvUsed = true
                        s.rearmAllowed = false
                        closeT1Box(s, ZoneStatus.DELETED, curBi, curT)
                    } else {
                        val inside = q1.l <= s.lvH && q1.h >= s.lvL
                        if (s.lvUses >= 2 && inside) {
                            // §4-7: لمس سوم = ابطال باکس؛ §18-11: معاملهٔ باز تغییری نمی‌کند، فقط مسلح‌مجدد بسته می‌شود.
                            s.lvUsed = true
                            s.rearmAllowed = false
                            closeT1Box(s, ZoneStatus.DELETED, curBi, curT)
                            addMarker(q1.bi, if (bullL) s.lvH else s.lvL, 11, "لمس سوم باکس ولوم کم → باطل ✖")
                            lg("ZONE", "باکس تریگر۱ هنگام معامله برای بار سوم لمس شد → باطل؛ مسلح‌مجدد بسته شد · ستاپ #${s.id}",
                                "box=${f2(s.lvL)}-${f2(s.lvH)} bar=${q1.bi}")
                        } else {
                            if (inside && !s.lvTouched) s.lvTouched = true
                            if (s.lvTouched) {
                                val left = if (bullL) q1.c > s.lvH else q1.c < s.lvL
                                if (left) {
                                    s.lvUses++
                                    s.lvTouched = false
                                    addMarker(q1.bi, if (bullL) s.lvH else s.lvL, 11, "مصرف ${s.lvUses}/۲ باکس ولوم کم")
                                    lg("ZONE", "باکس ولوم کم هنگام معامله ${s.lvUses} بار مصرف شد · ستاپ #${s.id}",
                                        "box=${f2(s.lvL)}-${f2(s.lvH)} bar=${q1.bi}")
                                }
                            }
                        }
                    }
                }
                continue
            }

            var lvConfirmedNow = false

            // ── مارک / تایید کندل ولوم کم (فقط پیش از تایید اولیهٔ باکس) ──
            if (!s.lvUsed && s.lvConfBi < 0 && s.stage <= 4) {
                if (s.lvBi < 0) {
                    if (prevT1Vol.isNaN() || q1.v < prevT1Vol) {
                        s.lvBi = q1.bi; s.lvT = q1.t; s.lvH = q1.h; s.lvL = q1.l
                        addMarker(q1.bi, if (bullL) q1.l else q1.h, 4, "کندل ولوم کم (تریگر۱)")
                        lg("ENGINE", "کندل ولوم کم مارک شد · ستاپ #${s.id}",
                            "vol=${f2(q1.v)} < prev=${f2(prevT1Vol)} hi=${f2(q1.h)} lo=${f2(q1.l)} bar=${q1.bi}")
                    }
                } else {
                    val violL = if (bullL) q1.l < s.lvL else q1.h > s.lvH
                    val confL = if (bullL) q1.c > s.lvH else q1.c < s.lvL
                    if (violL) {
                        s.lvBi = -1; s.lvT = 0L; s.lvH = Double.NaN; s.lvL = Double.NaN
                    } else if (confL) {
                        val lvCloseBi = maxOf(q1.bi, curBi - 1)
                        s.lvConfBi = lvCloseBi; s.lvConfT = curT
                        lvConfirmedNow = true
                        s.stage = 4
                        cnt.lv++
                        s.lvTouched = false
                        s.lvReEntered = false
                        s.hvScanBi = -1
                        s.hvBi = -1; s.hvT = 0L
                        if (!cfg.hvScanFirstTouch) s.hvScanBi = curBi
                        closeT1Box(s, ZoneStatus.DELETED, curBi, curT)
                        val zb1 = ZoneBox(zoneSeq++, s.dir, 1, s.lvH, s.lvL, s.lvBi, s.lvT, 0, 0, lvCloseBi, curT, ZoneStatus.ACTIVE)
                        t1Zones.add(zb1)
                        s.lvZoneId = zb1.id
                        addMarker(q1.bi, if (bullL) s.lvH else s.lvL, 5, "ناحیهٔ ولوم کم ✔ (تریگر۱)")
                        lg("ENGINE", "ناحیهٔ ولوم کم تایید شد · ستاپ #${s.id}",
                            "box=${f2(s.lvL)}-${f2(s.lvH)} bar=${q1.bi} مرحله=${s.stage}")
                        alarm(AlertKind.LV, "ناحیهٔ ولوم کم تایید شد",
                            "ستاپ #${s.id} · باکس ${f2(s.lvL)} تا ${f2(s.lvH)} · منتظر کندل ولوم زیاد (تریگر ۲)")
                        scanHvFromLvExtreme(s, lvCloseBi, bullL)
                    }
                }
            }

            // ── ابطال باکس ولوم کم پس از تایید (§4-7 سند نهایی) ──
            if (!lvConfirmedNow && s.lvBi >= 0 && s.lvConfBi >= 0 && !s.lvUsed) {
                val lvViolAfter = if (bullL) q1.l < s.lvL else q1.h > s.lvH
                if (lvViolAfter) {
                    if (s.stage in 4..5) {
                        closeT1Box(s, ZoneStatus.DELETED, curBi, curT)
                        closeT2Box(s, ZoneStatus.DELETED, curBi, curT)
                        s.lvBi = -1; s.lvT = 0L; s.lvConfBi = -1; s.lvConfT = 0L; s.lvH = Double.NaN; s.lvL = Double.NaN
                        s.lvTouched = false; s.lvReEntered = false; s.lvUses = 0
                        s.hvBi = -1; s.hvT = 0L; s.hvConfBi = -1; s.hvConfT = 0L; s.hvScanBi = -1; s.runMax = Double.NaN
                        s.stage = 3
                        s.note = "نقض ناحیهٔ ولوم کم (تریگر۱)"
                        addMarker(q1.bi, if (bullL) q1.l else q1.h, 10, "ابطال ناحیهٔ ولوم کم")
                        lg("ENGINE", "ناحیهٔ ولوم کم با عبور از سمت مخالف باطل شد · ستاپ #${s.id}",
                            "bar=${q1.bi}")
                        continue
                    } else if (s.stage == 6) {
                        // §4-8 و §18-10: بعد از مسلح شدن، ابطال LV سفارش مسلح‌شده را لغو می‌کند و چون برای هر LV فقط یک سفارش مجاز است، ستاپ غیرفعال می‌شود.
                        cancelArmedForLv(s, "ابطال ناحیهٔ ولوم کم پس از مسلح‌شدن")
                        s.lvUsed = true
                        if (s.touchT > 0L) markStructureZoneStatus(s, ZoneStatus.CONSUMED, curBi, curT)
                        s.stage = 8
                        s.note = "نقض ناحیهٔ ولوم کم (تریگر۱) پس از مسلح‌شدن — سفارش لغو و ستاپ غیرفعال شد"
                        addMarker(q1.bi, if (bullL) q1.l else q1.h, 10, "ابطال ناحیهٔ ولوم کم · سفارش لغو")
                        continue
                    }
                }
            }

            // ── قاعدهٔ دو بارمصرفی باکس ولوم کم (§4-7 سند) ──
            // کندل تاییدکننده خودش «برگشت» محسوب نمی‌شود؛ یک کندل تریگر۱ بعدتر لازم است.
            // هر «مصرف» = لمس/نفوذ به باکس و سپس کلوز آن‌سوی لبهٔ نزدیک (خرید: بالای سقف، فروش: زیر کف).
            // تا دو مصرف، باکس می‌ماند و ستاپ فعال است؛ لمس سوم (حتی یک تیک) = ابطال باکس و پاک شدن خطوط.
            if (!lvConfirmedNow && s.lvBi >= 0 && !s.lvUsed && s.stage in 4..6) {
                val inside = q1.l <= s.lvH && q1.h >= s.lvL
                if (s.lvUses >= 2 && inside) {
                    s.lvUsed = true
                    s.rearmAllowed = false
                    closeT1Box(s, ZoneStatus.DELETED, curBi, curT)
                    addMarker(q1.bi, if (bullL) s.lvH else s.lvL, 11, "لمس سوم باکس ولوم کم → باطل ✖")
                    lg("ZONE", "باکس ولوم کم پس از دو مصرف برای بار سوم لمس شد → باطل · ستاپ #${s.id}",
                        "box=${f2(s.lvL)}-${f2(s.lvH)} bar=${q1.bi}")
                    if (s.stage == 6) {
                        // §4-7 و §4-8 و §18-10: مصرف باکس ولوم کم سفارش معلق را می‌کُشد و ستاپ مسلح‌شده معتبر نیست.
                        cancelArmedForLv(s, "مصرف باکس ولوم کم پس از مسلح‌شدن")
                        if (s.touchT > 0L) markStructureZoneStatus(s, ZoneStatus.CONSUMED, curBi, curT)
                        s.stage = 8
                        s.note = "باکس ولوم کم مصرف شد (لمس سوم پس از مسلح‌شدن) — ستاپ غیرفعال"
                        events.add("[${s.id}] باکس ولوم کم بعد از مسلح مصرف شد — سفارش معلق لغو و ستاپ غیرفعال شد")
                    } else {
                        // §4-7: اگر ستاپ هنوز مسلح نشده باشد، وقتی باکس مصرف شود ستاپ تمام می‌شود و تا زمان اعتبار
                        // ناحیهٔ ساختار و تایید میانی، اگر محدودهٔ LV جدیدی تایید شد ستاپ دوباره فعال می‌شود.
                        closeT2Box(s, ZoneStatus.DELETED, curBi, curT)
                        broker?.onSetupInvalidated(s, curT, curBi)
                        s.lvBi = -1; s.lvT = 0L; s.lvConfBi = -1; s.lvConfT = 0L; s.lvH = Double.NaN; s.lvL = Double.NaN
                        s.lvTouched = false; s.lvUsed = false; s.lvReEntered = false; s.lvUses = 0
                        s.hvBi = -1; s.hvT = 0L; s.hvConfBi = -1; s.hvConfT = 0L; s.hvScanBi = -1; s.runMax = Double.NaN
                        s.prevEntry = Double.NaN
                        s.stage = if (!s.zGone && !s.midRef.isNaN()) 3 else 8
                        s.note = "باکس ولوم کم مصرف شد (لمس سوم) — جست‌وجوی LV جدید"
                        events.add("[${s.id}] باکس ولوم کم قبل از مسلح مصرف شد — جست‌وجوی LV جدید")
                    }
                    continue
                }
                if (inside && !s.lvTouched) {
                    // §۱۸-۷: این لمس فقط برای «مصرف» باکس است؛ شروع اسکن HV روی تریگر۲
                    // سنجیده می‌شود و شاخصش در همان موتور (③-الف) ثبت می‌شود.
                    s.lvTouched = true
                }
                if (s.lvTouched) {
                    // سند: خروج با کلوز آن‌سوی لبهٔ نزدیک باکس سنجیده می‌شود (لمس و کلوز می‌توانند در یک کندل باشند).
                    val left = if (bullL) q1.c > s.lvH else q1.c < s.lvL
                    if (left) {
                        s.lvUses++
                        s.lvTouched = false
                        addMarker(q1.bi, if (bullL) s.lvH else s.lvL, 11, "مصرف ${s.lvUses}/۲ باکس ولوم کم")
                        lg("ZONE", "باکس ولوم کم ${s.lvUses} بار از سهمیهٔ خود را مصرف کرد · ستاپ #${s.id}",
                            "box=${f2(s.lvL)}-${f2(s.lvH)} bar=${q1.bi}")
                    }
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  ⑬ موتور تریگر ۲ (چارت) : ناحیهٔ ساختار · ناحیهٔ ولوم زیاد · ورود · مدیریت
    // ═══════════════════════════════════════════════════════════════════════════
    private fun trigger2Engine(cd: Candle) {
        val open = cd.o; val low = cd.l; val high = cd.h; val close = cd.c; val volume = cd.v
        for (s in setups) {
            val bull = s.dir == 1

            // ④ انقضا / ورود بعد از مسلح‌شدن (§4-8 و §4-9 سند نهایی):
            //    فقط و فقط یک سفارش برای هر LV امکان‌پذیر است چه سفارش پر شود چه فعال نشود چه باطل شود.
            if (s.stage == 6 && curBi > s.hvConfBi) {
                if (s.priceExpiryRef.isNaN()) {
                    s.priceExpiryRef = if (bull) HH else LL
                }
                val openBreak = !s.priceExpiryRef.isNaN() &&
                        (if (bull) open > s.priceExpiryRef else open < s.priceExpiryRef)
                val closeBreak = priceExpiryCrossed(bull, open, close, s.priceExpiryRef)
                val nBars = maxOf(1, cfg.tfS / maxOf(1, cfg.tf2))
                val barExpire = s.armBi >= 0 && (curBi - s.armBi >= nBars)
                val entryLvl = entryLevel(s, bull)
                val entryTouched = if (bull) low <= entryLvl else high >= entryLvl
                val hvViol = if (bull) low < s.hvL else high > s.hvH
                val lvThirdTouch = !s.lvUsed && s.lvBi >= 0 && s.lvConfBi >= 0 &&
                        s.lvUses >= 2 && low <= s.lvH && high >= s.lvL

                if (MarketHours.isOrderTimeExit(cd.t)) {
                    // §18-27 سند: رأس ۲۰:۰۰ UTC سفارش معلق لغو می‌شود (دلیل: پایان زمان) تا
                    // شبانه پر نشود؛ این خروج بر پرشدن در همین کندل هم مقدم است.
                    closeT2Box(s, ZoneStatus.DELETED, curBi, curT)
                    closeT1Box(s, ZoneStatus.CONSUMED, curBi, curT)
                    if (s.touchT > 0L) markStructureZoneStatus(s, ZoneStatus.CONSUMED, curBi, curT)
                    s.lvUsed = true
                    s.rearmAllowed = false
                    s.stage = 8
                    s.note = "سفارش منقضی — پایان زمان"
                    events.add("[${s.id}] سفارش رأس ۲۰:۰۰ UTC لغو شد — پایان زمان (§18-27 سند)")
                    broker?.onSetupExpired(s, curT, curBi, "پایان زمان (رأس ۲۰:۰۰ UTC)")
                    s.orderId = -1
                    s.hvBi = -1; s.hvT = 0L; s.hvConfBi = -1; s.hvConfT = 0L; s.runMax = Double.NaN
                    lg("ENGINE", "سفارش معلق رأس ۲۰:۰۰ UTC لغو شد — پایان زمان · ستاپ #${s.id}",
                        "bar=$curBi")
                    continue
                } else if (lvThirdTouch) {
                    // §4-7: در صورتی که برای بار سوم قیمت حتی یک تیک باکس LV را لمس کرد باکس باطل می‌شود، سفارش معلق لغو و ستاپ غیرفعال می‌شود.
                    s.lvUsed = true
                    s.rearmAllowed = false
                    cancelArmedForLv(s, "مصرف باکس ولوم کم (لمس سوم) پس از مسلح‌شدن")
                    if (s.touchT > 0L) markStructureZoneStatus(s, ZoneStatus.CONSUMED, curBi, curT)
                    s.stage = 8
                    s.note = "باکس ولوم کم مصرف شد (لمس سوم پس از مسلح‌شدن) — ستاپ غیرفعال"
                    addMarker(curBi, if (bull) s.lvH else s.lvL, 11, "لمس سوم باکس ولوم کم → باطل ✖")
                    continue
                } else if (barExpire || openBreak || (!entryTouched && closeBreak)) {
                    val expReason = if (barExpire) {
                        "گذشت یک کندل ساختار کامل (${nBars} کندل تریگر۲)"
                    } else if (bull) {
                        "باز/کلوز از بالای HH هنگام مسلح‌شدن (${f2(s.priceExpiryRef)}) عبور کرد"
                    } else {
                        "باز/کلوز از زیر LL هنگام مسلح‌شدن (${f2(s.priceExpiryRef)}) عبور کرد"
                    }
                    closeT2Box(s, ZoneStatus.DELETED, curBi, curT)
                    closeT1Box(s, ZoneStatus.CONSUMED, curBi, curT)
                    if (s.touchT > 0L) markStructureZoneStatus(s, ZoneStatus.CONSUMED, curBi, curT)
                    s.lvUsed = true
                    s.rearmAllowed = false
                    s.stage = 8
                    s.note = "سفارش منقضی"
                    events.add("[${s.id}] سفارش منقضی شد — $expReason")
                    broker?.onSetupExpired(s, curT, curBi, expReason)
                    s.orderId = -1
                    s.hvBi = -1; s.hvT = 0L; s.hvConfBi = -1; s.hvConfT = 0L; s.runMax = Double.NaN
                    lg("ENGINE", "سفارش معلق منقضی شد (فقط یک سفارش برای هر LV مجاز است، §4-8) · ستاپ #${s.id}",
                        "reason=$expReason stage=${s.stage} bar=$curBi")
                    continue
                } else if (entryTouched) {
                    s.entry = entryLvl
                    s.slMidDone = false
                    s.rearmAllowed = false
                    s.stage = 7
                    s.entryBi = curBi   // کندل پرشدن — برای قاعده §4-9
                    s.entryT = curT
                    cnt.entry++
                    lastEntryPx = s.entry; lastSlPx = s.sl; lastTpPx = s.tpx
                    addMarker(curBi, s.entry, 8, (if (bull) "ورود خرید" else "ورود فروش") + " @ " + f2(s.entry))
                    lg("TRADE", "قیمت به ناحیهٔ ولوم زیاد برگشت (شرط ورود) · ستاپ #${s.id}",
                        "ورود=${f2(s.entry)} bar=$curBi")
                } else if (hvViol) {
                    closeT2Box(s, ZoneStatus.DELETED, curBi, curT)
                    closeT1Box(s, ZoneStatus.CONSUMED, curBi, curT)
                    if (s.touchT > 0L) markStructureZoneStatus(s, ZoneStatus.CONSUMED, curBi, curT)
                    s.hvBi = -1; s.hvT = 0L; s.hvConfBi = -1; s.hvConfT = 0L; s.runMax = Double.NaN
                    s.lvUsed = true
                    s.rearmAllowed = false
                    s.stage = 8
                    s.note = "نقض باکس ولوم زیاد پس از مسلح‌شدن — سفارش باطل"
                    broker?.onSetupInvalidated(s, curT, curBi)
                    s.orderId = -1
                    lg("ENGINE", "ناحیهٔ HV پس از مسلح‌شدن نقض شد → سفارش لغو و ستاپ غیرفعال (فقط یک سفارش برای هر LV، §4-8) · ستاپ #${s.id}",
                        "hvL=${f2(s.hvL)} hvH=${f2(s.hvH)} stage=${s.stage} bar=$curBi")
                    continue
                }
            }

            // §4-6: تأیید میانی باید روی هر کندل بستهٔ تریگر۲ بررسی شود؛
            // منتظر بسته‌شدن کندل میانی نمانیم، چون یک عبور کوتاه هم تأیید را باطل می‌کند.
            if (s.stage == 7 && s.rearmAllowed && rearmInvalidatedByBar(s, cd, cfg.midInvalidClose)) {
                s.rearmAllowed = false
                lg("ENGINE", "اعتبار ناحیهٔ ساختار یا تایید میانی هنگام معامله از بین رفت؛ مسلح‌مجدد بسته شد · ستاپ #${s.id}",
                    "zone=${f2(s.zBot)}-${f2(s.zTop)} midRef=${f2(s.midRef)} bar=$curBi")
            }
            if (s.stage in 2..6 && !s.midRef.isNaN() && curBi > s.midConfirmBi) {
                val brokenMid = if (cfg.midInvalidClose) {
                    if (bull) close < s.midRef else close > s.midRef
                } else {
                    if (bull) low < s.midRef else high > s.midRef
                }
                if (brokenMid) {
                    val old = s.midRef
                    if (s.stage == 6) {
                        // §۴-۸ (ویرایش فعلی سند): فهرست لغو سفارش معلق «شکست ناحیهٔ ساختار، تغییر روند،
                        // ابطال/مصرف LV و انقضا» است و **ابطال تایید میانی در آن نیست**. پیش‌تر همین
                        // بررسی در مسیر تریگر۲ (هر کندل M1) سفارش مسلح را لغو می‌کرد، در حالی که مسیر
                        // کندل میانی (middleEngine) سفارش را نگه می‌داشت و فقط سطح تایید را پاک می‌کرد
                        // ⇒ دو رفتار متناقض از یک قاعده. حالا هر دو مسیر یکسان‌اند: سفارش مسلح
                        // می‌ماند، باکس‌های LV/HV دست‌نخورده‌اند و فقط تایید میانی پاک می‌شود.
                        s.midRef = Double.NaN; s.midBi = -1; s.midT = 0L; s.markBi = -1
                        clearSetupFib(s)
                        addMarker(curBi, old, 10, "تایید میانی باطل شد (سفارش مسلح پابرجاست)")
                        lg("ENGINE", "تایید میانی باطل شد — سفارش مسلح لغو نشد (§4-8 سند) · ستاپ #${s.id}",
                            "سطح قبلی=${f2(old)} bar=$curBi · تریگر۲")
                        // ادامهٔ بررسی‌های همین کندل (پر شدن/انقضا/نقض HV) مسدود نمی‌شود.
                    } else {
                        closeT1Box(s, ZoneStatus.DELETED, curBi, curT)
                        closeT2Box(s, ZoneStatus.DELETED, curBi, curT)
                        s.midRef = Double.NaN; s.midBi = -1; s.midT = 0L; s.markBi = -1
                        clearSetupFib(s)
                        if (s.zUsed) {
                            // §18-14: ناحیهٔ ساختار مصرف شده بود ⇒ ستاپ باطل و تا لمس ناحیهٔ بعدی غیرفعال
                            s.zGone = true
                            s.stage = 8
                            s.note = "ابطال تایید میانی پس از مصرف ناحیهٔ ساختار — ستاپ باطل"
                            events.add("[${s.id}] تایید میانی باطل شد و ناحیهٔ ساختار مصرف شده بود — ستاپ باطل")
                        } else {
                            // §18-8: کندل میانی‌ای که تیک شکست در آن رخ داده، هنگام بسته شدن می‌تواند خودش تایید میانی جدید باشد
                            s.touchMidStart = Tf.bucket(cfg.tfM, cd.t)
                            s.stage = 1
                        }
                        s.lvBi = -1; s.lvT = 0L; s.lvConfBi = -1; s.lvConfT = 0L; s.lvH = Double.NaN; s.lvL = Double.NaN
                        s.hvBi = -1; s.hvT = 0L; s.hvConfBi = -1; s.hvConfT = 0L; s.hvScanBi = -1; s.runMax = Double.NaN
                        s.lvTouched = false; s.lvUsed = false; s.lvReEntered = false; s.lvUses = 0
                        s.orderId = -1
                        addMarker(curBi, old, 10, "تایید میانی باطل شد")
                        lg("ENGINE", "تایید میانی باطل شد · ستاپ #${s.id}",
                            "سطح قبلی=${f2(old)} bar=$curBi · تریگر۲")
                        continue
                    }
                }
            }

            // ① برخورد به ناحیهٔ فیکس‌رنج ساختار
            if (s.stage == 0 && !s.zGone && curBi > s.zBi) {
                if (low <= s.zTop && high >= s.zBot) {
                    s.stage = 1
                    cnt.touch++
                    s.touchBi = curBi
                    s.touchT = cd.t
                    s.touchMidStart = aggM.cur?.t ?: cd.t
                    addMarker(curBi, if (bull) s.zBot else s.zTop, 2, "برخورد · منتظر بسته شدن کندل میانی")
                    lg("ENGINE", "برخورد قیمت با ناحیهٔ ساختار · ستاپ #${s.id}",
                        "zone=${f2(s.zBot)}-${f2(s.zTop)} bar=$curBi · منتظر تایید میانی (${Tf.label(cfg.tfM)})")
                }
            }

            // ② پاک شدن / لغو ناحیهٔ ساختار (کلوز از سمت دور)
            if (s.stage < 7 && !s.zGone && curBi > s.zBi) {
                val broken = if (bull) close < s.zBot else close > s.zTop
                if (broken) {
                    //  اگر سفارش معلق داشت (stage=6) آن را هم لغو کن
                    if (s.stage == 6) broker?.onSetupInvalidated(s, curT, curBi)
                    closeT1Box(s, ZoneStatus.DELETED, curBi, curT)
                    closeT2Box(s, ZoneStatus.DELETED, curBi, curT)
                    clearSetupFib(s)
                    s.zGone = true
                    s.stage = 8
                    s.note = "شکست ناحیهٔ ساختار (لغو)"
                    s.orderId = -1
                    events.add("[${s.id}] لغو شد — شکست ناحیهٔ ساختار")
                    addMarker(curBi, close, 10, "لغو · شکست ناحیهٔ ساختار")
                    lg("ZONE", "ناحیهٔ ساختار شکست (لغو ستاپ #${s.id})",
                        "zone=${f2(s.zBot)}-${f2(s.zTop)} close=${f2(close)} bar=$curBi")
                    alarm(AlertKind.ZONE_DEAD, "ناحیهٔ ساختار شکست",
                        "ستاپ #${s.id} لغو شد · کلوز ${f2(close)} خارج از ناحیهٔ ${f2(s.zBot)}-${f2(s.zTop)}")
                }
            }

            // ②-ب ابطال باکس ولوم کم پس از تایید با عبور ۱ تیک از سمت مخالف یا لمس سوم (§4-7 سند نهایی)
            if (!s.lvUsed && s.lvBi >= 0 && s.lvConfBi >= 0 && curBi > s.lvConfBi) {
                val lvViol = if (bull) low < s.lvL else high > s.lvH
                val lvThirdTouch = !lvViol && s.lvUses >= 2 && low <= s.lvH && high >= s.lvL
                if (lvViol || lvThirdTouch) {
                    if (s.stage in 4..5) {
                        closeT1Box(s, ZoneStatus.DELETED, curBi, curT)
                        closeT2Box(s, ZoneStatus.DELETED, curBi, curT)
                        broker?.onSetupInvalidated(s, curT, curBi)
                        s.lvBi = -1; s.lvT = 0L; s.lvConfBi = -1; s.lvConfT = 0L; s.lvH = Double.NaN; s.lvL = Double.NaN
                        s.lvTouched = false; s.lvUsed = false; s.lvReEntered = false; s.lvUses = 0
                        s.hvBi = -1; s.hvT = 0L; s.hvConfBi = -1; s.hvConfT = 0L; s.hvScanBi = -1; s.runMax = Double.NaN
                        s.prevEntry = Double.NaN
                        s.stage = if (!s.zGone && !s.midRef.isNaN()) 3 else 8
                        s.note = if (lvThirdTouch) "باکس ولوم کم مصرف شد (لمس سوم) — جست‌وجوی LV جدید" else "نقض ناحیهٔ ولوم کم (تریگر۱)"
                        addMarker(curBi, if (bull) low else high, 10, if (lvThirdTouch) "لمس سوم باکس ولوم کم → باطل ✖" else "ابطال ناحیهٔ ولوم کم")
                        lg("ENGINE", "ناحیهٔ ولوم کم باطل شد · ستاپ #${s.id}", "bar=$curBi")
                    } else if (s.stage == 6) {
                        // §4-7، §4-8 و §18-10: ابطال/مصرف LV پس از مسلح‌شدن ⇒ لغو سفارش معلق و غیرفعال‌شدن ستاپ
                        cancelArmedForLv(s, if (lvThirdTouch) "مصرف باکس ولوم کم (لمس سوم) پس از مسلح‌شدن" else "ابطال ناحیهٔ ولوم کم پس از مسلح‌شدن")
                        s.lvUsed = true
                        if (s.touchT > 0L) markStructureZoneStatus(s, ZoneStatus.CONSUMED, curBi, curT)
                        s.stage = 8
                        s.note = "نقض/مصرف ناحیهٔ ولوم کم پس از مسلح‌شدن — سفارش لغو و ستاپ غیرفعال شد"
                        addMarker(curBi, if (bull) low else high, 10, "ابطال ناحیهٔ ولوم کم · سفارش لغو")
                        lg("ENGINE", "ناحیهٔ ولوم کم پس از مسلح‌شدن باطل شد → سفارش لغو و ستاپ غیرفعال · ستاپ #${s.id}", "bar=$curBi")
                        continue
                    } else if (s.stage == 7) {
                        // §18-11: معاملهٔ باز تغییری نمی‌کند؛ فقط مسلح‌مجدد بسته می‌شود.
                        s.lvUsed = true
                        s.rearmAllowed = false
                        closeT1Box(s, ZoneStatus.DELETED, curBi, curT)
                    }
                }
            }

            // ③-الف بازگشت قیمت به باکس ولوم کم ← شروع اسکن ولوم زیاد
            if (s.stage == 4 && !s.zGone && s.lvBi >= 0 && !s.lvReEntered) {
                if (low <= s.lvH && high >= s.lvL) {
                    s.lvReEntered = true
                    if (s.hvScanBi < 0) s.hvScanBi = curBi
                    s.runMax = volume
                    addMarker(curBi, if (bull) s.lvH else s.lvL, 5, "بازگشت به باکس ولوم کم · اسکن ولوم زیاد")
                    lg("ENGINE", "بازگشت به باکس ولوم کم → اسکن ناحیهٔ ولوم زیاد فعال شد · ستاپ #${s.id}",
                        "box=${f2(s.lvL)}-${f2(s.lvH)} bar=$curBi vol=${f2(volume)}")
                }
            }

            // ③-ب ناحیهٔ ولوم زیاد (تریگر ۲)
            // §4-8 سند نهایی: کندل تایید ناحیه پرحجم صرفاً باید بالای سقف (خرید) یا زیر کف (فروش)
            // کندل پرحجم بسته شود و شرط افزایش مجدد حجم روی کندل تایید وجود ندارد.
            // پیش‌فرض سند: از اولین برگشت/برخورد قیمت به باکس LV. اگر گزینه خاموش باشد،
            // اسکن از «لحظهٔ تایید ناحیهٔ ولوم کم» (کندل بعد از تایید) شروع می‌شود.
            val scanReady = s.lvReEntered || (!cfg.hvScanFirstTouch && curBi > s.lvConfBi)
            if (s.stage in 4..5 && s.lvBi >= 0 && scanReady) {
                val scanOK = if (s.hvScanBi < 0) true else curBi >= s.hvScanBi
                if (scanOK) {
                    val marked = s.hvBi >= 0
                    val viol = marked && (if (bull) low < s.hvL else high > s.hvH)
                    val conf = marked && (if (bull) close > s.hvH else close < s.hvL)
                    if (viol) {
                        s.hvBi = -1; s.hvT = 0L; s.runMax = volume; s.stage = 4
                        lg("ENGINE", "کندل ولوم زیاد نقض شد → جست‌وجوی کندل جدید · ستاپ #${s.id}",
                            "hvH=${f2(s.hvH)} hvL=${f2(s.hvL)} bar=$curBi")
                    } else if (conf) {
                        armSetup(s, cd, bull)
                    } else {
                        val markNow = if (cfg.hvMarkFirstIncrease) {
                            s.hvBi < 0 && volume > (prevChartVol.takeIf { !it.isNaN() } ?: Double.NEGATIVE_INFINITY)
                        } else {
                            volume > (s.runMax.takeIf { !it.isNaN() } ?: Double.NEGATIVE_INFINITY)
                        }
                        if (markNow) {
                            s.hvBi = curBi; s.hvT = curT; s.hvH = high; s.hvL = low
                            s.runMax = volume; s.stage = 5
                            addMarker(curBi, if (bull) low else high, 6, "کندل ولوم زیاد (مارک)")
                            lg("ENGINE", "کندل ولوم زیاد مارک شد · ستاپ #${s.id}",
                                "vol=${f2(volume)} hi=${f2(high)} lo=${f2(low)} bar=$curBi مرحله=5")
                        }
                    }
                }
            }

            // ⑤ مدیریت معامله : حدضرر / سر‌به‌سر / حدسود (بررسی موتور)
            //   ترتیب محافظه‌کارانه، مطابق §۴-۹ و PaperBroker.manageBar:
            //   حد ضرر → حد سود نهایی → حد سود اول → حد سود دوم.
            if (s.stage == 7 && !s.sl.isNaN() && !s.tpx.isNaN()) {
                // سند ۶: حد ضرر/حد سود با «جهت معامله» سنجیده می‌شود (در حالت معکوس برعکس ساختار).
                val tdBull = s.tradeDir == 1
                var done = false
                var px = Double.NaN
                var note = ""
                val entrySameBar = (s.entryBi == curBi)
                // §4-9: پله‌های TP1/TP2 در هر کندلی که سطحشان رد شده ثبت می‌شوند — از جمله
                // کندلی که تا حد سود نهایی رفته است (قیمت برای رسیدن به ۱.۲۷۲ ناچار از
                // ۰.۳۸۲ و ۰.۵ گذشته). پیش‌تر شاخهٔ «حد سود نهایی» پیش از این پله‌ها معامله را
                // می‌بست و TP1/TP2 نه روی چارت مارک می‌شدند و نه در گزارش سفارش‌ها می‌آمدند.
                fun crossedTp(level: Double): Boolean = if (tdBull) high >= level else low <= level
                fun markTpLegs() {
                    // §4-9: اگر در کندل ورود هم حد سود اول (۰.۳۸۲ فیبو) و هم قیمت ورود لمس شود،
                    // ورود ثبت می‌شود، حد سود اول ۰.۵ فیبو و حد سود نهایی ۱.۲۷۲ فیبو محاسبه می‌شود (۵۰٪/۵۰٪)
                    // و حد ضرر بعد از لمس ۰.۵ فیبو به نقطهٔ ورود منتقل می‌شود.
                    if (entrySameBar && !s.tp1.isNaN() && !s.tp2.isNaN() && crossedTp(s.tp1)) {
                        s.tp1 = s.tp2
                        s.tp2 = Double.NaN
                        lg("TRADE", "لمس هم‌زمان ورود و ۰.۳۸۲ فیبو در یک کندل → حد سود اول به ۰.۵ فیبو منتقل شد · ستاپ #${s.id}",
                            "tp1=${f2(s.tp1)} bar=$curBi")
                    }
                    if (!s.be && !s.tp1.isNaN() && crossedTp(s.tp1)) {
                        s.be = true; s.sl = s.entry
                        val tp1Lbl = if (s.tp2.isNaN()) "TP1 (50%)" else "TP1 (38%)"
                        val tp1Fa = if (s.tp2.isNaN()) "TP1 (۵۰٪)" else "TP1 (۳۸٪)"
                        addMarker(curBi, s.entry, 8, "$tp1Lbl ✔ · حدضرر = سر‌به‌سر")
                        lg("TRADE", "$tp1Fa لمس شد → حدضرر به سر‌به‌سر منتقل شد · ستاپ #${s.id}",
                            "tp1=${f2(s.tp1)} bar=$curBi")
                        alarm(AlertKind.TP1, "$tp1Fa لمس شد", "ستاپ #${s.id} · حدضرر به سر‌به‌سر منتقل شد · ${f2(s.tp1)}")
                    }
                    if (s.be && !s.tp2Hit && !s.tp2.isNaN() && crossedTp(s.tp2)) {
                        s.tp2Hit = true
                        addMarker(curBi, s.tp2, 8, "TP2 (50%) ✔")
                        lg("TRADE", "TP2 (۵۰٪) لمس شد · ستاپ #${s.id}", "tp2=${f2(s.tp2)} bar=$curBi")
                        alarm(AlertKind.TP2, "TP2 (۵۰٪) لمس شد", "ستاپ #${s.id} · قیمت ${f2(s.tp2)}")
                    }
                }
                val hitStopE = if (tdBull) low <= s.sl else high >= s.sl
                // §4-9 سند: بلوک «انتقال حد ضرر به سطح oo با کلوز تریگر۲» در ویرایش فعلی سند داخل
                // بلوک «به تعویق افتاده و در کد ایجاد نمی‌شود» است ⇒ در این شبیه‌ساز هم اجرا نمی‌شود.
                // حد ضرر فقط با حد سود اول به نقطهٔ ورود (سر‌به‌سر) می‌رود و پیش از آن ثابت است.
                val hitFinalE = if (tdBull) high >= s.tpx else low <= s.tpx
                if (MarketHours.isOrderTimeExit(cd.t)) {
                    // §18-27 سند: رأس ۲۰:۰۰ UTC معاملهٔ باز با قیمت لحظهٔ ۲۰:۰۰ (بازشدن
                    // همین کندل) بسته و نتیجه محاسبه و ثبت می‌شود — دلیل: پایان زمان.
                    // این خروج بر حد ضرر/حد سودِ داخل همین کندل مقدم است.
                    done = true; px = open; note = "پایان زمان"
                } else if (hitStopE) {
                    val isBe = s.be
                    done = true; px = cfg.exitFill(tdBull, s.sl, isBe); note = if (isBe) "خروج سر‌به‌سر" else "حد ضرر"
                }
                else if (hitFinalE) {
                    markTpLegs()
                    done = true; px = s.tpx; note = "حد سود نهایی (1.272)"
                } else {
                    markTpLegs()
                    val backToEntry = if (tdBull) low <= s.entry else high >= s.entry
                    if (!entrySameBar && s.be && backToEntry) { done = true; px = s.entry; note = "خروج سر‌به‌سر" }
                }
                if (done) {
                    lastResult = note
                    s.note = note
                    addMarker(curBi, px, 9, note + " @ " + f2(px))
                    lg("TRADE", "پایان معامله · ستاپ #${s.id} → $note",
                        "خروج=${f2(px)} bar=$curBi")
                    closeT2Box(s, ZoneStatus.CONSUMED, curBi, curT)
                    // §4-8: فقط و فقط یک سفارش برای هر LV امکان‌پذیر است چه سفارش پر شود چه فعال نشود چه باطل شود.
                    closeT1Box(s, ZoneStatus.CONSUMED, curBi, curT)
                    if (s.touchT > 0L) markStructureZoneStatus(s, ZoneStatus.CONSUMED, curBi, curT)
                    s.stage = 8
                    s.rearmAllowed = false
                    cnt.done++
                }
            }
        }
        // وضعیت کلی برای پنل
        stageFa = setups.minByOrNull { it.stage }?.let { stageFa(it.stage) } ?: "-"
    }

    /** §4-8 سند: سطح سفارش خرید = سقف باکس HV + ۱ تیک بافر؛ سطح سفارش فروش = کف باکس HV − ۱ تیک بافر (همیشه مثبت). */
    fun entryLevel(s: Setup, bull: Boolean): Double = cfg.entryLevel(bull, s.hvH, s.hvL)

    /**
     * §۴-۸ سند: «بعد از باطل شدن [مارک]، جست‌وجو از نو شروع می‌شود.»
     * هر جا مارک HV پاک می‌شود ولی سفارشی مسلح نشده، ستاپ باید به مرحلهٔ «اسکن ولوم زیاد» (۴)
     * برگردد؛ ماندن روی مرحلهٔ ۵ باعث می‌شود چارت خط‌های مارکِ پاک‌شده را نگه دارد و
     * پنل هم «مارک شد» را نشان دهد، در حالی که hvBi منفی است.
     */
    private fun clearHvMark(s: Setup) {
        s.hvBi = -1; s.hvT = 0L; s.runMax = Double.NaN
        s.slBlockedHvBi = -1
        if (s.stage == 5) s.stage = 4
    }

    /** لحظهٔ مسلح شدن (تایید ناحیهٔ ولوم زیاد) → محاسبهٔ SL/TP و ثبت سفارش ورود. */
    private fun armSetup(s: Setup, cd: Candle, bull: Boolean) {
        // §18-26/§18-29/§18-30 سند: سفارش‌گیری و مسلح‌شدن فقط از ۰۱:۳۰ تا ۲۰:۰۰ UTC مجاز است.
        // تأیید HV در خارج از پنجره دور ریخته می‌شود (مارک پاک و اسکن ادامه دارد) ولی ستاپ،
        // نواحی و بقیهٔ قوانین طبق برنامه فعال می‌مانند؛ اولین تأیید تازهٔ HV بعد از ۰۱:۳۰
        // طبق برنامه مسلح می‌شود.
        if (!MarketHours.isOrderWindow(cd.t)) {
            clearHvMark(s)
            cnt.windowBlocked++
            lg("ENGINE", "مسلح‌شدن ستاپ #${s.id} انجام نشد — خارج از پنجرهٔ سفارش‌گیری (۰۱:۳۰ تا ۲۰:۰۰ UTC)؛ ستاپ فعال می‌ماند و اسکن ولوم زیاد ادامه دارد (§18-26 سند)",
                "bar=$curBi")
            return
        }
        // §4-8 سند: سطح سفارش = سقف باکس HV + ۱ تیک بافر (خرید) / کف باکس HV − ۱ تیک بافر (فروش)
        val eRef = entryLevel(s, bull)
        // ── سند ۶ (§۴-۸ جدید): «تریگر ۲ معکوس» ──────────────────────────────────
        // سطح ورود همان لبهٔ باکس حجم زیاد می‌ماند (همان جایی که قیمت برگشت می‌کند)، ولی
        // جهت معامله برعکسِ جهت ساختار است، حد ضرر آینهٔ همان فاصله (R) در آن‌طرف ورود
        // گذاشته می‌شود و حد سود = ضریب k × R (پیش‌فرض کد ۳؛ آزمون عددی: ۳ تا ۵).
        val td = if (cfg.trigger2Fade) -s.dir else s.dir
        val docSl = if (bull) s.hvL - cfg.slBufTicks * cfg.mintick else s.hvH + cfg.slBufTicks * cfg.mintick
        val rDoc = abs(eRef - docSl)
        // §4-8 سند (بازگردانده‌شده): حد ضرر از **ناحیهٔ حجم زیاد** ساخته می‌شود، نه از تایید میانی —
        //   خرید: کف ناحیهٔ حجم زیاد − ۲ تیک بافر · فروش: سقف ناحیهٔ حجم زیاد + ۲ تیک بافر.
        // «ناحیهٔ حجم زیاد» همان باکس HV است که سطح سفارش هم از آن گرفته می‌شود.
        val slBuf = cfg.slBufTicks * cfg.mintick
        // حالت عادی: حد ضرر از باکس HV (سند ۵). حالت معکوس: آینهٔ همان فاصله در جهت معامله.
        s.dirTrade = td
        s.sl = if (td != s.dir) eRef - rDoc * td else (if (bull) s.hvL - slBuf else s.hvH + slBuf)
        // §4-8 / §18-5 / §18-32: لنگر صفر فیبوناچی = جدیدترین HH یعنی بالاترین pinBE بعد از تغییر
        // روند به صعودی (خرید) / پایین‌ترین pinBU بعد از تغییر روند به نزولی (فروش)؛
        // لنگر ۱۰۰٪ = سطح تایید میانی (کف برای خرید، سقف برای فروش).
        val a0 = fibAnchor(bull)
        val a1 = s.midRef
        // استراتژی ۲: بدون فیبو؛ حد سود نهایی فقط «ورود ± tpUnits» (TP1/TP2 ندارد) — همان مسیر «فیبوی نامعتبر» سند.
        val targets = if (cfg.strategy == Strategy.NO_TREND)
            calculateTargetLevels(bull, eRef, Double.NaN, Double.NaN, maxOf(cfg.tpUnits, cfg.mintick))
        else calculateTargetLevels(bull, eRef, a0, a1, cfg.minTPunits)
        if (cfg.trigger2TargetR > 0.0 && rDoc > 0.0) {
            // سند ۶: یک حد سود واحد بر حسب R (پله‌های فیبو حذف می‌شوند).
            s.tp1 = Double.NaN
            s.tp2 = Double.NaN
            s.tpx = eRef + cfg.trigger2TargetR * rDoc * td
        } else {
            s.tp1 = targets.tp1
            s.tp2 = targets.tp2
            s.tpx = targets.tpx
        }
        // §18-31 سند: سفارشی که فاصلهٔ حد ضررش از سطح سفارش بیشتر از N است ثبت نمی‌شود و
        // سفارش باطل است (علت: SL>N). HV مطابق ستاپ می‌ماند و ستاپ تا پر/منقضی/باطل‌شدن HV
        // ادامه دارد، ولی روی این HV سفارشی ثبت نمی‌شود و در بک‌تست هم حسابی ندارد.
        val slDist = abs(eRef - s.sl)
        if (slDistSamples.size < SL_SAMPLE_CAP) slDistSamples.add(slDist)
        // سند ۶/۷ (§۵-۳): فاصلهٔ حد ضرر باید دست‌کم «ضریب × هزینهٔ رفت‌وبرگشت هر واحد» باشد؛
        // در غیر این صورت کارمزد کل سود انتظاری را می‌خورد و سفارش ثبت نمی‌شود.
        //
        // ⚠ هزینه فقط وقتی هست که مود §۲۰ روشن باشد: طبق §۵-۱ سند، مود پیش‌فرض اپ «بدون
        // کارمزد» است و کمیسیون هم همان‌جا ۰ حساب می‌شود ([MoneyManager.commissionFor]).
        // پیش‌تر این‌جا مستقیم `mm.spread()` خوانده می‌شد، پس یک اسپردِ ذخیره‌شده از یک بار
        // روشن‌بودن مود §۲۰، بعد از خاموش‌کردن مود هم فیلتر را فعال نگه می‌داشت و همهٔ
        // سفارش‌ها بی‌صدا رد می‌شدند — «همه‌چیز صفر شد و برنگشت».
        val mm = broker?.mm
        val moneyModeOn = mm != null && mm.active
        val costPerUnit = if (moneyModeOn) mm!!.spread() else 0.0
        if (cfg.minSlCostMult > 0.0 && costPerUnit > 0.0 && slDist < cfg.minSlCostMult * costPerUnit) {
            cnt.slCostBlocked++
            if (s.slBlockedHvBi != s.hvBi) {
                s.slBlockedHvBi = s.hvBi
                lg("ENGINE", "سفارش ستاپ #${s.id} ثبت نمی‌شود — فاصلهٔ حد ضرر (${f2(slDist)}) کمتر از ${f2(cfg.minSlCostMult)}×هزینه (${f2(cfg.minSlCostMult * costPerUnit)}) است (§۵-۳ سند ۷؛ هزینهٔ هر واحد ${f2(costPerUnit)})",
                    "entry=${f2(eRef)} sl=${f2(s.sl)} bar=$curBi")
            }
            return
        }
        if (cfg.minSlUnits > 0.0 && slDist < cfg.minSlUnits) {
            cnt.slMinBlocked++
            if (s.slBlockedHvBi != s.hvBi) {
                s.slBlockedHvBi = s.hvBi
                lg("ENGINE", "سفارش ستاپ #${s.id} ثبت نمی‌شود — فاصلهٔ حد ضرر (${f2(slDist)}) کمتر از حداقل ${f2(cfg.minSlUnits)} است (SL<min)؛ با این کارمزد، معامله بازنده است",
                    "entry=${f2(eRef)} sl=${f2(s.sl)} bar=$curBi")
            }
            return
        }
        if (slDist > cfg.maxSlUnits) {
            cnt.slNBlocked++
            if (s.slBlockedHvBi != s.hvBi) {
                s.slBlockedHvBi = s.hvBi
                lg("ENGINE", "سفارش ستاپ #${s.id} ثبت نمی‌شود — فاصلهٔ حد ضرر از سطح سفارش (${f2(slDist)}) بیشتر از N=${f2(cfg.maxSlUnits)} است (SL>N، §18-31 سند)؛ HV و ستاپ ادامه دارند",
                    "entry=${f2(eRef)} sl=${f2(s.sl)} bar=$curBi")
            }
            return
        }
        s.slBlockedHvBi = -1
        // §4-8: فقط و فقط یک سفارش برای هر LV امکان‌پذیر است چه سفارش پر شود چه فعال نشود چه باطل شود.
        if (!s.prevEntry.isNaN()) {
            clearHvMark(s)
            lg("ENGINE", "مسلح‌مجدد ستاپ #${s.id} رد شد — فقط یک سفارش برای هر LV مجاز است (§4-8)",
                "prev=${f2(s.prevEntry)} new=${f2(eRef)} bar=$curBi")
            return
        }
        // §4-8 و §10 سند نهایی: ما فقط مجاز به گذاشتن ۱ سفارش در چارت هستیم (حداکثر ۱ سفارش معلق یا ۱ معامله باز).
        // §4-8 و §18-12/§18-13: در هر لحظه فقط یک سفارش (معلق یا باز) مجاز است؛ وجود معاملهٔ باز مانع مسلح شدن است.
        // استراتژی ۲: خرید و فروش هم‌زمان مجازند ولی از هر جهت فقط یک سفارش (معلق یا باز).
        val limit = max(1, cfg.maxSetups)
        val activeNow = if (cfg.strategy == Strategy.NO_TREND)
            max(setups.count { it.id != s.id && it.tradeDir == s.tradeDir && (it.stage == 6 || it.stage == 7) },
                broker?.activeCount(s.tradeDir) ?: 0)
        else
            max(setups.count { it.id != s.id && (it.stage == 6 || it.stage == 7) }, broker?.activeCount() ?: 0)
        val hasPendingSetupOrder = activeNow >= limit
        if (hasPendingSetupOrder) {
            clearHvMark(s)
            lg("ENGINE", if (cfg.strategy == Strategy.NO_TREND)
                "مسلح‌شدن ستاپ #${s.id} رد شد — از هر جهت فقط یک سفارش (معلق یا باز) مجاز است"
            else "مسلح‌شدن ستاپ #${s.id} رد شد — سقف ستاپ هم‌زمان (${limit}) پر است: سفارش معلق یا معاملهٔ باز دیگری وجود دارد (§۱۸-۱۳)",
                "bar=$curBi")
            return
        }
        //  ⚠ فقط اگر کارگزار واقعاً سفارش را ثبت کرد (یا در حالت بدون کارگزار) به stage=6 می‌رویم.
        s.entry = eRef   // سطح سفارش (§4-8) — کارگزار همین سطح را ثبت می‌کند
        val registered = broker?.registerLimit(s, cd) ?: true
        if (!registered) {
            clearHvMark(s)
            lg("ENGINE", "ثبت سفارش برای ستاپ #${s.id} رد شد (${broker?.lastError}) — کندل HV ریست شد",
                "bar=$curBi")
            return
        }
        s.armBi = curBi
        s.armT = curT
        s.armsSinceS++
        s.prevEntry = eRef
        // §4-8: انقضای سفارش معلق با عبور باز/کلوز از HH (خرید) / LL (فروش) در لحظهٔ مسلح‌شدن
        s.priceExpiryRef = if (bull) HH else LL
        // §4-8: اگر فاصلهٔ حد سود نهایی فیبوناچی از ورود کمتر از ۱۰ واحد باشد، هدف‌ها «جایگزین ۱۰ واحد»
        // می‌شوند. ولی فیبوناچی از چارت حذف نمی‌شود — رسم آن اطلاعات است و همیشه درست رسم می‌شود
        // (اصلاح نسخهٔ ۷: لنگر صفر = بالاترین سقف واقعی روند، نه آخرین pinBE کهنه).
        updateSetupFib(s)
        s.rearmAllowed = false
        s.stage = 6
        cnt.hv++
        s.hvConfBi = curBi
        s.hvConfT = curT
        closeT2Box(s, ZoneStatus.DELETED, curBi, curT)
        val zb2 = ZoneBox(zoneSeq++, s.dir, 2, s.hvH, s.hvL, s.hvBi, s.hvT, 0, 0, curBi, curT, ZoneStatus.ACTIVE)
        t2Zones.add(zb2)
        s.hvZoneId = zb2.id
        s.entry = eRef
        s.be = false
        s.tp2Hit = false
        addMarker(curBi, eRef, 7, if (bull) "مسلح برای خرید (Armed)" else "مسلح برای فروش (Armed)")
        events.add("[${s.id}] مسلح شد · ورود ${f2(eRef)} · SL ${f2(s.sl)} · TP ${f2(s.tpx)}")
        lg("ENGINE", "ناحیهٔ ولوم زیاد تایید شد → ستاپ مسلح (Armed) · ستاپ #${s.id}",
            "dir=${if (bull) "BUY" else "SELL"} ورود=${f2(eRef)} SL=${f2(s.sl)} TP1=${f2(s.tp1)} TP2=${f2(s.tp2)} TP=${f2(s.tpx)} bar=$curBi")
        alarm(AlertKind.HV, "ناحیهٔ ولوم زیاد تایید شد",
            "ستاپ #${s.id} (" + (if (bull) "خرید" else "فروش") + ") · ورود ${f2(eRef)} · حدضرر ${f2(s.sl)} · حدسود ${f2(s.tpx)}")
    }

    // ── ابزارها ────────────────────────────────────────────────────────────────
    private fun bestReady(ps: List<Pin>, kind: Int): Int {
        var idx = -1
        var best = Double.NaN
        for (i in ps.indices) {
            val p = ps[i]
            if (p.status == 1 && p.kind == kind) {
                val lv = if (kind == 1) p.lo else p.hi
                if (best.isNaN() || (if (kind == 1) lv < best else lv > best)) { best = lv; idx = i }
            }
        }
        return idx
    }

    /**
     * انتخاب کندل مهم در «پایین‌ترین‌ها» و «بالاترین‌ها» — **مطابق §4-1 سند نهایی استراتژی**.
     *
     * پایین‌ترین‌ها: کمترین کف (`min(low(a, b))`)؛ در صورت تساوی کف، کمترین سقف (`min(high(a, b))`).
     * بالاترین‌ها: بیشترین سقف (`max(high(a, b))`)؛ در صورت تساوی سقف، کمترین کف (`min(low(a, b))`).
     */
    internal fun pickLow(a: Candle, b: Candle): Triple<Double, Double, Int> =
        if (a.l < b.l || (a.l == b.l && a.h < b.h)) Triple(a.h, a.l, a.bi) else Triple(b.h, b.l, b.bi)

    internal fun pickHigh(a: Candle, b: Candle): Triple<Double, Double, Int> =
        if (a.h > b.h || (a.h == b.h && a.l < b.l)) Triple(a.h, a.l, a.bi) else Triple(b.h, b.l, b.bi)

    /**
     * پاک‌کردن مارکرِ یک پین از روی چارت (§18-4 سند نهایی: «IC، Ready، pin که به‌خاطر
     * توالی اجازه ندارند یا به‌خاطر قوانین باطل شدند **کاملاً دور ریخته می‌شوند**»).
     *
     * پیش‌تر وضعیت پین پاک می‌شد ولی مارکر «کندل مهم …» آن برای همیشه روی چارت می‌ماند؛
     * پس بعد از هر ابطال، پین هم‌جهت بعدی ثبت می‌شد و روی چارت چند مارکر هم‌جهتِ پشت‌سرهم
     * (مثلاً سه «کندل مهم بالاترین» بدون «پایین‌ترین» میانشان) دیده می‌شد، در حالی که طبق
     * §18-1/§18-3 چنین توالی‌ای وجود ندارد. مارکر با (kind, price) و **آخرین** مورد منطبق
     * پیدا می‌شود — همان روشی که [addStructLabel] برای یافتن مارکر پین به کار می‌برد؛
     * پین زنده همیشه تازه‌ترین مارکرِ هم‌قیمت و هم‌جهت است.
     */
    private fun removePinMarker(kind: Int, price: Double) {
        if (price.isNaN()) return
        val idx = markers.indexOfLast { it.kind == kind && it.price == price }
        if (idx >= 0) {
            markers.removeAt(idx)
            prunePinMarkerAlternation()
        }
    }

    /**
     * تناوب سخت مارکرهای pinBU/pinBE روی چارت (§18-1/§18-3/§18-4 سند): در دنبالهٔ
     * مارکرهای پین هرگز دو مارکر هم‌جهت پشت‌سرهم نباید دیده شود.
     *
     * چرا با وجود حذف مارکرِ پینِ باطل‌شده باز هم دو هم‌جهت پشت‌سرهم می‌ماند؟
     * وقتی پین هم‌جهت تازه‌ای Ready می‌شود، وضعیت پین قبلی همان جهت بازنویسی می‌شود
     * (lastBUpin/lastBEpin) و موتور دیگر آن پین قدیمی را ردیابی نمی‌کند؛ اگر بعداً پین
     * مخالفِ بین آن دو باطل شود و مارکرش (درست) حذف شود، مارکر پین قدیمیِ ازیادرفته
     * برای همیشه روی چارت می‌ماند و کنار پین هم‌جهت بعدی «دو کندل مهم بالاترین/پایین‌ترین
     * پشت‌سرهم» دیده می‌شود — همان ایراد عکس‌های مالک. طبق §18-4 چنین پینی مرده است و
     * باید کاملاً دور ریخته شود؛ این تابع پس از هر ثبت/حذف مارکر پین، از هر جفت هم‌جهتِ
     * چسبیده، آن‌که پینِ زندهٔ فعلی نیست را دور می‌ریزد (اگر هیچ‌کدام زنده نبود، قدیمی‌تر
     * دور ریخته می‌شود چون پین تازه جای آن را گرفته است). در بارگذاری وضعیت ذخیره‌شده هم
     * صدا زده می‌شود تا مارکرهای مردهٔ ذخیره‌های قدیمی پاک‌سازی شوند.
     */
    fun prunePinMarkerAlternation() {
        fun isLivePin(m: Marker): Boolean =
            (m.kind == MarkerKind.PIN_BU && buActive && m.price == lastBUpin) ||
                (m.kind == MarkerKind.PIN_BE && beActive && m.price == lastBEpin)
        var prevIdx = -1
        var i = 0
        while (i < markers.size) {
            val m = markers[i]
            if (m.kind != MarkerKind.PIN_BU && m.kind != MarkerKind.PIN_BE) { i++; continue }
            if (prevIdx >= 0 && markers[prevIdx].kind == m.kind) {
                val victim = if (isLivePin(markers[prevIdx]) && !isLivePin(m)) i else prevIdx
                markers.removeAt(victim)
                prevIdx = -1
                i = 0
                continue
            }
            prevIdx = i
            i++
        }
    }

    /**
     * پاک‌کردن کامل pinBU بعد از ابطال/شکست بدون توالی (§4-1 سند نهایی).
     * «کامل» یعنی مارکر چارت آن هم دور ریخته می‌شود (§18-4) — همهٔ فراخواننده‌های این
     * تابع مسیر ابطال یا مصرف‌شدن پین در چرخش روندند؛ برگشت HH/HL به پین (شکست HH/LL)
     * از این تابع نمی‌گذرد و مارکر پین در آن مسیر سر جایش می‌ماند.
     */
    private fun clearBUpin() {
        removePinMarker(MarkerKind.PIN_BU, lastBUpin)
        buActive = false
        buSeq = -1
        lastBUpin = Double.NaN
        lastBUpinRef = Double.NaN
        lastBUpinHi = Double.NaN
    }

    /** قرینهٔ کامل برای pinBE. */
    private fun clearBEpin() {
        removePinMarker(MarkerKind.PIN_BE, lastBEpin)
        beActive = false
        beSeq = -1
        lastBEpin = Double.NaN
        lastBEpinRef = Double.NaN
        lastBEpinLo = Double.NaN
    }

    /**
     * §18-32 سند: لنگر صفر فیبوناچی = **جدیدترین HH**، یعنی «صعودی‌ترین/بالاترین pinBE بعد از
     * تغییر روند به صعودی» (خرید) و قرینه‌اش «نزولی‌ترین/پایین‌ترین pinBU بعد از تغییر روند به
     * نزولی» (فروش).
     *
     * این همان [highestBE]/[lowestBUpin] است: در هر شروع/چرخش روند بازمقداردهی می‌شود و با هر
     * pinBE/pinBU تازهٔ هم‌جهت به سمت اکسترمم خودش حرکت می‌کند. برخلاف برچسب ساختاری HH/LL که
     * با شکست سطح پاک (NaN) می‌شود و تا ثبت پین بعدی فیبو را بی‌دلیل نامعتبر می‌کرد.
     * §4-8 سند هم لنگر صفر را «بالاترین سقف (HH)» می‌نامد که با همین تعریف می‌خواند.
     */
    fun fibAnchor(bull: Boolean): Double = if (bull) maxOfOr(highestBE, trendHigh) else minOfOr(lowestBUpin, trendLow)

    private fun maxOfOr(a: Double, b: Double): Double =
        if (a.isNaN()) b else if (b.isNaN()) a else maxOf(a, b)

    private fun minOfOr(a: Double, b: Double): Double =
        if (a.isNaN()) b else if (b.isNaN()) a else minOf(a, b)

    /** §18-5 و §18-32: رسم فیبوناچی ریتریسمنت از جدیدترین HH (خرید) / LL (فروش) تا کف/سقف تایید میانی. */
    private fun updateSetupFib(s: Setup) {
        if (cfg.strategy == Strategy.NO_TREND || s.midRef.isNaN()) {
            clearSetupFib(s)
            return
        }
        val bull = s.dir == 1
        val a0 = fibAnchor(bull)
        val a1 = s.midRef
        val valid = !a0.isNaN() && !a1.isNaN() && (if (bull) a0 > a1 else a0 < a1)
        if (!valid) {
            clearSetupFib(s)
            return
        }
        val r = if (bull) a0 - a1 else a1 - a0
        s.fib0 = a0
        s.fib1 = a1
        s.fib382 = if (bull) a1 + 0.382 * r else a1 - 0.382 * r
        s.fib500 = if (bull) a1 + 0.5 * r else a1 - 0.5 * r
        s.fib1272 = if (bull) a1 + 1.272 * r else a1 - 1.272 * r
        s.fibValid = true
    }

    /** §18-5 / §4-8: ابطال یا حذف فیبوناچی در ستاپ/معامله. */
    private fun clearSetupFib(s: Setup) {
        s.fib0 = Double.NaN
        s.fib1 = Double.NaN
        s.fib382 = Double.NaN
        s.fib500 = Double.NaN
        s.fib1272 = Double.NaN
        s.fibValid = false
    }

    private fun cancelDir(dir: Int) {
        // §4-1 (بخش «برخورد چرخش با ستاپ‌های موجود»): با تغییر روند فقط **ستاپ‌ها** لغو می‌شوند؛
        // «معاملهٔ باز هرگز به‌زور بسته نمی‌شود» (stage=7 این‌جا دست نمی‌خورد).
        val it = setups.iterator()
        while (it.hasNext()) {
            val s = it.next()
            if (s.dir == dir && s.stage < 7) {
                closeT1Box(s, ZoneStatus.DELETED, curBi, curT)
                closeT2Box(s, ZoneStatus.DELETED, curBi, curT)
                clearSetupFib(s)
                s.stage = 8
                s.note = "لغو (تغییر روند)"
                events.add("[${s.id}] لغو شد — تغییر روند")
                lg("ENGINE", "ستاپ #${s.id} به دلیل تغییر روند لغو شد", "dir=${if (dir == 1) "BUY" else "SELL"}")
                broker?.onSetupInvalidated(s, curT, curBi)
                it.remove()
            }
        }
        // ⚠ §4-5 سند نهایی: «حذف ناحیه» **فقط** با بسته شدن یک کندل تریگر۲ از سمت دور ناحیه است
        // («تصمیم جدید مالک»). تغییر روند باکس‌های ناحیه را پاک/خاکستری نمی‌کند؛ وگرنه هر بار
        // روند عوض می‌شود تاریخچهٔ باکس‌های قدیمی (که سند می‌گوید نامحدود نگه داشته و ذخیره شوند)
        // از بین می‌رفت. ابطال باکس‌ها تابع قاعدهٔ خودشان است (کلوز بیرون از سمت دور).
    }

    private fun addMarker(bi: Int, price: Double, kind: Int, text: String) {
        val t = times[bi] ?: curT
        addMarkerAtTime(t, bi, price, kind, text)
    }

    /**
     * برچسب ساختار (HH / HL / LL / LH) روی کندل مهمِ همان پین.
     * اگر ایندکس کندل مهم داده نشده باشد، از مارکر پینِ هم‌قیمت پیدا می‌شود.
     */
    private fun addStructLabel(text: String, pinKind: Int, price: Double, impBi: Int) {
        if (price.isNaN()) return
        var bi = impBi
        var t = if (impBi != -1) times[impBi] else null
        if (impBi == -1) {
            val pm = markers.lastOrNull { it.kind == pinKind && it.price == price }
            if (pm != null) { bi = pm.bi; t = pm.t }
        }
        markers.removeAll { it.kind == MarkerKind.STRUCT && it.text == text && it.price == price }
        addMarkerAtTime(t ?: curT, if (bi == -1) curBi else bi, price, MarkerKind.STRUCT, text)
    }

    private fun removeStructLabel(text: String, price: Double) {
        if (price.isNaN()) return
        markers.removeAll { it.kind == MarkerKind.STRUCT && it.text == text && it.price == price }
    }

    /** علامت‌گذاری جهت روند فعلی هنگام بازسازی/بازیابیِ وضعیت قدیمی. */
    fun ensureCurrentTrendMarker(t: Long, price: Double, bi: Int) {
        if (trend == 0 || t <= 0L || price.isNaN()) return
        val kind = if (trend > 0) MarkerKind.TREND_UP else MarkerKind.TREND_DOWN
        markers.removeAll { it.text.startsWith("روند فعلی:") }
        val text = if (trend > 0) "روند فعلی: صعودی ▲" else "روند فعلی: نزولی ▼"
        addMarkerAtTime(t, bi, price, kind, text)
    }

    private fun addMarkerAtTime(t: Long, bi: Int, price: Double, kind: Int, text: String) {
        markers.add(Marker(t, bi, price, kind, text))
        // ثبت هر پین تازه، فرصت دور ریختن مارکر پین هم‌جهتِ مردهٔ قبلی است (§18-4).
        if (kind == MarkerKind.PIN_BU || kind == MarkerKind.PIN_BE) prunePinMarkerAlternation()
        // توالی پین‌ها و شروع/چرخش روند تاریخچه‌اند و نباید با سقف مارکرهای
        // جزئی حذف شوند؛ فقط مارکرهای رخدادهای پرتکرار قدیمی‌تر trim می‌شوند.
        while (markers.size > 2000) {
            val discard = markers.indexOfFirst {
                it.kind != MarkerKind.PIN_BU && it.kind != MarkerKind.PIN_BE &&
                    it.kind != MarkerKind.TREND_UP && it.kind != MarkerKind.TREND_DOWN &&
                    it.kind != MarkerKind.STRUCT
            }
            if (discard < 0) break
            markers.removeAt(discard)
        }
    }

    /** نگاشت ایندکس کندل → زمان (برای برچسب‌ها و ذخیره‌سازی). */
    private val times = HashMap<Int, Long>()

    fun stageFa(st: Int): String = when (st) {
        0 -> "منتظر برخورد به ناحیهٔ ساختار"
        1 -> "برخورد شد · منتظر بسته شدن کندل میانی"
        2 -> "تایید میانی ✔"
        3 -> "اسکن ناحیهٔ ولوم کم (تریگر۱)"
        4 -> "ولوم کم ✔ · اسکن ولوم زیاد (تریگر۲)"
        5 -> "کندل ولوم زیاد (تریگر۲) مارک شد"
        6 -> "مسلح (Armed) · منتظر برگشت به ناحیهٔ ولوم زیاد"
        7 -> "در معامله"
        else -> "پایان‌یافته"
    }

    companion object {
        /** حداقل تعداد ستاپ‌های در حال پیگیری (ناحیه‌های منتظر لمس تا معامله)؛ مستقل از «حداکثر ستاپ هم‌زمان». */
        const val TRACKED_SETUPS_MIN = 512

        fun fmtV(v: Double): String = if (v >= 1000) String.format("%.1fk", v / 1000) else String.format("%.0f", v)
        fun f2(v: Double): String = String.format("%.2f", v)
    }
}

/** ستاپ — معادل ساختار Setup در Pine. */
class Setup(
    val id: Long,
    val dir: Int,
    var stage: Int,
    var zTop: Double,
    var zBot: Double,
    var zIdx: Int,
    var zBi: Int
) {
    /** جهت معامله (۰ = همان جهت ساختار). در حالت «تریگر ۲ معکوس» برعکس [dir] است. */
    var dirTrade: Int = 0
    /** جهت مؤثر معامله — حد ضرر/حد سود و سفارش از این استفاده می‌کنند. */
    val tradeDir: Int get() = if (dirTrade == 0) dir else dirTrade
    var zT = 0L
    var touchBi = -1
    var touchMidStart = 0L
    var midRef = Double.NaN
    var midBi = -1
    var midT = 0L
    var markH = Double.NaN; var markL = Double.NaN; var markBi = -1
    var lvH = Double.NaN; var lvL = Double.NaN; var lvBi = -1; var lvT = 0L; var lvConfBi = -1; var lvConfT = 0L; var lvZoneId = -1L
    var hvH = Double.NaN; var hvL = Double.NaN; var hvBi = -1; var hvT = 0L; var hvScanBi = -1; var hvConfBi = -1; var hvConfT = 0L; var hvZoneId = -1L
    /** §18-31 سند: ایندکس HVای که به‌علت SL>N سفارش رویش ثبت نشد؛ تا وقتی همان HV برقرار است تلاش مجدد/لاگ تکراری نداریم. */
    var slBlockedHvBi = -1
    var midConfirmBi = -1
    var runMax = Double.NaN
    var zGone = false
    /** §18-14 سند: ناحیهٔ ساختار پس از لمس و گذشت یک کندل ساختار کامل «مصرف‌شده/باطل» است؛
     *  ستاپ تا ابطال تایید میانی فعال می‌ماند و با ابطال میانی پایان می‌یابد. */
    var zUsed = false
    var touchT = 0L
    var lvTouched = false
    var lvUsed = false
    /** §4-7 سند: تعداد دفعات مصرف سهمیهٔ باکس LV (حداکثر ۲؛ لمس سوم = ابطال). */
    var lvUses = 0
    /** §4-9 سند («خیلی مهم»): انتقال یک‌بارهٔ حد ضرر به سطح سفارش انجام شده است. */
    /** غیرفعال: بند §4-9 «انتقال حد ضرر به سطح oo» در سند به تعویق افتاده است؛ همیشه false می‌ماند. */
    var slMidDone = false
    var lvReEntered = false
    var entry = Double.NaN
    var sl = Double.NaN
    var tp1 = Double.NaN
    var tp2 = Double.NaN
    var tpx = Double.NaN
    var be = false
    var tp2Hit = false      // آیا پلهٔ ۳۳٪ دوم (TP2) لمس شده (فقط برای مارکر چارت/لاگ)
    var note = ""
    var orderId = -1L
    // §4-8 سند نهایی: انقضای سفارش معلق
    var armBi = -1                // کندلی که در آن مسلح شدیم
    var armT = 0L
    var armsSinceS = 0            // تعداد مسلح‌شدن در این ستاپ (برای شرط «پایین‌تر/بالاتر»)
    var prevEntry = Double.NaN    // ورود قبلی در همین ستاپ (برای مقایسه)
    var rearmAllowed = false      // آیا اعتبار ساختار/میانی/تریگر۱ برای مسلح‌مجدد حفظ شده است
    var priceExpiryRef = Double.NaN // آخرین پین مخالف هنگام مسلح‌شدن این سفارش
    var entryBi = -1              // کندلی که ورود در آن پر شد (برای قاعده «ورود و TP1 در یک کندل»)
    var entryT = 0L
    // §18-5 و §4-8: لنگرها و سطوح فیبوناچی ریتریسمنت برای این ستاپ/معامله
    var fib0 = Double.NaN
    var fib1 = Double.NaN
    var fib382 = Double.NaN
    var fib500 = Double.NaN
    var fib1272 = Double.NaN
    var fibValid = false
    val bull: Boolean get() = dir == 1

    /** کپی کامل وضعیت ستاپ با شناسهٔ تازه؛ برای حفظ ستاپ‌های ناحیه‌های قدیمی هنگام بازسازی سری قیمت. */
    fun copyWithId(newId: Long): Setup = Setup(newId, dir, stage, zTop, zBot, zIdx, zBi).also { c ->
        c.zT = zT
        c.touchBi = touchBi; c.touchMidStart = touchMidStart; c.touchT = touchT
        c.midRef = midRef; c.midBi = midBi; c.midT = midT; c.midConfirmBi = midConfirmBi
        c.markH = markH; c.markL = markL; c.markBi = markBi
        c.lvH = lvH; c.lvL = lvL; c.lvBi = lvBi; c.lvT = lvT
        c.lvConfBi = lvConfBi; c.lvConfT = lvConfT; c.lvZoneId = lvZoneId
        c.hvH = hvH; c.hvL = hvL; c.hvBi = hvBi; c.hvT = hvT; c.hvScanBi = hvScanBi
        c.hvConfBi = hvConfBi; c.hvConfT = hvConfT; c.hvZoneId = hvZoneId
        c.slBlockedHvBi = slBlockedHvBi
        c.runMax = runMax; c.zGone = zGone; c.zUsed = zUsed
        c.lvTouched = lvTouched; c.lvUsed = lvUsed; c.lvReEntered = lvReEntered; c.lvUses = lvUses
        c.slMidDone = slMidDone
        c.entry = entry; c.sl = sl; c.tp1 = tp1; c.tp2 = tp2; c.tpx = tpx
        c.be = be; c.tp2Hit = tp2Hit; c.note = note; c.orderId = orderId
        c.armBi = armBi; c.armT = armT; c.armsSinceS = armsSinceS; c.prevEntry = prevEntry
        c.rearmAllowed = rearmAllowed; c.priceExpiryRef = priceExpiryRef
        c.entryBi = entryBi; c.entryT = entryT
        c.fib0 = fib0; c.fib1 = fib1; c.fib382 = fib382; c.fib500 = fib500
        c.fib1272 = fib1272; c.fibValid = fibValid
    }
}


/** هدف‌های فیبوناچی با فالبک حداقل فاصلهٔ TPX. اگر TPX فالبک شود، پله‌ها نیز از همان فاصله محاسبه می‌شوند و فیبوناچی در آن معامله حذف می‌شود (§4-8). */
internal data class TargetLevels(val tp1: Double, val tp2: Double, val tpx: Double, val fibDeleted: Boolean = false)

internal fun calculateTargetLevels(
    bull: Boolean,
    entry: Double,
    anchor0: Double,
    anchor1: Double,
    minDistance: Double
): TargetLevels {
    val haveFib = !anchor0.isNaN() && !anchor1.isNaN() &&
            (if (bull) anchor0 > anchor1 else anchor0 < anchor1)
    if (!haveFib) {
        val tpx = if (bull) entry + minDistance else entry - minDistance
        return TargetLevels(Double.NaN, Double.NaN, tpx, fibDeleted = true)
    }

    val r = if (bull) anchor0 - anchor1 else anchor1 - anchor0
    val fib0382 = if (bull) anchor1 + 0.382 * r else anchor1 - 0.382 * r
    val fib0500 = if (bull) anchor1 + 0.5 * r else anchor1 - 0.5 * r
    val fib1272 = if (bull) anchor1 + 1.272 * r else anchor1 - 1.272 * r

    return if (bull) {
        val movedToMinimum = fib1272 - entry < minDistance
        if (movedToMinimum) {
            val tpx = entry + minDistance
            val distance = tpx - entry
            TargetLevels(entry + distance * 0.382, entry + distance * 0.5, tpx, fibDeleted = true)
        } else if (entry >= fib0382) {
            // §18-20: در صورتی که سفارش بعد از ناحیه 0.382 فیبو باز شده بود حد سود اول ناحیه 0.5 و حد سود نهایی 1.272 است و لحظه سر به سر شدن معامله در ناحیه 0.5 است
            val tp1 = if (fib0500 > entry) fib0500 else Double.NaN
            TargetLevels(tp1, Double.NaN, fib1272, fibDeleted = false)
        } else {
            TargetLevels(fib0382, fib0500, fib1272, fibDeleted = false)
        }
    } else {
        val movedToMinimum = entry - fib1272 < minDistance
        if (movedToMinimum) {
            val tpx = entry - minDistance
            val distance = entry - tpx
            TargetLevels(entry - distance * 0.382, entry - distance * 0.5, tpx, fibDeleted = true)
        } else if (entry <= fib0382) {
            // §18-20: در صورتی که سفارش بعد از ناحیه 0.382 فیبو باز شده بود حد سود اول ناحیه 0.5 و حد سود نهایی 1.272 است و لحظه سر به سر شدن معامله در ناحیه 0.5 است
            val tp1 = if (fib0500 < entry) fib0500 else Double.NaN
            TargetLevels(tp1, Double.NaN, fib1272, fibDeleted = false)
        } else {
            TargetLevels(fib0382, fib0500, fib1272, fibDeleted = false)
        }
    }
}

/** A full structure interval has elapsed when the trigger-2 bar delta reaches its configured bar count. */
internal fun fullStructureElapsed(armBi: Int, currentBi: Int, tfS: Int, tf2: Int): Boolean {
    if (armBi < 0) return false
    val nBars = maxOf(1, tfS / maxOf(1, tf2))
    return currentBi - armBi >= nBars
}

/** Strict open/close crossing of the captured latest opposing pin; wicks and equality do not expire an order. */
internal fun priceExpiryCrossed(bull: Boolean, open: Double, close: Double, level: Double): Boolean {
    if (level.isNaN()) return false
    return if (bull) open > level || close > level else open < level || close < level
}

/** The original structure/middle/Trigger-1 chain must remain valid for a rearm. */
internal fun rearmPrerequisitesValid(s: Setup): Boolean =
    !s.zGone && !s.midRef.isNaN() && s.lvBi >= 0 && s.lvReEntered && !s.lvUsed

/** A close/wick that invalidates a pre-entry condition while the trade is open blocks only a future rearm. */
internal fun rearmInvalidatedByBar(s: Setup, bar: Candle, midInvalidClose: Boolean): Boolean {
    val bull = s.dir == 1
    val structureBroken = bar.bi > s.zBi &&
            (if (bull) bar.c < s.zBot else bar.c > s.zTop)
    val midBroken = !s.midRef.isNaN() && bar.bi > s.midConfirmBi &&
            (if (midInvalidClose) {
                if (bull) bar.c < s.midRef else bar.c > s.midRef
            } else {
                if (bull) bar.l < s.midRef else bar.h > s.midRef
            })
    return structureBroken || midBroken
}
