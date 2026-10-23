package com.alisport.goldpin.core

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ۱) زمان‌بندی استاندارد بازار (تصمیم مالک): هفتهٔ معاملاتی دوشنبه تا جمعه، تعطیلات
 *    شنبه و یکشنبه؛ سشن یکشنبه‌شب (از ۲۲:۰۰ UTC) جزئی از دوشنبه است و در بازار بسته
 *    هیچ تصمیم/سفارش/پرشدنی انجام نمی‌شود.
 * ۲) گزارش سفارش‌ها طبق §4-9 سند: معامله سه پله دارد (۳۳٪ TP1، ‏۳۳٪ TP2، باقی‌مانده در
 *    حد سود نهایی) و هر پله باید در گزارش ثبت شود — حتی وقتی یک کندل یک‌راست تا حد سود
 *    نهایی می‌رود. پیش‌تر در چنین کندلی کل حجم با TPX بسته می‌شد و TP1/TP2 ثبت نمی‌شدند.
 */
class MarketScheduleAndTpLegsTest {

    private fun ms(iso: String): Long = Instant.parse(iso).toEpochMilli()

    @Test
    fun `standard week starts monday 00 UTC and sunday session belongs to monday`() {
        val mon = ms("2026-10-19T00:00:00Z")            // دوشنبه
        val prevMon = ms("2026-10-12T00:00:00Z")
        // کندل هفتگی: جمعه متعلق به دوشنبهٔ همان هفته است
        assertEquals(prevMon, Tf.bucket(Tf.W1, ms("2026-10-16T12:00:00Z")))
        assertEquals(mon, Tf.bucket(Tf.W1, ms("2026-10-19T00:00:00Z")))
        assertEquals(mon, Tf.bucket(Tf.W1, ms("2026-10-21T09:30:00Z")))
        // شنبه و یکشنبه به هفتهٔ معاملاتی بعد (دوشنبه) می‌افتند؛ هفته از پنجشنبه شروع نمی‌شود
        assertEquals(mon, Tf.bucket(Tf.W1, ms("2026-10-17T12:00:00Z")))
        assertEquals(mon, Tf.bucket(Tf.W1, ms("2026-10-18T22:30:00Z")))
        // کندل روزانه: یکشنبه‌شب کندل جدای یکشنبه نمی‌سازد و جزئی از دوشنبه است
        assertEquals(ms("2026-10-16T00:00:00Z"), Tf.bucket(Tf.D1, ms("2026-10-16T12:00:00Z")))
        assertEquals(mon, Tf.bucket(Tf.D1, ms("2026-10-18T22:30:00Z")))
        // گزارش هفتگی دقیقاً روی همان باکت W1 می‌نشیند
        assertEquals(Tf.bucket(Tf.W1, ms("2026-10-16T12:00:00Z")), Reports.weekStart(ms("2026-10-16T12:00:00Z")))
        assertEquals(mon, Reports.weekStart(ms("2026-10-18T22:30:00Z")))
        // ساعت بازار: شنبه تمام‌روز و یکشنبه تا ۲۲:۰۰ UTC بسته؛ از ۲۲:۰۰ یکشنبه سشن دوشنبه باز است
        assertTrue(MarketHours.isClosed(ms("2026-10-17T00:00:00Z")))
        assertTrue(MarketHours.isClosed(ms("2026-10-17T23:59:00Z")))
        assertTrue(MarketHours.isClosed(ms("2026-10-18T21:59:00Z")))
        assertTrue(MarketHours.isOpen(ms("2026-10-18T22:00:00Z")))
        assertTrue(MarketHours.isOpen(ms("2026-10-16T21:00:00Z")))
        assertTrue(MarketHours.isOpen(ms("2026-10-19T08:00:00Z")))
    }

