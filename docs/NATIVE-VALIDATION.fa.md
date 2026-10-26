# شواهد آزمون مرحلهٔ عملیات بومی

تاریخ: ۲۰۲۶-۱۰-۲۶. محیط: Linux آزمایشگاهی، Python 3.14.8، Go 1.27.3، Xray 26.10.21، NATS Server 2.12.0 و Chromium. این سند مربوط به کد مرحلهٔ عملیات در شاخهٔ `feat/donino-integration` است؛ پذیرش روی VPS یا ISP کاربر انجام نشده است.

## آزمون‌های اجراشده

| مجموعه | نتیجه | پوشش |
|---|---|---|
| بک‌اند بومی منتخب | ۲۸۳ پاس | DNS، resolver، تونل، Paqet، هاست/اشتراک/کش، چرخهٔ نود، پیش‌بررسی، health/failover، رزرو بایت، بکاپ و بازیابی، ثبت job در نقش صحیح |
| API و اشتراک منتخب | ۱۲۰ پاس | host، node، core، صفحهٔ ساب، hot path و CPU |
| Xray واقعی | ۲ پاس | TCP و UDP رمز‌شده با تنظیمات کامپایل‌شده |
| کلاستر واقعی NATS | ۱ پاس | سه broker با replicas=3، دو worker واقعی، قطع همهٔ brokerها، تغییر DB بدون پیام، reconnect، KV قدیمی و حذف leader |
| پنل production در Docker | ۲ پاس | TLS، migration، ساخت مالک/ورود، APIهای عملیات، صفحات SmartDNS/تونل، واردکردن JSON نود بدون ثبت خودکار، حفظ DB پس از تعویض کانتینر و Unbound UDP/TCP |
| دو نود واقعی Paqet در Docker | ۱ پاس | API پنل→نودهای فورک، ترافیک TCP/UDP، health روی حامل واقعی و پورت مدیریت NATشده، چرخهٔ مسیر و انتشار ورودی ایران در سابلینک |

مجموع آزمون‌های منتخب Python: **۴۰۹ پاس**. این عدد تمام تست‌های مخزن نیست.

Go با `-race` برای `smartdns`، `tunnel/health`، `config` و `tunnel/paqet` پاس شد. آزمون health با باینری واقعی Xray اجرا شد؛ صرفاً تست mock نیست. آزمون‌های gateway شامل TCP/TLS بدون رمزگشایی، UDP ثابت، خروج رمز‌شدهٔ SS2022 با Xray تعبیه‌شده، انقضای مجوز/ابطال، حفظ listener قبلی در شکست bind، مقصدهای خصوصی و DNS واقعی UDP/TCP با TLS معتبر تا authorizer هستند.

آزمون مرورگر متصل به API واقعی شامل انتخاب تونل خروجی gateway، بودجهٔ ۱٫۵ GiB، ذخیرهٔ DNS نود و وضعیت pending، جلوگیری از failover در خطای کنترل و چیدمان موبایل پاس شد. TypeScript، تولید دوبارهٔ client از OpenAPI، Ruff و Compose با افزونهٔ نمونهٔ بکاپ نیز پاس شدند.

مهاجرت SQLite روی دیتابیس جدید: upgrade کامل، `alembic check` بدون drift، حفظ مصرف کاربر، جلوگیری از downgrade با DNS فعال پیش از هر DDL، downgrade و upgrade دوباره پاس شد. موتور PostgreSQL/MySQL در این محیط اجرا نشده است.

## دستورات بازتولید

پس از نصب dependencyهای قفل‌شده و ابزارهای نسخهٔ بالا:

