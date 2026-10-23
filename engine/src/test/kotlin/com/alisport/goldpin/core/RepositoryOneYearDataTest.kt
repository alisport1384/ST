package com.alisport.goldpin.core

import java.io.File
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * دادهٔ یک‌سالهٔ XAUUSD ‏M1 که در پوشهٔ datasets ریپو نگهداری می‌شود
 * (`datasets/goldpin_xauusd_m1_1y.csv.gz`).
 *
 * این آزمون «قرارداد فایل» را قفل می‌کند: اگر روزی فایل دیتاست عوض/ناقص/جابه‌جا شود،
 * بک‌تست داخلی روی گوشی بی‌صدا خراب می‌شود؛ پس همان‌جا که داده در ریپو نگهداری می‌شود،
 * هویتش هم بررسی می‌شود:
 *  • هش و اندازهٔ بایتی (همان مقداری که `Feed.BACKTEST_DATA_SHA256` در اپ دارد)
 *  • تعداد کندل = ۳۵۱٬۸۹۴ (همان `Feed.BACKTEST_DATA_BARS`)
 *  • بازهٔ زمانی دقیقاً یک سال، زمان‌ها صعودی، هیچ کندل بی‌حجم
 *  • موتور واقعاً می‌تواند همین داده را بخورد (یک پاس کوتاه روی ابتدای سال)
 */
class RepositoryOneYearDataTest {

    private companion object {
        /** مقدار مورد انتظار — همان ثابت‌های `Feed` در ماژول اپ. */
        const val EXPECT_SHA256 = "15fffd94b9c1970080d6012ff2aa3ae307d18a872381c677e44d16b5fd08db44"
        const val EXPECT_BYTES = 5_128_001L
        const val EXPECT_BARS = 351_894
        /** ۲۰۲۵-۰۸-۲۱T00:00:00Z */
        const val EXPECT_FIRST_MS = 1_755_734_400_000L
        /** ۲۰۲۶-۰۸-۲۰T23:59:00Z */
        const val EXPECT_LAST_MS = 1_787_270_340_000L

        fun datasetFile(): File? = listOf(
            "datasets/goldpin_xauusd_m1_1y.csv.gz",
            "../datasets/goldpin_xauusd_m1_1y.csv.gz",
            "/home/user/ST2/datasets/goldpin_xauusd_m1_1y.csv.gz"
        ).map(::File).firstOrNull { it.exists() }
    }

    private data class Row(val t: Long, val o: Double, val h: Double, val l: Double, val c: Double, val v: Double)

    private fun readAll(f: File): List<Row> {
        val out = ArrayList<Row>(EXPECT_BARS)
        GZIPInputStream(f.inputStream().buffered(1 shl 16)).bufferedReader().useLines { lines ->
            for (ln in lines) {
                val p = ln.split(',')
                if (p.size < 6) continue
                val t = p[0].trim().toLongOrNull() ?: continue   // ردیف تیتر اینجا رد می‌شود
                out.add(
                    Row(
                        t,
                        p[1].toDouble(), p[2].toDouble(), p[3].toDouble(), p[4].toDouble(), p[5].toDouble()
                    )
                )
            }
        }
        return out
    }

    @Test
    fun `repository one-year dataset is byte-identical to the published dataset`() {
        val f = datasetFile()
        assumeTrue(f != null, "فایل دادهٔ یک‌ساله در دسترس نیست")
        assertEquals(EXPECT_BYTES, f!!.length(), "اندازهٔ فایل دیتاست عوض شده است")

        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().buffered(1 shl 16).use { ins ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = ins.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        val hex = StringBuilder()
        for (b in md.digest()) hex.append(String.format("%02x", b))
        assertEquals(EXPECT_SHA256, hex.toString(), "هش دارایی با دیتاست منتشرشده نمی‌خواند")
    }

    @Test
    fun `repository one-year dataset holds a full tradable year of volume-bearing M1 bars`() {
        val f = datasetFile()
        assumeTrue(f != null, "فایل دادهٔ یک‌ساله در دسترس نیست")
        val rows = readAll(f!!)

        assertEquals(EXPECT_BARS, rows.size, "تعداد کندل دارایی با مقدار مورد انتظار اپ فرق دارد")
        assertEquals(EXPECT_FIRST_MS, rows.first().t, "اولین کندل جابه‌جا شده است")
        assertEquals(EXPECT_LAST_MS, rows.last().t, "آخرین کندل جابه‌جا شده است")

        var prev = Long.MIN_VALUE
        var zeroVol = 0
        var badOhlc = 0
        for (r in rows) {
            assertTrue(r.t > prev, "زمان‌ها باید اکیداً صعودی باشند (t=${r.t} prev=$prev)")
            prev = r.t
            if (r.v <= 0.0) zeroVol++
            if (!(r.h >= maxOf(r.o, r.c) && r.l <= minOf(r.o, r.c) && r.h > 0 && r.l > 0)) badOhlc++
        }
        assertEquals(0, zeroVol, "کندل بی‌حجم در دادهٔ موجود در دیتاست پیدا شد")
        assertEquals(0, badOhlc, "کندل با OHLC نامعتبر در دادهٔ موجود در دیتاست پیدا شد")
    }

    @Test
    fun `engine consumes the repository one-year data without breaking`() {
        val f = datasetFile()
        assumeTrue(f != null, "فایل دادهٔ یک‌ساله در دسترس نیست")
        val rows = readAll(f!!)
        // پاس کوتاه روی ابتدای سال: ثابت می‌کند همین فایل (نه فقط نمونهٔ کوچک) خوراک موتور است.
        val slice = rows.subList(0, 30_000)
        val candles = ArrayList<Candle>(slice.size)
        for ((i, r) in slice.withIndex()) candles.add(Candle(i, r.t, r.o, r.h, r.l, r.c, r.v))

        val mode = TF_MODES.first { it.idx == 1 }
        val cfg = Settings().apply { tfS = mode.s; tfM = mode.m; tf1 = mode.t1; tf2 = mode.t2 }
        val eng = Engine(cfg)
        val broker = PaperBroker(cfg)
        eng.broker = broker
        broker.engine = eng
        eng.barCommitHook = { cd -> broker.onBar(cd, live = false) }

        var upto = 0
        val step = 10_000
        while (upto < candles.size) {
            val next = minOf(upto + step, candles.size)
            eng.feed(candles.subList(0, next), lastIsClosed = false)
            upto = next
        }
        eng.feed(candles, lastIsClosed = true)

        assertEquals(candles.size, eng.processed, "موتور باید همهٔ کندل‌های دادهٔ دیتاست را پردازش کند")
        assertTrue(eng.zones.isNotEmpty(), "روی ۳۰ هزار کندل ابتدای سال باید باکس ساختار ساخته شود")
        assertTrue(candles.all { it.v > 0.0 }, "حجم واقعی باید تا موتور برسد")
    }
}
