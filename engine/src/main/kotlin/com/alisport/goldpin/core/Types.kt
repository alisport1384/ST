package com.alisport.goldpin.core

// ═══════════════════════════════════════════════════════════════════════════════
//  انواع پایه — پورت مستقیم تایپ‌های Pine اسکریپت PinReady_FRVP
// ═══════════════════════════════════════════════════════════════════════════════

/** یک کندل. [bi] ایندکس کندل در چارت (تایم‌فریم تریگر ۲) است. */
data class Candle(
    val bi: Int,
    val t: Long,
    val o: Double,
    val h: Double,
    val l: Double,
    val c: Double,
    val v: Double
) {
    val bull: Boolean get() = c > o
    val bear: Boolean get() = c < o
}

/** تجمیع‌کنندهٔ تایم‌فریم — معادل `f_step` در Pine. */
class Agg {
    var cur: Candle? = null
    var cl: Candle? = null
    var closedNow = false
    var periods = 0

    fun reset() {
        cur = null; cl = null; closedNow = false; periods = 0
    }

    fun step(isNew: Boolean, bi: Int, t: Long, o: Double, h: Double, l: Double, c: Double, v: Double) {
        closedNow = false
        if (isNew) {
            val c0 = cur
            if (periods >= 1 && c0 != null) {
                cl = Candle(c0.bi, c0.t, c0.o, c0.h, c0.l, c0.c, c0.v)
                closedNow = true
            }
            cur = Candle(bi, t, o, h, l, c, v)
            periods++
        } else if (cur == null) {
            cur = Candle(bi, t, o, h, l, c, v)
            // اولین کندلِ موجود در تاریخچه هم یک عضو بازه است؛ آن را برای بستن
            // در مرز بعدی بشمار تا ابتدای تاریخچه از تجمیع ساختار حذف نشود.
            periods = maxOf(periods, 1)
        } else {
            val u = cur!!
            cur = Candle(u.bi, u.t, u.o, maxOf(u.h, h), minOf(u.l, l), c, u.v + v)
        }
    }
}

/** ناحیهٔ فیکس‌رنج. [loIdx]/[hiIdx] ایندکس ردیف‌ها برای بازتولید دقیق مرزها. */
class Zone(val top: Double, val bot: Double, val idx: Int, val loIdx: Int, val hiIdx: Int)

/** پروفایل حجم فیکس‌رنج. */
class Profile(
    val vol: DoubleArray,
    val lo: Double,
    val step: Double,
    val poc: Double,
    val vaHi: Double,
    val vaLo: Double
) {
    val rows: Int get() = vol.size
    fun rowBot(r: Int) = lo + r * step
    fun rowTop(r: Int) = lo + (r + 1) * step
    fun rowMid(r: Int) = lo + r * step + step / 2.0
}

/** کاندید «کندل مهم». kind: +1 پایین‌ترین‌ها (BU) ، -1 بالاترین‌ها (BE). status: 0 فعال، 1 تاییدشده، -1 مرده */
class Pin(
    val kind: Int,
    var hi: Double,
    var lo: Double,
    val impBi: Int,
    val startBi: Int,
    val ref: Double,
    var status: Int
)

/** چرخهٔ عمر باکس ناحیه روی چارت — برای رسم، ذخیره و بازیابی دقیق. */
object ZoneStatus {
    const val ACTIVE = 0      // زنده
    const val DELETED = 1     // با کلوز از سمت دور پاک شد
    const val REJECTED = 2    // کلوز داخل ناحیه بود → ناحیهٔ ردشده (خط‌چین خاکستری)
    const val CAP = 3         // به‌خاطر سقف تعداد حذف شد
    const val CONSUMED = 4    // ستاپش پایان یافت (باکس مصرف‌شده)
}

class ZoneBox(
    val id: Long,
    val dir: Int,
    val idx: Int,
    val top: Double,
    val bot: Double,
    val createdBi: Int,
    val createdT: Long,
    val loIdx: Int,
    val hiIdx: Int,
    var endBi: Int = createdBi,
    var endT: Long = createdT,
    var status: Int = ZoneStatus.ACTIVE
)

/** وضعیت سفارش. */
object OrderStatus {
    const val PENDING = 0
    const val FILLED = 1
    const val CANCELLED_EXPIRED = 2
    const val CANCELLED_INVALID = 3
    const val CANCELLED_MANUAL = 4
}