```sh
pytest tests/test_native_dns.py tests/test_native_dns_resolver.py tests/test_native_tunnels.py tests/test_native_paqet.py tests/test_tunnel_subscription_hosts.py tests/test_subscription_config_cache.py tests/test_node_start_timeout.py tests/test_node_wireguard_batched_start.py tests/test_donino_subscription_template.py tests/test_app_factory.py tests/test_node_core_users_tags.py tests/test_node_maintenance_rpc_timeout.py tests/test_core_live_update.py tests/test_node_removal_payloads.py tests/test_node_core_update_model.py tests/test_tunnel_preflight.py tests/test_node_bootstrap.py tests/test_smartdns_gateway.py tests/test_tunnel_health_failover.py tests/test_native_backup.py tests/test_native_operational_recovery.py -q

DATABASE_URL=sqlite+aiosqlite:////tmp/pg-api-test.db SQLALCHEMY_DATABASE_URL=sqlite+aiosqlite:////tmp/pg-api-test.db pytest tests/api/test_host.py tests/api/test_host_download_host.py tests/api/test_host_format_fields.py tests/api/test_node.py tests/api/test_core.py tests/api/test_subscription_page.py tests/test_subscription_hot_path.py tests/test_subscription_cpu.py -q

XRAY_TEST_BINARY=/path/to/xray pytest tests/test_native_tunnel_traffic.py -q
NATS_SERVER_BINARY=/path/to/nats-server pytest tests/test_native_nats_cluster.py -q
XRAY_TEST_BINARY=/path/to/xray go -C node test -race ./smartdns ./tunnel/health ./config ./tunnel/paqet

docker build -f node/Dockerfile.plus -t pasarguard-plus-node:ops node
PAQET_DOCKER_IMAGE=pasarguard-plus-node:ops PAQET_GO_BINARY=/path/to/go XRAY_TEST_BINARY=/path/to/xray pytest tests/test_paqet_docker.py -q

docker build -t pasarguard-plus:ops .
PANEL_DOCKER_IMAGE=pasarguard-plus:ops pytest tests/test_panel_docker.py -q
node dashboard/scripts/test-native-operations-ui.mjs
bun run --cwd dashboard typecheck
```

در محیط proxy از secret گواهی CA طبق سیاست محیط برای build استفاده شد؛ بررسی TLS خاموش نشد. آزمون‌ها روی منابع موقت محلی اجرا می‌شوند. مسیر SQLite و فایل‌های تست را روی دادهٔ عملیاتی تنظیم نکنید. ابزارهای اختیاری اگر تنظیم نشده باشند، آزمون مربوطه را skip می‌کنند؛ skip نتیجهٔ پاسِ پذیرش شبکه نیست.

## حدود شواهد

failover با کنترلر واقعی و پاسخ probe کنترل‌شده آزموده شده؛ health جداگانه روی Paqet واقعی سنجیده شده است. بازی یا جابه‌جایی چند VPS واقعی در این آزمایش اجرا نشده است. تست فشار loopback با ۱۶ اتصال TCP و ۱۶ UDP و ۱۵۳۶ رفت‌وبرگشت، صحت عبور و حسابداری را پوشش داد؛ عدد ظرفیت تجاری نیست. گواهی TLS عمومی، شبکهٔ ایران/آلمان، NAT واقعی اپراتور، پورت‌های عمومی، CGNAT، رفتار بازی‌ها و نگهداری offsite باید طبق [راهنمای عملیات](NATIVE-OPERATIONS.fa.md) روی استقرار مقصد پذیرفته شوند.

تصویرهای ساخته‌شده از سورس این مرحله:

- پنل: `sha256:ff0e54a315c8287207fe34aeff86ed5afe6962bae078f5e7cae1a0a2ee5eb55b`
- نود: `sha256:1e77cea7b94c7fc8380045ec2b592da9fd023fd2562a041ddb9bf4fd0e1bb3c0`

محتوای تمام ۴۴۰ فایل سورس Python بک‌اند در تصویر نهایی با درخت سورس تحویلی، با SHA-256 تطبیق داده شد؛ اصلاح نقش scheduler نیز در تصویر تأیید شد. این تصاویر محلی‌اند و در registry منتشر نشده‌اند.
