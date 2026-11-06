# شواهد نصب خودکار نود و تونل

تاریخ ثبت: ۲۰۲۶-۱۰-۳۰. مبنای تغییر: checkpoint `7ad5da63` در شاخهٔ `feat/donino-integration`. نتایج این سند محلی‌اند؛ هیچ VPS کاربر، فایروال ارائه‌دهنده، بازی واقعی یا ISP در این محیط آزمایش نشده است.

## نصب واقعی از SSH

آزمون opt-in در `tests/test_native_provisioning_ssh.py` با موفقیت اجرا شد. دو مقصد موقت Debian 12 در Docker با OpenSSH واقعی و بدون هیچ هستهٔ تونل از قبل نصب‌شده ساخته شدند. پنل این مراحل را انجام داد:

- بررسی کلید میزبان و اتصال pinned SSH با کلید آزمایشی؛ انتقال SFTP بستهٔ کامل source-built؛ اجرای نصب‌کننده با Python 3.11 مقصد.
- نصب release در مسیر اصلی مقصد، تولید هویت TLS، ثبت نود در دیتابیس پنل و تأیید روشن‌شدن Xray از API بومی.
- تطبیق capability نسخه و commit هر چهار موتور در هر دو نود.
- نصب مجدد مبدا با حفظ شناسه، CA و کلید API قبلی.
- ساخت مسیر GOST TLS از پنل، اجرای کار فعال‌سازی و تأیید واقعی TCP و UDP از حامل رمز‌شده؛ بررسی قواعد واقعی iptables برای ورودی کاربر.
- حذف ciphertext اطلاعات SSH در پایان کار.

**محدودهٔ تست سرویس:** مقصد Docker معمولی از systemd واقعی به‌عنوان PID 1 استفاده نمی‌کند. فقط در fixture، فرمان `systemctl` با supervisor آزمایشی جایگزین شده که باینری نصب‌شده را واقعاً اجرا و متوقف می‌کند. بنابراین این تست به معنی تأیید boot/reboot در VM یا systemd واقعی نیست. نصب‌کنندهٔ production این جایگزین را ندارد و systemd فعال را الزامی می‌داند.

بستهٔ آزمون SSH: amd64، اندازهٔ `124498830` بایت، SHA-256 برابر `5d3617c2e337f19a2a0864a9c49eae6f40731c836bb5f6e37245b113502a0421`. این بسته شامل هفت باینری، geo assets و مجوزهای پروژه‌ها بود. معماری arm64 در این محیط اجرا نشده است.

## کنترل خطا و حفظ داده

مجموع ۴۵۶ آزمون منتخب Python پاس شد: ۳۳۳ بک‌اند بومی، ۱۲۰ API/اشتراک، یک پذیرش نصب SSH دو نود و دو پذیرش تصویر production. این عدد به معنی اجرای تمام آزمون‌های مخزن نیست.

۲۴ آزمون نصب شامل رمزگذاری و مجوز خصوصی کلید، ساخت هم‌زمان کلید، جلوگیری از ادعای هم‌زمان یک مقصد، ادامهٔ کار منقضی‌شده و توقف تلاش‌های فرسوده، حذف اطلاعات ورود در خطا و لغو، فنس مالکیت worker، محدودیت مالک پنل، نام‌های یکتای OpenAPI، رد کلید SSH متفاوت پیش از ارسال رمز، رد مسیر آرشیو مخرب، حفظ هویت ناشناخته و بازگرداندن unit/env/release قبلی در شکست سرویس پاس شدند.

ترتیب اعمال ingress هم بررسی شد: قواعد عمومی ورودی کاربر فقط پس از تأیید روشن‌شدن هسته ارسال می‌شوند و در شکست تأیید باز نمی‌شوند. آزمون backup نیز ثابت کرد restore کارهای نصب فعال و credential آن‌ها را متوقف/پاک می‌کند و نسخهٔ اصلی دیتابیس را تغییر نمی‌دهد.