class Order(
    val id: Long,
    val setupId: Long,
    val dir: Int,
    val type: String,          // LIMIT
    val price: Double,
    val sl: Double,
    val tp1: Double,
    val tp2: Double,
    val tpX: Double,
    val qty: Double,
    val zoneTop: Double,
    val zoneBot: Double,
    val placedT: Long,
    val placedBi: Int,
    var status: Int = OrderStatus.PENDING,
    var filledT: Long? = null,
    var filledBi: Int? = null,
    var closedT: Long? = null,
    var cancelReason: String? = null
) {
    /** §20: توضیح محاسبهٔ حجم (مود، Lost، div، MinLot …) برای نمایش در جزئیات سفارش؛ خالی = مود خاموش. */
    var mmNote: String = ""
    /** §20 Mixed: این سفارش در فاز ManageCM (Capital > M) حجم‌گذاری شده است؟ (فقط آن معاملات cmsnLost را می‌سازند) */
    var mmCm: Boolean = false
    /** §4-8: لبه‌های باکس HV هنگام مسلح‌شدن (برای نمایش/گزارش؛ قاعدهٔ انتقال §4-9 به تعویق افتاده است). */
    var hvTop: Double = Double.NaN
    var hvBot: Double = Double.NaN
    /**
     * جهت «لمس» سطح برای پر شدن. در حالت عادی = [dir]؛ در «سند ۶» سطح از سمت ساختار لمس می‌شود
     * ولی پوزیشن در جهت مخالف باز می‌شود (وگرنه سفارش عملاً marketable با قیمت بهتر از بازار
     * می‌شد و با قیمتی که هرگز معامله نشده پر می‌شد).
     */
    var touchDir: Int = dir
}

class Fill(
    val t: Long,
    val bi: Int,
    val price: Double,
    val qty: Double,
    val kind: String,          // ENTRY / TP1 / TP2 / TPX / SL / BE
    val pnl: Double
)

class Trade(
    val id: Long,
    val orderId: Long,
    val setupId: Long,
    val dir: Int,
    val entryT: Long,
    val entryBi: Int,
    val entry: Double,
    val sl0: Double,
    var tp1: Double,
    var tp2: Double,
    val tpX: Double,
    val qty: Double,
    val zoneTop: Double,
    val zoneBot: Double,
    val contractSize: Double,
    var exitT: Long? = null,
    var exitBi: Int? = null,
    var exitPx: Double? = null,
    var reason: String = "open",
    var be: Boolean = false,
    /**
     * **غیرفعال.** بند §4-9 «انتقال حد ضرر به سطح oo با کلوز تریگر۲» در ویرایش فعلی سند داخل بلوک
     * «به تعویق افتاده و در کد ایجاد نمی‌شود» است؛ بنابراین این فیلد هیچ‌گاه مقدار نمی‌گیرد و
     * فقط برای سازگاری با فایل‌های ذخیرهٔ قدیمی نگه داشته شده است. از آن در محاسبه استفاده نشود.
     */
    var slAdj: Double = Double.NaN,
    val fills: MutableList<Fill> = mutableListOf(),
    /** §20 cmsn: کمیسیون (اسپرد تحمیلی) پرداخت‌شده برای این معامله، به دلار؛ ۰ = بدون کمیسیون (مود §۲۰ خاموش). */
    var commission: Double = 0.0,
    /** §20 Mixed: این معامله در فاز ManageCM (Capital > M) حجم‌گذاری شده بود؟ */
    var mmCm: Boolean = false
) {
    /** §4-8: لبه‌های باکس HV این معامله (نمایش/گزارش؛ قاعدهٔ انتقال §4-9 به تعویق افتاده است). */
    var hvTop: Double = Double.NaN
    var hvBot: Double = Double.NaN
    val open: Boolean get() = exitT == null
    val risk: Double get() = if (sl0 != 0.0) kotlin.math.abs(entry - sl0) else 0.0

    /** سود/زیان ناخالصِ قیمتی: مجموع همهٔ پله‌ها، بدون کمیسیون. «نتیجهٔ قیمتی» معامله از همین می‌آید. */
    fun grossPnl(): Double = fills.sumOf { it.pnl }

    /** سود/زیان دلاری (خالص، شامل همهٔ پله‌ها و منهای کمیسیون cmsn). */
    fun pnl(): Double = grossPnl() - commission

    /**
     * نتیجهٔ قیمتی معامله، **مستقل از کمیسیون و مود مالی**:
     *  ۱ = سود (حداقل یک پلهٔ سودده خورد: TP1/TP2/TPX)، −۱ = ضرر (SL)،
     *  ۰ = سر‌به‌سر (خروج روی قیمت ورود، بدون هیچ سود قیمتی).
     * شمارش «برد/باخت/سر‌به‌سر» در گزارش‌ها از همین است؛ پس روشن‌کردن کمیسیون (مودهای §۲۰) تعداد TP/SL را عوض نمی‌کند
     * (قبلاً سر‌به‌سرهای صفر‌دلاری «برد» بودند و با کمیسیون یکباره «باخت» می‌شدند).
     */
    fun outcome(): Int {
        val g = grossPnl()
        return when { g > OUTCOME_EPS -> 1; g < -OUTCOME_EPS -> -1; else -> 0 }
    }

    /** چند R سود/زیان شد. */
    fun rMultiple(): Double {
        val r = risk * qty * contractSize
        return if (r > 1e-9) pnl() / r else 0.0
    }
}

