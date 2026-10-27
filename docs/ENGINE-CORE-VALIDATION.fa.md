# شواهد هستهٔ چندموتوره

تاریخ: ۲۰۲۶-۱۰-۲۷. مبنا: شاخهٔ `feat/donino-integration` بعد از checkpoint `9501663a`. این سند نتایج **محلی** همین مرحله را ثبت می‌کند؛ VPS و ISP کاربر، بازی واقعی و ظرفیت تجاری آزموده نشده‌اند.

## سورس انتخاب‌شده

| پروژه | نسخه | commit پایه | تغییر محلی |
|---|---|---|---|
| Backhaul | v0.7.2 | `df7966f8f725837a680ea7b90bd37ea52666c277` | سورس upstream بدون تغییر |
| GOST | v3.3.1 | `84dd70f9f65e6cb318268204887b2b8e78b93937` | سورس upstream بدون تغییر |
| FRP | v0.71.1 | `d8cf1800dc1abd7fda1828cab105d7a6b5a50eb1` | احترام به proxy/NO_PROXY و تست آن؛ دو فایل ثبت‌شده در manifest |

تمام ۷۶۵ فایل tracked این سه snapshot با upstream مقایسه شدند؛ فقط همان دو فایل FRP تفاوت دارند. مجوزها و فایل‌های پروژه حفظ شده‌اند. checksum آرشیو اولیه و تعداد فایل هر snapshot در [manifest](../node/engines/UPSTREAM.json) است. سورس کامل Paqet و Node قبلاً در همین فورک قرار گرفته بود.

## نتایج

مجموع **۴۳۷ تست منتخب Python پاس** شد؛ این عدد به معنی اجرای تمام تست‌های مخزن نیست:

| مجموعه | تعداد پاس | پوشش |
|---|---|---|
| بک‌اند بومی | ۳۰۸ | مجموعهٔ قبلی و ۲۵ آزمون جدید؛ compiler، کلید فقط‌نوشتنی، جهت اتصال و پورت‌ها، capability pin، legacy compiler، لغو health و مهلت محدود تأیید failover |
| API و اشتراک | ۱۲۰ | host، node، core، subscription و hot path/CPU |
| Xray واقعی | ۲ | عبور رمز‌شدهٔ TCP/UDP و بسته‌ماندن مسیر خراب |
| NATS واقعی | ۱ | سه broker و دو worker، قطع/برگشت، KV قدیمی و حذف leader |
| دو نود واقعی در Docker | ۴ | Paqet، Backhaul TCP، GOST TLS و FRP QUIC؛ API پنل، health واقعی روی حامل، ورودی VLESS مشتری، TCP/UDP، شمارندهٔ کاربر، انتشار IP ایران در اشتراک، ویرایش/چرخش کلید/غیرفعال‌سازی |
| پنل production در Docker | ۲ | نصب و migration، ورود، API کاتالوگ موتور، داشبورد bundle‌شده و چهار کارت موتور، SmartDNS، فرم نود و نگهداری DB، resolver UDP/TCP |

آزمون Go با `-race` برای supervisor مشترک و adapterها پاس شد. **۹ پروفایل** از باینری source-built واقعاً ترافیک TCP و UDP عبور دادند و restart، حفظ tombstone و رد revision قدیمی را گذراندند: Backhaul TCP؛ GOST TCP/WS/TLS/WSS؛ FRP TCP/WebSocket/KCP/QUIC. بررسی رد CA نامعتبر و بازیابی پس از revision جدید در GOST TLS و FRP TCP نیز پاس شد. تست واحد اصلاح proxy در خود FRP پاس شد.

تست مرورگر با API واقعی: پیش‌فرض Paqet، ساخت و ویرایش سه موتور، پروفایل‌ها، کلید فقط‌نوشتنی، عدم تغییر نوع موتور بعد از ساخت، وضعیت runtime، تطبیق capability و نبود overflow در موبایل پاس شد. TypeScript، تولید client از OpenAPI و Ruff هم پاس شدند.

