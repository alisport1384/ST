package com.alisport.goldpin.core

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * بندهای §18-26 تا §18-30 سند (افزودهٔ مالک):
 * • سفارش‌گیری و مسلح‌شدن فقط از ۰۱:۳۰ تا ۲۰:۰۰ UTC مجاز است؛ تأیید HV در شب دور
 *   ریخته می‌شود ولی ستاپ فعال می‌ماند و اولین تأیید تازه بعد از ۰۱:۳۰ مسلح می‌شود.
 * • رأس ۲۰:۰۰ UTC همهٔ معاملات باز با قیمت ساعت ۲۰:۰۰ و دلیل «پایان زمان» بسته و
 *   سفارش‌های معلق لغو می‌شوند.
 * • ناحیهٔ ساختاری که خارج از پنجرهٔ سفارش‌گیری باطل/مصرف شود، ستاپش همان شب خاتمه
 *   می‌یابد و بعد از ۰۱:۳۰ روی آن ستاپ/سفارشی ساخته نمی‌شود (§18-28).
 */
class OrderWindowTest {

    private fun ms(iso: String): Long = Instant.parse(iso).toEpochMilli()
    private fun mon(hhmm: String): Long = ms("2026-10-19T$hhmm:00Z")   // دوشنبه

    @Test
    fun `doc 18-26 and 18-30 order window boundaries 01-30 to 20-00 UTC`() {
        assertFalse(MarketHours.isOrderWindow(mon("01:29")))
        assertTrue(MarketHours.isOrderWindow(mon("01:30")))
        assertTrue(MarketHours.isOrderWindow(mon("12:00")))
        assertTrue(MarketHours.isOrderWindow(mon("19:59")))
        assertFalse(MarketHours.isOrderWindow(mon("20:00")))
        assertFalse(MarketHours.isOrderWindow(mon("23:30")))
        assertFalse(MarketHours.isOrderWindow(mon("00:30")))
        assertFalse(MarketHours.isOrderTimeExit(mon("19:59")))
        assertTrue(MarketHours.isOrderTimeExit(mon("20:00")))
        assertTrue(MarketHours.isOrderTimeExit(mon("23:59")))
        assertFalse(MarketHours.isOrderTimeExit(mon("00:00")))
        assertFalse(MarketHours.isOrderTimeExit(mon("01:29")))
    }

    private fun buySetup(): Setup = Setup(1L, 1, 6, 165.0, 155.0, 1, 0).apply {
        hvH = 169.99; hvL = 165.0; sl = 164.98
        tp1 = 180.0; tp2 = 190.0; tpx = 220.0; entry = 170.0
    }

    private fun tryRegister(t: Long): Boolean =
        PaperBroker(Settings().apply { maxSlUnits = 1_000_000.0 }).registerLimit(buySetup(), Candle(0, t, 171.0, 172.0, 170.5, 171.5, 100.0))

    @Test
    fun `doc 18-26 broker registers orders only inside the window`() {
        assertFalse(tryRegister(mon("01:29")), "پیش از ۰۱:۳۰ سفارش‌گیری غیرفعال است")
        assertTrue(tryRegister(mon("01:30")), "رأس ۰۱:۳۰ سفارش‌گیری فعال می‌شود (§18-30)")
        assertTrue(tryRegister(mon("19:59")))
        assertFalse(tryRegister(mon("20:00")), "رأس ۲۰:۰۰ پنجره بسته است")
        assertFalse(tryRegister(mon("23:00")))
    }