/** آستانهٔ صفر بودن سود/زیان ناخالص برای تشخیص سر‌به‌سر (دلار). */
const val OUTCOME_EPS = 1e-9

/**
 * استراتژی قابل انتخاب (تنظیمات → «استراتژی»).
 *  [DOC]      = استراتژی ۱: سند اصلی (روند + کندل مهم/پین، ناحیه فقط روی کندل هم‌جهت با روند، حد سود فیبوناچی). **پیش‌فرض**.
 *  [NO_TREND] = استراتژی ۲: بدون تعیین روند؛ ناحیه روی همهٔ کندل‌های ساختار؛ حد سود ثابت به واحد؛
 *               هم‌زمان یک سفارش خرید و یک سفارش فروش (از هر جهت فقط یکی).
 */
object Strategy {
    const val DOC = 1
    const val NO_TREND = 2

    fun valid(s: Int) = s == DOC || s == NO_TREND

    fun label(s: Int): String = when (s) {
        NO_TREND -> "استراتژی ۲ — بدون روند · ناحیه روی همهٔ کندل‌ها · حد سود ثابت"
        else -> "استراتژی ۱ — سند اصلی (روند + فیبوناچی)"
    }
}

/** نوع مارکر روی چارت؛ مقادیر عددی در فایل‌های ذخیره‌شده ثابت می‌مانند. */
object MarkerKind {
    const val PIN_BU = 1
    const val PIN_BE = -1
    const val TOUCH = 2
    const val MID_CONFIRM = 3
    const val TRIGGER1 = 4
    const val TRIGGER1_CONFIRM = 5
    const val TRIGGER2 = 6
    const val ARMED = 7
    const val ENTRY = 8
    const val EXIT = 9
    const val INVALIDATION = 10
    const val TRIGGER1_USED = 11
    const val TREND_UP = 12
    const val TREND_DOWN = 13
    const val STRUCT = 14          // برچسب ساختار HH / HL / LL / LH
}

/** مارکر روی چارت (برچسب‌های استراتژی) — ذخیره و بازیابی می‌شود. */
class Marker(
    val t: Long,
    val bi: Int,
    val price: Double,
    val kind: Int,
    val text: String
)

/** سطوح معاملهٔ فعال برای رسم روی چارت. */
class TradeLines(
    var dir: Int = 0,
    var entry: Double? = null,
    var sl: Double? = null,
    var tp1: Double? = null,
    var tp2: Double? = null,
    var tpX: Double? = null,
    var fromBi: Int = 0,
    var active: Boolean = false
)

enum class Dir(val s: Int) { BULL(1), BEAR(-1) }

/**
 * انواع هشدار — رشته‌های ثابت که بین موتور (Kotlin خالص) و سیستم هشدار اپ
 * (util/Alerts.kt) مشترک‌اند. هر دو طرف از همین مقادیر استفاده می‌کنند.
 */
