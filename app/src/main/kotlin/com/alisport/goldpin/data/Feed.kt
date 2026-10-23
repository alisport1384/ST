package com.alisport.goldpin.data

import com.alisport.goldpin.core.Candle
import com.alisport.goldpin.core.MarketHours
import com.alisport.goldpin.core.Tf
import com.alisport.goldpin.util.Fa
import com.alisport.goldpin.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream
import javax.net.ssl.HttpsURLConnection

// ═══════════════════════════════════════════════════════════════════════════════
//  فید داده — دانلود خودکار تاریخچهٔ طلا + قیمت لحظه‌ای + ورود از فایل CSV
//
//  منابع (به ترتیب اولویت):
//    ۱) Yahoo Finance  — نماد GC=F (فیوچرز طلا) : کندل + حجم ، بدون کلید ، ۱ دقیقه تا ۲ سال
//    ۲) gold-api.com   — نرخ لحظه‌ای طلا (Spot XAU) برای به‌روزرسانی قیمت و تیک
//    ۳) فایل CSV       — ورود دستی (مثلاً خروجی دوکاس‌کپی) برای تاریخچهٔ عمیق با حجم واقعی
//    ۴) دارایی نمونهٔ داخلی — بک‌تست آفلاین
//
//  نکته: حجم در Yahoo برای GC=F موجود است. اگر فید انتخابی حجم ندهد، موتور
//  امکان «حجم تقریبی از دامنهٔ کندل» را دارد تا استراتژی از کار نیفتد.
// ═══════════════════════════════════════════════════════════════════════════════

class FeedException(msg: String) : Exception(msg)

data class BacktestDatasetSpec(
    val years: Int,
    val bars: Int,
    val bytesGz: Long,
    val sha256: String,
    val url: String,
    val fallbackUrl: String? = null
)

/**
 * Primitive-backed candle view for the 10-year history. It keeps only primitive columns
 * (28 bytes/bar) instead of millions of heap-allocated Candle objects; a Candle is created
 * on demand when the engine/chart reads an index.
 */
private const val PACKED_PRICE_SCALE = 100_000.0

private class PackedCandleList(
    private val times: LongArray,
    private val opens: IntArray,
    private val highs: IntArray,
    private val lows: IntArray,
    private val closes: IntArray,
    private val volumes: IntArray,
    override val size: Int
) : AbstractList<Candle>(), RandomAccess {
    override fun get(index: Int): Candle {
        if (index !in 0 until size) throw IndexOutOfBoundsException("index=$index size=$size")
        return Candle(index, times[index], opens[index] / PACKED_PRICE_SCALE, highs[index] / PACKED_PRICE_SCALE,
            lows[index] / PACKED_PRICE_SCALE, closes[index] / PACKED_PRICE_SCALE, volumes[index].toDouble())
    }
}

object Feed {

    const val SYMBOL_DEFAULT = "GC=F"
    @Volatile var connectTimeoutMs: Int = 8000
    @Volatile var readTimeoutMs: Int = 12000
    /** برای اینکه استفاده از حجم ساختگی فقط در لاگ نماند، اپ این callback را وصل می‌کند. */
    @Volatile var syntheticVolumeWarning: ((Int) -> Unit)? = null

