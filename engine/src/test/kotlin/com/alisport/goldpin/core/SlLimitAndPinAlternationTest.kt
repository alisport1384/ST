package com.alisport.goldpin.core

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ۱) §18-31 سند: سفارشی که فاصلهٔ حد ضررش از N بیشتر است ثبت نمی‌شود (پیش‌فرض N=3؛
 *    مثال خود سند N=3)؛ HV و ستاپ می‌مانند ولی روی آن HV سفارشی نیست.
 * ۲) تناوب مارکرهای pinBU/pinBE (§18-1/§18-3/§18-4): روی چارت هرگز دو «کندل مهم
 *    بالاترین/پایین‌ترین» هم‌جهت پشت‌سرهم نیست. رگرشن عکس‌های مالک: پین هم‌جهت تازه
 *    وضعیت پین قبلی را بازنویسی می‌کرد و مارکر پینِ ازیادرفته برای همیشه می‌ماند.
 */
class SlLimitAndPinAlternationTest {

    private fun ms(iso: String): Long = Instant.parse(iso).toEpochMilli()

    @Test
    fun `doc 18-31 broker refuses order when stop distance exceeds N (doc example) and allows at exactly N`() {
        val t = ms("2026-10-19T08:00:00Z")   // دوشنبه، داخل پنجرهٔ سفارش‌گیری
        // مثال خود سند: N=3، سطح سفارش 4173، کف ناحیه 4168 ⇒ فاصلهٔ حد ضرر 5 > 3 ⇒ سفارش باطل است
        val b = PaperBroker(Settings().apply { maxSlUnits = 3.0 })
        val sDoc = Setup(1L, 1, 6, 4170.0, 4160.0, 1, 0).apply {
            hvH = 4172.99; hvL = 4168.0; sl = 4167.98
            tp1 = 4180.0; tp2 = 4190.0; tpx = 4220.0; entry = 4173.0
        }
        assertFalse(
            b.registerLimit(sDoc, Candle(0, t, 4174.0, 4175.0, 4173.5, 4174.5, 100.0)),
            "سفارش با SL>N نباید ثبت شود (§18-31)"
        )
        assertTrue(b.lastError?.contains("18-31") == true, "lastError=${b.lastError}")
        assertEquals(0, b.pendingOrders.size)

        // پیش‌فرض سند N=3 است؛ همان مثال با فاصلهٔ 5 باید با تنظیمات پیش‌فرض هم رد شود
        // (دقیقاً همان چیزی که مثال §18-31 می‌گوید).
        assertEquals(3.0, Settings().maxSlUnits)
        val bDefault = PaperBroker(Settings())
        assertFalse(
            bDefault.registerLimit(sDoc, Candle(0, t, 4174.0, 4175.0, 4173.5, 4174.5, 100.0)),
            "با پیش‌فرض N=3 مثال خود سند باید رد شود (§18-31)"
        )

        // دقیقاً برابر N مجاز است («بیشتر از N» باطل است) — اینجا N=3 را صریحاً تنظیم می‌کنیم.
        val b2 = PaperBroker(Settings().apply { maxSlUnits = 3.0 })
        val sExact = Setup(2L, 1, 6, 165.0, 155.0, 1, 0).apply {
            hvH = 169.99; hvL = 167.0; sl = 167.0
            tp1 = 180.0; tp2 = 190.0; tpx = 220.0; entry = 170.0
        }
        assertTrue(
            b2.registerLimit(sExact, Candle(0, t, 171.0, 172.0, 170.5, 171.5, 100.0)),
            "فاصلهٔ حد ضرر دقیقاً برابر N=3 باید مجاز باشد"
        )

        // با N بزرگ‌تر در تنظیمات، همان سفارش اول ثبت می‌شود
        val b3 = PaperBroker(Settings().apply { maxSlUnits = 6.0 })
        assertTrue(b3.registerLimit(sDoc, Candle(0, t, 4174.0, 4175.0, 4173.5, 4174.5, 100.0)))
    }

