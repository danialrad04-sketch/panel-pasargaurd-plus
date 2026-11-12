<div dir="rtl">

<p align="center">
  <img width="120" height="120" src="dashboard/public/statics/favicon/favicon.svg" alt="PasarGuard Plus">
</p>

<h1 align="center">PasarGuard Plus</h1>

<p align="center">
  فورکی از <a href="https://github.com/PasarGuard/panel">PasarGuard</a> برای استفاده در ایران:
  تونل بومی بین نودها، DNS مخصوص بازی و سرویس‌های تحریمی، و داشبورد و صفحه اشتراک بازطراحی‌شده.
  <br>
  <a href="README.md">English</a>
</p>

---

## نصب

روی یک سرور تازه (Ubuntu / Debian / CentOS) با دسترسی root، فقط یک خط:

</div>

```sh
curl -fsSL https://raw.githubusercontent.com/danialrad04-sketch/panel-pasargaurd-plus/main/install.sh | sudo bash
```

<div dir="rtl">

این دستور در صورت نبودن Docker را نصب می‌کند، ایمیج را می‌گیرد (یا می‌سازد)، پنل را بالا می‌آورد و آدرس پنل و یک کلید یک‌بارمصرف مالک را چاپ می‌کند. **دامنه لازم نیست**؛ به‌طور پیش‌فرض پنل با HTTP روی IP سرور بالا می‌آید.

سپس `http://IP-سرور:8000/dashboard/` را باز کنید، کلید مالک را همراه نام کاربری و رمز قوی وارد کنید و پورت TCP 8000 را در فایروال باز کنید.

> HTTP رمزنگاری ندارد. برای شروع یا شبکهٔ خصوصی مناسب است. برای استفادهٔ جدی دستور `pasarguard-plus https-ip` را بزنید (گواهی خودامضا برای IP، بدون دامنه) یا دامنه و TLS جلوی پنل بگذارید. gateway دی‌ان‌اس گیمینگ HTTPS لازم دارد؛ پیش از ساخت پروفایل گیمینگ `pasarguard-plus https-ip` را اجرا کنید.

گزینه‌های نصب (متغیر محیطی پیش از `bash`):

| متغیر | پیش‌فرض | معنی |
|---|---|---|
| `PGP_MODE` | `http` | `http` = HTTP ساده روی IP؛ `https-ip` = گواهی خودامضا برای IP |
| `PGP_PORT` | `8000` | پورت پنل |
| `PGP_DNS` | خاموش | `1` یعنی worker دی‌ان‌اس و resolver محلی هم بالا بیایند |
| `PGP_BUILD` | خاموش | `1` یعنی ایمیج روی خود سرور ساخته شود |
| `PGP_DIR` | `/opt/pasarguard-plus` | مسیر نصب |
| `PGP_REF` | `main` | شاخه یا تگ |

ساخت ایمیج روی سرور داشبورد و هستهٔ نودها را کامپایل می‌کند؛ حدود ۲ گیگابایت RAM و چند دقیقه زمان لازم است. اجرای دوبارهٔ نصب‌کننده نصب را ارتقا می‌دهد و `.env` شما را نگه می‌دارد.

### مدیریت

</div>

```sh
pasarguard-plus status      # وضعیت کانتینرها و آدرس پنل
pasarguard-plus logs        # دنبال‌کردن لاگ
pasarguard-plus key         # کلید یک‌بارمصرف مالک
pasarguard-plus update      # آخرین نسخه، پشتیبان SQLite، ساخت و راه‌اندازی مجدد
pasarguard-plus dns         # روشن‌کردن worker دی‌ان‌اس و resolver محلی
pasarguard-plus https-ip    # رفتن به HTTPS با گواهی خودامضا برای IP
pasarguard-plus env         # ویرایش .env
```

<div dir="rtl">

راهنمای کامل و پیش‌نیازها: [docs/TESTING-START.fa.md](docs/TESTING-START.fa.md) (انگلیسی: [docs/INSTALL.md](docs/INSTALL.md)).

---

## این فورک چه چیزی اضافه می‌کند

