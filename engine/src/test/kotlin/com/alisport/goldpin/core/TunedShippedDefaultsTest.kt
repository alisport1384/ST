package com.alisport.goldpin.core

import java.io.File
import java.util.zip.GZIPInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * آزمون «تنظیمِ ارسالی» — همان مسیری که اپ طی می‌کند.
 *
 * اپ روی نصب تازه [Tuned.apply] را صدا می‌زند (و مهاجرت `specRev=9` همان کار را برای فایل‌های
 * دست‌نخوردهٔ سند ۷ انجام می‌دهد). این آزمون دقیقاً همان دو خط را اجرا می‌کند و نتیجه را روی
 * **دادهٔ واقعی یک‌سالهٔ موجود در ریپؤ خود APK** می‌سنجد؛ یعنی اگر روزی کسی [Tuned] یا پیش‌فرض‌های
 * [Settings] را عوض کند و انتظار مثبت از بین برود، اینجا قرمز می‌شود — نه در دست کاربر.
 *
 * عددهای مرجع از جاروب پارامتر (`sweep/sweep_phase7.csv`) آمده‌اند:
 *   ۴۳۸ معامله · نرخ برد ۱۹٫۹٪ · انتظار ‎+۰٫۱۰R‎ · سود ‎+۳٬۴۳۸$‎ · ضریب سود ۱٫۱۹ · افت ۱٬۹۶۵$
 */
class TunedShippedDefaultsTest {

    private companion object {
        fun assetFile(): File? = listOf(
            "datasets/goldpin_xauusd_m1_1y.csv.gz",
            "../datasets/goldpin_xauusd_m1_1y.csv.gz",
            "/home/user/ST2/datasets/goldpin_xauusd_m1_1y.csv.gz"
        ).map(::File).firstOrNull { it.exists() }

        fun loadCandles(): List<Candle> {
            val f = assetFile() ?: error("فایل دادهٔ یک‌ساله پیدا نشد")
            val out = ArrayList<Candle>(360_000)
            GZIPInputStream(f.inputStream().buffered(1 shl 16)).bufferedReader().useLines { lines ->
                var bi = 0
                for (ln in lines) {
                    val p = ln.split(',')
                    if (p.size < 6) continue
                    val t = p[0].trim().toLongOrNull() ?: continue
                    out.add(Candle(bi++, t, p[1].toDouble(), p[2].toDouble(), p[3].toDouble(), p[4].toDouble(), p[5].toDouble()))
                }
            }
            return out
        }

        /** همان کاری که اپ روی نصب تازه می‌کند: پیش‌فرض‌های کد + [Tuned.apply]. */
        fun shippedSettings(): Settings {
            val mode = TF_MODES.first { it.idx == 1 }
            return Settings().apply {
                tfS = mode.s; tfM = mode.m; tf1 = mode.t1; tf2 = mode.t2
                mmMode = MoneyMode.MIXED
                minLotAuto = false
                minLotManual = 10.0
                initialEquity = 100_000.0
                Tuned.apply(this)          // ← تنها تفاوت با پیش‌فرض‌های سند ۷
            }
        }
    }

