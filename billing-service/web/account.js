"use strict";

const get = (id) => document.getElementById(id);
const fa = new Intl.NumberFormat("fa-IR");
const labels = {
  AWAITING_RECEIPT: "در انتظار واریز", PENDING_REVIEW: "در انتظار بررسی", REJECTED: "ردشده",
  APPROVED: "پرداخت تأیید شد", EXPIRED: "منقضی",
};
const savedKeys = new Map();
let busy = false;
const transfer = new URLSearchParams(location.hash.slice(1)).get("transfer");
if (transfer && /^[A-Za-z0-9_-]{43}$/.test(transfer)) {
  sessionStorage.setItem("pg-order-transfer", transfer);
  history.replaceState(null, "", location.pathname);
}

function el(tag, text, className) {
  const item = document.createElement(tag);
  if (text != null) item.textContent = text;
  if (className) item.className = className;
  return item;
}

function notice(message = "", error = false, target = get("notice")) {
  target.textContent = message;
  target.hidden = !message;
  target.toggleAttribute("data-error", error);
}

async function api(path, { method = "GET", body, headers = {} } = {}) {
  const json = body && !(body instanceof FormData);
  const response = await fetch(path, {
    method, credentials: "same-origin", signal: AbortSignal.timeout(15000), cache: "no-store",
    headers: { Accept: "application/json", ...(json && { "Content-Type": "application/json" }), ...headers },
    body: json ? JSON.stringify(body) : body,
  });
  if (response.status === 204 || response.status === 202) return null;
  const data = await response.json();
  if (response.ok) return data;
  const byCode = {
    GMAIL_REQUIRED: "نشانی معتبر Gmail وارد کنید.", INVALID_CUSTOMER_LOGIN: "ایمیل یا رمز حساب صحیح نیست.",
    CUSTOMER_EMAIL_UNAVAILABLE: "ارسال ایمیل فعلاً در دسترس نیست.",
    INVALID_VERIFICATION_CODE: "کد اشتباه یا منقضی است.", TRANSFER_EXPIRED: "مهلت انتقال پایان یافته است. دوباره از برنامه درخواست بدهید.",
    SUBSCRIPTION_EXPIRED: "زمان اشتراک پایان یافته است.", INSUFFICIENT_WALLET: "اعتبار کیف پول کافی نیست.",
  };
  const byStatus = {
    401: "دوباره وارد حساب شوید.", 409: "این درخواست در وضعیت فعلی قابل انجام نیست.",
    422: "اطلاعات واردشده را بررسی کنید.", 429: "تعداد درخواست‌ها زیاد است. بعداً امتحان کنید.",
  };
  const error = new Error(byCode[data.detail] || byStatus[response.status] || "انجام درخواست ممکن نشد.");
  error.status = response.status;
  throw error;
}

async function run(action, target = get("notice")) {
  if (busy) return false;
  busy = true;
  notice("", false, target);
  try {
    await action();
    return true;
  } catch (error) {
    notice(error.message, true, target);
    return false;
  } finally {
    busy = false;
  }
}

function button(label, action, style = "subtle") {
  const result = el("button", label, "button " + style);
  result.type = "button";
  result.addEventListener("click", action);
  return result;
}

function row(title, description, ...actions) {
  const item = el("div", null, "account-item");
  const text = el("div");
  text.append(el("strong", title), el("p", description));
  const menu = el("div");
  menu.append(...actions);
  item.append(text, menu);
  return item;
}

const price = (amount) => fa.format(amount) + " تومان";
const orderURL = (id) => "/v1/billing/orders/" + encodeURIComponent(id);

async function render() {
  let member;
  try { member = await api("/v1/customer/me"); }
  catch (error) { if (error.status !== 401) throw error; }
  get("auth").hidden = Boolean(member);
  get("account").hidden = !member;
  get("logout").hidden = !member;
  get("email-display").textContent = member?.email ?? "";
  if (!member) return;
  get("transfer").hidden = !sessionStorage.getItem("pg-order-transfer");
  const [wallet, { orders }] = await Promise.all([api("/v1/customer/wallet"), api("/v1/billing/orders")]);
  get("balance").textContent = fa.format(wallet.balance_toman);
  const refunds = new Map(wallet.refunds.map(item => [item.order_id, item]));
  get("refunds").replaceChildren(...wallet.refunds.slice(0, 8).map(item =>
    el("p", item.finished ? "اعتبار لغو اشتراک: " + price(item.amount) : "در انتظار غیرفعال شدن اشتراک")));
  get("orders").replaceChildren(...orders.map(order => {
    const refund = refunds.get(order.id);
    const status = refund ? (refund.finished ? "لغوشده" : "در حال لغو") : labels[order.status] ?? order.status;
    const actions = [];
    if (order.status === "AWAITING_RECEIPT" || order.status === "PENDING_REVIEW") {
      actions.push(button("فیش و اطلاعات پرداخت", () => paymentSheet(order)));
    }
    if (order.activation_status === "READY" && !refund) {
      actions.push(button("کپی لینک اشتراک", () => run(async () => {
        const result = await api(orderURL(order.id) + "/subscription");
        await navigator.clipboard.writeText(result.subscription_url);
        notice("لینک اشتراک کپی شد.");
      })));
      actions.push(button("لغو اشتراک", () => cancellationSheet(order), "danger"));
    }
    return row(order.plan_title_fa, status + " · " + price(order.final_price_toman), ...actions);
  }));
  if (!orders.length) get("orders").replaceChildren(el("p", "هنوز سفارشی ثبت نشده است.", "empty"));
  try {
    const { plans } = await api("/v1/billing/plans");
    get("plans").replaceChildren(...plans.map(plan => row(
      plan.title_fa, price(plan.price_toman) + " · " + fa.format(plan.duration_days) + " روز · " +
      (plan.data_limit_gb ? fa.format(plan.data_limit_gb) + " گیگابایت" : "نامحدود"),
      button("کارت‌به‌کارت", () => run(() => purchase(plan, "CARD_TO_CARD")), "primary"),
      ...(wallet.balance_toman >= plan.price_toman ? [
        button("خرید با کیف پول", () => run(() => purchase(plan, "WALLET"))),
      ] : []),
    )));
    if (!plans.length) get("plans").replaceChildren(el("p", "پلنی برای خرید نمایش داده نمی‌شود.", "empty"));
  } catch {
    get("plans").replaceChildren(el("p", "خرید اشتراک فعلاً در دسترس نیست.", "empty"));
  }
}