    @Test
    fun `no fill on weekend and engine skips weekend candles`() {
        val cfg = Settings().apply { maxSlUnits = 1_000_000.0 } // موضوع این تست‌ها §18-31 نیست
        val b = PaperBroker(cfg)
        val fri = ms("2026-10-16T12:00:00Z")
        val s = Setup(1L, 1, 6, 165.0, 155.0, 1, 0).apply {
            hvH = 169.99; hvL = 165.0; sl = 164.98
            tp1 = 180.0; tp2 = 190.0; tpx = 220.0; entry = 170.0
        }
        assertTrue(b.registerLimit(s, Candle(0, fri, 171.0, 172.0, 170.5, 171.5, 100.0)))
        // شنبه: حتی با لمس سطح سفارش، پر شدن نداریم (نه با کندل بسته و نه با قیمت زنده)
        val sat = Candle(1, ms("2026-10-17T12:00:00Z"), 171.0, 172.0, 169.0, 170.5, 100.0)
        assertEquals(0, b.onLiveCandle(sat))
        b.onBar(sat)
        assertEquals(1, b.pendingOrders.size, "سفارش نباید شنبه پر شود")
        // یکشنبه پیش از بازگشایی (۲۲:۰۰ UTC) هم بازار بسته است
        b.onBar(Candle(2, ms("2026-10-18T12:00:00Z"), 171.0, 172.0, 169.0, 170.5, 100.0))
        assertEquals(1, b.pendingOrders.size, "سفارش نباید یکشنبه پیش از بازگشایی پر شود")
        // سشن دوشنبه (یکشنبه ۲۲:۰۰ UTC) از نظر بازار باز است، ولی خارج از پنجرهٔ
        // سفارش‌گیری است (§18-26): سفارشِ مانده با «پایان زمان» لغو می‌شود و پر نمی‌شود (§18-27)
        b.onBar(Candle(3, ms("2026-10-18T22:01:00Z"), 171.0, 172.0, 169.5, 170.8, 100.0))
        assertEquals(0, b.pendingOrders.size, "سفارش معلق باید با پایان زمان لغو شده باشد")
        assertEquals(0, b.openTrades.size, "یکشنبه‌شب، خارج از پنجرهٔ سفارش‌گیری، پر شدن نداریم")
        // دوشنبه داخل پنجره (۰۱:۳۰ تا ۲۰:۰۰): ثبت و پرشدن طبق برنامه (§18-30)
        val s2 = Setup(2L, 1, 6, 165.0, 155.0, 1, 0).apply {
            hvH = 169.99; hvL = 165.0; sl = 164.98
            tp1 = 180.0; tp2 = 190.0; tpx = 220.0; entry = 170.0
        }
        assertTrue(b.registerLimit(s2, Candle(4, ms("2026-10-19T08:00:00Z"), 171.0, 172.0, 170.5, 171.5, 100.0)))
        b.onBar(Candle(5, ms("2026-10-19T08:05:00Z"), 171.0, 172.0, 169.5, 170.8, 100.0))
        assertEquals(1, b.openTrades.size, "سفارش باید دوشنبه داخل پنجره پر شود")

        // موتور هم کندل‌های آخر هفته را وارد تجمیع نمی‌کند
        val e = Engine(Settings().apply { tfS = Tf.M1; tfM = Tf.M1; tf1 = Tf.M1; tf2 = Tf.M1 })
        e.runAll(listOf(
            Candle(0, ms("2026-10-16T12:00:00Z"), 100.0, 101.0, 99.0, 100.5, 10.0),
            Candle(1, ms("2026-10-17T12:00:00Z"), 100.5, 102.0, 100.0, 101.5, 10.0),
            Candle(2, ms("2026-10-18T12:00:00Z"), 101.5, 103.0, 101.0, 102.5, 10.0),
            Candle(3, ms("2026-10-18T22:30:00Z"), 102.5, 104.0, 102.0, 103.5, 10.0),
            Candle(4, ms("2026-10-19T09:00:00Z"), 103.5, 105.0, 103.0, 104.5, 10.0),
            Candle(5, ms("2026-10-19T09:01:00Z"), 104.5, 106.0, 104.0, 105.5, 10.0)
        ))
        assertEquals(
            listOf(ms("2026-10-16T12:00:00Z"), ms("2026-10-18T22:30:00Z"), ms("2026-10-19T09:00:00Z")),
            e.structBars.map { it.t },
            "کندل‌های شنبه و یکشنبهٔ پیش از بازگشایی نباید وارد ساختار شوند"
        )
    }

    private fun buyBrokerWithTrade(tp1: Double, tp2: Double, tpx: Double, hvH: Double, hvL: Double, sl: Double): Pair<PaperBroker, Long> {
        val cfg = Settings().apply { maxSlUnits = 1_000_000.0 } // موضوع این تست‌ها §18-31 نیست
        val b = PaperBroker(cfg)
        val t0 = ms("2026-10-19T08:00:00Z")   // دوشنبه
        val entry = cfg.entryLevel(true, hvH, hvL)
        val s = Setup(1L, 1, 6, hvL + 10.0, hvL - 10.0, 1, 0).apply {
            this.hvH = hvH; this.hvL = hvL; this.sl = sl
            this.tp1 = tp1; this.tp2 = tp2; this.tpx = tpx; this.entry = entry
        }
        assertTrue(b.registerLimit(s, Candle(0, t0, entry + 1.0, entry + 2.0, entry + 0.5, entry + 1.5, 100.0)))
        return b to t0
    }