    @Test
    fun `shipped Tuned defaults stay profitable on the repository real year`() {
        val f = assetFile()
        assumeTrue(f != null, "فایل دادهٔ یک‌ساله در دسترس نیست")
        val candles = loadCandles()
        assertTrue(candles.size > 300_000, "دادهٔ یک‌ساله باید کامل بارگذاری شود: ${candles.size}")

        val cfg = shippedSettings()
        // پیش‌شرط: تنظیم ارسالی همان چیزی باشد که جاروب سنجیده است.
        assertEquals(false, cfg.trigger2Fade)
        assertEquals(7.0, cfg.trigger2TargetR, 1e-9)
        assertEquals(200.0, cfg.slBufTicks, 1e-9)
        assertEquals(-2.0, cfg.entryBufTicks, 1e-9)
        assertEquals(10.0, cfg.maxSlUnits, 1e-9)
        assertEquals(0.7, cfg.cmsnManual, 1e-9)

        val eng = Engine(cfg)
        val broker = PaperBroker(cfg)
        eng.broker = broker
        broker.engine = eng
        eng.barCommitHook = { cd -> broker.onBar(cd, live = false) }
        var upto = 0
        val step = 25_000
        while (upto < candles.size) {
            val next = minOf(upto + step, candles.size)
            eng.feed(candles.subList(0, next), lastIsClosed = false)
            upto = next
        }
        eng.feed(candles, lastIsClosed = true)

        val s = Reports.summary(broker.trades)
        val winRate = if (s.trades > 0) s.wins * 100.0 / s.trades else 0.0

        // ۱) مشکل «صفر سفارش» حل شده باشد (پیش‌فرض سند ۷ با کارمزد ۰٫۷ هیچ سفارشی نمی‌داد).
        assertTrue(s.trades > 300, "باید معامله داشته باشیم؛ پیش‌فرض سند ۷ صفر می‌داد. trades=${s.trades}")
        // ۲) انتظار مثبت و سود خالص مثبت.
        assertTrue(s.avgR > 0.0, "انتظار باید مثبت باشد: avgR=${s.avgR}")
        assertTrue(s.net > 0.0, "سود خالص باید مثبت باشد: net=${s.net}")
        assertTrue(s.profitFactor > 1.10, "ضریب سود باید بالای ۱٫۱۰ باشد: pf=${s.profitFactor}")
        // ۳) نرخ برد در همان بازهٔ اندازه‌گیری‌شده (۱۹–۲۲٪) بماند؛ تغییر بزرگ یعنی رگرسیون.
        assertTrue(winRate in 15.0..26.0, "نرخ برد بیرون از بازهٔ مرجع است: $winRate")
        // ۴) افت سرمایه در حد همان اندازه‌گیری (نه انفجاری).
        assertTrue(s.maxDD < 5_000.0, "افت سرمایه بیشتر از حد مرجع است: ${s.maxDD}")

        println(
            "SHIPPED: trades=${s.trades} win%=${"%.2f".format(winRate)} avgR=${"%.4f".format(s.avgR)} " +
                "net=${"%.0f".format(s.net)} pf=${"%.3f".format(s.profitFactor)} maxDD=${"%.0f".format(s.maxDD)}"
        )
    }

    @Test
    fun `doc 7 defaults on the same data still produce zero orders`() {
        // شاهدِ کنترل: همان داده، همان موتور، فقط پیش‌فرض سند ۷ (معکوس روشن · ۴R · فیلتر ۶ · N=۳)
        // با کارمزد ۰٫۷ ⇒ آستانهٔ فیلتر ۴٫۲ از سقف N=۳ بزرگ‌تر است و هیچ سفارشی ثبت نمی‌شود.
        // اگر این آزمون روزی سبزِ «با معامله» شود، یعنی فیلتر کارمزد عوض شده و باید دوباره سنجیده شود.
        val f = assetFile()
        assumeTrue(f != null, "فایل دادهٔ یک‌ساله در دسترس نیست")
        val candles = loadCandles()

        val mode = TF_MODES.first { it.idx == 1 }
        val cfg = Settings().apply {
            tfS = mode.s; tfM = mode.m; tf1 = mode.t1; tf2 = mode.t2
            mmMode = MoneyMode.MIXED
            minLotAuto = false
            minLotManual = 10.0
            initialEquity = 100_000.0
            cmsnAuto = false
            cmsnManual = 0.7        // تنها تغییر: کارمزد واقعی کاربر
        }
        // پیش‌فرض‌های سند ۷ همان‌طور که در Settings() هستند
        assertTrue(cfg.trigger2Fade && cfg.trigger2TargetR == 4.0 && cfg.minSlCostMult == 6.0)

        val eng = Engine(cfg)
        val broker = PaperBroker(cfg)
        eng.broker = broker
        broker.engine = eng
        eng.barCommitHook = { cd -> broker.onBar(cd, live = false) }
        var upto = 0
        val step = 25_000
        while (upto < candles.size) {
            val next = minOf(upto + step, candles.size)
            eng.feed(candles.subList(0, next), lastIsClosed = false)
            upto = next
        }
        eng.feed(candles, lastIsClosed = true)

        val s = Reports.summary(broker.trades)
        println("DOC7 CONTROL: trades=${s.trades} orders=${broker.orders.size} net=${s.net}")
        assertEquals(0, s.trades, "پیش‌فرض سند ۷ با کارمزد ۰٫۷ باید صفر معامله بدهد (شاهدِ کنترل)")
    }
}
