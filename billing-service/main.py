"""Independent PasarGuard billing service."""
import hmac
import json
import os
import re
import sqlite3
import time
from contextlib import contextmanager
from html import escape
from pathlib import Path
from typing import Annotated
from urllib.error import HTTPError as UpstreamHTTPError, URLError
from urllib.request import HTTPRedirectHandler, Request, build_opener
from uuid import UUID, uuid4

from fastapi import Depends, FastAPI, Form, Header, HTTPException, Request as IncomingRequest
from fastapi.responses import FileResponse, HTMLResponse, RedirectResponse
from fastapi.security import HTTPBasic, HTTPBasicCredentials
from pydantic import BaseModel, Field

app = FastAPI(docs_url=None, redoc_url=None, openapi_url=None)
basic = HTTPBasic()
WINDOW_MS = 30 * 60 * 1000
DIGITS = str.maketrans("۰۱۲۳۴۵۶۷۸۹٠١٢٣٤٥٦٧٨٩", "0123456789" * 2)
WEB = Path(__file__).with_name("web")
CHECKOUT_HEADERS = {
    "Content-Security-Policy": "default-src 'none'; script-src 'self'; style-src 'self'; "
    "connect-src 'self'; form-action 'none'; frame-ancestors 'none'; base-uri 'none'",
    "Referrer-Policy": "no-referrer",
    "X-Content-Type-Options": "nosniff",
}
ORDER_STATUS_LABELS = {
    "AWAITING_RECEIPT": "در انتظار واریز",
    "PENDING_REVIEW": "در انتظار بررسی",
    "APPROVED": "تأیید شد",
    "REJECTED": "رد شد",
    "EXPIRED": "مهلت پایان یافت",
}


def setting(name):
    return os.environ[name]


def now():
    return time.time_ns() // 1_000_000


@contextmanager
def transaction():
    connection = sqlite3.connect(setting("BILLING_DB"))
    connection.row_factory = sqlite3.Row
    try:
        connection.execute("PRAGMA foreign_keys = ON")
        connection.execute("BEGIN IMMEDIATE")
        yield connection
        connection.commit()
    except BaseException:
        connection.rollback()
        raise
    finally:
        connection.close()


@app.on_event("startup")
def initialize():
    for name in ("ADMIN_PASSWORD", "SMS_WEBHOOK_SECRET", "BILLING_KEY"):
        if len(setting(name)) < 24:
            raise ValueError(f"{name} requires at least 24 characters")
    for name in ("PANEL_URL", "BILLING_PUBLIC_URL"):
        if not re.fullmatch(r"https://[a-zA-Z0-9.-]+(?::[0-9]+)?", setting(name)):
            raise ValueError(f"{name} requires an HTTPS origin without a trailing slash")
    if not re.fullmatch(r"[0-9]{16}", setting("DESTINATION_CARD")):
        raise ValueError("DESTINATION_CARD requires 16 digits")
    if not re.fullmatch(r"[0-9][0-9.-]+", setting("SMS_ACCOUNT")):
        raise ValueError("SMS_ACCOUNT requires the account identifier shown in bank SMS")
    catalog()
    with transaction() as db:
        db.execute("""CREATE TABLE IF NOT EXISTS orders (
            id TEXT PRIMARY KEY, owner TEXT NOT NULL, request_key TEXT NOT NULL,
            amount INTEGER NOT NULL UNIQUE, status TEXT NOT NULL,
            created INTEGER NOT NULL, expires INTEGER NOT NULL,
            details TEXT NOT NULL, UNIQUE(owner, request_key)
        )""")
        db.execute("""CREATE TABLE IF NOT EXISTS deposits (
            digest TEXT PRIMARY KEY, amount INTEGER NOT NULL, received INTEGER NOT NULL,
            order_id TEXT UNIQUE REFERENCES orders(id)
        )""")
        db.execute("""CREATE TABLE IF NOT EXISTS decisions (
            order_id TEXT NOT NULL, decision TEXT NOT NULL, timestamp INTEGER NOT NULL
        )""")


@app.middleware("http")
async def prevent_billing_cache(request, call_next):
    response = await call_next(request)
    if request.url.path.startswith(("/v1/billing/", "/admin")):
        response.headers["Cache-Control"] = "no-store"
    return response


@app.get("/healthz")
def health():
    return {"status": "ok"}


@app.get("/")
def checkout():
    return FileResponse(WEB / "checkout.html", headers=CHECKOUT_HEADERS)


@app.get("/checkout.js")
def checkout_script():
    return FileResponse(WEB / "checkout.js", media_type="text/javascript", headers=CHECKOUT_HEADERS)