    // ── ابزار HTTP ─────────────────────────────────────────────────────────────
    /**
     * @param connectMs سقف اتصال و @param readMs سقف خواندن.
     *
     * ⚠ این سقف‌ها مستقیماً «بدترین حالت یک تیک لایو» را می‌سازند. پیش‌تر هر دو
     * ۲۰٬۰۰۰ms بود و سه منبع قیمت هم timeout جداگانه داشتند؛ یک تیک می‌توانست
     * بیش از دو دقیقه طول بکشد. حالا هر درخواست حداکثر ۸+۱۲ ثانیه است، دو تلاش Yahoo
     * و بودجهٔ زنجیرهٔ قیمت هرکدام محدودند (بدترین حالت حدود ۶۰ ثانیه)، و `CoalescingGate`
     * نمی‌گذارد تیک‌های تازه روی هم تلنبار شوند.
     */
    private fun get(urlStr: String, connectMs: Int = connectTimeoutMs, readMs: Int = readTimeoutMs, userAgent: String = UA): String {
        val url = URL(urlStr)
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = connectMs
        conn.readTimeout = readMs
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", userAgent)
        conn.setRequestProperty("Accept", "application/json,text/csv,*/*")
        conn.setRequestProperty("Accept-Language", "en-US,en;q=0.9")
        if (urlStr.contains("yahoo.com")) {
            conn.setRequestProperty("Referer", "https://finance.yahoo.com/")
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                val errBody = try {
                    conn.errorStream?.let { es ->
                        BufferedReader(InputStreamReader(es, Charsets.UTF_8)).use { it.readText() }
                    } ?: ""
                } catch (_: Exception) { "" }
                val detail = if (errBody.isNotBlank()) " (${Fa.short(errBody, 120)})" else ""
                throw FeedException("HTTP $code از $urlStr$detail")
            }
            val gz = conn.contentEncoding?.contains("gzip", true) == true
            val raw = conn.inputStream
            val ins = if (gz) GZIPInputStream(raw) else raw
            return BufferedReader(InputStreamReader(ins, Charsets.UTF_8)).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    // ⚠ سرور Edge یاهو User-Agent مرورگر موبایل اندروید (Chrome Mobile) را با HTTP 429
    // رد می‌کند؛ User-Agent دسکتاپ بدون محدودیت پاسخ ۲۰۰ می‌گیرد.
    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    private val YAHOO_UAS = listOf(
        UA,
        "Mozilla/5.0",
        "Mozilla/5.0 (X11; Linux x86_64; rv:125.0) Gecko/20100101 Firefox/125.0"
    )

    /** بازه‌های مجاز جایگزین در Yahoo برای هر interval (جلوگیری از HTTP 422 و افت دقت max به ماهانه). */
    internal fun yahooCandidateRanges(interval: String, preferredRange: String): List<String> {
        val normPref = when {
            // در Yahoo، بازهٔ 3mo برای تایم‌فریم‌های زیر یک ساعت خطای HTTP 422 می‌دهد (سقف ۶۰ روز).
            interval in setOf("5m", "15m", "30m") && preferredRange in setOf("3mo", "6mo", "1y", "2y", "max") -> "60d"
            // در Yahoo، سقف 1h/60m دقیقاً ۷۳۰ روز است؛ 2y در سال کبیسه ۷۳۱ روز می‌شود و HTTP 422 می‌دهد.
            interval in setOf("1h", "60m") && preferredRange in setOf("2y", "5y", "10y", "max") -> "730d"
            // در Yahoo، range=max روی 1d و 1wk دقت داده را به 1mo (ماهانه) تقلیل می‌دهد.
            interval in setOf("1d", "1wk") && preferredRange == "max" -> "10y"
            else -> preferredRange
        }
        val fallbacks = when (interval) {
            "1m" -> listOf(normPref, "7d", "5d", "1d")
            "2m", "5m", "15m", "30m", "90m" -> listOf(normPref, "60d", "59d", "1mo", "7d", "5d")
            "60m", "1h" -> listOf(normPref, "730d", "720d", "1y", "6mo", "3mo", "1mo")
            "1d" -> listOf(normPref, "10y", "5y", "2y", "1y", "6mo")
            "1wk" -> listOf(normPref, "10y", "5y", "2y", "1y")
            else -> listOf(normPref)
        }
        return fallbacks.distinct()
    }

    // ── Yahoo Finance ──────────────────────────────────────────────────────────
    /**
     * کندل‌های تاریخی از Yahoo با بازه‌های پشتیبان و منبع جایگزین بدون کلید.
     * interval: 1m,5m,15m,30m,1h,1d,1wk — range: 1d,5d,7d,1mo,60d,3mo,6mo,1y,730d,5y,10y
     */
    fun yahooChart(symbol: String, interval: String, range: String): List<Candle> {
        val t0 = System.currentTimeMillis()
        val ranges = yahooCandidateRanges(interval, range)
        Log.i(Log.CAT_FEED, "درخواست داده از Yahoo", "symbol=$symbol interval=$interval range=$range candidates=$ranges")
        val hosts = listOf("query1.finance.yahoo.com", "query2.finance.yahoo.com")
        var lastErr: Exception? = null
        for (r in ranges) {
            for ((idx, h) in hosts.withIndex()) {
                val ua = YAHOO_UAS[idx % YAHOO_UAS.size]
                try {
                    val url = "https://$h/v8/finance/chart/$symbol?interval=$interval&range=$r&includePrePost=false"
                    val body = get(url, userAgent = ua)
                    val list = parseYahoo(body, expectedInterval = interval)
                    if (list.isNotEmpty()) {
                        Log.i(Log.CAT_FEED, "پاسخ Yahoo دریافت شد",
                            "host=$h interval=$interval range=$r کندل=${list.size} مدت=${System.currentTimeMillis() - t0}ms حجم‌دار=${list.any { it.v > 0 }}")
                        return list
                    }
                    Log.w(Log.CAT_FEED, "پاسخ Yahoo خالی بود", "host=$h interval=$interval range=$r")
                } catch (e: Exception) {
                    Log.w(Log.CAT_FEED, "خطای درخواست Yahoo",
                        "host=$h range=$r · " + Fa.short(e.message, 200) + " · طول‌پیام=${e.message?.length ?: 0}")
                    lastErr = e
                }
            }
        }
        // پشتیبان دوم (بدون کلید): اگر یاهو در شبکهٔ کاربر مسدود یا با محدودیت مواجه شد، از فید عمومی طلا (PAXG) می‌گیریم
        val alt = fallbackGoldCandles(symbol, interval)
        if (alt.isNotEmpty()) {
            Log.i(Log.CAT_FEED, "تاریخچه از منبع پشتیبان دریافت شد",
                "interval=$interval کندل=${alt.size} مدت=${System.currentTimeMillis() - t0}ms")
            return alt
        }
        throw FeedException("دریافت داده از Yahoo ناموفق بود: " +
            Fa.short(lastErr?.message ?: "خالی", 200))
    }

    /**
     * `internal` (نه `private`) تا آزمون واحد بتواند شکل واقعی پاسخ Yahoo را
     * بدون شبکه بررسی کند — همان مسیری که باعث فریز اپ شده بود.
     */
    internal fun parseYahoo(body: String, expectedInterval: String? = null): List<Candle> {
        val root = JSONObject(body)
        val chart = root.optJSONObject("chart") ?: throw FeedException("پاسخ نامعتبر: بدون «chart»")
        if (chart.optString("result", "null") == "null")
            throw FeedException("نتیجه خالی (${Fa.short(chart.optString("error"), 120)})")
        val res = chart.optJSONArray("result")?.optJSONObject(0)
            ?: throw FeedException("پاسخ بدون «result»")
        val gran = res.optJSONObject("meta")?.optString("dataGranularity", "") ?: ""
        if (expectedInterval != null && expectedInterval != "1mo" && gran == "1mo") {
            throw FeedException("دقت دادهٔ برگشتی ماهانه (1mo) است نه $expectedInterval")
        }
        val ts = res.optJSONArray("timestamp")
            ?: throw FeedException("پاسخ بدون «timestamp»")
        // ⚠ در پاسخ /v8/finance/chart کلید «indicators» یک **JSONObject** است نه
        // JSONArray. پیش‌تر `getJSONArray("indicators")` صدا زده می‌شد که همیشه
        // JSONException می‌داد — یعنی لایو هیچ‌وقت داده نمی‌گرفت و هر ۱۰ ثانیه شکست
        // می‌خورد. بدتر اینکه آن JSONException کل آبجکت indicators (نزدیک یک مگابایت
        // برای ۵ روز کندل ۱ دقیقه‌ای) را در پیامش جاسازی می‌کرد و همان رشته نخ رابط
        // را در صفحه‌آرایی متن قفل می‌کرد.
        val ind = res.optJSONObject("indicators")
            ?: res.optJSONArray("indicators")?.optJSONObject(0)
            ?: throw FeedException("پاسخ بدون «indicators»")
        val quote = ind.optJSONArray("quote")?.optJSONObject(0)
            ?: throw FeedException("پاسخ بدون «quote»")
        val o = quote.optJSONArray("open"); val hi = quote.optJSONArray("high")
        val lo = quote.optJSONArray("low"); val cl = quote.optJSONArray("close")
        val vol = quote.optJSONArray("volume")
        val out = ArrayList<Candle>(ts.length())
        var bi = 0
        for (i in 0 until ts.length()) {
            val ov = o?.optDouble(i, Double.NaN) ?: Double.NaN
            val hv = hi?.optDouble(i, Double.NaN) ?: Double.NaN
            val lv = lo?.optDouble(i, Double.NaN) ?: Double.NaN
            val cv = cl?.optDouble(i, Double.NaN) ?: Double.NaN
            if (ov.isNaN() || hv.isNaN() || lv.isNaN() || cv.isNaN()) continue
            val vv = vol?.optDouble(i, 0.0) ?: 0.0
            out.add(Candle(bi++, ts.getLong(i) * 1000L, ov, hv, lv, cv, if (vv.isNaN()) 0.0 else vv))
        }
        return out
    }

    /** منبع پشتیبان کندل تاریخی طلا (بدون کلید API) در صورت مسدودی یا خطای یاهو */
    internal fun fallbackGoldCandles(symbol: String, interval: String): List<Candle> {
        val symUp = symbol.uppercase()
        if (!symUp.contains("GC") && !symUp.contains("XAU") && !symUp.contains("PAXG") && !symUp.contains("GOLD")) {
            return emptyList()
        }
        val binInterval = when (interval) {
            "1m" -> "1m"
            "2m", "3m" -> "3m"
            "5m" -> "5m"
            "15m" -> "15m"
            "30m" -> "30m"
            "60m", "1h" -> "1h"
            "4h" -> "4h"
            "1d" -> "1d"
            "1wk" -> "1w"
            else -> "1h"
        }
        try {
            val url = "https://data-api.binance.vision/api/v3/klines?symbol=PAXGUSDT&interval=$binInterval&limit=1000"
            val body = get(url)
            val arr = org.json.JSONArray(body)
            val out = ArrayList<Candle>(arr.length())
            var bi = 0
            for (i in 0 until arr.length()) {
                val row = arr.optJSONArray(i) ?: continue
                val t = row.optLong(0, -1L)
                val ov = row.optString(1).toDoubleOrNull() ?: continue
                val hv = row.optString(2).toDoubleOrNull() ?: continue
                val lv = row.optString(3).toDoubleOrNull() ?: continue
                val cv = row.optString(4).toDoubleOrNull() ?: continue
                val vv = row.optString(5).toDoubleOrNull() ?: 0.0
                if (t > 0L) out.add(Candle(bi++, t, ov, hv, lv, cv, vv))
            }
            if (out.isNotEmpty()) return out
        } catch (e: Exception) {
            Log.w(Log.CAT_FEED, "پشتیبان Binance Vision پاسخ نداد", Fa.short(e.message, 140))
        }
        try {
            val krakenInt = when (interval) {
                "1m" -> 1
                "5m" -> 5
                "15m" -> 15
                "30m" -> 30
                "60m", "1h" -> 60
                "4h" -> 240
                "1d" -> 1440
                "1wk" -> 10080
                else -> 60
            }
            val url = "https://api.kraken.com/0/public/OHLC?pair=PAXGUSD&interval=$krakenInt"
            val body = get(url)
            val res = JSONObject(body).optJSONObject("result")
            val arr = res?.optJSONArray("PAXGUSD")
            if (arr != null) {
                val out = ArrayList<Candle>(arr.length())
                var bi = 0
                for (i in 0 until arr.length()) {
                    val row = arr.optJSONArray(i) ?: continue
                    val tSec = row.optLong(0, -1L)
                    val ov = row.optString(1).toDoubleOrNull() ?: continue
                    val hv = row.optString(2).toDoubleOrNull() ?: continue
                    val lv = row.optString(3).toDoubleOrNull() ?: continue
                    val cv = row.optString(4).toDoubleOrNull() ?: continue
                    val vv = row.optString(6).toDoubleOrNull() ?: 0.0
                    if (tSec > 0L) out.add(Candle(bi++, tSec * 1000L, ov, hv, lv, cv, vv))
                }
                if (out.isNotEmpty()) return out
            }
        } catch (e: Exception) {
            Log.w(Log.CAT_FEED, "پشتیبان Kraken پاسخ نداد", Fa.short(e.message, 140))
        }
        return emptyList()
    }

    /** آخرین قیمت (چند ثانیه تأخیر) */
    fun yahooLast(symbol: String): Double? {
        return try {
            val body = get("https://query1.finance.yahoo.com/v8/finance/chart/$symbol?interval=1m&range=1d")
            val res = JSONObject(body).getJSONObject("chart").getJSONArray("result").getJSONObject(0)
            val meta = res.optJSONObject("meta")
            val p = meta?.optDouble("regularMarketPrice", Double.NaN) ?: Double.NaN
            if (p.isNaN()) null else p
        } catch (e: Exception) {
            null
        }
    }

    /** ۱) نرخ لحظه‌ای تتر طلا (PAXGUSDT) از Binance — بدون کلید */
    fun binanceSpot(): Double? {
        val urls = listOf(
            "https://api.binance.com/api/v3/ticker/price?symbol=PAXGUSDT",
            "https://data-api.binance.vision/api/v3/ticker/price?symbol=PAXGUSDT"
        )
        for (u in urls) {
            try {
                val body = get(u)
                val p = JSONObject(body).optString("price").toDoubleOrNull()
                    ?: JSONObject(body).optDouble("price", Double.NaN)
                if (!p.isNaN() && p > 500.0 && p < 20000.0) return p
            } catch (e: Exception) {
                Log.d(Log.CAT_FEED, "Binance پاسخ نداد", e.message ?: "")
            }
        }
        return null
    }

    /** ۲) نرخ لحظه‌ای تتر طلا (PAXG-USDT) از KuCoin — بدون کلید */
    fun kucoinSpot(): Double? {
        return try {
            val body = get("https://api.kucoin.com/api/v1/market/orderbook/level1?symbol=PAXG-USDT")
            val data = JSONObject(body).optJSONObject("data")
            val p = data?.optString("price")?.toDoubleOrNull()
                ?: data?.optDouble("price", Double.NaN) ?: Double.NaN
            if (!p.isNaN() && p > 500.0 && p < 20000.0) p else null
        } catch (e: Exception) {
            Log.d(Log.CAT_FEED, "KuCoin پاسخ نداد", e.message ?: "")
            null
        }
    }

    /** نرخ لحظه‌ای طلای نقدی (Spot) — بدون کلید */
    fun goldApiSpot(): Double? {
        return try {
            val body = get("https://api.gold-api.com/price/XAU")
            val p = JSONObject(body).optDouble("price", Double.NaN)
            if (p.isNaN()) null else p
        } catch (e: Exception) {
            Log.d(Log.CAT_FEED, "gold-api پاسخ نداد", e.message ?: "")
            null
        }
    }

    /** ۳) نرخ XAU/USD از Swissquote (عمومی، بدون کلید) */
    fun swissquoteSpot(): Double? {
        return try {
            val body = get("https://forex-data-feed.swissquote.com/public-quotes/bboquotes/instrument/XAU/USD")
            val arr = org.json.JSONArray(body)
            val profiles = arr.getJSONObject(0).getJSONArray("spreadProfilePrices")
            val p = profiles.getJSONObject(0)
            val bid = p.optDouble("bid", Double.NaN)
            val ask = p.optDouble("ask", Double.NaN)
            if (bid.isNaN() || ask.isNaN()) null else (bid + ask) / 2.0
        } catch (e: Exception) {
            Log.d(Log.CAT_FEED, "Swissquote پاسخ نداد", e.message ?: "")
            null
        }
    }

    /** bid/ask اولین پروفایل پاسخ Swissquote؛ null اگر ساختار پاسخ نامعتبر بود. */
    internal fun parseSwissquoteBidAsk(body: String): Pair<Double, Double>? = try {
        val arr = org.json.JSONArray(body)
        val p = arr.getJSONObject(0).getJSONArray("spreadProfilePrices").getJSONObject(0)
        val bid = p.optDouble("bid", Double.NaN)
        val ask = p.optDouble("ask", Double.NaN)
        if (bid.isNaN() || ask.isNaN()) null else bid to ask
    } catch (e: Exception) { null }

    /**
     * §۲۰ cmsn «خودکار»: اسپرد زندهٔ XAU/USD = ask − bid (واحد قیمت، دلار برای هر اونس) از Swissquote.
     * null اگر در دسترس نبود یا مقدار نامعقول بود (≤ ۰ یا > ۲۰ دلار).
     */
    fun swissquoteSpread(): Double? {
        return try {
            val body = get("https://forex-data-feed.swissquote.com/public-quotes/bboquotes/instrument/XAU/USD", 4000, 5000)
            val ba = parseSwissquoteBidAsk(body) ?: return null
            val sp = ba.second - ba.first
            if (sp.isNaN() || sp <= 0.0 || sp > 20.0) null else sp
        } catch (e: Exception) {
            Log.d(Log.CAT_FEED, "اسپرد Swissquote دریافت نشد", e.message ?: "")
            null
        }
    }

    /** نرخ طلای توکنیزه (PAXG) از CoinGecko — پشتیبان */
    fun coinGeckoSpot(): Double? {
        return try {
            val body = get("https://api.coingecko.com/api/v3/simple/price?ids=pax-gold&vs_currencies=usd")
            val p = JSONObject(body).optJSONObject("pax-gold")?.optDouble("usd", Double.NaN) ?: Double.NaN
            if (p.isNaN()) null else p
        } catch (e: Exception) {
            null
        }
    }

    /**
     * §6 سند: زنجیرهٔ منابع قیمت لحظه‌ای زنده: gold-api ← Swissquote ← CoinGecko؛ اولین پاسخ معتبر استفاده می‌شود.
     * در صورت نیامدن قیمت زنده، زنجیره در چارچوب بودجهٔ زمانی به طور مداوم تکرار می‌شود تا منبع دریافت گردد.
     */
    fun spotPrice(
        budgetMs: Long = connectTimeoutMs.toLong() + readTimeoutMs.toLong(),
        maxRounds: Int = 3
    ): Pair<Double?, String> {
        val deadline = System.currentTimeMillis() + budgetMs
        var round = 0
        while (round < maxOf(1, maxRounds)) {
            round++
            goldApiSpot()?.let {
                Log.d(Log.CAT_FEED, "قیمت لحظه‌ای از gold-api", com.alisport.goldpin.util.Fa.n(it, 2))
                return it to "gold-api"
            }
            if (System.currentTimeMillis() >= deadline) {
                Log.w(Log.CAT_FEED, "بودجهٔ قیمت لحظه‌ای تمام شد", "دور=$round")
                return null to "کند/ناموفق"
            }
            swissquoteSpot()?.let {
                Log.d(Log.CAT_FEED, "قیمت لحظه‌ای از Swissquote", com.alisport.goldpin.util.Fa.n(it, 2))
                return it to "Swissquote"
            }
            if (System.currentTimeMillis() >= deadline) {
                Log.w(Log.CAT_FEED, "بودجهٔ قیمت لحظه‌ای تمام شد", "دور=$round")
                return null to "کند/ناموفق"
            }
            coinGeckoSpot()?.let {
                Log.d(Log.CAT_FEED, "قیمت لحظه‌ای از CoinGecko", com.alisport.goldpin.util.Fa.n(it, 2))
                return it to "CoinGecko(PAXG)"
            }
            if (System.currentTimeMillis() >= deadline) {
                Log.w(Log.CAT_FEED, "بودجهٔ قیمت لحظه‌ای تمام شد", "دور=$round")
                return null to "کند/ناموفق"
            }
        }
        Log.w(Log.CAT_FEED, "هیچ منبع قیمت لحظه‌ای پاسخ نداد", "دورها=$round")
        return null to "ناموفق"
    }

    // ── انتخاب بازه بر اساس تایم‌فریم چارت ─────────────────────────────────────
    /**
     * (interval, range) مناسب Yahoo برای رسیدن به تایم‌فریم چارت.
     *
     * محدودیت‌های واقعی سرور `/v8/finance/chart` یاهو:
     *  • `1m`: حداکثر ۷ روز (`7d`)
     *  • `5m`, `15m`, `30m`: حداکثر ۶۰ روز (`60d`) — ارسال `3mo` خطای HTTP 422 می‌دهد!
     *  • `1h`: حداکثر ۷۳۰ روز (`730d`) — ارسال `2y` در سال کبیسه (۷۳۱ روز) خطای HTTP 422 می‌دهد!
     *  • `1d`, `1wk`: ارسال `range=max` دقت کندل را به `1mo` (ماهانه) تقلیل می‌دهد؛ `10y` کندل واقعی روزانه/هفتگی می‌دهد.
     */
    fun yahooSpec(chartTfSec: Int, depth: Int): Pair<String, String> {
        return when {
            chartTfSec < Tf.M5 -> "1m" to if (depth >= 3) "7d" else "5d"
            chartTfSec <= Tf.M5 -> "5m" to when {
                depth >= 3 -> "60d"; depth == 2 -> "1mo"; else -> "7d"
            }
            chartTfSec <= Tf.M15 -> "15m" to when {
                depth >= 3 -> "60d"; depth == 2 -> "1mo"; else -> "7d"
            }
            chartTfSec <= Tf.M30 -> "30m" to when {
                depth >= 3 -> "60d"; depth == 2 -> "1mo"; else -> "7d"
            }
            chartTfSec <= Tf.H1 -> "1h" to when {
                depth >= 3 -> "730d"; depth == 2 -> "1y"; else -> "3mo"
            }
            chartTfSec <= Tf.H4 -> "1h" to when {
                depth >= 3 -> "730d"; depth == 2 -> "1y"; else -> "6mo"
            }
            chartTfSec <= Tf.D1 -> "1d" to when {
                depth >= 3 -> "10y"; depth == 2 -> "5y"; else -> "1y"
            }
            else -> "1wk" to when {
                depth >= 3 -> "10y"; depth == 2 -> "5y"; else -> "2y"
            }
        }
    }

    /** تایم‌فریم پایه‌ای که از Yahoo می‌گیریم و بعد تجمیع می‌کنیم */
    fun baseTfFor(chartTfSec: Int): Int = when {
        chartTfSec < Tf.M5 -> Tf.M1      // 1m و 3m از 1m ساخته می‌شوند
        chartTfSec <= Tf.M5 -> Tf.M5
        chartTfSec <= Tf.M15 -> Tf.M15
        chartTfSec <= Tf.M30 -> Tf.M30
        chartTfSec <= Tf.H1 -> Tf.H1
        chartTfSec <= Tf.H4 -> Tf.H1
        chartTfSec <= Tf.D1 -> Tf.D1
        else -> Tf.W1
    }

    // ── تجمیع کندل‌ها ──────────────────────────────────────────────────────────
    /** تجمیع کندل‌های تایم‌فریم پایه به تایم‌فریم بالاتر (بدون ریپینت). */
    fun aggregate(src: List<Candle>, targetSec: Int): List<Candle> {
        if (src.isEmpty()) return src
        if (targetSec <= 0) return src
        val out = ArrayList<Candle>((src.size / 4) + 4)
        var curBucket = Long.MIN_VALUE
        var o = 0.0; var h = 0.0; var l = 0.0; var c = 0.0; var v = 0.0; var t = 0L
        for (cd in src) {
            // ساعت استاندارد بازار: کندل‌های تعطیلات شنبه و یکشنبه در هیچ تایم‌فریمی ساخته
            // نمی‌شوند؛ سشن یکشنبه‌شب (از ۲۲:۰۰ UTC) با باکت روزانه/هفتگی جزئی از دوشنبه است.
            if (MarketHours.isClosed(cd.t)) continue
            val b = Tf.bucket(targetSec, cd.t)
            if (b != curBucket) {
                if (curBucket != Long.MIN_VALUE) out.add(Candle(out.size, t, o, h, l, c, v))
                curBucket = b; t = b; o = cd.o; h = cd.h; l = cd.l; c = cd.c; v = cd.v
            } else {
                h = maxOf(h, cd.h); l = minOf(l, cd.l); c = cd.c; v += cd.v
            }
        }
        if (curBucket != Long.MIN_VALUE) out.add(Candle(out.size, t, o, h, l, c, v))
        if (src.size != out.size) Log.d(Log.CAT_PERF, "تجمیع کندل‌ها",
            "ورودی=${src.size} خروجی=${out.size} تایم‌فریم=${Tf.label(targetSec)}")
        return out
    }

    /** اگر فید حجم نداشت، حجم تقریبی از دامنهٔ کندل می‌سازیم (تقریبی — برای کارکرد موتور). */
    fun synthesizeVolumeIfMissing(candles: List<Candle>): List<Candle> {
        if (candles.isEmpty()) return candles
        // §6 سند: «اگر بیش از ۹۰٪ کندل‌ها حجم صفر داشته باشند» ⇒ حجم ساختگی (دقیقاً ۹۰٪ هنوز ساختگی نیست).
        val zeros = candles.count { !(it.v > 0.0) }
        val tooManyZeros = zeros.toLong() * 10L > candles.size.toLong() * 9L
        if (!tooManyZeros) return candles
        Log.w(Log.CAT_FEED, "فید حجم نداشت → ساخت حجم تقریبی از دامنهٔ کندل", "کندل=${candles.size}")
        try { syntheticVolumeWarning?.invoke(candles.size) }
        catch (t: Throwable) { Log.e(Log.CAT_FEED, "اعلام حجم ساختگی به کاربر ناموفق بود", t) }
        return candles.map { Candle(it.bi, it.t, it.o, it.h, it.l, it.c, (it.h - it.l) * 10000.0 + 1.0) }
    }

    // ── CSV ────────────────────────────────────────────────────────────────────
    /**
     * خواندن CSV کندل. ستون‌ها: timestamp,open,high,low,close[,volume]
     * سرصفحه اختیاری است. مهر زمان می‌تواند ثانیه یا میلی‌ثانیه باشد.
     */
    fun parseCsv(text: String): List<Candle> {
        val out = ArrayList<Candle>()
        var bi = 0
        var first = true
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (first) {
                first = false
                if (line.contains("open", true) || line.contains("timestamp", true)) continue
            }
            // ستون‌های داخل " " هم پذیرفته می‌شوند (خروجی برخی ابزارها CSV نقل‌قول‌دار می‌دهند)
            val p = line.split(',', ';', '\t').map { it.trim().trim('"', '\'') }
            if (p.size < 5) continue
            val t = p[0].toLongOrNull() ?: continue
            val o = p[1].toDoubleOrNull() ?: continue
            val h = p[2].toDoubleOrNull() ?: continue
            val l = p[3].toDoubleOrNull() ?: continue
            val c = p[4].toDoubleOrNull() ?: continue
            val v = if (p.size >= 6) (p[5].toDoubleOrNull() ?: 0.0) else 0.0
            val ms = if (t < 100_000_000_000L) t * 1000L else t
            out.add(Candle(bi++, ms, o, h, l, c, v))
        }
        if (out.isEmpty()) throw FeedException("فایل CSV خوانده نشد (قالب ستون‌ها را بررسی کنید)")
        out.sortBy { it.t }
        val res = out.mapIndexed { i, c -> Candle(i, c.t, c.o, c.h, c.l, c.c, c.v) }
        Log.i(Log.CAT_FEED, "CSV تجزیه شد", "کندل=${res.size} حجم‌دار=${res.any { it.v > 0 }}")
        return res
    }

