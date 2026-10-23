package com.alisport.goldpin

import android.os.Handler
import android.os.Looper
import android.content.Context
import android.widget.Toast
import com.alisport.goldpin.core.*
import com.alisport.goldpin.data.Feed
import com.alisport.goldpin.data.FeedException
import com.alisport.goldpin.data.Storage
import com.alisport.goldpin.util.Alerts
import com.alisport.goldpin.util.Fa
import com.alisport.goldpin.util.Log
import java.io.File
import java.util.concurrent.Executors

// ═══════════════════════════════════════════════════════════════════════════════
//  وضعیت سراسری اپ — موتور، کارگزار، کندل‌ها، تنظیمات فید و ذخیره/بازیابی
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * نشست فعال (طرح B).
 *
 * دو نشست **کاملاً جدا** هستند و هیچ‌وقت داده‌شان قاطی نمی‌شود:
 *  • [LIVE]     — دادهٔ فید شبکه، فایل `goldpin_state.json.gz`، سرویس زنده روشن.
 *  • [INTERNAL] — دیتاست M1 انتخاب‌شده (۱/۲/۳/۱۰ سال) جدا از APK و با نتیجهٔ مستقل هر بازه،
 *                 فید و سرویس زنده **خاموش**.
 *
 * دلیل جداسازی: «مرج کردن» دو فید در یک سری، سه مشکل واقعی می‌ساخت —
 *  ۱) اختلاف قیمت ~۰٫۱۶ دلار در نقطهٔ دوخت (پرش), ۲) اختلاف مقیاس حجم ~۲۵٬۰۰۰ برابر
 *  (کندلِ درز فوراً «ولوم زیاد» دیده می‌شد), ۳) انتقال باکس‌های ساخته‌شده روی نمونهٔ داخلی
 *  به سری زنده از طریق `zoneKeepKey`. با دو نشست جدا، هر سه غیرممکن است.
 */
enum class SessionMode(val fa: String) {
    LIVE("زنده"),
    INTERNAL("بک‌تست داخلی")
}

class AppState {

    val cfg = Settings()
    val engine = Engine(cfg)
    val broker = PaperBroker(cfg)

    /** کندل‌های نمایشی تایم‌فریم چارت فعلی (chartTfSec) */
    /**
     * فهرست کندل‌ها. به‌صورت «تعویض اتمیک» به‌روزرسانی می‌شود تا نخ رابط
     * هرگز فهرست نیمه‌ساخته نبیند (منشأ قبلی فریز و پرش).
     */
    @Volatile
    var candles: List<Candle> = emptyList()
        private set

    /** کندل‌های تایم‌فریم تریگر ۲ در مود انتخاب‌شده (cfg.tf2) که موتور و باکس‌ها در حافظه روی آن ثابت‌اند */
    @Volatile
    var modeCandles: List<Candle> = emptyList()
        private set

    /** کش آخرین سری ریزدانه (۱ دقیقه) برای تجمیع آفلاین هنگام بازگشت از تایم‌فریم‌های بالاتر به پایین‌تر */
    @Volatile
    private var fineCandles: List<Candle> = emptyList()

    /** کش کندل‌های دانلودشدهٔ فید به تفکیک تایم‌فریم (ثانیه) در حافظه */
    private val tfFeedCache = java.util.concurrent.ConcurrentHashMap<Int, List<Candle>>()

    /** تاریخچهٔ قدیمی تایم‌فریم ساختار؛ برای پیش‌بارگذاری توالی‌ها، بدون ساخت باکس بی‌دیتای تریگر۲. */
    @Volatile
    private var structureCandles: List<Candle> = emptyList()

    /** آیا دادهٔ مود فعلی از فید آنلاین دانلود شده است؟ */
    @Volatile
    private var modeFromFeed: Boolean = false

    // تنظیمات فید (اپ)
    var symbol: String = Feed.SYMBOL_DEFAULT
    var baseTfSec: Int = Tf.M1
    var chartTfSec: Int = Tf.M1
    var depth: Int = 3                 // ۱=کوتاه ، ۲=متوسط ، ۳=عمیق (نسخهٔ ۷: پیش‌فرض عمیق)
    var useSyntheticVolume: Boolean = true
    var livePollMs: Long = 10000L
    var liveRunning: Boolean = false
    var lastLivePrice: Double = Double.NaN
    var lastFeedAt: Long = 0L
    var lastFeedError: String? = null
    /**
     * تشخیص «چرا نتیجه صفر شد» — فقط وقتی پر می‌شود که آخرین بازسازی **هیچ سفارشی** نساخته
     * باشد ولی موتور ستاپ/HV داشته است. رشتهٔ کوتاه فارسی برای نمایش در بنر/تب معاملات.
     * (توسط [diagnoseEmptyRun] بعد از هر rebuild پر می‌شود.)
     */
    @Volatile var zeroRunReason: String? = null
    var lastPriceSource: String = "—"
    var autosaveEveryMs: Long = 120000L

    /** تعداد تیک‌های پشت‌سرهم ناموفق — سرویس با آن فاصلهٔ تیک را به‌صورت نمایی عقب می‌اندازد. */
    @Volatile var liveFailStreak: Int = 0

    /** چند تیک به‌خاطر «تیک قبلی هنوز در جریان» حذف شده‌اند (نشانهٔ کندی فید). */
    @Volatile var lastLiveSkips: Long = 0L

    /** آخرین بازپخش تاریخچه چند کندل داشت (که ساکت اجرا شد) — برای شفافیت در نوتیفیکیشن */
    @Volatile var lastLiveSilentBars: Int = 0

    /**
     * حالت بک‌تست کامل: آخرین کندل هم بسته حساب می‌شود (دادهٔ تاریخی).
     * در حالت لایو، آخرین کندل «باز» می‌ماند و فقط پس از بسته شدن پردازش می‌شود
     * (سفارش‌ها اما در همان لحظه با قیمت زنده پر می‌شوند).
     */
    var backtestFull: Boolean = true

    /**
     * کلید پیکربندیِ موتوری که باکس‌های ناحیهٔ فعلی با آن ساخته شده‌اند.
     * §4-5 سند: ناحیه‌های ابطال‌نشده باید نگهداری/فراخوانی شوند؛ ولی فقط وقتی که همان سلسله‌مراتب
     * تایم‌فریمی و همان استراتژی/پارامترهای پروفایل برقرار باشد — وگرنه باکس‌های مود قبلی بی‌معنا می‌شوند.
     */
    @Volatile
    private var zoneKeepKey: String = ""

    // ── نشست بک‌تست داخلی (طرح B) ─────────────────────────────────────────────
    /** نشست فعال؛ همهٔ مسیرهای ذخیره/بارگذاری و نگهبان‌های فید به آن نگاه می‌کنند. */
    @Volatile
    var sessionMode: SessionMode = SessionMode.LIVE
        private set

    /** بازه‌ای که کاربر برای بک‌تست انتخاب کرده؛ نتیجه‌های هر بازه جداگانه ذخیره می‌شوند. */
    @Volatile
    var selectedBacktestYears: Int = 1
        private set

    /** بازه‌ای که داده‌اش همین حالا در موتور/چارت بارگذاری شده است. */
    @Volatile
    var activeBacktestYears: Int = 1
        private set

    /** تنظیمات یا تایم‌فریم‌ها بعد از آخرین اجرای بک‌تست عوض شده‌اند ⇒ نتیجه کهنه است. */
    @Volatile
    var backtestStale: Boolean = false

    /** ۰..۱ پیشرفت اجرای بک‌تست؛ مقدار منفی = بی‌کار. */
    @Volatile
    var backtestProgress: Float = -1f

    @Volatile
    var backtestProgressText: String = ""

    /** خلاصهٔ آخرین اجرا/بارگذاری بک‌تست برای نمایش در تنظیمات. */
    @Volatile
    var backtestInfo: String = ""

    private fun zoneKey(): String =
        // sessionMode عمداً اول کلید است: باکس‌های بک‌تست داخلی هرگز نباید به نشست زنده
        // (یا برعکس) منتقل شوند — قیمت/حجم دو فید متفاوت است.
        "${sessionMode.name}/$symbol/${cfg.tfS}/${cfg.tfM}/${cfg.tf1}/${cfg.tf2}/${cfg.strategy}/${cfg.vpRows}/${cfg.vpVA}/" +
            "${cfg.vpSmooth}/${cfg.distMode}/${cfg.minZoneRows}/${cfg.clearUsedZones}"

    /** امضای پارامترهایی که «کهنه شدن» نتیجه را تعیین می‌کنند — برای هر [Settings]. */
    fun signatureOf(c: Settings): String =
        "$symbol/${c.tfS}/${c.tfM}/${c.tf1}/${c.tf2}/${c.strategy}/${c.vpRows}/${c.vpVA}/" +
            "${c.vpSmooth}/${c.distMode}/${c.minZoneRows}/${c.maxSetups}/${c.midInvalidClose}/" +
            "${c.hvScanFirstTouch}/${c.hvMarkFirstIncrease}/${c.minTPunits}/${c.maxSlUnits}/" +
            "${c.minSlUnits}/${c.slBufTicks}/${c.entryBufTicks}/${c.slipTicks}/${c.mintick}/${c.maxLeverage}/" +
            "${c.useRiskPct}/${c.riskPct}/${c.initialEquity}/${c.mmMode}/${c.contractSize}/${c.tpUnits}/" +
            // کلیدهای تریگر ۲ معکوس و کارمزد هم باید نتیجه را کهنه کنند (بخش تطبیق این‌ها را عوض می‌کند)
            "${c.trigger2Fade}/${c.trigger2TargetR}/${c.minSlCostMult}/${c.cmsnAuto}/${c.cmsnManual}/" +
            "${c.minLotAuto}/${c.minLotManual}/${c.divAuto}/${c.divManual}/${c.mixedGrowthPct}"

    /** امضای تنظیمات فعلی. */
    fun backtestSignature(): String = signatureOf(cfg)

    /** نتیجهٔ نشست داخلی علاوه بر تنظیمات باید به بازهٔ دیتاست هم وابسته باشد. */
    private fun backtestResultSignature(): String = "${backtestSignature()}/datasetYears=$activeBacktestYears"


    /**
     * کندل‌های خام M1 نشست داخلی — مرجع تجمیع برای هر تایم‌فریمی که کاربر انتخاب کند.
     * با تغییر مود/استراتژی، سری تریگر۲ از همین سری دوباره ساخته می‌شود تا تغییر تنظیمات
     * واقعاً روی بک‌تست اثر بگذارد (باگ «بک‌تست تغییر مودها را نمی‌گیرد»).
     */
    @Volatile private var internalRaw: List<Candle> = emptyList()

    /** در حال محاسبهٔ سنگین (اجرای موتور/دانلود) — رابط پیام «در حال محاسبه…» نشان می‌دهد */
    @Volatile var busy: Boolean = false
    @Volatile var busyText: String = ""
    /** ورود خودکار به تمام‌صفحه در تب چارت */
    var autoFullscreen: Boolean = false
    /** تنظیمات نمایش تک‌تک اجزای چارت (باکس‌ها، لیبل‌ها، خطوط و اجزای پایه به تفکیک نوع) */
    var showVolume: Boolean = true
    var showZeroVolumeHighlight: Boolean = true
    var showZones: Boolean = true
    var showActiveStructureBoxes: Boolean = true
    var showDeletedStructureBoxes: Boolean = true
    var showRejectedStructureBoxes: Boolean = true
    var showTrigger1Boxes: Boolean = true
    var showTrigger2Boxes: Boolean = true
    var showBoxLabels: Boolean = true

    var showMarkers: Boolean = true
    var showPinMarkers: Boolean = true
    var showTrendMarkers: Boolean = true
    var showTouchMarkers: Boolean = true
    var showMidMarkers: Boolean = true
    var showTrigger1Markers: Boolean = true
    var showTrigger2Markers: Boolean = true
    var showTradeMarkers: Boolean = true
    /** نتیجهٔ معاملات روی چارت (سود سبز / ضرر قرمز / سر‌به‌سر خط‌چین)؛ پیش‌فرض خاموش. */
    var showTradeResults: Boolean = false
    var showInvalidationMarkers: Boolean = true
    var showMarkerText: Boolean = true

    var showTradeLines: Boolean = true
    var showSetupZoneLines: Boolean = true
    var showMidLine: Boolean = true
    var showTrigger1Lines: Boolean = true
    var showTrigger2Lines: Boolean = true
    var showEntryLine: Boolean = true
    var showSlLine: Boolean = true
    var showTpStepsLines: Boolean = true
    var showTpFinalLine: Boolean = true
    var showPocLine: Boolean = true

    var showGrid: Boolean = true
    var showLastPriceLine: Boolean = true
    var showWatermark: Boolean = true
    var showLegend: Boolean = true
    var showStatusBadges: Boolean = true
    var showEventsPanel: Boolean = true

    private var appCtx: Context? = null
    @Volatile private var lastSyntheticVolumeNoticeAt: Long = 0L

    /**
     * نخ «تغییر وضعیت» — موتور، کارگزار و کندل‌ها فقط اینجا عوض می‌شوند (اجرای سریالی).
     *
     * ⚠ این executor تک‌نخی و صفش **بدون سقف** است. هر کاری که روی شبکه معطل بماند
     * و اینجا گذاشته شود، همهٔ کارهای بعدی (ذخیره، بازسازی موتور، خروجی لاگ…) را
     * پشت سر خود معطل می‌کند ⇒ رابط «فریز» به نظر می‌رسد. پس هیچ I/O شبکه‌ای
     * نباید روی [io] برود — برای شبکه از [net] استفاده می‌شود.
     */
    val io = Executors.newSingleThreadExecutor()

    /**
     * نخ «شبکه» — جدا از [io]. اگر فید کند یا قطع باشد، فقط این نخ معطل می‌شود
     * و کارهای رابط (ذخیره/بازسازی/خروجی لاگ) روی [io] بدون معطلی انجام می‌شوند.
     */
    val net = Executors.newSingleThreadExecutor { r ->
        Thread(r, "goldpin-net").apply { isDaemon = true }
    }

    /**
     * دروازهٔ تیک لایو: حداکثر **یک** تیک در جریان. تیک‌های اضافی حذف می‌شوند
     * (idempotent‌اند — تیک بعدی همان دنبالهٔ تازه را دوباره می‌گیرد).
     * بدون این دروازه، با فید کند صف [io] بی‌نهایت رشد می‌کرد.
     */
    val liveGate = com.alisport.goldpin.util.CoalescingGate("live")

    val main = Handler(Looper.getMainLooper())
    val listeners = ArrayList<() -> Unit>()

    // موتور در نخ IO بازسازی می‌شود و رابط در نخ اصلی می‌خواند.
    //
    // ⚠ خواندن وضعیت **هرگز** نباید قفل بگیرد. `stateLock` گاهی صدها میلی‌ثانیه نگه داشته
    // می‌شود (ساخت JSON ذخیرهٔ خودکار روی ۴۰٬۰۰۰ کندل ≈ ۲۵۰ms روی JVM دسکتاپ، روی گوشی بیشتر)
    // و اگر `uiSnapshot()` هم پشت همان قفل منتظر بماند، رابط در هر ذخیرهٔ دوره‌ای فریز می‌شود.
    // پس: قفل فقط برای *نوشتن* است؛ بعد از هر تغییر، یک snapshot تغییرناپذیر ساخته و به‌صورت
    // اتمیک (@Volatile) منتشر می‌شود و رابط همان را بدون هیچ انتظار می‌خواند.
    private val stateLock = Any()
    data class UiSnapshot(
        val candles: List<Candle>,
        val zones: List<ZoneBox>,
        val markers: List<Marker>,
        val setups: List<Setup>,
        val events: List<String>,
        val orders: List<Order>,
        val trades: List<Trade>,
        val openTrade: Trade?,
        val pendingOrder: Order?,
        val openTrades: List<Trade>,
        val pendingOrders: List<Order>,
        val lastPocPx: Double,
        val lastEntryPx: Double,
        val lastSlPx: Double,
        val lastTpPx: Double,
        val lastResult: String,
        val t1Zones: List<ZoneBox> = emptyList(),
        val t2Zones: List<ZoneBox> = emptyList()
    )

    @Volatile
    private var snap: UiSnapshot = UiSnapshot(
        emptyList(), emptyList(), emptyList(), emptyList(), emptyList(),
        emptyList(), emptyList(), null, null, emptyList(), emptyList(),
        Double.NaN, Double.NaN, Double.NaN, Double.NaN, "-",
        emptyList(), emptyList()
    )

    /** ساخت و انتشار اتمیک snapshot — باید بلافاصله بعد از هر تغییر وضعیت صدا زده شود. */
    private fun publishSnapshot() {
        // دادهٔ ده‌ساله primitive-backed و پس از بارگذاری تغییرناپذیر است؛ کپی کامل آن
        // به List<Candle> چندصد مگابایت موقت مصرف می‌کند. برای بازه‌های دیگر همان snapshot کپی می‌ماند.
        val candleSnapshot = if (sessionMode == SessionMode.INTERNAL && activeBacktestYears == 10) candles else candles.toList()
        snap = UiSnapshot(
            candleSnapshot, engine.zones.toList(), engine.markers.toList(), engine.setups.toList(),
            engine.events.toList(), broker.orders.toList(), broker.trades.toList(),
            broker.openTrade, broker.pendingOrder, broker.openTrades, broker.pendingOrders,
            engine.lastPocPx, engine.lastEntryPx,
            engine.lastSlPx, engine.lastTpPx, engine.lastResult,
            engine.t1Zones.toList(), engine.t2Zones.toList()
        )
    }

    /** بدون قفل — رابط هرگز برای خواندن وضعیت منتظر موتور نمی‌ماند. */
    fun uiSnapshot(): UiSnapshot = snap
    fun tradesSnapshot(): List<Trade> = snap.trades
    fun ordersSnapshot(): List<Order> = snap.orders

    init {
        engine.broker = broker
        broker.engine = engine
        engine.barCommitHook = { cd -> broker.onBar(cd, live = false) }
        wireLogging()
    }

