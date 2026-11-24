"use strict";

const get = (id) => document.getElementById(id);
const fa = new Intl.NumberFormat("fa-IR");
const compact = new Intl.NumberFormat("fa-IR", { notation: "compact", maximumFractionDigits: 1 });
const fullDate = new Intl.DateTimeFormat("fa-IR", { timeZone: "Asia/Tehran", dateStyle: "medium", timeStyle: "short" });
const dayLabel = new Intl.DateTimeFormat("fa-IR", { timeZone: "Asia/Tehran", month: "numeric", day: "numeric" });
const today = new Intl.DateTimeFormat("fa-IR", { timeZone: "Asia/Tehran", dateStyle: "full" });
const titles = { overview: "نمای کلی", orders: "سفارش‌ها", deposits: "واریزی‌ها", activity: "فعالیت‌ها", catalog: "پلن‌ها", settings: "تنظیمات" };
const statusLabels = {
  AWAITING_RECEIPT: "در انتظار واریز", PENDING_REVIEW: "در انتظار بررسی",
  APPROVED: "تأییدشده", REJECTED: "ردشده", EXPIRED: "منقضی",
  matched: "دارای سفارش", unmatched: "بدون سفارش",
};
const decisionLabels = { ADMIN_MATCHED: "تطبیق دستی واریز و تأیید پرداخت", ADMIN_EXPEDITED: "تسریع فعال‌سازی توسط مدیر", ADMIN_CANCEL: "لغو سفارش پرداخت‌نشده توسط مدیر", SMS_APPROVED: "تأیید خودکار با پیامک بانک", ADMIN_APPROVED: "تأیید دستی مدیر", ADMIN_REJECTED: "رد دستی مدیر" };
const activityLabels = {
  ...decisionLabels, LOGIN: "ورود به پنل", LOGOUT: "خروج از پنل",
  RELEASE_UPDATED: "تغییر نسخهٔ برنامه", WALLET_PAID: "پرداخت از کیف پول", CUSTOMER_CANCELLED: "لغو اشتراک و ثبت اعتبار کیف پول", CATALOG_UPDATED: "تغییر پلن فروش", CARD_UPDATED: "تغییر کارت مقصد", PROVISIONING_UPDATED: "تغییر تنظیمات ساخت اشتراک",
};
const date = (value) => value ? fullDate.format(value) : "—";
const money = (value) => fa.format(value) + " تومان";
let active = null;
let activeLoad = null;
let orderPage = 1;
let depositPage = 1;
let activityPage = 1;
let detailVersion = 0;
let saving = false;
let chosenGroups = [];
let releases = {};
let uploadedAPKs = [];
let chartSeries = [];
let showingCount = false;
let chartTotals;

function node(tag, text, className) {
  const element = document.createElement(tag);
  if (text != null) element.textContent = text;
  if (className) element.className = className;
  return element;
}

function button(text, action, style = "subtle") {
  const element = node("button", text, "button " + style);
  element.type = "button";
  element.addEventListener("click", action);
  return element;
}

function setNotice(text = "", error = false, target = get("notice")) {
  target.textContent = text;
  target.hidden = !text;
  target.toggleAttribute("data-error", error);
}

async function request(path, options = {}) {
  const { headers = {}, signal, raw = false, ...rest } = options;
  let response;
  try {
    const timeout = AbortSignal.timeout(12000);
    response = await fetch("/admin" + path, {
      ...rest, headers: { ...headers, Accept: "application/json" }, cache: "no-store",
      credentials: "same-origin", signal: signal ? AbortSignal.any([signal, timeout]) : timeout,
    });
  } catch (error) {
    if (error.name === "AbortError") throw error;
    throw new Error("ارتباط با سرویس برقرار نشد. دوباره امتحان کنید.");
  }
  if (response.status === 401) {
    location.replace("/admin/login");
    throw new Error("نشست شما پایان یافته است. دوباره وارد شوید.");
  }
  if (!response.ok) {
    if (path === "/api/releases/android" && response.status === 409) {
      throw new Error("مخزن GitHub برای به‌روزرسانی خودکار پیکربندی نشده است.");
    }
    if (raw && response.status === 422) {
      throw new Error("برای دریافت CSV با کمتر از ۱۰ هزار سفارش، فیلترهای دقیق‌تری انتخاب کنید.");
    }
    const detail = (await response.json().catch(() => null))?.detail;
    const messages = {
      MATCH_UNAVAILABLE: "این واریز یا سفارش دیگر برای تطبیق در دسترس نیست.",
      DEPOSIT_BELOW_PLAN_PRICE: "مبلغ واریزی کمتر از قیمت پلن است.",
      DEPOSIT_ALREADY_ACCOUNTED: "سفارشی با مبلغ این واریز قبلاً تأیید شده است.",
      MATCH_REASON_REQUIRED: "دلیل تطبیق را وارد کنید.",
    };
    if (typeof detail === "string" && Object.hasOwn(messages, detail)) throw new Error(messages[detail]);
    const errors = {
      403: "این درخواست از دامنهٔ فعلی پذیرفته نشد.",
      404: "این سفارش پیدا نشد.",
      409: "سفارش قبلاً بررسی شده است. وضعیت را دوباره بارگیری کنید.",
      422: "اطلاعات واردشده را بررسی کنید.",
    };
    throw new Error(errors[response.status] || "سرویس پاسخ نداد. دوباره تلاش کنید.");
  }
  if (raw) return response;
  return response.status === 204 ? null : response.json();
}

function status(value) {
  const element = node("span", statusLabels[value] || "نامشخص", "tag");
  element.dataset.state = value;
  return element;
}