    /**
     * خواندن فایل CSV از ورودی با تشخیص خودکار gzip (بر اساس بایت‌های جادویی).
     * توجه: بسته‌بندی اندروید ممکن است دارایی «.gz» را باز و با نام «.csv» ذخیره کند،
     * پس هر دو حالت پشتیبانی می‌شود.
     */
    fun readCsvStream(stream: java.io.InputStream, gzipped: Boolean = false): String {
        val buffered = java.io.BufferedInputStream(stream, 1 shl 16)
        buffered.mark(4)
        val b0 = buffered.read()
        val b1 = buffered.read()
        buffered.reset()
        val isGz = (b0 == 0x1f && b1 == 0x8b) || gzipped
        val ins = if (isGz) GZIPInputStream(buffered) else buffered
        return BufferedReader(InputStreamReader(ins, Charsets.UTF_8)).use { it.readText() }
    }

    // ── دیتاست‌های بک‌تست داخلی (طرح B) ────────────────────────────────────────
    // فایل‌های یک‌، دو، سه و ده‌ساله بیرون APK در ریپوی عمومی ST قرار دارند.
    const val BACKTEST_DATA_URL =
        "https://raw.githubusercontent.com/alisport1384/ST/main/datasets/goldpin_xauusd_m1_1y.csv.gz"
    const val BACKTEST_DATA_FALLBACK_URL =
        "https://github.com/alisport1384/ST1/releases/download/data-v1/goldpin_xauusd_m1_1y.csv.gz"
    const val BACKTEST_DATA_SHA256 = "15fffd94b9c1970080d6012ff2aa3ae307d18a872381c677e44d16b5fd08db44"
    const val BACKTEST_DATA_BARS = 351_894
    const val BACKTEST_DATA_2Y_URL =
        "https://raw.githubusercontent.com/alisport1384/ST/main/datasets/goldpin_xauusd_m1_2y.csv.gz"
    const val BACKTEST_DATA_2Y_SHA256 = "873d4ffc765723abfada8ef55fbf8b9bb9c7432125187395972f51af94496e9f"
    const val BACKTEST_DATA_2Y_BARS = 701_067
    const val BACKTEST_DATA_3Y_URL =
        "https://raw.githubusercontent.com/alisport1384/ST/main/datasets/goldpin_xauusd_m1_3y.csv.gz"
    const val BACKTEST_DATA_3Y_SHA256 = "56751049149f3df1ed134fdbc5e667ec57655f445edde5820ef9d9fed6697fcc"
    const val BACKTEST_DATA_3Y_BARS = 1_040_730
    const val BACKTEST_DATA_10Y_URL =
        "https://raw.githubusercontent.com/alisport1384/ST/main/datasets/goldpin_xauusd_m1_10y.csv.gz"
    const val BACKTEST_DATA_10Y_SHA256 = "c0e5c8d2151605db8d0cf1c75f3f1d6cca0ecc5304bad233f536ffe45d7f7881"
    const val BACKTEST_DATA_10Y_BARS = 3_402_126

