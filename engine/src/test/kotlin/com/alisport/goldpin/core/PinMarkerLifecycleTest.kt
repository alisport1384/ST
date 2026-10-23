package com.alisport.goldpin.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * چرخهٔ عمر مارکر پین روی چارت — §18-4 سند نهایی:
 * «IC، Ready، pin که به‌خاطر توالی اجازه ندارند یا به‌خاطر قوانین باطل شدند کاملاً دور ریخته می‌شوند».
 *
 * رگرسیون عکس مالک (GC=F · 1H، پنجرهٔ ۲۰۲۶-۰۹-۲۸ ۱۹:۰۰ تا ۲۰۲۶-۰۹-۳۰ ۰۶:۰۰ UTC):
 * سه مارکر «کندل مهم بالاترین» پشت‌سرهم روی چارت دیده می‌شد، چون دو pinBE اول طبق §4-1
 * باطل شده بودند (کلوز بالای سطح ابطال) و وضعیتشان در موتور پاک شده بود، ولی مارکرشان
 * برای همیشه روی چارت می‌ماند و توالی §18-1/§18-3 در ظاهر شکسته دیده می‌شد.
 */
class PinMarkerLifecycleTest {

    private fun cfgS1() = Settings().apply { tfS = Tf.M1; tfM = Tf.M1; tf1 = Tf.M1; tf2 = Tf.M1 }

    /** هر کندل ورودی = یک کندل ساختار (M1)؛ کندل ساختار با رسیدن کندل بعدی بسته می‌شود. */
    private fun bars(vararg ohlc: DoubleArray): List<Candle> {
        val t0 = 1_700_000_040_000L
        return ohlc.mapIndexed { i, b -> Candle(i, t0 + i * 60_000L, b[0], b[1], b[2], b[3], 100.0) }
    }

    private fun pinMarkers(e: Engine): List<Pair<Int, Double>> =
        e.markers.filter { it.kind == MarkerKind.PIN_BU || it.kind == MarkerKind.PIN_BE }
            .map { it.kind to it.price }

    @Test
    fun `doc 18-4 invalidated pinBE marker is discarded and next pinBE does not duplicate on chart`() {
        val e = Engine(cfgS1())
        e.runAll(bars(
            doubleArrayOf(100.0, 101.0, 90.0, 95.0),    // 0 نزولی (a) — جفت پایین‌ترین‌ها
            doubleArrayOf(95.0, 102.0, 94.0, 100.0),    // 1 صعودی (b) → کاندید BU، IC=کندل0 (کف 90)
            doubleArrayOf(100.0, 110.0, 99.0, 105.0),   // 2 کلوز 105 > سقف IC (101) → ReadyBU @90
            doubleArrayOf(105.0, 115.0, 104.0, 112.0),  // 3 صعودی (a) — جفت بالاترین‌ها
            doubleArrayOf(112.0, 116.0, 108.0, 109.0),  // 4 نزولی (b) → کاندید BE، IC=کندل4 (سقف 116)، ref=112
            doubleArrayOf(109.0, 110.0, 100.0, 102.0),  // 5 کلوز 102 < کف IC (108) → ReadyBE @116
            doubleArrayOf(102.0, 114.0, 101.0, 113.0),  // 6 کلوز 113 > سطح ابطال 112 (و زیر سقف tempbox) → pinBE باطل
            doubleArrayOf(113.0, 120.0, 112.0, 118.0),  // 7 صعودی (a) — جفت بالاترین‌های بعدی
            doubleArrayOf(118.0, 121.0, 114.0, 115.0),  // 8 نزولی (b) → کاندید BE، IC=کندل8 (سقف 121)
            doubleArrayOf(115.0, 116.0, 110.0, 111.0),  // 9 کلوز 111 < کف IC (114) → ReadyBE @121
            doubleArrayOf(111.0, 112.0, 110.0, 111.5),  // 10 دنباله (بستن کندل 9)
            doubleArrayOf(111.5, 112.0, 110.5, 111.8)   // 11 دنباله
        ))
        // pinBE باطل‌شده (@116) نباید هیچ مارکری داشته باشد؛ فقط پین‌های زنده با توالی متناوب می‌مانند
        assertEquals(
            listOf(MarkerKind.PIN_BU to 90.0, MarkerKind.PIN_BE to 121.0), pinMarkers(e),
            "مارکر pinBE باطل‌شده (@116) باید کاملاً دور ریخته شود (§18-4)؛ وگرنه دو «کندل مهم بالاترین» پشت‌سرهم دیده می‌شود"
        )
        assertTrue(
            e.markers.any { it.kind == MarkerKind.INVALIDATION && it.price == 116.0 },
            "مارکر رخداد «pinBE بی‌اعتبار شد» سر جایش می‌ماند تا دلیل حذف روی چارت معلوم باشد"
        )
        assertTrue(e.beActive && e.lastBEpin == 121.0 && e.buActive && e.lastBUpin == 90.0)
    }