async function purchase(plan, method) {
  const attempt = plan.id + ":" + method;
  const key = savedKeys.get(attempt) ?? crypto.randomUUID();
  savedKeys.set(attempt, key);
  const order = await api("/v1/billing/orders", {
    method: "POST", body: { plan_id: plan.id, payment_method: method }, headers: { "Idempotency-Key": key },
  });
  savedKeys.delete(attempt);
  if (method === "CARD_TO_CARD") paymentSheet(order);
  await render();
  if (method === "WALLET") notice("خرید با کیف پول ثبت شد. اشتراک در صف فعال‌سازی است.");
}

function openSheet(title, ...contents) {
  get("sheet-title").textContent = title;
  get("sheet-content").replaceChildren(...contents);
  notice("", false, get("sheet-notice"));
  get("order-sheet").showModal();
}

function paymentSheet(order) {
  const card = order.destination_card;
  const number = el("strong", card.card_number.replace(/(.{4})(?=.)/g, "$1 "));
  number.dir = "ltr";
  const label = el("label", "شمارهٔ پیگیری", "field");
  const field = el("input");
  field.inputMode = "numeric";
  field.maxLength = 64;
  field.dir = "ltr";
  label.append(field);
  const uploadLabel = el("label", "تصویر فیش تا ۵۱۲ کیلوبایت", "field");
  const image = el("input");
  image.type = "file";
  image.accept = "image/jpeg,image/png,image/webp";
  uploadLabel.append(image);
  const target = get("sheet-notice");
  const submit = button("ارسال فیش", () => run(async () => {
    const body = new FormData();
    if (field.value.trim()) body.set("tracking_number", field.value.trim());
    if (image.files[0]) {
      if (image.files[0].size > 512 * 1024) throw new Error("حجم تصویر باید حداکثر ۵۱۲ کیلوبایت باشد.");
      body.set("receipt_image", image.files[0]);
    }
    if (!Array.from(body).length) throw new Error("شمارهٔ پیگیری یا تصویر فیش را وارد کنید.");
    await api(orderURL(order.id) + "/receipt", { method: "POST", body });
    get("order-sheet").close();
    await render();
    notice("فیش ارسال شد.");
  }, target), "primary");
  openSheet(order.plan_title_fa, el("strong", "مبلغ دقیق " + price(order.final_price_toman)), number,
    el("p", card.holder_name_fa + " · " + card.bank_name_fa), label, uploadLabel, submit);
}

function cancellationSheet(order) {
  const target = get("sheet-notice");
  openSheet("لغو اشتراک",
    el("p", "با تأیید شما، دسترسی این اشتراک قطع می‌شود و سهم زمان باقی‌مانده پس از تأیید قطع به کیف پول اضافه می‌شود."),
    button("تأیید لغو", () => run(async () => {
      await api("/v1/customer/orders/" + encodeURIComponent(order.id) + "/cancel", { method: "POST" });
      get("order-sheet").close();
      await render();
      notice("درخواست لغو ثبت شد.");
    }, target), "danger"),
    button("انصراف", () => get("order-sheet").close()),
  );
}

const fields = get("auth-form").elements;
get("auth-form").addEventListener("submit", (event) => {
  event.preventDefault();
  run(async () => {
    await api("/v1/customer/login", { method: "POST", body: { email: fields.email.value, password: fields.password.value } });
    get("auth-form").reset();
    await render();
  });
});
get("send-code").addEventListener("click", () => run(async () => {
  if (!fields.email.reportValidity()) return;
  await api("/v1/customer/code", { method: "POST", body: { email: fields.email.value } });
  get("verification").hidden = false;
  fields.code.focus();
  notice("کد تأیید ایمیل شد.");
}));
get("verify").addEventListener("click", () => run(async () => {
  await api("/v1/customer/verify", {
    method: "POST", body: { email: fields.email.value, code: fields.code.value, password: fields.new_password.value },
  });
  get("auth-form").reset();
  get("verification").hidden = true;
  await render();
}));
get("logout").addEventListener("click", () => run(async () => {
  await api("/v1/customer/logout", { method: "POST" });
  get("orders").replaceChildren();
  get("refunds").replaceChildren();
  get("balance").textContent = "—";
  get("verification").hidden = true;
  savedKeys.clear();
  await render();
}));
get("claim-order").addEventListener("click", () => run(async () => {
  const ticket = sessionStorage.getItem("pg-order-transfer");
  if (!ticket) return;
  try {
    await api("/v1/customer/claim", { method: "POST", body: { ticket } });
  } catch (error) {
    if (error.status === 404) {
      sessionStorage.removeItem("pg-order-transfer");
      get("transfer").hidden = true;
    }
    throw error;
  }
  sessionStorage.removeItem("pg-order-transfer");
  await render();
  notice("سفارش به حساب منتقل شد.");
}));
get("refresh").addEventListener("click", () => run(render));
run(render);
