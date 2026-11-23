"use strict";

const byId = (id) => document.getElementById(id);
const money = new Intl.NumberFormat("fa-IR");
const datetime = new Intl.DateTimeFormat("fa-IR", { dateStyle: "medium", timeStyle: "short" });
const statuses = {
  AWAITING_RECEIPT: ["در انتظار واریز", "مبلغ دقیق را تا پایان مهلت به کارت مقصد بفرستید. پس از شناسایی واریز، وضعیت خودکار به‌روز می‌شود."],
  PENDING_REVIEW: ["در انتظار بررسی", "شمارهٔ پیگیری ثبت شد. پس از بررسی واریز، نتیجه اینجا نمایش داده می‌شود."],
  APPROVED: ["پرداخت تأیید شد", "واریز تأیید شد. اعمال پلن روی اشتراک از طریق پنل پاسارگارد انجام می‌شود."],
  REJECTED: ["پرداخت رد شد", "واریز در بررسی دستی تأیید نشد."],
  EXPIRED: ["مهلت پایان یافت", "برای پرداخت، سفارش تازه‌ای ثبت کنید."],
};
const errors = {
  400: "شمارهٔ پیگیری بانکی را بررسی کنید.",
  401: "لینک یا توکن اشتراک معتبر نیست. دوباره با اطلاعات اشتراک وارد شوید.",
  404: "سفارش یا پلن پیدا نشد.",
  409: "مهلت سفارش گذشته یا وضعیت آن تغییر کرده است. وضعیت را به‌روز کنید.",
  429: "درخواست‌های زیادی ثبت شده. کمی بعد دوباره امتحان کنید.",
};
const orderList = new Map();
const pendingKeys = new Map();
let token = "";
let selected = null;
let polling = null;
let busy = false;

function notice(message = "") {
  byId("notice").textContent = message;
  byId("notice").hidden = !message;
}

function subscriptionToken(value) {
  const input = value.trim();
  const match = /^https:\/\/[^\s]+$/i.test(input)
    ? new URL(input).pathname.match(/\/sub\/([^/]+)(?:\/|$)/)
    : null;
  const result = match ? decodeURIComponent(match[1]) : input;
  if (!/^[A-Za-z0-9_~.-]{4,1024}$/.test(result)) {
    throw new Error("لینک یا توکن اشتراک را بررسی کنید.");
  }
  return result;
}

async function request(path, { headers = {}, ...options } = {}) {
  const response = await fetch("/v1/billing/" + path, {
    ...options,
    headers: { ...headers, "X-Subscription-Token": token },
    cache: "no-store",
    credentials: "omit",
    redirect: "error",
    signal: AbortSignal.timeout(12000),
  });
  if (!response.ok) {
    if (response.status === 503) {
      const problem = await response.json().catch(() => ({}));
      if (problem.detail === "DESTINATION_CARD_NOT_CONFIGURED") {
        throw new Error("کارت مقصد هنوز در پنل مدیریت ثبت نشده است.");
      }
    }
    throw new Error(errors[response.status] || "ارتباط با سرویس برقرار نشد. کمی بعد دوباره امتحان کنید.");
  }
  return response.json();
}

function renderHistory() {
  const list = byId("history");
  list.replaceChildren();
  byId("empty-history").hidden = orderList.size > 0;
  const orders = [...orderList.values()].sort((left, right) => right.created_at_ms - left.created_at_ms);
  for (const order of orders) {
    const row = document.createElement("li");
    const button = document.createElement("button");
    button.type = "button";
    button.className = "secondary";
    const label = statuses[order.status]?.[0] || "در حال بررسی";
    button.textContent = order.plan_title_fa + " · " + label;
    button.setAttribute("aria-pressed", order.id === selected?.id);
    button.addEventListener("click", () => run(() => showOrder(order)));
    row.append(button);
    list.append(row);
  }
}