    val BACKTEST_DATASETS = listOf(
        BacktestDatasetSpec(1, BACKTEST_DATA_BARS, 5_128_001L, BACKTEST_DATA_SHA256,
            BACKTEST_DATA_URL, BACKTEST_DATA_FALLBACK_URL),
        BacktestDatasetSpec(2, BACKTEST_DATA_2Y_BARS, 9_844_331L, BACKTEST_DATA_2Y_SHA256,
            BACKTEST_DATA_2Y_URL),
        BacktestDatasetSpec(3, BACKTEST_DATA_3Y_BARS, 14_310_975L, BACKTEST_DATA_3Y_SHA256,
            BACKTEST_DATA_3Y_URL),
        BacktestDatasetSpec(10, BACKTEST_DATA_10Y_BARS, 43_314_146L, BACKTEST_DATA_10Y_SHA256,
            BACKTEST_DATA_10Y_URL)
    )

    fun backtestDataset(years: Int): BacktestDatasetSpec = BACKTEST_DATASETS.firstOrNull { it.years == years }
        ?: throw FeedException("بازهٔ بک‌تست پشتیبانی نمی‌شود: $years سال")

    /**
     * خواندن CSV کندل به‌صورت **جریانی** (بدون ساخت رشتهٔ کامل).
     *
     * `readCsvStream` کل فایل ۱۸ مگابایتی را داخل یک String می‌ریخت (≈۳۶MB در حافظهٔ اندروید)
     * و بعد لیست را دوباره می‌ساخت. برای یک سال M1 این روی گوشی‌های ضعیف به OOM می‌خورد.
     * این نسخه خط‌به‌خط می‌خواند و فقط لیست نهایی را نگه می‌دارد.
     */
    fun parseCsvStream(stream: java.io.InputStream, expectedBars: Int = BACKTEST_DATA_BARS): List<Candle> {
        val buffered = java.io.BufferedInputStream(stream, 1 shl 16)
        buffered.mark(4)
        val b0 = buffered.read(); val b1 = buffered.read()
        buffered.reset()
        val isGz = b0 == 0x1f && b1 == 0x8b
        val ins = if (isGz) GZIPInputStream(buffered) else buffered
        // برای ۳ سال، حذف لیست میانی جلوی کپی هم‌زمانِ بیش از یک میلیون Candle را می‌گیرد.
        val out = ArrayList<Candle>(expectedBars.coerceIn(16, 2_000_000))
        var first = true
        var previousTime = Long.MIN_VALUE
        var monotonic = true
        BufferedReader(InputStreamReader(ins, Charsets.UTF_8), 1 shl 16).use { br ->
            while (true) {
                val raw = br.readLine() ?: break
                val line = raw.trim()
                if (line.isEmpty()) continue
                if (first) {
                    first = false
                    if (line.contains("open", true) || line.contains("timestamp", true)) continue
                }
                val p = line.split(',', ';', '\t')
                if (p.size < 5) continue
                val t = p[0].trim().toLongOrNull() ?: continue
                val o = p[1].trim().toDoubleOrNull() ?: continue
                val h = p[2].trim().toDoubleOrNull() ?: continue
                val l = p[3].trim().toDoubleOrNull() ?: continue
                val c = p[4].trim().toDoubleOrNull() ?: continue
                val v = if (p.size >= 6) (p[5].trim().toDoubleOrNull() ?: 0.0) else 0.0
                val ms = if (t < 100_000_000_000L) t * 1000L else t
                if (ms < previousTime) monotonic = false
                previousTime = ms
                out.add(Candle(out.size, ms, o, h, l, c, v))
            }
        }
        if (out.isEmpty()) throw FeedException("فایل دادهٔ بک‌تست خوانده نشد")
        if (!monotonic) out.sortBy { it.t }
        for (i in out.indices) {
            val c = out[i]
            if (c.bi != i) out[i] = Candle(i, c.t, c.o, c.h, c.l, c.c, c.v)
        }
        Log.i(Log.CAT_FEED, "دادهٔ بک‌تست خوانده شد",
            "کندل=${out.size} حجم‌دار=${out.any { it.v > 0 }} مرتب=${monotonic}")
        return out
    }