    @Test
    fun `doc 18-4 invalidated pinBU marker is discarded (mirror)`() {
        val e = Engine(cfgS1())
        // قرینهٔ کامل تست قبل (قیمت‌ها = 300 − قیمت تست قبل، جهت‌ها برعکس)
        e.runAll(bars(
            doubleArrayOf(200.0, 210.0, 199.0, 205.0),
            doubleArrayOf(205.0, 206.0, 198.0, 200.0),
            doubleArrayOf(200.0, 201.0, 190.0, 195.0),
            doubleArrayOf(195.0, 196.0, 185.0, 188.0),
            doubleArrayOf(188.0, 192.0, 184.0, 191.0),
            doubleArrayOf(191.0, 200.0, 190.0, 198.0),
            doubleArrayOf(198.0, 199.0, 186.0, 187.0),
            doubleArrayOf(187.0, 188.0, 180.0, 182.0),
            doubleArrayOf(182.0, 186.0, 179.0, 185.0),
            doubleArrayOf(185.0, 190.0, 184.0, 189.0),
            doubleArrayOf(189.0, 190.0, 188.0, 188.5),
            doubleArrayOf(188.5, 189.5, 188.0, 188.2)
        ))
        assertEquals(
            listOf(MarkerKind.PIN_BE to 210.0, MarkerKind.PIN_BU to 179.0), pinMarkers(e),
            "مارکر pinBU باطل‌شده (@184) باید کاملاً دور ریخته شود (§18-4)"
        )
        assertTrue(e.markers.any { it.kind == MarkerKind.INVALIDATION && it.price == 184.0 })
        assertTrue(e.buActive && e.lastBUpin == 179.0 && e.beActive && e.lastBEpin == 210.0)
    }

    @Test
    fun `tempbox pin consumed by trend break loses its marker base pin keeps it`() {
        val e = Engine(cfgS1())
        e.runAll(bars(
            doubleArrayOf(100.0, 101.0, 90.0, 95.0),    // 0
            doubleArrayOf(95.0, 102.0, 94.0, 100.0),    // 1 → کاندید BU
            doubleArrayOf(100.0, 110.0, 99.0, 105.0),   // 2 → ReadyBU @90 (عدد 1)
            doubleArrayOf(105.0, 115.0, 104.0, 112.0),  // 3
            doubleArrayOf(112.0, 116.0, 108.0, 109.0),  // 4 → کاندید BE
            doubleArrayOf(109.0, 110.0, 100.0, 102.0),  // 5 → ReadyBE @116 (عدد 2) ⇒ tempbox [90,116]
            doubleArrayOf(102.0, 125.0, 101.0, 120.0),  // 6 کلوز 120 > سقف tempbox → روند صعودی؛ tempbox و pinBE موقت پاک
            doubleArrayOf(120.0, 122.0, 118.0, 121.0),  // 7 دنباله
            doubleArrayOf(121.0, 122.0, 119.0, 120.5)   // 8 دنباله
        ))
        assertEquals(1, e.trend, "کلوز بالای سقف tempbox روند صعودی را تثبیت می‌کند")
        assertEquals(
            listOf(MarkerKind.PIN_BU to 90.0), pinMarkers(e),
            "pinBU پایهٔ HL بعدی می‌ماند ولی مارکر pinBE موقتِ مصرف‌شده باید پاک شود (§4-1: بعد از تعیین روند tempbox پاک می‌شود)"
        )
        assertEquals(-1, e.expectedReady, "پین بعدی طبق توالی pinBE (همان HH آینده) است")
        assertTrue(e.buActive && !e.beActive)
    }

