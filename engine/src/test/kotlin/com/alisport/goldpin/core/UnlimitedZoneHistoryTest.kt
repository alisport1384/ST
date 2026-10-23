package com.alisport.goldpin.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UnlimitedZoneHistoryTest {
    @Test
    fun `legacy cap and disabled invalidation settings migrate without dropping old boxes`() {
        val cfg = Settings().apply { maxZoneBoxes = 1; clearUsedZones = false }
        val eng = Engine(cfg)
        eng.zones.add(ZoneBox(1L, 1, 1, 110.0, 100.0, 0, 1_000L, 1, 2, 0, 1_000L))
        eng.zones.add(ZoneBox(2L, 1, 2, 120.0, 115.0, 1, 2_000L, 2, 3, 1, 2_000L, ZoneStatus.CAP))
        eng.zones.add(ZoneBox(3L, -1, 1, 140.0, 130.0, 2, 3_000L, 1, 2, 2, 3_000L, ZoneStatus.REJECTED))
        val oldJson = Store.save(cfg, eng, PaperBroker(cfg), emptyList())
            .replace("\"maxZoneBoxes\":2147483647", "\"maxZoneBoxes\":1")
            .replace("\"clearUsedZones\":true", "\"clearUsedZones\":false")
        val loaded = Store.load(oldJson)
        assertEquals(Int.MAX_VALUE, loaded.cfg.maxZoneBoxes)
        assertTrue(loaded.cfg.clearUsedZones)
        assertEquals(listOf(1L, 2L, 3L), loaded.eng.zones.map { it.id })
        assertEquals(listOf(ZoneStatus.ACTIVE, ZoneStatus.CAP, ZoneStatus.REJECTED), loaded.eng.zones.map { it.status })
    }

    @Test
    fun `historical capped boxes survive repeated rebuild and save load`() {
        val cfg = Settings()
        val old = listOf(ZoneBox(17L, 1, 1, 110.0, 100.0, 2, 1_000L, 0, 1, 2, 1_000L, ZoneStatus.CAP))
        val now = Engine(cfg)
        assertEquals(1, now.carryOverZones(old, emptyList(), emptyList(), 5_000L))
        assertEquals(0, now.carryOverZones(old, emptyList(), emptyList(), 5_000L))
        assertEquals(ZoneStatus.CAP, Store.load(Store.save(cfg, now, PaperBroker(cfg), emptyList())).eng.zones.single().status)
    }
}
