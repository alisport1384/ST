package com.alisport.goldpin.core

import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max

// ═══════════════════════════════════════════════════════════════════════════════
//  §۲۰ سند — مدیریت مالی و مدیریت زیان
//
//  دو مود دارد (و یک حالت «خاموش» که همان حجم §۵ = ریسک ثابت است و پیش‌فرض می‌ماند):
//    • ManageAllLost (§۲۰-۱، مدیریت تهاجمی):  Lot = (Lost / div) + MinLot
//    • Mixed (§۲۰-۲):  M = NakedCapital × (1 + ۲۰٪)
//         Capital ≤ M  ⇒ ManageNothing :  Lot = MinLot
//         Capital > M  ⇒ ManageCM      :  Lot = (cmsnLost / div) + MinLot
//
//  تعریف‌ها (طبق مثال‌های عددی سند؛ متن «پیش‌نیازها» در سند جای دو تعریف را عوض نوشته):
//    NakedCapital = سرمایهٔ اولیه (ثابت) = Settings.initialEquity
//    Capital      = سرمایهٔ فعلی = سرمایهٔ اولیه + سود/زیان تحقق‌یافته = PaperBroker.balance
//    MinLot       = (NakedCapital × risk) / 100   (خودکار)  یا عدد دستی
//    div          = |ورودِ سفارش − حدسود نهایی|     (خودکار)  یا عدد دستی
//    cmsn         = اسپرد (واحد قیمت) × Lot × اندازهٔ قرارداد = کمیسیونِ تحمیلی هر معامله (دلار)
//    Lost         = ضرر خالصِ هنوز جبران‌نشده (دلار)
//    cmsnLost     = جمع کمیسیون معاملاتی که با ضرر بسته شدند (فقط در فاز ManageCM)
//
//  crt (بند ۵ سند): فقط در مود ManageAllLost فعال است. اگر Lost از درصد آستانهٔ Capital
//  بیشتر شود (پیش‌فرض ۱۰٪)، حجم با crt محاسبه می‌شود: Lot = Lost / div (بدون MinLot).
// ═══════════════════════════════════════════════════════════════════════════════

/** حالت‌های مدیریت مالی. مقدارهای عددی در فایل ذخیره می‌شوند؛ تغییر ندهید. */
object MoneyMode {
    const val NONE = 0              // خاموش: همان حجم §۵ (ریسک ثابت)
    const val MANAGE_ALL_LOST = 1   // §۲۰-۱
    const val MIXED = 2             // §۲۰-۲

    fun label(mode: Int): String = when (mode) {
        MANAGE_ALL_LOST -> "ManageAllLost"
        MIXED -> "Mixed"
        else -> "بدون مدیریت (§۵)"
    }
}

/** نتیجهٔ محاسبهٔ Lot در مودهای §۲۰ (پیش از اعمال سقف اهرم). */
class MmLot(
    /** Lot خام طبق فرمول (بدون گام و سقف). */
    val raw: Double,
    val minLot: Double,
    val div: Double,
    /** Mixed: آیا فاز ManageCM بود (Capital > M)؟ */
    val cm: Boolean,
    /** توضیح یک‌خطی فرمول برای لاگ و جزئیات سفارش. */
    val note: String
)

class MoneyManager(private val cfg: Settings) {

    /** Lost — ضرر خالصِ جبران‌نشده، به دلار (همیشه ≥ ۰). */
    var lost: Double = 0.0

    /** cmsnLost — جمع کمیسیون معاملات ضررده در فاز ManageCM، به دلار. */
    var cmsnLost: Double = 0.0

    val active: Boolean get() = cfg.mmMode != MoneyMode.NONE

    fun reset() { lost = 0.0; cmsnLost = 0.0; naked = Double.NaN }

    /** NakedCapital جاری؛ NaN = هنوز دو برابر نشده ⇒ همان سرمایهٔ اولیهٔ تنظیمات. */
    private var naked: Double = Double.NaN

    /** NakedCapital — سرمایهٔ اولیه؛ در مود Mixed با رسیدن Capital به دو برابر آن، دو برابر می‌شود (§۲۰-۲). */
    fun nakedCapital(): Double = if (naked.isNaN()) cfg.initialEquity else naked

    /** بازیابی NakedCapital از فایل ذخیره‌شده. */
    fun restoreNaked(v: Double) { naked = if (v.isNaN() || v <= 0.0 || v == cfg.initialEquity) Double.NaN else v }

    /**
     * §۲۰-۲: if (capital / nakedcapital) >= 2 ⇒ nakedcapital = nakedcapital × 2 (فقط مود Mixed).
     *
     * توجه: ویرایش فعلی سند جملهٔ «و مقدار lost = 0.0 می‌شود» را برای بازگشت Capital به زیر M
     * **حذف کرده است**؛ پس با رفتن به فاز ManageNothing مقدار [lost] دست‌نخورده می‌ماند و فقط
     * با بسته شدن معامله (و در صورت روشن بودن [Settings.lostResetAtFinalTp] با حد سود نهایی)
     * به‌روزرسانی می‌شود.
     */
    fun onCapitalUpdated(capital: Double) {
        if (cfg.mmMode != MoneyMode.MIXED) return
        var guard = 0
        while (nakedCapital() > 0.0 && capital / nakedCapital() >= 2.0 && guard++ < 64) naked = nakedCapital() * 2.0
    }

    /** MinLot = (NakedCapital × risk) / 100 یا مقدار دستی. */
    fun minLot(): Double = if (cfg.minLotAuto) nakedCapital() * cfg.riskPct / 100.0 else cfg.minLotManual

