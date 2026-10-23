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
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.GZIPOutputStream

/** Verifies downloadable test datasets stay outside the APK and are stored per range. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class StorageBundledDataTest {

    @Test
    fun backtestDatasetsAreNotBundledInApk() {
        val names = RuntimeEnvironment.getApplication().assets.list("").orEmpty().toSet()
        for (years in listOf(1, 2, 3, 10)) {
            assertFalse("Compressed ${years}-year dataset must stay outside the APK",
                names.contains("goldpin_xauusd_m1_${years}y.csv.gz"))
        }
        assertFalse("Expanded one-year dataset must stay outside the APK",
            names.contains("goldpin_xauusd_m1_1y.csv"))
    }

    @Test
    fun eachRangeHasIndependentDataAndResultFiles() {
        val ctx = RuntimeEnvironment.getApplication()
        assertEquals("goldpin_xauusd_m1_1y.csv.gz", Storage.dataFile(ctx, 1).name)
        assertEquals("goldpin_xauusd_m1_2y.csv.gz", Storage.dataFile(ctx, 2).name)
        assertEquals("goldpin_xauusd_m1_3y.csv.gz", Storage.dataFile(ctx, 3).name)
        assertEquals("goldpin_xauusd_m1_10y.csv.gz", Storage.dataFile(ctx, 10).name)
        assertNotEquals(Storage.dataFile(ctx, 1), Storage.dataFile(ctx, 2))
        assertNotEquals(Storage.dataPartFile(ctx, 2), Storage.dataPartFile(ctx, 3))
        // Keep the historical one-year result filename so existing saved runs remain readable.
        assertEquals("goldpin_backtest.json.gz", Storage.internalFile(ctx, 1).name)
        assertEquals("goldpin_backtest_2y.json.gz", Storage.internalFile(ctx, 2).name)
        assertEquals("goldpin_backtest_3y.json.gz", Storage.internalFile(ctx, 3).name)
        assertEquals("goldpin_backtest_10y.json.gz", Storage.internalFile(ctx, 10).name)
        assertNotEquals(Storage.internalFile(ctx, 1), Storage.internalFile(ctx, 2))
    }

    @Test
    fun packedTenYearParserKeepsPreciseBarsAndIndices() {
        val csv = "timestamp,open,high,low,close,volume\n" +
            "1700000000000,1234.12345,1235.00001,1233.99999,1234.50000,321\n" +
            "1700000060000,1234.50000,1236.00000,1234.10000,1235.25000,654\n"
        val compressed = ByteArrayOutputStream().also { out ->
            GZIPOutputStream(out).use { it.write(csv.toByteArray(Charsets.UTF_8)) }
        }.toByteArray()
        val bars = Feed.parseCsvPackedStream(ByteArrayInputStream(compressed), expectedBars = 2)
        assertEquals(2, bars.size)
        assertEquals(0, bars[0].bi)
        assertEquals(1234.12345, bars[0].o, 0.00000001)
        assertEquals(1235.00001, bars[0].h, 0.00000001)
        assertEquals(321.0, bars[0].v, 0.0)
        assertEquals(1, bars[1].bi)
        assertEquals(1_700_000_060_000L, bars[1].t)
        assertEquals(1235.25, bars[1].c, 0.00000001)
    }

    @Test
    fun tenYearRepositoryFileParsesAsLargeRandomAccessSeries() {
        val file = listOf(
            File("datasets/goldpin_xauusd_m1_10y.csv.gz"),
            File("../datasets/goldpin_xauusd_m1_10y.csv.gz")
        ).firstOrNull { it.isFile } ?: error("10-year dataset is missing from the repository")
        val spec = Feed.backtestDataset(10)
        val bars = file.inputStream().use { Feed.parseBacktestStream(it, spec) }
        assertEquals(3_402_126, bars.size)
        assertEquals(1_471_820_400_000L, bars[0].t)
        assertEquals(1_787_270_340_000L, bars.last().t)
        assertEquals(1340.04, bars[0].o, 0.00000001)
        assertEquals(237.0, bars[0].v, 0.0)
        assertEquals(4523.95, bars.last().c, 0.00000001)
    }

    @Test
    fun datasetCatalogIncludesVerifiedOneTwoThreeAndTenYearRanges() {
        assertEquals(listOf(1, 2, 3, 10), Feed.BACKTEST_DATASETS.map { it.years })
        assertEquals(listOf(351_894, 701_067, 1_040_730, 3_402_126), Feed.BACKTEST_DATASETS.map { it.bars })
        assertTrue(Feed.BACKTEST_DATASETS.all { it.sha256.length == 64 && it.url.startsWith("https://") })
        assertEquals(Feed.BACKTEST_DATA_FALLBACK_URL, Feed.backtestDataset(1).fallbackUrl)
        assertEquals(null, Feed.backtestDataset(2).fallbackUrl)
        assertEquals(43_314_146L, Feed.backtestDataset(10).bytesGz)
    }
}