    @Test
    fun `doc 4-9 one candle straight to final TP still records TP1 and TP2 legs 33 33 rest`() {
        val (b, t0) = buyBrokerWithTrade(tp1 = 180.0, tp2 = 190.0, tpx = 220.0, hvH = 169.99, hvL = 165.0, sl = 164.98)
        // کندل ورود (پرشدن روی ۱۷۰) بدون لمس TP1
        b.onBar(Candle(1, t0 + 60_000L, 171.0, 175.0, 169.8, 174.0, 100.0))
        assertEquals(1, b.openTrades.size)
        val tr = b.openTrades[0]
        val q = tr.qty
        // یک کندل یک‌راست تا بالای حد سود نهایی؛ حد ضرر لمس نمی‌شود
        b.onBar(Candle(2, t0 + 120_000L, 174.0, 225.0, 171.0, 220.0, 100.0))
        assertFalse(tr.open, "معامله در حد سود نهایی تمام می‌شود")
        assertEquals(listOf("ENTRY", "TP1", "TP2", "TPX"), tr.fills.map { it.kind },
            "هر سه پله باید در گزارش سفارش ثبت شوند (§4-9)")
        assertEquals(180.0, tr.fills[1].price, 1e-9)
        assertEquals(190.0, tr.fills[2].price, 1e-9)
        assertEquals(220.0, tr.fills[3].price, 1e-9)
        assertEquals(q * 0.33, tr.fills[1].qty, 1e-9)
        assertEquals(q * 0.33, tr.fills[2].qty, 1e-9)
        assertEquals(q - q * 0.66, tr.fills[3].qty, 1e-9)
        // سود = میانگین موزون پله‌ها، نه کل حجم در قیمت نهایی
        val expected = q * (0.33 * 10.0 + 0.33 * 20.0 + 0.34 * 50.0)
        assertEquals(expected, tr.grossPnl(), 1e-6)
    }

    @Test
    fun `doc 4-9 TP1 in earlier candle then final candle records TP2 before final`() {
        val (b, t0) = buyBrokerWithTrade(tp1 = 180.0, tp2 = 190.0, tpx = 220.0, hvH = 169.99, hvL = 165.0, sl = 164.98)
        b.onBar(Candle(1, t0 + 60_000L, 171.0, 175.0, 169.8, 174.0, 100.0))
        val tr = b.openTrades[0]
        // فقط TP1
        b.onBar(Candle(2, t0 + 120_000L, 174.0, 185.0, 171.0, 182.0, 100.0))
        assertEquals(listOf("ENTRY", "TP1"), tr.fills.map { it.kind })
        // کندل بعد تا حد سود نهایی: TP2 باید پیش از پلهٔ نهایی ثبت شود
        b.onBar(Candle(3, t0 + 180_000L, 182.0, 225.0, 175.0, 220.0, 100.0))
        assertFalse(tr.open)
        assertEquals(listOf("ENTRY", "TP1", "TP2", "TPX"), tr.fills.map { it.kind })
        assertEquals(190.0, tr.fills[2].price, 1e-9)
    }

    @Test
    fun `doc 4-9 entry bar straight to final records 50 percent at fib 0-5 and rest at final`() {
        val (b, t0) = buyBrokerWithTrade(tp1 = 169.1, tp2 = 175.0, tpx = 213.6, hvH = 159.99, hvL = 155.0, sl = 154.98)
        // کندل ورود: هم ورود (۱۶۰) و هم ۰.۳۸۲ (۱۶۹.۱) و هم ۰.۵ (۱۷۵) و هم نهایی (۲۱۳.۶) لمس می‌شود
        b.onBar(Candle(1, t0 + 60_000L, 161.0, 215.0, 159.5, 214.0, 100.0))
        assertEquals(0, b.openTrades.size, "معامله در همان کندل ورود با رسیدن به حد سود نهایی تمام می‌شود")
        val tr = b.trades[0]
        assertEquals(175.0, tr.tp1, 1e-9, "حد سود اول باید به ۰.۵ فیبو منتقل شده باشد (§4-9)")
        assertEquals(listOf("ENTRY", "TP1", "TPX"), tr.fills.map { it.kind })
        assertEquals(175.0, tr.fills[1].price, 1e-9)
        assertEquals(tr.qty * 0.50, tr.fills[1].qty, 1e-9)
        assertEquals(tr.qty * 0.50, tr.fills[2].qty, 1e-9)
    }
}