    /** M = NakedCapital × (1 + رشد٪/100) — آستانهٔ مود Mixed. */
    fun growthLimit(): Double = nakedCapital() * (1.0 + cfg.mixedGrowthPct / 100.0)

    /** div = |ورودی سفارش − حدسود نهایی| (همیشه مثبت) یا مقدار دستی. */
    fun div(entry: Double, finalTp: Double): Double =
        if (cfg.divAuto) abs(entry - finalTp) else abs(cfg.divManual)

    /** اسپرد مؤثر (واحد قیمت): خودکار و دریافت‌شده ⇒ [Settings.cmsnAutoValue] ؛ وگرنه عدد دستی. تابعی فقط از تنظیمات. */
    fun spread(): Double =
        if (cfg.cmsnAuto && cfg.cmsnAutoValue > 0.0) cfg.cmsnAutoValue else max(0.0, cfg.cmsnManual)

    /** آیا اسپرد مؤثر از بروکر آمده است (نه دستی)؟ */
    fun spreadIsBroker(): Boolean = cfg.cmsnAuto && cfg.cmsnAutoValue > 0.0

    /** cmsn برای یک معامله با حجم [qty]، به دلار. در مود خاموش همیشه ۰ است (§۵: اپ بدون کارمزد). */
    fun commissionFor(qty: Double): Double =
        if (active) max(0.0, spread() * qty * cfg.contractSize) else 0.0

    /** کف‌کردن حجم به گام [Settings.lotStep] (مثال‌های سند: ۱۶٫۶۶۷ → ۱۶٫۶۶ ، ۳۴٫۰۷۸ → ۳۴٫۰۷). */
    fun floorToStep(q: Double): Double {
        val st = cfg.lotStep
        if (st <= 0.0 || q <= 0.0) return max(q, 0.0)
        val v = floor(q / st + 1e-9) * st
        return Math.round(v * 1e8) / 1e8        // حذف خطای ممیز شناور (مثلاً ۱۶٫۶۶۰۰۰۰۰۰۰۰۰۰۴)
    }

    /**
     * Lot طبق فرمول سند، پیش از سقف اهرم و گام.
     * @param capital سرمایهٔ فعلی (Capital) — موجودی تحقق‌یافتهٔ کارگزار.
     */
    fun lotFor(capital: Double, entry: Double, finalTp: Double): MmLot {
        val minLot = minLot()
        val d = div(entry, finalTp)
        var cm = false
        val raw: Double
        val note: String
        when (cfg.mmMode) {
            MoneyMode.MANAGE_ALL_LOST -> {
                // §۲۰ بند ۵: crt فقط در ManageAllLost — «if lost > (capital*10)/100 ⇒ lot = (lost/div)»
                val crtLimit = capital * cfg.crtPct / 100.0
                val crt = cfg.crtEnabled && capital > 0.0 && lost > crtLimit
                if (crt) {
                    raw = if (d > 1e-9) lost / d else minLot
                    note = "ManageAllLost/crt (Lost ${n2(lost)} > ${n2(cfg.crtPct)}٪ Capital ${n2(capital)}): " +
                        "Lot = Lost ${n2(lost)} ÷ div ${n2(d)}"
                } else {
                    raw = if (d > 1e-9) lost / d + minLot else minLot
                    note = "ManageAllLost: Lot = Lost ${n2(lost)} ÷ div ${n2(d)} + MinLot ${n2(minLot)}"
                }
            }
            MoneyMode.MIXED -> {
                val m = growthLimit()
                cm = capital > m
                if (cm) {
                    raw = if (d > 1e-9) cmsnLost / d + minLot else minLot
                    note = "Mixed/ManageCM (Capital ${n2(capital)} > M ${n2(m)}): Lot = cmsnLost ${n2(cmsnLost)} ÷ div ${n2(d)} + MinLot ${n2(minLot)}"
                } else {
                    raw = minLot
                    note = "Mixed/ManageNothing (Capital ${n2(capital)} ≤ M ${n2(m)}): Lot = MinLot ${n2(minLot)}"
                }
            }
            else -> { raw = 0.0; note = "" }
        }
        return MmLot(raw, minLot, d, cm, note)
    }

    /**
     * به‌روزرسانی Lost/cmsnLost بعد از بسته شدن کامل یک معامله.
     *  • Lost: زیان خالص (شامل cmsn) اضافه می‌شود؛ سود خالص به همان اندازه کم می‌کند (کف ۰)؛
     *    با رسیدن به حد سود نهایی (۱٫۲۷۲) صفر می‌شود (سند: «lost = 0.0» بعد از TP) — اگر [Settings.lostResetAtFinalTp].
     *  • cmsnLost (فقط Mixed): کمیسیون معامله‌ای که در فاز ManageCM با ضرر بسته شد اضافه می‌شود؛
     *    «بعد از رسیدن معامله به حد سود نهایی مقدار cmsnLost صفر می‌شود».
     */
    fun onTradeClosed(t: Trade) {
        if (!active) return
        val net = t.pnl()
        val finalTp = t.fills.any { it.kind == "TPX" }
        if (net < 0.0) lost += -net
        else if (net > 0.0) lost = max(0.0, lost - net)
        if (finalTp && cfg.lostResetAtFinalTp) lost = 0.0
        if (cfg.mmMode == MoneyMode.MIXED) {
            if (finalTp) cmsnLost = 0.0
            else if (t.mmCm && net < 0.0) cmsnLost += t.commission
        }
    }

    companion object {
        fun n2(v: Double): String = if (v.isNaN()) "-" else String.format(Locale.US, "%.2f", v)
    }
}
