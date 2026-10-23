package com.alisport.goldpin

import com.alisport.goldpin.core.MoneyMode
import com.alisport.goldpin.core.Strategy
import com.alisport.goldpin.core.Tuned

// ═══════════════════════════════════════════════════════════════════════════════
//  پیش‌تنظیم‌های بخش «تطبیق» — هر عدد از جاروب پارامتر روی دادهٔ واقعی یک‌سالهٔ
//  داخل اپ آمده است (۳۵۱٬۸۹۴ کندل M1 طلا · ۲۰۲۵-۰۸-۲۱ تا ۲۰۲۶-۰۸-۲۰ · کارمزد ۰٫۷).
//
//  سنجه‌ها همان‌هایی است که اپ نشان می‌دهد: تعداد معامله، نرخ برد، سود خالص پس از کسر
//  کارمزد واقعی، ضریب سود (PF)، بیشترین افت سرمایه و تعداد ماه‌های مثبت.
//  گزارش خام: `sweep/sweep_phase*.csv` در مخزن.
//
//  صادقانه: دو ترکیب (تریگر ۲ معکوس و استراتژی ۲) روی این سال **منفی** ماندند؛ بهترین
//  تنظیم یافت‌شده‌شان با همان برچسب «منفی روی این سال» در اپ آمده تا کاربر با عدد واقعی
//  تصمیم بگیرد، نه با امید.
// ═══════════════════════════════════════════════════════════════════════════════

