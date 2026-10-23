package com.alisport.goldpin.core

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * پروب‌های عددی سند — قواعدی که سناریوی کامل مسیر اصلی آن‌ها را پوشش نمی‌دهد:
 *   • §۲۰-۱ سه معاملهٔ نمونهٔ سند (Lot = Lost/div + MinLot)
 *   • §۴-۸ فالبک حد سود نهایی (فاصلهٔ کمتر از ۱۰ واحد)
 *   • §۱۸-۳۱ سفارش با فاصلهٔ حد ضرر بیشتر از N ثبت نمی‌شود
 */
class DocProbeTest {

    private fun f2(v: Double) = String.format("%.2f", v)
    private fun f4(v: Double) = String.format("%.4f", v)

    @Test
    fun `doc 20-1 managed-all-lost worked example`() {
        val cfg = Settings().apply {
            initialEquity = 10_000.0
            mmMode = MoneyMode.MANAGE_ALL_LOST
            minLotAuto = false; minLotManual = 10.0
            divAuto = true
            contractSize = 1.0
            lotStep = 0.01
            lostResetAtFinalTp = true
            maxLeverage = 1_000_000.0
        }
        val mm = MoneyManager(cfg)
        var capital = 9_900.0
        val report = StringBuilder()

        // سند: nakedcapital=10,000 · capital=9,900 · lost=100 · MinLot=10
        mm.lost = 100.0

        data class Leg(val entry: Double, val tp: Double, val sl: Double, val docLot: Double)
        val legs = listOf(
            Leg(2000.0, 2015.0, 1995.0, 16.66),   // div = 15
            Leg(1900.0, 1920.0, 1897.0, 19.16),   // div = 20
            Leg(1811.0, 1821.0, 1808.0, 34.07)    // div = 10
        )
        val outcomes = listOf("SL", "SL", "TP")
        for ((i, leg) in legs.withIndex()) {
            val m = mm.lotFor(capital, leg.entry, leg.tp)
            val lot = mm.floorToStep(m.raw)
            report.appendLine("معامله ${i + 1}: div=${f2(m.div)} · lot=${f4(m.raw)} → ${f2(lot)} (سند: ${f2(leg.docLot)})")
            assertEquals(leg.docLot, lot, 0.005, "حجم معامله ${i + 1} مطابق مثال سند")
            // بستن معامله: SL یا TP
            val exit = if (outcomes[i] == "SL") leg.sl else leg.tp
            val pnl = (exit - leg.entry) * lot * cfg.contractSize
            capital += pnl
            val t = Trade(
                id = i.toLong(), orderId = i.toLong(), setupId = 0, dir = 1,
                entryT = 0, entryBi = 0, entry = leg.entry, sl0 = leg.sl,
                tp1 = Double.NaN, tp2 = Double.NaN, tpX = leg.tp, qty = lot,
                zoneTop = 0.0, zoneBot = 0.0, contractSize = 1.0
            )
            t.fills.add(Fill(0, 0, leg.entry, lot, "ENTRY", 0.0))
            t.fills.add(Fill(0, 0, exit, lot, if (outcomes[i] == "SL") "SL" else "TPX", pnl))
            mm.onTradeClosed(t)
            mm.onCapitalUpdated(capital)
            report.appendLine("   نتیجه ${outcomes[i]} · pnl=${f2(pnl)} · capital=${f4(capital)} · lost=${f4(mm.lost)}")
        }
        // اعداد سند: capital 9816.7 / lost 183.3 ⇒ 9757.87* / 240.78 ⇒ 10098.57 / lost 0
        report.appendLine("سند: capital 9816.70 · lost 183.30 | capital 9757.87* · lost 240.78 | capital 10098.57 · lost 0.00")
        report.appendLine("* عدد capital در مثال دوم سند با lost هم‌خوان نیست (10000 − 240.78 = 9759.22).")
        println(report)
        assertEquals(0.0, mm.lost, 1e-9, "با رسیدن به حد سود نهایی، Lost صفر می‌شود (§۲۰-۱)")
    }

    @Test
    fun `doc 4-8 final tp fallback when fib distance is under ten units`() {
        // خرید: ورود 2013.21 · لنگرها 2022.00 و 2019.00 ⇒ TPX فیبو = 2022.816 ⇒ فاصله 9.606 < 10
        val t = calculateTargetLevels(true, 2013.21, 2022.00, 2019.00, 10.0)
        println("فالبک: tp1=${f4(t.tp1)} tp2=${f4(t.tp2)} tpX=${f4(t.tpx)} fibDeleted=${t.fibDeleted}")
        assertEquals(true, t.fibDeleted)
        assertEquals(2023.21, t.tpx, 1e-9, "§۴-۸: حد سود نهایی = ورود + ۱۰ واحد")
        assertEquals(2013.21 + 10.0 * 0.382, t.tp1, 1e-9, "§۴-۸: پله‌ها از فاصلهٔ جدید (۳۸٪)")
        assertEquals(2013.21 + 10.0 * 0.5, t.tp2, 1e-9, "§۴-۸: پله‌ها از فاصلهٔ جدید (۵۰٪)")
    }

    @Test
    fun `doc 18-31 order with stop further than N is not registered`() {
        val cfg = Settings().apply { maxSlUnits = 3.0; mintick = 0.01; initialEquity = 100_000.0 }
        val b = PaperBroker(cfg)
        val s = Setup(0, 1, 5, 2020.0, 2010.0, 1, 0).apply {
            hvH = 2013.20; hvL = 2012.70
            sl = 2009.00                       // فاصله = 2013.21 − 2009.00 = 4.21 > N
            tpx = 2024.72; tp1 = 2015.82; tp2 = 2017.00
            midRef = 2012.00
        }
        val bar = Candle(1, (1792368000L + 14 * 3600) * 1000L, 2013.0, 2013.5, 2012.9, 2013.4, 100.0)
        val ok = b.registerLimit(s, bar)
        println("SL>N: ثبت شد؟ $ok · علت: ${b.lastError}")
        assertEquals(false, ok, "§۱۸-۳۱: سفارش با فاصلهٔ حد ضرر > N نباید ثبت شود")
    }
}