function table(target, columns, rows, empty) {
  if (!rows.length) {
    target.replaceChildren(node("p", empty, "empty"));
    return;
  }
  const element = node("table");
  const header = element.createTHead().insertRow();
  columns.forEach(([label, className]) => {
    const cell = node("th", label, className);
    cell.scope = "col";
    header.append(cell);
  });
  const body = element.createTBody();
  for (const values of rows) {
    const row = body.insertRow();
    values.forEach((value, index) => {
      const cell = row.insertCell();
      cell.dataset.label = columns[index][0];
      if (columns[index][1]) cell.className = columns[index][1];
      if (value instanceof Node) cell.append(value);
      else cell.textContent = value ?? "—";
    });
  }
  target.replaceChildren(element);
}

function ordersTable(target, orders) {
  table(target, [["اشتراک و پلن"], ["مبلغ"], ["وضعیت"], ["زمان ثبت", "optional"], ["جزئیات"]], orders.map((order) => {
    const customer = node("div");
    customer.append(node("strong", order.subscriber || "—"), node("p", order.plan_title_fa));
    if (order.has_receipt_image) customer.append(node("span", "عکس فیش", "receipt-flag"));
    return [
      customer, money(order.final_price_toman), status(order.status), date(order.created_at_ms),
      button(order.status === "PENDING_REVIEW" ? "بررسی" : "باز کردن", () => showOrder(order.id), "text-button"),
    ];
  }), "سفارشی در این فهرست پیدا نشد.");
}

function pages(target, total, size, current, change) {
  const pageCount = Math.max(1, Math.ceil(total / size));
  const actions = node("div");
  const previous = button("قبلی", () => change(current - 1));
  const next = button("بعدی", () => change(current + 1));
  previous.disabled = current <= 1;
  next.disabled = current >= pageCount;
  actions.append(previous, next);
  const info = node("span", fa.format(total) + " مورد · صفحهٔ " + fa.format(current) + " از " + fa.format(pageCount));
  target.replaceChildren(info, actions);
}

function renderChart() {
  const series = chartSeries;
  const diagram = get("chart");
  diagram.classList.toggle("dense", series.length > 7);
  const metric = showingCount ? "count" : "amount";
  get("chart-title").textContent = showingCount ? "تعداد سفارش‌های تأییدشده" : "مبلغ تأییدشده";
  get("chart-unit").textContent = showingCount ? "سفارش" : "تومان";
  if (chartTotals) get("revenue").textContent = fa.format(chartTotals[metric]);
  const peak = Math.max(0, ...series.map((point) => point[metric]));
  const targetStep = Math.max(1, peak / 4);
  const magnitude = 10 ** Math.floor(Math.log10(targetStep));
  const step = [1, 2, 2.5, 5, 10].map((factor) => factor * magnitude)
    .find((value) => value >= targetStep && (!showingCount || Number.isInteger(value)));
  const axis = get("chart-axis");
  axis.replaceChildren(...(peak ? [4, 3, 2, 1, 0] : [0]).map((tick) =>
    node("span", (showingCount ? fa : compact).format(tick * step))));
  const tip = get("chart-tip");
  tip.textContent = peak ? "برای دیدن جزئیات، یک روز را انتخاب کنید." :
    series.length ? "در این بازه سفارش تأییدشده‌ای ثبت نشده است." : "";
  diagram.setAttribute("aria-label", showingCount ? "روند روزانهٔ تعداد سفارش‌های تأییدشده" :
    "روند روزانهٔ مبلغ تأییدشده");
  diagram.replaceChildren(...series.map((point) => {
    const description = dayLabel.format(point.start) + "، " + money(point.amount) + " · " + fa.format(point.count) + " سفارش";
    const wrap = node("button", null, "bar-wrap");
    wrap.type = "button";
    wrap.title = description;
    wrap.setAttribute("aria-label", description);
    wrap.addEventListener("click", () => { tip.textContent = description; });
    const bar = node("progress");
    bar.max = step * 4;
    bar.value = point[metric];
    bar.setAttribute("aria-hidden", "true");
    wrap.append(bar, node("time", dayLabel.format(point.start)));
    return wrap;
  }));
}

function renderReviewQueue(orders) {
  const queue = get("review-queue");
  if (!orders.length) {
    queue.replaceChildren(node("p", "رسیدی در صف بررسی نیست.", "empty"));
    return;
  }
  queue.replaceChildren(...orders.map((order) => {
    const item = node("div", null, "review-item");
    const identity = node("div");
    identity.append(node("h3", order.subscriber || "اشتراک جدید"), node("p", order.plan_title_fa));
    if (order.has_receipt_image) identity.append(node("span", "عکس فیش", "receipt-flag"));
    const actions = node("div", null, "review-side");
    actions.append(node("strong", money(order.final_price_toman)),
      button("بررسی", () => showOrder(order.id), "text-button"));
    item.append(identity, actions);
    return item;
  }));
}

async function overview(signal) {
  const data = await request("/api/overview?days=" + get("period").value, { signal });
  if (signal.aborted) return;
  chartTotals = { amount: data.approved_amount, count: data.approved_count };
  get("hero-amount").textContent = fa.format(data.approved_amount);
  get("average-order").textContent = data.approved_count ? money(Math.round(data.approved_amount / data.approved_count)) : "—";
  const peak = data.series.reduce((best, day) => day.amount > (best?.amount || 0) ? day : best, null);
  get("peak-day").textContent = peak ? dayLabel.format(peak.start) + " · " + money(peak.amount) : "—";
  get("activation-count").textContent = fa.format(data.activating_count ?? 0);
  get("oldest-activation").textContent = data.oldest_activation_ms ?
    "قدیمی‌ترین تأیید منتظر فعال‌سازی " + date(data.oldest_activation_ms) : "";
  get("paid-metric").textContent = fa.format(data.approved_count);
  get("awaiting-metric").textContent = fa.format(data.awaiting_count);
  get("review-metric").textContent = fa.format(data.pending_count);
  const oldestReview = get("oldest-review");
  oldestReview.hidden = !data.oldest_pending_ms;
  oldestReview.textContent = data.oldest_pending_ms ? "ثبت قدیمی‌ترین سفارش صف " + date(data.oldest_pending_ms) : "";
  get("approved").textContent = fa.format(data.approved_count);
  get("automatic").textContent = fa.format(data.automatic_count);
  get("manual").textContent = fa.format(data.approved_count - data.automatic_count);
  get("pending").textContent = fa.format(data.pending_count);
  get("unmatched").textContent = fa.format(data.unmatched_count);
  get("bank-name").textContent = data.bank || "کارت مقصد فعال نشده";
  get("card-preview").textContent = data.card_last4 ? "•••• •••• •••• " + data.card_last4 : "—";
  const accountMode = data.sms_account_configured ? "شناسهٔ حساب" : "شناسهٔ حساب ثبت نشده";
  get("sms-mode").textContent = data.sms_format === "blu" ? "بلو" : accountMode;
  ordersTable(get("recent"), data.orders);
  renderReviewQueue(data.review_queue);
  chartSeries = data.series;
  renderChart();
}

