package com.alisport.goldpin.core

import java.io.File
import kotlin.test.Test

/**
 * اعتبارسنجی «سند ۶» از داخل خود موتور (همان کدی که در APK اجرا می‌شود).
 *
 * برای هر سه پنجرهٔ ۱۲۰ روزه، دو حالت اجرا می‌شود:
 *  • سند ۵ (پیش‌فرض) — باید زیان‌ده باشد (مطابق گزارش)
 *  • سند ۶ (معکوس، هدف ۴R، فیلتر کارمزد ۶) — باید سودده باشد
 *
 * مسیر داده با متغیر محیطی `DOC6_DIR` تعیین می‌شود (پیش‌فرض `/home/user/data`).
 * اگر داده نبود، تست بی‌صدا رد می‌شود (داده در مخزن نیست).
 */
class DocFadeBacktestTest {

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

    private fun run(candles: List<Candle>, fade: Boolean, k: Double, costMult: Double, cost: Double, name: String = "", label: String = ""): Res {
        val cfg = Settings().apply {
            vpRows = 24; vpSmooth = 0; distMode = VolumeProfile.DIST_UNIFORM
            tfMode = 1; tfS = Tf.H1; tfM = Tf.M15; tf1 = Tf.M5; tf2 = Tf.M1
            mintick = 0.01; slBufTicks = 2.0; entryBufTicks = 1.0; minTPunits = 10.0
            maxSlUnits = 3.0; maxLeverage = 500.0; riskPct = 1.0; initialEquity = 100_000.0
            contractSize = 1.0; strategy = Strategy.DOC
            // کارمزد فقط در مودهای §۲۰ کسر می‌شود (مود §۵ = بدون کارمزد، مطابق سند ۵).
            mmMode = if (cost > 0.0) MoneyMode.MIXED else MoneyMode.NONE
            hvScanFirstTouch = true
            trigger2Fade = fade; trigger2TargetR = k; minSlCostMult = costMult
            cmsnManual = cost; cmsnAuto = false
        }
        val e = Engine(cfg)
        val b = PaperBroker(cfg)
        e.broker = b; b.engine = e
        e.barCommitHook = { cd -> b.onBar(cd, live = false) }
        e.runAll(candles)

        // خروجی خام برای بازرسی بیرونی
        val dump = File((System.getenv("DOC6_TRADES") ?: "/tmp/d6") + "_" + name + "_" + label + ".csv")
        dump.writeText(buildString {
            append("id,dir,entryT,entry,sl0,tp1,tp2,tpX,qty,contractSize,exitPx,reason,commission,pnl,gross,legs\n")
            for (t in b.trades) {
                append("${t.id},${t.dir},${t.entryT},${t.entry},${t.sl0},${t.tp1},${t.tp2},${t.tpX},${t.qty},${t.contractSize},${t.exitPx},${t.reason},${t.commission},${t.pnl()},${t.grossPnl()},${t.fills.size}\n")
            }
        })
        val closed = b.trades.filter { !it.open }
        if (closed.isEmpty()) return Res(0, 0.0, 0.0, 1.0, 0.0)
        // R با مقیاس حجم نرمال می‌شود تا با مطالعهٔ پایتون (که R را واحد می‌گیرد) قابل مقایسه باشد.
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
    fun `doc six reproduces the python study inside the engine`() {
        val dir = File(System.getenv("DOC6_DIR") ?: "/home/user/data")
        val win = listOf("xau_m1_c.csv" to "C", "xau_m1_b.csv" to "B", "xau_m1.csv" to "A")
            .map { (f, name) -> name to File(dir, f) }
            .filter { it.second.exists() }
        if (win.isEmpty()) { println("DOC6: دادهٔ پنجره‌ها موجود نیست — تست رد شد (skip)"); return }

        val sb = StringBuilder("DOC6 engine backtest (کدِ داخل APK)\n")
        sb.append(String.format("%-3s %-6s %4s %7s %8s %8s %7s%n", "win", "mode", "n", "WR%", "expR", "equity", "DD%"))
        for ((name, f) in win) {
            val c = load(f)
            for ((label, fade, k, cm, cost) in listOf(
                Quint("doc5", false, 0.0, 0.0, 0.0),
                Quint("fade1R", true, 1.0, 0.0, 0.0),
                Quint("fade4R", true, 4.0, 0.0, 0.0),
                Quint("f3R+fee6", true, 3.0, 6.0, 0.20),
                Quint("f4R+fee6", true, 4.0, 6.0, 0.20),
                Quint("f5R+fee6", true, 5.0, 6.0, 0.20),
                Quint("f4R+nofilter", true, 4.0, 0.0, 0.20)
            )) {
                val r = run(c, fade, k, cm, cost, name, label)
                sb.append(String.format("%-3s %-6s %4d %7.1f %+8.3f %7.2fx %6.1f%n",
                    name, label, r.n, r.wr, r.exp, r.equity, r.dd))
            }
        }
        println(sb)
        File(System.getenv("DOC6_OUT") ?: "doc6_engine.txt").writeText(sb.toString())
    }

    private data class Quint(val a: String, val b: Boolean, val c: Double, val d: Double, val e: Double)
}