    /** راه‌اندازی لاگر و هشدارها + وصل کردن آن‌ها به موتور و کارگزار */
    fun initApp(ctx: Context) {
        appCtx = ctx.applicationContext
        // ⚠ نسخهٔ لاگ باید با versionName واقعی یکی باشد؛ این رشته دستی و جامانده بود
        // («1.4.21») و تشخیص نسخهٔ کاربر از روی لاگ را بی‌اعتبار می‌کرد.
        Log.init(appCtx!!, APP_VERSION)
        Alerts.init(appCtx!!)
        val p = appCtx!!.getSharedPreferences("goldpin", Context.MODE_PRIVATE)
        // نشست ذخیره‌شدهٔ کاربر (زنده/بک‌تست داخلی) — تا بعد از بستن و باز کردن اپ،
        // کاربر دوباره داخل همان نشستی که بوده بالا بیاید.
        sessionMode = try {
            SessionMode.valueOf(p.getString("session_mode", SessionMode.LIVE.name) ?: SessionMode.LIVE.name)
        } catch (e: Exception) { SessionMode.LIVE }
        selectedBacktestYears = p.getInt("backtest_years", 1)
            .takeIf { years -> Feed.BACKTEST_DATASETS.any { it.years == years } } ?: 1
        activeBacktestYears = selectedBacktestYears
        // مهاجرت ۱٫۱ → ۱٫۲: تمام‌صفحهٔ خودکار دیگر پیش‌فرض نیست؛ تنظیم قدیمی true را یک‌بار خاموش کن.
        val uiVersion = p.getInt("ui_prefs_version", 0)
        autoFullscreen = if (uiVersion < 2) false else p.getBoolean("auto_fullscreen", false)
        if (uiVersion < 2) p.edit().putBoolean("auto_fullscreen", false).putInt("ui_prefs_version", 2).apply()
        Feed.connectTimeoutMs = p.getInt("feed_connect_timeout_ms", 8000).coerceIn(1000, 120000)
        Feed.readTimeoutMs = p.getInt("feed_read_timeout_ms", 12000).coerceIn(1000, 120000)
        showVolume = p.getBoolean("cv_show_volume", true)
        showZeroVolumeHighlight = p.getBoolean("cv_show_zero_vol", true)
        showZones = p.getBoolean("cv_show_zones", true)
        showActiveStructureBoxes = p.getBoolean("cv_show_active_s_boxes", true)
        showDeletedStructureBoxes = p.getBoolean("cv_show_deleted_s_boxes", true)
        showRejectedStructureBoxes = p.getBoolean("cv_show_rejected_s_boxes", true)
        showTrigger1Boxes = p.getBoolean("cv_show_t1_boxes", true)
        showTrigger2Boxes = p.getBoolean("cv_show_t2_boxes", true)
        showBoxLabels = p.getBoolean("cv_show_box_labels", true)
        showMarkers = p.getBoolean("cv_show_markers", true)
        showPinMarkers = p.getBoolean("cv_show_pin_markers", true)
        showTrendMarkers = p.getBoolean("cv_show_trend_markers", true)
        showTouchMarkers = p.getBoolean("cv_show_touch_markers", true)
        showMidMarkers = p.getBoolean("cv_show_mid_markers", true)
        showTrigger1Markers = p.getBoolean("cv_show_t1_markers", true)
        showTrigger2Markers = p.getBoolean("cv_show_t2_markers", true)
        showTradeMarkers = p.getBoolean("cv_show_trade_markers", true)
        showTradeResults = p.getBoolean("cv_show_trade_results", false)
        showInvalidationMarkers = p.getBoolean("cv_show_inv_markers", true)
        showMarkerText = p.getBoolean("cv_show_marker_text", true)
        showTradeLines = p.getBoolean("cv_show_trade_lines", true)
        showSetupZoneLines = p.getBoolean("cv_show_setup_zone_lines", true)
        showMidLine = p.getBoolean("cv_show_mid_line", true)
        showTrigger1Lines = p.getBoolean("cv_show_t1_lines", true)
        showTrigger2Lines = p.getBoolean("cv_show_t2_lines", true)
        showEntryLine = p.getBoolean("cv_show_entry_line", true)
        showSlLine = p.getBoolean("cv_show_sl_line", true)
        showTpStepsLines = p.getBoolean("cv_show_tp_steps_lines", true)
        showTpFinalLine = p.getBoolean("cv_show_tp_final_line", true)
        showPocLine = p.getBoolean("cv_show_poc_line", true)
        showGrid = p.getBoolean("cv_show_grid", true)
        showLastPriceLine = p.getBoolean("cv_show_last_price_line", true)
        showWatermark = p.getBoolean("cv_show_watermark", true)
        showLegend = p.getBoolean("cv_show_legend", true)
        showStatusBadges = p.getBoolean("cv_show_status_badges", true)
        showEventsPanel = p.getBoolean("cv_show_events_panel", true)
        Feed.syntheticVolumeWarning = { count -> announceSyntheticVolume(count) }
        Log.i(Log.CAT_APP, "AppState راه‌اندازی شد",
            "autoFullscreen=$autoFullscreen logEnabled=${Log.enabled} alerts=${Alerts.enabled}")
        wireLogging()
    }

    fun persistPrefs(ctx: Context) {
        ctx.getSharedPreferences("goldpin", Context.MODE_PRIVATE).edit()
            .putBoolean("auto_fullscreen", autoFullscreen)
            .putInt("feed_connect_timeout_ms", Feed.connectTimeoutMs)
            .putInt("feed_read_timeout_ms", Feed.readTimeoutMs)
            .putBoolean("cv_show_volume", showVolume)
            .putBoolean("cv_show_zero_vol", showZeroVolumeHighlight)
            .putBoolean("cv_show_zones", showZones)
            .putBoolean("cv_show_active_s_boxes", showActiveStructureBoxes)
            .putBoolean("cv_show_deleted_s_boxes", showDeletedStructureBoxes)
            .putBoolean("cv_show_rejected_s_boxes", showRejectedStructureBoxes)
            .putBoolean("cv_show_t1_boxes", showTrigger1Boxes)
            .putBoolean("cv_show_t2_boxes", showTrigger2Boxes)
            .putBoolean("cv_show_box_labels", showBoxLabels)
            .putBoolean("cv_show_markers", showMarkers)
            .putBoolean("cv_show_pin_markers", showPinMarkers)
            .putBoolean("cv_show_trend_markers", showTrendMarkers)
            .putBoolean("cv_show_touch_markers", showTouchMarkers)
            .putBoolean("cv_show_mid_markers", showMidMarkers)
            .putBoolean("cv_show_t1_markers", showTrigger1Markers)
            .putBoolean("cv_show_t2_markers", showTrigger2Markers)
            .putBoolean("cv_show_trade_markers", showTradeMarkers)
            .putBoolean("cv_show_trade_results", showTradeResults)
            .putBoolean("cv_show_inv_markers", showInvalidationMarkers)
            .putBoolean("cv_show_marker_text", showMarkerText)
            .putBoolean("cv_show_trade_lines", showTradeLines)
            .putBoolean("cv_show_setup_zone_lines", showSetupZoneLines)
            .putBoolean("cv_show_mid_line", showMidLine)
            .putBoolean("cv_show_t1_lines", showTrigger1Lines)
            .putBoolean("cv_show_t2_lines", showTrigger2Lines)
            .putBoolean("cv_show_entry_line", showEntryLine)
            .putBoolean("cv_show_sl_line", showSlLine)
            .putBoolean("cv_show_tp_steps_lines", showTpStepsLines)
            .putBoolean("cv_show_tp_final_line", showTpFinalLine)
            .putBoolean("cv_show_poc_line", showPocLine)
            .putBoolean("cv_show_grid", showGrid)
            .putBoolean("cv_show_last_price_line", showLastPriceLine)
            .putBoolean("cv_show_watermark", showWatermark)
            .putBoolean("cv_show_legend", showLegend)
            .putBoolean("cv_show_status_badges", showStatusBadges)
            .putBoolean("cv_show_events_panel", showEventsPanel)
            .apply()
    }

    fun applyChartVis(cv: com.alisport.goldpin.ui.ChartView) {
        cv.showVolume = showVolume
        cv.showZeroVolumeHighlight = showZeroVolumeHighlight
        cv.showZones = showZones
        cv.showActiveStructureBoxes = showActiveStructureBoxes
        cv.showDeletedStructureBoxes = showDeletedStructureBoxes
        cv.showRejectedStructureBoxes = showRejectedStructureBoxes
        cv.showTrigger1Boxes = showTrigger1Boxes
        cv.showTrigger2Boxes = showTrigger2Boxes
        cv.showBoxLabels = showBoxLabels
        cv.showMarkers = showMarkers
        cv.showPinMarkers = showPinMarkers
        cv.showTrendMarkers = showTrendMarkers
        cv.showTouchMarkers = showTouchMarkers
        cv.showMidMarkers = showMidMarkers
        cv.showTrigger1Markers = showTrigger1Markers
        cv.showTrigger2Markers = showTrigger2Markers
        cv.showTradeMarkers = showTradeMarkers
        cv.showTradeResults = showTradeResults
        cv.showInvalidationMarkers = showInvalidationMarkers
        cv.showMarkerText = showMarkerText
        cv.showTradeLines = showTradeLines
        cv.showSetupZoneLines = showSetupZoneLines
        cv.showMidLine = showMidLine
        cv.showTrigger1Lines = showTrigger1Lines
        cv.showTrigger2Lines = showTrigger2Lines
        cv.showEntryLine = showEntryLine
        cv.showSlLine = showSlLine
        cv.showTpStepsLines = showTpStepsLines
        cv.showTpFinalLine = showTpFinalLine
        cv.showPocLine = showPocLine
        cv.showGrid = showGrid
        cv.showLastPriceLine = showLastPriceLine
        cv.showWatermark = showWatermark
        cv.showLegend = showLegend
        cv.showStatusBadges = showStatusBadges
    }

    @Synchronized
    private fun announceSyntheticVolume(candleCount: Int) {
        val now = System.currentTimeMillis()
        if (now - lastSyntheticVolumeNoticeAt < 10 * 60_000L) return
        lastSyntheticVolumeNoticeAt = now
        val ctx = appCtx ?: return
        val body = "این داده حجم واقعی ندارد؛ برای ادامه، حجم تقریبی از دامنهٔ کندل‌ها ساخته شد (${Fa.d(candleCount.toString())} کندل)."
        Alerts.fire(ctx, AlertKind.FEED, "حجم تقریبی جایگزین شد", body)
        main.post { Toast.makeText(ctx, body, Toast.LENGTH_LONG).show() }
    }

    /**
     * لاگر و هشدار را به موتور، کارگزار و همهٔ بخش‌ها وصل می‌کند.
     * اگر لاگر خاموش باشد، قلاب‌ها null می‌شوند تا هیچ هزینه‌ای تحمیل نشود.
     */
    fun wireLogging() {
        if (Log.enabled) {
            engine.logSink = { cat, msg, data -> Log.i(cat, msg, data) }
            broker.logSink = { cat, msg, data -> Log.i(cat, msg, data) }
        } else {
            engine.logSink = null
            broker.logSink = null
        }
        val c = appCtx
        engine.alertSink = { kind, title, body -> Alerts.fire(c, kind, title, body) }
        broker.alertSink = { kind, title, body -> Alerts.fire(c, kind, title, body) }
    }

    fun onChange(f: () -> Unit) { listeners.add(f) }