@app.get("/checkout.css")
def checkout_styles():
    return FileResponse(WEB / "checkout.css", media_type="text/css", headers=CHECKOUT_HEADERS)


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        return None


def secret_digest(value):
    return hmac.new(setting("BILLING_KEY").encode(), value.encode(), "sha256").hexdigest()


def customer(x_subscription_token: str = Header(default="")):
    if not re.fullmatch(r"[A-Za-z0-9_~.-]{4,1024}", x_subscription_token):
        raise HTTPException(401)
    request = Request(f"{setting('PANEL_URL')}/sub/{x_subscription_token}/info")
    try:
        with build_opener(NoRedirect()).open(request, timeout=5) as response:
            profile = json.loads(response.read(65_536))
    except UpstreamHTTPError as error:
        raise HTTPException(401 if error.code in (401, 403, 404) else 503) from None
    except (URLError, TimeoutError, ValueError):
        raise HTTPException(503) from None
    if not isinstance(profile, dict) or not isinstance(profile.get("username"), str) or not profile["username"]:
        raise HTTPException(503)
    return profile["username"]


def catalog():
    source = Path(setting("BILLING_PLANS"))
    plans = json.loads(source.read_text(encoding="utf-8"))["plans"]
    keys = ("id", "category", "titleFa", "titleEn", "durationDays", "priceToman", "dataLimitGb")
    items = []
    for plan in plans:
        if type(plan["priceToman"]) is not int or plan["priceToman"] < 100:
            raise ValueError("Invalid plan price")
        items.append({re.sub(r"[A-Z]", lambda m: "_" + m[0].lower(), k): plan[k] for k in keys})
    return {
        "currency": "TOMAN", "payment_window_minutes": WINDOW_MS // 60_000,
        "destination_card": {
            "card_number": setting("DESTINATION_CARD"),
            "holder_name_fa": setting("CARD_HOLDER"),
            "holder_name_en": setting("CARD_HOLDER"),
            "bank_name_fa": setting("CARD_BANK"),
            "bank_name_en": setting("CARD_BANK"),
        },
        "plans": items,
    }


def present(row, *, admin=False):
    data = json.loads(row["details"])
    if not admin:
        data.pop("subscriber", None)
    status = row["status"]
    if status == "AWAITING_RECEIPT" and row["expires"] <= now():
        status = "EXPIRED"
    return dict(data, id=row["id"], status=status, final_price_toman=row["amount"],
                created_at_ms=row["created"], expires_at_ms=row["expires"])


@app.get("/v1/billing/plans")
def list_plans(owner=Depends(customer)):
    return catalog()


class OrderRequest(BaseModel):
    plan_id: str = Field(max_length=100)
    payment_method: str
    discount_code: str | None = None


@app.post("/v1/billing/orders")
def create_order(body: OrderRequest, idempotency_key: Annotated[UUID, Header()], subscriber=Depends(customer)):
    owner = secret_digest(subscriber)
    if body.payment_method != "CARD_TO_CARD" or body.discount_code:
        raise HTTPException(422, "Only card-to-card orders without a discount are supported")
    with transaction() as db:
        key = str(idempotency_key)
        previous = db.execute(
            "SELECT * FROM orders WHERE owner=? AND request_key=?", (owner, key)
        ).fetchone()
        if previous:
            if json.loads(previous["details"])["plan_id"] != body.plan_id:
                raise HTTPException(409)
            return present(previous)
        current = now()
        recent = db.execute(
            "SELECT count(*) FROM orders WHERE owner=? AND created>?",
            (owner, current - WINDOW_MS),
        ).fetchone()[0]
        if recent >= 5:
            raise HTTPException(429)
        config = catalog()
        plan = next((p for p in config["plans"] if p["id"] == body.plan_id), None)
        if plan is None:
            raise HTTPException(404)
        price = plan["price_toman"]
        used = {r[0] for r in db.execute(
            "SELECT amount FROM orders WHERE amount BETWEEN ? AND ? UNION SELECT amount FROM deposits WHERE amount BETWEEN ? AND ?",
            (price, price + 999, price, price + 999)
        )}
        amount = next((n for n in range(price, price + 1000) if n not in used), None)
        if amount is None:
            raise HTTPException(503, "Payment amount range exhausted")
        details = {
            "plan_id": plan["id"],
            "plan_title_fa": plan["title_fa"],
            "plan_title_en": plan["title_en"],
            "plan_category": plan["category"],
            "payment_method": "CARD_TO_CARD",
            "base_price_toman": price,
            "destination_card": config["destination_card"],
            "subscriber": subscriber,
        }
        order_id = uuid4().hex
        db.execute("INSERT INTO orders VALUES (?,?,?,?,?,?,?,?)", (
            order_id, owner, key, amount, "AWAITING_RECEIPT", current,
            current + WINDOW_MS, json.dumps(details),
        ))
        return present(db.execute("SELECT * FROM orders WHERE id=?", (order_id,)).fetchone())


