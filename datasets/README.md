# دیتاست‌های بک‌تست طلا — XAUUSD، کندل M1

داده‌ها بیرون از APK نگهداری می‌شوند و اپ هر بازه را پس از انتخاب کاربر جداگانه دانلود می‌کند. دیتاست و نتیجهٔ اجرای بک‌تست هر بازه در فایل مستقل ذخیره می‌شود.

| بازه | فایل | کندل | اندازهٔ فشرده | شروع UTC | پایان UTC | SHA-256 |
|---|---|---:|---:|---|---|---|
| ۱ سال | [`goldpin_xauusd_m1_1y.csv.gz`](goldpin_xauusd_m1_1y.csv.gz) | 351,894 | 5,128,001 بایت | 2025-08-21 00:00 | 2026-08-20 23:59 | `15fffd94b9c1970080d6012ff2aa3ae307d18a872381c677e44d16b5fd08db44` |
| ۲ سال | [`goldpin_xauusd_m1_2y.csv.gz`](goldpin_xauusd_m1_2y.csv.gz) | 701,067 | 9,844,331 بایت | 2024-08-21 00:00 | 2026-08-20 23:59 | `873d4ffc765723abfada8ef55fbf8b9bb9c7432125187395972f51af94496e9f` |
| ۳ سال | [`goldpin_xauusd_m1_3y.csv.gz`](goldpin_xauusd_m1_3y.csv.gz) | 1,040,730 | 14,310,975 بایت | 2023-08-21 00:00 | 2026-08-20 23:59 | `56751049149f3df1ed134fdbc5e667ec57655f445edde5820ef9d9fed6697fcc` |
| ۱۰ سال | [`goldpin_xauusd_m1_10y.csv.gz`](goldpin_xauusd_m1_10y.csv.gz) | 3,402,126 | 43,314,146 بایت (حدود 41.3 MiB) | 2016-08-21 23:00 | 2026-08-20 23:59 | `c0e5c8d2151605db8d0cf1c75f3f1d6cca0ecc5304bad233f536ffe45d7f7881` |
| ۲۰ سال | [`goldpin_xauusd_m1_20y.csv.gz`](goldpin_xauusd_m1_20y.csv.gz) | 6,563,723 | 77,779,089 بایت (حدود 74.2 MiB) | 2006-08-21 00:02 | 2026-08-20 23:59 | `ffbc7c1bd8723728714dfc2f476102f7887be3797d0caf187b622aa533c830c9` |

همهٔ فایل‌ها شامل سرستون و ردیف‌های `timestamp(ms,UTC),open,high,low,close,volume` هستند. حجم، tick volume منبع است. در برش ۲۰ساله کندل بی‌حجم، نامعتبر یا timestamp تکراری وجود ندارد؛ ۱٬۰۷۱ فاصلهٔ بیش از ۲۰ ساعت گزارش شده است. فایل `.meta.json` کنار دیتاست، آمار ساخت را ثبت می‌کند.

## رفتار اپ

- از تنظیمات، بازهٔ ۱، ۲، ۳، ۱۰ یا ۲۰ سال را انتخاب و همان فایل را دانلود کنید؛ SHA-256 قبل از تأیید فایل بررسی می‌شود.
- برای اجرای بک‌تست، نشست داخلی را روشن کنید. با تغییر بازه، «اجرای مجدد» دیتاست انتخابی را بارگذاری می‌کند.
- داده و نتیجهٔ هر بازه جدا هستند؛ حذف، دانلود یا اجرای یک بازه به بقیه دست نمی‌زند. هیچ دیتاستی داخل APK نیست.
- برای تاریخچه‌های بلند، کندل‌ها با ستون‌های primitive نگه‌داری می‌شوند؛ زمان‌های دقیقه‌ای هم فشرده می‌شوند تا حافظهٔ کمتری مصرف شود.
- دیتاست ۲۰ساله حدود ۷۴٫۲ MiB فشرده است و ۶٫۵۶ میلیون کندل دارد. پردازش آن ممکن است ده‌ها دقیقه طول بکشد و روی گوشی‌های کم‌حافظه محدودیت داشته باشد.

## منبع و بازتولید

هر پنج برش از [Kaggle: majidmehdipour/xauusd-ohlcv-1m](https://www.kaggle.com/datasets/majidmehdipour/xauusd-ohlcv-1m) ساخته شده‌اند (مجوز MIT؛ فایل منبع 6,886,934 ردیف دارد). بازهٔ ۲۰ساله از `2006-08-21` تا `2026-08-21` (حد بالا غیرشامل) انتخاب شده؛ دادهٔ منبع در ۲۰۲۶-۰۸-۲۰ پایان می‌یابد. انتهای دیتاست ۲۰ساله با تمام ردیف‌های فایل ۱۰ساله، بایت‌به‌بایت یکسان است.

ساخت برش‌های کوتاه‌تر با `tools/build_sample.py` انجام می‌شود. برای برش‌های بلند از نسخهٔ دسته‌ای سریع `tools/build_long_sample.py` استفاده کنید (نیازمند `pandas` و `numpy`). هر دو ابزار زمان `Europe/Berlin` را با DST به UTC تبدیل، آخرهفتهٔ بستهٔ بازار را حذف، OHLC را بررسی و خروجی gzip قطعی (`mtime=0`) تولید می‌کنند. برای بازسازی ۲۰ سال:

```bash
python3 -m pip install pandas numpy
python3 tools/build_long_sample.py --src /path/XAUUSD_OHLCV_1M.csv \
  --from 2006-08-21 --to 2026-08-21 \
  --out datasets/goldpin_xauusd_m1_20y.csv.gz
```

## دریافت مستقیم از GitHub

- ۱ سال: `https://raw.githubusercontent.com/alisport1384/ST/main/datasets/goldpin_xauusd_m1_1y.csv.gz`
- ۲ سال: `https://raw.githubusercontent.com/alisport1384/ST/main/datasets/goldpin_xauusd_m1_2y.csv.gz`
- ۳ سال: `https://raw.githubusercontent.com/alisport1384/ST/main/datasets/goldpin_xauusd_m1_3y.csv.gz`
- ۱۰ سال: `https://raw.githubusercontent.com/alisport1384/ST/main/datasets/goldpin_xauusd_m1_10y.csv.gz`
- ۲۰ سال: `https://raw.githubusercontent.com/alisport1384/ST/main/datasets/goldpin_xauusd_m1_20y.csv.gz`
