package com.alisport.goldpin.core

import java.io.File
import java.util.zip.GZIPInputStream
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * جاروب پارامتر روی **دادهٔ واقعی یک‌سالهٔ موجود در ریپو** (۳۵۱٬۸۹۴ کندل M1 طلا).
 *
 * در اجرای عادی `:engine:test` کاری نمی‌کند؛ فقط با متغیر محیطی `SWEEP_PHASE` اجرا می‌شود:
 *
 *   SWEEP_PHASE=2 SWEEP_OUT=/home/user/sweep ./gradlew :engine:test --tests "*ParamSweepTest*"
 *
 * سنجه‌ها برای هر اجرا:
 *  • `net`      — سود خالص همان‌طور که موتور/کارگزار گزارش می‌دهد (در مود خاموش بدون کارمزد).
 *  • `netCost`  — سود **یکدست**: ناخالص − کارمزد واقعی (cmsn × حجم × اندازهٔ قرارداد) برای همهٔ
 *                 مودها. این عدد ملاک مقایسهٔ مودهاست، چون در مود خاموش کارمزد کسر نمی‌شود (§۵).
 *  • `avgR`     — انتظار بر حسب R؛ `win%`؛ `maxDD`؛ تعداد ماه‌های مثبت.
 */
class ParamSweepTest {

    private companion object {
        fun assetFile(): File? = listOf(
            "datasets/goldpin_xauusd_m1_1y.csv.gz",
            "../datasets/goldpin_xauusd_m1_1y.csv.gz",
            "/home/user/ST2/datasets/goldpin_xauusd_m1_1y.csv.gz"
        ).map(::File).firstOrNull { it.exists() }

        fun loadCandles(): List<Candle> {
            val f = assetFile() ?: error("فایل دادهٔ یک‌ساله پیدا نشد")
            val out = ArrayList<Candle>(360_000)
            GZIPInputStream(f.inputStream().buffered(1 shl 16)).bufferedReader().useLines { lines ->
                var bi = 0
                for (ln in lines) {
                    val p = ln.split(',')
                    if (p.size < 6) continue
                    val t = p[0].trim().toLongOrNull() ?: continue
                    out.add(Candle(bi++, t, p[1].toDouble(), p[2].toDouble(), p[3].toDouble(), p[4].toDouble(), p[5].toDouble()))
                }
            }
            return out
        }
    }

    private data class Cfg(
        val tag: String,
        val strategy: Int,
        val mm: Int,
        val fade: Boolean,
        val cmsn: Double,
        val k: Double,           // trigger2TargetR (۰ = پله‌های فیبو)
        val costMult: Double,    // minSlCostMult (فقط مودهای §۲۰)
        val nMax: Double,        // maxSlUnits (§18-31)
        val minSl: Double,       // minSlUnits (جدید؛ مستقل از مود)
        val tpUnits: Double,     // فقط استراتژی ۲
        val riskPct: Double = 1.0,
        val minTPunits: Double = 10.0,
        val slipTicks: Double = 1.0,
        val slBufTicks: Double = 2.0,
        val entryBufTicks: Double = 1.0,
        val maxSetups: Int = 1,
        val minLotManual: Double = 10.0,
        val mixedGrowthPct: Double = 20.0
    )

    private fun settings(c: Cfg): Settings {
        val mode = TF_MODES.first { it.idx == 1 }
        return Settings().apply {
            tfS = mode.s; tfM = mode.m; tf1 = mode.t1; tf2 = mode.t2
            strategy = c.strategy
            mmMode = c.mm
            trigger2Fade = c.fade
            trigger2TargetR = c.k
            minSlCostMult = c.costMult
            maxSlUnits = c.nMax
            minSlUnits = c.minSl
            tpUnits = c.tpUnits
            minTPunits = c.minTPunits
            riskPct = c.riskPct
            slipTicks = c.slipTicks
            slBufTicks = c.slBufTicks
            entryBufTicks = c.entryBufTicks
            maxSetups = c.maxSetups
            cmsnAuto = false
            cmsnManual = c.cmsn
            minLotAuto = false
            minLotManual = c.minLotManual
            mixedGrowthPct = c.mixedGrowthPct
            initialEquity = 100_000.0
        }
    }