    @Test
    fun `HH break reverts pins and keeps pin markers only struct labels are removed`() {
        val e = Engine(cfgS1())
        e.trend = 1; e.HH = 110.0; e.HL = 100.0
        e.hhPinHi = 110.0; e.hhPinRef = 104.0; e.hhPinLo = 102.0; e.hhPinSeq = 2
        e.hlPinLo = 100.0; e.hlPinRef = 96.0; e.hlPinHi = 103.0; e.hlPinSeq = 1
        e.lastBEpin = 110.0; e.lastBEpinRef = 104.0; e.lastBEpinLo = 102.0
        e.beActive = true; e.beSeq = 2
        e.lastBUpin = 100.0; e.lastBUpinRef = 96.0; e.lastBUpinHi = 103.0
        e.buActive = true; e.buSeq = 1
        e.expectedReady = -1
        val t0 = 1_700_000_040_000L
        e.markers.add(Marker(t0 - 120_000L, -2, 100.0, MarkerKind.PIN_BU, "کندل مهم پایین‌ترین\nReady BU · pinBU"))
        e.markers.add(Marker(t0 - 60_000L, -1, 110.0, MarkerKind.PIN_BE, "کندل مهم بالاترین\nReady BE · pinBE"))
        e.markers.add(Marker(t0 - 60_000L, -1, 110.0, MarkerKind.STRUCT, "HH"))
        e.markers.add(Marker(t0 - 120_000L, -2, 100.0, MarkerKind.STRUCT, "HL"))
        e.feed(listOf(
            Candle(0, t0, 108.0, 112.0, 107.0, 111.0, 100.0),
            Candle(1, t0 + 60_000L, 111.0, 111.5, 110.5, 111.2, 100.0),
            Candle(2, t0 + 120_000L, 111.2, 111.4, 111.0, 111.3, 100.0)
        ), lastIsClosed = false)
        assertTrue(e.markers.none { it.kind == MarkerKind.STRUCT }, "برچسب HH/HL برداشته می‌شود")
        assertEquals(
            listOf(MarkerKind.PIN_BU to 100.0, MarkerKind.PIN_BE to 110.0), pinMarkers(e),
            "در شکست HH پین‌ها «برمی‌گردند» (HH→pinBE و HL→pinBU) و باطل نشده‌اند؛ مارکرشان نباید پاک شود"
        )
    }

