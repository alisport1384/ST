package com.alisport.goldpin.core

import java.io.File
import kotlin.test.Test

/**
 * استخراج «همهٔ سفارش‌هایی که موتور روی دادهٔ واقعی مسلح کرده» برای آزمایشگاه ورود تریگر ۲.
 * خروجی CSV: هر سفارش مسلح + سطوح ستاپ + نتیجهٔ واقعی کارگزار.
 * این فایل فقط داده تولید می‌کند و هیچ ادعایی دربارهٔ درستی ندارد؛ سنجش در `entry_lab.py`.
 */
class EntryLabDumpTest {

    @Test
    fun dumpArmedSetups() {
        val csv = System.getenv("LAB_CSV") ?: "../scenario/xau_m1.csv"
        val outFile = File(System.getenv("LAB_OUT") ?: "../scenario/lab_setups.csv")
        val f = File(csv)
        if (!f.exists()) {
            // دادهٔ واقعی در مخزن نیست (حجیم است)؛ آزمایشگاه با LAB_CSV اجرا می‌شود.
            println("LAB: فایل $csv موجود نیست — تست رد شد (skip)")
            return
        }
        val bars = ArrayList<Candle>()
        var bi = 0
        f.forEachLine { ln ->
            val s = ln.trim()
            if (s.isEmpty() || s.startsWith("time")) return@forEachLine
            val p = s.split(',')
            if (p.size < 6) return@forEachLine
            bars.add(Candle(bi++, p[0].toLong(), p[1].toDouble(), p[2].toDouble(), p[3].toDouble(), p[4].toDouble(), p[5].toDouble()))
        }

        val cfg = Settings().apply {
            vpRows = 24; vpSmooth = 0; distMode = VolumeProfile.DIST_UNIFORM
            tfMode = 1; tfS = Tf.H1; tfM = Tf.M15; tf1 = Tf.M5; tf2 = Tf.M1
            mintick = 0.01; slBufTicks = 2.0; entryBufTicks = 1.0; minTPunits = 10.0
            maxSlUnits = 3.0; maxLeverage = 500.0; riskPct = 1.0; initialEquity = 100_000.0
            contractSize = 1.0; strategy = Strategy.DOC; mmMode = MoneyMode.NONE
            hvScanFirstTouch = true
        }
        val eng = Engine(cfg)
        val broker = PaperBroker(cfg)
        eng.broker = broker
        broker.engine = eng
        // ستاپ‌ها بعد از بسته شدن معامله از حافظه پاک می‌شوند؛ پس سطوح را همان لحظهٔ «مسلح شدن» ثبت می‌کنیم.
        data class Rec(val setupId: Long, val dir: Int, val armBi: Int, val armT: Long, val entry: Double,
                       val sl: Double, val tp1: Double, val tp2: Double, val tpX: Double,
                       val hvH: Double, val hvL: Double, val lvH: Double, val lvL: Double,
                       val mid: Double, val zTop: Double, val zBot: Double,
                       val fibA0: Double, val highBE: Double, val lowBU: Double)
        val recs = LinkedHashMap<Long, Rec>()
        eng.barCommitHook = { cd ->
            broker.onBar(cd, live = false)
            for (s in eng.setups) {
                if (s.stage >= 6 && s.id !in recs && !s.entry.isNaN()) {
                    recs[s.id] = Rec(s.id, s.dir, cd.bi, s.armT, s.entry, s.sl, s.tp1, s.tp2, s.tpx,
                        s.hvH, s.hvL, s.lvH, s.lvL, s.midRef, s.zTop, s.zBot,
                        eng.fibAnchor(s.dir == 1), eng.highestBE, eng.lowestBUpin)
                }
            }
        }
        eng.runAll(bars)

        val sb = StringBuilder()
        sb.appendLine("setupId,dir,armT,armBi,entry,sl,tp1,tp2,tpX,hvTop,hvBot,lvTop,lvBot,midRef,zTop,zBot,orderStatus,filledBi,tradePnl,tradeR,fibA0,highBE,lowBU")
        fun d(v: Double) = if (v.isNaN()) "" else String.format("%.4f", v)
        for (r in recs.values) {
            val o = broker.orders.lastOrNull { it.setupId == r.setupId }
            val tr = broker.trades.lastOrNull { it.setupId == r.setupId }
            sb.appendLine(
                listOf(
                    r.setupId.toString(), r.dir.toString(), r.armT.toString(), r.armBi.toString(),
                    d(r.entry), d(r.sl), d(r.tp1), d(r.tp2), d(r.tpX),
                    d(r.hvH), d(r.hvL), d(r.lvH), d(r.lvL), d(r.mid), d(r.zTop), d(r.zBot),
                    (o?.status ?: -1).toString(), (o?.filledBi ?: "").toString(),
                    tr?.let { String.format("%.2f", it.pnl()) } ?: "", tr?.let { String.format("%.4f", it.rMultiple()) } ?: "",
                    d(r.fibA0), d(r.highBE), d(r.lowBU)
                ).joinToString(",")
            )
        }
        outFile.writeText(sb.toString(), Charsets.UTF_8)
        println("LAB: bars=${bars.size} armed=${recs.size} orders=${broker.orders.size} trades=${broker.trades.size} → ${outFile.path}")
    }
}