    private class Result(
        val cfg: Cfg,
        val trades: Int,
        val wins: Int,
        val net: Double,
        val netCost: Double,
        val commCost: Double,
        val avgR: Double,
        val avgRisk: Double,
        val pf: Double,
        val avgRCost: Double,
        val maxDD: Double,
        val finalEquity: Double,
        val orders: Int,
        val costBlocked: Int,
        val nBlocked: Int,
        val minBlocked: Int,
        val months: List<Pair<String, Double>>,
        val seconds: Double
    ) {
        val winRate: Double get() = if (trades > 0) wins * 100.0 / trades else 0.0
        val posMonths: Int get() = months.count { it.second > 0 }
    }

    private fun run(c: Cfg, candles: List<Candle>): Result {
        val t0 = System.currentTimeMillis()
        val cfg = settings(c)
        val eng = Engine(cfg)
        val broker = PaperBroker(cfg)
        eng.broker = broker
        broker.engine = eng
        eng.barCommitHook = { cd -> broker.onBar(cd, live = false) }
        var upto = 0
        val step = 25_000
        while (upto < candles.size) {
            val next = minOf(upto + step, candles.size)
            eng.feed(candles.subList(0, next), lastIsClosed = false)
            upto = next
        }
        eng.feed(candles, lastIsClosed = true)

        val closed = broker.trades.filter { !it.open }
        val s = Reports.summary(broker.trades)
        var gross = 0.0
        var comm = 0.0
        var riskSum = 0.0
        var riskDollars = 0.0
        val monthly = LinkedHashMap<String, Double>()
        for (t in closed.sortedBy { it.exitT }) {
            gross += t.grossPnl()
            comm += c.cmsn * t.qty * t.contractSize     // کارمزد واقعی، مستقل از مود مالی
            riskSum += t.risk
            riskDollars += t.risk * t.qty * t.contractSize
            val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
            cal.timeInMillis = t.exitT!!
            val key = "%04d-%02d".format(cal.get(java.util.Calendar.YEAR), cal.get(java.util.Calendar.MONTH) + 1)
            monthly[key] = (monthly[key] ?: 0.0) + (t.grossPnl() - c.cmsn * t.qty * t.contractSize)
        }
        val secs = (System.currentTimeMillis() - t0) / 1000.0
        return Result(
            c, s.trades, s.wins, s.net, gross - comm, comm, s.avgR,
            if (closed.isEmpty()) 0.0 else riskSum / closed.size,
            s.profitFactor,
            if (riskDollars > 0.0) (gross - comm) / riskDollars else 0.0,
            s.maxDD, broker.equity, broker.orders.size,
            eng.cnt.slCostBlocked, eng.cnt.slNBlocked, eng.cnt.slMinBlocked,
            monthly.toList(), secs
        )
    }

    private val CSV_HEADER =
        "tag,strategy,mm,fade,cmsn,k,costMult,nMax,minSl,tpUnits,slBuf,entryBuf,avgRCost,riskPct," +
            "trades,wins,winRate,avgR,avgRisk,net,netCost,commCost,profitFactor,maxDD,finalEquity," +
            "orders,costBlocked,nBlocked,minBlocked,posMonths,months,seconds"

    private fun csvLine(r: Result): String {
        val c = r.cfg
        return listOf(
            c.tag, c.strategy, c.mm, c.fade, c.cmsn, c.k, c.costMult, c.nMax, c.minSl, c.tpUnits,
            c.slBufTicks, c.entryBufTicks, "%.4f".format(r.avgRCost), c.riskPct, r.trades, r.wins, "%.2f".format(r.winRate), "%.4f".format(r.avgR),
            "%.3f".format(r.avgRisk), "%.2f".format(r.net), "%.2f".format(r.netCost),
            "%.2f".format(r.commCost), "%.3f".format(r.pf), "%.2f".format(r.maxDD),
            "%.2f".format(r.finalEquity), r.orders, r.costBlocked, r.nBlocked, r.minBlocked,
            r.posMonths,
            r.months.joinToString("|") { "${it.first}:${it.second.roundToInt()}" },
            "%.1f".format(r.seconds)
        ).joinToString(",")
    }