@app.get("/v1/billing/orders")
def list_orders(limit: int = 50, subscriber=Depends(customer)):
    owner = secret_digest(subscriber)
    with transaction() as db:
        rows = db.execute("SELECT * FROM orders WHERE owner=? ORDER BY created DESC LIMIT ?",
                          (owner, min(max(limit, 1), 100)))
        return {"orders": [present(row) for row in rows]}


@app.get("/v1/billing/orders/{order_id}")
def get_order(order_id: str, subscriber=Depends(customer)):
    owner = secret_digest(subscriber)
    with transaction() as db:
        row = db.execute("SELECT * FROM orders WHERE id=? AND owner=?", (order_id, owner)).fetchone()
        if not row:
            raise HTTPException(404)
        return present(row)


def parse_deposit(text):
    """Accept explicit rial deposits for the configured destination account."""
    normalized = text.translate(DIGITS)
    normalized = re.sub(r"[,٬،]", "", normalized)
    lines = [line.strip() for line in normalized.splitlines() if line.strip()]
    accounts = [match[1] for line in lines if (match := re.fullmatch(
        r"(?:حساب|Save)\s*[:：]\s*([0-9.-]+)", line, re.IGNORECASE
    ))]
    if accounts != [setting("SMS_ACCOUNT")]:
        return None
    pasargad = any(re.match(r"Save\s*[:：]", line, re.IGNORECASE) for line in lines)
    pattern = r"\+\s*([0-9]{1,12})" if pasargad else r"واریز\s*[:：]\s*([0-9]{1,12})\s*ریال"
    values = [match[1] for line in lines if (match := re.fullmatch(pattern, line))]
    if len(values) != 1 or int(values[0]) <= 0 or int(values[0]) % 10:
        return None
    return int(values[0]) // 10, secret_digest("\n".join(lines))


class BankMessage(BaseModel):
    key: str | None = Field(default=None, max_length=4096)
    text: str | None = Field(default=None, max_length=4096)


@app.post("/web/shop/payments/card-deposit/webhook")
def bank_webhook(body: BankMessage, x_webhook_secret: str = Header(default="")):
    if not hmac.compare_digest(x_webhook_secret.encode(), setting("SMS_WEBHOOK_SECRET").encode()):
        raise HTTPException(401)
    deposit = parse_deposit(body.key or body.text or "")
    if deposit is None:
        return {"result": "ignored"}
    amount, digest = deposit
    with transaction() as db:
        if db.execute("SELECT 1 FROM deposits WHERE digest=?", (digest,)).fetchone():
            return {"result": "duplicate"}
        received = now()
        row = db.execute(
            """SELECT id FROM orders WHERE amount=? AND status IN ('AWAITING_RECEIPT','PENDING_REVIEW')
               AND created<=? AND expires>?""", (amount, received, received)
        ).fetchone()
        order_id = row["id"] if row else None
        db.execute("INSERT INTO deposits VALUES (?,?,?,?)", (digest, amount, received, order_id))
        if order_id:
            decide(db, order_id, "APPROVED", "SMS_APPROVED")
        return {"result": "matched" if order_id else "no_match"}


def decide(db, order_id, status, audit):
    timestamp = now()
    row = db.execute("SELECT details FROM orders WHERE id=?", (order_id,)).fetchone()
    details = json.loads(row["details"])
    details["reviewed_at_ms"] = timestamp
    db.execute("UPDATE orders SET status=?, details=? WHERE id=?", (status, json.dumps(details), order_id))
    db.execute("INSERT INTO decisions VALUES (?,?,?)", (order_id, audit, timestamp))