val ADAPT_PRESETS: List<AppState.AdaptPreset> = listOf(
    AppState.AdaptPreset(
        id = "s1-mix-best",
        label = "★ بهینهٔ سود — استراتژی ۱ · Mixed · هم‌جهت ساختار",
        strategy = Strategy.DOC,
        mm = MoneyMode.MIXED,
        fade = Tuned.TRIGGER2_FADE,
        targetR = Tuned.TARGET_R,
        tpUnits = 10.0,
        costMult = Tuned.COST_MULT,
        minSl = 0.0,
        maxSl = Tuned.MAX_SL_UNITS,
        cmsn = Tuned.CMSN,
        slBufTicks = Tuned.SL_BUF_TICKS,
        entryBufTicks = Tuned.ENTRY_BUF_TICKS,
        note = "۴۳۸ معامله · نرخ برد ۱۹٫۹٪ · انتظار ‎+۰٫۱۰R‎ · سود ‎+۳٬۴۳۸ دلار · ضریب سود ۱٫۱۹ · " +
            "بیشترین افت ۱٬۹۶۵ دلار · ۷ ماه مثبت از ۱۳ ماه."
    ),
    AppState.AdaptPreset(
        id = "s1-mix-lowdd",
        label = "بهینهٔ کم‌افت — استراتژی ۱ · Mixed",
        strategy = Strategy.DOC,
        mm = MoneyMode.MIXED,
        fade = false,
        targetR = 6.0,
        tpUnits = 10.0,
        costMult = 0.0,
        minSl = 0.0,
        maxSl = 10.0,
        cmsn = Tuned.CMSN,
        slBufTicks = 175.0,
        entryBufTicks = -2.0,
        note = "۴۵۴ معامله · نرخ برد ۲۱٫۴٪ · انتظار ‎+۰٫۱۰R‎ · سود ‎+۲٬۹۱۹ دلار · ضریب سود ۱٫۱۶ · " +
            "بیشترین افت ۱٬۶۳۸ دلار (کمترین افت بین همهٔ تنظیم‌ها) · ۷ ماه مثبت."
    ),
    AppState.AdaptPreset(
        id = "s1-off-best",
        label = "استراتژی ۱ · مود خاموش (§۵) · حجم بر پایهٔ ریسک ۱٪",
        strategy = Strategy.DOC,
        mm = MoneyMode.NONE,
        fade = false,
        targetR = Tuned.TARGET_R,
        tpUnits = 10.0,
        costMult = 0.0,
        minSl = 0.0,
        maxSl = 10.0,
        cmsn = Tuned.CMSN,
        slBufTicks = Tuned.SL_BUF_TICKS,
        entryBufTicks = Tuned.ENTRY_BUF_TICKS,
        note = "۴۳۸ معامله · انتظار ‎+۰٫۲۷R‎ · سود پس از کسر کارمزد واقعی ‎+۲۹٬۱۳۶ دلار · " +
            "ضریب سود ۱٫۲۴ · ⚠ بیشترین افت ۶۹٬۲۳۳ دلار (چون حجم با ریسک ۱٪ در هر معامله " +
            "ساخته می‌شود؛ برای افت کمتر، ریسک هر معامله را پایین بیاورید). " +
            "در این مود اپ کارمزد را از سود کم نمی‌کند (§۵)، پس عددِ خود اپ خوش‌بینانه‌تر است."
    ),
    AppState.AdaptPreset(
        id = "s1-off-lowdd",
        label = "استراتژی ۱ · مود خاموش · حد ضرر کم‌افت‌تر",
        strategy = Strategy.DOC,
        mm = MoneyMode.NONE,
        fade = false,
        targetR = 6.0,
        tpUnits = 10.0,
        costMult = 0.0,
        minSl = 0.0,
        maxSl = 10.0,
        cmsn = Tuned.CMSN,
        slBufTicks = 175.0,
        entryBufTicks = -2.0,
        note = "۴۵۴ معامله · انتظار ‎+۰٫۲۷R‎ · سود پس از کارمزد ‎+۴۱٬۵۰۱ دلار · ضریب سود ۱٫۲۸ · " +
            "⚠ بیشترین افت ۴۲٬۳۱۵ دلار (اثر حجم ۱٪ ریسک، نه ضعف استراتژی)."
    ),
    AppState.AdaptPreset(
        id = "s1-mix-fade",
        label = "تریگر ۲ معکوس (سند ۶) — بهترین تنظیم یافت‌شده",
        strategy = Strategy.DOC,
        mm = MoneyMode.MIXED,
        fade = true,
        targetR = 8.0,
        tpUnits = 10.0,
        costMult = 0.0,
        minSl = 0.0,
        maxSl = 10.0,
        cmsn = Tuned.CMSN,
        slBufTicks = 175.0,
        entryBufTicks = -2.0,
        note = "⚠ روی این سال **منفی** است: ۴۶۴ معامله · نرخ برد ۱۴٪ · انتظار ‎−۰٫۲۷R‎ · " +
            "زیان ‎−۵٬۷۷۷ دلار · ضریب سود ۰٫۷۲. با ۱۲ ترکیبِ دیگر (هدف ۳ تا ۸، بافر ۱۰۰ تا ۳۰۰ تیک) " +
            "هم منفی ماند ⇒ در این بازه، ورودِ برخلاف ساختار برتری ندارد. اگر این کلید را " +
            "روشن می‌کنید، بدانید که چه عددی پشتش است."
    ),
    AppState.AdaptPreset(
        id = "s2-mix-best",
        label = "استراتژی ۲ (بدون روند) — بهترین تنظیم یافت‌شده",
        strategy = Strategy.NO_TREND,
        mm = MoneyMode.MIXED,
        fade = false,
        // در استراتژی ۲ فقط وقتی «هدف بر حسب R» صفر باشد، حد سود واحدی اثر می‌کند.
        targetR = 0.0,
        tpUnits = 35.0,
        costMult = 0.0,
        minSl = 0.0,
        maxSl = 10.0,
        cmsn = Tuned.CMSN,
        slBufTicks = 250.0,
        entryBufTicks = 1.0,
        note = "نزدیک سربه‌سر ولی هنوز منفی: ۹۲۶ معامله · نرخ برد ۱۸٫۶٪ · انتظار ‎−۰٫۰۰۴R‎ · " +
            "زیان ‎−۸۰۵ دلار · ضریب سود ۰٫۹۸ · ۸ ماه مثبت از ۱۳ ماه. " +
            "نکته: «حد سود واحدی» فقط وقتی اثر می‌کند که «هدف تریگر ۲ بر حسب R» صفر باشد."
    ),
    AppState.AdaptPreset(
        id = "doc7-defaults",
        label = "بازگشت به پیش‌فرض سند ۷ (معکوس · ۴R · فیلتر ۶ · N=۳)",
        strategy = Strategy.DOC,
        mm = MoneyMode.MIXED,
        fade = true,
        targetR = 4.0,
        tpUnits = 10.0,
        costMult = 6.0,
        minSl = 0.0,
        maxSl = 3.0,
        cmsn = Tuned.CMSN,
        slBufTicks = 2.0,
        entryBufTicks = 1.0,
        note = "⚠ با کارمزد ۰٫۷ این پیش‌فرض **صفر سفارش** می‌دهد: آستانهٔ فیلتر کارمزد " +
            "(۶ × ۰٫۷ = ۴٫۲) از سقف N=۳ بزرگ‌تر است، پس هر سفارشی رد می‌شود. این دکمه فقط " +
            "برای مقایسه با سند است؛ بعد از آن «تطبیق با کارمزد» را بزنید."
    )
)
