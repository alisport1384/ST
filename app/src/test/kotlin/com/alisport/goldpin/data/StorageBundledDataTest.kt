package com.alisport.goldpin.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Verifies the one-year test dataset is no longer shipped inside the APK. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class StorageBundledDataTest {

    @Test
    fun oneYearDataIsNotBundledInApk() {
        val names = RuntimeEnvironment.getApplication().assets.list("").orEmpty().toSet()
        assertFalse("Compressed one-year dataset must stay outside the APK",
            names.contains("goldpin_xauusd_m1_1y.csv.gz"))
        assertFalse("Expanded one-year dataset must stay outside the APK",
            names.contains("goldpin_xauusd_m1_1y.csv"))
    }

    @Test
    fun eachRangeHasIndependentDataAndResultFiles() {
        val ctx = RuntimeEnvironment.getApplication()
        assertEquals("goldpin_xauusd_m1_1y.csv.gz", Storage.dataFile(ctx, 1).name)
        assertEquals("goldpin_xauusd_m1_2y.csv.gz", Storage.dataFile(ctx, 2).name)
        assertEquals("goldpin_xauusd_m1_3y.csv.gz", Storage.dataFile(ctx, 3).name)
        assertNotEquals(Storage.dataFile(ctx, 1), Storage.dataFile(ctx, 2))
        assertNotEquals(Storage.dataPartFile(ctx, 2), Storage.dataPartFile(ctx, 3))
        // Keep the historical one-year result filename so existing saved runs remain readable.
        assertEquals("goldpin_backtest.json.gz", Storage.internalFile(ctx, 1).name)
        assertEquals("goldpin_backtest_2y.json.gz", Storage.internalFile(ctx, 2).name)
        assertEquals("goldpin_backtest_3y.json.gz", Storage.internalFile(ctx, 3).name)
        assertNotEquals(Storage.internalFile(ctx, 1), Storage.internalFile(ctx, 2))
    }

    @Test
    fun datasetCatalogIncludesVerifiedOneTwoAndThreeYearRanges() {
        assertEquals(listOf(1, 2, 3), Feed.BACKTEST_DATASETS.map { it.years })
        assertEquals(listOf(351_894, 701_067, 1_040_730), Feed.BACKTEST_DATASETS.map { it.bars })
        assertTrue(Feed.BACKTEST_DATASETS.all { it.sha256.length == 64 && it.url.startsWith("https://") })
        assertEquals(Feed.BACKTEST_DATA_FALLBACK_URL, Feed.backtestDataset(1).fallbackUrl)
        assertEquals(null, Feed.backtestDataset(2).fallbackUrl)
    }
}