@app.post("/v1/billing/orders/{order_id}/receipt")
def submit_reference(order_id: str, tracking_number: str = Form(default="", max_length=64),
                     payer_card_last4: str = Form(default="", max_length=4), subscriber=Depends(customer)):
    owner = secret_digest(subscriber)
    reference = tracking_number.strip().translate(DIGITS)
    last4 = payer_card_last4.strip().translate(DIGITS)
    if not re.fullmatch(r"[0-9]{4,64}", reference) or (last4 and not re.fullmatch(r"[0-9]{4}", last4)):
        raise HTTPException(400)
    with transaction() as db:
        row = db.execute("SELECT * FROM orders WHERE id=? AND owner=?", (order_id, owner)).fetchone()
        if not row:
            raise HTTPException(404)
        if row["status"] in ("APPROVED", "PENDING_REVIEW", "REJECTED"):
            return present(row)
        if row["expires"] <= now():
            raise HTTPException(409)
        data = json.loads(row["details"])
        data.update(tracking_number=reference, payer_card_last4=last4 or None, submitted_at_ms=now())
        db.execute("UPDATE orders SET details=?, status='PENDING_REVIEW' WHERE id=?",
                   (json.dumps(data), order_id))
        return present(db.execute("SELECT * FROM orders WHERE id=?", (order_id,)).fetchone())


def administrator(credentials: HTTPBasicCredentials = Depends(basic)):
    if not (hmac.compare_digest(credentials.username.encode(), b"admin")
            and hmac.compare_digest(credentials.password.encode(), setting("ADMIN_PASSWORD").encode())):
        raise HTTPException(401, headers={"WWW-Authenticate": "Basic"})


@app.get("/admin", response_class=HTMLResponse, dependencies=[Depends(administrator)])
def dashboard():
    with transaction() as db:
        orders = [present(row, admin=True) for row in db.execute("SELECT * FROM orders ORDER BY created DESC LIMIT 100")]
        deposits = list(db.execute("SELECT * FROM deposits ORDER BY received DESC LIMIT 30"))
    rows = []
    for order in orders:
        oid = escape(order["id"])
        status = escape(ORDER_STATUS_LABELS.get(order["status"], order["status"]))
        review = ""
        if order["status"] == "PENDING_REVIEW":
            review = (f'<form method="post" action="/admin/orders/{oid}">'
                      '<button name="decision" value="APPROVED">تأیید واریز</button> '
                      '<button name="decision" value="REJECTED">رد</button></form>')
        rows.append(f"<tr><td>{oid}</td><td>{escape(order.get('subscriber', '—'))}</td>"
                    f"<td>{escape(order['plan_title_fa'])}</td>"
                    f"<td>{order['final_price_toman']:,}</td><td>{status}</td>"
                    f"<td>{escape(order.get('tracking_number') or '—')}</td>"
                    f"<td>{escape(order.get('payer_card_last4') or '—')}</td><td>{review}</td></tr>")
    events = "".join(f"<li>{row['amount']:,} تومان · "
                     f"{escape(row['order_id'] or 'بدون سفارش منطبق')}</li>" for row in deposits)
    return HTMLResponse("""<!doctype html><html lang="fa" dir="rtl"><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1"><title>مدیریت پرداخت</title>
<style>body{font:16px system-ui;background:#f4f7fa;color:#172b3f;margin:4vw}
table{border-collapse:collapse;background:white;width:100%}th,td{padding:12px;border-bottom:1px solid #ddd}
section{overflow:auto}button{padding:8px;cursor:pointer}</style>
<h1>مدیریت پرداخت کارت‌به‌کارت</h1><section><table><thead><tr>
<th>سفارش</th><th>اشتراک</th><th>پلن</th><th>تومان</th><th>وضعیت</th><th>پیگیری</th><th>چهار رقم کارت</th><th>عملیات</th>
</tr></thead><tbody>""" + ("".join(rows) or '<tr><td colspan="8">هنوز سفارشی ثبت نشده.</td></tr>')
        + "</tbody></table></section><h2>واریزهای دریافتی</h2><ul>"
        + (events or "<li>هنوز واریزی ثبت نشده.</li>") + "</ul></html>",
        headers={"Cache-Control": "no-store", "X-Frame-Options": "DENY",
                 "Content-Security-Policy": "default-src 'none'; style-src 'unsafe-inline'; form-action 'self'"})


@app.post("/admin/orders/{order_id}", dependencies=[Depends(administrator)])
def review(order_id: str, request: IncomingRequest, decision: str = Form()):
    if request.headers.get("origin") != setting("BILLING_PUBLIC_URL"):
        raise HTTPException(403)
    if decision not in ("APPROVED", "REJECTED"):
        raise HTTPException(422)
    with transaction() as db:
        row = db.execute("SELECT status FROM orders WHERE id=?", (order_id,)).fetchone()
        if not row or row["status"] != "PENDING_REVIEW":
            raise HTTPException(409)
        decide(db, order_id, decision, "ADMIN_" + decision)
    return RedirectResponse("/admin", status_code=303)