    /**
     * Parser مخصوص فایل ۱۰ساله: خروجی primitive-backed است تا ۳٫۴ میلیون کندل به
     * چندصد مگابایت شیء Candle تبدیل نشود. فایل منتشرشدهٔ این مسیر با timestamp صعودی است.
     */
    fun parseCsvPackedStream(stream: java.io.InputStream, expectedBars: Int = BACKTEST_DATA_10Y_BARS): List<Candle> {
        val buffered = java.io.BufferedInputStream(stream, 1 shl 16)
        buffered.mark(4)
        val b0 = buffered.read(); val b1 = buffered.read()
        buffered.reset()
        val ins = if (b0 == 0x1f && b1 == 0x8b) GZIPInputStream(buffered) else buffered
        var capacity = expectedBars.coerceIn(16, 5_000_000)
        var times = LongArray(capacity)
        var opens = IntArray(capacity)
        var highs = IntArray(capacity)
        var lows = IntArray(capacity)
        var closes = IntArray(capacity)
        var volumes = IntArray(capacity)
        var count = 0
        var first = true
        var previousTime = Long.MIN_VALUE
        BufferedReader(InputStreamReader(ins, Charsets.UTF_8), 1 shl 16).use { br ->
            while (true) {
                val line = br.readLine() ?: break
                if (line.isEmpty()) continue
                if (first) {
                    first = false
                    if (line.contains("open", true) || line.contains("timestamp", true)) continue
                }
                // Fast path for the fixed repository CSV schema: avoid split() and dozens
                // of short-lived String objects per row across 3.4 million candles.
                val a = line.indexOf(',')
                val b = if (a >= 0) line.indexOf(',', a + 1) else -1
                val c = if (b >= 0) line.indexOf(',', b + 1) else -1
                val d = if (c >= 0) line.indexOf(',', c + 1) else -1
                val e = if (d >= 0) line.indexOf(',', d + 1) else -1
                if (a < 0 || b < 0 || c < 0 || d < 0 || e < 0) continue
                val f = line.indexOf(',', e + 1)
                val msRaw = parseLongField(line, 0, a)
                val ms = if (msRaw < 100_000_000_000L) msRaw * 1000L else msRaw
                if (ms <= previousTime) throw FeedException("فایل ده‌ساله مرتب زمانی نیست یا timestamp تکراری دارد")
                if (count == capacity) {
                    val next = minOf(5_000_000, capacity * 2)
                    if (next <= capacity) throw FeedException("تعداد کندل‌های فایل از حد پشتیبانی‌شده بیشتر است")
                    times = times.copyOf(next); opens = opens.copyOf(next); highs = highs.copyOf(next)
                    lows = lows.copyOf(next); closes = closes.copyOf(next); volumes = volumes.copyOf(next)
                    capacity = next
                }
                try {
                    times[count] = ms
                    opens[count] = parsePriceScaled(line, a + 1, b)
                    highs[count] = parsePriceScaled(line, b + 1, c)
                    lows[count] = parsePriceScaled(line, c + 1, d)
                    closes[count] = parsePriceScaled(line, d + 1, e)
                    volumes[count] = parseRoundedInt(line, e + 1, if (f < 0) line.length else f)
                } catch (ex: Exception) {
                    throw FeedException("مقدار عددی نامعتبر در دیتاست ده‌ساله، ردیف ${count + 1}: ${ex.message}")
                }
                previousTime = ms
                count++
            }
        }
        if (count == 0) throw FeedException("فایل دادهٔ بک‌تست خوانده نشد")
        if (count != expectedBars) Log.w(Log.CAT_FEED, "تعداد کندل با متادیتای ده‌ساله فرق دارد",
            "انتظار=$expectedBars واقعی=$count")
        Log.i(Log.CAT_FEED, "دیتاست ده‌سالهٔ فشرده در حافظه بارگذاری شد",
            "کندل=$count بایت‌دادهٔ فشرده‌شده‌در‌حافظه=${count.toLong() * 28L}")
        return PackedCandleList(times, opens, highs, lows, closes, volumes, count)
    }

