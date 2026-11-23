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
const decisionLabels = { SMS_APPROVED: "تأیید خودکار با پیامک بانک", ADMIN_APPROVED: "تأیید دستی مدیر", ADMIN_REJECTED: "رد دستی مدیر" };
const activityLabels = {
  ...decisionLabels, LOGIN: "ورود به پنل", LOGOUT: "خروج از پنل",
  WALLET_PAID: "پرداخت از کیف پول", CUSTOMER_CANCELLED: "لغو اشتراک و ثبت اعتبار کیف پول", CATALOG_UPDATED: "تغییر پلن فروش", CARD_UPDATED: "تغییر کارت مقصد", PROVISIONING_UPDATED: "تغییر تنظیمات ساخت اشتراک",
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
    if (raw && response.status === 422) {
      throw new Error("برای دریافت CSV با کمتر از ۱۰ هزار سفارش، فیلترهای دقیق‌تری انتخاب کنید.");
    }
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
    q: get("search").value.trim(), status: get("order-status").value, receipt: get("receipt-filter").value,
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
    deposit.order_id ? button("مشاهده", () => showOrder(deposit.order_id), "text-button") : "—",
  ]), "واریزی در این فهرست پیدا نشد.");
  pages(get("deposits-pages"), data.total, data.page_size, depositPage, (page) => { depositPage = page; load(); });
}

async function catalogList(signal) {
  const data = await request("/api/catalog", { signal });
  if (signal.aborted) return;
  table(get("catalog-table"), [["پلن"], ["قیمت"], ["حجم"], ["نمایش"], ["ویرایش"]], data.plans.map((plan) => [
    plan.title_fa, money(plan.price_toman),
    plan.data_limit_gb == null ? "نامحدود" : fa.format(plan.data_limit_gb) + " گیگابایت",
    plan.visible ? "فعال" : "مخفی",
    button("ویرایش", () => editPlan(plan), "text-button"),
  ]), "هنوز پلنی ثبت نشده است.");
}

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
  const [data, provision] = await Promise.all([request("/api/card", { signal }), request("/api/provisioning", { signal })]);
  if (signal.aborted) return;
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
      activity.append(node("li", (decisionLabels[event.decision] || "بررسی سفارش") + " · " + date(event.timestamp)));
    }
    content.replaceChildren(errorNotice, detailList(data.order), node("h3", "سابقهٔ بررسی"),
      data.decisions.length ? activity : node("p", "هنوز تصمیمی ثبت نشده است."));
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
    if (data.order.status === "PENDING_REVIEW") {
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
      actions.append(
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
  orderPage = 1;
}
for (const id of ["pending-link", "review-metric-link"]) {
  get(id).addEventListener("click", () => applyStatus("PENDING_REVIEW"));
}
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