function showOrder(order) {
  if (selected?.id !== order.id) {
    byId("receipt-form").reset();
    byId("receipt-form").closest("details").open = false;
  }
  selected = order;
  orderList.set(order.id, order);
  const [label, description] = statuses[order.status] || ["در حال بررسی", ""];
  byId("order").hidden = false;
  if (byId("status").textContent !== label) byId("status").textContent = label;
  byId("status").dataset.state = order.status;
  byId("plan-name").textContent = order.plan_title_fa;
  byId("status-description").textContent = description;
  byId("amount").textContent = money.format(order.final_price_toman) + " تومان";
  byId("order-id").textContent = order.id;
  const awaiting = order.status === "AWAITING_RECEIPT";
  byId("deadline-row").hidden = !awaiting;
  byId("deadline").textContent = datetime.format(order.expires_at_ms);
  byId("payment-details").hidden = !awaiting;
  if (awaiting) {
    const card = order.destination_card;
    byId("card-number").textContent = card.card_number.replace(/(.{4})(?=.)/g, "$1 ");
    byId("card-owner").textContent = [card.holder_name_fa, card.bank_name_fa].filter(Boolean).join(" · ");
  }
  const list = [...byId("history").querySelectorAll("button")];
  // Preserve focus on a history entry while updating its accessible selection.
  const focusIndex = list.indexOf(document.activeElement);
  renderHistory();
  if (focusIndex >= 0) byId("history").querySelectorAll("button")[focusIndex]?.focus();
}

async function refreshOrder() {
  if (selected) showOrder(await request("orders/" + encodeURIComponent(selected.id)));
}

async function run(action, { background = false } = {}) {
  if (busy) return;
  busy = true;
  window.clearTimeout(polling);
  if (!background) notice();
  try {
    await action();
  } catch (error) {
    const text = error.name === "TypeError" || error.name === "TimeoutError" || error.name === "AbortError"
      ? "ارتباط با سرویس برقرار نشد. دوباره امتحان کنید."
      : error.message;
    if (!background || byId("notice").hidden) notice(text);
  } finally {
    busy = false;
    if (["AWAITING_RECEIPT", "PENDING_REVIEW"].includes(selected?.status)) {
      polling = window.setTimeout(() => run(refreshOrder, { background: true }), 10000);
    }
  }
}

byId("signin-form").addEventListener("submit", (event) => {
  event.preventDefault();
  run(async () => {
    try {
      token = subscriptionToken(byId("subscription").value);
      const [catalog, history] = await Promise.all([request("plans"), request("orders")]);
      const plans = catalog.plans.map((plan) => new Option(
        plan.title_fa + " · " + money.format(plan.price_toman) + " تومان", plan.id,
      ));
      byId("plan").replaceChildren(...plans);
      byId("plan-form").querySelector("button").disabled = !plans.length;
      for (const order of history.orders) orderList.set(order.id, order);
      byId("signin-form").reset();
      byId("signin").hidden = true;
      byId("account").hidden = false;
      if (history.orders.length) showOrder(history.orders[0]);
      else renderHistory();
      byId("plan").focus();
    } catch (error) {
      token = "";
      throw error;
    }
  });
});

byId("signout").addEventListener("click", () => run(() => {
  token = "";
  selected = null;
  orderList.clear();
  pendingKeys.clear();
  byId("plan").replaceChildren();
  byId("history").replaceChildren();
  byId("receipt-form").reset();
  for (const id of ["status", "plan-name", "status-description", "amount", "order-id", "deadline", "card-number", "card-owner"]) {
    byId(id).textContent = "";
  }
  byId("order").hidden = true;
  byId("account").hidden = true;
  byId("signin").hidden = false;
  byId("subscription").focus();
}));

byId("plan-form").addEventListener("submit", (event) => {
  event.preventDefault();
  run(async () => {
    const plan = byId("plan").value;
    if (!pendingKeys.has(plan)) pendingKeys.set(plan, crypto.randomUUID());
    const order = await request("orders", {
      method: "POST",
      headers: { "Content-Type": "application/json", "Idempotency-Key": pendingKeys.get(plan) },
      body: JSON.stringify({ plan_id: plan, payment_method: "CARD_TO_CARD" }),
    });
    pendingKeys.delete(plan);
    showOrder(order);
  });
});

byId("receipt-form").addEventListener("submit", (event) => {
  event.preventDefault();
  run(async () => {
    if (selected?.status !== "AWAITING_RECEIPT") return;
    const body = new URLSearchParams({ tracking_number: byId("tracking").value.trim() });
    const order = await request("orders/" + encodeURIComponent(selected.id) + "/receipt", { method: "POST", body });
    byId("receipt-form").reset();
    showOrder(order);
  });
});

byId("refresh").addEventListener("click", () => run(refreshOrder));
byId("copy").addEventListener("click", () => run(async () => {
  await navigator.clipboard.writeText(selected.destination_card.card_number);
  notice("شمارهٔ کارت کپی شد.");
}));