    private fun parseLongField(s: String, start: Int, end: Int): Long {
        if (start >= end) throw NumberFormatException("empty long")
        var i = start
        var sign = 1L
        if (s[i] == '-') { sign = -1L; i++ } else if (s[i] == '+') i++
        var value = 0L
        while (i < end) {
            val d = s[i++] - '0'
            if (d !in 0..9) throw NumberFormatException("invalid timestamp")
            value = value * 10L + d
        }
        return value * sign
    }

    private fun parsePriceScaled(s: String, start: Int, end: Int): Int {
        if (start >= end) throw NumberFormatException("empty price")
        var i = start
        var sign = 1L
        if (s[i] == '-') { sign = -1L; i++ } else if (s[i] == '+') i++
        var whole = 0L
        var fraction = 0L
        var fractionDigits = 0
        var afterDot = false
        var roundDigit = -1
        while (i < end) {
            val ch = s[i++]
            if (ch == '.' && !afterDot) { afterDot = true; continue }
            val digit = ch - '0'
            if (digit !in 0..9) throw NumberFormatException("invalid price")
            if (!afterDot) whole = whole * 10L + digit
            else if (fractionDigits < 5) { fraction = fraction * 10L + digit; fractionDigits++ }
            else if (roundDigit < 0) roundDigit = digit
        }
        while (fractionDigits < 5) { fraction *= 10L; fractionDigits++ }
        var scaled = whole * 100_000L + fraction
        if (roundDigit >= 5) scaled++
        scaled *= sign
        if (scaled !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) throw NumberFormatException("price overflow")
        return scaled.toInt()
    }