function orderFilters() {
  return new URLSearchParams({
    q: get("search").value.trim(), status: get("order-status").value, receipt: get("receipt-filter").value, activation: get("activation-filter").value,
  });
}

async function orderList(signal) {
  const query = orderFilters();
  query.set("page", orderPage);
  const data = await request("/api/orders?" + query, { signal });
  if (signal.aborted) return;
  ordersTable(get("orders-table"), data.orders);
  pages(get("orders-pages"), data.total, data.page_size, orderPage, (page) => { orderPage = page; load(); });
}

async function depositList(signal) {
  const query = new URLSearchParams({ page: depositPage, match: get("deposit-match").value });
  const data = await request("/api/deposits?" + query, { signal });
  if (signal.aborted) return;
  table(get("deposits-table"), [["مبلغ"], ["زمان دریافت"], ["وضعیت تطبیق"], ["سفارش"]], data.deposits.map((deposit) => [
    money(deposit.amount), date(deposit.received), status(deposit.order_id ? "matched" : "unmatched"),
    deposit.order_id ? button("مشاهده", () => showOrder(deposit.order_id), "text-button") :
      button("تطبیق دستی", () => openDepositMatch(deposit), "text-button"),
  ]), "واریزی در این فهرست پیدا نشد.");
  pages(get("deposits-pages"), data.total, data.page_size, depositPage, (page) => { depositPage = page; load(); });
}

async function catalogList(signal) {
  const data = await request("/api/catalog", { signal });
  if (signal.aborted) return;
  get("display-limit").value = data.maximum;
  const offered = new Set(data.offered);
  const reasons = { hidden: "مخفی", category: "نوع پلن قابل فروش نیست", limit: "خارج از سقف نمایش" };
  function renderPreview() {
    get("preview-title").textContent = get("preview-mode").value === "new" ? "خرید اشتراک" : "تمدید اشتراک";
    const cards = data.offered.map(id => {
      const plan = data.plans.find(item => item.id === id);
      const card = node("article", null, "preview-plan");
      card.append(node("strong", plan.title_fa), node("span", money(plan.price_toman)),
        node("span", fa.format(plan.duration_days) + " روز · " +
          (plan.data_limit_gb == null ? "نامحدود" : fa.format(plan.data_limit_gb) + " گیگابایت")),
        button("ویرایش پلن", () => editPlan(plan), "text-button"));
      return card;
    });
    get("preview-plans").replaceChildren(...(cards.length ? cards : [node("p", "پلن قابل خرید وجود ندارد.")]));
  }
  get("preview-mode").onchange = renderPreview;
  renderPreview();
  function renderPlans() {
    const query = get("plan-search").value.trim().toLocaleLowerCase();
    const plans = data.plans.filter(plan => [plan.title_fa, plan.title_en, plan.id].join(" ").toLocaleLowerCase().includes(query));
    const shelf = get("catalog-table");
    shelf.replaceChildren(...plans.map(plan => {
      const card = node("article", null, "surface plan-tile");
      const heading = node("div", null, "section-head");
      heading.append(node("h2", plan.title_fa), node("small", offered.has(plan.id) ? "در حال فروش" : (reasons[data.excluded[plan.id]] || "خارج از فهرست فروش")));
      const facts = node("dl", null, "details-list");
      for (const [label, value] of [["حجم", plan.data_limit_gb == null ? "نامحدود" : fa.format(plan.data_limit_gb) + " گیگابایت"],
        ["مدت", fa.format(plan.duration_days) + " روز"], ["ترتیب نمایش", fa.format(plan.sort_order)]]) {
        const line = node("div");
        line.append(node("dt", label), node("dd", value));
        facts.append(line);
      }
      card.append(heading, node("strong", money(plan.price_toman), "plan-price"), facts, button("ویرایش پلن", () => editPlan(plan), "subtle full"));
      if (offered.has(plan.id) && data.amount_capacity?.[plan.price_toman] != null) {
        card.append(node("p", fa.format(data.amount_capacity[plan.price_toman]) + " مبلغ قابل رزرو در بازهٔ این قیمت", "text-muted"));
      }
      card.append(button("ساخت پلن مشابه", () => editPlan({...plan, id: "", title_fa: plan.title_fa + " (کپی)", visible: false}), "text-button"));
      return card;
    }));
    if (!plans.length) shelf.append(node("p", "پلنی پیدا نشد.", "empty"));
  }
  get("plan-search").oninput = renderPlans;
  renderPlans();
}

for (const control of document.querySelectorAll("[data-settings]")) {
  control.addEventListener("click", () => {
    for (const item of document.querySelectorAll("[data-settings]")) {
      const active = item === control;
      item.setAttribute("aria-pressed", String(active));
      get(item.getAttribute("aria-controls")).hidden = !active;
    }
  });
}

