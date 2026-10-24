package com.alisport.goldpin.core

import kotlin.test.Test
import kotlin.test.assertEquals

class RollingHistoryTest {
    @Test
    fun appendEvictsOldestInConstantCapacityAndKeepsChronologicalIndexing() {
        val history = RollingHistory<Int>(3)
        (1..5).forEach(history::add)
        assertEquals(3, history.size)
        assertEquals(listOf(3, 4, 5), history.toList())
        assertEquals(3, history[0])
        assertEquals(5, history.last())
        history.clear()
        assertEquals(0, history.size)
        history.add(7)
        assertEquals(listOf(7), history.toList())
    }
}
