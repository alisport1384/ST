package com.alisport.goldpin.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * §۴-۵ سند: «تعداد ناحیه‌ها نامحدود است. ناحیه‌هایی که باطل نشده‌اند باید ثبت شوند و روی حافظه‌ی
 * گوشی ذخیره شوند، تا در تایم‌فریم‌های کوچک‌تر دوباره فراخوانی شوند.»
 *
 * این تست‌ها همان قاعده را در بازسازی موتور روی پنجرهٔ کوتاه‌تر قفل می‌کنند: باکس‌ها گم نمی‌شوند،
 * تکرار نمی‌شوند و هویت ناحیه به هندسهٔ باکس گره نمی‌خورد.
 */
class ZoneHistoryIdentityTest {

    @Test
    fun `zone created inside the rebuild window survives when the engine did not rebuild it`() {
        val cfg = Settings()
        // ناحیه‌ای که «داخل» پنجرهٔ تازه ساخته شده (createdT >= fromT) ولی در این اجرا دادهٔ ریز
        // آن بازه در دسترس نبوده و موتور آن را از نو نساخته است.
        val old = ZoneBox(5L, 1, 1, 110.0, 100.0, 7, 6_000L, 2, 5, 7, 6_000L, ZoneStatus.ACTIVE)
        val oldSetup = Setup(3L, 1, 0, 110.0, 100.0, 1, 7).apply { zT = 6_000L }
        val rebuilt = Engine(cfg)
        val bars = listOf(Candle(0, 6_500L, 105.0, 106.0, 104.0, 105.5, 10.0))

        val n = rebuilt.carryOverZones(listOf(old), emptyList(), emptyList(), 6_000L, 6L, listOf(oldSetup), bars)

        assertEquals(1, n, "باکس داخل پنجره هم باید بازگردانده شود")
        assertEquals(1, rebuilt.zones.size)
        assertEquals(ZoneStatus.ACTIVE, rebuilt.zones.single().status)
        assertTrue(rebuilt.setups.any { it.zT == 6_000L && it.dir == 1 }, "ستاپ منتظر برخورد باید بازگردد")
    }

    @Test
    fun `same zone identity ignores geometry drift caused by a finer profile rebuild`() {
        val cfg = Settings()
        val previous = ZoneBox(5L, 1, 1, 110.0, 100.0, 7, 6_000L, 2, 5, 7, 6_000L, ZoneStatus.ACTIVE)
        val rebuilt = Engine(cfg)
        // همان کندل ساختار (createdT=6000، Z1، صعودی) با سقف/کف کمی متفاوت ساخته شده است.
        rebuilt.zones.add(ZoneBox(1L, 1, 1, 110.4, 99.7, 0, 6_000L, 3, 6, 0, 6_000L, ZoneStatus.ACTIVE))
        val bars = listOf(Candle(0, 6_500L, 105.0, 106.0, 104.0, 105.5, 10.0))

        val n = rebuilt.carryOverZones(listOf(previous), emptyList(), emptyList(), 6_000L, 6L, emptyList(), bars)

        assertEquals(0, n, "هویت یکی است ⇒ نسخهٔ دوم اضافه نمی‌شود")
        assertEquals(1, rebuilt.zones.size)
        assertEquals(110.4, rebuilt.zones.single().top, 1e-9, "هندسهٔ تازهٔ موتور معتبر است")
    }

    @Test
    fun `carried setup is matched by zone identity not by box geometry`() {
        val cfg = Settings()
        val previous = ZoneBox(5L, 1, 1, 110.0, 100.0, 7, 6_000L, 2, 5, 7, 6_000L, ZoneStatus.ACTIVE)
        val oldSetup = Setup(3L, 1, 2, 110.0, 100.0, 1, 7).apply {
            zT = 6_000L
            touchBi = 9; touchT = 6_100L; touchMidStart = 6_100L
            midRef = 99.0; midBi = 10; midT = 6_200L; midConfirmBi = 11
        }
        val rebuilt = Engine(cfg)
        rebuilt.zones.add(ZoneBox(1L, 1, 1, 110.5, 100.5, 0, 6_000L, 3, 6, 0, 6_000L, ZoneStatus.ACTIVE))
        val bars = listOf(Candle(0, 6_500L, 105.0, 106.0, 104.0, 105.5, 10.0))

        rebuilt.carryOverZones(listOf(previous), emptyList(), emptyList(), 6_000L, 6L, listOf(oldSetup), bars)

        val carried = rebuilt.setups.filter { it.zT == 6_000L }
        assertEquals(1, carried.size, "ستاپ تکراری ساخته نمی‌شود")
        assertEquals(2, carried.single().stage, "وضعیت ستاپ قبلی حفظ می‌شود")
        assertEquals(99.0, carried.single().midRef, 1e-9)
    }
}