    @Test
    fun `owner photo window GC 1H 2026-09-28 dead pinBE markers at 4171-80 and 4207-20 are gone`() {
        // کندل‌های واقعی GC=F ‏1H یاهو — همان ۳۴ کندلِ عکس (UTC ‏2026-09-28 19:00 … 2026-09-30 06:00)
        // به‌علاوهٔ دو کندل دنباله برای بسته‌شدن کندل‌های ساختار آخر.
        val rows = arrayOf(
            doubleArrayOf(4172.2, 4172.4, 4155.2, 4156.3), // win0
            doubleArrayOf(4156.1, 4156.9, 4144.2, 4148.5), // win1  ▼ pinBU (IC)
            doubleArrayOf(4150.2, 4159.2, 4147.8, 4154.1), // win2
            doubleArrayOf(4154.0, 4161.4, 4153.5, 4159.6), // win3  → ReadyBU
            doubleArrayOf(4159.9, 4160.3, 4146.2, 4150.0), // win4
            doubleArrayOf(4149.4, 4168.6, 4145.2, 4166.9), // win5
            doubleArrayOf(4167.1, 4170.7, 4157.8, 4168.7), // win6
            doubleArrayOf(4169.1, 4171.8, 4162.8, 4164.1), // win7  ▲ pinBE (IC)
            doubleArrayOf(4165.1, 4165.6, 4155.9, 4158.2), // win8  → ReadyBE @4171.8
            doubleArrayOf(4157.9, 4176.2, 4153.1, 4169.9), // win9  کلوز 4169.9 > ابطال 4169.1 → pinBE باطل
            doubleArrayOf(4170.1, 4179.5, 4169.8, 4171.7), // win10
            doubleArrayOf(4171.8, 4180.9, 4167.4, 4172.4), // win11
            doubleArrayOf(4172.9, 4178.3, 4169.2, 4171.6), // win12
            doubleArrayOf(4171.0, 4181.2, 4170.7, 4178.0), // win13
            doubleArrayOf(4178.0, 4186.8, 4175.4, 4185.6), // win14
            doubleArrayOf(4185.8, 4193.3, 4181.5, 4185.7), // win15
            doubleArrayOf(4185.8, 4190.9, 4180.6, 4183.0), // win16
            doubleArrayOf(4183.0, 4196.8, 4182.5, 4189.8), // win17
            doubleArrayOf(4190.0, 4207.2, 4188.5, 4202.0), // win18 ▲ pinBE (IC)
            doubleArrayOf(4202.2, 4202.2, 4180.0, 4190.3), // win19
            doubleArrayOf(4190.5, 4190.9, 4176.7, 4177.8), // win20 → ReadyBE @4207.2
            doubleArrayOf(4177.8, 4181.2, 4174.4, 4176.3), // win21
            doubleArrayOf(4176.9, 4209.0, 4176.5, 4203.9), // win22 کلوز 4203.9 > ابطال 4202.2 → pinBE باطل
            doubleArrayOf(4203.7, 4210.1, 4200.4, 4210.0), // win23
            doubleArrayOf(4209.8, 4218.1, 4204.2, 4215.0), // win24
            doubleArrayOf(4216.1, 4217.3, 4209.2, 4211.3), // win25
            doubleArrayOf(4211.5, 4219.7, 4211.0, 4216.8), // win26 ▲ pinBE (IC)
            doubleArrayOf(4216.7, 4219.5, 4208.2, 4208.2), // win27
            doubleArrayOf(4208.0, 4213.3, 4198.6, 4204.1), // win28 → ReadyBE @4219.7 · ▼ pinBU (IC)
            doubleArrayOf(4205.4, 4216.0, 4202.7, 4210.9), // win29
            doubleArrayOf(4211.0, 4214.2, 4203.2, 4205.5), // win30
            doubleArrayOf(4204.7, 4217.4, 4204.3, 4211.4), // win31
            doubleArrayOf(4211.0, 4214.8, 4197.6, 4210.0), // win32
            doubleArrayOf(4209.8, 4227.0, 4208.3, 4222.6), // win33 → ReadyBU @4198.6
            doubleArrayOf(4222.6, 4233.2, 4221.6, 4225.5), // دنباله
            doubleArrayOf(4225.3, 4234.0, 4222.8, 4223.4)  // دنباله
        )
        val e = Engine(cfgS1())
        e.runAll(bars(*rows))
        val pins = pinMarkers(e)
        // pinBU اول (@4144.2) با Ready شدن pinBU بعدی (@4198.6 در win33) جایگزین شد و
        // pinBE میان آن دو (@4219.7) هم با تثبیت روند مصرف شد؛ طبق §18-4 پینِ ازیادرفته
        // کاملاً دور ریخته می‌شود وگرنه دو «کندل مهم پایین‌ترین» پشت‌سرهم می‌ماند
        // (عکس‌های بعدی مالک). پس فقط pinBU زندهٔ فعلی باید روی چارت باشد.
        assertEquals(
            listOf(MarkerKind.PIN_BU to 4198.6), pins,
            "فقط pinBU زنده (@4198.6) باید بماند؛ پین‌های مرده/جایگزین‌شده دور ریخته می‌شوند (§18-4): $pins"
        )
        assertFalse(
            pins.any { it.first == MarkerKind.PIN_BE && it.second == 4171.8 },
            "pinBE اول در win9 باطل شده؛ مارکرش نباید روی چارت بماند (§18-4): $pins"
        )
        assertFalse(
            pins.any { it.first == MarkerKind.PIN_BE && it.second == 4207.2 },
            "pinBE دوم در win22 باطل شده؛ مارکرش نباید روی چارت بماند (§18-4): $pins"
        )
        // هیچ دو مارکرِ «بالاترین» پشت‌سرهم در بازه نیست (شکایت عکس)
        val winBe = pins.filter { it.first == MarkerKind.PIN_BE && it.second in 4171.8..4207.2 }
        assertTrue(winBe.isEmpty(), "مارکر بالاترینِ مرده نباید بماند: $winBe")
    }
}
