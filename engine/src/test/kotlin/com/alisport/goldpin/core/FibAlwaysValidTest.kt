package com.alisport.goldpin.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * نسخهٔ ۷ — «فیبوناچی همیشه درست رسم می‌شود».
 *
 * عیب نسخهٔ قبل: لنگر صفر = آخرین pinBE. وقتی قیمت از آن بالاتر می‌رفت و پین تازه‌ای ثبت نشده بود،
 * لنگر **کهنه** می‌ماند، زیر «کف تأیید میانی» می‌افتاد و کد فیبو را نامعتبر می‌دانست
 * (اندازه‌گیری: ۱۵٪ ستاپ‌ها). اصلاح: لنگر صفر = بالاترین سقف (پایین‌ترین کف) واقعی روند.
 */
class FibAlwaysValidTest {

    @Test
    fun `stale pin anchor is replaced by the running trend extreme`() {
        val e = Engine(Settings())
        // روند صعودی: آخرین pinBE پایین است ولی روند تا ۲٬۲۰۰ رفته است
        e.trend = 1
        e.highestBE = 2_050.0
        e.trendHigh = 2_200.0
        assertEquals(2_200.0, e.fibAnchor(bull = true), 1e-9, "لنگر صفر باید بالاترین سقف واقعی روند باشد")

        // قرینه: روند نزولی
        e.trend = -1
        e.lowestBUpin = 1_950.0
        e.trendLow = 1_800.0
        assertEquals(1_800.0, e.fibAnchor(bull = false), 1e-9, "لنگر صفر باید پایین‌ترین کف واقعی روند باشد")
    }

    @Test
    fun `anchor stays valid when only one of the two sources exists`() {
        val e = Engine(Settings())
        e.highestBE = Double.NaN; e.trendHigh = 2_100.0
        assertEquals(2_100.0, e.fibAnchor(bull = true), 1e-9)
        e.highestBE = 2_050.0; e.trendHigh = Double.NaN
        assertEquals(2_050.0, e.fibAnchor(bull = true), 1e-9)
        e.lowestBUpin = Double.NaN; e.trendLow = Double.NaN
        assertTrue(e.fibAnchor(bull = false).isNaN())
    }

    /**
     * آزمون روی دادهٔ واقعی: برای هر ستاپ مسلح‌شده، لنگر صفر باید همیشه در سمت درستِ
     * «تأیید میانی» باشد. قبلاً ۱۵٪ ستاپ‌ها نقض می‌شد.
     */
    @Test
    fun `real data every armed setup has a valid fibonacci anchor`() {
        val dir = File(System.getenv("DOC7_DIR") ?: "/home/user/data")
        val f = listOf("xau_m1_c.csv", "xau_m1_b.csv", "xau_m1.csv").map { File(dir, it) }.firstOrNull { it.exists() }
        if (f == null) { println("FIB: داده موجود نیست — skip"); return }

        val cfg = Settings().apply {
            vpRows = 24; vpSmooth = 0; distMode = VolumeProfile.DIST_UNIFORM
            tfMode = 1; tfS = Tf.H1; tfM = Tf.M15; tf1 = Tf.M5; tf2 = Tf.M1
            trigger2Fade = false; trigger2TargetR = 0.0   // هندسهٔ سند ۵ تا فیبو واقعاً استفاده شود
        }
        val e = Engine(cfg)
        val b = PaperBroker(cfg)
        e.broker = b; b.engine = e
        val bad = ArrayList<String>()
        val seen = HashSet<Long>()
        var armed = 0
        e.barCommitHook = { cd ->
            b.onBar(cd, live = false)
            for (s in e.setups) {
                if (s.stage >= 6 && !s.entry.isNaN() && s.midRef == s.midRef && seen.add(s.id)) {
                    armed++
                    val a0 = e.fibAnchor(s.dir == 1)
                    val ok = !a0.isNaN() && (if (s.dir == 1) a0 > s.midRef else a0 < s.midRef)
                    if (!ok) bad.add("setup=${s.id} dir=${s.dir} a0=$a0 mid=${s.midRef}")
                }
            }
        }
        val bars = ArrayList<Candle>()
        var bi = 0
        f.forEachLine { ln ->
            val t = ln.trim()
            if (t.isEmpty() || t.startsWith("time")) return@forEachLine
            val p = t.split(',')
            if (p.size < 6) return@forEachLine
            bars.add(Candle(bi++, p[0].toLong(), p[1].toDouble(), p[2].toDouble(),
                p[3].toDouble(), p[4].toDouble(), p[5].toDouble()))
        }
        e.runAll(bars)
        println("FIB: armed=$armed invalid=${bad.size}")
        assertTrue(armed > 50, "باید ستاپ کافی مسلح شود (armed=$armed)")
        assertTrue(bad.isEmpty(), "لنگر فیبو در ${bad.size} ستاپ نامعتبر است: ${bad.take(5)}")
    }
}