    @Test
    fun `doc 18-31 engine keeps HV and setup but never arms when SL exceeds N`() {
        fun run(maxSl: Double): Pair<Setup, PaperBroker> {
            val cfg = Settings().apply {
                    hvScanFirstTouch = false   // §۱۸-۱۷: اسکن از کف ناحیهٔ LV
                tfMode = 1
                tfS = Tf.H1; tfM = Tf.M15; tf1 = Tf.M5; tf2 = Tf.M1
                maxSlUnits = maxSl
            }
            val e = Engine(cfg)
            val b = PaperBroker(cfg)
            e.broker = b; b.engine = e
            e.HH = 2100.0; e.HL = 1980.0; e.trend = 1
            val baseT = 28_800_000L   // پنجشنبهٔ اپوک 08:00 UTC — داخل پنجرهٔ سفارش‌گیری
            val s = Setup(1L, 1, 1, 2010.0, 2000.0, 1, -1).apply {
                zT = baseT; touchBi = 0; touchT = baseT; touchMidStart = baseT
            }
            e.setups.add(s)
            val bars = ArrayList<Candle>()
            var bi = 0
            fun addM1(o: Double, h: Double, l: Double, c: Double, v: Double) {
                bars.add(Candle(bi, baseT + bi * 60_000L, o, h, l, c, v)); bi++
            }
            addM1(2008.0, 2009.0, 2005.0, 2006.0, 200.0)
            addM1(2006.0, 2007.0, 2004.0, 2004.5, 200.0)
            addM1(2004.5, 2006.0, 2002.0, 2005.5, 200.0)
            addM1(2005.5, 2008.0, 2005.0, 2007.5, 200.0)
            addM1(2007.5, 2009.0, 2007.0, 2008.5, 200.0)
            addM1(2008.5, 2009.0, 2007.0, 2007.5, 50.0)
            addM1(2007.5, 2008.0, 2006.0, 2007.2, 40.0)
            addM1(2007.2, 2010.0, 2007.0, 2009.5, 80.0)   // 7 → HV (باکس 2007..2010؛ فاصلهٔ SL از ورود 3.03)
            addM1(2009.5, 2011.0, 2009.2, 2010.5, 60.0)
            addM1(2010.5, 2011.0, 2010.2, 2010.8, 70.0)
            addM1(2010.8, 2012.0, 2010.5, 2011.5, 90.0)
            addM1(2011.5, 2013.0, 2011.2, 2012.5, 100.0)
            addM1(2012.5, 2014.0, 2012.0, 2013.5, 110.0)
            addM1(2013.5, 2015.0, 2013.0, 2014.5, 100.0)
            addM1(2014.5, 2016.0, 2014.0, 2015.5, 100.0)
            addM1(2015.5, 2016.5, 2015.0, 2016.0, 100.0)
            e.feed(bars, lastIsClosed = true)
            return s to b
        }

        // N پیش‌فرض سند (۳): فاصلهٔ حد ضرر این HV برابر 3.03 است ⇒ مسلح نمی‌شود، ولی HV و ستاپ می‌مانند
        val (sBlocked, bBlocked) = run(3.0)
        assertEquals(7, sBlocked.hvBi, "HV باید سر جایش بماند (§18-31: «HV مطابق ستاپ می‌ماند»)")
        assertEquals(7, sBlocked.slBlockedHvBi)
        assertTrue(sBlocked.stage != 6, "ستاپ نباید مسلح شده باشد؛ stage=${sBlocked.stage}")
        assertEquals(0, bBlocked.pendingOrders.size, "سفارشی نباید در کارگزار ثبت شده باشد")
        assertEquals(0, bBlocked.orders.size, "در بک‌تست هم سفارشی حساب نمی‌شود")

        // با N=10 همان سناریو مسلح می‌شود
        val (sArmed, bArmed) = run(10.0)
        assertEquals(6, sArmed.stage)
        assertEquals(1, bArmed.pendingOrders.size)
    }