    private fun parseRoundedInt(s: String, start: Int, end: Int): Int {
        if (start >= end) return 0
        var i = start
        var sign = 1L
        if (s[i] == '-') { sign = -1L; i++ } else if (s[i] == '+') i++
        var value = 0L
        var afterDot = false
        var firstDecimal = -1
        while (i < end) {
            val ch = s[i++]
            if (ch == '.' && !afterDot) { afterDot = true; continue }
            val digit = ch - '0'
            if (digit !in 0..9) throw NumberFormatException("invalid volume")
            if (!afterDot) value = value * 10L + digit else if (firstDecimal < 0) firstDecimal = digit
        }
        if (firstDecimal >= 5) value++
        value *= sign
        if (value !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) throw NumberFormatException("volume overflow")
        return value.toInt()
    }

    fun parseBacktestStream(stream: java.io.InputStream, spec: BacktestDatasetSpec): List<Candle> =
        if (spec.years == 10) parseCsvPackedStream(stream, spec.bars) else parseCsvStream(stream, spec.bars)

    /** دانلود فایل دادهٔ بک‌تست با گزارش پیشرفت (بایت دریافتی از کل). */
    fun downloadTo(urlStr: String, dest: java.io.File, onProgress: (Long, Long) -> Unit = { _, _ -> }) {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", UA)
            setRequestProperty("Accept", "application/gzip,application/octet-stream,*/*")
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw FeedException("HTTP $code از سرور دادهٔ بک‌تست")
            val total = conn.contentLengthLong
            conn.inputStream.use { input ->
                java.io.BufferedOutputStream(dest.outputStream(), 1 shl 16).use { output ->
                    val buf = ByteArray(1 shl 16)
                    var got = 0L
                    var lastReport = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        output.write(buf, 0, n)
                        got += n
                        val now = System.currentTimeMillis()
                        if (now - lastReport > 250) { lastReport = now; onProgress(got, total) }
                    }
                    onProgress(got, total)
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    /** هش SHA-256 یک فایل (برای اعتبارسنجی دادهٔ دانلودشده). */
    fun sha256(file: java.io.File): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        java.io.BufferedInputStream(file.inputStream(), 1 shl 16).use { ins ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = ins.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        val sb = StringBuilder()
        for (b in md.digest()) sb.append(String.format("%02x", b))
        return sb.toString()
    }

    /** باز کردن دارایی نمونهٔ داخلی (هر نامی که بسته‌بندی برایش انتخاب کرده باشد) */
    fun openSampleAsset(ctx: android.content.Context): java.io.InputStream {
        val names = listOf("sample_xauusd_m1.csv.gz", "sample_xauusd_m1.csv")
        for (n in names) {
            try { return ctx.assets.open(n) } catch (e: Exception) { }
        }
        throw FeedException("دارایی نمونهٔ داخلی پیدا نشد")
    }

    /** علامت خالی بودن دادهٔ زنده */
    fun isStale(candles: List<Candle>, chartTfSec: Int, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (candles.isEmpty()) return true
        val last = candles.last().t
        // بازار طلا آخر هفته تعطیل است → ۳ روز مهلت
        return nowMs - last > 3L * 86400_000L
    }
}
