package com.alisport.goldpin.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * باگ گزارش‌شدهٔ کاربر (لاگ SM-J810F، ۳۰ مهر):
 * «با انتخاب مود Mixed یا ManageAllLost روی حالت تریگر ۲ معکوس، کل معاملات صفر می‌شود.»
 *
 * علت: اسپرد Swissquote روی XAU/USD در آن لحظه **۰٫۶۶ دلار** بود (premium 25.6 pip).
 * فیلتر کارمزد §۵-۳ با ضریب ۶ ⇒ آستانه ۳٫۹۶ دلار، ولی میانهٔ فاصلهٔ حد ضرر ۱٫۹۸ دلار
 * (بیشینه ۳٫۸۴) ⇒ **۲۱۷ سفارش از ۲۱۷** رد شد و هر سه مود (§۵، ManageAllLost، Mixed) صفر داد.
 *
 * منطق درست (چیزی که این تست‌ها قفل می‌کنند):
 *  • مود §۲۰ روشن ⇒ کارمزد واقعی وجود دارد ⇒ فیلتر کارمزد فعال است.
 *  • مود §۲۰ خاموش ⇒ طبق §۵-۱ «بدون کارمزد» ⇒ فیلتر نباید فعال باشد، حتی اگر اسپردی
 *    از قبل در تنظیمات ذخیره شده باشد (باگی که فیلتر را بعد از خاموش‌کردن مود زنده نگه می‌داشت).
 */
class SlCostFilterTest {

    private fun chain(stepSec: Int = 60, count: Int = 4000, startMs: Long = 1_700_006_400_000L): List<Candle> {
        // رمپ سادهٔ صعودی/نزولی با دامنهٔ کافی تا موتور ستاپ/ناحیه بسازد.
        val out = ArrayList<Candle>(count)
        var px = 2000.0
        for (i in 0 until count) {
            val drift = if ((i / 500) % 2 == 0) 0.25 else -0.25
            px += drift
            val hi = px + 0.6
            val lo = px - 0.6
            out.add(Candle(i, startMs + i.toLong() * stepSec * 1000L, px, hi, lo, px, 100.0 + (i % 7)))
        }
        return out
    }

    private fun run(spread: Double, mode: Int, costMult: Double): Engine {
        val cfg = Settings()
        cfg.tfS = Tf.H1; cfg.tfM = Tf.M15; cfg.tf1 = Tf.M5; cfg.tf2 = Tf.M1
        cfg.vpRows = 24; cfg.vpSmooth = 0
        cfg.minSlCostMult = costMult
        cfg.trigger2Fade = true; cfg.trigger2TargetR = 4.0
        cfg.maxSlUnits = 3.0
        cfg.mmMode = mode
        cfg.cmsnAuto = false; cfg.cmsnManual = spread
        cfg.maxSetups = 1
        val eng = Engine(cfg)
        val broker = PaperBroker(cfg)
        eng.broker = broker; broker.engine = eng
        eng.barCommitHook = { cd -> broker.onBar(cd, live = false) }
        val c = chain()
        eng.feed(c, lastIsClosed = true)
        return eng
    }

    @Test
    fun `money mode off means no commission so the cost filter cannot block anything`() {
        // همان اسپرد ۰٫۶۶ که در لاگ کاربر بود، ولی مود §۲۰ خاموش (§۵: بدون کارمزد).
        val eng = run(spread = 0.66, mode = MoneyMode.NONE, costMult = 6.0)
        assertEquals(0.0, eng.slCostThreshold(), 1e-9, "در مود §۵ آستانهٔ فیلتر باید صفر باشد")
        assertEquals(0.0, eng.slCostPerUnit(), 1e-9)
        assertEquals(0, eng.cnt.slCostBlocked, "در مود §۵ هیچ سفارشی نباید با فیلتر کارمزد رد شود")
    }

    @Test
    fun `money mode on exposes the real broker spread as the cost base`() {
        val eng = run(spread = 0.66, mode = MoneyMode.MANAGE_ALL_LOST, costMult = 6.0)
        assertEquals(0.66, eng.slCostPerUnit(), 1e-9)
        assertEquals(3.96, eng.slCostThreshold(), 1e-9)
    }

    @Test
    fun `zero cost multiplier disables the filter in every mode`() {
        for (mode in intArrayOf(MoneyMode.NONE, MoneyMode.MANAGE_ALL_LOST, MoneyMode.MIXED)) {
            val eng = run(spread = 0.66, mode = mode, costMult = 0.0)
            assertEquals(0.0, eng.slCostThreshold(), 1e-9, "مود $mode با ضریب ۰ باید آستانهٔ صفر بدهد")
            assertEquals(0, eng.cnt.slCostBlocked, "مود $mode نباید چیزی را با فیلتر رد کند")
        }
    }

    @Test
    fun `median stop distance is reported after a run`() {
        val eng = run(spread = 0.0, mode = MoneyMode.NONE, costMult = 0.0)
        val med = eng.slDistMedian()
        assertTrue(med.isNaN() || med > 0.0, "میانهٔ فاصلهٔ حد ضرر باید مثبت یا NaN باشد: $med")
    }
}