    @Test
    fun `doc 18-27 at 20-00 open trade closes at 20-00 price and pending is cancelled not filled`() {
        // معاملهٔ باز: رأس ۲۰:۰۰ با قیمت بازشدن کندل ۲۰:۰۰ بسته می‌شود، حتی اگر همان کندل تا حد سود نهایی برود
        val b = PaperBroker(Settings().apply { maxSlUnits = 1_000_000.0 })
        assertTrue(b.registerLimit(buySetup(), Candle(0, mon("08:00"), 171.0, 172.0, 170.5, 171.5, 100.0)))
        b.onBar(Candle(1, mon("08:01"), 171.0, 175.0, 169.8, 174.0, 100.0))
        assertEquals(1, b.openTrades.size)
        b.onBar(Candle(2, mon("19:59"), 175.0, 178.0, 171.0, 176.0, 100.0))
        assertEquals(1, b.openTrades.size, "معامله تا پیش از ۲۰:۰۰ باز می‌ماند")
        b.onBar(Candle(3, mon("20:00"), 200.0, 230.0, 199.0, 225.0, 100.0))
        val tr = b.trades[0]
        assertFalse(tr.open)
        assertEquals(listOf("ENTRY", "CLOSE"), tr.fills.map { it.kind },
            "خروج پایان زمان بر حد سود همان کندل مقدم است")
        assertEquals(200.0, tr.exitPx ?: Double.NaN, 1e-9, "قیمت بستن = قیمت لحظهٔ ۲۰:۰۰")
        assertTrue(tr.reason.contains("پایان زمان"), "دلیل ثبت‌شده باید «پایان زمان» باشد: ${tr.reason}")

        // سفارش معلق: رأس ۲۰:۰۰ لغو می‌شود و همان کندل (حتی با لمس سطح) آن را پر نمی‌کند
        val b2 = PaperBroker(Settings().apply { maxSlUnits = 1_000_000.0 })
        assertTrue(b2.registerLimit(buySetup(), Candle(0, mon("19:00"), 171.0, 172.0, 170.5, 171.5, 100.0)))
        b2.onBar(Candle(1, mon("19:30"), 171.0, 172.0, 171.0, 171.5, 100.0))
        assertEquals(1, b2.pendingOrders.size)
        b2.onBar(Candle(2, mon("20:00"), 171.0, 172.0, 169.0, 170.5, 100.0))
        assertEquals(0, b2.pendingOrders.size, "سفارش معلق رأس ۲۰:۰۰ لغو می‌شود")
        assertEquals(0, b2.openTrades.size, "سفارش لغوشده نباید در همان کندل پر شود")
        assertEquals(OrderStatus.CANCELLED_EXPIRED, b2.orders[0].status)
        assertTrue(b2.orders[0].cancelReason?.contains("پایان زمان") == true)
        // و با قیمت زنده هم بعد از ۲۰:۰۰ چیزی پر نمی‌شود
        val b3 = PaperBroker(Settings().apply { maxSlUnits = 1_000_000.0 })
        assertTrue(b3.registerLimit(buySetup(), Candle(0, mon("19:00"), 171.0, 172.0, 170.5, 171.5, 100.0)))
        assertEquals(0, b3.onLiveCandle(Candle(5, mon("20:05"), 171.0, 172.0, 169.0, 170.0, 100.0)))
    }

    @Test
    fun `doc 18-27 remaining qty after TP1 closes at 20-00 with end-of-time reason`() {
        val b = PaperBroker(Settings().apply { maxSlUnits = 1_000_000.0 })
        assertTrue(b.registerLimit(buySetup(), Candle(0, mon("08:00"), 171.0, 172.0, 170.5, 171.5, 100.0)))
        b.onBar(Candle(1, mon("08:01"), 171.0, 175.0, 169.8, 174.0, 100.0))
        val tr = b.openTrades[0]
        b.onBar(Candle(2, mon("08:02"), 174.0, 185.0, 171.0, 182.0, 100.0))   // TP1
        assertEquals(listOf("ENTRY", "TP1"), tr.fills.map { it.kind })
        b.onBar(Candle(3, mon("20:00"), 195.0, 196.0, 194.0, 195.0, 100.0))
        assertFalse(tr.open)
        assertEquals(listOf("ENTRY", "TP1", "CLOSE"), tr.fills.map { it.kind })
        assertEquals(195.0, tr.fills[2].price, 1e-9)
        assertEquals(tr.qty - tr.qty * 0.33, tr.fills[2].qty, 1e-9, "فقط حجم باقی‌مانده در پایان زمان بسته می‌شود")
    }

    private fun engineAllM1(): Engine =
        Engine(Settings().apply { tfS = Tf.M1; tfM = Tf.M1; tf1 = Tf.M1; tf2 = Tf.M1 })

    /** runAll ابتدا reset() می‌کند و ستاپ مصنوعیِ تزریق‌شده پاک می‌شود؛ برای این تست‌ها feed مستقیم لازم است. */
    private fun Engine.feedBars(bars: List<Candle>) = feed(bars, lastIsClosed = true)