object AlertKind {
    const val ARMED = "ARMED"        // سفارش آماده شد (Armed)
    const val FILLED = "FILLED"      // پر شدن سفارش ورود
    const val TP1 = "TP1"
    const val TP2 = "TP2"
    const val TPX = "TPX"            // حد سود نهایی ۱٫۲۷۲
    const val SL = "SL"
    const val BE = "BE"              // سر‌به‌سر
    const val CLOSE = "CLOSE"        // پایان معامله
    const val CANCEL = "CANCEL"
    const val ZONE_NEW = "ZONE_NEW"
    const val ZONE_DEAD = "ZONE_DEAD"
    const val LV = "LV"              // ناحیهٔ ولوم کم
    const val HV = "HV"              // ناحیهٔ ولوم زیاد
    const val TREND = "TREND"
    const val PIN = "PIN"            // کندل مهم / Ready
    const val STAGE = "STAGE"
    const val FEED = "FEED"
    const val INFO = "INFO"
}

object Tf {
    const val M1 = 60
    const val M5 = 300
    const val M15 = 900
    const val M30 = 1800
    const val H1 = 3600
    const val H4 = 14400
    const val D1 = 86400
    const val W1 = 604800

    fun label(sec: Int): String = when (sec) {
        M1 -> "1m"; M5 -> "5m"; M15 -> "15m"; M30 -> "30m"
        H1 -> "1H"; H4 -> "4H"; D1 -> "D"; W1 -> "W"
        else -> if (sec % 3600 == 0) "${sec / 3600}H" else "${sec / 60}m"
    }

    /** آیا کندل جدید در این تایم‌فریم شروع می‌شود؟ (معادل timeframe.change) */
    fun isNewBarStart(sec: Int, t: Long, prevT: Long?): Boolean {
        if (prevT == null || prevT == t) return false
        return bucket(sec, t) != bucket(sec, prevT)
    }

    const val DAY_MS = 86_400_000L

    /** روز هفتهٔ UTC با مبدأ دوشنبه: ۰=دوشنبه … ۴=جمعه، ۵=شنبه، ۶=یکشنبه (روز epoch، ۱۹۷۰-۰۱-۰۱، پنجشنبه بود). */
    fun utcDowMonday0(t: Long): Int = Math.floorMod(Math.floorDiv(t, DAY_MS) + 3L, 7L).toInt()

    /**
     * شروع «روز معاملاتی» استاندارد (UTC): روزهای دوشنبه تا جمعه همان روز تقویمی‌اند؛
     * شنبه و یکشنبه تعطیل‌اند و سشن یکشنبه‌شب (بازگشایی بازار، از ۲۲:۰۰ UTC) جزئی از
     * روز معاملاتی دوشنبه است — پس هر مهر زمانی در شنبه/یکشنبه به دوشنبهٔ بعد می‌افتد
     * و هیچ کندل روزانه/هفتگیِ شنبه یا یکشنبه ساخته نمی‌شود.
     */
    fun tradingDayStart(t: Long): Long {
        val dayStart = Math.floorDiv(t, DAY_MS) * DAY_MS
        return when (utcDowMonday0(t)) {
            5 -> dayStart + 2 * DAY_MS   // شنبه → دوشنبهٔ بعد
            6 -> dayStart + DAY_MS       // یکشنبه (سشن بازگشایی) → دوشنبه
            else -> dayStart
        }
    }

    /**
     * شروع باکت زمانی.
     * تایم‌فریم‌های زیر روزانه: کفِ UTC معمولی. روزانه: [tradingDayStart].
     * هفتگی: **دوشنبهٔ ۰۰:۰۰ UTC** هفتهٔ معاملاتی (استاندارد بازار؛ هفته از دوشنبه تا
     * جمعه و تعطیلات شنبه و یکشنبه) — نه پنجشنبهٔ epoch که کف‌گیری سادهٔ ۷روزه می‌داد.
     */
    fun bucket(sec: Int, t: Long): Long {
        if (sec == D1) return tradingDayStart(t)
        if (sec == W1) {
            val td = tradingDayStart(t)
            return td - utcDowMonday0(td) * DAY_MS
        }
        val ms = sec * 1000L
        return Math.floorDiv(t, ms) * ms
    }
}

/**
 * ساعت استاندارد بازار طلا (XAUUSD/GC) — تعطیلات **شنبه و یکشنبه**:
 * بازار تمام شنبه و یکشنبه تا بازگشایی یکشنبه‌شب (**۲۲:۰۰ UTC**) بسته است؛ از ۲۲:۰۰
 * یکشنبه UTC سشن دوشنبه شروع می‌شود (همان داده‌ای که فیدها می‌دهند: یکشنبه فقط ۲۲ تا ۲۴).
 * وقتی بازار بسته است هیچ تصمیم استراتژی، ثبت/پرشدن سفارش و مدیریت معامله‌ای انجام
 * نمی‌شود — حتی اگر منبع قیمت لحظه‌ای (اسپات نمایشی) در آخر هفته عددی بدهد.
 */
