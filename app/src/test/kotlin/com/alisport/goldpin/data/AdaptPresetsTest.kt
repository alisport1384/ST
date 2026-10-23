package com.alisport.goldpin.data

import com.alisport.goldpin.ADAPT_PRESETS
import com.alisport.goldpin.core.MoneyMode
import com.alisport.goldpin.core.Strategy
import com.alisport.goldpin.core.Tuned
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * سلامت جدول پیش‌تنظیم‌های بخش «تطبیق».
 *
 * این عددها مستقیم به دست کاربر می‌رسند و هر کدام یک اجرای بک‌تست کامل را تعیین می‌کنند؛
 * یک تایپو (مثلاً کارمزد صفر، یا سقف N کوچک‌تر از فاصلهٔ حد ضرر) یعنی «صفر معامله» یا
 * نتیجه‌ای کاملاً متفاوت با آنچه در برچسب نوشته شده. پس جدول باید اینجا قفل شود.
 */
class AdaptPresetsTest {

    @Test
    fun presetTableIsWellFormedAndStarredPresetIsTheTunedOptimum() {
        assertTrue("جدول تطبیق نباید خالی باشد", ADAPT_PRESETS.isNotEmpty())

        // شناسه‌ها یکتا (کلید لاگ و ذخیرهٔ انتخاب کاربر)
        assertEquals("شناسهٔ تکراری در جدول تطبیق",
            ADAPT_PRESETS.size, ADAPT_PRESETS.map { it.id }.distinct().size)

        for (p in ADAPT_PRESETS) {
            assertTrue("برچسب خالی: ${p.id}", p.label.isNotBlank())
            assertTrue("توضیح خالی: ${p.id}", p.note.isNotBlank())
            assertTrue("استراتژی نامعتبر در ${p.id}",
                p.strategy == Strategy.DOC || p.strategy == Strategy.NO_TREND)
            assertTrue("مود مالی نامعتبر در ${p.id}",
                p.mm == MoneyMode.NONE || p.mm == MoneyMode.MANAGE_ALL_LOST || p.mm == MoneyMode.MIXED)
            // کارمزد صفر یعنی نتایجِ برچسب‌خورده دیگر با آنچه اپ نشان می‌دهد نمی‌خواند
            assertTrue("کارمزد پیش‌تنظیم باید مثبت باشد: ${p.id}", p.cmsn > 0.0)
            // ناسازگاری آستانه‌ها همان باگ «کل معاملات صفر» است: سقف N باید از حداقل بزرگ‌تر باشد
            assertTrue("سقف N باید از حداقل فاصلهٔ حد ضرر بزرگ‌تر باشد: ${p.id}", p.maxSl > p.minSl)
            // آستانهٔ فیلتر کارمزد (ضریب × کارمزد) نباید از سقف N بگذرد، وگرنه **همهٔ** سفارش‌ها
            // رد می‌شوند و کاربر «کل معاملات صفر» می‌بیند. تنها استثنا، پیش‌تنظیم مرجعِ سند ۷ است
            // که عمداً همان حالت خراب را بازتولید می‌کند تا کاربر بتواند مقایسه کند؛ برای همان هم
            // باید در توضیحش صریحاً هشدار «صفر سفارش» آمده باشد.
            val blocksEveryOrder = p.costMult * p.cmsn > p.maxSl
            if (p.id == "doc7-defaults") {
                assertTrue("پیش‌تنظیم مرجع سند ۷ باید واقعاً حالت صفر‌سفارشی باشد وگرنه برچسبش دروغ است",
                    blocksEveryOrder)
                assertTrue("هشدار صفر‌سفارشی باید در توضیح پیش‌تنظیم مرجع باشد: ${p.id}",
                    p.note.contains("صفر سفارش"))
            } else {
                assertFalse("آستانهٔ فیلتر کارمزد از سقف N بزرگ‌تر است ⇒ صفر سفارش: ${p.id}",
                    blocksEveryOrder)
            }
            // هدف صفر فقط برای استراتژی ۲ معنا دارد (حد سود واحدی)؛ در استراتژی ۱ یعنی پله‌های
            // فیبو، که روی این داده فاجعه بود ⇒ نباید در جدول باشد.
            if (p.strategy == Strategy.DOC) {
                assertTrue("هدف R=۰ (پله‌های فیبو) برای استراتژی ۱ نباید پیشنهاد شود: ${p.id}",
                    p.targetR > 0.0)
            }
        }

        // پیش‌تنظیم ستاره‌دار باید دقیقاً همان تنظیمی باشد که [Tuned] و آزمون بک‌تستِ دادهٔ
        // یک‌ساله سنجیده‌اند؛ وگرنه عددِ روی دکمه با نتیجهٔ واقعی نمی‌خواند.
        val star = ADAPT_PRESETS.firstOrNull { it.id == "s1-mix-best" }
        assertTrue("پیش‌تنظیم ستاره‌دار در جدول نیست", star != null)
        star!!
        assertFalse("تنظیم آزموده‌شده: هم‌جهت با ساختار", star.fade)
        assertEquals(Tuned.TARGET_R, star.targetR, 1e-9)
        assertEquals(Tuned.SL_BUF_TICKS, star.slBufTicks, 1e-9)
        assertEquals(Tuned.ENTRY_BUF_TICKS, star.entryBufTicks, 1e-9)
        assertEquals(Tuned.MAX_SL_UNITS, star.maxSl, 1e-9)
        assertEquals(Tuned.COST_MULT, star.costMult, 1e-9)
        assertEquals(Tuned.CMSN, star.cmsn, 1e-9)
        assertEquals(MoneyMode.MIXED, star.mm)
        assertEquals(Strategy.DOC, star.strategy)

        // هر دو مود مالی باید برای استراتژی ۱ پوشش داده شده باشند (خواست کاربر: ۲ مود)
        val s1modes = ADAPT_PRESETS.filter { it.strategy == Strategy.DOC }.map { it.mm }.toSet()
        assertTrue("مود Mixed باید باشد", MoneyMode.MIXED in s1modes)
        assertTrue("مود خاموش باید باشد", MoneyMode.NONE in s1modes)

        // هر دو استراتژی و هر دو جهت (معکوس روشن/خاموش) باید در دسترس باشند
        assertTrue("استراتژی ۲ باید باشد", ADAPT_PRESETS.any { it.strategy == Strategy.NO_TREND })
        assertTrue("حالت تریگر ۲ معکوس باید باشد", ADAPT_PRESETS.any { it.fade })
        assertTrue("حالت هم‌جهت باید باشد", ADAPT_PRESETS.any { !it.fade })
    }
}