    @Test
    fun `doc 18-27 engine expires armed setup at 20-00 instead of filling it`() {
        val e = engineAllM1()
        val s = Setup(1L, 1, 6, 165.0, 155.0, 1, 0).apply {
            hvH = 169.99; hvL = 165.0; hvBi = 1; hvConfBi = 1; armBi = 1
            entry = 170.0; sl = 164.98; tp1 = 180.0; tp2 = 190.0; tpx = 220.0
            midRef = 160.0; midConfirmBi = 9999; priceExpiryRef = 1e9
        }
        e.setups.add(s)
        e.feedBars(listOf(
            Candle(0, mon("19:58"), 175.0, 176.0, 174.0, 175.0, 10.0),
            Candle(1, mon("19:59"), 175.0, 176.0, 174.0, 175.0, 10.0),
            // کندل ۲۰:۰۰ سطح ورود را هم لمس می‌کند؛ ولی پایان زمان مقدم است و پر شدن نداریم
            Candle(2, mon("20:00"), 175.0, 176.0, 169.0, 175.0, 10.0),
            Candle(3, mon("20:01"), 175.0, 176.0, 174.0, 175.0, 10.0)
        ))
        assertEquals(8, s.stage, "ستاپ مسلح رأس ۲۰:۰۰ منقضی می‌شود، نه اینکه پر شود")
        assertTrue(s.note.contains("پایان زمان"), "note=${s.note}")
        assertEquals(-1, s.entryBi, "ورود نباید ثبت شده باشد")
    }

    @Test
    fun `doc 18-27 engine closes open setup trade at 20-00 open price`() {
        val e = engineAllM1()
        val s = Setup(1L, 1, 7, 165.0, 155.0, 1, 0).apply {
            hvH = 169.99; hvL = 165.0
            entry = 170.0; entryBi = 0; sl = 164.98; tp1 = 180.0; tp2 = 190.0; tpx = 220.0
            midRef = 160.0; midConfirmBi = 9999
        }
        e.setups.add(s)
        e.feedBars(listOf(
            Candle(0, mon("19:59"), 175.0, 178.0, 171.0, 176.0, 10.0),
            // کندل ۲۰:۰۰ تا بالای حد سود نهایی می‌رود، ولی معامله با قیمت ۲۰:۰۰ بسته می‌شود
            Candle(1, mon("20:00"), 200.0, 230.0, 199.0, 225.0, 10.0),
            Candle(2, mon("20:01"), 225.0, 226.0, 224.0, 225.0, 10.0)
        ))
        assertEquals(8, s.stage)
        assertEquals("پایان زمان", s.note)
        assertTrue(e.markers.any { it.text.contains("پایان زمان") && it.price == 200.0 },
            "مارکر پایان زمان باید روی قیمت لحظهٔ ۲۰:۰۰ باشد")
    }

    @Test
    fun `doc 18-28 zone consumed outside order window ends its setup same night, inside window setup survives`() {
        fun runCase(touchT: Long, bars: List<Candle>): Setup {
            val e = engineAllM1()
            val s = Setup(1L, 1, 3, 165.0, 155.0, 1, 0).apply {
                this.touchT = touchT
                midRef = 170.0; midConfirmBi = 9999
            }
            e.setups.add(s)
            e.feedBars(bars)
            return s
        }
        fun flat(bi: Int, t: Long) = Candle(bi, t, 175.0, 175.0, 175.0, 175.0, 10.0)

        // ابطال/مصرف ناحیه در ساعت ۲۰:۰۱ (خارج از پنجره) ← ستاپ همان شب خاتمه می‌یابد (§18-28)
        val night = runCase(mon("19:59"), listOf(flat(0, mon("20:00")), flat(1, mon("20:01")), flat(2, mon("20:02"))))
        assertTrue(night.zUsed)
        assertEquals(8, night.stage, "ستاپِ ناحیهٔ مصرف‌شده در شب نباید تا صبح زنده بماند")
        assertTrue(night.zGone)
        assertTrue(night.note.contains("18-28"), "note=${night.note}")

        // همان رویداد ساعت ۱۰:۰۱ (داخل پنجره) ← رفتار §18-14: ستاپ تا ابطال تایید میانی فعال می‌ماند
        val day = runCase(mon("09:59"), listOf(flat(0, mon("10:00")), flat(1, mon("10:01")), flat(2, mon("10:02"))))
        assertTrue(day.zUsed)
        assertFalse(day.zGone, "داخل پنجرهٔ سفارش‌گیری رفتار §18-14 بدون تغییر می‌ماند")
        assertTrue(day.stage != 8, "stage=${day.stage}")
    }
}