مهاجرت SQLite: upgrade کامل، schema check بدون drift، رد downgrade پیش از DDL تا حذف مسیرهای موتورهای جدید، downgrade/upgrade و حفظ مصرف کاربر پاس شد. PostgreSQL/MySQL در این محیط اجرا نشده‌اند.

## تصاویر و هویت build

- [رابط دسکتاپ](images/native-engine-core-desktop.png)
- [رابط موبایل](images/native-engine-core-mobile.png)؛ snapshot جزء رابط است و header چسبان هنگام capture پنهان شده تا روی جزء نیفتد.
- تصویر نود: `sha256:8779681222dbeaaeed0f960ccbab07bd70e7cfdb3e0987a4f1508b73f70cb71a`
- تصویر پنل: `sha256:e75eaf3b4bd2d8029f8327efdbf678010e0dda73b3a51f91e6f594aab3b79b69`

تمام **۴۴۳ فایل Python بک‌اند** تصویر production با سورس تحویلی از طریق SHA-256 تطبیق داده شدند. این تصاویر از سورس ساخته و محلی تست شده‌اند؛ در registry منتشر نشده‌اند.

## بازتولید

از Go 1.27.3، Python 3.14، dependencyهای قفل‌شده، Xray 26.10.21، NATS Server 2.12.0 و Chromium استفاده شد. فرمان‌های منتخب قبلی در [سند عملیات](NATIVE-VALIDATION.fa.md) هستند؛ به مجموعهٔ بک‌اند `tests/test_native_carriers.py` اضافه شد. تست Docker قبلی اکنون چهار موتور را پارامتری بررسی می‌کند.

```sh
# باینری‌ها باید از سورس pin‌شدهٔ همین مخزن ساخته شوند:
go -C node/engines/backhaul build -o /tmp/carrier-bin/backhaul .
go -C node/engines/gost build -o /tmp/carrier-bin/gost ./cmd/gost
go -C node/engines/frp build -tags frps,noweb -o /tmp/carrier-bin/frps ./cmd/frps
go -C node/engines/frp build -tags frpc,noweb -o /tmp/carrier-bin/frpc ./cmd/frpc
NATIVE_CARRIER_TEST_BIN=/tmp/carrier-bin go -C node test -race -v ./tunnel/carrier ./tunnel/paqet
go -C node/engines/frp test ./pkg/config/v1

pytest tests/test_native_carriers.py -q

docker build -f node/Dockerfile.plus -t pasarguard-plus-node:engines node
PAQET_DOCKER_IMAGE=pasarguard-plus-node:engines PAQET_GO_BINARY=/path/to/go XRAY_TEST_BINARY=/path/to/xray pytest tests/test_paqet_docker.py -q

docker build -t pasarguard-plus:engines .
PANEL_DOCKER_IMAGE=pasarguard-plus:engines pytest tests/test_panel_docker.py -q
node dashboard/scripts/test-native-carriers-ui.mjs
bun run --cwd dashboard typecheck
```

فرمان‌ها روی دیتابیس و شبکهٔ موقت اجرا شوند. ابزار اختیاری تنظیم‌نشده می‌تواند باعث skip شود؛ skip به معنی پذیرش موتور نیست. در محیط proxy، secret گواهی CA برای build استفاده شد و TLS خاموش نشد. fixture شبکهٔ Docker فقط subnet موقت داخلی خود را به NO_PROXY افزود؛ پراکسی سایر مقصدها حفظ شد.

برای کاربرد واقعی، [راهنمای هسته](NATIVE-ENGINE-CORE.fa.md) و پذیرش سناریوی مشتری را دنبال کنید. همهٔ قابلیت‌های upstream به adapter قابل استفاده تبدیل نشده‌اند؛ کارایی یا شناسایی‌ناپذیری هر پروتکل از این نتایج استنتاج نمی‌شود.
