package com.alisport.goldpin.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SpecRevTest {

    @Test
    fun `document defaults and money manager naked capital doubling`() {
        val c = Settings()
        assertEquals(500.0, c.maxLeverage)
        assertEquals(1.0, c.entryBufTicks)
        assertEquals(1, c.maxSetups)
        assertEquals(3.0, c.maxSlUnits, "§18-31 سند: N پیش‌فرض ۳ است")
        // پیش‌فرض‌های نسخهٔ ۷ (سند ۷، §۵-۴) — این‌ها نباید بی‌صدا عوض شوند
        assertEquals(true, c.trigger2Fade, "نسخهٔ ۷: تریگر ۲ معکوس پیش‌فرض روشن است")
        assertEquals(4.0, c.trigger2TargetR, "نسخهٔ ۷: هدف پیش‌فرض ۴R")
        assertEquals(6.0, c.minSlCostMult, "نسخهٔ ۷: فیلتر کارمزد ۶")
        assertEquals(false, c.minLotAuto, "نسخهٔ ۷: MinLot خاموش")
        assertEquals(10.0, c.minLotManual, "نسخهٔ ۷: مقدار دستی ۱۰")
        assertEquals(false, c.lostResetAtFinalTp, "نسخهٔ ۷: lost با حد سود نهایی صفر نمی‌شود")
        assertEquals(0, c.vpSmooth, "§۴-۵ ویرایش جدید: هموارسازی پیش‌فرض ۰")
        assertEquals(VolumeProfile.DIST_UNIFORM, c.distMode, "§۴-۵ ویرایش جدید: مدل پخش حجم پیش‌فرض یکنواخت")

        val mmCfg = Settings().apply { mmMode = MoneyMode.MIXED; initialEquity = 1000.0 }
        val mm = MoneyManager(mmCfg)
        mm.onCapitalUpdated(1999.0); assertEquals(1000.0, mm.nakedCapital())
        mm.onCapitalUpdated(2000.0); assertEquals(2000.0, mm.nakedCapital())
        mm.onCapitalUpdated(8000.0); assertEquals(8000.0, mm.nakedCapital())
        mm.reset(); assertEquals(1000.0, mm.nakedCapital())
        mmCfg.mmMode = MoneyMode.MANAGE_ALL_LOST
        mm.onCapitalUpdated(5000.0); assertEquals(1000.0, mm.nakedCapital())
    }

    @Test
    fun `spec rev 7 upgrades untouched trading-mode defaults and keeps custom ones`() {
        // فایل نسخهٔ ۶ با پیش‌فرض‌های قدیمی (معکوس خاموش / هدف ۰ / فیلتر ۰) ⇒ پیش‌فرض‌های سند ۷.
        // بافر حد ضرر عمداً سفارشی (۵۰ تیک) است تا مهاجرت نسخهٔ ۹ اجرا **نشود**: آن مهاجرت فقط
        // فایل‌هایی را می‌برد که تمام پیش‌فرض‌های سند ۷ دست‌نخورده باشد. پس اینجا نتیجهٔ خالصِ
        // مهاجرت نسخهٔ ۷ دیده می‌شود (پوشش نسخهٔ ۹ در آزمون جداگانهٔ خودش است).
        val old = Settings().apply {
            trigger2Fade = false; trigger2TargetR = 0.0; minSlCostMult = 0.0; slBufTicks = 50.0
        }
        val oldJson = Store.save(old, Engine(old), PaperBroker(old), emptyList())
            .replace("\"specRev\":${Store.SPEC_REV}", "\"specRev\":6")
        val migrated = Store.load(oldJson).cfg
        assertTrue(migrated.trigger2Fade, "سند ۷: تریگر ۲ معکوس پیش‌فرض روشن است")
        assertEquals(4.0, migrated.trigger2TargetR, "سند ۷: هدف پیش‌فرض ۴R")
        assertEquals(6.0, migrated.minSlCostMult, "سند ۷: فیلتر کارمزد ۶")
        assertEquals(50.0, migrated.slBufTicks, 1e-9, "بافر سفارشی کاربر دست‌نخورده می‌ماند")

        // اگر کاربر حتی یکی از این سه را دست‌کاری کرده باشد، هیچ‌کدام عوض نمی‌شود
        val custom = Settings().apply { trigger2Fade = false; trigger2TargetR = 3.0; minSlCostMult = 0.0 }
        val customJson = Store.save(custom, Engine(custom), PaperBroker(custom), emptyList())
            .replace("\"specRev\":${Store.SPEC_REV}", "\"specRev\":6")
        val kept = Store.load(customJson).cfg
        assertFalse(kept.trigger2Fade, "انتخاب کاربر نباید عوض شود")
        assertEquals(3.0, kept.trigger2TargetR, 1e-9)
        assertEquals(0.0, kept.minSlCostMult, 1e-9)

        // پروندهٔ تازه (specRev جاری) دست‌نخورده می‌ماند
        val fresh = Store.save(Settings(), Engine(Settings()), PaperBroker(Settings()), emptyList())
        assertTrue(Store.load(fresh).cfg.trigger2Fade)
    }

    @Test
    fun `spec rev 9 tunes untouched doc 7 defaults with the data-verified optimum`() {
        // فایل نسخهٔ ۷ با **تمام** پیش‌فرض‌های دست‌نخوردهٔ سند ۷ ⇒ تنظیم آزموده‌شده با داده.
        // دلیل: با کارمزد ۰٫۷، همان پیش‌فرض روی دادهٔ یک‌سالهٔ خود اپ صفر سفارش می‌داد
        // (آستانهٔ فیلتر ۴٫۲ > سقف N=۳) و حد ضرر ۲ تیکی هم داخل نویز بود.
        val doc7 = Settings()
        val json = Store.save(doc7, Engine(doc7), PaperBroker(doc7), emptyList())
            .replace("\"specRev\":${Store.SPEC_REV}", "\"specRev\":7")
        val m = Store.load(json).cfg
        assertFalse(m.trigger2Fade, "تنظیم آزموده‌شده: هم‌جهت با ساختار")
        assertEquals(7.0, m.trigger2TargetR, 1e-9)
        assertEquals(200.0, m.slBufTicks, 1e-9, "بافر حد ضرر باید از نویز بیرون بیاید")
        assertEquals(-2.0, m.entryBufTicks, 1e-9)
        assertEquals(10.0, m.maxSlUnits, 1e-9, "سقف N باید از فاصلهٔ جدید حد ضرر بزرگ‌تر باشد")
        assertEquals(0.0, m.minSlCostMult, 1e-9, "فیلتر کارمزد خاموش: خودش مانع صفر‌سفارشی بود")
        assertEquals(0.7, m.cmsnManual, 1e-9, "هیچ مبنای کارمزدی در فایل نبود ⇒ مرجع آزموده‌شده")
        assertFalse(m.cmsnAuto)

        // زنجیرهٔ کامل: فایل نسخهٔ ۶ هم اول به سند ۷ می‌رسد و بعد به همین تنظیم.
        val v6 = Settings().apply { trigger2Fade = false; trigger2TargetR = 0.0; minSlCostMult = 0.0 }
        val j6 = Store.save(v6, Engine(v6), PaperBroker(v6), emptyList())
            .replace("\"specRev\":${Store.SPEC_REV}", "\"specRev\":6")
        val m6 = Store.load(j6).cfg
        assertEquals(7.0, m6.trigger2TargetR, 1e-9)
        assertEquals(200.0, m6.slBufTicks, 1e-9)

        // عدد دستی کاربر بر مرجع ۰٫۷ اولویت دارد (هندسه تنظیم می‌شود، کارمزد دست نمی‌خورد).
        val withCmsn = Settings().apply { cmsnManual = 0.35 }
        val jc = Store.save(withCmsn, Engine(withCmsn), PaperBroker(withCmsn), emptyList())
            .replace("\"specRev\":${Store.SPEC_REV}", "\"specRev\":7")
        val mc = Store.load(jc).cfg
        assertEquals(0.35, mc.cmsnManual, 1e-9, "کارمزد واقعی کاربر نباید با حدس عوض شود")
        assertEquals(200.0, mc.slBufTicks, 1e-9)

        // اسپرد خودکارِ معقولِ بروکر هم دست‌نخورده می‌ماند.
        val auto = Settings().apply { cmsnAuto = true; cmsnAutoValue = 0.18 }
        val ja = Store.save(auto, Engine(auto), PaperBroker(auto), emptyList())
            .replace("\"specRev\":${Store.SPEC_REV}", "\"specRev\":7")
        val ma = Store.load(ja).cfg
        assertTrue(ma.cmsnAuto, "اسپرد معقول خودکار نباید خاموش شود")
        assertEquals(0.18, ma.cmsnAutoValue, 1e-9)
        assertEquals(0.0, ma.cmsnManual, 1e-9)

        // اگر کاربر حتی یکی از کلیدهای هندسی را دست‌کاری کرده باشد، هیچ‌چیز عوض نمی‌شود.
        val custom = Settings().apply { slBufTicks = 50.0 }
        val jx = Store.save(custom, Engine(custom), PaperBroker(custom), emptyList())
            .replace("\"specRev\":${Store.SPEC_REV}", "\"specRev\":7")
        val mx = Store.load(jx).cfg
        assertEquals(50.0, mx.slBufTicks, 1e-9, "انتخاب کاربر باید بماند")
        assertTrue(mx.trigger2Fade, "بقیهٔ پیش‌فرض‌های سند ۷ هم دست‌نخورده می‌مانند")
        assertEquals(4.0, mx.trigger2TargetR, 1e-9)
        assertEquals(3.0, mx.maxSlUnits, 1e-9)

        // پروندهٔ تازه (specRev جاری) هیچ مهاجرتی نمی‌بیند.
        val fresh = Store.save(Settings(), Engine(Settings()), PaperBroker(Settings()), emptyList())
        val mf = Store.load(fresh).cfg
        assertTrue(mf.trigger2Fade)
        assertEquals(2.0, mf.slBufTicks, 1e-9)
    }

    @Test
    fun `spec rev 8 demotes a stored retail broker spread that would block every order`() {
        // سناریوی لاگ واقعی کاربر (SM-J810F، مهر ۱۴۰۵): مود Mixed/ManageAllLost روشن ⇒ اپ اسپرد
        // Swissquote را ۰٫۶۶ گرفت و ذخیره کرد ⇒ با فیلتر ۶× آستانهٔ ۳٫۹۶ ساخته شد و **۲۱۷ سفارش
        // از ۲۱۷** رد شد (میانهٔ فاصلهٔ حد ضرر ۱٫۹۸). نتیجه: «کل معاملات صفر».
        val old = Settings().apply {
            cmsnAuto = true; cmsnAutoValue = 0.66; cmsnManual = 0.0; minSlCostMult = 6.0
        }
        val json = Store.save(old, Engine(old), PaperBroker(old), emptyList())
            .replace("\"specRev\":${Store.SPEC_REV}", "\"specRev\":7")
        val migrated = Store.load(json).cfg
        assertFalse(migrated.cmsnAuto, "اسپرد خودکارِ بزرگ نباید مبنای فیلتر بماند")
        assertEquals(0.20, migrated.cmsnManual, 1e-9, "مبنا: عدد مرجع سند برای طلا")
        assertEquals(0.66, migrated.cmsnMigratedFromAuto, 1e-9, "مقدار قبلی برای اطلاع‌دادن نگه داشته می‌شود")
        // با ضریب ۶ و هزینهٔ ۰٫۲۰ آستانه ۱٫۲۰ می‌شود ⇒ بخش زیادی از سفارش‌ها مجاز می‌مانند.
        assertEquals(1.20, 6.0 * migrated.cmsnManual, 1e-9)

        // عدد دستیِ کاربر هرگز با مهاجرت عوض نمی‌شود
        val manual = Settings().apply {
            cmsnAuto = true; cmsnAutoValue = 0.66; cmsnManual = 0.35; minSlCostMult = 6.0
        }
        val json2 = Store.save(manual, Engine(manual), PaperBroker(manual), emptyList())
            .replace("\"specRev\":${Store.SPEC_REV}", "\"specRev\":7")
        val m2 = Store.load(json2).cfg
        assertFalse(m2.cmsnAuto)
        assertEquals(0.35, m2.cmsnManual, 1e-9, "مقدار دستی کاربر باید حفظ شود")

        // اسپرد کوچک/معقول خودکار دست‌نخورده می‌ماند (زیر آستانهٔ ۰٫۵)
        val small = Settings().apply { cmsnAuto = true; cmsnAutoValue = 0.18; minSlCostMult = 6.0 }
        val json3 = Store.save(small, Engine(small), PaperBroker(small), emptyList())
            .replace("\"specRev\":${Store.SPEC_REV}", "\"specRev\":7")
        val m3 = Store.load(json3).cfg
        assertTrue(m3.cmsnAuto, "اسپرد معقول خودکار نباید خاموش شود")
        assertEquals(0.18, m3.cmsnAutoValue, 1e-9)

        // فایل تازه (پیش‌فرض ۱٫۴٫۴۰): خودکار خاموش است ⇒ فیلتر فقط با عدد دستی کار می‌کند
        val fresh = Settings()
        assertFalse(fresh.cmsnAuto, "پیش‌فرض تازه: بدون اسپرد خودکار (§۵: اپ بدون کارمزد)")
    }

    @Test
    fun `doc 18-32 fib anchor uses the trend-extreme pin rather than cleared HH or LL label`() {
        val e = Engine(Settings())
        e.trend = 1
        e.HH = Double.NaN // برچسب HH ممکن است هنگام شکست به NaN برگردد، اما لنگر روند باقی است.
        e.highestBE = 2_150.0
        assertEquals(2_150.0, e.fibAnchor(bull = true), 1e-9)
        e.trend = -1
        e.LL = Double.NaN
        e.lowestBUpin = 1_850.0
        assertEquals(1_850.0, e.fibAnchor(bull = false), 1e-9)
    }

    @Test
    fun `old save files migrate to document defaults and plateau peak builds zone`() {
        // trigger2Fade=false عمداً: تا مهاجرت نسخهٔ ۹ (که فقط پیش‌فرض‌های دست‌نخوردهٔ سند ۷ را
        // به تنظیم آزموده‌شده می‌برد) اجرا نشود و نتیجهٔ خالص مهاجرت‌های اهرم/بافر/N دیده شود.
        val c = Settings().apply {
            maxLeverage = 10.0; entryBufTicks = 2.0; maxSetups = 6; trigger2Fade = false
        }
        val e = Engine(c); val b = PaperBroker(c); e.broker = b; b.engine = e
        val j = Store.save(c, e, b, listOf(Candle(0, 28_800_000L, 1.0, 2.0, 0.5, 1.5, 10.0)))
        val old = j.replace("\"specRev\"", "\"zz_specRev\"")
        val l = Store.load(old).cfg
        assertEquals(500.0, l.maxLeverage); assertEquals(1.0, l.entryBufTicks); assertEquals(1, l.maxSetups)
        assertEquals(10.0, Store.load(j).cfg.maxLeverage)

        // مهاجرت N: پیش‌فرض سند در نسخهٔ ۱.۴.۳۱ برابر ۲۰ بود و اکنون به ۳ برگشته است.
        //  • فایلی که مقدار ۲۰ دارد (پیش‌فرض نسخهٔ قبل) باید ۳ شود؛
        //  • عدد سفارشی کاربر (مثل ۷) دست‌نخورده می‌ماند.
        val prevN = Settings().apply { maxSlUnits = 20.0; trigger2Fade = false }
        val prevJson = Store.save(prevN, Engine(prevN), PaperBroker(prevN), emptyList())
            .replace("\"specRev\":${Store.SPEC_REV}", "\"specRev\":5")
        assertEquals(3.0, Store.load(prevJson).cfg.maxSlUnits, "پیش‌فرض ۲۰ نسخهٔ قبل باید به ۳ مهاجرت کند")
        val customN = Settings().apply { maxSlUnits = 7.0 }
        val customJson = Store.save(customN, Engine(customN), PaperBroker(customN), emptyList())
            .replace("\"specRev\":${Store.SPEC_REV}", "\"specRev\":5")
        assertEquals(7.0, Store.load(customJson).cfg.maxSlUnits, "عدد سفارشی کاربر نباید عوض شود")
        val defOld = Settings().apply { trigger2Fade = false }
        val defaultJson = Store.save(defOld, Engine(defOld), PaperBroker(defOld), emptyList())
            .replace("\"specRev\":${Store.SPEC_REV}", "\"specRev\":2")
        assertEquals(3.0, Store.load(defaultJson).cfg.maxSlUnits, "پرونده‌های قدیمی هم باید به پیش‌فرض ۳ برسند")

        val rv = doubleArrayOf(50.0, 50.0, 40.0, 30.0, 35.0, 20.0, 10.0)
        val p = Profile(rv, 1000.0, 10.0, Double.NaN, Double.NaN, Double.NaN)
        val z = VolumeProfile.zones(p, 7, bull = true, cClose = 2000.0, minRows = 1)
        assertEquals(1, z.size); assertEquals(1, z[0].loIdx); assertEquals(3, z[0].hiIdx)
    }

    @Test
    fun `doc 4-9 18-20 and 18-24 target levels 3-step vs 2-step 50-50 and breakeven zero commission`() {
        // لنگر 100% = 150 (midRef) ، لنگر 0% = 200 (HH) ⇒ دامنه = 50
        // fib 0.382 = 169.10 ، fib 0.5 = 175.00 ، fib 1.272 = 213.60
        val before382 = calculateTargetLevels(
            bull = true, entry = 160.0, anchor0 = 200.0, anchor1 = 150.0, minDistance = 10.0
        )
        assertEquals(169.1, before382.tp1, 1e-9)
        assertEquals(175.0, before382.tp2, 1e-9)
        assertEquals(213.6, before382.tpx, 1e-9)

        // §18-20: سفارش بعد از ناحیه 0.382 فیبو باز شده ⇒ حد سود اول = 0.5 فیبو (175) ، حد سود نهایی = 1.272 (213.6)
        val after382Buy = calculateTargetLevels(
            bull = true, entry = 170.0, anchor0 = 200.0, anchor1 = 150.0, minDistance = 10.0
        )
        assertEquals(175.0, after382Buy.tp1, 1e-9, "حد سود اول بعد از ۰.۳۸۲ باید ۰.۵ فیبو باشد")
        assertTrue(after382Buy.tp2.isNaN(), "پلهٔ میانی دوم دیگر وجود ندارد")
        assertEquals(213.6, after382Buy.tpx, 1e-9, "حد سود نهایی باید ۱.۲۷۲ فیبو بماند")

        // حالت نزولی: لنگر 100% = 250 ، لنگر 0% = 200 ⇒ دامنه = 50
        // fib 0.382 = 230.90 ، fib 0.5 = 225.00 ، fib 1.272 = 186.40
        val after382Sell = calculateTargetLevels(
            bull = false, entry = 230.0, anchor0 = 200.0, anchor1 = 250.0, minDistance = 10.0
        )
        assertEquals(225.0, after382Sell.tp1, 1e-9)
        assertTrue(after382Sell.tp2.isNaN())
        assertEquals(186.4, after382Sell.tpx, 1e-9)

        // ۱) سفارش بعد از 0.382 فیبو باز شده (§18-20 و §18-24):
        // ۵۰٪ حجم در 0.5 فیبو بسته می‌شود + سر‌به‌سر شدن بدون کمیسیون (commission = 0)؛
        // در صورت برگشت به نقطهٔ ورود، ۵۰٪ باقی‌مانده دقیقاً در نقطهٔ ورود بدون کمیسیون بسته می‌شود.
        val cfg = Settings().apply {
            maxSlUnits = 1_000_000.0 // موضوع تست §18-31 نیست
            mmMode = MoneyMode.MIXED
            initialEquity = 1000.0
            cmsnAuto = false
            cmsnManual = 0.50
        }
        val b = PaperBroker(cfg)
        b.balance = 1300.0 // بالاتر از M = 1200 ⇒ فاز ManageCM فعال
        val s = Setup(1L, 1, 5, 165.0, 155.0, 1, 0).apply {
            hvH = 169.99; hvL = 165.0; sl = 164.98
            tp1 = after382Buy.tp1 // 175.0 (ناحیه 0.5 فیبو چون ورود 170 بعد از 169.1 است)
            tp2 = after382Buy.tp2 // NaN
            tpx = after382Buy.tpx // 213.6
        }
        val t0 = 1_699_948_800_000L // سه‌شنبه 2023-11-14 ساعت 08:00 UTC — داخل پنجرهٔ سفارش‌گیری (§18-26 سند)
        assertTrue(b.registerLimit(s, Candle(1, t0, 170.5, 171.0, 170.2, 170.8, 100.0)))
        // پر شدن سفارش روی 170.00
        b.onBar(Candle(2, t0 + 60_000L, 170.5, 171.0, 169.8, 170.5, 100.0))
        assertEquals(1, b.openTrades.size)
        val tr = b.openTrades[0]
        assertEquals(170.0, tr.entry, 1e-9)
        val totalQty = tr.qty

        // کندل کاملاً بالای ورود (171..174) ولی قبل از 175.0 ⇒ حد ضرر نباید به ورود منتقل شود
        b.onBar(Candle(3, t0 + 120_000L, 171.0, 174.0, 171.0, 173.5, 100.0))
        assertFalse(tr.be, "قبل از رسیدن به حد سود اول، معامله نباید سر‌به‌سر شود")

        // رسیدن به 175.0 (ناحیه 0.5 فیبو) ⇒ ۵۰٪ معامله بسته می‌شود و حد ضرر بدون کمیسیون به ورود منتقل می‌شود
        b.onBar(Candle(4, t0 + 180_000L, 173.5, 175.2, 173.0, 174.5, 100.0))
        assertTrue(tr.be, "با لمس ناحیه 0.5 فیبو معامله باید سر‌به‌سر شود")
        assertTrue(tr.open, "۵۰٪ باقی‌مانده تا ۱.۲۷۲ یا سر‌به‌سر باز می‌ماند")
        assertEquals(0.0, tr.commission, 1e-9, "با سر‌به‌سر شدن معامله کارمزد کمیسیون باید صفر شود (§18-20 و §18-24)")
        val tp1Fill = tr.fills.first { it.kind == "TP1" }
        assertEquals(totalQty * 0.50, tp1Fill.qty, 1e-9, "در حالت بعد از ۰.۳۸۲ باید ۵۰٪ حجم در ۰.۵ فیبو بسته شود (§18-20)")

        // برگشت قیمت به نقطهٔ ورود (170.0) ⇒ بسته شدن ۵۰٪ باقی‌مانده دقیقاً در نقطهٔ ورود با ضرر صفر و بدون کمیسیون
        b.onBar(Candle(5, t0 + 240_000L, 174.0, 174.2, 169.9, 170.2, 100.0))
        assertFalse(tr.open)
        val beFill = tr.fills.first { it.kind == "BE" }
        assertEquals(totalQty * 0.50, beFill.qty, 1e-9)
        assertEquals(0.0, beFill.pnl, 1e-9, "خروج سر‌به‌سر روی ۵۰٪ باقی‌مانده دقیقاً در نقطهٔ ورود با نتیجهٔ صفر است")
        assertEquals(0.0, tr.commission, 1e-9, "کارمزد کمیسیون در سر‌به‌سر صفر است (§18-24)")
        assertEquals(0.0, b.mm.cmsnLost, 1e-9, "هیچ کمیسیونی نباید در cmsnLost انباشته شود")

        // ۲) §4-9 «حد سود اول و قیمت ورود در یک کندل»:
        // اگر در یک کندل هم قیمت ورود (160) و هم 0.382 فیبو (169.1) لمس شود،
        // ورود ثبت می‌شود، حد سود اول به 0.5 فیبو (175) منتقل می‌شود و ۵۰٪ در 0.5 و ۵۰٪ در 1.272 بسته می‌شود.
        val sSame = Setup(2L, 1, 5, 160.0, 155.0, 1, 0).apply {
            hvH = 159.99; hvL = 155.0; sl = 154.98
            tp1 = before382.tp1 // 169.1 (0.382)
            tp2 = before382.tp2 // 175.0 (0.5)
            tpx = before382.tpx // 213.6 (1.272)
        }
        assertTrue(b.registerLimit(sSame, Candle(6, t0 + 300_000L, 161.0, 162.0, 160.5, 161.5, 100.0)))
        // کندل ۷: هم ورود (160.0) و هم 0.382 فیبو (169.1) لمس می‌شود (تا 170.0)، ولی هنوز به 0.5 فیبو (175.0) نرسیده است
        b.onBar(Candle(7, t0 + 360_000L, 161.0, 170.0, 159.5, 169.5, 100.0))
        assertEquals(1, b.openTrades.size, "معامله نباید در کندل ورود سر‌به‌سر بسته شود (§4-9)")
        val trSame = b.openTrades[0]
        assertEquals(175.0, trSame.tp1, 1e-9, "حد سود اول باید به ۰.۵ فیبو منتقل شده باشد (§4-9)")
        assertTrue(trSame.tp2.isNaN(), "معامله به حالت دوپله‌ای ۵۰٪/۵۰٪ تبدیل می‌شود")
        assertFalse(trSame.be, "حد ضرر فقط بعد از لمس ۰.۵ فیبو به نقطهٔ ورود منتقل می‌شود (§4-9)")

        // کندل ۸: لمس 0.5 فیبو (175.0) ⇒ ۵۰٪ بسته می‌شود و حد ضرر به نقطهٔ ورود منتقل می‌شود
        b.onBar(Candle(8, t0 + 420_000L, 169.5, 176.0, 169.0, 175.5, 100.0))
        assertTrue(trSame.be, "پس از لمس ۰.۵ فیبو حد ضرر به نقطهٔ ورود منتقل می‌شود")
        assertEquals(0.0, trSame.commission, 1e-9)
        assertEquals(trSame.qty * 0.50, trSame.fills.first { it.kind == "TP1" }.qty, 1e-9)

        // کندل ۹: لمس 1.272 فیبو (213.6) ⇒ ۵۰٪ باقی‌مانده در حد سود نهایی بسته می‌شود
        b.onBar(Candle(9, t0 + 480_000L, 175.5, 215.0, 175.0, 214.0, 100.0))
        assertFalse(trSame.open)
        assertEquals(trSame.qty * 0.50, trSame.fills.first { it.kind == "TPX" }.qty, 1e-9)
    }

    @Test
    fun `doc 4-7 and 4-8 strictly one order per LV whether filled expired or invalidated and 3rd LV touch`() {
        val cfg = Settings().apply {
            tfS = Tf.H1; tfM = Tf.M15; tf1 = Tf.M1; tf2 = Tf.M1
            hvMarkFirstIncrease = true
            maxSlUnits = 1_000_000.0
        }
        val e = Engine(cfg)
        val b = PaperBroker(cfg)
        e.broker = b; b.engine = e
        e.HH = 2050.0; e.HL = 1980.0; e.trend = 1

        // الف) انقضای سفارش معلق با عبور از HH ⇒ ستاپ به مرحلهٔ ۸ می‌رود و سفارش دومی برای همان LV مسلح نمی‌شود (§4-8)
        val sExp = Setup(1L, 1, 6, 2010.0, 2000.0, 1, 0).apply {
            midRef = 2001.0; midConfirmBi = -1
            lvBi = 0; lvConfBi = 0; lvH = 2008.0; lvL = 2004.0; lvReEntered = true
            hvBi = 0; hvConfBi = 0; hvH = 2012.0; hvL = 2009.0
            armBi = 0; prevEntry = 2012.01; priceExpiryRef = 2050.0
            entry = 2012.01; sl = 2008.98; tp1 = 2020.0; tp2 = 2025.0; tpx = 2060.0
        }
        e.setups.add(sExp)
        val c0 = Candle(0, 28_800_000L, 2010.0, 2013.0, 2010.0, 2012.5, 100.0)
        assertTrue(b.registerLimit(sExp, c0))
        // بار ۱: کلوز بالای HH (2051 > 2050) بدون لمس ورود ⇒ انقضای سفارش
        val c1 = Candle(1, 28_860_000L, 2040.0, 2052.0, 2039.0, 2051.0, 100.0)
        val bars = mutableListOf(c0, c1)
        e.processed = 1
        e.feed(bars, lastIsClosed = true)
        b.onBar(c1)
        assertEquals(8, sExp.stage, "پس از انقضای سفارش، برای همان LV سفارش دومی مجاز نیست و ستاپ غیرفعال می‌شود (§4-8)")
        assertFalse(sExp.rearmAllowed)
        assertEquals(0, b.pendingOrders.size)

        // ب) لمس سوم باکس LV پس از ۲ بار مصرف ⇒ سفارش معلق لغو و ستاپ غیرفعال می‌شود (§4-7)
        val sLv3 = Setup(2L, -1, 6, 2040.0, 2030.0, 1, 0).apply {
            midRef = 2039.0; midConfirmBi = -1
            lvBi = 0; lvConfBi = 0; lvH = 2036.0; lvL = 2032.0; lvReEntered = true; lvUses = 2
            hvBi = 0; hvConfBi = 1; hvH = 2031.0; hvL = 2028.0
            armBi = 1; prevEntry = 2027.99; priceExpiryRef = 1980.0
            entry = 2027.99; sl = 2031.02; tp1 = 2015.0; tp2 = 2010.0; tpx = 1970.0
        }
        e.setups.add(sLv3)
        assertTrue(b.registerLimit(sLv3, c1))
        val c2 = Candle(2, 28_920_000L, 2031.5, 2032.5, 2031.0, 2031.8, 100.0) // لمس کف باکس LV (2032.0..2036.0) برای بار سوم!
        bars.add(c2)
        e.feed(bars, lastIsClosed = true)
        b.onBar(c2)
        assertEquals(8, sLv3.stage, "با لمس سوم باکس LV سفارش معلق لغو و ستاپ غیرفعال می‌شود (§4-7)")
        assertEquals(0, b.pendingOrders.size)
    }

    @Test
    fun `doc 18-22 inside structure candles X and X plus n have invalid zones while Y stays reference`() {
        val cfg = Settings().apply {
            strategy = Strategy.NO_TREND
            tfS = Tf.M5; tfM = Tf.M1; tf1 = Tf.M1; tf2 = Tf.M1
        }
        val e = Engine(cfg)
        val bars = ArrayList<Candle>()
        var bi = 0
        fun addM5Candle(bucketIdx: Int, o: Double, h: Double, l: Double, c: Double) {
            val baseT = bucketIdx * 300_000L
            val step = (c - o) / 5.0
            for (m in 0 until 5) {
                val bo = o + m * step
                val bc = o + (m + 1) * step
                val bh = if (m == 2) h else maxOf(bo, bc) + 0.5
                val bl = if (m == 1) l else minOf(bo, bc) - 0.5
                bars.add(Candle(bi++, baseT + m * 60_000L, bo, minOf(h, bh), maxOf(l, bl), bc, 100.0 + m * 20.0))
            }
        }

        // کندل Y (ساختار اول): 2000..2030 (صعودی: o=2002, c=2028)
        addM5Candle(0, 2002.0, 2030.0, 2000.0, 2028.0)
        // کندل X (ساختار دوم، کاملاً درون Y: 2005..2025): نواحی آن باید باطل باشد
        addM5Candle(1, 2008.0, 2025.0, 2005.0, 2022.0)
        // کندل X+1 (ساختار سوم، بزرگ‌تر از X ولی همچنان درون Y: 2001..2029): نواحی آن هم باید باطل باشد
        addM5Candle(2, 2004.0, 2029.0, 2001.0, 2027.0)
        // کندل ساختار چهارم (خارج از Y: سقف 2045 > 2030): نواحی آن معتبر است و مرجع جدید می‌شود
        addM5Candle(3, 2027.0, 2045.0, 2025.0, 2042.0)
        // کندل دنباله برای بستن کندل چهارم
        bars.add(Candle(bi, 4 * 300_000L, 2042.0, 2042.0, 2042.0, 2042.0, 100.0))

        e.runAll(bars)
        val createdStructStarts = e.setups.map { it.zT }.distinct()
        assertTrue(0L in createdStructStarts, "کندل مرجع Y باید ناحیه ثبت کند")
        assertFalse(300_000L in createdStructStarts, "کندل درونی X نباید هیچ ناحیهٔ فعالی ثبت کند (§18-22)")
        assertFalse(600_000L in createdStructStarts, "کندل درونی X+1 نیز باید نسبت به Y سنجیده و باطل شود (§18-22)")
        assertTrue(900_000L in createdStructStarts, "کندل خارج از Y باید ناحیه ثبت کند")
    }

    @Test
    fun `doc 18-21 and 18-14 structure zone touched once by another structure candle is consumed on close`() {
        val cfg = Settings().apply {
            strategy = Strategy.NO_TREND
            tfS = Tf.M5; tfM = Tf.M1; tf1 = Tf.M1; tf2 = Tf.M1
        }
        val e = Engine(cfg)
        val bars = ArrayList<Candle>()
        var bi = 0
        // کندل ساختار 0 (0..240s): صعودی 2000..2020 -> ثبت ناحیه
        for (m in 0 until 5) {
            bars.add(Candle(bi++, 28_800_000L + m * 60_000L, 2002.0 + m * 3.0, 2005.0 + m * 3.5, 2000.0 + m * 3.0, 2004.0 + m * 3.5, 100.0 + (m % 2) * 80.0))
        }
        // کندل ساختار 1 (300..540s): لمس ناحیهٔ کندل 0 و تشکیل تایید میانی با کف 2005.0
        bars.add(Candle(bi++, 29_100_000L, 2018.0, 2019.0, 2005.0, 2017.0, 150.0))
        for (m in 1 until 5) {
            bars.add(Candle(bi++, 29_100_000L + m * 60_000L, 2017.0, 2019.0, 2016.0, 2018.0, 120.0))
        }
        // شروع کندل ساختار 2 (600s): باعث بسته شدن کندل ساختار 1 می‌شود
        bars.add(Candle(bi++, 29_400_000L, 2018.0, 2019.0, 2017.0, 2018.5, 100.0))
        // کندل دنباله
        bars.add(Candle(bi, 29_460_000L, 2018.5, 2019.0, 2018.0, 2018.5, 100.0))

        e.runAll(bars)
        val touchedSetups = e.setups.filter { it.touchT > 0L }
        assertTrue(touchedSetups.isNotEmpty(), "ناحیهٔ ساختار باید در کندل ساختار ۱ لمس شده باشد")
        for (s in touchedSetups) {
            assertTrue(s.zUsed, "با بسته شدن کندل ساختارِ لمس‌کننده، ناحیهٔ ساختار باید باطل/مصرف‌شده علامت بخورد (§18-21)")
            val zb = e.zones.first { it.createdBi == s.zBi && it.idx == s.zIdx && it.dir == s.dir }
            assertEquals(ZoneStatus.CONSUMED, zb.status, "وضعیت باکس ساختار روی چارت باید CONSUMED شود (§18-21)")
            assertTrue(s.stage in 2..6, "تا زمانی که تایید میانی باطل نشده، ستاپ فعال می‌ماند (§18-14)")
        }

        // حالا در کندل ساختار 2، تایید میانی (2005.0) شکسته می‌شود (کف 2004.0 ولی بالای کف ناحیه)
        // چون ناحیهٔ ساختار قبلاً با یک بار لمس در کندل ساختار 1 باطل شده بود، ستاپ باید کامل باطل شود (stage=8) و تایید میانی جدید نگیرد!
        bars.removeAt(bars.lastIndex)
        val breakBi = bars.size
        bars.add(Candle(breakBi, 29_460_000L, 2018.0, 2018.5, 2004.0, 2016.0, 100.0))
        bars.add(Candle(breakBi + 1, 29_520_000L, 2016.0, 2017.0, 2015.0, 2016.5, 100.0))
        e.runAll(bars)
        for (s in e.setups.filter { it.touchT > 0L }) {
            assertEquals(8, s.stage, "پس از باطل شدن تایید میانیِ ناحیهٔ مصرف‌شده، ستاپ باید کامل باطل شود (§18-14)")
            assertTrue(s.zGone)
        }
    }

    @Test
    fun `doc 18-16 18-17 and 18-23 multi-timeframe LV and HV scan from extremes to current price`() {
        // مود ۱: ساختار 1H ، میانی 15m ، تریگر۱ 5m ، تریگر۲ 1m
        val cfg = Settings().apply {
            tfMode = 1
            tfS = Tf.H1; tfM = Tf.M15; tf1 = Tf.M5; tf2 = Tf.M1
            maxSlUnits = 1_000_000.0
            // §۱۸-۱۷: «اسکن از کف ناحیهٔ تاییدشدهٔ LV» — این حالت با خاموش‌کردن پیش‌فرض §۴-۸
            // («از اولین برگشت به باکس») قابل انتخاب است.
            hvScanFirstTouch = false
        }
        val e = Engine(cfg)
        val b = PaperBroker(cfg)
        e.broker = b
        b.engine = e
        e.HH = 2100.0
        e.HL = 1980.0
        e.trend = 1

        // یک ستاپ خرید که ناحیهٔ ساختار 2000..2010 را در ابتدای کندل میانی 15m (t=0..840s) لمس کرده است
        val s = Setup(1L, 1, 1, 2010.0, 2000.0, 1, -1).apply {
            zT = 28_800_000L
            touchBi = 0
            touchT = 28_800_000L
            touchMidStart = 28_800_000L
        }
        e.setups.add(s)

        val bars = ArrayList<Candle>()
        var bi = 0
        fun addM1(o: Double, h: Double, l: Double, c: Double, v: Double) {
            bars.add(Candle(bi, 28_800_000L + bi * 60_000L, o, h, l, c, v))
            bi++
        }

        // ۵ دقیقهٔ اول (0..4): حجم کل بالا (5 * 200 = 1000)؛ کف تایید میانی (2002.0) در دقیقهٔ 2 شکل می‌گیرد
        addM1(2008.0, 2009.0, 2005.0, 2006.0, 200.0) // 0
        addM1(2006.0, 2007.0, 2004.0, 2004.5, 200.0) // 1
        addM1(2004.5, 2006.0, 2002.0, 2005.5, 200.0) // 2 -> کف تایید میانی = 2002.0
        addM1(2005.5, 2008.0, 2005.0, 2007.5, 200.0) // 3
        addM1(2007.5, 2009.0, 2007.0, 2008.5, 200.0) // 4 (پایان باکت ۵ دقیقهٔ اول: h=2009, l=2002, v=1000)

        // ۵ دقیقهٔ دوم (5..9): کندل LV در تایم‌فریم 5m با حجم کمتر (50+40+80+60+70 = 300 < 1000)
        // سقف این کندل 5m برابر 2011.0 و کف آن در دقیقهٔ 6 برابر 2006.0 است.
        // بعد از کف 2006.0 (دقیقهٔ 6)، در دقیقهٔ 7 اولین کندل 1m با حجم بیشتر (80 > 40) با سقف 2010.0 و کف 2007.0 بسته می‌شود،
        // و در دقیقهٔ 8 کندل 1m با کلوز 2010.5 بالای 2010.0 آن را تایید می‌کند و دیگر تا دقیقهٔ 14 به 2010.01 برنمی‌گردد!
        addM1(2008.5, 2009.0, 2007.0, 2007.5, 50.0)  // 5
        addM1(2007.5, 2008.0, 2006.0, 2007.2, 40.0)  // 6 -> کف ناحیهٔ LV (2006.0)
        addM1(2007.2, 2010.0, 2007.0, 2009.5, 80.0)  // 7 -> اولین کندل 1m با حجم بیشتر (80 > 40) بعد از کف LV (hvH=2010, hvL=2007)
        addM1(2009.5, 2011.0, 2009.2, 2010.5, 60.0)  // 8 -> کلوز 2010.5 > 2010.0 (تایید HV بدون لمس مجدد 2010.01 در کندل‌های بعدی)
        addM1(2010.5, 2011.0, 2010.2, 2010.8, 70.0)  // 9 (پایان باکت ۵ دقیقهٔ دوم: h=2011, l=2006, v=300 < 1000 -> مارک LV)

        // ۵ دقیقهٔ سوم (10..14): کندل تایید LV در تایم‌فریم 5m (کلوز بالای 2011.0، بدون برگشت به سطح سفارش 2010.01)
        addM1(2010.8, 2012.0, 2010.5, 2011.5, 90.0)  // 10
        addM1(2011.5, 2013.0, 2011.2, 2012.5, 100.0) // 11
        addM1(2012.5, 2014.0, 2012.0, 2013.5, 110.0) // 12
        addM1(2013.5, 2015.0, 2013.0, 2014.5, 100.0) // 13
        addM1(2014.5, 2016.0, 2014.0, 2015.5, 100.0) // 14 (پایان کندل 15m میانی و کندل 5m سوم: کلوز 2015.5 > 2011.0)

        // کندل دقیقهٔ 15 (شروع ربع‌ساعت بعدی -> بسته شدن کندل 15m میانی)
        addM1(2015.5, 2016.5, 2015.0, 2016.0, 100.0) // 15

        e.feed(bars, lastIsClosed = true)

        assertEquals(2002.0, s.midRef, 1e-9, "کف تایید میانی باید 2002.0 باشد")
        assertEquals(5, s.lvBi, "کندل ۵ دقیقه‌ای دوم (شروع از بار ۵) باید به عنوان اولین LV معتبر انتخاب شود (§18-16 و §18-23)")
        assertEquals(2011.0, s.lvH, 1e-9)
        assertEquals(2006.0, s.lvL, 1e-9)
        assertEquals(7, s.hvBi, "اولین کندل ۱ دقیقه‌ای با حجم بیشتر از قبلی پس از کف LV (بار ۷) باید به عنوان HV انتخاب شود (§18-17)")
        assertEquals(6, s.stage, "با پیدا شدن و تایید شدن HV، ستاپ باید بلافاصله مسلح شود (§18-17)")
        assertEquals(1, b.pendingOrders.size, "سفارش لیمیت باید در کارگزار ثبت شده باشد")
        assertEquals(2010.01, b.pendingOrders[0].price, 1e-9, "سطح سفارش = 2010.0 + 1 تیک")
    }

    @Test
    fun `doc 18-16 18-17 skips invalidated LV or touched HV and store roundtrips zoneRef`() {
        val cfg = Settings().apply {
            tfMode = 2
            tfS = Tf.M30; tfM = Tf.M5; tf1 = Tf.M1; tf2 = Tf.M1
            hvScanFirstTouch = false   // §۱۸-۱۷: اسکن از کف ناحیهٔ LV (نه اولین برگشت)
            maxSlUnits = 1_000_000.0
        }
        val e = Engine(cfg)
        val b = PaperBroker(cfg)
        e.broker = b
        b.engine = e
        e.HH = 2100.0
        e.HL = 1980.0
        e.trend = 1
        e.zoneRefH = 2050.0
        e.zoneRefL = 1990.0

        val savedJson = Store.save(cfg, e, b, emptyList())
        val loaded = Store.load(savedJson)
        assertEquals(2050.0, loaded.eng.zoneRefH, 1e-9)
        assertEquals(1990.0, loaded.eng.zoneRefL, 1e-9)

        val s = Setup(1L, 1, 1, 2010.0, 2000.0, 1, -1).apply {
            zT = 28_800_000L; touchBi = 0; touchT = 28_800_000L; touchMidStart = 28_800_000L
        }
        e.setups.add(s)

        val bars = listOf(
            // کندل میانی ۵ دقیقه‌ای (0..4):
            // 0: کف تایید میانی = 2001.0 (حجم 200)
            Candle(0, 28_800_000L, 2008.0, 2009.0, 2001.0, 2005.0, 200.0),
            // 1: کندل حجم کم اول (50 < 200، کف=2004، سقف=2006) اما در کندل 2 باطل می‌شود (کف 2003.5 < 2004)
            Candle(1, 28_860_000L, 2005.0, 2006.0, 2004.0, 2005.5, 50.0),
            // 2: عبور از کف کندل 1 (2003.5 < 2004) و حجم بیشتر (120)
            Candle(2, 28_920_000L, 2005.5, 2007.0, 2003.5, 2006.5, 120.0),
            // 3: کندل حجم کم دوم (40 < 120، کف=2005، سقف=2008) -> معتبر!
            Candle(3, 28_980_000L, 2006.5, 2008.0, 2005.0, 2007.5, 40.0),
            // 4: تایید کندل حجم کم دوم (کلوز 2009.5 > 2008)
            Candle(4, 29_040_000L, 2007.5, 2010.0, 2007.0, 2009.5, 90.0),
            // 5: شروع کندل ۵ دقیقه‌ای بعدی (بسته شدن کندل میانی 0..4) + کندل حجم زیاد (150 > 90، سقف 2012، کف 2009)
            Candle(5, 29_100_000L, 2009.5, 2012.0, 2009.0, 2011.5, 150.0),
            // 6: کلوز بالای 2012 -> تایید HV و مسلح شدن در 2012.01
            Candle(6, 29_160_000L, 2011.5, 2014.0, 2011.0, 2013.5, 100.0)
        )
        e.feed(bars, lastIsClosed = true)

        assertEquals(3, s.lvBi, "کندل حجم کمِ باطل‌شده (بار ۱) باید رد شود و اولین LV باطل‌نشده (بار ۳) انتخاب شود (§18-16 و §18-23)")
        assertEquals(5, s.hvBi, "در ادامهٔ اسکن، کندل بار ۵ باید به عنوان HV مارک شود (§18-17)")
        assertEquals(6, s.stage, "با کلوز بار ۶ بالای سقف HV، سفارش باید مسلح شود")
        assertEquals(1, b.pendingOrders.size)
        assertEquals(2012.01, b.pendingOrders[0].price, 1e-9)
    }

    @Test
    fun `doc 4-9 oo stop transfer is deferred so the stop never moves on a close beyond oo`() {
        val cfg = Settings().apply { initialEquity = 100000.0; maxSlUnits = 1_000_000.0 }
        val b = PaperBroker(cfg)
        val t0 = 1_699_948_800_000L // سه‌شنبه 2023-11-14 ساعت 08:00 UTC — داخل پنجرهٔ سفارش‌گیری (§18-26)
        // سفارش خرید: oo=2010.01 (سقف HV 2010 + ۱ تیک)، باکس HV 2007..2010، SL=2006.98
        val s = Setup(9L, 1, 6, 2020.0, 2000.0, 1, 0).apply {
            hvH = 2010.0; hvL = 2007.0; sl = 2006.98
            tp1 = 2030.0; tp2 = 2040.0; tpx = 2100.0; entry = 2010.01
        }
        assertTrue(b.registerLimit(s, Candle(0, t0, 2015.0, 2016.0, 2014.0, 2015.5, 100.0)))
        // کندل پرشدن (باید oo را لمس کند)
        b.onBar(Candle(1, t0 + 60_000L, 2012.0, 2013.0, 2010.0, 2012.5, 100.0))
        assertEquals(1, b.openTrades.size)
        val tr = b.openTrades[0]
        assertEquals(2006.98, tr.sl0, 1e-9)

        // §4-9: بلوک «انتقال حد ضرر به سطح oo» در سند داخل بلوک «به تعویق افتاده و در کد
        // ایجاد نمی‌شود» است ⇒ هیچ کلوزی — حتی کلوز کاملاً بالای oo — حد ضرر را جابه‌جا نمی‌کند.
        b.onBar(Candle(2, t0 + 120_000L, 2011.0, 2012.0, 2009.0, 2011.5, 100.0))
        assertTrue(tr.open)
        b.onBar(Candle(3, t0 + 180_000L, 2015.0, 2020.0, 2014.0, 2019.0, 100.0)) // دو کلوز قوی بالای oo
        assertTrue(tr.open, "معامله باید باز بماند")
        assertFalse(tr.be, "سر‌به‌سر فقط با حد سود اول فعال می‌شود")

        // تنها حد ضررِ مؤثر همان SL اولیه است: برگشت به ۲۰۰۶٫۹۸ معامله را با «حد ضرر» می‌بندد
        // (نه «خروج سر‌به‌سر»)، چون هیچ انتقالی رخ نداده است.
        b.onBar(Candle(4, t0 + 240_000L, 2009.0, 2010.0, 2006.0, 2007.0, 100.0))
        assertFalse(tr.open)
        assertEquals("حد ضرر", tr.reason)
        assertTrue(tr.grossPnl() < 0.0, "خروج باید ضرر قیمتی داشته باشد، نه صفر")
    }

    @Test
    fun `trend HH break keeps pinBE and HL reverts to pinBU, LL break keeps pinBU and LH reverts to pinBE`() {
        fun cfg() = Settings().apply { tfS = Tf.M1; tfM = Tf.M1; tf1 = Tf.M1; tf2 = Tf.M1 }
        // ـ روند صعودی: کلوز بالای HH ⇒ HH→pinBE (حذف نمی‌شود)، HL→pinBU، پین بعدی pinBU است
        val up = Engine(cfg())
        up.trend = 1; up.HH = 110.0; up.HL = 100.0
        up.hhPinHi = 110.0; up.hhPinRef = 104.0; up.hhPinLo = 102.0; up.hhPinSeq = 2
        up.hlPinLo = 100.0; up.hlPinRef = 96.0; up.hlPinHi = 103.0; up.hlPinSeq = 1
        up.lastBEpin = 110.0; up.lastBEpinRef = 104.0; up.lastBEpinLo = 102.0
        up.beActive = true; up.beSeq = 2
        up.lastBUpin = 100.0; up.lastBUpinRef = 96.0; up.lastBUpinHi = 103.0
        up.buActive = true; up.buSeq = 1
        up.expectedReady = -1
        val t0 = 1_700_000_040_000L
        up.feed(listOf(
            Candle(0, t0, 108.0, 112.0, 107.0, 111.0, 100.0),            // کندل ساختارِ شکست HH (کلوز 111 > 110)
            Candle(1, t0 + 60_000L, 111.0, 111.5, 110.5, 111.2, 100.0),  // شروع کندل بعد ⇒ بستن کندل شکست
            Candle(2, t0 + 120_000L, 111.2, 111.4, 111.0, 111.3, 100.0)  // کندل زنده (بسته نمی‌شود)
        ), lastIsClosed = false)
        assertTrue(up.HH.isNaN() && up.HL.isNaN(), "برچسب HH/HL باید برداشته شود")
        assertTrue(up.beActive && up.lastBEpin == 110.0, "HH باید به pinBE برگردد، نه اینکه پین حذف شود")
        assertTrue(up.buActive && up.lastBUpin == 100.0, "HL باید به pinBU برگردد")
        assertEquals(1, up.expectedReady, "بعد از pinBE طبق توالی پین بعدی pinBU است")

        // ـ روند نزولی (قرینه): کلوز زیر LL ⇒ LL→pinBU (حذف نمی‌شود)، LH→pinBE، پین بعدی pinBE است
        val dn = Engine(cfg())
        dn.trend = -1; dn.LL = 90.0; dn.LH = 100.0
        dn.llPinLo = 90.0; dn.llPinRef = 96.0; dn.llPinHi = 98.0; dn.llPinSeq = 2
        dn.lhPinHi = 100.0; dn.lhPinRef = 104.0; dn.lhPinLo = 97.0; dn.lhPinSeq = 1
        dn.lastBUpin = 90.0; dn.lastBUpinRef = 96.0; dn.lastBUpinHi = 98.0
        dn.buActive = true; dn.buSeq = 2
        dn.lastBEpin = 100.0; dn.lastBEpinRef = 104.0; dn.lastBEpinLo = 97.0
        dn.beActive = true; dn.beSeq = 1
        dn.expectedReady = 1
        dn.feed(listOf(
            Candle(0, t0, 92.0, 93.0, 88.0, 89.0, 100.0),              // کندل ساختارِ شکست LL (کلوز 89 < 90)
            Candle(1, t0 + 60_000L, 89.0, 89.5, 88.5, 89.2, 100.0),    // شروع کندل بعد ⇒ بستن کندل شکست
            Candle(2, t0 + 120_000L, 89.2, 89.4, 89.0, 89.3, 100.0)    // کندل زنده (بسته نمی‌شود)
        ), lastIsClosed = false)
        assertTrue(dn.LL.isNaN() && dn.LH.isNaN(), "برچسب LL/LH باید برداشته شود")
        assertTrue(dn.buActive && dn.lastBUpin == 90.0, "LL باید به pinBU برگردد، نه اینکه پین حذف شود")
        assertTrue(dn.beActive && dn.lastBEpin == 100.0, "LH باید به pinBE برگردد")
        assertEquals(-1, dn.expectedReady, "بعد از pinBU طبق توالی پین بعدی pinBE است")
    }

    @Test
    fun `doc 4-8 stop loss is taken from the high volume box not the middle confirmation`() {
        // همان سری کندلیِ تست §18-16/§18-17: کف تایید میانی = ۲۰۰۲٫۰ ، باکس HV = ۲۰۰۷٫۰..۲۰۱۰٫۰ ،
        // سطح سفارش = ۲۰۱۰٫۰۱. صفت «ناحیهٔ حجم زیاد» یعنی همان باکس HV، پس حد ضرر = ۲۰۰۷٫۰ − ۲ تیک.
        val cfg = Settings().apply {
            trigger2Fade = false; trigger2TargetR = 0.0   // هندسهٔ سند ۵
            hvScanFirstTouch = false   // همان سری دادهٔ §۱۸-۱۷
            tfMode = 1
            tfS = Tf.H1; tfM = Tf.M15; tf1 = Tf.M5; tf2 = Tf.M1
            maxSlUnits = 1_000_000.0
            slBufTicks = 2.0
        }
        val e = Engine(cfg)
        val b = PaperBroker(cfg)
        e.broker = b; b.engine = e
        e.HH = 2100.0; e.HL = 1980.0; e.trend = 1
        val s = Setup(1L, 1, 1, 2010.0, 2000.0, 1, -1).apply {
            zT = 28_800_000L; touchBi = 0; touchT = 28_800_000L; touchMidStart = 28_800_000L
        }
        e.setups.add(s)

        val bars = ArrayList<Candle>()
        var bi = 0
        fun addM1(o: Double, h: Double, l: Double, c: Double, v: Double) {
            bars.add(Candle(bi, 28_800_000L + bi * 60_000L, o, h, l, c, v)); bi++
        }
        addM1(2008.0, 2009.0, 2005.0, 2006.0, 200.0)
        addM1(2006.0, 2007.0, 2004.0, 2004.5, 200.0)
        addM1(2004.5, 2006.0, 2002.0, 2005.5, 200.0)   // کف تایید میانی = 2002.0
        addM1(2005.5, 2008.0, 2005.0, 2007.5, 200.0)
        addM1(2007.5, 2009.0, 2007.0, 2008.5, 200.0)
        addM1(2008.5, 2009.0, 2007.0, 2007.5, 50.0)
        addM1(2007.5, 2008.0, 2006.0, 2007.2, 40.0)    // کف ناحیهٔ LV = 2006.0
        addM1(2007.2, 2010.0, 2007.0, 2009.5, 80.0)    // HV: 2007..2010
        addM1(2009.5, 2011.0, 2009.2, 2010.5, 60.0)    // تایید HV ⇒ مسلح‌شدن روی 2010.01
        addM1(2010.5, 2011.0, 2010.2, 2010.8, 70.0)
        addM1(2010.8, 2012.0, 2010.5, 2011.5, 90.0)
        addM1(2011.5, 2013.0, 2011.2, 2012.5, 100.0)
        addM1(2012.5, 2014.0, 2012.0, 2013.5, 110.0)
        addM1(2013.5, 2015.0, 2013.0, 2014.5, 100.0)
        addM1(2014.5, 2016.0, 2014.0, 2015.5, 100.0)
        addM1(2015.5, 2016.5, 2015.0, 2016.0, 100.0)

        e.feed(bars, lastIsClosed = true)

        assertEquals(2002.0, s.midRef, 1e-9, "کف تایید میانی باید ۲۰۰۲٫۰ باشد")
        assertEquals(2010.01, b.pendingOrders[0].price, 1e-9, "سطح سفارش = سقف باکس HV + ۱ تیک")
        // §4-8 سند: حد ضرر = کف **ناحیهٔ حجم زیاد** (۲۰۰۷٫۰) − ۲ تیک = ۲۰۰۶٫۹۸
        // و نه کف تایید میانی (۲۰۰۲٫۰) که ۲۰۰۱٫۹۸ می‌داد.
        assertEquals(2006.98, s.sl, 1e-9, "حد ضرر باید از کف ناحیهٔ حجم زیاد ساخته شود، نه از تایید میانی")
        assertEquals(2006.98, b.pendingOrders[0].sl, 1e-9)
    }

    @Test
    fun `doc 20-2 mixed keeps lost when capital falls back below M`() {
        val cfg = Settings().apply {
            mmMode = MoneyMode.MIXED; initialEquity = 1000.0
            minLotAuto = false; minLotManual = 10.0
        }
        val mm = MoneyManager(cfg)
        mm.lost = 250.0
        mm.onCapitalUpdated(1300.0)     // M = 1200 ⇒ فاز ManageCM
        assertEquals(250.0, mm.lost, 1e-9)
        // ویرایش فعلی سند جملهٔ «و مقدار lost = 0.0 می‌شود» را برداشته است؛ پس بازگشت به
        // ManageNothing نباید Lost را صفر کند.
        mm.onCapitalUpdated(1150.0)
        assertEquals(250.0, mm.lost, 1e-9, "بازگشت Capital به زیر M نباید Lost را صفر کند")
    }
}