get("display-form").addEventListener("submit", async (event) => {
  event.preventDefault();
  try {
    await request("/api/catalog-display", {method: "POST", body: new URLSearchParams({maximum: get("display-limit").value})});
    await load();
    setNotice("تعداد پلن‌های قابل نمایش ذخیره شد.");
  } catch (error) { setNotice(error.message, true); }
});

function editPlan(plan = {category: "RENEW", visible: true, sort_order: 100}) {
  const form = get("plan-form");
  form.reset();
  for (const control of form.elements) {
    const value = plan[control.name];
    if (!control.name) continue;
    if (control.type === "checkbox") control.checked = Boolean(value);
    else control.value = Array.isArray(value) ? value.join("\n") : value ?? "";
  }
  form.elements.namedItem("id").readOnly = Boolean(plan.id);
  get("plan-dialog-title").textContent = plan.id ? "ویرایش پلن" : "پلن جدید";
  setNotice("", false, get("plan-notice"));
  get("plan-dialog").showModal();
}

async function activityList(signal) {
  const query = new URLSearchParams({ page: activityPage, kind: get("activity-kind").value });
  const data = await request("/api/activity?" + query, { signal });
  if (signal.aborted) return;
  table(get("activity-table"), [["فعالیت"], ["زمان"], ["سفارش"]], data.events.map((event) => [
    activityLabels[event.action] || "فعالیت پنل", date(event.timestamp),
    event.order_id ? button("مشاهده", () => showOrder(event.order_id), "text-button") : "—",
  ]), "فعالیتی برای این فیلتر ثبت نشده است.");
  pages(get("activity-pages"), data.total, data.page_size, activityPage, (page) => { activityPage = page; load(); });
}

function last4(value) {
  if (!value) return node("span", "—");
  const element = node("span", "•••• " + value, "mono");
  element.dir = "ltr";
  return element;
}

async function settings(signal) {
  const [data, provision, appReleases, library] = await Promise.all([
    request("/api/card", { signal }), request("/api/provisioning", { signal }),
    request("/api/releases", { signal }), request("/api/apk", { signal }),
  ]);
  if (signal.aborted) return;
  releases = appReleases;
  uploadedAPKs = library.files;
  fillApkLibrary();
  fillReleaseEditor();
  chosenGroups = provision.selected_group_ids;
  get("provision-state").textContent = provision.configured
    ? "اتصال ثبت شده است. برای تغییر گروه‌ها، فهرست را دریافت کنید."
    : "کلید دارای دسترسی دیدن گروه‌ها و ساخت اشتراک را وارد کنید.";
  get("provision-key").value = "";
  get("provision-key").placeholder = provision.configured ? "خالی بگذارید تا کلید فعلی استفاده شود" : "";
  resetGroupOptions();
  get("card-number").value = data.card.number.replace(/(.{4})(?=.)/g, "$1 ");
  get("card-holder").value = data.card.holder;
  get("card-bank").value = data.card.bank;
  get("same-account").checked = false;
  table(get("card-history"), [["زمان تغییر"], ["کارت قبلی"], ["کارت جدید"]], data.history.map((change) => [
    date(change.timestamp), last4(change.previous_last4), last4(change.current_last4),
  ]), "هنوز تغییری از طریق پنل ثبت نشده است.");
}

function fillReleaseEditor() {
  const platform = get("release-platform").value;
  const ios = platform === "ios";
  const form = get("release-form").elements;
  const release = releases[platform] ?? {};
  get("release-ios-field").hidden = !ios;
  get("release-android-field").hidden = ios;
  get("release-upload").hidden = ios;
  get("release-github").hidden = ios || !releases.android;
  form.namedItem("latest_version").value = release.latest_version ?? "";
  form.namedItem("latest_version_code").value = release.latest_version_code ?? "";
  form.namedItem("package_name").value = release.package_name ?? (ios ? "com.pasarguard.vpn.ios" : "com.pasarguard.vpn");
  form.namedItem("store_url").value = release.store_url ?? "";
  form.namedItem("assets").value = (release.assets ?? []).map(item => [item.abi, item.download_url, item.sha256].join(" ")).join("\n");
  form.namedItem("changelog").value = release.changelog ?? "";
  form.namedItem("visible").checked = Boolean(release.visible);
}

function fillApkLibrary() {
  const picker = get("apk-library");
  picker.replaceChildren(new Option("انتخاب فایل", ""));
  for (const file of uploadedAPKs) {
    const label = date(file.timestamp) + " · " + fa.format(Math.ceil(file.size / 1048576)) + " مگابایت · " + file.sha256.slice(0, 8);
    picker.append(new Option(label, file.sha256));
  }
}

function selectApk(file, abi) {
  const field = get("release-form").elements.namedItem("assets");
  const rows = field.value.split("\n").filter(line => line.trim() && line.trim().split(/\s+/)[0] !== abi);
  field.value = [...rows, [abi, file.download_url, file.sha256].join(" ")].join("\n");
  const link = get("apk-link");
  link.href = file.download_url;
  link.hidden = false;
  get("apk-copy").hidden = false;
}

get("apk-library").addEventListener("change", (event) => {
  const file = uploadedAPKs.find(item => item.sha256 === event.target.value);
  if (file) selectApk(file, get("apk-abi").value);
});

get("apk-copy").addEventListener("click", async () => {
  try {
    await navigator.clipboard.writeText(get("apk-link").href);
    setNotice("لینک عمومی کپی شد.");
  } catch {
    setNotice("لینک را از دکمهٔ باز کردن دانلود کپی کنید.", true);
  }
});