| بخش | امکانات |
|---|---|
| **تشخیص تونل موجود** | بررسی فقط‌خواندنی سرور: تونل Paqet در حال کار، کانفیگ V2Ray/Xray وابسته، مسیر DNS و قوانین فایروال را پیدا می‌کند و پورت‌هایشان را رزرو می‌کند تا پنل رویشان ننویسد. |
| **تونل بومی** | تونل Paqet، Backhaul، GOST، FRP و SS2022 بین نودها از داخل پنل؛ نصب خودکار نود با SSH؛ انتشار ورودی تونل در اشتراک. |
| **DNS گیمینگ** | پروفایل DNS (حدود ۸۹۰ دامنه، ۶۶ سرویس، بسته‌های یک‌کلیکی) که ترافیک بازی، فروشگاه و سرویس‌های تحریمی را از gateway روی نود شما عبور می‌دهد. داخل فرم افزودن/ویرایش کاربر؛ کاربر IP خودش را از لینک شخصی ثبت می‌کند؛ سقف تغییر IP روزانه؛ مصرف به تفکیک سرویس؛ ابزار watch برای پیدا کردن دامنهٔ جاافتاده. |
| **کاربر یکدست** | دسترسی DNS از خود کاربر پنل می‌آید: انقضا، حجم، وضعیت و دسترسی مدیر. حساب جداگانه‌ای برای نگهداری نیست. |
| **رابط کاربری** | تم «فیروزه و لاجورد» و صفحه اشتراک بازطراحی‌شده. |
| **عملیات** | healthcheck، پشتیبان رمزنگاری‌شدهٔ SQLite، هشدار انقضای گواهی. |

### وضعیت صادقانه

قابلیت‌های سمت پنل با تست خودکار پوشش داده شده‌اند. **هنوز با بازی، کنسول یا ISP واقعی راستی‌آزمایی نشده‌اند** ([جدول پذیرش](docs/GAMING-ACCEPTANCE.fa.md)). محدودیت‌های شناخته‌شده: gateway فقط TCP را رله می‌کند (ترافیک UDP بازی‌ها هنوز نه)، نود هنوز سقف سرعت هر کاربر را اعمال نمی‌کند، و هر پروفایل gateway حداکثر ۲۵۶ مسیر دامنه دارد.

---

## پیش‌نیاز هر قابلیت

| قابلیت | لازم است |
|---|---|
| پنل | Linux با معماری x86_64 یا arm64، Docker و Compose v2 (خودکار نصب می‌شود) |
| نود و تونل | یک سرور برای هر نود با دسترسی SSH برای نصب خودکار؛ برای Paqet قابلیت raw socket و NIC/IP/MAC درست ([راهنما](docs/NATIVE-PAQET.fa.md)) |
| لینک DNS کاربر | `DNS_ENABLED=true` (دستور `pasarguard-plus dns`) |
| gateway دی‌ان‌اس گیمینگ | نود متصل، آدرس HTTPS پنل در `SMARTDNS_PANEL_URL` (دستور `pasarguard-plus https-ip` یا دامنهٔ خودتان) و نود/تونل خروجی **خارج از ایران** برای دورزدن تحریم |
| پورت ۵۳ عمومی | پورت ۵۳ آزاد (stub سیستم `systemd-resolved` خاموش) و تنظیم `DNS_LISTEN_ADDRESSES` و `DNS_PORT` |

همهٔ متغیرها در [`.env.example`](.env.example) توضیح داده شده‌اند.

---

## مستندات

- [نصب و پیش‌نیازها](docs/TESTING-START.fa.md)
- [DNS گیمینگ](docs/GAMING-DNS.fa.md) · [نقشه راه](docs/GAMING-DNS-PRO-PLAN.fa.md) · [جدول پذیرش](docs/GAMING-ACCEPTANCE.fa.md)
- [تشخیص تونل‌های موجود](docs/TUNNEL-DISCOVERY.fa.md) (فقط‌خواندنی؛ پورت‌های Paqet/V2Ray/DNS در حال کار را رزرو می‌کند)
- [تونل بومی](docs/NATIVE-TUNNELS.fa.md) · [Paqet](docs/NATIVE-PAQET.fa.md) · [هاست تونل در اشتراک](docs/TUNNEL-SUBSCRIPTION-HOSTS.fa.md)
- [عملیات و پشتیبان‌گیری](docs/NATIVE-OPERATIONS.fa.md)

مستندات پایهٔ PasarGuard: [docs.pasarguard.org](https://docs.pasarguard.org/fa).

## مجوز

[AGPL-3.0](LICENSE). بر پایهٔ [PasarGuard](https://github.com/PasarGuard/panel)؛ حق نشر و مجوز اصلی برقرار است.

</div>