object MarketHours {
    const val SUNDAY_OPEN_HOUR_UTC = 22

    fun isOpen(t: Long): Boolean = when (Tf.utcDowMonday0(t)) {
        5 -> false                                              // شنبه: تمام روز تعطیل
        6 -> (t % Tf.DAY_MS) >= SUNDAY_OPEN_HOUR_UTC * 3_600_000L  // یکشنبه: فقط از ۲۲:۰۰ UTC (سشن دوشنبه)
        else -> true                                            // دوشنبه تا جمعه
    }

    fun isClosed(t: Long): Boolean = !isOpen(t)

    // ── پنجرهٔ سفارش‌گیری (§18-26 تا §18-30 سند) ─────────────────────────────
    // ستاپ فقط از ۰۱:۳۰ تا ۲۰:۰۰ UTC مجاز به سفارش‌گیری و مسلح‌شدن است؛ رأس ۲۰:۰۰
    // همهٔ معاملات باز بسته و سفارش‌های معلق لغو می‌شوند (دلیل: پایان زمان) و رأس
    // ۰۱:۳۰ سفارش‌گیری دوباره طبق برنامه فعال می‌شود. خارج از این پنجره فقط
    // «سفارش‌گیری و مسلح‌شدن» غیرفعال است — ستاپ، نواحی و بقیهٔ قوانین طبق برنامه
    // ادامه دارند (§18-29)؛ ولی ناحیه‌ای که در این بازه باطل شود دیگر ستاپ/سفارش
    // نمی‌سازد و ستاپِ در جریان رویش همان شب خاتمه می‌یابد (§18-28).
    const val ORDER_START_MIN_UTC = 90        // ۰۱:۳۰ UTC
    const val ORDER_END_MIN_UTC = 20 * 60     // ۲۰:۰۰ UTC

    /** دقیقهٔ روز به وقت UTC (۰ تا ۱۴۳۹). */
    fun minuteOfDayUtc(t: Long): Int = ((t % Tf.DAY_MS) / 60_000L).toInt()

    /** آیا الان داخل پنجرهٔ مجاز سفارش‌گیری/مسلح‌شدن هستیم؟ [۰۱:۳۰، ۲۰:۰۰) UTC — §18-26 و §18-30. */
    fun isOrderWindow(t: Long): Boolean {
        val m = minuteOfDayUtc(t)
        return m >= ORDER_START_MIN_UTC && m < ORDER_END_MIN_UTC
    }

    /** از رأس ۲۰:۰۰ UTC به بعد: لحظهٔ «پایان زمان» — بستن همهٔ معاملات و لغو سفارش‌های معلق (§18-27). */
    fun isOrderTimeExit(t: Long): Boolean = minuteOfDayUtc(t) >= ORDER_END_MIN_UTC
}

/** پنج مود تایم‌فریمی (پیش‌فرض = مود ۱ : ساختار ۱ ساعته). */
class TfMode(
    val idx: Int,
    val name: String,
    val s: Int,
    val m: Int,
    val t1: Int,
    val t2: Int,
    val custom: Boolean = false
) {
    fun pretty(): String = "${Tf.label(s)} / ${Tf.label(m)} / ${Tf.label(t1)} / ${Tf.label(t2)}"
}

val TF_MODES: List<TfMode> = listOf(
    TfMode(1, "ساختار ۱ ساعته (پیش‌فرض)", Tf.H1, Tf.M15, Tf.M5, Tf.M1),
    TfMode(2, "هفتگی / روزانه / ۴ ساعته / ۱ ساعته", Tf.W1, Tf.D1, Tf.H4, Tf.H1),
    TfMode(3, "روزانه / ۴ ساعته / ۱ ساعته / ۱۵ دقیقه", Tf.D1, Tf.H4, Tf.H1, Tf.M15),
    TfMode(4, "۴ ساعته / ۱ ساعته / ۱۵ دقیقه / ۵ دقیقه", Tf.H4, Tf.H1, Tf.M15, Tf.M5),
    TfMode(5, "دلخواه (تایم‌فریم‌ها را خودتان انتخاب کنید)", Tf.H1, Tf.M15, Tf.M5, Tf.M1, custom = true)
)
