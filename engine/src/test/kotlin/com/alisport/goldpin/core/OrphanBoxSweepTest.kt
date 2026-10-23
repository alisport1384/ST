package com.alisport.goldpin.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * باگ گزارش‌شده: «بعضی باکس‌های باطل‌شده روی چارت پاک نمی‌شوند.»
 *
 * ریشه: باکس‌های LV/HV (تریگر ۱ و ۲) فقط با فراخوانی صریح ستاپ بسته می‌شدند. اگر ستاپ
 * حذف/جایگزین می‌شد، باکسش بی‌مالک می‌ماند و تا ابد `ACTIVE` کشیده می‌شد.
 * اصلاح: هر باکس ACTIVE که هیچ ستاپی مالکش نیست، در نخستین کندل بستهٔ تریگر ۲ بسته می‌شود.
 */
class OrphanBoxSweepTest {

    private fun openBar(bi: Int, h: Double, l: Double, c: Double) =
        Candle(bi, 1_761_000_000_000L + bi * 60_000L, c, h, l, c, 100.0)

    @Test
    fun `active trigger boxes without an owner are closed on the next closed bar`() {
        val e = Engine(Settings())
        // دو باکس ACTIVE که هیچ ستاپی مالکشان نیست (شبیه‌سازی ستاپِ حذف‌شده)
        e.t1Zones.add(ZoneBox(1, 1, 1, 2_010.0, 2_005.0, 0, 1_761_000_000_000L, 0, 1))
        e.t2Zones.add(ZoneBox(2, -1, 2, 2_020.0, 2_015.0, 0, 1_761_000_000_000L, 0, 1))
        assertEquals(ZoneStatus.ACTIVE, e.t1Zones[0].status)
        assertEquals(ZoneStatus.ACTIVE, e.t2Zones[0].status)

        // دو کندل بسته می‌فرستیم تا مسیر عادی processClosed اجرا شود
        e.feed(listOf(openBar(0, 2_030.0, 2_025.0, 2_028.0), openBar(1, 2_031.0, 2_026.0, 2_029.0)), lastIsClosed = true)

        assertEquals(ZoneStatus.DELETED, e.t1Zones[0].status, "باکس LV بی‌مالک باید بسته شود")
        assertEquals(ZoneStatus.DELETED, e.t2Zones[0].status, "باکس HV بی‌مالک باید بسته شود")
        assertTrue(e.t1Zones[0].endT > 0L, "زمان پایان باید ثبت شود تا روی چارت کوتاه شود")
    }

    @Test
    fun `owned boxes are left untouched`() {
        val e = Engine(Settings())
        val s = Setup(1L, 1, 2, 2_000.0, 1_990.0, 1, 0)
        val box = ZoneBox(7, 1, 1, 2_010.0, 2_005.0, 0, 1_761_000_000_000L, 0, 1)
        e.t1Zones.add(box)
        s.lvZoneId = box.id
        e.setups.add(s)

        e.feed(listOf(openBar(0, 2_030.0, 2_025.0, 2_028.0), openBar(1, 2_031.0, 2_026.0, 2_029.0)), lastIsClosed = true)

        assertEquals(ZoneStatus.ACTIVE, box.status, "باکس متعلق به ستاپ زنده نباید بسته شود")
    }
}