    fun notifyUi() {
        val run = Runnable {
            // هر شنونده جدا محافظت می‌شود تا یک خطا کل رابط را از کار نیندازد
            for (f in ArrayList(listeners)) {
                try { f() } catch (t: Throwable) { Log.e(Log.CAT_UI, "خطا در شنوندهٔ رابط", t) }
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) run.run() else main.post(run)
    }

    fun offChange(f: () -> Unit) { listeners.remove(f) }

    // ── تنظیمات و بازسازی ──────────────────────────────────────────────────────
    /** بازسازی غیرهمزمان؛ تغییرات تنظیمات هرگز نخ رابط را قفل نمی‌کنند. */
    fun rebuildAsync() {
        // ⚠ در نشست بک‌تست داخلی، هر تغییر تنظیم یعنی اجرای دوبارهٔ موتور روی کل بازهٔ انتخابی
        // (در ۱۰ سال حدود ۳٫۴ میلیون کندل). پس به‌جای بازسازی خودکار، نتیجه «کهنه»
        // علامت می‌خورد و دکمهٔ «اجرای مجدد بک‌تست» روشن می‌شود.
        if (sessionMode == SessionMode.INTERNAL) {
            backtestStale = true
            lastFeedError = null
            Log.i(Log.CAT_ENGINE, "تنظیمات در نشست بک‌تست عوض شد — نتیجه کهنه است", "منتظر اجرای مجدد")
            notifyUi()
            return
        }
        busy = true
        busyText = "در حال بازسازی موتور…"
        notifyUi()
        io.execute {
            try {
                rebuild()
            } catch (e: Throwable) {
                Log.e(Log.CAT_ENGINE, "بازسازی موتور ناموفق بود", e)
                lastFeedError = "خطای بازسازی موتور: ${e.message}"
            } finally {
                busy = false
                busyText = ""
                notifyUi()
            }
        }
    }

    /**
     * بعد از تغییر تنظیمات: موتور و کارگزار از صفر روی دادهٔ مود انتخاب‌شده (modeCandles) اجرا می‌شوند.
     *
     * کارگزار کاغذی تابعی قطعی از (کندل‌ها + تنظیمات) است؛ پس هر بازسازی **همیشه** آن را هم از صفر می‌کند.
     * (نسخه‌های قبل پارامتر `clearOrders=false` داشتند که موتور را از صفر بازپخش می‌کرد ولی کارگزار را پاک
     * نمی‌کرد ⇒ سفارش/معامله/موجودی/Lost دوبار شمرده می‌شد و هر بار عدد دیگری درمی‌آمد.)
     */
    fun rebuild() {
        if (sessionMode == SessionMode.INTERNAL) {
            // در نشست داخلی، بازسازی خودکار ممنوع است؛ نتیجه فقط با اجرای کامل روی
            // دیتاست انتخاب‌شده (runInternalBacktest) ساخته می‌شود.
            backtestStale = true
            Log.w(Log.CAT_ENGINE, "درخواست بازسازی در نشست داخلی رد شد — نتیجه کهنه علامت خورد")
            notifyUi()
            return
        }
        synchronized(stateLock) {
        val execCandles = if (modeCandles.isNotEmpty()) modeCandles else candles
        // ── گارد «سری نامعتبر» ──────────────────────────────────────────────────
        // اگر سری مود از تایم‌فریم مود **درشت‌تر** باشد (مثلاً بعد از تغییر مود، سری H1
        // مانده و مود M1 شده)، اجرای موتور روی آن نه‌فقط بی‌معنا بلکه مخرب است: کارگزار
        // reset می‌شود و نتیجهٔ «صفر ستاپ/صفر سفارش» منتشر می‌شود و همان روی صفحه می‌ماند.
        // در این حالت هیچ‌چیز بازنویسی نمی‌شود؛ نتیجهٔ معتبر قبلی سر جایش می‌ماند و
        // پیام «منتظر تاریخچهٔ مود جدید» نشان داده می‌شود (دانلود، خودش بازسازی می‌کند).
        if (execCandles.isNotEmpty() && !seriesFitsMode(execCandles)) {
            val st = com.alisport.goldpin.core.SeriesPick.stepOf(execCandles)
            Log.w(Log.CAT_ENGINE, "بازسازی متوقف شد — سری مود با تایم‌فریم مود نمی‌خواند",
                "سری=${execCandles.size} کندل با فاصلهٔ ${st}s · tf2=${com.alisport.goldpin.core.Tf.label(cfg.tf2)} " +
                    "· نتیجهٔ قبلی دست‌نخورده ماند")
            lastFeedError = "دادهٔ ${com.alisport.goldpin.core.Tf.label(cfg.tf2)} برای این مود در حافظه نیست — منتظر تاریخچه"
            notifyUi()
            return
        }
        val t0 = System.currentTimeMillis()
        // §4-5 سند: ناحیه‌های ابطال‌نشده باید ثبت/نگهداری شوند و بعد از بازسازی موتور هم
        // دوباره فراخوانی شوند. باکس‌هایی که پیش از پنجرهٔ دادهٔ تازه ساخته شده‌اند از خودِ
        // داده قابل بازسازی نیستند، پس همین‌جا (پیش از reset) نگه داشته می‌شوند.
        // اگر سلسله‌مراتب تایم‌فریمی/استراتژی/پارامترهای پروفایل عوض شده باشد، باکس‌های مود
        // قبلی معنا ندارند و بازگردانده نمی‌شوند.
        val key = zoneKey()
        val keepSameConfig = key == zoneKeepKey
        val keepZones = if (keepSameConfig) ArrayList(engine.zones) else ArrayList()
        val keepT1 = if (keepSameConfig) ArrayList(engine.t1Zones) else ArrayList()
        val keepT2 = if (keepSameConfig) ArrayList(engine.t2Zones) else ArrayList()
        val keepSetups = if (keepSameConfig) ArrayList(engine.setups) else ArrayList()
        val keepZoneSeq = engine.zoneSeq
        zoneKeepKey = key
        Log.i(Log.CAT_ENGINE, "شروع اجرای مجدد موتور",
            "کندل‌مود=${execCandles.size} مود=${cfg.tfMode} (tf2=${com.alisport.goldpin.core.Tf.label(cfg.tf2)}) چارت=${com.alisport.goldpin.core.Tf.label(chartTfSec)} بک‌تست‌کامل=$backtestFull")
        engine.reset()
        engine.broker = broker
        broker.engine = engine
        broker.reset()
        engine.barCommitHook = { cd -> broker.onBar(cd, live = false) }
        val prev = Alerts.inBacktest
        Alerts.inBacktest = true
        var carried = 0
        try {
            if (execCandles.isNotEmpty()) {
                val structureHistory = availableStructureHistory(cfg.tfS)
                // هشدار «عمق ساختار»: اگر تاریخچهٔ تایم‌فریم ساختار فقط چند کندل باشد (مثلاً
                // تغییر «ساختار» به W1 روی دادهٔ یک‌ماهه)، نتیجه کم‌عمق و بی‌ستاپ می‌شود.
                // این پیام فقط راهنماست؛ نتیجهٔ محاسبه‌شده دست نمی‌خورد.
                if (structureHistory.size in 1..7) {
                    lastFeedError = "تاریخچهٔ ${com.alisport.goldpin.core.Tf.label(cfg.tfS)} کم است " +
                        "(${Fa.d(structureHistory.size.toString())} کندل) — برای نتیجهٔ کامل، ↻ (بارگذاری مجدد) را بزنید"
                    Log.w(Log.CAT_ENGINE, "تاریخچهٔ ساختار کوتاه است",
                        "tfS=${com.alisport.goldpin.core.Tf.label(cfg.tfS)} کندل=${structureHistory.size}")
                }
                val seeded = engine.seedStructureHistory(structureHistory, execCandles.first().t)
                if (seeded > 0) {
                    Log.i(Log.CAT_ENGINE, "توالی‌های تاریخی ساختار پیش‌بارگذاری شد",
                        "کندل ساختار=${Fa.d(seeded.toString())} شروع=${Tf.label(cfg.tfS)} ${structureHistory.firstOrNull()?.t ?: 0L}")
                }
                engine.feed(execCandles, lastIsClosed = backtestFull)
                // بازگرداندن ناحیه‌های ابطال‌نشدهٔ بیرونِ این پنجره (§4-5 سند)
                carried = engine.carryOverZones(
                    keepZones, keepT1, keepT2, execCandles.first().t, keepZoneSeq,
                    prevSetups = keepSetups, currentCandles = execCandles
                )
                val latestClosedIndex = engine.processed - 1
                val latestClosed = execCandles.getOrNull(latestClosedIndex)
                if (latestClosed != null) {
                    engine.ensureCurrentTrendMarker(latestClosed.t, latestClosed.c, latestClosedIndex)
                }
            }
        } finally {
            // ⚠ بدون finally، هر استثنا داخل feed این پرچم را برای همیشه true می‌گذاشت
            // و از آن لحظه به بعد هیچ بنر/نوتیفیکیشن/صدایی پخش نمی‌شد.
            Alerts.inBacktest = prev
        }
        engine.broker = broker
        Log.i(Log.CAT_ENGINE, "اجرای موتور تمام شد",
            "مدت=${System.currentTimeMillis() - t0}ms پردازش‌شده=${engine.processed} ستاپ=${engine.cnt.setup} برخورد=${engine.cnt.touch} " +
                "میانی=${engine.cnt.mid} ولوم‌کم=${engine.cnt.lv} ولوم‌زیاد=${engine.cnt.hv} ورود=${engine.cnt.entry} پایان=${engine.cnt.done} " +
                "باکس=${engine.zones.size} (بازگردانده‌شده=${Fa.d(carried.toString())}) سفارش=${broker.orders.size} معامله=${broker.trades.size} موجودی=${Fa.n(broker.balance, 2)}")
        diagnoseEmptyRun()
        publishSnapshot()
        notifyUi()
        }
    }

    /**
     * چرا آخرین بازسازی «صفر سفارش» داد؟ (باگ گزارش‌شده: «مود را عوض می‌کنم همهٔ معاملات صفر می‌شود»)
     *
     * سه علت واقعی که در لاگ کاربر دیده شد، هر سه با یک عدد قابل تشخیص‌اند:
     *  ۱) فیلتر کارمزد (§۵-۳): فاصلهٔ حد ضرر از «ضریب × هزینه» کمتر است. اگر میانهٔ فاصله‌ها از
     *     آستانه کمتر باشد، **هیچ** سفارشی ثبت نمی‌شود — بی‌صدا و برای همیشه.
     *  ۲) پنجرهٔ سفارش‌گیری (۰۱:۳۰–۲۰:۰۰ UTC): تأییدهای HV همه بیرون پنجره رد شده‌اند.
     *  ۳) سری دادهٔ ناقص/نامعتبر (که در ۱٫۴٫۳۹ با گاردهای سری پوشش داده شد).
     * تشخیص فقط **گزارش** است؛ هیچ تصمیمی را عوض نمی‌کند.
     */
    private fun diagnoseEmptyRun() {
        val orders = broker.orders.size
        val st = engine.cnt
        val median = engine.slDistMedian()
        val thr = engine.slCostThreshold()
        val parts = ArrayList<String>()
        if (orders == 0) {
            if (cfg.minSlCostMult > 0.0 && thr > 0.0 && st.slCostBlocked > 0 &&
                !median.isNaN() && median < thr) {
                parts.add(
                    "فیلتر کارمزد: میانهٔ فاصلهٔ حد ضرر ${Fa.n(median, 2)} از آستانه ${Fa.n(thr, 2)} " +
                    "(=${Fa.n(cfg.minSlCostMult, 0)}× هزینهٔ ${Fa.n(engine.slCostPerUnit(), 2)}) کمتر است ⇒ هیچ سفارشی ثبت نشد"
                )
            }
            if (st.windowBlocked > 0) {
                parts.add("${Fa.d(st.windowBlocked.toString())} مسلح‌شدن بیرون پنجرهٔ سفارش‌گیری (۰۱:۳۰–۲۰:۰۰ UTC) رد شد")
            }
            if (st.slNBlocked > 0) {
                parts.add("${Fa.d(st.slNBlocked.toString())} سفارش با سقف حد ضرر N=${Fa.n(cfg.maxSlUnits, 2)} رد شد")
            }
            if (st.slMinBlocked > 0) {
                parts.add("${Fa.d(st.slMinBlocked.toString())} سفارش با حداقل فاصلهٔ حد ضرر ${Fa.n(cfg.minSlUnits, 2)} رد شد")
            }
            if (engine.processed == 0) parts.add("هیچ کندلی به موتور نرسید")
        }
        val reason = if (parts.isEmpty()) null else parts.joinToString(" · ")
        zeroRunReason = reason
        if (reason != null) {
            Log.w(Log.CAT_ENGINE, "نتیجهٔ بازسازی صفر سفارش دارد", reason)
        }
    }

    private fun aggregateBestAvailable(targetTfSec: Int): List<Candle> {
        if (targetTfSec <= 0) return emptyList()
        // ترتیب اولویت منابع: دادهٔ ریز محلی (CSV/نمونه) → سری مود → دادهٔ ریز → سری چارت.
        // ⚠ هیچ‌کدام «اجباری» نیست: اگر هیچ منبعی از تایم‌فریم هدف ریزتر نبود، خروجی خالی است.
        // (قبلاً همین‌جا آخرین گزینه‌ها هر سری‌ای — حتی درشت‌تر — را برمی‌گرداندند و همان
        //  باعث می‌شد بعد از تغییر مود، دادهٔ مود قبلی به‌عنوان دادهٔ مود جدید به موتور برود.)
        val sources = if (!modeFromFeed) listOf(fineCandles, modeCandles, candles)
                      else listOf(modeCandles, fineCandles, candles)
        val src = com.alisport.goldpin.core.SeriesPick.firstUsable(targetTfSec, sources) ?: return emptyList()
        val srcStep = com.alisport.goldpin.core.SeriesPick.stepOf(src)
        val agg = if (com.alisport.goldpin.core.SeriesPick.sameTf(srcStep, targetTfSec.toLong())) src
                  else Feed.aggregate(src, targetTfSec)
        return if (useSyntheticVolume) Feed.synthesizeVolumeIfMissing(agg) else agg
    }

    /**
     * ضریب پیشنهادی برای «فیلتر کارمزد» از دادهٔ همین اجرا.
     *
     * اگر فیلتر همهٔ سفارش‌ها را رد کرده باشد (میانهٔ فاصلهٔ حد ضرر < آستانه)، این تابع ضریبی
     * می‌دهد که آستانه‌اش ۸۰٪ میانهٔ فاصلهٔ واقعی باشد ⇒ بخش عمدهٔ سفارش‌ها دوباره مجاز می‌شوند
     * بدون اینکه فیلتر بی‌اثر شود. NaN = داده‌ای برای پیشنهاد نیست.
     */
    fun suggestedCostMult(): Double {
        val median = engine.slDistMedian()
        val cost = engine.slCostPerUnit()
        if (median.isNaN() || median <= 0.0 || cost <= 0.0) return Double.NaN
        val k = median * 0.8 / cost
        // حداقل ۱ (کمتر از یک یعنی «فیلتر عملاً خاموش»)؛ با یک رقم اعشار.
        return Math.round(maxOf(1.0, k) * 10.0) / 10.0
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  بخش تطبیق — پارامترهای آزموده‌شده روی دادهٔ واقعی یک‌ساله
    //
    //  چرا این بخش لازم است: سه کلید (استراتژی، مود مالی §۲۰، تریگر ۲ معکوس) هر کدام
    //  «بهترین» پارامتر متفاوتی می‌خواهند و مهم‌تر از همه، **کارمزد واقعی** آستانه‌ها را
    //  جابه‌جا می‌کند. با کارمزد ۰٫۷ دلار، پیش‌فرض‌های سند ۷ (ضریب ۶ ⇒ آستانهٔ ۴٫۲ در برابر
    //  سقف N=۳) عملاً **همهٔ** سفارش‌ها را رد می‌کنند؛ و در مود خاموش که کارمزد کسر نمی‌شود،
    //  معامله‌های کم‌فاصله (R کوچک) با هزینهٔ واقعی بازنده‌اند.
    //
    //  عددهای هر پیش‌تنظیم از جاروب پارامتر روی همان ۳۵۱٬۸۹۴ کندل M1 داخل اپ گرفته شده
    //  (گزارش: `sweep/` در مخزن) — نه از حدس.
    // ═══════════════════════════════════════════════════════════════════════

    /** یک پیش‌تنظیم تطبیق: مجموعهٔ پارامترهایی که **با هم** روی دادهٔ یک‌ساله آزموده شده‌اند. */
    class AdaptPreset(
        val id: String,
        val label: String,
        val strategy: Int,
        val mm: Int,
        val fade: Boolean,
        val targetR: Double,
        val tpUnits: Double,
        val costMult: Double,
        val minSl: Double,
        val maxSl: Double,
        val cmsn: Double,
        val slBufTicks: Double,
        val entryBufTicks: Double,
        val note: String
    )

    /** کارمزد مرجع این پیش‌تنظیم‌ها (واحد قیمت). اگر کارمزد کاربر فرق دارد، «تطبیق از داده» را بزند. */
    val adaptCmsn = 0.7

    val adaptPresets: List<AdaptPreset> = ADAPT_PRESETS

    /** کارمزد مؤثر از تنظیمات (خودکارِ بروکر یا دستی) — مستقل از مود مالی. */
    fun effectiveCmsn(): Double =
        if (cfg.cmsnAuto && cfg.cmsnAutoValue > 0.0) cfg.cmsnAutoValue else Math.max(0.0, cfg.cmsnManual)

    /**
     * حداقل فاصلهٔ حد ضرر پیشنهادی از روی کارمزد واقعی.
     *
     * کارمزد هر معامله بر حسب R برابر `cmsn / R` است (چون حجم با ۱/R مقیاس می‌شود). پس برای
     * اینکه هزینه بیشتر از [costRatioInR] از R نشود: `R ≥ cmsn / costRatioInR`.
     * پیش‌فرض ۰٫۲۵ یعنی هزینه حداکثر یک‌چهارم R.
     */
    fun suggestedMinSlUnits(costRatioInR: Double = 0.25): Double {
        val cmsn = effectiveCmsn()
        if (cmsn <= 0.0 || costRatioInR <= 0.0) return 0.0
        return Math.round(cmsn / costRatioInR * 10.0) / 10.0
    }

    /**
     * تطبیق خودکار با کارمزد واردشده: حداقل/حداکثر فاصلهٔ حد ضرر و ضریب فیلتر کارمزد را
     * طوری تنظیم می‌کند که (۱) معاملهٔ کم‌فاصلهٔ بازنده ثبت نشود و (۲) آستانه‌ها با سقف N
     * سازگار بمانند (وگرنه هیچ سفارشی رد نمی‌شود — همان باگی که «کل معاملات صفر» می‌داد).
     */
    fun adaptToCommission(): String {
        val cmsn = effectiveCmsn()
        if (cmsn <= 0.0) return "اول کارمزد/اسپرد واقعی را وارد کنید (۰ ⇒ تطبیق معنا ندارد)"
        val minSl = suggestedMinSlUnits()
        val before = "min=${Fa.n(cfg.minSlUnits, 2)} N=${Fa.n(cfg.maxSlUnits, 2)} ضریب=${Fa.n(cfg.minSlCostMult, 2)}"
        cfg.minSlUnits = minSl
        // سقف N باید از حداقل بزرگ‌تر باشد وگرنه هیچ سفارشی باقی نمی‌ماند
        if (cfg.maxSlUnits <= cfg.minSlUnits) cfg.maxSlUnits = Math.round((cfg.minSlUnits + 3.0) * 10.0) / 10.0
        // ضریب فیلتر کارمزد (§۵-۳) معادل همان حداقل است: mult = minSl / cmsn
        cfg.minSlCostMult = Math.round(minSl / cmsn * 10.0) / 10.0
        Log.i(Log.CAT_CFG, "تطبیق با کارمزد", "cmsn=${Fa.n(cmsn, 2)} $before → min=${Fa.n(cfg.minSlUnits, 2)} N=${Fa.n(cfg.maxSlUnits, 2)}")
        rebuildAsync()
        return "کارمزد ${Fa.n(cmsn, 2)} ⇒ حداقل فاصلهٔ حد ضرر ${Fa.n(cfg.minSlUnits, 2)}، سقف N=${Fa.n(cfg.maxSlUnits, 2)}، " +
            "ضریب فیلتر ${Fa.n(cfg.minSlCostMult, 2)} (هزینه ≤ ۲۵٪ R)"
    }

    /** اعمال یک پیش‌تنظیم تطبیق؛ متن نتیجه برای نمایش برمی‌گردد. */
    fun applyAdaptPreset(p: AdaptPreset): String {
        cfg.strategy = p.strategy
        cfg.mmMode = p.mm
        cfg.trigger2Fade = p.fade
        cfg.trigger2TargetR = p.targetR
        cfg.tpUnits = p.tpUnits
        cfg.minSlCostMult = p.costMult
        cfg.minSlUnits = p.minSl
        cfg.maxSlUnits = p.maxSl
        cfg.slBufTicks = p.slBufTicks
        cfg.entryBufTicks = p.entryBufTicks
        cfg.cmsnAuto = false
        cfg.cmsnManual = p.cmsn
        Log.i(Log.CAT_CFG, "پیش‌تنظیم تطبیق اعمال شد",
            "${p.id} strategy=${p.strategy} mm=${MoneyMode.label(p.mm)} fade=${p.fade} k=${p.targetR} minSl=${p.minSl} N=${p.maxSl}")
        ensureBrokerSpread()
        rebuildAsync()
        return "${p.label}\n${p.note}"
    }

    /** اعمال ضریب پیشنهادی و بازسازی. خروجی: متن نتیجه برای toast. */
    fun applySuggestedCostMult(): String {
        val k = suggestedCostMult()
        if (k.isNaN()) return "برای پیشنهاد ضریب، اول یک بازسازی با مود §۲۰ انجام دهید"
        val before = cfg.minSlCostMult
        cfg.minSlCostMult = k
        Log.i(Log.CAT_CFG, "ضریب فیلتر کارمزد از داده تنظیم شد", "قبلی=${Fa.n(before, 2)} جدید=${Fa.n(k, 2)}")
        rebuildAsync()
        return "ضریب فیلتر کارمزد: ${Fa.n(before, 2)} → ${Fa.n(k, 2)} (آستانه ${Fa.n(engine.slCostPerUnit() * k, 2)})"
    }

    /** آیا سری [list] می‌تواند مبنای تایم‌فریم مود فعلی (cfg.tf2) باشد؟ */
    private fun seriesFitsMode(list: List<Candle>): Boolean =
        list.isNotEmpty() && com.alisport.goldpin.core.SeriesPick.canBuild(cfg.tf2, list)

    /**
     * گردآوری قدیمی‌ترین تاریخچهٔ قابل‌استفاده برای تایم‌فریم ساختار از همهٔ منابع
     * ریزتر/هم‌دانه: سری ساختار ذخیره‌شده، کش‌های فید، دادهٔ ریز داخلی/CSV و مود.
     * در هر باکت، منبع ریزتر اولویت دارد؛ پس از آغاز تاریخچهٔ تریگر۲، موتور اصلی
     * پروفایل دقیق را از همان کندل‌های تریگر۲ می‌سازد.
     */
    private fun availableStructureHistory(targetTfSec: Int): List<Candle> {
        if (targetTfSec <= 0) return emptyList()
        data class SourceBar(val candle: Candle, val step: Long, val priority: Int)
        val byTime = java.util.TreeMap<Long, SourceBar>()
        val sources = ArrayList<Pair<List<Candle>, Int>>()
        sources.add(structureCandles to 0)
        tfFeedCache.entries.sortedBy { it.key }.forEachIndexed { index, entry ->
            sources.add(entry.value to (10 + index))
        }
        sources.add(fineCandles to 100)
        sources.add(modeCandles to 200)

        for ((source, priority) in sources) {
            if (source.isEmpty()) continue
            val step = medianStepSec(source)
            if (step <= 0L || step > targetTfSec.toLong()) continue
            for (bar in Feed.aggregate(source, targetTfSec)) {
                val bucket = Tf.bucket(targetTfSec, bar.t)
                val old = byTime[bucket]
                if (old == null || step < old.step || (step == old.step && priority >= old.priority)) {
                    byTime[bucket] = SourceBar(
                        Candle(bar.bi, bucket, bar.o, bar.h, bar.l, bar.c, bar.v), step, priority
                    )
                }
            }
        }
        return byTime.values.mapIndexed { index, source ->
            val c = source.candle
            Candle(index, c.t, c.o, c.h, c.l, c.c, c.v)
        }
    }

    /** ادغام دو سری هم‌تایم‌فریم با ترجیح دادهٔ جدیدتر و حفظ بازهٔ قدیمی‌تر. */
    private fun mergeAtTimeframe(
        preferred: List<Candle>,
        fallback: List<Candle>,
        targetTfSec: Int
    ): List<Candle> {
        if (targetTfSec <= 0) return emptyList()
        val byTime = java.util.TreeMap<Long, Candle>()
        fun add(source: List<Candle>) {
            if (source.isEmpty()) return
            val step = medianStepSec(source)
            if (step <= 0L || step > targetTfSec.toLong()) return
            for (bar in Feed.aggregate(source, targetTfSec)) {
                val bucket = Tf.bucket(targetTfSec, bar.t)
                byTime[bucket] = Candle(bar.bi, bucket, bar.o, bar.h, bar.l, bar.c, bar.v)
            }
        }
        add(fallback)
        add(preferred)
        return byTime.values.mapIndexed { index, c ->
            Candle(index, c.t, c.o, c.h, c.l, c.c, c.v)
        }
    }

    /** ثبت سری تاریخی ساختار مستقل از سری اجرای تریگر۲. */
    private fun rememberStructureHistory(source: List<Candle>, targetTfSec: Int) {
        if (source.isEmpty() || targetTfSec <= 0) return
        val merged = mergeAtTimeframe(source, structureCandles, targetTfSec)
        if (merged.isNotEmpty()) {
            structureCandles = merged
            tfFeedCache[targetTfSec] = mergeAtTimeframe(merged, tfFeedCache[targetTfSec].orEmpty(), targetTfSec)
        }
    }

    private fun mergeDisplayWithMode(chart: List<Candle>, targetTfSec: Int): List<Candle> {
        if (chart.isEmpty()) return aggregateBestAvailable(targetTfSec)
        if (modeCandles.isEmpty() || targetTfSec < cfg.tf2) return chart
        if (targetTfSec == cfg.tf2) return modeCandles
        val aggMode = Feed.aggregate(modeCandles, targetTfSec)
        if (aggMode.isEmpty()) return chart
        val firstModeT = aggMode.first().t
        val older = chart.filter { it.t < firstModeT }
        if (older.isEmpty()) return aggMode
        val combined = ArrayList<Candle>(older.size + aggMode.size)
        combined.addAll(older)
        combined.addAll(aggMode)
        return combined.mapIndexed { idx, c -> Candle(idx, c.t, c.o, c.h, c.l, c.c, c.v) }
    }

    private fun fetchChartHistoryBlocking(targetTfSec: Int): Pair<List<Candle>, Int> {
        var base = Feed.baseTfFor(targetTfSec)
        val (interval, range) = Feed.yahooSpec(base, depth)
        var raw = Feed.yahooChart(symbol, interval, range)
        if (raw.size < 30 && base != Tf.M1 && targetTfSec < Tf.H1) {
            try {
                val r1m = Feed.yahooChart(symbol, "1m", "7d")
                if (r1m.size > raw.size) {
                    raw = r1m
                    base = Tf.M1
                }
            } catch (_: Exception) { }
        }
        var chart = if (base == targetTfSec) raw else Feed.aggregate(raw, targetTfSec)
        if (useSyntheticVolume) chart = Feed.synthesizeVolumeIfMissing(chart)
        return chart to base
    }

    fun setMode(modeIdx: Int) {
        Log.i(Log.CAT_CFG, "تغییر مود تایم‌فریمی", "مود=$modeIdx")
        val m = TF_MODES.firstOrNull { it.idx == modeIdx } ?: TF_MODES[0]
        cfg.tfMode = m.idx
        val tfChanged = !m.custom && (chartTfSec != m.t2 || !sameTimeframe(medianStepSec(modeCandles), m.t2.toLong()))
        if (!m.custom) {
            cfg.tfS = m.s; cfg.tfM = m.m; cfg.tf1 = m.t1; cfg.tf2 = m.t2
            chartTfSec = m.t2
        }
        // ── نگهبان طرح B ────────────────────────────────────────────────────────
        // در نشست داخلی، تغییر مود نباید موتور را خودسرانه روی دادهٔ M1 بازسازی کند؛
        // فقط سری نمایش/تریگر۲ از نو تجمیع می‌شود و نتیجه «کهنه» علامت می‌خورد تا با
        // دکمهٔ «اجرای مجدد بک‌تست» با همین تنظیمات تازه از نو اجرا شود.
        if (sessionMode == SessionMode.INTERNAL) {
            internalResetSeries()
            backtestStale = true
            Log.i(Log.CAT_CFG, "مود در نشست داخلی عوض شد — نتیجه کهنه شد",
                "مود=${m.idx} tf2=${com.alisport.goldpin.core.Tf.label(cfg.tf2)}")
            notifyUi()
            return
        }
        busy = true
        busyText = "در حال تغییر مود تایم‌فریمی…"
        notifyUi()
        io.execute {
            try {
                val cached = tfFeedCache[cfg.tf2]
                val source = when {
                    cached != null && seriesFitsMode(cached) -> cached
                    tfChanged || !seriesFitsMode(modeCandles) -> aggregateBestAvailable(cfg.tf2)
                    else -> modeCandles                       // سری فعلی، خودش همین تایم‌فریم است
                }
                val haveSeries = seriesFitsMode(source)
                if (haveSeries) setCandles(source, rebuildNow = false)
                // ⚠ بازسازی موتور فقط وقتی انجام می‌شود که در همین مود دانلودی در راه نباشد.
                // دلیل باگ «تغییر مود ⇒ سفارش‌ها صفر» همین بود: setMode اول روی دادهٔ محلی
                // (تجمیع کوتاه ۷ روزه یا حتی سری نامعتبر) موتور را از صفر اجرا می‌کرد، کارگزار
                // reset می‌شد و نتیجهٔ «صفر ستاپ/صفر سفارش» منتشر می‌شد؛ اگر دانلود هم (به‌خاطر
                // شرط modeFromFeed) اجرا نمی‌شد، همان صفر تا ابد روی صفحه می‌ماند.
                // حالا نتیجهٔ معتبر قبلی دست‌نخورده می‌ماند تا دانلود تاریخچهٔ مود تمام شود و
                // خودش یک‌بار، کامل و روی دادهٔ درست بازسازی کند.
                if (m.custom) {
                    if (haveSeries) rebuild()
                    else {
                        lastFeedError = "سری ${com.alisport.goldpin.core.Tf.label(cfg.tf2)} در حافظه نیست"
                        Log.w(Log.CAT_CFG, "مود سفارشی بدون سری معتبر — بازسازی انجام نشد", "")
                    }
                } else {
                    Log.i(Log.CAT_CFG, "مود عوض شد — بازسازی به پایان دانلود تاریخچه موکول شد",
                        "مود=${m.idx} tf2=${com.alisport.goldpin.core.Tf.label(cfg.tf2)} " +
                            "سری‌محلی=${if (haveSeries) "${source.size} کندل" else "نامعتبر/خالی"}")
                    lastFeedError = "تاریخچهٔ ${com.alisport.goldpin.core.Tf.label(cfg.tf2)} در حال دانلود است…"
                }
            } catch (e: Throwable) {
                Log.e(Log.CAT_ENGINE, "تغییر مود ناموفق بود", e)
                lastFeedError = "خطای تغییر مود: ${e.message}"
            } finally {
                busy = false
                busyText = ""
                notifyUi()
            }
        }
        // با تغییر مود، تاریخچهٔ تایم‌فریم ساختار هم باید از منبع مناسب آن مود گرفته شود؛
        // سری نمایش چارت به‌تنهایی موتور را بازسازی نمی‌کند.
        // ⚠ شرط قبلی `modeFromFeed` بود: بعد از هر بار بازیابی فایل نشست (که این پرچم false است)
        // تغییر مود هرگز دانلود نمی‌کرد ⇒ موتور روی دادهٔ ناقص/نامعتبر می‌ماند و «صفر سفارش» دائمی می‌شد.
        if (!m.custom) {
            downloadHistory(forceRebuild = true) { msg ->
                if (msg.startsWith("خطای دانلود")) {
                    Log.w(Log.CAT_CFG, "دانلود تاریخچهٔ مود ناموفق بود؛ بازسازی با دادهٔ موجود", msg)
                    rebuildAsync()
                }
            }
        }
    }

    /** تغییر تایم‌فریم تریگر ۲ در مود ۵ (سفارشی) و بازسازی موتور مود */
    fun setCustomTf2(newTf2: Int) {
        if (newTf2 <= 0) return
        cfg.tf2 = newTf2
        chartTfSec = newTf2
        // نگهبان طرح B: همان منطق setMode — بازسازی موتور فقط با «اجرای مجدد بک‌تست».
        if (sessionMode == SessionMode.INTERNAL) {
            internalResetSeries()
            backtestStale = true
            notifyUi()
            return
        }
        // اگر تاریخچهٔ این تایم‌فریم در کش نیست، دانلودی در راه است؛ بازسازی به پایان آن موکول
        // می‌شود (همان قاعدهٔ setMode: هرگز نتیجهٔ خالی/کوتاه منتشر نشود).
        val willDownload = tfFeedCache[newTf2] == null
        io.execute {
            try {
                val cached = tfFeedCache[newTf2]
                val local = when {
                    cached != null && seriesFitsMode(cached) -> cached
                    else -> aggregateBestAvailable(newTf2)
                }
                if (seriesFitsMode(local)) {
                    setCandles(local, rebuildNow = !willDownload)
                } else {
                    Log.i(Log.CAT_CFG, "تایم‌فریم مود سفارشی عوض شد ولی سری معتبر نیست — بازسازی موکول به دانلود",
                        "tf2=${com.alisport.goldpin.core.Tf.label(newTf2)} سری‌فعلی=${modeCandles.size} کندل")
                    lastFeedError = "تاریخچهٔ ${com.alisport.goldpin.core.Tf.label(newTf2)} در حال دانلود است…"
                    notifyUi()
                }
            } catch (e: Throwable) {
                Log.e(Log.CAT_ENGINE, "تغییر تایم‌فریم مود سفارشی ناموفق بود", e)
                lastFeedError = "خطای تغییر تایم‌فریم: ${e.message}"
                notifyUi()
            }
        }
        if (willDownload) {
            downloadHistory(forceRebuild = true) { msg ->
                if (msg.startsWith("خطای دانلود")) rebuildAsync()
            }
        }
    }

    fun setCandles(list: List<Candle>, rebuildNow: Boolean = true) {
        Log.i(Log.CAT_FEED, "داده در اپ گذاشته شد", "کندل=${list.size}")
        if (list.isNotEmpty()) stateLoaded = true
        busy = true
        try {
            val copy = ArrayList(list)
            val step = medianStepSec(copy)
            if (copy.isNotEmpty() && (fineCandles.isEmpty() || (step in 1L..90L && copy.size >= fineCandles.size))) {
                fineCandles = copy
            }
            modeCandles = copy
            candles = if (chartTfSec == cfg.tf2 || sameTimeframe(step, chartTfSec.toLong()) || copy.isEmpty()) {
                copy
            } else {
                aggregateBestAvailable(chartTfSec).ifEmpty { copy }
            }
            if (rebuildNow) rebuild() else synchronized(stateLock) { publishSnapshot() }
        } finally {
            busy = false
        }
        notifyUi()
    }

    /** تعویض فقط کندل‌های نمایشی چارت، بدون تغییر مود و بدون بازسازی/تغییر باکس‌های ساختار و تریگر ۱ و ۲ در حافظه */
    fun setDisplayCandles(list: List<Candle>) {
        val copy = ArrayList(list)
        val step = medianStepSec(copy)
        if (copy.isNotEmpty() && (fineCandles.isEmpty() || (step in 1L..90L && copy.size >= fineCandles.size))) {
            fineCandles = copy
        }
        synchronized(stateLock) {
            candles = copy
            publishSnapshot()
        }
        notifyUi()
    }

    // ── دانلود تاریخچه از فید ───────────────────────────────────────────────────
    fun downloadHistory(forceRebuild: Boolean = false, onDone: (String) -> Unit = {}) {
        // نگهبان طرح B: در نشست داخلی هیچ دانلودی انجام نمی‌شود؛ وگرنه دادهٔ شبکه جای
        // دادهٔ یک‌ساله را می‌گرفت (همان «تداخل» که با جداسازی نشست‌ها حذف شد).
        if (sessionMode == SessionMode.INTERNAL) {
            Log.w(Log.CAT_FEED, "دانلود تاریخچه در نشست بک‌تست داخلی نادیده گرفته شد")
            main.post { onDone("در حالت بک‌تست داخلی، فید شبکه خاموش است") }
            return
        }
        val t0 = System.currentTimeMillis()
        val targetTf = chartTfSec
        val modeTf = cfg.tf2
        val structureTf = cfg.tfS
        busy = true
        busyText = "در حال دانلود تاریخچهٔ طلا (${com.alisport.goldpin.core.Tf.label(targetTf)})…"
        lastFeedError = null
        notifyUi()
        Log.i(Log.CAT_FEED, "شروع دانلود تاریخچه", "نماد=$symbol چارت=${com.alisport.goldpin.core.Tf.label(targetTf)} مود=${com.alisport.goldpin.core.Tf.label(modeTf)} عمق=$depth")
        net.execute {
            try {
                val needModeDownload = (targetTf == modeTf || !modeFromFeed || modeCandles.isEmpty() || !sameTimeframe(medianStepSec(modeCandles), modeTf.toLong()))
                val (modeChart, modeBase) = if (needModeDownload) fetchChartHistoryBlocking(modeTf) else (modeCandles to baseTfSec)
                val (chart, base) = if (targetTf == modeTf) (modeChart to modeBase) else fetchChartHistoryBlocking(targetTf)
                val modeSeries = mergeAtTimeframe(modeChart, fineCandles, modeTf)
                val structureSource = when {
                    structureTf == modeTf -> modeSeries
                    structureTf == targetTf -> chart
                    else -> try {
                        fetchChartHistoryBlocking(structureTf).first
                    } catch (e: Exception) {
                        Log.w(Log.CAT_FEED, "دریافت تاریخچهٔ مستقل ساختار ناموفق بود؛ از منابع محلی استفاده می‌شود",
                            e.message ?: "")
                        emptyList()
                    }
                }
                val n = chart.size
                Log.i(Log.CAT_FEED, "دانلود تاریخچه موفق",
                    "کندل=$n مدت=${System.currentTimeMillis() - t0}ms پایه=${com.alisport.goldpin.core.Tf.label(base)} " +
                        "چارت=${com.alisport.goldpin.core.Tf.label(targetTf)} ساختار=${com.alisport.goldpin.core.Tf.label(structureTf)} " +
                        "توالی‌ساختار=${structureSource.size}")
                io.execute {
                    try {
                        baseTfSec = base
                        modeFromFeed = true
                        if (modeSeries.isNotEmpty()) tfFeedCache[modeTf] = modeSeries
                        if (chart.isNotEmpty()) tfFeedCache[targetTf] = chart
                        if (structureSource.isNotEmpty()) rememberStructureHistory(structureSource, structureTf)
                        if (needModeDownload && modeSeries.isNotEmpty()) {
                            busyText = "در حال محاسبهٔ موتور روی ${com.alisport.goldpin.util.Fa.d(modeSeries.size.toString())} کندل…"
                            setCandles(modeSeries, rebuildNow = false)
                        }
                        if (forceRebuild || needModeDownload || structureSource.isNotEmpty()) {
                            rebuild()
                        }
                        if (targetTf != modeTf) {
                            setDisplayCandles(mergeDisplayWithMode(chart, targetTf))
                        }
                    } finally {
                        main.post {
                            lastFeedAt = System.currentTimeMillis()
                            lastFeedError = null
                            busy = false
                            busyText = ""
                            notifyUi()
                            onDone("دانلود شد: ${com.alisport.goldpin.util.Fa.d(n.toString())} کندل (${com.alisport.goldpin.core.Tf.label(targetTf)})")
                        }
                    }
                }
            } catch (e: Exception) {
                val msg = e.message ?: "خطای نامشخص"
                Log.e(Log.CAT_FEED, "دانلود تاریخچه ناموفق", e)
                main.post {
                    lastFeedError = com.alisport.goldpin.util.Fa.short(msg, 160)
                    busy = false
                    busyText = ""
                    notifyUi()
                    onDone("خطای دانلود: ${com.alisport.goldpin.util.Fa.short(msg, 160)}")
                }
            }
        }
    }

    /**
     * «بارگذاری مجدد همه‌چیز» (دکمهٔ گرد ↻): دادهٔ تازه را می‌گیرد و موتور + کارگزار + همهٔ محاسبات را از صفر می‌سازد.
     * اگر شبکه نبود، دست‌کم همهٔ محاسبات روی دادهٔ فعلی از صفر انجام می‌شود.
     * @param download false = فقط بازمحاسبه (بدون شبکه) — برای آزمون و حالت آفلاین
     */
    fun reloadAll(download: Boolean = true, onDone: (String) -> Unit = {}) {
        Log.i(Log.CAT_APP, "بارگذاری مجدد کامل (دکمهٔ ↻)", "دانلود=$download")
        if (!download) {
            rebuildAsync()
            main.post { onDone("همهٔ محاسبات از صفر انجام شد") }
            return
        }
        downloadHistory(forceRebuild = true) { msg ->
            if (msg.startsWith("خطای دانلود")) {
                rebuildAsync()
                onDone("دانلود ممکن نشد؛ همهٔ محاسبات روی داده‌های فعلی از صفر انجام شد")
            } else {
                onDone("بارگذاری مجدد کامل شد · $msg")
            }
        }
    }

    /** منبع اسپرد بروکر؛ در آزمون‌ها جایگزین می‌شود تا شبکهٔ واقعی دخالت نکند. */
    @Volatile var spreadFetcher: () -> Double? = { Feed.swissquoteSpread() }

    /**
     * §۲۰ cmsn «خودکار»: اسپرد فعلی بروکر (ask − bid از Swissquote) را **یک‌بار** می‌گیرد و داخل تنظیمات ذخیره می‌کند
     * ([Settings.cmsnAutoValue]). فقط وقتی اجرا می‌شود که کاربر بخواهد (روشن‌کردن «خودکار»، دکمهٔ دریافت) یا هنوز
     * مقداری نداریم؛ در تیک‌های لایو هرگز — تا نتیجهٔ معاملات بی‌صدا عوض نشود («هر بار یک جور حساب می‌کند»).
     * مقدار تازه در همهٔ معاملات (تاریخچه و زنده) یکسان اعمال می‌شود، پس بازسازی لازم است.
     */
    fun fetchBrokerSpread(onDone: (String) -> Unit = {}) {
        net.execute {
            val sp = try { spreadFetcher() } catch (e: Throwable) { null }
            io.execute {
                if (sp == null || sp.isNaN() || sp <= 0.0) {
                    main.post { onDone("اسپرد بروکر دریافت نشد؛ عدد دستی/قبلی می‌ماند") }
                    return@execute
                }
                cfg.cmsnAutoValue = sp
                cfg.cmsnAutoAt = System.currentTimeMillis()
                Log.i(Log.CAT_FEED, "اسپرد بروکر دریافت و ذخیره شد (Swissquote)", Fa.n(sp, 3))
                if (cfg.mmMode != MoneyMode.NONE && cfg.cmsnAuto) rebuildAsync()
                main.post { notifyUi(); onDone("اسپرد بروکر: ${Fa.n(sp, 3)}") }
            }
        }
    }

    /** مود §۲۰ روشن، cmsn «خودکار» و هنوز اسپردی نداریم ⇒ یک‌بار بگیر (بعدش فقط با درخواست کاربر عوض می‌شود). */
    fun ensureBrokerSpread() {
        if (cfg.mmMode != MoneyMode.NONE && cfg.cmsnAuto && cfg.cmsnAutoValue <= 0.0) fetchBrokerSpread()
    }

    /**
     * به‌روزرسانی زنده: فقط دنبالهٔ دادهٔ مود (cfg.tf2) را می‌گیریم و با سری مود ادغام می‌کنیم.
     *
     * سه لایهٔ محافظ در برابر فریز:
     *  ۱) [liveGate] — حداکثر یک تیک در جریان؛ تیک‌های اضافی **حذف** می‌شوند تا صف رشد نکند.
     *  ۲) شبکه روی [net] است نه [io] ⇒ socket معطل، کارهای رابط را گروگان نمی‌گیرد.
     *  ۳) تغییر وضعیت روی [io] و زیر [stateLock] ⇒ با rebuild() هم‌زمان نمی‌شود.
     */
    fun updateLiveOnce(notifyDone: Boolean = false, onDone: (String) -> Unit = {}) {
        // ۱) اگر تیک قبلی هنوز تمام نشده، این تیک حذف می‌شود (نه اینکه در صف بایستد).
        if (!liveGate.tryAcquire()) {
            Log.w(Log.CAT_LIVE, "تیک لایو حذف شد — تیک قبلی هنوز در جریان است",
                "حذف‌شده تاکنون=${liveGate.skipped} (فید کند است؛ تیک بعدی همان دنباله را می‌گیرد)")
            lastLiveSkips = liveGate.skipped
            if (notifyDone) onDone("فید کند است — تیک قبلی هنوز در جریان است")
            return
        }
        val t0 = System.currentTimeMillis()
        // ۲) شبکه روی نخ جدا (همواره روی تایم‌فریم تریگر ۲ مود انتخاب‌شده تا باکس‌ها در حافظه ثابت بمانند)
        net.execute {
            var handedOff = false
            try {
                val modeTf = cfg.tf2
                val base = Feed.baseTfFor(modeTf)
                val (interval, range) = Feed.yahooSpec(base, 1)
                val raw = Feed.yahooChart(symbol, interval, range)
                var tail = if (base == modeTf) raw else Feed.aggregate(raw, modeTf)
                if (useSyntheticVolume) tail = Feed.synthesizeVolumeIfMissing(tail)
                val (spot, spotSrc) = Feed.spotPrice()
                // §6 سند: «اگر قیمت زنده نیامد، آخرین کندل بسته‌شده استفاده می‌شود» — آخرین عنصر دنباله
                // کندل در حال تشکیل است (موتور هم همین را باز می‌داند)، پس کلوز کندل قبل از آن.
                val last = (if (tail.size >= 2) tail[tail.size - 2] else tail.lastOrNull())?.c ?: Double.NaN
                // §6 سند: «قیمت لحظه‌ای زنده به ترتیب از سه منبع مستقل گرفته می‌شود و به کندل آخر تزریق می‌شود»
                if (spot != null && !spot.isNaN() && tail.isNotEmpty()) {
                    val lc = tail.last()
                    val updated = ArrayList(tail)
                    updated[updated.lastIndex] = Candle(
                        lc.bi, lc.t, lc.o,
                        maxOf(lc.h, spot),
                        minOf(lc.l, spot),
                        spot,
                        lc.v
                    )
                    tail = updated
                }
                Log.d(Log.CAT_LIVE, "به‌روزرسانی زنده",
                    "دنباله=${tail.size} قیمت=${if (spot != null) Fa.n(spot, 2) else "-"} منبع=$spotSrc مدت=${System.currentTimeMillis() - t0}ms")
                // ۳) تغییر وضعیت روی نخ سریالی [io]
                io.execute {
                    try {
                        feedLiveTail(tail)
                    } finally {
                        liveGate.release()
                    }
                    liveFailStreak = 0
                    main.post {
                        lastLivePrice = if (spot != null && !spot.isNaN()) spot else last
                        lastPriceSource = spotSrc
                        lastFeedAt = System.currentTimeMillis()
                        lastFeedError = null
                        notifyUi()
                        if (notifyDone) onDone("به‌روزرسانی شد · ${com.alisport.goldpin.util.Fa.n(lastLivePrice, 2)}")
                    }
                }
                handedOff = true   // واگذار شد؛ آزادی دروازه به عهدهٔ finally داخل io است
            } catch (e: Exception) {
                liveFailStreak++
                Log.w(Log.CAT_LIVE, "به‌روزرسانی زنده ناموفق",
                    "${e.message ?: ""} · پشت‌سرهم=$liveFailStreak")
                main.post {
                    lastFeedError = com.alisport.goldpin.util.Fa.short(e.message, 160)
                        .ifEmpty { "خطای فید" }
                    notifyUi()
                    if (notifyDone) onDone("خطای فید: ${lastFeedError}")
                }
            } finally {
                // اگر هیچ‌وقت به io واگذار نشد (خطای شبکه یا صف رد شد)، همین‌جا آزاد کن
                if (!handedOff) liveGate.release()
            }
        }
    }

    /** ادغام دنبالهٔ تازه در سری چارت (بر اساس زمان — بدون تکرار و بدون ریپینت) */
    /** حداکثر پرش مجاز قیمت در یک تیک لایو (۰٫۲٪ ≈ ۸ دلار روی طلای ۴۱۰۰) — بیشتر = منبع ناسازگار */
    private val MAX_LIVE_PRICE_JUMP = 0.002

    /**
     * آیا وضعیت از فایل نشست بازیابی شده است؟ تا وقتی false باشد، هیچ نوشتن/ساختن سری تازه‌ای
     * مجاز نیست؛ وگرنه یک ایندکس خالی روی فایل سالمِ کاربر نوشته می‌شود (باگ «تاریخچه غیب شد»).
     */
    @Volatile var stateLoaded: Boolean = false

    @Volatile private var restoreRequested = false

    /** بازیابی وضعیت را یک‌بار (و فقط یک‌بار) در پس‌زمینه می‌خواهد. */
    fun requestRestoreOnce() {
        if (restoreRequested || stateLoaded) return
        val ctx = appCtx ?: return
        restoreRequested = true
        Log.i(Log.CAT_LIVE, "وضعیت بازیابی‌نشده بود — بازیابی خودکار شروع شد")
        io.execute { restoreLiveBlocking(ctx); main.post { notifyUi() } }
    }

    /** فاصلهٔ میانی کندل‌ها بر حسب ثانیه (برای تشخیص تایم‌فریم واقعی یک سری) */
    private fun medianStepSec(list: List<Candle>): Long =
        com.alisport.goldpin.core.SeriesPick.stepOf(list)

    /** دو فاصلهٔ زمانی «یک تایم‌فریم» حساب می‌شوند؟ (تحمل ۱٫۵ برابر برای لرزش داده) */
    private fun sameTimeframe(a: Long, b: Long): Boolean =
        com.alisport.goldpin.core.SeriesPick.sameTf(a, b)

    /**
     * حداکثر کندل بسته‌ای که یک تیک لایو «طبیعی» ممکن است جدید ببیند. بیشتر از این
     * یعنی بازپخش تاریخچه (اپ بسته بوده یا سری عوض شده) و باید ساکت بماند.
     */
    private val LIVE_CATCHUP_SILENT_BARS = 3

    /**
     * هستهٔ تیک لایو: ادغام دنباله + اجرای موتور، زیر [stateLock] و روی نخ [io].
     *
     * ⚠ **محافظ سیل هشدار** — همین اپ را فریز می‌کرد: سرویس لایو `Alerts.inBacktest`
     * را `false` می‌گذارد تا هشدارها واقعاً پخش شوند. ولی اگر همان تیک اول هزاران کندل
     * **تاریخی** را بازپخش کند، همه «زنده» حساب می‌شدند و برای هر کدام یک
     * `runOnUiThread` (ساخت/حذف View + requestLayout) و یک نوتیفیکیشن ساخته می‌شد ⇒
     * چند هزار کار روی نخ رابط صف می‌شد و اپ کامل فریز می‌شد.
     *
     * حالا اگر کندل‌های بستهٔ جدید از [LIVE_CATCHUP_SILENT_BARS] بیشتر باشد، آن بسته
     * به‌صورت بک‌تست **ساکت** (فقط لاگ، بدون بنر/نوتیفیکیشن/صدا/لرزش) اجرا می‌شود.
     */
    fun feedLiveTail(tail: List<Candle>) {
        // نگهبان طرح B: تیک زنده هرگز وارد موتورِ بک‌تست نمی‌شود.
        if (sessionMode == SessionMode.INTERNAL) return
        synchronized(stateLock) {
            mergeTail(tail)
            val execCandles = if (modeCandles.isNotEmpty()) modeCandles else candles
            val newClosed = (execCandles.size - 1) - engine.processed
            val prev = Alerts.inBacktest
            val silent = newClosed > LIVE_CATCHUP_SILENT_BARS
            if (silent) Alerts.inBacktest = true
            try {
                engine.broker = broker
                engine.barCommitHook = { cd -> broker.onBar(cd, live = false) }
                engine.feed(execCandles, lastIsClosed = false)
                // §1/§3/§6 سند: پر شدن سفارش لیمیت با قیمت زنده — با high/low کندلِ در حال تشکیل.
                // تصمیم‌های استراتژی همچنان فقط با کندل بسته؛ این فقط سفارش معلق را پر می‌کند.
                val forming = execCandles.lastOrNull()
                if (forming != null && forming.bi >= engine.processed && broker.hasPending) {
                    try {
                        val n = broker.onLiveCandle(forming)
                        if (n > 0) Log.i(Log.CAT_LIVE, "سفارش با قیمت زنده پر شد",
                            "تعداد=$n کندل=${forming.bi} high=${Fa.n(forming.h, 2)} low=${Fa.n(forming.l, 2)}")
                    } catch (t: Throwable) {
                        // خطای پر شدن زنده هرگز نباید مسیر تیک لایو را بشکند؛ سفارش با کلوز کندل پر می‌شود.
                        Log.e(Log.CAT_LIVE, "پر شدن زندهٔ سفارش ناموفق بود", t)
                    }
                }
                publishSnapshot()
            } finally {
                Alerts.inBacktest = prev
            }
            if (silent) {
                lastLiveSilentBars = newClosed
                Log.w(Log.CAT_LIVE, "catch-up replayed silently",
                    "newClosedBars=$newClosed > $LIVE_CATCHUP_SILENT_BARS — inha rokh-dade zende nistand")
            }
        }
    }

    /**
     * ادغام دنبالهٔ لایو در سری مود.
     *
     * ⚠ **باگی که تاریخچهٔ سفارش‌ها را پاک می‌کرد:** نسخه‌های قبل هنگام هر «ناسازگاری» سری را
     * کامل جایگزین می‌کردند (`setCandles(tail, rebuildNow = true)`) و `rebuild()` طبق طراحی
     * کارگزار را از صفر می‌سازد ⇒ همهٔ سفارش‌ها/معاملات/موجودی می‌رفت و گزارش «هیچ معامله‌ای
     * بسته نشده» می‌داد. این حالت با یک تیک ناقص (دنبالهٔ خیلی کوتاه)، با قطع موقت Yahoo و
     * سوئیچ بی‌صدای منبع (PAXG با مقیاس حجم/قیمت متفاوت) هم رخ می‌داد.
     *
     * حالا در لایو **هیچ تیکی سری را جایگزین نمی‌کند**؛ تیک مشکوک دور ریخته می‌شود و
     * وضعیت (سفارش‌ها، معاملات، باکس‌ها) دست‌نخورده می‌ماند. جایگزینی سری فقط با درخواست صریح
     * کاربر (دکمهٔ ↻ / تغییر تنظیمات) انجام می‌شود.
     */
    fun mergeTail(tail: List<Candle>) {
        if (tail.isEmpty()) return
        val baseSeries = if (modeCandles.isNotEmpty()) modeCandles else candles
        val before = baseSeries.size
        if (baseSeries.isEmpty()) {
            // حالت فقط‌اولین‌بار: هنوز هیچ داده‌ای در حافظه نیست. اگر وضعیت بازیابی‌نشده باشد،
            // ساختن سری از این دنباله یعنی پاک‌کردن تاریخچهٔ ذخیره‌شده؛ پس اول بازیابی می‌کنیم.
            if (!stateLoaded) {
                Log.w(Log.CAT_LIVE, "دنبالهٔ لایو قبل از بازیابی وضعیت رسید — دور ریخته شد",
                    "دنباله=${tail.size} · منتظر بازیابی فایل نشست")
                requestRestoreOnce()
                return
            }
            setCandles(tail, rebuildNow = true); return
        }
        // ① دنبالهٔ کوتاه: فاصلهٔ زمانی‌اش قابل اندازه‌گیری نیست ⇒ نمی‌شود در مورد ناسازگاری قضاوت کرد.
        val tailStep = medianStepSec(tail)
        if (tail.size < 3 || tailStep <= 0L) {
            Log.w(Log.CAT_LIVE, "تیک لایو دور ریخته شد — دنبالهٔ کوتاه",
                "دنباله=${tail.size} فاصله=${tailStep}s")
            return
        }
        val curStep = medianStepSec(baseSeries)
        if (curStep > 0L && !sameTimeframe(curStep, tailStep)) {
            Log.w(Log.CAT_LIVE, "تیک لایو دور ریخته شد — ناسازگاری تایم‌فریم دنباله",
                "سری=$before با فاصلهٔ ${curStep}s · دنباله=${tail.size} با فاصلهٔ ${tailStep}s · " +
                    "chart=${com.alisport.goldpin.core.Tf.label(chartTfSec)}")
            return
        }
        // ② پرش قیمت: منبع پشتیبان (PAXG) با قیمت/حجم متفاوت. ادغامش یعنی باکس‌های جعلی و
        //    حجم‌های ۲۵٬۰۰۰ برابری، پس تیک دور ریخته می‌شود.
        val lastBase = baseSeries.last().c
        val firstTail = tail.first().o
        if (lastBase > 0 && firstTail > 0) {
            val dev = kotlin.math.abs(firstTail - lastBase) / lastBase
            if (dev > MAX_LIVE_PRICE_JUMP) {
                Log.w(Log.CAT_LIVE, "تیک لایو دور ریخته شد — پرش قیمت (منبع ناسازگار؟)",
                    "سری=${Fa.n(lastBase, 2)} دنباله=${Fa.n(firstTail, 2)} اختلاف=${Fa.n(dev * 100, 2)}٪")
                return
            }
        }
        val firstNew = tail.first().t
        // حذف بخش هم‌پوشان از انتهای سری فعلی
        var cut = baseSeries.size
        while (cut > 0 && baseSeries[cut - 1].t >= firstNew) cut--
        val merged = ArrayList<Candle>(cut + tail.size)
        for (i in 0 until cut) merged.add(baseSeries[i])
        for (c in tail) merged.add(c)
        val nextMode = merged.mapIndexed { i, c -> Candle(i, c.t, c.o, c.h, c.l, c.c, c.v) }
        modeCandles = nextMode
        candles = if (chartTfSec == cfg.tf2 || sameTimeframe(tailStep, chartTfSec.toLong())) {
            nextMode
        } else {
            val cached = tfFeedCache[chartTfSec]
            if (cached != null && cached.isNotEmpty()) mergeDisplayWithMode(cached, chartTfSec)
            else aggregateBestAvailable(chartTfSec).ifEmpty { nextMode }
        }
        Log.d(Log.CAT_LIVE, "ادغام دنبالهٔ داده", "قبل=$before بعد=${modeCandles.size} جدید=${modeCandles.size - before}")
    }

    /**
     * تغییر تایم‌فریم نمایشی چارت.
     *
     * ⚠ در مود انتخاب‌شده، با تغییر تایم‌فریم چارت:
     *  • سلسله‌مراتب تایم‌فریمی مود (`cfg.tfMode/tfS/tfM/tf1/tf2`) تغییر نمی‌کند؛
     *  • موتور و باکس‌های ساختار (`engine.zones`) و محدوده‌های تریگر ۱ (`t1Zones`/`lvH..lvL`)
     *    و تریگر ۲ (`t2Zones`/`hvH..hvL`) در حافظه ثابت می‌مانند و بازسازی نمی‌شوند؛
     *  • فقط کندل‌های نمایشی چارت (`candles`) به تایم‌فریم انتخاب‌شده تغییر می‌کنند.
     */
    fun changeChartTf(newTf: Int, forceDownload: Boolean = false) {
        if (newTf <= 0) return
        val stepMatches = sameTimeframe(medianStepSec(candles), newTf.toLong()) && candles.size >= 30
        if (!forceDownload && newTf == chartTfSec && stepMatches) return
        Log.i(Log.CAT_CFG, "تغییر تایم‌فریم چارت (باکس‌های مود ثابت در حافظه)",
            "${com.alisport.goldpin.core.Tf.label(chartTfSec)} → ${com.alisport.goldpin.core.Tf.label(newTf)} · مود=${cfg.tfMode} (tf2=${com.alisport.goldpin.core.Tf.label(cfg.tf2)})")
        chartTfSec = newTf

        // ۱) اگر دادهٔ تایم‌فریم مقصد از حافظه قابل ساخت است، بلافاصله نمایش بده.
        //
        // ⚠ نکتهٔ مهم (§۴-۵ سند): تجمیعِ محلی از سری تریگر۲ فقط به عمق همان سری است.
        // اگر تایم‌فریم مقصد **درشت‌تر** از دادهٔ موجود باشد (مثلاً چارت را روی تایم‌فریم
        // ساختار بگذاریم ولی سری ۱ دقیقه فقط ۷ روز عقب برود)، چارتِ ساختار فقط همان ۷ روز را
        // نشان می‌دهد و باکس‌های قدیمی (که سند می‌گوید نامحدود نگه داشته و نمایش داده شوند)
        // بیرون از محدودهٔ چارت می‌افتند و دیده نمی‌شوند. پس برای تایم‌فریم درشت‌تر از منبع،
        // ابتدا تاریخچهٔ عمیقِ همان تایم‌فریم از فید گرفته می‌شود (با بازگشت به تجمیع محلی در
        // صورت نبود شبکه).
        val srcStep = listOf(medianStepSec(modeCandles), medianStepSec(fineCandles))
            .filter { it > 0L }.minOrNull() ?: 0L
        val coarserThanSource = srcStep > 0L && newTf.toLong() > srcStep
        val cached = tfFeedCache[newTf]
        val canAggregateLocally = (!modeFromFeed && fineCandles.isNotEmpty() && medianStepSec(fineCandles) in 1L..newTf.toLong()) ||
            (modeCandles.isNotEmpty() && medianStepSec(modeCandles) in 1L..newTf.toLong())
        if (!forceDownload) {
            if (newTf == cfg.tf2 && modeCandles.isNotEmpty()) {
                setDisplayCandles(modeCandles)
                return
            }
            if (cached != null && cached.isNotEmpty()) {
                setDisplayCandles(mergeDisplayWithMode(cached, newTf))
                return
            }
            // تایم‌فریم ساختار: تاریخچهٔ عمیقِ ثبت‌شده (structureCandles) باید بر تجمیع کوتاهِ
            // سری تریگر۲ ترجیح داشته باشد تا باکس‌های قدیمی ساختار در محدودهٔ چارت بمانند.
            if (newTf == cfg.tfS) {
                val deep = availableStructureHistory(newTf)
                val localAgg = if (canAggregateLocally) aggregateBestAvailable(newTf) else emptyList()
                val deepStartsEarlier = deep.isNotEmpty() &&
                    (localAgg.isEmpty() || deep.first().t < localAgg.first().t)
                if (deepStartsEarlier) {
                    setDisplayCandles(
                        if (localAgg.isEmpty()) deep else mergeDisplayWithMode(mergeAtTimeframe(deep, localAgg, newTf), newTf)
                    )
                    return
                }
            }
            // فقط برای «تایم‌فریم ساختار» عمق بیشتر از فید گرفته می‌شود (باکس‌های قدیمی ساختار
            // باید در محدودهٔ چارت بمانند)؛ برای بقیهٔ تایم‌فریم‌ها همان تجمیع محلی سریع کافی است.
            if (canAggregateLocally && !(coarserThanSource && modeFromFeed && newTf == cfg.tfS)) {
                val local = aggregateBestAvailable(newTf)
                if (local.isNotEmpty()) {
                    setDisplayCandles(local)
                    return
                }
            }
        }

        // ۲) در صورت نیاز به دریافت از شبکه (مثلاً تایم‌فریم ریزتر در حالت آنلاین یا forceDownload):
        //    فقط کندل‌های نمایشی چارت به‌روز می‌شوند و موتور/باکس‌های مود در حافظه دست‌نخورده می‌مانند.
        busy = true
        busyText = "در حال دریافت تاریخچهٔ ${com.alisport.goldpin.core.Tf.label(newTf)}…"
        lastFeedError = null
        notifyUi()
        net.execute {
            try {
                val (chart, base) = fetchChartHistoryBlocking(newTf)
                io.execute {
                    try {
                        baseTfSec = base
                        if (chart.isNotEmpty()) tfFeedCache[newTf] = chart
                        // §4-5: تاریخچهٔ تایم‌فریم ساختار باید ثبت/ذخیره شود تا باکس‌های قدیمی و
                        // توالی ساختار در بازسازی‌های بعدی هم در دسترس باشند.
                        if (chart.isNotEmpty() && newTf == cfg.tfS) {
                            rememberStructureHistory(chart, cfg.tfS)
                        }
                        if (modeCandles.isEmpty()) {
                            modeFromFeed = true
                            setCandles(chart, rebuildNow = true)
                        } else {
                            setDisplayCandles(mergeDisplayWithMode(chart, newTf))
                        }
                    } finally {
                        main.post {
                            lastFeedAt = System.currentTimeMillis()
                            lastFeedError = null
                            busy = false
                            busyText = ""
                            notifyUi()
                        }
                    }
                }
            } catch (e: Exception) {
                // بدون شبکه: از بهترین دادهٔ موجود در حافظه تجمیع می‌کنیم (بدون بازسازی موتور)
                io.execute {
                    try {
                        val chart = aggregateBestAvailable(newTf)
                        if (modeCandles.isEmpty() && chart.isNotEmpty()) {
                            setCandles(chart, rebuildNow = true)
                        } else if (chart.isNotEmpty()) {
                            setDisplayCandles(chart)
                        }
                    } finally {
                        main.post {
                            lastFeedError = com.alisport.goldpin.util.Fa.short(
                                "تجمیع داخلی از دادهٔ موجود: ${e.message}", 160
                            )
                            busy = false
                            busyText = ""
                            notifyUi()
                        }
                    }
                }
            }
        }
    }

    // ── ذخیره / بازیابی ────────────────────────────────────────────────────────
    fun buildJson(): String {
        val meta = linkedMapOf<String, Any?>(
            "symbol" to symbol,
            "baseTfSec" to baseTfSec,
            "chartTfSec" to chartTfSec,
            "depth" to depth,
            "appVersion" to 2
        )
        // ⚠ موتور/کارگزار در نخ IO تغییر می‌کنند؛ بدون قفل، Store.save روی فهرست‌های در حال
        // تغییر می‌افتاد و ConcurrentModificationException می‌داد (ذخیرهٔ خودکار حین لایو).
        // ⚠ نشست بک‌تست داخلی: کندل‌ها **نویسنده نمی‌شوند**.
        // یک سال M1 در JSON ≈۲۲MB می‌شد (اندازه‌گیری‌شده) و ذخیرهٔ خودکار هر بار آن را
        // می‌نوشت؛ در حالی که همین کندل‌ها از فایل داده (۵MB gz) قابل خواندن دوباره‌اند.
        // نتیجه: فایل نشست داخلی ۳۶۰KB می‌ماند و باکس‌های ساختار/LV/HV کامل ذخیره می‌شوند.
        val persistedCandles = when {
            sessionMode == SessionMode.INTERNAL -> emptyList()
            modeCandles.isNotEmpty() -> modeCandles
            else -> candles
        }
        val btMeta = if (sessionMode == SessionMode.INTERNAL) backtestResultSignature() else ""
        return synchronized(stateLock) {
            Store.save(
                cfg, engine, broker, persistedCandles,
                meta + mapOf("session" to sessionMode.name, "btSig" to btMeta),
                structureCandles = structureCandles
            )
        }
    }

    fun saveToAutoFile(ctx: Context): String {
        val t0 = System.currentTimeMillis()
        return try {
            // ── گاردِ «فایل سالم را با وضعیت خالی خراب نکن» ─────────────────────────────
            // باگ گزارش‌شده: بعد از مدتی در لایو، همهٔ سفارش‌ها/معاملات غیب می‌شدند. یکی از
            // مسیرها این بود: سرویس/اپ با وضعیت خالی بالا می‌آمد و ذخیرهٔ خودکار، همان خالی را
            // روی فایل پرِ قبلی می‌نوشت ⇒ تاریخچه برای همیشه از بین می‌رفت.
            // حالا اگر حافظه خالی است ولی فایل موجودِ پر داریم، نوشتن انجام نمی‌شود.
            if (!stateLoaded && engine.processed == 0 && broker.orders.isEmpty() && broker.trades.isEmpty()) {
                val f = sessionFile(ctx)
                if (f.exists() && f.length() > 4096) {
                    Log.w(Log.CAT_STORE, "ذخیرهٔ خودکار رد شد — وضعیت خالی روی فایل سالم نوشته نمی‌شود",
                        "فایل=${f.name} بایت=${f.length()}")
                    return "ذخیره نشد: وضعیت بازیابی نشده است (فایل سالم دست‌نخورده ماند)"
                }
            }
            busy = true
            val json = buildJson()
            val main = sessionFile(ctx)
            try {   // پشتیبان از آخرین نسخهٔ سالم
                if (main.exists()) {
                    val tmp = java.io.File(main.parentFile, main.name + ".tmp")
                    main.copyTo(tmp, overwrite = true)
                    val bak = java.io.File(main.parentFile, main.name + ".bak")
                    if (bak.exists()) bak.delete()
                    tmp.renameTo(bak)
                }
            } catch (e: Exception) { Log.w(Log.CAT_STORE, "نسخهٔ پشتیبان ساخته نشد", e.message ?: "") }
            Storage.writeText(main, json)
            Log.i(Log.CAT_STORE, "ذخیرهٔ خودکار انجام شد",
                "نشست=${sessionMode.fa} بایت=${json.length} کندل=${candles.size} باکس=${engine.zones.size} سفارش=${broker.orders.size} معامله=${broker.trades.size} مدت=${System.currentTimeMillis() - t0}ms")
            "ذخیره شد: ${sessionFile(ctx).absolutePath}"
        } catch (e: Exception) {
            Log.e(Log.CAT_STORE, "ذخیرهٔ خودکار ناموفق", e)
            "خطای ذخیره: ${e.message}"
        } finally {
            busy = false
        }
    }

    fun loadFromAutoFile(ctx: Context): String {
        val f: File = Storage.autoFile(ctx)
        if (!f.exists()) return "فایل ذخیره‌ای پیدا نشد"
        return try {
            loadFromText(Storage.readText(f))
        } catch (e: Exception) {
            // هرگز اپ را با فایل خراب از کار نینداز؛ فقط اطلاع بده
            Log.e(Log.CAT_STORE, "بازیابی خودکار ناموفق بود", e)
            "بازیابی نشد: ${e.message}"
        }
    }

    /** بارگذاری کامل — وضعیت دقیقاً همان‌طور که ذخیره شده بود برمی‌گردد. */
    /**
     * @param applySettings اگر true باشد (شروع سرد اپ) تنظیمات داخل فایل روی تنظیمات فعلی
     *   نوشته می‌شود. هنگام «تعویض نشست» باید false باشد؛ وگرنه تنظیماتی که کاربر همین
     *   الان عوض کرده با نسخهٔ قدیمیِ داخل فایل زنده به عقب برمی‌گردد (باگ «بک‌تست تغییر
     *   مود/استراتژی را نمی‌گیرد»).
     */
    fun loadFromText(json: String, applySettings: Boolean = true): String {
        val t0 = System.currentTimeMillis()
        Log.i(Log.CAT_STORE, "شروع بازیابی وضعیت", "بایت=${json.length} اعمال‌تنظیمات=$applySettings")
        return try {
            // تجزیهٔ JSON (سنگین) بیرون از قفل؛ کپی وضعیت داخل قفل
            val ld = Store.load(json)
            val m = ld.meta
            symbol = m["symbol"]?.toString() ?: symbol
            baseTfSec = (m["baseTfSec"] as? Long)?.toInt() ?: baseTfSec
            depth = (m["depth"] as? Long)?.toInt() ?: depth
            val settingsDiffer = signatureOf(ld.cfg) != signatureOf(cfg)
            synchronized(stateLock) {
                if (applySettings) copySettings(ld.cfg, cfg)
                chartTfSec = if (applySettings) ((m["chartTfSec"] as? Long)?.toInt() ?: cfg.tf2) else chartTfSec
                engine.reset()
                broker.reset()
                // انتقال وضعیت بازیابی‌شده به نمونه‌های سراسری
                copyEngine(ld.eng, engine)
                copyBroker(ld.broker, broker)
                // باکس‌های ناحیهٔ بازیابی‌شده از فایل، باکس‌های معتبرِ همین پیکربندی‌اند؛ پس اگر بعداً
                // موتور روی پنجرهٔ دادهٔ کوتاه‌تری بازسازی شد، ناحیه‌های ابطال‌نشده دوباره فراخوانی شوند (§4-5 سند).
                zoneKeepKey = zoneKey()
                engine.broker = broker
                broker.engine = engine
                engine.barCommitHook = { cd -> broker.onBar(cd, live = false) }
                val restoredMode = ArrayList(ld.candles)
                val step = medianStepSec(restoredMode)
                if (restoredMode.isNotEmpty() && (fineCandles.isEmpty() || (step in 1L..90L && restoredMode.size >= fineCandles.size))) {
                    fineCandles = restoredMode
                }
                modeCandles = restoredMode
                structureCandles = ArrayList(ld.structureCandles)
                val latestClosedIndex = engine.processed - 1
                val latestClosed = restoredMode.getOrNull(latestClosedIndex)
                if (latestClosed != null) {
                    engine.ensureCurrentTrendMarker(latestClosed.t, latestClosed.c, latestClosedIndex)
                }
                candles = if (chartTfSec == cfg.tf2 || sameTimeframe(step, chartTfSec.toLong()) || restoredMode.isEmpty()) {
                    restoredMode
                } else {
                    aggregateBestAvailable(chartTfSec).ifEmpty { restoredMode }
                }
                publishSnapshot()
            }
            stateLoaded = true
            if (!applySettings && settingsDiffer) {
                // تنظیمات کاربر مقدس است: وضعیت بازگردانده می‌شود، ولی موتور با
                // تنظیمات فعلی از نو ساخته می‌شود تا باکس/سفارش‌های مود قبلی نمانند.
                Log.i(Log.CAT_STORE, "تنظیمات فعلی با فایل زنده فرق دارد → بازسازی با تنظیمات فعلی")
                rebuild()
            }
            notifyUi()
            Log.i(Log.CAT_STORE, "بازیابی موفق",
                "کندل=${candles.size} باکس=${engine.zones.size} (فعال=${engine.zones.count { it.status == ZoneStatus.ACTIVE }} " +
                    "پاک‌شده=${engine.zones.count { it.status == ZoneStatus.DELETED }} ردشده=${engine.zones.count { it.status == ZoneStatus.REJECTED }}) " +
                    "سفارش=${broker.orders.size} معامله=${broker.trades.size} موجودی=${Fa.n(broker.balance, 2)} مدت=${System.currentTimeMillis() - t0}ms")
            "بازیابی شد: ${com.alisport.goldpin.util.Fa.d(candles.size.toString())} کندل · " +
                    "${com.alisport.goldpin.util.Fa.d(broker.trades.size.toString())} معامله · " +
                    "${com.alisport.goldpin.util.Fa.d(engine.zones.size.toString())} باکس ناحیه"
        } catch (e: Exception) {
            Log.e(Log.CAT_STORE, "بازیابی ناموفق", e)
            "خطای بازیابی: ${e.message}"
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun copySettings(from: Settings, to: Settings) {
        val c = from.copy()
        to.tfMode = c.tfMode; to.tfS = c.tfS; to.tfM = c.tfM; to.tf1 = c.tf1; to.tf2 = c.tf2
        to.vpRows = c.vpRows; to.vpVA = c.vpVA; to.vpSmooth = c.vpSmooth; to.distMode = c.distMode
        to.minZoneRows = c.minZoneRows; to.drawZones = c.drawZones; to.showRejectedZones = c.showRejectedZones
        to.clearUsedZones = c.clearUsedZones; to.maxZoneBoxes = c.maxZoneBoxes; to.showPocVA = c.showPocVA
        to.maxSetups = c.maxSetups; to.midInvalidClose = c.midInvalidClose
        to.hvScanFirstTouch = c.hvScanFirstTouch; to.hvMarkFirstIncrease = c.hvMarkFirstIncrease
        to.rejectCandleNext = c.rejectCandleNext
        to.mintick = c.mintick; to.slBufTicks = c.slBufTicks; to.entryBufTicks = c.entryBufTicks; to.minTPunits = c.minTPunits
        to.slipTicks = c.slipTicks
        to.strategy = c.strategy; to.tpUnits = c.tpUnits
        to.maxSlUnits = c.maxSlUnits
        to.minSlUnits = c.minSlUnits
        to.trigger2Fade = c.trigger2Fade; to.trigger2TargetR = c.trigger2TargetR; to.minSlCostMult = c.minSlCostMult
        to.useRiskPct = c.useRiskPct; to.riskPct = c.riskPct; to.equityPct = c.equityPct
        to.maxLeverage = c.maxLeverage; to.roundQty = c.roundQty
        to.contractSize = c.contractSize; to.initialEquity = c.initialEquity
        // §۲۰ سند: مدیریت مالی و مدیریت زیان
        to.mmMode = c.mmMode; to.minLotAuto = c.minLotAuto; to.minLotManual = c.minLotManual
        to.divAuto = c.divAuto; to.divManual = c.divManual
        to.cmsnAuto = c.cmsnAuto; to.cmsnManual = c.cmsnManual
        to.cmsnAutoValue = c.cmsnAutoValue; to.cmsnAutoAt = c.cmsnAutoAt
        to.crtEnabled = c.crtEnabled; to.crtPct = c.crtPct
        to.mixedGrowthPct = c.mixedGrowthPct
        to.lotStep = c.lotStep; to.lostResetAtFinalTp = c.lostResetAtFinalTp
    }

    /** کپی وضعیت موتورِ بازگردانی‌شده به موتور سراسری (تا ارجاع‌ها یکسان بماند) */
    private fun copyEngine(src: Engine, dst: Engine) {
        dst.aggS.reset(); dst.aggM.reset(); dst.agg1.reset()
        copyAgg(src.aggS, dst.aggS); copyAgg(src.aggM, dst.aggM); copyAgg(src.agg1, dst.agg1)
        dst.structBars.clear(); dst.structBars.addAll(src.structBars)
        dst.microS.clear(); dst.microS.addAll(src.microS)
        dst.pins.clear(); dst.pins.addAll(src.pins)
        dst.setups.clear(); dst.setups.addAll(src.setups)
        dst.zones.clear(); dst.zones.addAll(src.zones)
        dst.t1Zones.clear(); dst.t1Zones.addAll(src.t1Zones)
        dst.t2Zones.clear(); dst.t2Zones.addAll(src.t2Zones)
        dst.markers.clear(); dst.markers.addAll(src.markers)
        dst.events.clear(); dst.events.addAll(src.events)
        dst.cnt.setup = src.cnt.setup; dst.cnt.touch = src.cnt.touch; dst.cnt.mid = src.cnt.mid
        dst.cnt.lv = src.cnt.lv; dst.cnt.hv = src.cnt.hv; dst.cnt.entry = src.cnt.entry
        dst.cnt.done = src.cnt.done; dst.cnt.zoneCount = src.cnt.zoneCount; dst.cnt.zoneRejected = src.cnt.zoneRejected
        dst.trend = src.trend; dst.HH = src.HH; dst.HL = src.HL; dst.LL = src.LL; dst.LH = src.LH
        dst.tempBoxTop = src.tempBoxTop; dst.tempBoxBot = src.tempBoxBot
        dst.hlPinLo = src.hlPinLo; dst.hlPinRef = src.hlPinRef; dst.hlPinHi = src.hlPinHi; dst.hlPinSeq = src.hlPinSeq
        dst.hhPinHi = src.hhPinHi; dst.hhPinRef = src.hhPinRef; dst.hhPinLo = src.hhPinLo; dst.hhPinSeq = src.hhPinSeq
        dst.lhPinHi = src.lhPinHi; dst.lhPinRef = src.lhPinRef; dst.lhPinLo = src.lhPinLo; dst.lhPinSeq = src.lhPinSeq
        dst.llPinLo = src.llPinLo; dst.llPinRef = src.llPinRef; dst.llPinHi = src.llPinHi; dst.llPinSeq = src.llPinSeq
        dst.seqStart = src.seqStart
        dst.bullBU1Low = src.bullBU1Low; dst.bullBEHigh = src.bullBEHigh
        dst.bullBU2Low = src.bullBU2Low; dst.bullBU2Bi = src.bullBU2Bi
        dst.bearBE1High = src.bearBE1High; dst.bearBULow = src.bearBULow
        dst.bearBE2High = src.bearBE2High; dst.bearBE2Bi = src.bearBE2Bi
        dst.bullSetup = src.bullSetup; dst.bearSetup = src.bearSetup
        dst.expectedReady = src.expectedReady
        dst.lowestBUpin = src.lowestBUpin; dst.highestBE = src.highestBE
        dst.lastBUpin = src.lastBUpin; dst.lastBEpin = src.lastBEpin
        dst.buActive = src.buActive; dst.beActive = src.beActive
        dst.pinSeq = src.pinSeq; dst.buSeq = src.buSeq; dst.beSeq = src.beSeq
        dst.lastBUpinRef = src.lastBUpinRef; dst.lastBUpinHi = src.lastBUpinHi
        dst.lastBEpinRef = src.lastBEpinRef; dst.lastBEpinLo = src.lastBEpinLo
        dst.lastZoneTop = src.lastZoneTop; dst.lastZoneBot = src.lastZoneBot
        dst.zoneRefH = src.zoneRefH; dst.zoneRefL = src.zoneRefL   // §18-22: مرجع ابطال نواحی درونی
        dst.lastPocPx = src.lastPocPx; dst.lastProfBars = src.lastProfBars
        dst.lastEntryPx = src.lastEntryPx; dst.lastSlPx = src.lastSlPx; dst.lastTpPx = src.lastTpPx
        dst.lastResult = src.lastResult
        dst.prevT1Vol = src.prevT1Vol; dst.prevChartVol = src.prevChartVol
        dst.zoneSeq = src.zoneSeq; dst.setupSeq = src.setupSeq; dst.processed = src.processed
        dst.curBi = src.curBi; dst.curT = src.curT
    }

    private fun copyAgg(s: Agg, d: Agg) {
        d.reset()
        d.periods = s.periods
        d.cur = s.cur?.let { Candle(it.bi, it.t, it.o, it.h, it.l, it.c, it.v) }
        d.cl = s.cl?.let { Candle(it.bi, it.t, it.o, it.h, it.l, it.c, it.v) }
        d.closedNow = false
    }

    private fun copyBroker(src: PaperBroker, dst: PaperBroker) {
        dst.orders.clear(); dst.orders.addAll(src.orders)
        dst.trades.clear(); dst.trades.addAll(src.trades)
        dst.balance = src.balance; dst.equity = src.equity
        dst.maxEquity = src.maxEquity; dst.maxDrawdown = src.maxDrawdown
        dst.orderSeq = src.orderSeq; dst.tradeSeq = src.tradeSeq
        dst.mm.lost = src.mm.lost; dst.mm.cmsnLost = src.mm.cmsnLost   // §۲۰
        // §۲۰ سند: «اگر Capital = 2×NakedCapital شد، NakedCapital دو برابر می‌شود»؛ این حالت
        // باید بعد از بازیابی فایل هم حفظ شود، وگرنه با هر بار لود، حجم/ریسک به مقدار اولیه برمی‌گردد.
        dst.mm.restoreNaked(src.mm.nakedCapital())
        // v1.4.9: همهٔ سفارش‌ها/پوزیشن‌های فعال کپی شوند.
        for (o in src.pendingOrders) dst.restorePending(o)
        for (t in src.openTrades) dst.restoreOpen(t)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  نشست بک‌تست داخلی (طرح B) — دیتاست M1 انتخاب‌شده با حجم، جدا از فید زنده
    //
    //  چرا نشست جدا و نه «مرج»: دیتاست آزاد و فید زنده دو فید متفاوت‌اند
    //  (اختلاف قیمت میانه −۰٫۱۶۵ دلار و مقیاس حجم ≈۲۵٬۰۰۰ برابر — اندازه‌گیری‌شده).
    //  هر اتصالی بین این دو، یک پرش قیمت و یک کندل حجمی جعلی روی درز می‌سازد و
    //  باکس‌های داخلی را وارد سری زنده می‌کند. با جداسازی، تداخل از نظر ساختاری حذف می‌شود.
    // ═══════════════════════════════════════════════════════════════════════════

    /** ثبت نشست فعال در تنظیمات دستگاه. */
    private fun persistSessionMode() {
        try {
            appCtx?.getSharedPreferences("goldpin", Context.MODE_PRIVATE)
                ?.edit()?.putString("session_mode", sessionMode.name)?.apply()
        } catch (e: Exception) { Log.w(Log.CAT_APP, "ثبت نشست ناموفق", e.message ?: "") }
    }

    /** فایل نشست فعال (زنده یا بک‌تست). */
    private fun sessionFile(ctx: Context): File =
        if (sessionMode == SessionMode.INTERNAL) Storage.internalFile(ctx, activeBacktestYears) else Storage.autoFile(ctx)

    /** انتخاب بازهٔ دیتاست؛ اگر در بک‌تست باشیم، اجرای قبلی کهنه علامت می‌خورد. */
    fun selectBacktestYears(ctx: Context, years: Int): String {
        if (Feed.BACKTEST_DATASETS.none { it.years == years }) return "بازهٔ بک‌تست پشتیبانی نمی‌شود"
        if (selectedBacktestYears == years) return "بازهٔ ${Fa.d(years.toString())} سال همین حالا انتخاب شده است"
        selectedBacktestYears = years
        try { ctx.getSharedPreferences("goldpin", Context.MODE_PRIVATE).edit()
            .putInt("backtest_years", years).apply() } catch (_: Exception) { }
        if (sessionMode == SessionMode.INTERNAL && activeBacktestYears != years) backtestStale = true
        notifyUi()
        return "بازهٔ ${Fa.d(years.toString())} سال انتخاب شد" +
            if (hasBacktestData(ctx, years)) " — آمادهٔ اجرای بک‌تست" else " — ابتدا داده را دانلود کنید"
    }

    /** آیا فایل بازهٔ انتخابی (یا بازهٔ درخواستی) روی گوشی موجود است؟ */
    fun hasBacktestData(ctx: Context, years: Int = selectedBacktestYears): Boolean {
        val f = Storage.dataFile(ctx, years)
        return f.exists() && f.length() > 1_000_000
    }

    /** وضعیت فایل داده برای نمایش در تنظیمات. */
    fun backtestDataText(ctx: Context, years: Int = selectedBacktestYears): String {
        val spec = Feed.backtestDataset(years)
        val f = Storage.dataFile(ctx, years)
        val bars = Fa.d(spec.bars.toString())
        if (f.exists() && f.length() > 1_000_000) {
            val mb = f.length() / 1048576.0
            return "${Fa.d(years.toString())} سال دانلود شده · ${Fa.n(mb, 2)} مگابایت · $bars کندل M1"
        }
        val mb = spec.bytesGz / 1048576.0
        return "${Fa.d(years.toString())} سال دانلود نشده · حدود ${Fa.n(mb, 1)} مگابایت · $bars کندل M1"
    }

    /** دانلود بازهٔ انتخابی از مخزن عمومی با بررسی SHA-256. */
    fun downloadBacktestData(ctx: Context, years: Int = selectedBacktestYears, onDone: (String) -> Unit = {}) {
        val spec = try { Feed.backtestDataset(years) } catch (e: Exception) {
            main.post { onDone("بازهٔ انتخابی معتبر نیست: ${e.message}") }
            return
        }
        io.execute {
            busy = true
            busyText = "دانلود دادهٔ ${Fa.d(years.toString())} ساله…"
            backtestProgress = 0f
            notifyUi()
            var msg: String
            try {
                val part = Storage.dataPartFile(ctx, years)
                if (part.exists()) part.delete()
                val onProgress: (Long, Long) -> Unit = { got, total ->
                    backtestProgress = if (total > 0) (got.toFloat() / total) else -1f
                    busyText = if (total > 0)
                        "دانلود ${Fa.d(years.toString())} ساله: ${(got * 100 / total)}٪ (${Fa.n(got / 1048576.0, 1)} از ${Fa.n(total / 1048576.0, 1)} مگابایت)"
                    else "دانلود ${Fa.d(years.toString())} ساله: ${Fa.n(got / 1048576.0, 1)} مگابایت"
                    notifyUi()
                }
                try {
                    Feed.downloadTo(spec.url, part, onProgress)
                } catch (primaryError: Exception) {
                    val fallback = spec.fallbackUrl
                    if (fallback == null) throw primaryError
                    part.delete()
                    Log.w(Log.CAT_FEED, "دانلود دیتاست ریپو ناموفق؛ تلاش با Release قبلی",
                        primaryError.message ?: "")
                    Feed.downloadTo(fallback, part, onProgress)
                }
                val hash = Feed.sha256(part)
                if (!hash.equals(spec.sha256, ignoreCase = true)) {
                    part.delete()
                    throw FeedException("هش فایل ${years} ساله با مقدار معتبر نمی‌خواند — دوباره تلاش کنید")
                }
                val dest = Storage.dataFile(ctx, years)
                if (dest.exists()) dest.delete()
                if (!part.renameTo(dest)) { part.copyTo(dest, overwrite = true); part.delete() }
                msg = "دادهٔ ${Fa.d(years.toString())} ساله ذخیره شد: ${Fa.n(dest.length() / 1048576.0, 2)} مگابایت · ${Fa.d(spec.bars.toString())} کندل"
                Log.i(Log.CAT_FEED, "دادهٔ بک‌تست دانلود شد",
                    "سال=$years بایت=${dest.length()} هش=${hash.take(12)}…")
            } catch (e: Exception) {
                Log.e(Log.CAT_FEED, "دانلود دادهٔ بک‌تست ناموفق", e)
                msg = "خطای دانلود دادهٔ ${years} ساله: ${e.message}"
            } finally {
                backtestProgress = -1f
                busy = false
                busyText = ""
            }
            main.post { notifyUi(); onDone(msg) }
        }
    }

    /** پاک کردن دیتاست انتخاب‌شده (نتیجهٔ بک‌تست حذف نمی‌شود). */
    fun deleteBacktestData(ctx: Context, years: Int = selectedBacktestYears): String {
        var n = 0
        for (f in listOf(Storage.dataFile(ctx, years), Storage.dataPartFile(ctx, years))) {
            if (f.exists() && f.delete()) n++
        }
        notifyUi()
        return if (n > 0) "دادهٔ ${Fa.d(years.toString())} ساله از حافظهٔ اپ پاک شد — برای اجرای دوباره دانلودش کنید"
               else "فایلی برای پاک کردن نبود"
    }

    /**
     * پاک‌سازی کامل حافظه برای تعویض نشست — **هیچ فایلی حذف نمی‌شود**.
     * (برخلاف [wipe] که دادهٔ کاربر را هم پاک می‌کند.)
     */
    private fun resetStateForSession() {
        synchronized(stateLock) {
            candles = emptyList()
            modeCandles = emptyList()
            fineCandles = emptyList()
            structureCandles = emptyList()
            internalRaw = emptyList()
            tfFeedCache.clear()
            modeFromFeed = false
            zoneKeepKey = ""
            broker.reset()
            engine.reset()
            engine.broker = broker
            broker.engine = engine
            engine.barCommitHook = { cd -> broker.onBar(cd, live = false) }
            publishSnapshot()
        }
    }

    /** خلاصهٔ وضعیت موتور/کارگزار برای نمایش بعد از اجرا یا بارگذاری. */
    private fun backtestSummary(): String {
        val zones = engine.zones
        val lv = engine.t1Zones.size      // باکس‌های تریگر۱ (LV/ولوم کم)
        val hv = engine.t2Zones.size      // باکس‌های تریگر۲ (HV/ولوم زیاد)
        return "داده=${Fa.d(activeBacktestYears.toString())} سال M1 · پردازش=${Fa.d(engine.processed.toString())} · ناحیه=${Fa.d(zones.size.toString())} " +
            "(فعال=${Fa.d(zones.count { it.status == ZoneStatus.ACTIVE }.toString())}) · " +
            "LV=${Fa.d(lv.toString())} · HV=${Fa.d(hv.toString())} · " +
            "سفارش=${Fa.d(broker.orders.size.toString())} · معامله=${Fa.d(broker.trades.size.toString())} · " +
            "سرمایه=${Fa.n(broker.balance, 2)}"
    }

    /**
     * اجرای کامل موتور روی دیتاست انتخاب‌شدهٔ ۱/۲/۳/۱۰ساله — با گزارش پیشرفت.
     *
     * `feed` را قطعه‌قطعه صدا می‌زنیم تا نوار پیشرفت واقعی باشد؛ `feed` از `processed`
     * ادامه می‌دهد، پس نه کندلی دوبار پردازش می‌شود و نه کندلی جا می‌افتد. کندل آخر
     * فقط در پاس نهایی «بسته» حساب می‌شود.
     */
    /**
     * سری کندل «تریگر ۲» (تایم‌فریمی که موتور روی آن اجرا می‌شود) از دادهٔ خام M1.
     *
     * پیش‌تر همیشه خودِ M1 به موتور داده می‌شد؛ یعنی اگر کاربر مود را عوض می‌کرد،
     * موتور باز هم دادهٔ یک‌دقیقه‌ای را به‌عنوان تایم‌فریم تریگر۲ می‌دید و تغییر مود
     * هیچ اثری نداشت. حالا برای هر tf2 دوباره از M1 تجمیع می‌شود.
     */
    private fun internalModeSeries(raw: List<Candle>): List<Candle> {
        if (raw.isEmpty()) return raw
        val step = medianStepSec(raw)
        if (cfg.tf2 <= step || sameTimeframe(step, cfg.tf2.toLong())) return raw
        return Feed.aggregate(raw, cfg.tf2).ifEmpty { raw }
    }

    /** تجمیع دوبارهٔ سری‌های نمایشی از M1 — بدون دست‌زدن به نتیجهٔ موتور. */
    private fun internalResetSeries() {
        val raw = internalRaw
        if (raw.isEmpty()) return
        val mode = internalModeSeries(raw)
        synchronized(stateLock) {
            fineCandles = raw
            modeCandles = mode
            tfFeedCache.clear()
            tfFeedCache[cfg.tf2] = mode
            modeFromFeed = true
            candles = if (chartTfSec == cfg.tf2) mode else aggregateBestAvailable(chartTfSec).ifEmpty { mode }
            zoneKeepKey = ""
        }
        publishSnapshot()
    }

    private fun runInternalBlocking(ctx: Context, raw: List<Candle>): String {
        val t0 = System.currentTimeMillis()
        internalRaw = raw
        val mode = internalModeSeries(raw)
        synchronized(stateLock) {
            fineCandles = raw
            modeCandles = mode
            tfFeedCache.clear()
            tfFeedCache[cfg.tf2] = mode
            modeFromFeed = true
            structureCandles = emptyList()
            candles = if (chartTfSec == cfg.tf2) mode else aggregateBestAvailable(chartTfSec).ifEmpty { mode }
            zoneKeepKey = ""
            broker.reset()
            engine.reset()
            engine.reserveTimeCapacity(mode.size)
            engine.broker = broker
            broker.engine = engine
            engine.barCommitHook = { cd -> broker.onBar(cd, live = false) }
            publishSnapshot()
        }
        notifyUi()

        val prevAlerts = Alerts.inBacktest
        Alerts.inBacktest = true          // بک‌تست هرگز بنر/نوتیفیکیشن/صدا نمی‌دهد
        try {
            // موتور روی سری تریگر۲ همان مودِ انتخاب‌شده اجرا می‌شود (نه همیشه M1)
            val chunk = 20_000
            var done = 0
            while (done < mode.size) {
                val upto = minOf(mode.size, done + chunk)
                engine.feed(mode.subList(0, upto), lastIsClosed = false)
                done = upto
                backtestProgress = done.toFloat() / mode.size
                backtestProgressText = "${Fa.d(done.toString())} از ${Fa.d(mode.size.toString())}"
                busyText = "بک‌تست داخلی: ${(backtestProgress * 100).toInt()}٪ — $backtestProgressText کندل"
                notifyUi()
            }
            engine.feed(mode, lastIsClosed = true)
            // مارکر «روند فعلی» تا آخرین کندل بسته
            val lastClosed = mode.getOrNull(engine.processed - 1)
            if (lastClosed != null) engine.ensureCurrentTrendMarker(lastClosed.t, lastClosed.c, engine.processed - 1)
        } finally {
            Alerts.inBacktest = prevAlerts
        }

        zoneKeepKey = zoneKey()
        backtestStale = false
        backtestProgress = -1f
        backtestProgressText = ""
        synchronized(stateLock) { publishSnapshot() }
        val ms = System.currentTimeMillis() - t0
        backtestInfo = backtestSummary()
        Log.i(Log.CAT_ENGINE, "بک‌تست داخلی تمام شد",
            "مدت=${ms}ms کندل‌M1=${raw.size} کندل‌مود=${mode.size} tf2=${com.alisport.goldpin.core.Tf.label(cfg.tf2)} " +
                "استراتژی=${cfg.strategy} ناحیه=${engine.zones.size} LV=${engine.t1Zones.size} HV=${engine.t2Zones.size} " +
                "سفارش=${broker.orders.size} معامله=${broker.trades.size}")
        saveToAutoFile(ctx)               // فایل نشست داخلی: بدون کندل ⇒ ≈۳۶۰KB
        return "بک‌تست تمام شد (${ms / 1000} ثانیه)\n$backtestInfo"
    }

    /** بازیابی نشست داخلی ذخیره‌شده + خواندن کندل‌ها از فایل داده. */
    private fun restoreInternalBlocking(ctx: Context, raw: List<Candle>): String {
        val f = Storage.internalFile(ctx, activeBacktestYears)
        if (!f.exists()) return runInternalBlocking(ctx, raw)
        busyText = "بازیابی نتیجهٔ بک‌تست…"
        notifyUi()
        val ld = Store.load(Storage.readText(f))
        val savedSig = ld.meta["btSig"]?.toString().orEmpty()
        // ── نقطهٔ حیاتی «اتصال تنظیمات به بک‌تست» ────────────────────────────────
        // اگر مود/استراتژی/پارامترها از آخرین اجرا عوض شده باشد، نتیجهٔ ذخیره‌شده دیگر
        // پاسخِ همین تنظیمات نیست. پیش‌تر فقط هشدار «کهنه» نشان داده می‌شد و کاربر پیام
        // می‌گرفت که بک‌تست تغییرات را نمی‌گیرد؛ حالا خودکار با تنظیمات تازه اجرا می‌شود.
        val legacyOneYearSig = activeBacktestYears == 1 && savedSig == backtestSignature()
        if (savedSig.isNotEmpty() && savedSig != backtestResultSignature() && !legacyOneYearSig) {
            Log.i(Log.CAT_ENGINE, "تنظیمات یا بازهٔ داده از آخرین بک‌تست عوض شده → اجرای مجدد خودکار",
                "قدیم=${savedSig.take(40)}… جدید=${backtestResultSignature().take(40)}…")
            return runInternalBlocking(ctx, raw)
        }
        internalRaw = raw
        val mode = internalModeSeries(raw)
        synchronized(stateLock) {
            engine.reset()
            broker.reset()
            copyEngine(ld.eng, engine)
            copyBroker(ld.broker, broker)
            engine.broker = broker
            broker.engine = engine
            engine.barCommitHook = { cd -> broker.onBar(cd, live = false) }
            fineCandles = raw
            modeCandles = mode
            tfFeedCache.clear()
            tfFeedCache[cfg.tf2] = mode
            modeFromFeed = true
            structureCandles = ArrayList(ld.structureCandles)
            candles = if (chartTfSec == cfg.tf2) mode else aggregateBestAvailable(chartTfSec).ifEmpty { mode }
            zoneKeepKey = zoneKey()
            backtestStale = false
            publishSnapshot()
        }
        backtestInfo = backtestSummary()
        notifyUi()
        return "نتیجهٔ بک‌تست بازگردانده شد (تنظیمات مطابق است)\n$backtestInfo"
    }

    /** بازیابی نشست زنده از فایل (یا نمونهٔ داخلی اگر فایلی نبود). */
    private fun restoreLiveBlocking(ctx: Context, applySettings: Boolean = true): String {
        val f = Storage.autoFile(ctx)
        if (!f.exists()) {
            val n = loadSampleBlocking(ctx)
            return "فایل زنده‌ای نبود — نمونهٔ کوچک داخلی بارگذاری شد (${Fa.d(n.toString())} کندل)"
        }
        return loadFromText(Storage.readText(f), applySettings)
    }

    /**
     * بارگذاری کامل نشست داخلی (روی نخ IO). اگر فایل نشست نباشد، بک‌تست اجرا می‌شود.
     */
    private fun loadInternalBlocking(ctx: Context): String {
        val years = selectedBacktestYears
        val previousActiveYears = activeBacktestYears
        busy = true
        busyText = "خواندن دادهٔ ${Fa.d(years.toString())} ساله…"
        backtestProgress = 0f
        notifyUi()
        val raw = try {
            if (!hasBacktestData(ctx, years)) {
                backtestProgress = -1f
                busy = false; busyText = ""
                return "دادهٔ ${Fa.d(years.toString())} ساله هنوز دانلود نشده است — ابتدا از تنظیمات دانلود کنید"
            }
            activeBacktestYears = years
            val spec = Feed.backtestDataset(years)
            Storage.dataFile(ctx, years).inputStream().use { Feed.parseBacktestStream(it, spec) }
        } catch (e: Exception) {
            activeBacktestYears = previousActiveYears
            backtestProgress = -1f
            busy = false; busyText = ""
            return "خطای خواندن دادهٔ بک‌تست: ${e.message}"
        }
        return try {
            restoreInternalBlocking(ctx, raw)
        } finally {
            busy = false
            busyText = ""
            backtestProgress = -1f
            notifyUi()
        }
    }

    /** اجرای دستی مجدد بک‌تست (از دکمهٔ تنظیمات). */
    fun runInternalBacktest(ctx: Context, onDone: (String) -> Unit = {}) {
        if (sessionMode != SessionMode.INTERNAL) {
            main.post { onDone("اول «تاریخچهٔ داخلی» را روشن کنید") }
            return
        }
        io.execute {
            val years = selectedBacktestYears
            val previousActiveYears = activeBacktestYears
            busy = true
            busyText = "خواندن دادهٔ ${Fa.d(years.toString())} ساله…"
            notifyUi()
            var msg = ""
            try {
                if (!hasBacktestData(ctx, years)) {
                    msg = "دادهٔ ${Fa.d(years.toString())} ساله هنوز دانلود نشده است — ابتدا از تنظیمات دانلود کنید"
                } else {
                    activeBacktestYears = years
                    val spec = Feed.backtestDataset(years)
                    val raw = Storage.dataFile(ctx, years).inputStream().use { Feed.parseBacktestStream(it, spec) }
                    msg = runInternalBlocking(ctx, raw)
                }
            } catch (e: Exception) {
                activeBacktestYears = previousActiveYears
                Log.e(Log.CAT_ENGINE, "اجرای بک‌تست ناموفق", e)
                msg = "خطای بک‌تست: ${e.message}"
            } finally {
                busy = false
                busyText = ""
                backtestProgress = -1f
                backtestProgressText = ""
            }
            main.post { notifyUi(); onDone(msg) }
        }
    }

    /**
     * روشن/خاموش کردن «تاریخچهٔ داخلی».
     *
     * ترتیب کار عمدی است: **اول** نشست فعلی ذخیره می‌شود، **بعد** حافظه پاک و نشست مقصد
     * بارگذاری می‌شود. پس هیچ‌وقت وضعیت زنده با دادهٔ بک‌تست جای‌گزین نمی‌شود.
     */
    fun switchSession(ctx: Context, target: SessionMode, onDone: (String) -> Unit = {}) {
        if (target == SessionMode.INTERNAL && !hasBacktestData(ctx, selectedBacktestYears)) {
            main.post { onDone("دادهٔ ${Fa.d(selectedBacktestYears.toString())} ساله دانلود نشده است — ابتدا از تنظیمات دانلود کنید") }
            return
        }
        if (target == sessionMode) {
            main.post { onDone("نشست «${target.fa}» همین حالا فعال است") }
            return
        }
        io.execute {
            busy = true
            busyText = if (target == SessionMode.INTERNAL) "رفتن به بک‌تست داخلی…" else "بازگشت به حالت زنده…"
            notifyUi()
            var msg: String
            try {
                saveToAutoFile(ctx)            // نشست فعلی، در فایل خودش
                resetStateForSession()
                sessionMode = target
                if (target == SessionMode.INTERNAL) activeBacktestYears = selectedBacktestYears
                backtestStale = false
                backtestInfo = ""
                // هنگام بازگشت به زنده، تنظیماتِ فعلیِ کاربر حفظ می‌شود (applySettings=false)
                msg = if (target == SessionMode.INTERNAL) loadInternalBlocking(ctx)
                      else restoreLiveBlocking(ctx, applySettings = false)
                persistSessionMode()
                Log.i(Log.CAT_APP, "نشست عوض شد", "→ ${target.name}")
            } catch (e: Exception) {
                Log.e(Log.CAT_APP, "تعویض نشست ناموفق", e)
                msg = "خطای تعویض نشست: ${e.message}"
            } finally {
                busy = false
                busyText = ""
                backtestProgress = -1f
            }
            main.post { notifyUi(); onDone(msg) }
        }
    }

    /** پاک کردن کامل */
    fun wipe() {
        Log.w(Log.CAT_APP, "پاک کردن همهٔ داده‌ها و وضعیت")
        synchronized(stateLock) {
            candles = emptyList()
            modeCandles = emptyList()
            structureCandles = emptyList()
            tfFeedCache.clear()
            modeFromFeed = false
            zoneKeepKey = ""      // پاک‌سازی کامل: هیچ باکس قدیمی‌ای نباید بازگردانده شود
            broker.reset()
            engine.reset()
            engine.broker = broker; broker.engine = engine
            engine.barCommitHook = { cd -> broker.onBar(cd, live = false) }
            publishSnapshot()
        }
        notifyUi()
    }

    /** هستهٔ همگام بارگذاری نمونهٔ داخلی — باید روی نخ پس‌زمینه صدا زده شود. */
    private fun loadSampleBlocking(ctx: Context): Int {
        val n = Feed.openSampleAsset(ctx).use { ins ->
            val text = Feed.readCsvStream(ins)
            val raw = Feed.parseCsv(text)
            fineCandles = ArrayList(raw)
            modeFromFeed = false
            tfFeedCache.clear()
            val modeList = if (cfg.tf2 <= Tf.M1) raw else Feed.aggregate(raw, cfg.tf2)
            Log.i(Log.CAT_FEED, "دادهٔ نمونهٔ داخلی بارگذاری شد", "کندل‌مود=${modeList.size} مود=${cfg.tfMode} (tf2=${com.alisport.goldpin.core.Tf.label(cfg.tf2)})")
            setCandles(modeList, rebuildNow = true)
            candles.size
        }
        return n
    }

    /** بارگذاری دادهٔ نمونهٔ داخلی (آفلاین) */
    fun loadSample(ctx: Context, onDone: (String) -> Unit) {
        io.execute {
            try {
                val n = loadSampleBlocking(ctx)
                main.post {
                    onDone("نمونهٔ داخلی بارگذاری شد: ${com.alisport.goldpin.util.Fa.d(n.toString())} کندل")
                }
            } catch (e: Exception) {
                Log.e(Log.CAT_FEED, "خطا در دادهٔ نمونه", e)
                main.post { onDone("خطا در نمونهٔ داخلی: ${e.message}") }
            }
        }
    }

    /**
     * بازیابی وضعیت هنگام بالا آمدن اپ — **روی نخ پس‌زمینه**.
     * پیش‌تر در onCreate به‌صورت همگام اجرا می‌شد: خواندن و تجزیهٔ یک فایل چند مگابایتی
     * روی نخ رابط = صفحهٔ شروع فریز و خطر ANR.
     * اگر فایل ذخیره‌ای نباشد، دادهٔ نمونه بارگذاری می‌شود.
     */
    fun restoreOrSample(ctx: Context, onDone: (String) -> Unit) {
        busy = true
        busyText = "در حال بازیابی وضعیت…"
        notifyUi()
        io.execute {
            val msg = try {
                // نصب تازه (هیچ فایل وضعیتی روی دستگاه نیست): تنظیمات کد، تنظیمات کاربر نیستند،
                // پس همان تنظیم آزموده‌شده با داده ([Tuned]) می‌نشیند تا اپ از همان اول با کارمزد
                // ۰٫۷ سفارش بدهد. اگر فایلی باشد، مهاجرت specRev در Store همان کار را فقط برای
                // مقدارهای دست‌نخوردهٔ سند ۷ انجام می‌دهد و تنظیم دستی کاربر دست‌نخورده می‌ماند.
                if (!Storage.autoFile(ctx).exists() && !Storage.internalFile(ctx, selectedBacktestYears).exists()) {
                    Tuned.apply(cfg)
                    Log.i(Log.CAT_CFG, "نصب تازه → تنظیم آزموده‌شده با داده اعمال شد",
                        "هدف=${Fa.n(cfg.trigger2TargetR, 1)}R بافرحدضرر=${Fa.n(cfg.slBufTicks, 0)}تیک " +
                            "سقف‌N=${Fa.n(cfg.maxSlUnits, 1)} کارمزد=${Fa.n(cfg.cmsnManual, 2)}")
                }
                if (sessionMode == SessionMode.INTERNAL) {
                    // کاربر آخرین بار در نشست بک‌تست بوده است.
                    if (hasBacktestData(ctx)) {
                        loadInternalBlocking(ctx)
                    } else {
                        sessionMode = SessionMode.LIVE
                        persistSessionMode()
                        Log.w(Log.CAT_APP, "نشست داخلی انتخاب شده بود ولی داده موجود نیست → بازگشت به زنده")
                        restoreLiveBlocking(ctx)
                    }
                } else {
                    restoreLiveBlocking(ctx)
                }
            } catch (e: Exception) {
                // هرگز اپ را با فایل خراب از کار نینداز؛ فقط اطلاع بده
                Log.e(Log.CAT_STORE, "بازیابی خودکار ناموفق بود", e)
                "بازیابی نشد: ${e.message}"
            }
            main.post {
                busy = false
                busyText = ""
                // اطلاع مهاجرت اسپرد خودکار (فقط یک‌بار، همان لحظهٔ بازکردن فایل قدیمی)
                if (cfg.cmsnMigratedFromAuto > 0.0) {
                    Alerts.bannerSink?.invoke(
                        "اسپرد خودکار بروکر (${Fa.n(cfg.cmsnMigratedFromAuto, 2)}) با فیلتر کارمزد، همهٔ سفارش‌ها را رد می‌کرد؛ " +
                            "به کارمزد دستی ${Fa.n(cfg.cmsnManual, 2)} تغییر کرد (تنظیمات ⑥). عدد واقعی بروکر خود را وارد کنید.",
                        0, "warn"
                    )
                    Log.w(Log.CAT_CFG, "مهاجرت اسپرد خودکار → دستی",
                        "قبلی=${Fa.n(cfg.cmsnMigratedFromAuto, 2)} جدید=${Fa.n(cfg.cmsnManual, 2)}")
                    cfg.cmsnMigratedFromAuto = 0.0
                }
                onDone(msg)
                ensureBrokerSpread()      // §۲۰: اگر cmsn «خودکار» است و هنوز اسپردی ذخیره نشده، یک‌بار بگیر
            }
        }
    }

    /** ورود CSV از حافظهٔ دستگاه */
    fun importCsv(ctx: Context, uri: android.net.Uri, baseTfGuess: Int, onDone: (String) -> Unit) {
        Log.i(Log.CAT_FEED, "ورود CSV", "uri=$uri پایه=${com.alisport.goldpin.core.Tf.label(baseTfGuess)}")
        io.execute {
            try {
                val text = Storage.readStream(ctx.contentResolver.openInputStream(uri)!!, gz = false)
                val raw = Feed.parseCsv(text)
                baseTfSec = baseTfGuess
                modeFromFeed = false
                tfFeedCache.clear()
                if (baseTfGuess <= 90 && raw.isNotEmpty()) fineCandles = ArrayList(raw)
                // اگر تایم‌فریم فایل از تایم‌فریم مود درشت‌تر باشد، «ساختن» مود از آن ممکن نیست
                // (تجمیع معکوس فقط برچسب زمان را عوض می‌کند و دادهٔ جعلی می‌سازد). پس مود
                // با تایم‌فریم واقعی فایل هم‌تراز می‌شود و همان به کاربر گفته می‌شود.
                var tfNote = ""
                if (baseTfGuess > 0 && baseTfGuess > cfg.tf2) {
                    Log.w(Log.CAT_FEED, "تایم‌فریم فایل از مود درشت‌تر است — مود هم‌تراز فایل شد",
                        "فایل=${com.alisport.goldpin.core.Tf.label(baseTfGuess)} مود=${com.alisport.goldpin.core.Tf.label(cfg.tf2)}")
                    cfg.tf2 = baseTfGuess
                    chartTfSec = baseTfGuess
                    tfNote = " · تایم‌فریم مود با فایل هم‌تراز شد (${com.alisport.goldpin.core.Tf.label(baseTfGuess)})"
                }
                var modeList = if (baseTfGuess != cfg.tf2) Feed.aggregate(raw, cfg.tf2) else raw
                if (useSyntheticVolume) modeList = Feed.synthesizeVolumeIfMissing(modeList)
                setCandles(modeList, rebuildNow = true)
                main.post {
                    onDone("وارد شد: ${com.alisport.goldpin.util.Fa.d(candles.size.toString())} کندل$tfNote")
                }
            } catch (e: Exception) {
                Log.e(Log.CAT_FEED, "خطای ورود فایل CSV", e)
                main.post { onDone("خطای ورود فایل: ${e.message}") }
            }
        }
    }

    companion object {
        /** نسخهٔ نمایشی — تنها منبع حقیقت برای هدر لاگ. با `versionName` در build.gradle یکی نگه دارید. */
        const val APP_VERSION = "1.4.45"

        val instance = AppState()

        /** از MainActivity فراخوانی می‌شود: لاگر + هشدارها را آماده و وصل می‌کند */
        fun init(ctx: Context) = instance.initApp(ctx)
    }
}
