package com.alisport.goldpin.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * §۴-۸ سند — «شروع اسکن کندل حجم زیاد ... از **اولین برگشت و برخورد یا ورود قیمت به باکس حجم کم**».
 *
 * نقص بازگردانی‌شده: تابع اسکن پس از تایید LV، پرچم «بازگشت به باکس» (`lvReEntered`) را همان لحظه
 * true می‌کرد؛ پس اسکن از لحظهٔ تایید LV آغاز می‌شد، «اولین برگشت» هرگز سنجیده نمی‌شد و کلید
 * تنظیمات `hvScanFirstTouch` هیچ اثری نداشت. در گونهٔ B سناریو (حجم کندل ۱۴:۰۵ برابر ۲۰۰) نتیجه
 * این بود: مرحله ۳→۵ در ۱۴:۰۵ (کندل ولوم زیادِ نامعتبر مارک و بعد دور ریخته شد) و سپس ۵→۴→۵.
 *
 * این تست همان سناریوی عددی را با موتور واقعی اجرا می‌کند و ترتیب صحیح را قفل می‌کند.
 */
class HvScanStartTest {

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

    private fun engine(): Engine {
        val cfg = Settings().apply {
            vpRows = 24; vpSmooth = 0; distMode = VolumeProfile.DIST_UNIFORM
            tfMode = 1; tfS = Tf.H1; tfM = Tf.M15; tf1 = Tf.M5; tf2 = Tf.M1
            mintick = 0.01; slBufTicks = 2.0; entryBufTicks = 1.0; minTPunits = 10.0
            maxSlUnits = 3.0; maxLeverage = 500.0; riskPct = 1.0; initialEquity = 100000.0
            contractSize = 1.0; strategy = Strategy.DOC; mmMode = MoneyMode.NONE
            hvScanFirstTouch = true   // پیش‌فرض سند
            trigger2Fade = false      // این تست قاعدهٔ سند ۵ (جهت ساختار) را می‌سنجد
            trigger2TargetR = 0.0     // هدف‌های فیبوناچی سند ۵
        }
        val e = Engine(cfg)
        val b = PaperBroker(cfg)
        e.broker = b; b.engine = e
        return e
    }

    @Test
    fun `doc 4-8 no high volume candle is scanned before the price returns to the low volume box`() {
        val csv = System.getenv("SCEN_PROBE_CSV") ?: "../scenario/m1_probeB.csv"
        val candles = load(csv)
        val e = engine()
        // موتور تحویل کندل به کارگزار را خودش انجام نمی‌دهد؛ پوستهٔ برنامه (و این هارنس) با
        // barCommitHook آن را صدا می‌زند.
        val hhmm = { t: Long ->
            val dt = java.time.Instant.ofEpochMilli(t).atZone(java.time.ZoneOffset.UTC)
            "%02d:%02d".format(dt.hour, dt.minute)
        }
        val stageAt = HashMap<String, Int>()
        val hvAt = HashMap<String, Double>()
        e.barCommitHook = { cd ->
            val st = e.setups.firstOrNull { it.lvH == 2013.15 }
            if (st != null) { stageAt[hhmm(cd.t)] = st.stage; hvAt[hhmm(cd.t)] = st.hvH }
            e.broker!!.onBar(cd, live = false)
        }
        val t1405 = candles.first { hhmm(it.t) == "14:05" }.t
        val t1411 = candles.first { hhmm(it.t) == "14:11" }.t
        val minVol1405 = candles.first { it.t == t1405 }

        e.runAll(candles)
        val s = e.setups.first { it.lvH == 2013.15 }

        // گونهٔ B: حجم کندل ۱۴:۰۵ (۲۰۰) بیشتر از کندل قبلی (۶۴) است، ولی قیمت هنوز به باکس
        // ولوم کم برنگشته؛ طبق §۴-۸ این کندل نباید اسکن/مارک شود.
        assertEquals(200.0, minVol1405.v, 1e-9, "پیش‌شرط گونهٔ B: حجم ۱۴:۰۵ = ۲۰۰")
        // نقص پیشین: در ۱۴:۰۵ مرحله ۳→۵ می‌شد و باکس HV نامعتبر [2013.20..2013.42] مارک می‌شد.
        for (m in listOf("14:05", "14:06", "14:07", "14:08", "14:09", "14:10")) {
            assertEquals(4, stageAt[m], "در $m ستاپ باید در مرحلهٔ ۴ باشد؛ پیش از اولین برگشت، ولوم زیاد اسکن نمی‌شود (§۴-۸)")
        }
        assertTrue((hvAt["14:05"] ?: 0.0).let { it.isNaN() || it < 2013.3 },
            "هیچ باکس ولوم زیادی نباید در ۱۴:۰۵ مارک شود (نقص پیشین: [2013.20..2013.42])")
        assertEquals(t1411, s.hvT, "کندل ولوم زیاد باید ۱۴:۱۱ باشد؛ اولین افزایش حجم پس از اولین برگشت به باکس")
        assertEquals(2013.20, s.hvH, 1e-9)
        assertEquals(2012.70, s.hvL, 1e-9)

        val ord = e.broker!!.orders.first()
        assertEquals(2013.21, ord.price, 1e-9, "سطح سفارش = سقف باکس HV + ۱ تیک")
        assertEquals(2012.68, ord.sl, 1e-9, "حد ضرر = کف باکس HV − ۲ تیک")
        assertTrue(e.broker!!.trades.isNotEmpty(), "معامله باید همان‌طور که سند می‌گوید پر شود")
    }
}