    private fun report(rs: List<Result>, outDir: String, phase: String) {
        println("SWEEP_REPORT ${File(outDir, "sweep_phase$phase.csv").absolutePath}")
        println(
            "%-30s %-3s %-14s %-5s %-4s %-4s %-4s %-4s %-5s %-6s %-9s %-9s %-7s %-6s %-8s %-5s %s".format(
                "tag", "st", "mm", "fade", "k", "tp", "cMu", "min", "N", "trd", "net", "netCost",
                "avgR", "win%", "maxDD", "ord", "mo+"
            )
        )
        for (r in rs.sortedByDescending { it.netCost }) {
            println(
                "%-30s %-3d %-14s %-5s %-4s %-4s %-4s %-4s %-5s %-6d %-9.0f %-9.0f %-7.3f %-6.1f %-8.0f %-5d %d".format(
                    r.cfg.tag, r.cfg.strategy, MoneyMode.label(r.cfg.mm), r.cfg.fade, r.cfg.k, r.cfg.tpUnits,
                    r.cfg.costMult, r.cfg.minSl, r.cfg.nMax, r.trades, r.net, r.netCost, r.avgR, r.winRate,
                    r.maxDD, r.orders, r.posMonths
                )
            )
        }
    }

    @Test
    fun `sweep parameters on the real one-year data`() {
        val phase = System.getenv("SWEEP_PHASE") ?: ""
        assumeTrue(phase.isNotEmpty(), "فقط با SWEEP_PHASE اجرا می‌شود")
        val outDir = System.getenv("SWEEP_OUT") ?: "/tmp/sweep"
        val candles = loadCandles()
        println("SWEEP بارگذاری شد: ${candles.size} کندل")

        val combos = ArrayList<Cfg>()
        fun add(
            strategy: Int, mm: Int, fade: Boolean, k: Double, costMult: Double, minSl: Double, nMax: Double,
            tpUnits: Double = 10.0, cmsn: Double = 0.7, slBuf: Double = 2.0, entryBuf: Double = 1.0
        ) {
            val mmTag = if (mm == MoneyMode.NONE) "off" else "mix"
            val dirTag = if (fade) "fade" else "doc5"
            val tgt = if (strategy == Strategy.NO_TREND) "tp${tpUnits.toInt()}" else "k${k.toInt()}"
            combos.add(
                Cfg(
                    "s$strategy-$mmTag-$dirTag-$tgt-min$minSl-N${nMax.toInt()}-c$costMult-b${slBuf.toInt()}",
                    strategy, mm, fade, cmsn, k, costMult, nMax, minSl, tpUnits,
                    slBufTicks = slBuf, entryBufTicks = entryBuf
                )
            )
        }

        when (phase) {
            "0" -> add(Strategy.DOC, MoneyMode.MIXED, true, 4.0, 0.0, 0.0, 3.0)

            // وضعیت فعلی اپ با کارمزد ۰٫۷
            "1" -> for (s in listOf(Strategy.DOC, Strategy.NO_TREND))
                for (mm in listOf(MoneyMode.NONE, MoneyMode.MIXED))
                    for (fade in listOf(true, false))
                        add(s, mm, fade, 4.0, 6.0, 0.0, 3.0)

            // شبکهٔ اصلی
            "2" -> for (fade in listOf(true, false)) {
                // ── استراتژی ۱ ──
                for (k in listOf(3.0, 4.0, 5.0, 6.0)) {
                    add(Strategy.DOC, MoneyMode.NONE, fade, k, 0.0, 0.0, 3.0)
                    add(Strategy.DOC, MoneyMode.NONE, fade, k, 0.0, 2.0, 3.0)
                    add(Strategy.DOC, MoneyMode.NONE, fade, k, 0.0, 2.5, 6.0)
                    add(Strategy.DOC, MoneyMode.MIXED, fade, k, 0.0, 0.0, 3.0)
                    add(Strategy.DOC, MoneyMode.MIXED, fade, k, 0.0, 2.5, 6.0)
                }
                // ── استراتژی ۲ ──
                for (tp in listOf(6.0, 10.0, 14.0, 20.0)) {
                    add(Strategy.NO_TREND, MoneyMode.NONE, fade, 4.0, 0.0, 0.0, 3.0, tp)
                    add(Strategy.NO_TREND, MoneyMode.NONE, fade, 4.0, 0.0, 2.0, 3.0, tp)
                    add(Strategy.NO_TREND, MoneyMode.NONE, fade, 4.0, 0.0, 2.5, 6.0, tp)
                    add(Strategy.NO_TREND, MoneyMode.MIXED, fade, 4.0, 0.0, 0.0, 3.0, tp)
                    add(Strategy.NO_TREND, MoneyMode.MIXED, fade, 4.0, 0.0, 2.5, 6.0, tp)
                }
            }

            // فاز ۳: ریشهٔ مشکل = حد ضرر داخل نویز (۱٫۸ کندل). اهرم: بافر حد ضرر (تیک).
            "3" -> for (fade in listOf(true, false))
                for (k in listOf(0.0, 4.0))
                    for (b in listOf(2.0, 100.0, 200.0, 300.0)) {
                        // سقف N باید از فاصلهٔ جدید حد ضرر بزرگ‌تر باشد وگرنه همه رد می‌شوند
                        val n = if (b <= 2.0) 3.0 else 10.0
                        add(Strategy.DOC, MoneyMode.MIXED, fade, k, 0.0, 0.0, n, 10.0, 0.7, b)
                    }

            // فاز ۴: پالایش حول برندهٔ فاز ۳ (بافر حد ضرر بزرگ). فقط مود Mixed —
            // توالی معاملات در دو مود یکی است (فقط حجم فرق می‌کند)، پس netCost قابل تعمیم است.
            "4" -> {
                for (k in listOf(3.0, 4.0, 5.0))
                    for (b in listOf(150.0, 200.0, 250.0))
                        for (eb in listOf(1.0, -2.0))
                            add(Strategy.DOC, MoneyMode.MIXED, false, k, 0.0, 0.0, 10.0, 10.0, 0.7, b, eb)
                for (k in listOf(3.0, 4.0, 5.0))
                    for (b in listOf(200.0, 250.0))
                        add(Strategy.DOC, MoneyMode.MIXED, true, k, 0.0, 0.0, 10.0, 10.0, 0.7, b)
                for (tp in listOf(6.0, 10.0, 16.0))
                    for (b in listOf(150.0, 200.0))
                        add(Strategy.NO_TREND, MoneyMode.MIXED, false, 4.0, 0.0, 0.0, 10.0, tp, 0.7, b)
                for (tp in listOf(10.0, 16.0))
                    add(Strategy.NO_TREND, MoneyMode.MIXED, true, 4.0, 0.0, 0.0, 10.0, tp, 0.7, 200.0)
            }

            // فاز ۵: تأیید و پالایش نهایی برنده‌ها (با ستون‌های slBuf/entryBuf در گزارش)
            "5" -> {
                for (k in listOf(4.0, 5.0, 6.0))
                    for (b in listOf(175.0, 200.0, 225.0, 250.0))
                        for (eb in listOf(1.0, -2.0))
                            add(Strategy.DOC, MoneyMode.MIXED, false, k, 0.0, 0.0, 10.0, 10.0, 0.7, b, eb)
                for (k in listOf(4.0, 5.0))
                    for (b in listOf(200.0, 250.0))
                        add(Strategy.DOC, MoneyMode.MIXED, true, k, 0.0, 0.0, 10.0, 10.0, 0.7, b)
                // استراتژی ۲: فقط با k=۰ است که «حد سود واحدی» واقعاً اثر می‌کند
                for (tp in listOf(6.0, 10.0, 16.0, 25.0))
                    for (b in listOf(200.0, 250.0))
                        add(Strategy.NO_TREND, MoneyMode.MIXED, false, 0.0, 0.0, 0.0, 10.0, tp, 0.7, b)
                for (k in listOf(4.0, 5.0))
                    add(Strategy.NO_TREND, MoneyMode.MIXED, false, k, 0.0, 0.0, 10.0, 10.0, 0.7, 200.0)
                for (tp in listOf(10.0, 16.0))
                    add(Strategy.NO_TREND, MoneyMode.MIXED, true, 0.0, 0.0, 0.0, 10.0, tp, 0.7, 200.0)
                add(Strategy.NO_TREND, MoneyMode.MIXED, true, 4.0, 0.0, 0.0, 10.0, 10.0, 0.7, 200.0)
            }

            // فاز ۶: یافتن قلهٔ k و بررسی مود معکوس/استراتژی ۲ با هدف واحدی بزرگ‌تر
            "6" -> {
                for (k in listOf(6.0, 7.0, 8.0))
                    for (b in listOf(175.0, 200.0, 225.0))
                        add(Strategy.DOC, MoneyMode.MIXED, false, k, 0.0, 0.0, 10.0, 10.0, 0.7, b, -2.0)
                for (k in listOf(6.0, 8.0))
                    for (b in listOf(175.0, 250.0))
                        add(Strategy.DOC, MoneyMode.MIXED, true, k, 0.0, 0.0, 10.0, 10.0, 0.7, b, -2.0)
                for (tp in listOf(25.0, 35.0, 50.0))
                    add(Strategy.NO_TREND, MoneyMode.MIXED, false, 0.0, 0.0, 0.0, 10.0, tp, 0.7, 250.0, 1.0)
                for (b in listOf(175.0, 200.0))
                    add(Strategy.NO_TREND, MoneyMode.MIXED, false, 0.0, 0.0, 0.0, 10.0, 35.0, 0.7, b, 1.0)
            }

            // فاز ۷: عدد نهایی همان پیش‌تنظیم‌هایی که در اپ دکمه دارند (+ مود خاموش + پیش‌فرض سند ۷)
            "7" -> {
                // ۱ بهینهٔ سود · ۲ بهینهٔ کم‌افت · ۳ همان‌ها در مود خاموش · ۴ با فیلتر کارمزد تطبیق
                add(Strategy.DOC, MoneyMode.MIXED, false, 7.0, 0.0, 0.0, 10.0, 10.0, 0.7, 200.0, -2.0)
                add(Strategy.DOC, MoneyMode.MIXED, false, 6.0, 0.0, 0.0, 10.0, 10.0, 0.7, 175.0, -2.0)
                add(Strategy.DOC, MoneyMode.NONE, false, 7.0, 0.0, 0.0, 10.0, 10.0, 0.7, 200.0, -2.0)
                add(Strategy.DOC, MoneyMode.NONE, false, 6.0, 0.0, 0.0, 10.0, 10.0, 0.7, 175.0, -2.0)
                add(Strategy.DOC, MoneyMode.MIXED, false, 7.0, 4.0, 2.8, 10.0, 10.0, 0.7, 200.0, -2.0)
                // ۵ تریگر ۲ معکوس (بهترین یافت‌شده) · ۶ استراتژی ۲ (بهترین یافت‌شده)
                add(Strategy.DOC, MoneyMode.MIXED, true, 8.0, 0.0, 0.0, 10.0, 10.0, 0.7, 175.0, -2.0)
                add(Strategy.NO_TREND, MoneyMode.MIXED, false, 0.0, 0.0, 0.0, 10.0, 35.0, 0.7, 250.0, 1.0)
                // ۷ پیش‌فرض سند ۷ با کارمزد ۰٫۷ (باید صفر سفارش بدهد)
                add(Strategy.DOC, MoneyMode.MIXED, true, 4.0, 6.0, 0.0, 3.0, 10.0, 0.7, 2.0, 1.0)
            }

            else -> error("فاز ناشناخته: $phase")
        }

        println("SWEEP_PHASE=$phase ترکیب‌ها=${combos.size}")
        val dir = File(outDir).also { it.mkdirs() }
        val csv = File(dir, "sweep_phase$phase.csv")
        val results = ArrayList<Result>(combos.size)
        csv.bufferedWriter().use { w ->
            w.write(CSV_HEADER); w.newLine()
            for ((i, c) in combos.withIndex()) {
                val r = run(c, candles)
                results.add(r)
                w.write(csvLine(r)); w.newLine(); w.flush()
                println(
                    "SWEEP_PROGRESS ${i + 1}/${combos.size} ${c.tag} trades=${r.trades} net=%.0f netCost=%.0f secs=%.1f"
                        .format(r.net, r.netCost, r.seconds)
                )
            }
        }
        report(results, outDir, phase)
    }
}
