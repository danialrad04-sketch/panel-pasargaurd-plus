"use strict";

const get = (id) => document.getElementById(id);
const fa = new Intl.NumberFormat("fa-IR");
const fullDate = new Intl.DateTimeFormat("fa-IR", { timeZone: "Asia/Tehran", dateStyle: "medium", timeStyle: "short" });
const dayLabel = new Intl.DateTimeFormat("fa-IR", { timeZone: "Asia/Tehran", month: "numeric", day: "numeric" });
const today = new Intl.DateTimeFormat("fa-IR", { timeZone: "Asia/Tehran", dateStyle: "full" });
const titles = { overview: "نمای کلی", orders: "سفارش‌ها", deposits: "واریزی‌ها", settings: "کارت مقصد" };
const statusLabels = {
  AWAITING_RECEIPT: "در انتظار واریز", PENDING_REVIEW: "در انتظار بررسی",
  APPROVED: "تأییدشده", REJECTED: "ردشده", EXPIRED: "منقضی",
  matched: "دارای سفارش", unmatched: "بدون سفارش",
};
const decisionLabels = { SMS_APPROVED: "تأیید خودکار با پیامک بانک", ADMIN_APPROVED: "تأیید دستی مدیر", ADMIN_REJECTED: "رد دستی مدیر" };
const date = (value) => value ? fullDate.format(value) : "—";
const money = (value) => fa.format(value) + " تومان";
let active = null;
let activeLoad = null;
let orderPage = 1;
let depositPage = 1;
let detailVersion = 0;
let saving = false;

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
  const { headers = {}, signal, ...rest } = options;
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
  if (!response.ok) {
    const errors = {
      401: "دسترسی مدیر تأیید نشد. صفحه را دوباره باز کنید.",
      403: "این درخواست از دامنهٔ فعلی پذیرفته نشد.",
      404: "این سفارش پیدا نشد.",
      409: "سفارش قبلاً بررسی شده است. وضعیت را دوباره بارگیری کنید.",
      422: "اطلاعات واردشده را بررسی کنید.",
    };
    throw new Error(errors[response.status] || "سرویس پاسخ نداد. دوباره تلاش کنید.");
  }
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

function chart(series) {
  const diagram = get("chart");
  diagram.classList.toggle("dense", series.length > 7);
  const maximum = Math.max(1, ...series.map((point) => point.amount));
  get("chart-tip").textContent = "";
  diagram.replaceChildren(...series.map((point) => {
    const description = dayLabel.format(point.start) + "، " + money(point.amount);
    const wrap = node("button", null, "bar-wrap");
    wrap.type = "button";
    wrap.title = description;
    wrap.setAttribute("aria-label", description);
    wrap.addEventListener("click", () => { get("chart-tip").textContent = description + " · " + fa.format(point.count) + " سفارش"; });
    const bar = node("progress");
    bar.max = maximum;
    bar.value = point.amount;
    bar.setAttribute("aria-hidden", "true");
    wrap.append(bar, node("time", dayLabel.format(point.start)));
    return wrap;
  }));
}

async function overview(signal) {
  const data = await request("/api/overview?days=" + get("period").value, { signal });
  if (signal.aborted) return;
  get("revenue").textContent = fa.format(data.approved_amount);
  get("approved").textContent = fa.format(data.approved_count);
  get("automatic").textContent = fa.format(data.automatic_count) + " تأیید خودکار";
  get("pending").textContent = fa.format(data.pending_count);
  get("unmatched").textContent = fa.format(data.unmatched_count);
  get("bank-name").textContent = data.bank;
  get("card-preview").textContent = "•••• •••• •••• " + data.card_last4;
  get("sms-mode").textContent = data.sms_format === "blu" ? "بلو" : "شناسهٔ حساب";
  ordersTable(get("recent"), data.orders);
  chart(data.series);
}

async function orderList(signal) {
  const query = new URLSearchParams({ page: orderPage, q: get("search").value.trim(), status: get("order-status").value });
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

function last4(value) {
  const element = node("span", "•••• " + value, "mono");
  element.dir = "ltr";
  return element;
}

async function settings(signal) {
  const data = await request("/api/card", { signal });
  if (signal.aborted) return;
  get("card-number").value = data.card.number.replace(/(.{4})(?=.)/g, "$1 ");
  get("card-holder").value = data.card.holder;
  get("card-bank").value = data.card.bank;
  get("same-account").checked = false;
  table(get("card-history"), [["زمان تغییر"], ["کارت قبلی"], ["کارت جدید"]], data.history.map((change) => [
    date(change.timestamp), last4(change.previous_last4), last4(change.current_last4),
  ]), "هنوز تغییری از طریق پنل ثبت نشده است.");
}

const loaders = { overview, orders: orderList, deposits: depositList, settings };

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
    if (data.order.status === "PENDING_REVIEW") {
      const actions = node("div", null, "dialog-actions");
      async function review(decision, prompt) {
        if (!window.confirm(prompt)) return;
        for (const action of actions.children) action.disabled = true;
        try {
          await request("/orders/" + encodeURIComponent(orderId), { method: "POST", body: new URLSearchParams({ decision }) });
          if (version === detailVersion && dialog.open) await showOrder(orderId);
          await load();
          setNotice("نتیجهٔ بررسی ثبت شد.");
        } catch (error) {
          setNotice(error.message, true, errorNotice);
          for (const action of actions.children) action.disabled = false;
        }
      }
      actions.append(
        button("تأیید واریز", () => review("APPROVED", "واریز را با گردش حساب بانک تطبیق داده‌اید؟"), "primary"),
        button("رد رسید", () => review("REJECTED", "رسید این سفارش رد شود؟"), "danger"),
      );
      content.append(actions);
    }
  } catch (error) {
    if (version === detailVersion && dialog.open) content.replaceChildren(node("p", error.message, "empty"));
  }
}

get("order-dialog").addEventListener("close", () => { detailVersion++; });
window.addEventListener("hashchange", navigate);
get("today").textContent = today.format(Date.now());
get("refresh").addEventListener("click", () => load());
get("period").addEventListener("change", () => load());
get("order-filters").addEventListener("submit", (event) => { event.preventDefault(); orderPage = 1; load(); });
get("order-status").addEventListener("change", () => { orderPage = 1; load(); });
get("deposit-match").addEventListener("change", () => { depositPage = 1; load(); });
get("pending-link").addEventListener("click", () => { get("search").value = ""; get("order-status").value = "PENDING_REVIEW"; orderPage = 1; });
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

navigate();
