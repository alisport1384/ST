package com.alisport.goldpin.core

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * نگهبان باگ «در لایو همهٔ سفارش‌ها/معاملات غیب می‌شوند».
 *
 * در لایو، هر تیک فقط *دنبالهٔ* کندل‌ها را به موتور می‌دهد. موتور و کارگزار **نباید** در این
 * مسیر هیچ ریست/پاک‌سازی‌ای انجام دهند؛ تاریخچه باید فقط رشد کند. (پاک شدن در نسخهٔ قبل از
 * سمت اپ می‌آمد که سری داده را جایگزین و موتور را بازسازی می‌کرد — این تست همان قرارداد را
 * روی موتور قفل می‌کند.)
 */
class LiveHistoryKeepsGrowingTest {

    private fun bar(bi: Int, px: Double, vol: Double): Candle =
        Candle(bi, 1_761_000_000_000L + bi * 60_000L, px, px + 0.6, px - 0.6, px + 0.2, vol)

    @Test
    fun `live tail feeding never clears broker history`() {
        val cfg = Settings().apply {
            vpRows = 24; vpSmooth = 0; distMode = VolumeProfile.DIST_UNIFORM
            tfMode = 1; tfS = Tf.H1; tfM = Tf.M15; tf1 = Tf.M5; tf2 = Tf.M1
            maxSlUnits = 3.0
        }
        val e = Engine(cfg)
        val b = PaperBroker(cfg)
        e.broker = b; b.engine = e
        e.barCommitHook = { cd -> b.onBar(cd, live = false) }

        // ۴٬۰۰۰ کندل تاریخی (موج ساختگی با روند و نوسان)
        val all = ArrayList<Candle>(4200)
        var px = 4_000.0
        for (i in 0 until 4200) {
            px += kotlin.math.sin(i / 37.0) * 1.4 + kotlin.math.sin(i / 9.0) * 0.3
            all.add(bar(i, px, 50.0 + (i % 7) * 12.0))
        }

        val hist = all.subList(0, 4000)
        e.feed(hist, lastIsClosed = true)
        val ordersBefore = b.orders.size
        val tradesBefore = b.trades.size

        // شبیه‌سازی تیک‌های لایو: هر بار دنبالهٔ کامل، آخرین کندل «باز»
        for (cut in 4001..4200) {
            e.feed(all.subList(0, cut), lastIsClosed = false)
        }

        assertTrue(b.orders.size >= ordersBefore, "تاریخچهٔ سفارش‌ها فقط می‌تواند رشد کند")
        assertTrue(b.trades.size >= tradesBefore, "تاریخچهٔ معاملات فقط می‌تواند رشد کند")
        assertTrue(b.orders.isNotEmpty(), "باید حداقل یک سفارش در این سری ساخته شود")
    }
}