get("apk-upload").addEventListener("click", async (event) => {
  const file = get("apk-file").files[0];
  const trigger = event.currentTarget;
  const progress = get("apk-progress");
  const picker = get("release-platform");
  const link = get("apk-link");
  if (!file || !file.name.toLowerCase().endsWith(".apk") || file.size > 150 * 1024 * 1024) {
    setNotice("فایل APK تا ۱۵۰ مگابایت را انتخاب کنید.", true);
    return;
  }
  const id = crypto.randomUUID().replaceAll("-", "");
  const abi = get("apk-abi").value;
  const endpoint = "/api/apk/" + id;
  trigger.disabled = true;
  picker.disabled = true;
  progress.hidden = false;
  progress.value = 0;
  link.hidden = true;
  get("apk-copy").hidden = true;
  try {
    const chunk = 512 * 1024;
    for (let offset = 0; offset < file.size; offset += chunk) {
      const end = Math.min(offset + chunk, file.size);
      await request(endpoint + "?offset=" + offset, { method: "PUT", body: file.slice(offset, end) });
      progress.value = 100 * end / file.size;
    }
    const result = await request(endpoint + "?size=" + file.size, { method: "POST" });
    const stored = { ...result, size: file.size, timestamp: Date.now() };
    uploadedAPKs = [stored, ...uploadedAPKs.filter(item => item.sha256 !== stored.sha256)].slice(0, 30);
    fillApkLibrary();
    get("apk-library").value = stored.sha256;
    selectApk(stored, abi);
    setNotice("فایل بارگذاری شد. برای انتشار، شمارهٔ ساخت و نمایش نسخه را تنظیم و ذخیره کنید.");
  } catch (error) {
    setNotice(error.message, true);
  } finally {
    trigger.disabled = false;
    picker.disabled = false;
  }
});

get("release-platform").addEventListener("change", fillReleaseEditor);
get("release-github").addEventListener("click", async (event) => {
  const trigger = event.currentTarget;
  const dialog = get("source-dialog");
  dialog.returnValue = "";
  const answer = new Promise(resolve => dialog.addEventListener("close", () => resolve(dialog.returnValue), { once: true }));
  dialog.showModal();
  if (await answer !== "apply") return;
  trigger.disabled = true;
  try {
    await request("/api/releases/android", { method: "DELETE" });
    await load();
    setNotice("منبع نسخهٔ اندروید به GitHub تغییر کرد.");
  } catch (error) {
    setNotice(error.message, true);
  } finally {
    trigger.disabled = false;
  }
});
get("release-form").addEventListener("submit", async (event) => {
  event.preventDefault();
  const form = event.currentTarget;
  const platform = get("release-platform").value;
  const submit = form.querySelector("button[type=submit]");
  if (submit.disabled) return;
  submit.disabled = true;
  const fields = new FormData(form);
  const assets = platform === "android" ? fields.get("assets").trim().split("\n").filter(Boolean).map(line => {
    const [abi, download_url, sha256] = line.trim().split(/\s+/);
    return { abi, download_url, sha256: sha256?.toLowerCase() };
  }) : [];
  try {
    await request("/api/releases/" + platform, {
      method: "PUT", headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        latest_version: fields.get("latest_version").trim(), latest_version_code: Number(fields.get("latest_version_code")),
        package_name: fields.get("package_name").trim(), changelog: fields.get("changelog").trim(),
        visible: fields.has("visible"), store_url: platform === "ios" ? fields.get("store_url").trim() || null : null, assets,
      }),
    });
    await load();
    setNotice("تنظیمات نسخه ذخیره شد.");
  } catch (error) {
    setNotice(error.message, true);
  } finally {
    submit.disabled = false;
  }
});

function resetGroupOptions() {
  get("group-options").hidden = true;
  get("provision-groups").replaceChildren();
  get("save-provision").disabled = true;
}

function selectedGroups() {
  return Array.from(get("provision-groups").querySelectorAll("input:checked"), (input) => Number(input.value));
}

async function saveProvisionRequest(path, data) {
  return request(path, { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(data) });
}

const loaders = { overview, orders: orderList, deposits: depositList, activity: activityList, catalog: catalogList, settings };

async function load() {
  activeLoad?.abort();
  const controller = new AbortController();
  activeLoad = controller;
  get("refresh").disabled = true;
  get(active).setAttribute("aria-busy", "true");
  setNotice();
  try {
    await loaders[active](controller.signal);
    if (!controller.signal.aborted) get("updated").textContent = "آخرین به‌روزرسانی " + date(Date.now());
  } catch (error) {
    if (!controller.signal.aborted) setNotice(error.message, true);
  } finally {
    if (activeLoad === controller) {
      get("refresh").disabled = false;
      get(active).removeAttribute("aria-busy");
    }
  }
}

function navigate() {
  const hash = location.hash.slice(1);
  const tab = Object.hasOwn(titles, hash) ? hash : "overview";
  if (active === tab) return;
  active = tab;
  get("title").textContent = titles[tab];
  for (const view of document.querySelectorAll("[data-view]")) {
    view.hidden = view.id !== tab;
    view.removeAttribute("aria-busy");
  }
  for (const link of document.querySelectorAll("[data-tab]")) {
    if (link.dataset.tab === tab) link.setAttribute("aria-current", "page");
    else link.removeAttribute("aria-current");
  }
  load();
}

function detailList(order) {
  const list = node("dl", null, "details-list");
  const fields = [
    ["وضعیت", status(order.status)], ["اشتراک", order.subscriber],
    ...(order.new_subscription ? [["ساخت اشتراک", order.activation_status === "READY" ? "آماده" :
      order.activation_status === "PENDING" ? "در صف فعال‌سازی" : "پس از تأیید پرداخت"]] : []),
    ["پلن", order.plan_title_fa], ["مبلغ دقیق", money(order.final_price_toman)],
    ["شماره پیگیری", order.tracking_number], ["چهار رقم کارت پرداخت‌کننده", order.payer_card_last4],
    ["کارت مقصد هنگام ثبت", order.destination_card?.card_number ? last4(order.destination_card.card_number.slice(-4)) : "—"],
    ["دلیل تطبیق دستی", order.reconciliation_reason],
    ["زمان ثبت رسید", order.submitted_at_ms ? date(order.submitted_at_ms) : "—"],
    ["زمان تصمیم", order.reviewed_at_ms ? date(order.reviewed_at_ms) : "—"],
    ["آماده‌شدن اشتراک", order.activation_ready_at_ms ? date(order.activation_ready_at_ms) : "—"],
    ["زمان ثبت", date(order.created_at_ms)], ["مهلت پرداخت", date(order.expires_at_ms)],
  ];
  for (const [title, value] of fields) {
    const row = node("div");
    const description = node("dd");
    if (value instanceof Node) description.append(value);
    else description.textContent = value ?? "—";
    row.append(node("dt", title), description);
    list.append(row);
  }
  return list;
}

