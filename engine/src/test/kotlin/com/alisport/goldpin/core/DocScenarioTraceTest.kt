package com.alisport.goldpin.core

import java.io.File
import kotlin.test.Test

private const val MON = 1792368000L

/**
 * سناریوی عددی کنترل‌شده — اجرای موتور واقعی روی `scenario/m1.csv` و چاپ ردپای کامل.
 * خروجی در `scenario/code_report.txt` نوشته می‌شود تا با گزارش «سند» مقایسه شود.
 */
class DocScenarioTraceTest {

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

    private fun f2(v: Double) = if (v.isNaN()) "NaN" else String.format("%.2f", v)
    private fun f4(v: Double) = if (v.isNaN()) "NaN" else String.format("%.4f", v)

    @Test
    fun traceScenario() {
        val csv = System.getenv("SCEN_CSV") ?: System.getProperty("scenario.csv") ?: "../scenario/m1.csv"
        val outFile = File(System.getenv("SCEN_OUT") ?: System.getProperty("scenario.out") ?: "../scenario/code_report.txt")
        val candles = load(csv)
        val sb = StringBuilder()
        fun w(s: String) { sb.appendLine(s) }

        val cfg = Settings().apply {
            vpRows = 24; vpSmooth = 0; distMode = VolumeProfile.DIST_UNIFORM
            tfMode = 1; tfS = Tf.H1; tfM = Tf.M15; tf1 = Tf.M5; tf2 = Tf.M1
            mintick = 0.01; slBufTicks = 2.0; entryBufTicks = 1.0; minTPunits = 10.0
            maxSlUnits = 3.0; maxLeverage = 500.0; riskPct = 1.0; initialEquity = 100000.0
            contractSize = 1.0; strategy = Strategy.DOC; mmMode = MoneyMode.NONE
        }
        val eng = Engine(cfg)
        val broker = PaperBroker(cfg)
        eng.broker = broker
        broker.engine = eng

        w("═══ گزارش کد (موتور GoldPin) روی سناریوی کنترل‌شده ═══")
        w("کندل‌های M1: ${candles.size} · ساختار H1 · میانی M15 · تریگر۱ M5 · تریگر۲ M1")
        w("mintick=0.01 slBuf=2tick entryBuf=1tick N=3 leverage=500 risk=1% equity=100000 contract=1")
        w("")

        val logs = ArrayList<String>()
        eng.logSink = { cat, msg, data -> logs.add("[$cat] $msg" + (data?.let { " | $it" } ?: "")) }
        broker.logSink = { cat, msg, data -> logs.add("[$cat/B] $msg" + (data?.let { " | $it" } ?: "")) }

        val lastStage = HashMap<Long, Int>()
        val lastTrend = intArrayOf(-99)
        val lastHH = doubleArrayOf(-1.0)

        fun hhmm(t: Long): String {
            val sod = ((t / 1000) % 86400).toInt()
            return String.format("%02d:%02d", sod / 3600, (sod % 3600) / 60)
        }

        var lastHC = -1L
        eng.barCommitHook = { cd ->
            // روند و پین‌های ساختاری فقط در پایان هر کندل H1
            val hb = Tf.bucket(Tf.H1, cd.t)
            if (hb != lastHC) {
                lastHC = hb
                w("ساختار @ ${hhmm(cd.t)} · روند=${eng.trend} · HH=${f2(eng.HH)} HL=${f2(eng.HL)} LL=${f2(eng.LL)} LH=${f2(eng.LH)} " +
                    "· highestBE=${f2(eng.highestBE)} lowestBUpin=${f2(eng.lowestBUpin)} · pinBU=${f2(eng.lastBUpin)}(act=${eng.buActive}) pinBE=${f2(eng.lastBEpin)}(act=${eng.beActive})")
                val pr = eng.profileOnLastS
                if (pr != null && hb == Tf.bucket(Tf.H1, (MON + 9 * 3600 + 0) * 1000L - 1)) {
                    w("   پروفایل کندل ۰۸:۰۰ · lo=${f4(pr.lo)} step=${f4(pr.step)} rows=${pr.rows} · poc=${f4(pr.poc)}")
                    w("   ردیف‌ها (کف→سقف): " + (0 until pr.rows).joinToString(" ") { f2(pr.vol[it]) })
                }
            }
            // تغییر مرحلهٔ ستاپ‌ها در کندل تریگر۲
            for (s in eng.setups) {
                val prev = lastStage[s.id]
                if (prev == null || prev != s.stage) {
                    w("ستاپ #${s.id} @ ${hhmm(cd.t)} · stage ${prev ?: -1}→${s.stage} (${eng.stageFa(s.stage)}) · " +
                        "z=[${f2(s.zBot)}..${f2(s.zTop)}] mid=${f2(s.midRef)} lv=[${f2(s.lvL)}..${f2(s.lvH)}] " +
                        "hv=[${f2(s.hvL)}..${f2(s.hvH)}] entry=${f2(s.entry)} sl=${f2(s.sl)} " +
                        "tp1=${f2(s.tp1)} tp2=${f2(s.tp2)} tpx=${f2(s.tpx)} note=${s.note}")
                    lastStage[s.id] = s.stage
                }
            }
            broker.onBar(cd, live = false)
        }

        eng.runAll(candles)

        // ── پروفایل و ناحیه‌های ساعت ۰۸:۰۰ (کندل ناحیه) ─────────────────────
        w("")
        w("── پروفایل حجم کندل ساختار ۰۸:۰۰ (۲۴ ردیف، از کف به سقف) ──")
        val prof = eng.profileOnLastS
        val zoneBoxes = eng.zones
        w("آخرین پروفایل محاسبه‌شده در پایان اجرا (برای مرجع): rows=${prof?.rows}")
        for (zb in zoneBoxes) {
            w("باکس ناحیه: dir=${zb.dir} Z${zb.idx} top=${f2(zb.top)} bot=${f2(zb.bot)} createdT=${hhmm(zb.createdT)} " +
                "status=${zb.status} rows=${zb.loIdx}-${zb.hiIdx}")
        }
        w("ناحیه‌های تریگر۱: ${eng.t1Zones.size} · ناحیه‌های تریگر۲: ${eng.t2Zones.size}")
        for (z in eng.t1Zones) w("  LV box: [${f2(z.bot)} .. ${f2(z.top)}] createdT=${hhmm(z.createdT)} status=${z.status}")
        for (z in eng.t2Zones) w("  HV box: [${f2(z.bot)} .. ${f2(z.top)}] createdT=${hhmm(z.createdT)} status=${z.status}")

        w("")
        w("── سفارش‌ها ──")
        for (o in broker.orders) {
            w("سفارش #${o.id} setup=${o.setupId} dir=${o.dir} price=${f4(o.price)} sl=${f4(o.sl)} tp1=${f4(o.tp1)} tp2=${f4(o.tp2)} tpX=${f4(o.tpX)} " +
                "qty=${f4(o.qty)} hvTop=${f4(o.hvTop)} hvBot=${f4(o.hvBot)} status=${o.status} placed=${hhmm(o.placedT)} filled=${o.filledT?.let { hhmm(it) }} cancel=${o.cancelReason}")
        }
        w("")
        w("── معاملات ──")
        for (t in broker.trades) {
            w("معامله #${t.id} dir=${t.dir} entry=${f4(t.entry)} sl0=${f4(t.sl0)} tp1=${f4(t.tp1)} tp2=${f4(t.tp2)} tpX=${f4(t.tpX)} qty=${f4(t.qty)} " +
                "entryT=${hhmm(t.entryT)} exitT=${t.exitT?.let { hhmm(it) }} exitPx=${t.exitPx?.let { f4(it) }} reason=${t.reason} " +
                "gross=${f4(t.grossPnl())} pnl=${f4(t.pnl())} R=${f4(t.rMultiple())}")
            for (fl in t.fills) w("    پله: ${fl.kind} t=${hhmm(fl.t)} px=${f4(fl.price)} qty=${f4(fl.qty)} pnl=${f4(fl.pnl)}")
        }
        w("")
        w("── جمع‌بندی موتور ──")
        w("شمارنده‌ها: ستاپ=${eng.cnt.setup} برخورد=${eng.cnt.touch} میانی=${eng.cnt.mid} LV=${eng.cnt.lv} HV=${eng.cnt.hv} ورود=${eng.cnt.entry} پایان=${eng.cnt.done}")
        w("موجودی نهایی=${f4(broker.balance)} · equity=${f4(broker.equity)} · معاملات=${broker.trades.size} · سفارش‌ها=${broker.orders.size}")
        w("")
        w("── لاگ کامل موتور (به ترتیب) ──")
        logs.forEach { w(it) }

        outFile.writeText(sb.toString())
        println("CODE REPORT → ${outFile.absolutePath}")
        println(sb.toString())
    }
}
