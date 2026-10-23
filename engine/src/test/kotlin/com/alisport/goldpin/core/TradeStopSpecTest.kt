package com.alisport.goldpin.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TradeStopSpecTest {

    @Test
    fun `doc 4-9 oo stop transfer is deferred so a sell stop never moves on a close beyond oo`() {
        val cfg = Settings().apply { maxSlUnits = 1_000_000.0 }
        val broker = PaperBroker(cfg)
        val t0 = 1_699_948_800_000L // سه‌شنبه 08:00 UTC، داخل پنجرهٔ سفارش‌گیری
        val setup = Setup(1L, -1, 5, 2_020.0, 2_000.0, 1, 0).apply {
            hvH = 2_010.0; hvL = 2_007.0
            sl = 2_010.02; tp1 = 1_990.0; tp2 = 1_980.0; tpx = 1_900.0
        }
        assertTrue(broker.registerLimit(setup, Candle(0, t0, 2_008.0, 2_009.0, 2_007.0, 2_008.0, 10.0)))
        val oo = broker.pendingOrders.single().price
        assertEquals(2_006.99, oo, 1e-9)

        // پرشدن سفارش فروش با لمس سطح oo
        broker.onBar(Candle(1, t0 + 60_000L, 2_008.0, 2_007.5, 2_006.5, 2_007.0, 10.0))
        val trade = broker.openTrades.single()
        assertEquals(2_010.02, trade.sl0, 1e-9)

        // §4-9: بلوک «انتقال حد ضرر به سطح oo با کلوز تریگر۲» در ویرایش فعلی سند داخل بلوک
        // «به تعویق افتاده و در کد ایجاد نمی‌شود» است ⇒ کلوز کاملاً زیر oo هم حد ضرر را جابه‌جا نمی‌کند.
        broker.onBar(Candle(2, t0 + 120_000L, 2_006.0, 2_008.0, 2_004.0, 2_005.0, 10.0))
        assertTrue(trade.open, "معامله باید باز بماند")
        assertFalse(trade.be, "سر‌به‌سر فقط با حد سود اول فعال می‌شود")

        // حد ضرر همان ۲۰۱۰٫۰۲ اولیه است: برگشت به آن معامله را با «حد ضرر» می‌بندد.
        broker.onBar(Candle(3, t0 + 180_000L, 2_007.0, 2_011.0, 2_006.0, 2_010.5, 10.0))
        assertFalse(trade.open)
        assertEquals("حد ضرر", trade.reason, "برچسب باید «حد ضرر» باشد نه «خروج سر‌به‌سر»")
        assertTrue(trade.grossPnl() < 0.0, "خروج باید ضرر قیمتی داشته باشد")
    }
}
