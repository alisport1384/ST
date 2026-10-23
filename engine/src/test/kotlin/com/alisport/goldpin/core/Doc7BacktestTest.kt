package com.alisport.goldpin.core

import java.io.File
import kotlin.test.Test

/**
 * سنجش «نسخهٔ ۷» با **پیش‌فرض‌های خود اپ** (همان کدی که در APK اجرا می‌شود).
 *
 * سه حالت روی سه پنجرهٔ ۱۲۰ روزه:
 *  ۱) `doc5-fib`  — جهت ساختار + هدف‌های فیبوناچی (فیبوی اصلاح‌شده)
 *  ۲) `v7-default` — معکوس + هدف ۴R + فیلتر کارمزد ۶ + کارمزد ۰٫۲۰ (پیش‌فرض نسخهٔ ۷)
 *  ۳) `v7-nofade`  — جهت ساختار + هدف ۴R + فیلتر کارمزد ۶ + کارمزد ۰٫۲۰
 *
 * داده: `DOC7_DIR` (پیش‌فرض `/home/user/data`). نبودِ داده ⇒ رد شدن تست.
 */
class Doc7BacktestTest {

    private data class Res(val n: Int, val wr: Double, val exp: Double, val equity: Double, val dd: Double)

    private fun load(path: File): List<Candle> {
        val out = ArrayList<Candle>(400_000)
        var bi = 0
        path.forEachLine { ln ->
            val s = ln.trim()
            if (s.isEmpty() || s.startsWith("time")) return@forEachLine
            val p = s.split(',')
            if (p.size < 6) return@forEachLine
            try {
                out.add(Candle(bi++, p[0].toLong(), p[1].toDouble(), p[2].toDouble(),
                    p[3].toDouble(), p[4].toDouble(), p[5].toDouble()))
            } catch (_: Exception) { }
        }
        return out
    }

    /** [fade] حالت معکوس · [k] هدف بر حسب R (۰ = فیبوی سند) · [costMult] فیلتر کارمزد · [cost] هزینهٔ هر واحد */
    private fun run(candles: List<Candle>, fade: Boolean, k: Double, costMult: Double, cost: Double): Res {
        // پیش‌فرض‌های نسخهٔ ۷ دست‌نخورده می‌مانند؛ فقط همین چهار مورد آزاد است.
        val cfg = Settings().apply {
            vpRows = 24; vpSmooth = 0; distMode = VolumeProfile.DIST_UNIFORM
            tfMode = 1; tfS = Tf.H1; tfM = Tf.M15; tf1 = Tf.M5; tf2 = Tf.M1
            riskPct = 1.0; initialEquity = 100_000.0; contractSize = 1.0
            strategy = Strategy.DOC
            hvScanFirstTouch = true
            trigger2Fade = fade; trigger2TargetR = k; minSlCostMult = costMult
            cmsnManual = cost; cmsnAuto = false
            mmMode = if (cost > 0.0) MoneyMode.MIXED else MoneyMode.NONE
        }
        val e = Engine(cfg)
        val b = PaperBroker(cfg)
        e.broker = b; b.engine = e
        e.barCommitHook = { cd -> b.onBar(cd, live = false) }
        e.runAll(candles)

        val closed = b.trades.filter { !it.open }
        if (closed.isEmpty()) return Res(0, 0.0, 0.0, 1.0, 0.0)
        val rs = closed.map { t ->
            val riskUsd = t.risk * t.qty * maxOf(t.contractSize, 1e-9)
            t.pnl() / maxOf(riskUsd, 1e-9)
        }
        val wr = closed.count { it.pnl() > 0 } * 100.0 / closed.size
        val eqs = Reports.equityCurve(b.trades, cfg.initialEquity).map { it.second }
            .ifEmpty { listOf(cfg.initialEquity) }
        val peak = ArrayList<Double>(eqs.size); var mx = 0.0
        for (q in eqs) { mx = maxOf(mx, q); peak.add(mx) }
        val dd = eqs.indices.maxOf { (peak[it] - eqs[it]) / peak[it] * 100.0 }
        return Res(closed.size, wr, rs.average(), eqs.last() / cfg.initialEquity, dd)
    }

    @Test
    fun `version seven defaults on three windows`() {
        val dir = File(System.getenv("DOC7_DIR") ?: "/home/user/data")
        val wins = listOf("C" to "xau_m1_c.csv", "B" to "xau_m1_b.csv", "A" to "xau_m1.csv")
            .map { (n, f) -> n to File(dir, f) }.filter { it.second.exists() }
        if (wins.isEmpty()) { println("DOC7: داده موجود نیست — skip"); return }

        val modes = listOf(
            Triple("doc5-fib", false, 0.0) to 0.0,
            Triple("v7-fade4R", true, 4.0) to 0.20,
            Triple("v7-nofade4R", false, 4.0) to 0.20
        )
        val sb = StringBuilder("V7 defaults (N=10, Fib anchor fixed)\n")
        sb.append(String.format("%-3s %-12s %4s %7s %8s %8s %7s%n", "win", "mode", "n", "WR%", "expR", "equity", "DD%"))
        val agg = LinkedHashMap<String, MutableList<Res>>()
        for ((name, f) in wins) {
            val c = load(f)
            for ((mode, cost) in modes) {
                val (label, fade, k) = mode
                val r = run(c, fade, k, if (cost > 0) 6.0 else 0.0, cost)
                agg.getOrPut(label) { mutableListOf() }.add(r)
                sb.append(String.format("%-3s %-12s %4d %7.1f %+8.3f %7.2fx %6.1f%n",
                    name, label, r.n, r.wr, r.exp, r.equity, r.dd))
            }
        }
        sb.append("\n— جمع —\n")
        for ((label, rs) in agg) {
            val n = rs.sumOf { it.n }
            val exp = if (n > 0) rs.sumOf { it.exp * it.n } / n else 0.0
            val wr = if (n > 0) rs.sumOf { it.wr * it.n } / n else 0.0
            sb.append(String.format("%-12s n=%4d WR=%5.1f%% expR=%+8.3f%n", label, n, wr, exp))
        }
        println(sb)
        File(System.getenv("DOC7_OUT") ?: "doc7_engine.txt").writeText(sb.toString())
    }
}