مهاجرت SQLite از ابتدا، بررسی نبود schema drift، جلوگیری از downgrade با کار نصب فعال، چرخهٔ downgrade/upgrade پس از پایان کار و حفظ مصرف کاربر پاس شدند. PostgreSQL/MySQL اجرا نشده‌اند.

Go با `-race` برای firewall، config، carrier و supervisor پاس شد؛ حفاظت SSH معمولی و سفارشی و پورت‌های مدیریت، پذیرش پورت دادهٔ 443، محدودشدن تغییرات به chain اختصاصی، revision و خطای فرمان پوشش داده شدند. آزمون‌های حقیقی ۹ پروفایل از مرحلهٔ قبل در [سند قبلی](ENGINE-CORE-VALIDATION.fa.md) ثبت‌اند؛ این مرحله همهٔ آن‌ها را دوباره اجرا نکرد.

## رابط

Chromium با API واقعی و دیتابیس موقت: فرم نصب، الزام بررسی اثر انگشت پیش از ارسال، ثبت واقعی صف رمزگذاری‌شده بدون بازتاب credential، لغو کار در صف، ساخت تونل با دکمهٔ «ذخیره و راه‌اندازی خودکار» و چیدمان موبایل پاس شدند. در تست رابط، فقط پاسخ بررسی میزبان fixture است و نصب از راه دور اجرا نمی‌شود؛ SSH واقعی در تست مستقل بالا پوشش داده شد. client از OpenAPI یکتا دوباره تولید شد و TypeScript پاس شد.

[فرم دسکتاپ](images/native-provision-desktop.png) · [فرم موبایل](images/native-provision-mobile.png)

## تصویر نهایی

تصویر محلی `pasarguard-plus:automatic` با شناسهٔ `sha256:97db2fe3dd1c012741289f6dd42eaf414a0fd2dc223e77dff76f6fa1c0e237eb` ساخته شد. دو آزمون production شامل migration و ساخت مالک، ورود، API بستهٔ نصب هر چهار هسته، داشبورد bundle‌شده و فرم نصب، حفظ دیتابیس پس از جایگزینی کانتینر و Unbound محلی UDP/TCP پاس شدند. بستهٔ همین تصویر استخراج و در آزمون SSH بالا استفاده شد.

هر ۴۶۱ فایل Python بک‌اند تصویر، شامل ۴۴۹ فایل app، با SHA-256 سورس تحویلی تطبیق داده شد. hash هر ۱۴ عضو باینری/داده/مجوز بسته نیز با بستهٔ پذیرفته‌شدهٔ اولیه یکسان بود. هیچ تصویر یا release در registry منتشر نشده است.

## بازتولید منتخب

```sh
pytest tests/test_native_provisioning.py tests/test_native_backup.py -q
go -C node test -race ./tunnel/firewall ./config ./tunnel/carrier ./tunnel/paqet
node dashboard/scripts/test-native-provision-ui.mjs
bun run --cwd dashboard typecheck

# ساخت از همین سورس و استخراج /bundles از stage native-bundle:
docker build --target native-bundle -t pasarguard-native-bundle:test .
docker build -t pasarguard-ssh-fixture:test tests/fixtures/native-provision
NATIVE_SSH_TEST_IMAGE=pasarguard-ssh-fixture:test NATIVE_SSH_TEST_BUNDLES=/path/to/extracted/bundles pytest tests/test_native_provisioning_ssh.py -q

docker build -t pasarguard-plus:automatic .
PANEL_DOCKER_IMAGE=pasarguard-plus:automatic pytest tests/test_panel_docker.py -q
```

اگر ساخت از پراکسی HTTPS استفاده می‌کند، CA همان محیط را با BuildKit secret `proxy_ca` به build بدهید؛ TLS غیرفعال نمی‌شود. کانتینر مقصد تست به `NET_ADMIN` و `NET_RAW` نیاز دارد. این fixture صرفاً آزمایشی است و برای استقرار نود تولیدی استفاده نمی‌شود.