    @Test
    fun `doc 18-4 pin markers strictly alternate on real GC bars (owner screenshot regression)`() {
        val t0 = ms("2026-10-19T00:00:00Z")
        val e = Engine(Settings().apply { tfS = Tf.H1; tfM = Tf.H1; tf1 = Tf.H1; tf2 = Tf.H1 })
        // ۳۰ کندل واقعی اول GC=F 1H از بازپخش مالک: BU@3 ← BE@5 ← BU@8 ثبت و هر دو پینِ
        // میانی باطل می‌شوند؛ بدون اصلاح، BU@3 برای همیشه می‌ماند و کنار BU@21 دو
        // «کندل مهم پایین‌ترین» پشت‌سرهم دیده می‌شود.
        val bars = listOf(
            Candle(0, t0 + 0 * 3_600_000L, 2736.300048828125, 2738.89990234375, 2734.60009765625, 2738.699951171875, 100.0),
            Candle(1, t0 + 1 * 3_600_000L, 2738.5, 2740.199951171875, 2737.39990234375, 2738.60009765625, 100.0),
            Candle(2, t0 + 2 * 3_600_000L, 2738.699951171875, 2740.89990234375, 2736.300048828125, 2736.60009765625, 100.0),
            Candle(3, t0 + 3 * 3_600_000L, 2736.699951171875, 2744.300048828125, 2735.89990234375, 2741.300048828125, 100.0),
            Candle(4, t0 + 4 * 3_600_000L, 2741.39990234375, 2746.300048828125, 2739.89990234375, 2745.60009765625, 100.0),
            Candle(5, t0 + 5 * 3_600_000L, 2745.60009765625, 2747.800048828125, 2744.300048828125, 2746.699951171875, 100.0),
            Candle(6, t0 + 6 * 3_600_000L, 2746.60009765625, 2747.0, 2744.5, 2745.300048828125, 100.0),
            Candle(7, t0 + 7 * 3_600_000L, 2745.300048828125, 2745.300048828125, 2739.5, 2740.0, 100.0),
            Candle(8, t0 + 8 * 3_600_000L, 2739.800048828125, 2745.60009765625, 2738.60009765625, 2745.300048828125, 100.0),
            Candle(9, t0 + 9 * 3_600_000L, 2745.10009765625, 2747.39990234375, 2740.199951171875, 2745.5, 100.0),
            Candle(10, t0 + 10 * 3_600_000L, 2745.39990234375, 2748.0, 2744.39990234375, 2746.300048828125, 100.0),
            Candle(11, t0 + 11 * 3_600_000L, 2746.39990234375, 2751.699951171875, 2745.60009765625, 2749.300048828125, 100.0),
            Candle(12, t0 + 12 * 3_600_000L, 2749.39990234375, 2749.60009765625, 2742.699951171875, 2748.300048828125, 100.0),
            Candle(13, t0 + 13 * 3_600_000L, 2748.300048828125, 2752.800048828125, 2748.0, 2749.39990234375, 100.0),
            Candle(14, t0 + 14 * 3_600_000L, 2749.5, 2754.5, 2747.5, 2753.39990234375, 100.0),
            Candle(15, t0 + 15 * 3_600_000L, 2753.5, 2755.39990234375, 2748.39990234375, 2753.800048828125, 100.0),
            Candle(16, t0 + 16 * 3_600_000L, 2753.800048828125, 2754.300048828125, 2736.60009765625, 2739.89990234375, 100.0),
            Candle(17, t0 + 17 * 3_600_000L, 2739.800048828125, 2741.89990234375, 2731.39990234375, 2734.800048828125, 100.0),
            Candle(18, t0 + 18 * 3_600_000L, 2734.60009765625, 2738.800048828125, 2732.699951171875, 2738.300048828125, 100.0),
            Candle(19, t0 + 19 * 3_600_000L, 2738.199951171875, 2740.199951171875, 2736.10009765625, 2737.5, 100.0),
            Candle(20, t0 + 20 * 3_600_000L, 2737.39990234375, 2737.699951171875, 2729.60009765625, 2730.699951171875, 100.0),
            Candle(21, t0 + 21 * 3_600_000L, 2730.60009765625, 2735.89990234375, 2728.5, 2734.699951171875, 100.0),
            Candle(22, t0 + 22 * 3_600_000L, 2734.699951171875, 2736.0, 2733.5, 2734.5, 100.0),
            Candle(23, t0 + 23 * 3_600_000L, 2734.699951171875, 2736.5, 2733.5, 2735.10009765625, 100.0),
            Candle(24, t0 + 24 * 3_600_000L, 2735.0, 2737.10009765625, 2734.699951171875, 2735.800048828125, 100.0),
            Candle(25, t0 + 25 * 3_600_000L, 2735.800048828125, 2739.39990234375, 2735.0, 2738.89990234375, 100.0),
            Candle(26, t0 + 26 * 3_600_000L, 2738.89990234375, 2744.800048828125, 2738.300048828125, 2742.300048828125, 100.0),
            Candle(27, t0 + 27 * 3_600_000L, 2742.0, 2745.0, 2742.0, 2743.199951171875, 100.0),
            Candle(28, t0 + 28 * 3_600_000L, 2743.0, 2747.5, 2743.0, 2745.699951171875, 100.0),
            Candle(29, t0 + 29 * 3_600_000L, 2745.199951171875, 2750.199951171875, 2745.199951171875, 2749.0, 100.0)
        )
        e.feed(bars, lastIsClosed = true)
        val pins = e.markers.filter { it.kind == MarkerKind.PIN_BU || it.kind == MarkerKind.PIN_BE }
            .sortedWith(compareBy({ it.bi }, { it.t }))
        assertTrue(pins.isNotEmpty())
        for (i in 1 until pins.size) {
            assertTrue(
                pins[i].kind != pins[i - 1].kind,
                "دو مارکر هم‌جهت پشت‌سرهم نباید روی چارت بماند: bi=${pins[i - 1].bi} و bi=${pins[i].bi} kind=${pins[i].kind}"
            )
        }
        assertTrue(pins.none { it.bi == 3 }, "مارکر BU@3 که پینش مرده نباید بماند: ${pins.map { it.bi to it.kind }}")
        assertTrue(pins.any { it.bi == 21 && it.kind == MarkerKind.PIN_BU }, "پین زندهٔ BU@21 باید باشد")
    }