async function showOrder(orderId) {
  const version = ++detailVersion;
  const dialog = get("order-dialog");
  const content = get("detail-content");
  get("detail-id").textContent = orderId;
  content.replaceChildren(node("p", "در حال دریافت سفارش…"));
  if (!dialog.open) dialog.showModal();
  try {
    const data = await request("/api/orders/" + encodeURIComponent(orderId));
    if (version !== detailVersion || !dialog.open) return;
    const errorNotice = node("p", "", "notice");
    errorNotice.role = "status";
    errorNotice.hidden = true;
    const activity = node("ul", null, "activity");
    for (const event of data.decisions) {
      const entry = node("li", (decisionLabels[event.decision] || "بررسی سفارش") + " · " + date(event.timestamp));
      if (event.reason) entry.append(node("p", event.reason));
      activity.append(entry);
    }
    content.replaceChildren(errorNotice, detailList(data.order), node("h3", "سابقهٔ بررسی"),
      data.decisions.length ? activity : node("p", "هنوز تصمیمی ثبت نشده است."));
    if (data.activation && !data.activation.ready) {
      const job = data.activation;
      const inFlight = job.lease_until > Date.now();
      const elapsed = Math.max(0, Math.floor((Date.now() - (data.order.reviewed_at_ms || data.order.created_at_ms)) / 60000));
      const panel = node("section", null, "confirmation");
      const timing = inFlight ? "در حال اجرا" : job.next_run <= Date.now() ? "در صف اجرا" : "تلاش بعدی " + date(job.next_run);
      panel.append(node("h3", "فعال‌سازی"), node("p", timing + " · " + fa.format(job.attempts) +
        " تلاش · " + fa.format(elapsed) + " دقیقه انتظار"));
      if (job.last_error) panel.append(node("p", "تلاش قبلی با خطای ارتباط یا پاسخ پنل روبه‌رو شد."));
      const reason = node("textarea");
      reason.rows = 2;
      reason.maxLength = 300;
      const label = node("label", "دلیل تسریع", "field");
      label.append(reason);
      const expedite = button("تسریع فعال‌سازی", async () => {
        expedite.disabled = true;
        try {
          await request("/api/orders/" + encodeURIComponent(orderId) + "/expedite", {
            method: "POST", body: new URLSearchParams({reason: reason.value.trim()}),
          });
          if (dialog.open && version === detailVersion) await showOrder(orderId);
        } catch (error) {
          setNotice(error.message, true, errorNotice);
          expedite.disabled = false;
        }
      }, "subtle");
      const canExpedite = !inFlight && job.next_run > Date.now();
      if (canExpedite) {
        expedite.disabled = true;
        reason.addEventListener("input", () => { expedite.disabled = reason.value.trim().length < 5; });
        panel.append(label, expedite);
      }
      content.append(panel);
    }
    if (data.order.has_receipt_image) {
      const receipt = node("a", null, "receipt-preview");
      receipt.href = "/admin/api/orders/" + encodeURIComponent(orderId) + "/receipt";
      receipt.target = "_blank";
      receipt.rel = "noopener";
      const image = node("img");
      image.src = receipt.href;
      image.alt = "عکس فیش پرداخت";
      image.addEventListener("error", () => {
        image.replaceWith(node("p", "پیش‌نمایش بارگیری نشد."));
      });
      receipt.append(image, node("span", "باز کردن تصویر کامل فیش"));
      errorNotice.after(receipt);
    }
    if (["PENDING_REVIEW", "AWAITING_RECEIPT"].includes(data.order.status)) {
      const actions = node("div", null, "dialog-actions");
      const confirmation = node("div", null, "confirmation");
      confirmation.hidden = true;
      const question = node("p");
      const choices = node("div", null, "dialog-actions");
      let decision;
      let trigger;
      const confirm = button("ثبت تصمیم", async () => {
        confirm.disabled = true;
        cancel.disabled = true;
        setNotice("", false, errorNotice);
        try {
          await request("/orders/" + encodeURIComponent(orderId), { method: "POST", body: new URLSearchParams({ decision }) });
          if (version === detailVersion && dialog.open) {
            await showOrder(orderId);
            dialog.querySelector("button").focus();
          }
          await load();
          setNotice("نتیجهٔ بررسی ثبت شد.");
        } catch (error) {
          setNotice(error.message, true, errorNotice);
          errorNotice.scrollIntoView({ block: "nearest" });
          confirm.disabled = false;
          cancel.disabled = false;
        }
      }, "primary");
      const cancel = button("انصراف", () => {
        confirmation.hidden = true;
        actions.hidden = false;
        trigger.focus();
      });
      function ask(event, value, text) {
        trigger = event.currentTarget;
        decision = value;
        question.textContent = text;
        confirm.className = value === "APPROVED" ? "button primary" : "button danger";
        actions.hidden = true;
        confirmation.hidden = false;
        cancel.focus();
      }
      if (data.order.status === "AWAITING_RECEIPT") {
        actions.append(button("لغو سفارش پرداخت‌نشده", event => ask(event, "CANCEL", "این سفارش بسته شود؟ پس از لغو، ارسال رسید برای آن ممکن نیست."), "danger"));
      } else actions.append(
        button("تأیید واریز", (event) => ask(event, "APPROVED", "واریز را با گردش حساب بانک تطبیق داده‌اید؟"), "primary"),
        button("رد رسید", (event) => ask(event, "REJECTED", "رسید این سفارش رد شود؟"), "danger"),
      );
      choices.append(confirm, cancel);
      confirmation.append(question, choices);
      content.append(actions, confirmation);
    }
  } catch (error) {
    if (version === detailVersion && dialog.open) content.replaceChildren(node("p", error.message, "empty"));
  }
}

