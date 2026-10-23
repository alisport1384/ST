package com.alisport.goldpin.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * باگ «تغییر مود ⇒ سفارش‌ها صفر و دیگر شناسایی نمی‌شود» — ریشه در انتخاب سری منبع بود.
 *
 * `AppState.aggregateBestAvailable` یک میان‌بر داشت (`targetTfSec == cfg.tf2 → modeCandles`)
 * و در حالت نبود منبع ریزتر هم آخرین گزینه‌ها را بی‌قید برمی‌گرداند. چون `cfg.tf2` **قبل از**
 * فراخوانی عوض می‌شد، همان سری مود قبلی (مثلاً H1) به‌عنوان سری مود جدید (مثلاً M1)
 * برمی‌گشت و موتور روی دادهٔ جعلی اجرا می‌شد ⇒ صفر ستاپ/سفارش، برای همیشه.
 *
 * قاعده‌ای که این تست‌ها قفل می‌کنند: از یک سری فقط می‌توان تایم‌فریم مساوی یا درشت‌تر
 * ساخت؛ سری درشت‌تر هرگز مبنای تایم‌فریم ریزتر نمی‌شود و پاسخ در آن حالت «هیچ» است.
 */
class SeriesPickTest {

    private fun series(stepSec: Int, count: Int, startMs: Long = 1_700_000_000_000L): List<Candle> {
        val out = ArrayList<Candle>(count)
        for (i in 0 until count) {
            val t = startMs + i.toLong() * stepSec * 1000L
            val px = 2000.0 + (i % 17) * 0.5
            out.add(Candle(i, t, px, px + 1.0, px - 1.0, px + 0.25, 100.0 + i))
        }
        return out
    }

    @Test
    fun `step is measured as median distance`() {
        assertEquals(60L, SeriesPick.stepOf(series(60, 100)))
        assertEquals(3600L, SeriesPick.stepOf(series(3600, 100)))
        assertEquals(0L, SeriesPick.stepOf(series(60, 1)))
    }

    @Test
    fun `large series sampling still detects its base timeframe`() {
        val largeM1 = series(60, 100_001)
        assertEquals(60L, SeriesPick.stepOf(largeM1))
    }

    @Test
    fun `same timeframe tolerance is one and a half`() {
        assertTrue(SeriesPick.sameTf(3000L, 3600L))     // ۵۰ دقیقه ≈ ۱ ساعت
        assertFalse(SeriesPick.sameTf(900L, 3600L))
        assertFalse(SeriesPick.sameTf(0L, 60L))
    }

    @Test
    fun `a coarser series can never be the base of a finer timeframe`() {
        val h1 = series(3600, 500)
        assertFalse(SeriesPick.canBuild(Tf.M1, h1), "H1 نباید مبنای M1 شود")
        assertFalse(SeriesPick.canBuild(Tf.M15, h1))
        assertTrue(SeriesPick.canBuild(Tf.H1, h1))
        assertTrue(SeriesPick.canBuild(Tf.H4, h1))
        assertNull(SeriesPick.firstUsable(Tf.M1, listOf(h1)), "وقتی منبع ریزتر نیست، پاسخ باید «هیچ» باشد")
    }

    @Test
    fun `hourly series built from seven days of minute bars`() {
        val m1 = series(60, 7 * 24 * 60)               // ۱۰٬۰۸۰ کندل M1
        assertTrue(SeriesPick.canBuild(Tf.H1, m1))
        assertSame(m1, SeriesPick.firstUsable(Tf.H1, listOf(m1)))
        // تعداد کندل H1 قابل ساخت ≈ ۷ روز × ۲۴ = ۱۶۸
        val h1 = FeedAggregateForTest.aggregate(m1, Tf.H1)
        assertTrue(h1.size in 160..172, "کندل H1=${h1.size}")
    }

    @Test
    fun `priority order is respected and empty sources are skipped`() {
        val m1 = series(60, 200)
        val h1 = series(3600, 200)
        // هدف H1: منبع اول (m1) ریزتر است و برنده می‌شود، حتی اگر دومی هم‌دانه باشد.
        assertSame(m1, SeriesPick.firstUsable(Tf.H1, listOf(emptyList(), m1, h1)))
        // هدف M1: اولی نامعتبر، دومی هم نامعتبر ⇒ هیچ
        assertNull(SeriesPick.firstUsable(Tf.M1, listOf(h1, emptyList())))
        // هدف M5: m1 برنده است
        assertSame(m1, SeriesPick.firstUsable(Tf.M5, listOf(h1, m1)))
    }

    /** تجمیع ساده و مستقل (هم‌معنی Feed.aggregate در ماژول اپ) تا تست به ماژول اندروید وابسته نشود. */
    private object FeedAggregateForTest {
        fun aggregate(src: List<Candle>, targetSec: Int): List<Candle> {
            val out = ArrayList<Candle>()
            var curBucket = Long.MIN_VALUE
            var o = 0.0; var h = 0.0; var l = 0.0; var c = 0.0; var v = 0.0; var t = 0L
            for (cd in src) {
                val b = Tf.bucket(targetSec, cd.t)
                if (b != curBucket) {
                    if (curBucket != Long.MIN_VALUE) out.add(Candle(out.size, t, o, h, l, c, v))
                    curBucket = b; t = b; o = cd.o; h = cd.h; l = cd.l; c = cd.c; v = cd.v
                } else {
                    h = maxOf(h, cd.h); l = minOf(l, cd.l); c = cd.c; v += cd.v
                }
            }
            if (curBucket != Long.MIN_VALUE) out.add(Candle(out.size, t, o, h, l, c, v))
            return out
        }
    }
}
