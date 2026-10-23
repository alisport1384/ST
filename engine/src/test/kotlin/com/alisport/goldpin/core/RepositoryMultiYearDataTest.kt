package com.alisport.goldpin.core

import java.io.File
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Integrity checks for the longer datasets that are repository downloads, not app assets. */
class RepositoryMultiYearDataTest {

    private data class Expected(
        val years: Int,
        val bars: Int,
        val bytes: Long,
        val sha256: String,
        val firstMs: Long,
        val lastMs: Long
    )

    private val cases = listOf(
        Expected(2, 701_067, 9_844_331L,
            "873d4ffc765723abfada8ef55fbf8b9bb9c7432125187395972f51af94496e9f",
            1_724_198_400_000L, 1_787_270_340_000L),
        Expected(3, 1_040_730, 14_310_975L,
            "56751049149f3df1ed134fdbc5e667ec57655f445edde5820ef9d9fed6697fcc",
            1_692_576_000_000L, 1_787_270_340_000L)
    )

    private fun datasetFile(years: Int): File? = listOf(
        "datasets/goldpin_xauusd_m1_${years}y.csv.gz",
        "../datasets/goldpin_xauusd_m1_${years}y.csv.gz",
        "/home/user/ST2/datasets/goldpin_xauusd_m1_${years}y.csv.gz"
    ).map(::File).firstOrNull { it.isFile }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered(1 shl 16).use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
    }

    @Test
    fun `two and three year downloads have valid data and stable hashes`() {
        for (expected in cases) {
            val file = datasetFile(expected.years)
                ?: error("دیتاست ${expected.years} ساله در پوشهٔ datasets پیدا نشد")
            assertEquals(expected.bytes, file.length(), "اندازهٔ فایل ${expected.years} ساله تغییر کرده")
            assertEquals(expected.sha256, sha256(file), "هش فایل ${expected.years} ساله تغییر کرده")

            var count = 0
            var previous = Long.MIN_VALUE
            var first = Long.MIN_VALUE
            var last = Long.MIN_VALUE
            GZIPInputStream(file.inputStream().buffered(1 shl 16)).bufferedReader().useLines { lines ->
                lines.drop(1).forEach { line ->
                    val cells = line.split(',')
                    val time = cells[0].toLong()
                    val o = cells[1].toDouble()
                    val h = cells[2].toDouble()
                    val l = cells[3].toDouble()
                    val c = cells[4].toDouble()
                    val v = cells[5].toDouble()
                    assertTrue(time > previous, "timestamp باید صعودی باشد")
                    assertTrue(h >= maxOf(o, c) && l <= minOf(o, c) && h > 0 && l > 0,
                        "OHLC نامعتبر در فایل ${expected.years} ساله")
                    assertTrue(v > 0, "حجم باید مثبت باشد")
                    if (count == 0) first = time
                    last = time
                    previous = time
                    count++
                }
            }
            assertEquals(expected.bars, count)
            assertEquals(expected.firstMs, first)
            assertEquals(expected.lastMs, last)
        }
    }
}