get("order-dialog").addEventListener("close", () => { detailVersion++; });
window.addEventListener("hashchange", navigate);
document.querySelector(".skip").addEventListener("click", (event) => {
  event.preventDefault();
  get("main").focus();
  get("main").scrollIntoView();
});
get("today").textContent = today.format(Date.now());
get("refresh").addEventListener("click", () => load());
get("period").addEventListener("change", () => load());
get("order-filters").addEventListener("submit", (event) => { event.preventDefault(); orderPage = 1; load(); });
for (const id of ["order-status", "receipt-filter"]) {
  get(id).addEventListener("change", () => { orderPage = 1; load(); });
}
get("global-search-form").addEventListener("submit", (event) => {
  event.preventDefault();
  get("search").value = get("global-search").value.trim();
  get("order-status").value = "";
  get("receipt-filter").value = "";
  get("activation-filter").value = "";
  orderPage = 1;
  if (active === "orders") load();
  else location.hash = "orders";
});
get("chart-count-toggle").addEventListener("click", (event) => {
  showingCount = !showingCount;
  event.currentTarget.setAttribute("aria-pressed", String(showingCount));
  event.currentTarget.textContent = showingCount ? "نمایش مبلغ" : "نمایش تعداد";
  renderChart();
});
const themePreference = window.matchMedia("(prefers-color-scheme: dark)");
let savedTheme;
try { savedTheme = localStorage.getItem("billing-theme"); } catch {}
function setTheme(dark) {
  document.documentElement.dataset.theme = dark ? "dark" : "light";
  get("theme").setAttribute("aria-pressed", String(dark));
}
setTheme(savedTheme ? savedTheme === "dark" : themePreference.matches);
themePreference.addEventListener("change", (event) => {
  if (!savedTheme) setTheme(event.matches);
});
get("theme").addEventListener("click", () => {
  const dark = document.documentElement.dataset.theme !== "dark";
  savedTheme = dark ? "dark" : "light";
  setTheme(dark);
  try { localStorage.setItem("billing-theme", savedTheme); } catch {}
});
get("deposit-match").addEventListener("change", () => { depositPage = 1; load(); });
get("activity-kind").addEventListener("change", () => { activityPage = 1; load(); });
function applyStatus(status) {
  get("search").value = "";
  get("order-status").value = status;
  get("receipt-filter").value = "";
  get("activation-filter").value = "";
  orderPage = 1;
}
for (const id of ["pending-link", "review-metric-link"]) {
  get(id).addEventListener("click", () => applyStatus("PENDING_REVIEW"));
}
get("paid-link").addEventListener("click", () => applyStatus("APPROVED"));
get("awaiting-link").addEventListener("click", () => applyStatus("AWAITING_RECEIPT"));
get("export-orders").addEventListener("click", async (event) => {
  const trigger = event.currentTarget;
  trigger.disabled = true;
  setNotice();
  try {
    const response = await request("/api/exports/orders?" + orderFilters(), { raw: true });
    const download = node("a");
    const url = URL.createObjectURL(await response.blob());
    download.href = url;
    download.download = "pasarguard-orders.csv";
    document.body.append(download);
    download.click();
    download.remove();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  } catch (error) {
    setNotice(error.message, true);
  } finally {
    trigger.disabled = false;
  }
});
get("unmatched-link").addEventListener("click", () => { get("deposit-match").value = "unmatched"; depositPage = 1; });

get("card-form").addEventListener("submit", async (event) => {
  event.preventDefault();
  if (saving) return;
  saving = true;
  const submit = event.currentTarget.querySelector("button");
  submit.disabled = true;
  try {
    const payload = { number: get("card-number").value, holder: get("card-holder").value,
      bank: get("card-bank").value, same_account: get("same-account").checked };
    await request("/api/card", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(payload) });
    await load();
    setNotice("کارت مقصد سفارش‌های جدید ذخیره شد.");
  } catch (error) {
    setNotice(error.message, true);
  } finally {
    saving = false;
    submit.disabled = false;
  }
});

get("provision-key").addEventListener("input", resetGroupOptions);
get("load-groups").addEventListener("click", async () => {
  const button = get("load-groups");
  const api_key = get("provision-key").value;
  button.disabled = true;
  resetGroupOptions();
  setNotice();
  try {
    const result = await saveProvisionRequest("/api/provisioning/groups", { api_key });
    if (api_key !== get("provision-key").value || active !== "settings") return;
    if (!result.groups.length) { setNotice("کلید API به هیچ گروه سروری دسترسی ندارد.", true); return; }
    for (const group of result.groups) {
      const label = node("label", null, "check");
      const input = node("input");
      input.type = "checkbox";
      input.value = group.id;
      input.checked = chosenGroups.includes(group.id) || result.groups.length === 1;
      input.addEventListener("change", () => { get("save-provision").disabled = !selectedGroups().length; });
      label.append(input, document.createTextNode(group.name));
      get("provision-groups").append(label);
    }
    get("group-options").hidden = false;
    get("save-provision").disabled = !selectedGroups().length;
  } catch {
    setNotice("دریافت گروه‌ها ممکن نشد. کلید API و دسترسی مشاهدهٔ گروه‌ها را بررسی کنید.", true);
  } finally {
    button.disabled = false;
  }
});
get("provision-form").addEventListener("submit", async (event) => {
  event.preventDefault();
  const submit = get("save-provision");
  if (submit.disabled) return;
  const group_ids = selectedGroups();
  if (!group_ids.length) return;
  submit.disabled = true;
  try {
    await saveProvisionRequest("/api/provisioning", { api_key: get("provision-key").value, group_ids });
    await load();
    setNotice("اتصال ثبت شد. سفارش‌های جدید پس از تأیید پرداخت فعال می‌شوند.");
  } catch (error) {
    setNotice(error.message, true);
    submit.disabled = false;
  }
});

