package com.alisport.goldpin.core

import java.io.File
import java.util.zip.GZIPInputStream
import kotlin.test.Test
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * بک‌تست روی دادهٔ نمونهٔ واقعی همراه اپ (XAUUSD ‏M1):
 * • طبق §4-9 سند، هر معامله‌ای که به حد سود نهایی رسیده باید پله‌های TP1/TP2 معتبرش را
 *   در گزارش سفارش‌ها داشته باشد (پیش‌تر در کندل‌های پهن/گپ‌دار گم می‌شدند).
 * • طبق ساعت استاندارد بازار، هیچ پرشدنی در تعطیلات شنبه و یکشنبه نباید رخ داده باشد
 *   و هیچ کندل ساختاری نباید روی زمان بسته‌بودن بازار نشسته باشد.
 */
class RealSampleBacktestTest {

    @Test
    fun `real sample backtest - final TP trades keep TP1 TP2 legs and no weekend fills`() {
        val asset = listOf(
            "app/src/main/assets/sample_xauusd_m1.csv.gz",
            "../app/src/main/assets/sample_xauusd_m1.csv.gz",
            "/home/user/ST2/app/src/main/assets/sample_xauusd_m1.csv.gz"
        ).map(::File).firstOrNull { it.exists() }
        assumeTrue(asset != null, "فایل نمونهٔ داده در دسترس نیست")

        val candles = ArrayList<Candle>()
        GZIPInputStream(asset!!.inputStream()).bufferedReader().useLines { lines ->
            var bi = 0
            for (ln in lines) {
                val p = ln.split(',')
                if (p.size < 6) continue
                val t = p[0].trim().toLongOrNull() ?: continue
                candles.add(
                    Candle(
                        bi++, t,
                        p[1].toDouble(), p[2].toDouble(), p[3].toDouble(), p[4].toDouble(),
                        p[5].toDouble()
                    )
                )
            }
        }
        assertTrue(candles.size > 10_000, "دادهٔ نمونه باید بارگذاری شود")

        val mode = TF_MODES.first { it.idx == 1 }
        val cfg = Settings().apply { tfS = mode.s; tfM = mode.m; tf1 = mode.t1; tf2 = mode.t2 }
        val eng = Engine(cfg)
        val broker = PaperBroker(cfg)
        eng.broker = broker
        broker.engine = eng
        eng.barCommitHook = { cd -> broker.onBar(cd, live = false) }

        var upto = 0
        val step = 20_000
        while (upto < candles.size) {
            val next = minOf(upto + step, candles.size)
            eng.feed(candles.subList(0, next), lastIsClosed = false)
            upto = next
        }
        eng.feed(candles, lastIsClosed = true)

        val closed = broker.trades.filter { !it.open }
        val finalTp = closed.filter { tr -> tr.fills.any { it.kind == "TPX" } }
        println(
            "بک‌تست نمونه: کندل=${candles.size} معاملات بسته=${closed.size} " +
                "رسیده به حد سود نهایی=${finalTp.size} موجودی=${"%.2f".format(broker.balance)}"
        )

        // هیچ پرشدنی در بازار بسته (شنبه و یکشنبه تا بازگشایی ۲۲:۰۰ UTC) نباید باشد
        val weekendFills = broker.trades.flatMap { it.fills }.filter { MarketHours.isClosed(it.t) }
        assertTrue(weekendFills.isEmpty(), "پرشدن در تعطیلات آخر هفته نباید رخ داده باشد: $weekendFills")
        assertTrue(eng.structBars.none { MarketHours.isClosed(it.t) }, "کندل ساختار روی زمان تعطیل بازار نباید باشد")

        // §18-26/§18-27 سند: هیچ ورودی خارج از پنجرهٔ سفارش‌گیری (۰۱:۳۰ تا ۲۰:۰۰ UTC) و
        // هیچ پرشدنی بعد از رأس ۲۰:۰۰ نباید باشد؛ معاملات رأس ۲۰:۰۰ با «پایان زمان» بسته می‌شوند.
        val allFills = broker.trades.flatMap { it.fills }
        val outsideEntries = allFills.filter { it.kind == "ENTRY" && !MarketHours.isOrderWindow(it.t) }
        assertTrue(outsideEntries.isEmpty(), "ورود خارج از پنجرهٔ سفارش‌گیری نباید رخ داده باشد: $outsideEntries")
        val afterEnd = allFills.filter { MarketHours.minuteOfDayUtc(it.t) > MarketHours.ORDER_END_MIN_UTC }
        assertTrue(afterEnd.isEmpty(), "پرشدن بعد از رأس ۲۰:۰۰ UTC نباید رخ داده باشد: $afterEnd")
        println("بسته‌شدن با پایان زمان در نمونه: ${closed.count { it.reason.contains("پایان زمان") }} معامله")

        // هر معاملهٔ رسیده به حد سود نهایی: پله‌های معتبر TP1/TP2 باید در گزارش باشند (§4-9)
        for (tr in finalTp) {
            val kinds = tr.fills.map { it.kind }
            if (!tr.tp1.isNaN()) assertTrue("TP1" in kinds, "معامله #${tr.id} به حد نهایی رسیده ولی TP1 در گزارش نیست: $kinds")
            if (!tr.tp2.isNaN()) assertTrue("TP2" in kinds, "معامله #${tr.id} به حد نهایی رسیده ولی TP2 در گزارش نیست: $kinds")
            // مجموع حجم پله‌های خروجی دقیقاً حجم معامله است (نه بیشتر، نه کمتر)
            val exitQty = tr.fills.filter { it.kind != "ENTRY" }.sumOf { it.qty }
            assertTrue(kotlin.math.abs(exitQty - tr.qty) < 1e-9, "معامله #${tr.id}: حجم خروجی $exitQty با حجم معامله ${tr.qty} نمی‌خواند")
        }
    }
}
