package com.alisport.goldpin.core

/**
 * انتخاب «سری منبع» برای یک تایم‌فریم هدف.
 *
 * چرا جدا شد: منطق انتخاب سری پیش‌تر داخل `AppState.aggregateBestAvailable` بود و یک
 * میان‌بر داشت که هر سری‌ای را (حتی **درشت‌تر** از تایم‌فریم هدف) برمی‌گرداند. نتیجه‌اش
 * باگ «تغییر مود ⇒ سفارش‌ها صفر» بود: بعد از عوض‌شدن `cfg.tf2`، همان سری مود قبلی
 * (مثلاً H1) به‌عنوان سری مود جدید (مثلاً M1) به موتور داده می‌شد و `Feed.aggregate`
 * هم چون هر کندل H1 در باکت M1 جدا می‌افتد، عملاً همان کندل‌ها را با برچسب تازه
 * برمی‌گرداند ⇒ موتور روی دادهٔ جعلی اجرا می‌شد و هیچ ستاپ/سفارشی نمی‌ساخت.
 *
 * قاعدهٔ سخت: از یک سری **فقط** می‌توان تایم‌فریم مساوی یا درشت‌تر ساخت، هرگز ریزتر.
 * اگر چنین سری‌ای در حافظه نباشد، پاسخ «هیچ» است و caller باید تاریخچه را دانلود کند،
 * نه اینکه روی دادهٔ نامعتبر موتور را از صفر اجرا کند.
 */
object SeriesPick {

    /** فاصلهٔ میانی زمانی کندل‌ها بر حسب ثانیه (۰ = نامشخص/کوتاه‌تر از دو کندل). */
    fun stepOf(list: List<Candle>): Long {
        if (list.size < 2) return 0L
        // تاریخچهٔ ده‌ساله می‌تواند بیش از ۳٫۴ میلیون کندل داشته باشد. گرفتن median
        // دقیق با ArrayList<Long> هم boxed-memory سنگین می‌سازد و هم چند ثانیه sort می‌کند؛
        // برای تشخیص تایم‌فریم، نمونه‌برداری یکنواخت از 4096 فاصله به‌اندازهٔ کافی مقاوم است.
        if (list.size > 100_000) {
            val sampleSize = 4096
            val steps = LongArray(sampleSize)
            var count = 0
            for (j in 0 until sampleSize) {
                val i = 1 + (((list.size - 2L) * j) / (sampleSize - 1)).toInt()
                val d = (list[i].t - list[i - 1].t) / 1000L
                if (d > 0) steps[count++] = d
            }
            if (count == 0) return 0L
            steps.sort(0, count)
            return steps[count / 2]
        }
        val steps = ArrayList<Long>(list.size - 1)
        for (i in 1 until list.size) {
            val d = (list[i].t - list[i - 1].t) / 1000L
            if (d > 0) steps.add(d)
        }
        if (steps.isEmpty()) return 0L
        steps.sort()
        return steps[steps.size / 2]
    }

    /** دو فاصلهٔ زمانی «یک تایم‌فریم» حساب می‌شوند؟ (تحمل ۱٫۵ برابر برای لرزش داده) */
    fun sameTf(a: Long, b: Long): Boolean =
        a > 0 && b > 0 && minOf(a, b) * 3 >= maxOf(a, b) * 2

    /**
     * آیا این سری می‌تواند مبنای تایم‌فریم [targetSec] باشد؟
     * یعنی فاصله‌اش معلوم است و از هدف ریزتر (یا مساوی) است. سری درشت‌تر ⇒ false.
     */
    fun canBuild(targetSec: Int, series: List<Candle>): Boolean {
        if (targetSec <= 0 || series.isEmpty()) return false
        val step = stepOf(series)
        if (step <= 0L) return false              // تک‌کندل/بدون فاصلهٔ زمانی ⇒ قابل قضاوت نیست
        return step <= targetSec.toLong()
    }

    /**
     * اولین سری قابل‌استفاده از میان [series] به ترتیب اولویت، یا `null` اگر هیچ سری‌ای
     * از هدف ریزتر نبود. (فهرست خالی‌ها نادیده گرفته می‌شوند.)
     */
    fun firstUsable(targetSec: Int, series: List<List<Candle>>): List<Candle>? {
        for (s in series) {
            if (s.isEmpty()) continue
            if (canBuild(targetSec, s)) return s
        }
        return null
    }
}
