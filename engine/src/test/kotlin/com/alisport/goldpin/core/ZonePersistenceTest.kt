package com.alisport.goldpin.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ZonePersistenceTest {

    @Test
    fun `old structure and trigger boxes and unarmed setup survive a shorter history rebuild`() {
        val cfg = Settings()
        val previousZones = listOf(
            ZoneBox(1L, 1, 1, 110.0, 100.0, 10, 1_000L, 2, 5, 10, 1_000L, ZoneStatus.ACTIVE),
            ZoneBox(2L, -1, 1, 90.0, 80.0, 20, 2_000L, 3, 6, 45, 4_000L, ZoneStatus.DELETED),
            ZoneBox(3L, 1, 2, 120.0, 115.0, 30, 3_000L, 4, 7, 30, 3_000L, ZoneStatus.REJECTED),
            ZoneBox(4L, 1, 1, 130.0, 125.0, 40, 4_000L, 5, 8, 40, 4_000L, ZoneStatus.CAP)
        )
        val previousT1 = listOf(
            ZoneBox(5L, 1, 1, 105.0, 103.0, 50, 4_200L, 0, 0, 55, 4_500L, ZoneStatus.ACTIVE),
            ZoneBox(6L, 1, 1, 104.0, 102.0, 56, 4_600L, 0, 0, 57, 4_700L, ZoneStatus.DELETED)
        )
        val previousT2 = listOf(
            ZoneBox(7L, 1, 2, 107.0, 106.0, 58, 4_800L, 0, 0, 60, 4_900L, ZoneStatus.ACTIVE)
        )
        val previousSetup = Setup(9L, 1, 2, 110.0, 100.0, 1, 10).apply {
            zT = 1_000L
            touchBi = 12; touchT = 1_200L; touchMidStart = 1_200L
            midRef = 98.0; midBi = 15; midT = 1_500L; midConfirmBi = 19
            note = "تایید میانی"
        }

        val rebuilt = Engine(cfg)
        // همان ناحیه که در پنجرهٔ جدید دوباره ساخته شده، نباید با نسخهٔ قبلی تکرار شود.
        rebuilt.zones.add(ZoneBox(20L, 1, 1, 110.0, 100.0, 0, 1_000L, 2, 5, 0, 1_000L, ZoneStatus.ACTIVE))
        rebuilt.zoneSeq = 1L
        val replay = listOf(Candle(0, 5_000L, 104.0, 106.0, 103.0, 105.0, 10.0))

        val restoredCount = rebuilt.carryOverZones(
            previousZones, previousT1, previousT2, fromT = 5_000L,
            prevZoneSeq = 8L, prevSetups = listOf(previousSetup), currentCandles = replay
        )

        assertEquals(6, restoredCount, "ناحیهٔ ساختاری تکراری نمی‌شود و CAP تاریخی حفظ می‌شود")
        assertEquals(4, rebuilt.zones.size)
        assertTrue(rebuilt.zones.any { it.status == ZoneStatus.CAP && it.createdT == 4_000L })
        assertEquals(1, rebuilt.zones.count { it.status == ZoneStatus.ACTIVE })
        assertTrue(rebuilt.zones.any { it.status == ZoneStatus.DELETED && it.endT == 4_000L })
        assertTrue(rebuilt.zones.any { it.status == ZoneStatus.REJECTED })
        assertEquals(2, rebuilt.t1Zones.size)
        assertEquals(1, rebuilt.t2Zones.size)

        val carried = rebuilt.setups.single { it.zT == 1_000L }
        assertEquals(2, carried.stage)
        assertEquals(98.0, carried.midRef, 1e-9)
        assertEquals(-1, carried.zBi, "ایندکس کندل قدیمی نباید به سری جدید نشت کند")
        assertEquals(0, carried.midConfirmBi, "تایید قبلی تا آخرین کندل پنجره معتبر می‌ماند")

        // ذخیره و بازیابی باید باکس‌های زنده و خاکستری را با تمام وضعیت حفظ کند.
        val broker = PaperBroker(cfg)
        val restored = Store.load(Store.save(cfg, rebuilt, broker, replay)).eng
        assertEquals(rebuilt.zones.map { it.status to it.createdT }, restored.zones.map { it.status to it.createdT })
        assertEquals(rebuilt.t1Zones.map { it.status to it.createdT }, restored.t1Zones.map { it.status to it.createdT })
        assertEquals(rebuilt.t2Zones.map { it.status to it.createdT }, restored.t2Zones.map { it.status to it.createdT })

        // اجرای همان بازیابی دوباره نباید باکس یا ستاپ تکراری بسازد.
        val second = rebuilt.carryOverZones(
            previousZones, previousT1, previousT2, fromT = 5_000L,
            prevZoneSeq = 8L, prevSetups = listOf(previousSetup), currentCandles = replay
        )
        assertEquals(0, second)
        assertEquals(1, rebuilt.setups.count { it.zT == 1_000L })
    }

    @Test
    fun `old active zone invalidated by a close in the new window is restored as grey not tradable`() {
        val cfg = Settings()
        val old = ZoneBox(10L, 1, 1, 110.0, 100.0, 2, 1_000L, 0, 1, 2, 1_000L, ZoneStatus.ACTIVE)
        val oldSetup = Setup(1L, 1, 0, 110.0, 100.0, 1, 2).apply { zT = 1_000L }
        val rebuilt = Engine(cfg)
        val bars = listOf(Candle(0, 5_000L, 101.0, 103.0, 98.0, 99.0, 10.0))

        rebuilt.carryOverZones(listOf(old), emptyList(), emptyList(), 5_000L, 11L, listOf(oldSetup), bars)

        val zone = rebuilt.zones.single()
        assertEquals(ZoneStatus.DELETED, zone.status)
        assertEquals(5_000L, zone.endT)
        assertTrue(rebuilt.setups.isEmpty(), "ناحیه‌ای که با کلوز سمت دور باطل شده ستاپ فعال نمی‌سازد")
    }
}