get("new-plan").addEventListener("click", () => editPlan());
get("close-plan").addEventListener("click", () => get("plan-dialog").close());
get("plan-form").addEventListener("submit", async (event) => {
  event.preventDefault();
  const form = event.currentTarget;
  const submit = form.querySelector("button[type=submit]");
  if (submit.disabled) return;
  const fields = new FormData(form);
  const id = fields.get("id").trim();
  fields.delete("id");
  const data = Object.fromEntries(fields);
  for (const name of ["price_toman", "old_price_toman", "duration_days", "data_limit_gb", "max_devices", "sort_order"]) {
    data[name] = data[name] === "" ? null : Number(data[name]);
  }
  for (const name of ["visible", "is_popular", "is_best_value"]) data[name] = fields.has(name);
  for (const name of ["features_fa", "features_en"]) data[name] = data[name].split("\n").map(line => line.trim()).filter(Boolean);
  submit.disabled = true;
  try {
    await request("/api/catalog/" + encodeURIComponent(id), {
      method: "PUT", headers: { "Content-Type": "application/json" }, body: JSON.stringify(data),
    });
    get("plan-dialog").close();
    await load();
    setNotice("پلن ذخیره شد.");
  } catch (error) {
    setNotice(error.message, true, get("plan-notice"));
  } finally {
    submit.disabled = false;
  }
});

navigate();

const sectionsDialog = get("sections-dialog");
const sectionQuery = get("section-query");
function filterSections() {
  const links = [...get("section-links").children];
  for (const link of links) link.hidden = !link.textContent.includes(sectionQuery.value.trim());
  get("no-section").hidden = links.some(link => !link.hidden);
}
function openSections() {
  sectionQuery.value = "";
  filterSections();
  sectionsDialog.showModal();
  sectionQuery.focus();
}
for (const source of document.querySelectorAll(".navigation a, .sidebar-footer > a")) {
  const link = source.cloneNode(true);
  link.removeAttribute("data-tab");
  link.removeAttribute("aria-current");
  link.addEventListener("click", () => sectionsDialog.close());
  get("section-links").append(link);
}
sectionQuery.addEventListener("input", filterSections);
get("open-sections").addEventListener("click", openSections);
document.addEventListener("keydown", event => {
  if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === "k" && !document.querySelector("dialog[open]")) {
    event.preventDefault();
    openSections();
  }
});

get("clear-order-filters").addEventListener("click", () => {
  get("order-filters").reset();
  get("order-filters").requestSubmit();
});

get("activation-link").addEventListener("click", () => {
  applyStatus("APPROVED");
  get("activation-filter").value = "pending";
});


const depositDialog = get("deposit-dialog");
const matchForm = get("deposit-match-form");
const matchId = matchForm.elements.namedItem("order_id");
const matchNotice = get("deposit-match-notice");
const matchConfirm = get("confirm-deposit-match");
let selectedDeposit;
let matchBusy = false;

function openDepositMatch(deposit) {
  if (matchBusy) return;
  selectedDeposit = deposit;
  matchForm.reset();
  get("deposit-match-label").textContent = money(deposit.amount) + " · " + date(deposit.received);
  resetDepositPreview();
  depositDialog.showModal();
}

function resetDepositPreview() {
  get("deposit-order-detail").replaceChildren();
  matchConfirm.disabled = true;
  setNotice("", false, matchNotice);
}

matchId.addEventListener("input", resetDepositPreview);
get("deposit-order-preview").addEventListener("click", async () => {
  matchId.value = matchId.value.trim().toLowerCase();
  if (!matchId.reportValidity()) return;
  resetDepositPreview();
  const id = matchId.value;
  const deposit = selectedDeposit;
  try {
    const {order} = await request("/api/orders/" + encodeURIComponent(id));
    if (!depositDialog.open || selectedDeposit !== deposit || matchId.value !== id) return;
    get("deposit-order-detail").replaceChildren(detailList(order));
    if (order.payment_method === "CARD_TO_CARD" && ["AWAITING_RECEIPT", "PENDING_REVIEW", "EXPIRED"].includes(order.status)) {
      matchConfirm.disabled = false;
    } else {
      setNotice("این سفارش برای تطبیق دستی در دسترس نیست.", true, matchNotice);
    }
  } catch (error) {
    if (depositDialog.open && selectedDeposit === deposit) setNotice(error.message, true, matchNotice);
  }
});
matchForm.addEventListener("submit", async (event) => {
  event.preventDefault();
  if (matchBusy || matchConfirm.disabled) return;
  matchBusy = true;
  matchId.readOnly = true;
  matchConfirm.disabled = true;
  const id = matchId.value;
  try {
    await request("/api/deposits/" + encodeURIComponent(selectedDeposit.id) + "/match", {
      method: "POST",
      headers: {"Content-Type": "application/json"},
      body: JSON.stringify({order_id: id, reason: matchForm.elements.namedItem("reason").value}),
    });
    depositDialog.close();
    await load();
    setNotice("واریز تطبیق داده شد و پرداخت تأیید شد.");
  } catch (error) {
    setNotice(error.message, true, matchNotice);
    matchConfirm.disabled = false;
  } finally {
    matchId.readOnly = false;
    matchBusy = false;
  }
});
