package com.alisport.goldpin.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * سند ۶ (§۴-۸ جدید) — «تریگر ۲ معکوس».
 *
 * ادعاها:
 *  ۱) پیش‌فرض (`trigger2Fade=false`) رفتار سند ۵ را دست‌نخورده نگه می‌دارد (رگرسیون).
 *  ۲) در حالت معکوس، سطح ورود همان لبهٔ باکس HV می‌ماند، ولی جهت سفارش برعکس ساختار است،
 *     حد ضرر آینهٔ همان فاصله (R) در آن‌طرف ورود است و حد سود = k × R.
 *  ۳) فیلتر «حداقل فاصلهٔ حد ضرر نسبت به کارمزد» سفارش‌های بی‌معنا را حذف می‌کند.
 */
class DocFadeTest {

    private fun load(path: String): List<Candle> {
        val out = ArrayList<Candle>()
        var bi = 0
        File(path).forEachLine { ln ->
            val s = ln.trim()
            if (s.isEmpty() || s.startsWith("time")) return@forEachLine
            val p = s.split(',')
            if (p.size < 6) return@forEachLine
            out.add(Candle(bi++, p[0].toLong(), p[1].toDouble(), p[2].toDouble(), p[3].toDouble(), p[4].toDouble(), p[5].toDouble()))
        }
        return out
    }

    private fun run(fade: Boolean, targetR: Double, costMult: Double = 0.0, cmsn: Double = 0.0,
                    mm: Int = MoneyMode.NONE): Triple<Engine, PaperBroker, Setup?> {
        val cfg = Settings().apply {
            vpRows = 24; vpSmooth = 0; distMode = VolumeProfile.DIST_UNIFORM
            tfMode = 1; tfS = Tf.H1; tfM = Tf.M15; tf1 = Tf.M5; tf2 = Tf.M1
            mintick = 0.01; slBufTicks = 2.0; entryBufTicks = 1.0; minTPunits = 10.0
            maxSlUnits = 3.0; maxLeverage = 500.0; riskPct = 1.0; initialEquity = 100_000.0
            contractSize = 1.0; strategy = Strategy.DOC; mmMode = mm
            hvScanFirstTouch = true
            trigger2Fade = fade
            trigger2TargetR = targetR
            minSlCostMult = costMult
            cmsnManual = cmsn; cmsnAuto = false
        }
        val e = Engine(cfg)
        val b = PaperBroker(cfg)
        e.broker = b; b.engine = e
        e.barCommitHook = { cd -> b.onBar(cd, live = false) }
        val candles = load(System.getenv("SCEN_PROBE_CSV") ?: "../scenario/m1_probeB.csv")
        e.runAll(candles)
        return Triple(e, b, e.setups.firstOrNull { it.zTop == 2013.20 })
    }

    @Test
    fun `document five behaviour is unchanged by default`() {
        val (_, b, _) = run(fade = false, targetR = 0.0)
        val o = b.orders.first()
        assertEquals(1, o.dir, "خرید در جهت ساختار صعودی")
        assertEquals(2013.21, o.price, 1e-9, "سطح ورود = سقف باکس HV + ۱ تیک")
        assertEquals(2012.68, o.sl, 1e-9, "حد ضرر = کف باکس HV − ۲ تیک")
        assertEquals(2024.72, o.tpX, 1e-9, "حد سود = ۱.۲۷۲ فیبو (سند ۵)")
        assertTrue(!o.tp1.isNaN() && !o.tp2.isNaN(), "پله‌های TP1/TP2 سند ۵ باید وجود داشته باشند")
    }

    @Test
    fun `doc six inverted trigger two mirrors the trade`() {
        val (_, b, _) = run(fade = true, targetR = 3.0)
        val o = b.orders.first()
        assertEquals(-1, o.dir, "در حالت معکوس، معاملهٔ فروش باز می‌شود")
        assertEquals(2013.21, o.price, 1e-9, "سطح ورود همان لبهٔ باکس HV + ۱ تیک می‌ماند")
        // فاصلهٔ سند: 2013.21 − 2012.68 = 0.53 ⇒ حد ضرر آینه: 2013.21 + 0.53 = 2013.74
        assertEquals(2013.74, o.sl, 1e-9, "حد ضرر = ورود + همان فاصله (آینه)")
        assertEquals(2013.21 - 3 * 0.53, o.tpX, 1e-9, "حد سود = ورود − ۳R")
        assertTrue(o.tp1.isNaN() && o.tp2.isNaN(), "در حالت هدف بر حسب R پله‌های فیبو حذف می‌شوند")
        // معاملهٔ واقعی باید در جهت فروش پر و بسته شود
        val t = b.trades.firstOrNull()
        assertTrue(t != null && t!!.dir == -1, "معاملهٔ پر شده باید فروش باشد")
    }

    @Test
    fun `doc six cost filter rejects stop distances smaller than the fee multiple`() {
        // کارمزد/اسپرد ۰٫۲ دلار به‌ازای هر واحد و ضریب ۶ ⇒ حد ضرر باید دست‌کم ۱٫۲ دلار باشد.
        // فاصلهٔ این سناریو ۰٫۵۳ دلار است ⇒ سفارش نباید ثبت شود.
        //
        // ⚠ کارمزد فقط در مودهای §۲۰ وجود دارد (§۵-۱: «مود پیش‌فرض اپ بدون کارمزد است»).
        // پیش‌تر این تست با `mmMode = NONE` اجرا می‌شد و درنتیجه هزینهٔ ۰٫۲ دلاریِ تنظیمات را
        // «کارمزد واقعی» می‌گرفت — همان فرضی که باگ «مود §۲۰ روشن ⇒ همهٔ سفارش‌ها صفر» را ساخت:
        // اسپرد ذخیره‌شدهٔ بروکر (۰٫۶۶) بعد از خاموش‌کردن مود هم فیلتر را فعال نگه می‌داشت.
        val mmAllLost = MoneyMode.MANAGE_ALL_LOST
        val (_, b0, _) = run(fade = true, targetR = 3.0, costMult = 0.0, cmsn = 0.2, mm = mmAllLost)
        assertEquals(1, b0.orders.size, "بدون فیلتر کارمزد، سفارش ثبت می‌شود")
        val (_, b1, _) = run(fade = true, targetR = 3.0, costMult = 6.0, cmsn = 0.2, mm = mmAllLost)
        assertEquals(0, b1.orders.size, "با ضریب ۶ و هزینهٔ ۰٫۲ دلار، سفارش ۰٫۵۳ دلاری ثبت نمی‌شود")
        val (_, b2, _) = run(fade = true, targetR = 3.0, costMult = 2.0, cmsn = 0.2, mm = mmAllLost)
        assertEquals(1, b2.orders.size, "با ضریب ۲ (آستانهٔ ۰٫۴ دلار) سفارش مجاز است")
    }

    @Test
    fun `doc five mode has no commission so a stored spread cannot silence the filter`() {
        // همان اسپرد ۰٫۲ در تنظیمات هست (مثلاً از یک بار روشن‌بودن مود §۲۰)، ولی مود خاموش است:
        // §۵-۱ می‌گوید در این مود کارمزدی کسر نمی‌شود، پس فیلتر §۵-۳ هم نباید چیزی را رد کند.
        val (_, b, _) = run(fade = true, targetR = 3.0, costMult = 6.0, cmsn = 0.2, mm = MoneyMode.NONE)
        assertEquals(1, b.orders.size,
            "در مود §۵ (بدون کارمزد) اسپردِ ذخیره‌شده نباید سفارش را رد کند")
    }
}
