# دیتاست‌های بک‌تست طلا — XAUUSD، کندل M1

فایل‌های آمادهٔ بک‌تست بیرون از APK نگهداری می‌شوند و اپ آن‌ها را پس از انتخاب کاربر جداگانه دانلود می‌کند. دادهٔ هر بازه و نتیجهٔ اجرای بک‌تست در فایل مستقل ذخیره می‌شود.

| بازه | فایل | کندل | اندازهٔ فشرده | شروع UTC | پایان UTC | SHA-256 |
|---|---|---:|---:|---|---|---|
| ۱ سال | [`goldpin_xauusd_m1_1y.csv.gz`](goldpin_xauusd_m1_1y.csv.gz) | 351,894 | 5,128,001 بایت | 2025-08-21 00:00 | 2026-08-20 23:59 | `15fffd94b9c1970080d6012ff2aa3ae307d18a872381c677e44d16b5fd08db44` |
| ۲ سال | [`goldpin_xauusd_m1_2y.csv.gz`](goldpin_xauusd_m1_2y.csv.gz) | 701,067 | 9,844,331 بایت | 2024-08-21 00:00 | 2026-08-20 23:59 | `873d4ffc765723abfada8ef55fbf8b9bb9c7432125187395972f51af94496e9f` |
| ۳ سال | [`goldpin_xauusd_m1_3y.csv.gz`](goldpin_xauusd_m1_3y.csv.gz) | 1,040,730 | 14,310,975 بایت | 2023-08-21 00:00 | 2026-08-20 23:59 | `56751049149f3df1ed134fdbc5e667ec57655f445edde5820ef9d9fed6697fcc` |
| ۱۰ سال | [`goldpin_xauusd_m1_10y.csv.gz`](goldpin_xauusd_m1_10y.csv.gz) | 3,402,126 | 43,314,146 بایت (حدود 41.3 MiB) | 2016-08-21 23:00 | 2026-08-20 23:59 | `c0e5c8d2151605db8d0cf1c75f3f1d6cca0ecc5304bad233f536ffe45d7f7881` |

هر فایل شامل سرستون و ردیف‌های `timestamp(ms,UTC),open,high,low,close,volume` است. حجم، tick volume منبع است و در بازهٔ ۱۰ساله کندل بی‌حجم وجود ندارد. فایل متادیتای `.meta.json` کنار دیتاست، شمار کندل، بازه، اندازه و هش را ثبت می‌کند.

## رفتار اپ

- از تنظیمات، ۱، ۲، ۳ یا ۱۰ سال را انتخاب کنید؛ سپس دکمهٔ دانلود همان فایل را دریافت و SHA-256 آن را بررسی می‌کند.
- برای اجرای بک‌تست، نشست داخلی را روشن کنید. در صورت تغییر بازه هنگام نشست داخلی، «اجرای مجدد» دیتاست انتخابی را بارگذاری می‌کند.
- فایل‌های داده و نتیجهٔ نشست داخلی هر بازه مستقل‌اند؛ دانلود، حذف یا نتیجهٔ یک بازه به بازه‌های دیگر دست نمی‌زند.
- برای کاهش مصرف حافظهٔ ده‌ساله، اپ آن دیتاست را در آرایه‌های primitive فشرده نگه می‌دارد و Engine زمان‌های کندل را در `LongArray` ذخیره می‌کند.
- هیچ دیتاستی داخل APK نیست. دیتاست ۱۰ساله حدود ۴۱٫۳ MiB فشرده است و برای اولین اجرا باید یک‌بار دانلود شود.

## منبع و بازتولید

هر چهار برش از دیتاست [Kaggle: majidmehdipour/xauusd-ohlcv-1m](https://www.kaggle.com/datasets/majidmehdipour/xauusd-ohlcv-1m) ساخته شده‌اند (مجوز MIT؛ منبع 6,886,934 ردیف دارد). برش ده‌ساله از ۲۰۱۶-۰۸-۲۱ تا ۲۰۲۶-۰۸-۲۱ (حد بالای غیرشامل) ساخته شده است؛ انتهای فایل تا آخرین تاریخ موجود منبع، ۲۰۲۶-۰۸-۲۰ ۲۳:۵۹ UTC، پوشش دارد.

ساخت با `tools/build_sample.py` انجام می‌شود: زمان منبع `Europe/Berlin` با DST به UTC تبدیل، آخرهفتهٔ بستهٔ بازار حذف، OHLC بررسی و خروجی با gzip قطعی (`mtime=0`) ساخته می‌شود. دیتاست‌های بلندتر انتهای مشترک دارند و با انتهای فایل یک‌ساله تطبیق داده شده‌اند.

برای بازسازی فایل ده‌ساله:

```bash
python3 tools/build_sample.py --src /path/XAUUSD_OHLCV_1M.csv \
  --from 2016-08-21 --to 2026-08-21 \
  --out datasets/goldpin_xauusd_m1_10y.csv.gz
```

## دریافت مستقیم از GitHub

- ۱ سال: `https://raw.githubusercontent.com/alisport1384/ST/main/datasets/goldpin_xauusd_m1_1y.csv.gz`
- ۲ سال: `https://raw.githubusercontent.com/alisport1384/ST/main/datasets/goldpin_xauusd_m1_2y.csv.gz`
- ۳ سال: `https://raw.githubusercontent.com/alisport1384/ST/main/datasets/goldpin_xauusd_m1_3y.csv.gz`
- ۱۰ سال: `https://raw.githubusercontent.com/alisport1384/ST/main/datasets/goldpin_xauusd_m1_10y.csv.gz`