    @Test
    fun `doc 18-4 loading an old save prunes dead same-side pin markers but keeps the live pins`() {
        val cfg = Settings()
        val e = Engine(cfg)
        val b = PaperBroker(cfg)
        val t0 = ms("2026-10-19T08:00:00Z")
        // ذخیرهٔ قدیمی با مارکرهای مرده: BU,BU و BE,BE پشت‌سرهم؛ پین زندهٔ فعلی BE@120 است
        e.markers.add(Marker(t0, 0, 100.0, MarkerKind.PIN_BU, "کندل مهم پایین‌ترین\nReady BU · pinBU"))
        e.markers.add(Marker(t0 + 1000, 1, 90.0, MarkerKind.PIN_BU, "کندل مهم پایین‌ترین\nReady BU · pinBU"))
        e.markers.add(Marker(t0 + 2000, 2, 110.0, MarkerKind.PIN_BE, "کندل مهم بالاترین\nReady BE · pinBE"))
        e.markers.add(Marker(t0 + 3000, 3, 120.0, MarkerKind.PIN_BE, "کندل مهم بالاترین\nReady BE · pinBE"))
        e.beActive = true
        e.lastBEpin = 120.0
        val ld = Store.load(Store.save(cfg, e, b, emptyList()))
        val pins = ld.eng.markers.filter { it.kind == MarkerKind.PIN_BU || it.kind == MarkerKind.PIN_BE }
        assertEquals(
            listOf(MarkerKind.PIN_BU to 90.0, MarkerKind.PIN_BE to 120.0),
            pins.map { it.kind to it.price },
            "بعد از بازیابی فقط دنبالهٔ متناوب می‌ماند و مارکر پین زنده حفظ می‌شود"
        )
    }
}
