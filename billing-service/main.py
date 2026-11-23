"""Independent PasarGuard billing service."""
import asyncio
import base64
import hmac
import ipaddress
import json
import logging
import os
import re
import sqlite3
import time
from contextlib import contextmanager
from dataclasses import dataclass
from datetime import datetime, timedelta
from io import BytesIO
from itertools import pairwise
from pathlib import Path
from typing import Annotated
from urllib.error import HTTPError as UpstreamHTTPError, URLError
from urllib.request import Request, build_opener
from uuid import UUID, uuid4
from zoneinfo import ZoneInfo

import provisioning
from cryptography.fernet import Fernet, InvalidToken
from fastapi import (
    APIRouter,
    Depends,
    FastAPI,
    File,
    Form,
    Header,
    HTTPException,
    Query,
    Request as IncomingRequest,
    UploadFile,
)
from fastapi.responses import FileResponse, RedirectResponse, Response
from fastapi.security import HTTPBasic, HTTPBasicCredentials
from jdatetime import datetime as JalaliDateTime
from PIL import Image, ImageOps
from pydantic import BaseModel, Field

app = FastAPI(docs_url=None, redoc_url=None, openapi_url=None)
basic = HTTPBasic()
WINDOW_MS = 30 * 60 * 1000
MINUTE_MS = 60 * 1000
DIGITS = str.maketrans("۰۱۲۳۴۵۶۷۸۹٠١٢٣٤٥٦٧٨٩", "0123456789" * 2)
SEPARATORS = str.maketrans("", "", ",٬،")
RIAL_NUMBER = r"(?:[0-9]{1,3}(?:[,٬،][0-9]{3}){1,3}|[0-9]{1,12})"
WEB = Path(__file__).with_name("web")
CHECKOUT_HEADERS = {
    "Content-Security-Policy": "default-src 'none'; script-src 'self'; style-src 'self'; "
    "connect-src 'self'; form-action 'none'; frame-ancestors 'none'; base-uri 'none'",
    "Referrer-Policy": "no-referrer",
    "X-Content-Type-Options": "nosniff",
}
ADMIN_HEADERS = {
    **CHECKOUT_HEADERS,
    "Content-Security-Policy": "default-src 'none'; script-src 'self'; style-src 'self'; "
    "connect-src 'self'; img-src 'self'; font-src 'self'; form-action 'self'; "
    "frame-ancestors 'none'; base-uri 'none'",
}
ORDER_STATES = ("AWAITING_RECEIPT", "PENDING_REVIEW", "APPROVED", "REJECTED", "EXPIRED")
PAGE_SIZE = 12
MAX_RECEIPT_BYTES = 512 * 1024


def setting(name):
    return os.environ[name]


def now():
    return time.time_ns() // 1_000_000


@contextmanager
def transaction(*, write=True):
    connection = sqlite3.connect(setting("BILLING_DB"))
    connection.row_factory = sqlite3.Row
    try:
        connection.execute("PRAGMA foreign_keys = ON")
        connection.execute("BEGIN IMMEDIATE" if write else "BEGIN")
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
    card_number = os.getenv("DESTINATION_CARD", "")
    if card_number and not re.fullmatch(r"[0-9]{16}", card_number):
        raise ValueError("DESTINATION_CARD requires 16 digits when provided")
    sms_format = os.getenv("SMS_FORMAT", "account")
    if sms_format not in ("account", "blu"):
        raise ValueError("SMS_FORMAT must be account or blu")
    account = os.getenv("SMS_ACCOUNT", "")
    if sms_format == "account" and account and not re.fullmatch(r"[0-9][0-9.-]+", account):
        raise ValueError("SMS_ACCOUNT requires the account identifier shown in bank SMS when provided")
    if sms_format == "blu":
        ZoneInfo("Asia/Tehran")
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
        db.execute("""CREATE TABLE IF NOT EXISTS destination_card (
            id INTEGER PRIMARY KEY CHECK (id = 1), number TEXT NOT NULL,
            holder TEXT NOT NULL, bank TEXT NOT NULL, updated INTEGER NOT NULL
        )""")
        db.execute("""CREATE TABLE IF NOT EXISTS card_changes (
            id INTEGER PRIMARY KEY, previous_last4 TEXT NOT NULL, current_last4 TEXT NOT NULL,
            timestamp INTEGER NOT NULL
        )""")
        db.execute("""CREATE TABLE IF NOT EXISTS provisioning_jobs (
            order_id TEXT PRIMARY KEY REFERENCES orders(id), next_run INTEGER NOT NULL,
            attempts INTEGER NOT NULL DEFAULT 0, ready INTEGER NOT NULL DEFAULT 0
        )""")
        db.execute("""CREATE TABLE IF NOT EXISTS guest_reservations (
            order_id TEXT PRIMARY KEY REFERENCES orders(id), source TEXT NOT NULL, created INTEGER NOT NULL
        )""")
        db.execute("CREATE INDEX IF NOT EXISTS guest_reservations_created ON guest_reservations(created)")
        db.execute("""CREATE TABLE IF NOT EXISTS provisioning_settings (
            id INTEGER PRIMARY KEY CHECK (id = 1), api_key BLOB NOT NULL, group_ids TEXT NOT NULL
        )""")
        db.execute("""CREATE TABLE IF NOT EXISTS receipts (
            order_id TEXT PRIMARY KEY REFERENCES orders(id), image BLOB NOT NULL, uploaded INTEGER NOT NULL
        )""")


def key_cipher():
    return Fernet(base64.urlsafe_b64encode(hmac.digest(setting("BILLING_KEY").encode(), b"panel-api:v1", "sha256")))


def provision_config():
    with transaction(write=False) as db:
        saved = db.execute("SELECT api_key, group_ids FROM provisioning_settings WHERE id=1").fetchone()
    if not saved:
        return provisioning.credentials()
    try:
        return key_cipher().decrypt(saved["api_key"]).decode(), json.loads(saved["group_ids"])
    except InvalidToken:
        raise provisioning.PanelError() from None


def process_provisioning_job():
    current = now()
    with transaction() as db:
        job = db.execute(
            """SELECT j.order_id, j.attempts, o.details FROM provisioning_jobs j
               JOIN orders o ON o.id=j.order_id WHERE j.ready=0 AND j.next_run<=? AND o.status='APPROVED'
               ORDER BY j.next_run LIMIT 1""", (current,),
        ).fetchone()
        if not job:
            return
        db.execute("UPDATE provisioning_jobs SET attempts=attempts+1, next_run=? WHERE order_id=?",
                   (current + 120_000, job["order_id"]))
    order_id = job["order_id"]
    try:
        provisioning.provision(order_id, json.loads(job["details"])["purchased_plan"], provision_config())
    except provisioning.PanelError:
        delay = min(300_000, 10_000 * 2**min(job["attempts"], 5))
        with transaction() as db:
            db.execute("UPDATE provisioning_jobs SET next_run=? WHERE order_id=? AND ready=0",
                       (now() + delay, order_id))
        return
    with transaction() as db:
        db.execute("UPDATE provisioning_jobs SET ready=1 WHERE order_id=?", (order_id,))
        row = db.execute("SELECT details FROM orders WHERE id=?", (order_id,)).fetchone()
        details = json.loads(row["details"])
        details["activation_status"] = "READY"
        db.execute("UPDATE orders SET details=? WHERE id=?", (json.dumps(details), order_id))


@app.on_event("startup")
async def start_provisioning_worker():
    async def run():
        while True:
            try:
                await asyncio.to_thread(process_provisioning_job)
            except Exception:
                logging.getLogger(__name__).exception("Subscription provisioning worker failed")
            await asyncio.sleep(3)

    app.state.provisioning_worker = asyncio.create_task(run())


@app.on_event("shutdown")
async def stop_provisioning_worker():
    worker = getattr(app.state, "provisioning_worker", None)
    if worker:
        worker.cancel()
        await asyncio.gather(worker, return_exceptions=True)


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


def secret_digest(value):
    return hmac.new(setting("BILLING_KEY").encode(), value.encode(), "sha256").hexdigest()


def customer(x_subscription_token: str = Header(default="")):
    if not re.fullmatch(r"[A-Za-z0-9_~.-]{4,1024}", x_subscription_token):
        raise HTTPException(401)
    request = Request(f"{setting('PANEL_URL')}/sub/{x_subscription_token}/info")
    try:
        with build_opener(provisioning.NoRedirect()).open(request, timeout=5) as response:
            profile = json.loads(response.read(65_536))
    except UpstreamHTTPError as error:
        raise HTTPException(401 if error.code in (401, 403, 404) else 503) from None
    except (URLError, TimeoutError, ValueError):
        raise HTTPException(503) from None
    if not isinstance(profile, dict) or not isinstance(profile.get("username"), str) or not profile["username"]:
        raise HTTPException(503)
    return profile["username"]


@dataclass(frozen=True)
class BillingActor:
    owner: str
    subscriber: str | None

    @property
    def is_guest(self):
        return self.subscriber is None


def billing_actor(x_subscription_token: str = Header(default=""), x_purchase_token: str = Header(default="")):
    if x_purchase_token:
        if x_subscription_token or not re.fullmatch(r"[0-9a-f]{64}", x_purchase_token):
            raise HTTPException(401)
        return BillingActor(secret_digest("purchase:" + x_purchase_token), None)
    subscriber = customer(x_subscription_token)
    return BillingActor(secret_digest(subscriber), subscriber)


def ensure_guest_checkout():
    try:
        provisioning.assert_available(provision_config(), now() // MINUTE_MS)
    except provisioning.PanelError:
        raise HTTPException(503, "NEW_SUBSCRIPTIONS_UNAVAILABLE") from None


def reservation_source(request):
    # The supplied proxies overwrite or append the actual peer at the end of X-Forwarded-For.
    forwarded = request.headers.get("x-forwarded-for", "").rsplit(",", 1)[-1].strip()
    try:
        source = str(ipaddress.ip_address(forwarded))
    except ValueError:
        source = request.client.host if request.client else "unknown"
    return secret_digest("purchase-source:" + source)


def destination_card(db=None):
    override = db.execute("SELECT * FROM destination_card WHERE id=1").fetchone() if db is not None else None
    return dict(override) if override else {
        "number": os.getenv("DESTINATION_CARD", ""), "holder": os.getenv("CARD_HOLDER", ""),
        "bank": os.getenv("CARD_BANK", ""), "updated": None,
    }


def catalog(db=None):
    source = Path(setting("BILLING_PLANS"))
    plans = json.loads(source.read_text(encoding="utf-8"))["plans"]
    keys = ("id", "category", "titleFa", "titleEn", "durationDays", "priceToman", "dataLimitGb", "maxDevices")
    items = []
    for plan in plans:
        if type(plan["priceToman"]) is not int or plan["priceToman"] < 100:
            raise ValueError("Invalid plan price")
        items.append({re.sub(r"[A-Z]", lambda m: "_" + m[0].lower(), k): plan.get(k) for k in keys})
    card = destination_card(db)
    if db is not None and not card["number"]:
        raise HTTPException(503, "DESTINATION_CARD_NOT_CONFIGURED")
    return {
        "currency": "TOMAN", "payment_window_minutes": WINDOW_MS // 60_000,
        "destination_card": {
            "card_number": card["number"],
            "holder_name_fa": card["holder"],
            "holder_name_en": card["holder"],
            "bank_name_fa": card["bank"],
            "bank_name_en": card["bank"],
        },
        "plans": items,
    }


def present(row, *, admin=False):
    data = json.loads(row["details"])
    data.pop("purchased_plan", None)
    if not admin:
        data.pop("subscriber", None)
    status = row["status"]
    if status == "AWAITING_RECEIPT" and row["expires"] <= now():
        status = "EXPIRED"
    return dict(data, id=row["id"], status=status, final_price_toman=row["amount"],
                created_at_ms=row["created"], expires_at_ms=row["expires"])


@app.get("/v1/billing/plans")
def list_plans(actor=Depends(billing_actor)):
    if actor.is_guest:
        ensure_guest_checkout()
    with transaction(write=False) as db:
        result = catalog(db)
        if actor.is_guest:
            result["plans"] = [plan for plan in result["plans"] if plan["category"] == "RENEW"]
        return result


class OrderRequest(BaseModel):
    plan_id: str = Field(max_length=100)
    payment_method: str = "CARD_TO_CARD"
    discount_code: str | None = None


@app.post("/v1/billing/orders")
def create_order(body: OrderRequest, idempotency_key: Annotated[UUID, Header()], request: IncomingRequest,
                 actor=Depends(billing_actor)):
    owner = actor.owner
    if body.payment_method != "CARD_TO_CARD" or body.discount_code:
        raise HTTPException(422, "Only card-to-card orders without a discount are supported")
    if actor.is_guest:
        ensure_guest_checkout()
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
        if actor.is_guest:
            source = reservation_source(request)
            budget = db.execute(
                """SELECT count(*), count(CASE WHEN source=? THEN 1 END)
                   FROM guest_reservations WHERE created>?""", (source, current - WINDOW_MS),
            ).fetchone()
            pending = db.execute(
                """SELECT 1 FROM orders WHERE owner=? AND (
                   (status='AWAITING_RECEIPT' AND expires>?) OR status='PENDING_REVIEW')""", (owner, current),
            ).fetchone()
            if budget[0] >= 120 or budget[1] >= 5 or pending:
                raise HTTPException(429)
        config = catalog(db)
        plan = next((p for p in config["plans"] if p["id"] == body.plan_id), None)
        if plan is None:
            raise HTTPException(404)
        if actor.is_guest and plan["category"] != "RENEW":
            raise HTTPException(422)
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
            "subscriber": actor.subscriber or "خرید اشتراک جدید",
        }
        if actor.is_guest:
            details["new_subscription"] = True
            details["purchased_plan"] = {key: plan[key] for key in ("duration_days", "data_limit_gb", "max_devices")}
        order_id = uuid4().hex
        db.execute("INSERT INTO orders VALUES (?,?,?,?,?,?,?,?)", (
            order_id, owner, key, amount, "AWAITING_RECEIPT", current,
            current + WINDOW_MS, json.dumps(details),
        ))
        if actor.is_guest:
            db.execute("INSERT INTO guest_reservations VALUES (?,?,?)", (order_id, source, current))
        return present(db.execute("SELECT * FROM orders WHERE id=?", (order_id,)).fetchone())


@app.get("/v1/billing/orders")
def list_orders(limit: int = 50, actor=Depends(billing_actor)):
    owner = actor.owner
    with transaction(write=False) as db:
        rows = db.execute("SELECT * FROM orders WHERE owner=? ORDER BY created DESC LIMIT ?",
                          (owner, min(max(limit, 1), 100)))
        return {"orders": [present(row) for row in rows]}


@app.get("/v1/billing/orders/{order_id}")
def get_order(order_id: str, actor=Depends(billing_actor)):
    owner = actor.owner
    with transaction(write=False) as db:
        row = db.execute("SELECT * FROM orders WHERE id=? AND owner=?", (order_id, owner)).fetchone()
        if not row:
            raise HTTPException(404)
        return present(row)


@app.get("/v1/billing/orders/{order_id}/subscription")
def get_purchased_subscription(order_id: str, actor=Depends(billing_actor)):
    if not actor.is_guest:
        raise HTTPException(404)
    with transaction(write=False) as db:
        row = db.execute(
            """SELECT o.status, j.ready FROM orders o LEFT JOIN provisioning_jobs j ON j.order_id=o.id
               WHERE o.id=? AND o.owner=?""", (order_id, actor.owner),
        ).fetchone()
        if not row:
            raise HTTPException(404)
        if row["status"] != "APPROVED" or not row["ready"]:
            raise HTTPException(409, "SUBSCRIPTION_PENDING")
    try:
        return {"subscription_url": provisioning.read_subscription(order_id, provision_config())}
    except provisioning.PanelError:
        raise HTTPException(503, "SUBSCRIPTION_UNAVAILABLE") from None


def parse_blu_deposit(lines):
    if len(lines) != 6 or lines[:2] != ["بلو", "واریز پول"]:
        return None
    amount = re.fullmatch(rf"[^،]{{1,120}} عزیز،\s*({RIAL_NUMBER})\s*ریال به حساب شما نشست[.؟]?", lines[2])
    balance = re.fullmatch(rf"موجودی:\s*({RIAL_NUMBER})\s*ریال", lines[3])
    clock = re.fullmatch(r"([0-9]{1,2}):([0-9]{2})", lines[4])
    date = re.fullmatch(r"(1[34][0-9]{2})[./]([0-9]{1,2})[./]([0-9]{1,2})", lines[5])
    if not (amount and balance and clock and date):
        return None
    rials = int(amount[1].translate(SEPARATORS))
    if rials <= 0 or rials % 10:
        return None
    try:
        stamp = JalaliDateTime(*map(int, date.groups()), *map(int, clock.groups())).togregorian()
    except ValueError:
        return None
    minute = int(stamp.replace(tzinfo=ZoneInfo("Asia/Tehran")).timestamp()) * 1000
    digest = secret_digest(f"blu:{minute}:{rials}:{int(balance[1].translate(SEPARATORS))}")
    return rials // 10, digest, minute


def parse_deposit(text):
    """Match the deposit template selected for this destination account."""
    sms_format = os.getenv("SMS_FORMAT", "account")
    normalized = text.translate(DIGITS)
    if sms_format == "blu":
        return parse_blu_deposit([line.strip() for line in normalized.splitlines() if line.strip()])
    normalized = normalized.translate(SEPARATORS)
    lines = [line.strip() for line in normalized.splitlines() if line.strip()]
    accounts = [match[1] for line in lines if (match := re.fullmatch(
        r"(?:حساب|Save)\s*[:：]\s*([0-9.-]+)", line, re.IGNORECASE
    ))]
    account = os.getenv("SMS_ACCOUNT", "")
    if not account or accounts != [account]:
        return None
    pasargad = any(re.match(r"Save\s*[:：]", line, re.IGNORECASE) for line in lines)
    pattern = r"\+\s*([0-9]{1,12})" if pasargad else r"واریز\s*[:：]\s*([0-9]{1,12})\s*ریال"
    values = [match[1] for line in lines if (match := re.fullmatch(pattern, line))]
    if len(values) != 1 or int(values[0]) <= 0 or int(values[0]) % 10:
        return None
    return int(values[0]) // 10, secret_digest("\n".join(lines)), None


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
    amount, digest, sms_minute = deposit
    with transaction() as db:
        if db.execute("SELECT 1 FROM deposits WHERE digest=?", (digest,)).fetchone():
            return {"result": "duplicate"}
        received = now()
        if sms_minute is not None and not received - WINDOW_MS - MINUTE_MS < sms_minute <= received:
            return {"result": "ignored"}
        # Blu timestamps have minute precision. Allow orders created during that minute,
        # while excluding orders created in any later minute.
        latest_creation = received if sms_minute is None else min(received, sms_minute + MINUTE_MS - 1)
        row = db.execute(
            """SELECT id FROM orders WHERE amount=? AND status IN ('AWAITING_RECEIPT','PENDING_REVIEW')
               AND created<=? AND expires>?""", (amount, latest_creation, received)
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
    if status == "APPROVED" and details.get("new_subscription"):
        details["activation_status"] = "PENDING"
        db.execute("INSERT OR IGNORE INTO provisioning_jobs (order_id, next_run) VALUES (?, ?)", (order_id, timestamp))
    db.execute("UPDATE orders SET status=?, details=? WHERE id=?", (status, json.dumps(details), order_id))
    db.execute("INSERT INTO decisions VALUES (?,?,?)", (order_id, audit, timestamp))


def receipt_jpeg(upload):
    payload = upload.file.read(MAX_RECEIPT_BYTES + 1)
    if len(payload) > MAX_RECEIPT_BYTES:
        raise HTTPException(413, "RECEIPT_TOO_LARGE")
    try:
        with Image.open(BytesIO(payload), formats=("JPEG", "PNG", "WEBP")) as source:
            if source.width * source.height > 16_000_000:
                raise ValueError("Image dimensions exceed the limit")
            image = ImageOps.exif_transpose(source).convert("RGB")
            image.thumbnail((1600, 1600))
            cleaned = BytesIO()
            image.save(cleaned, format="JPEG", quality=84, optimize=True)
    except (OSError, ValueError, Image.DecompressionBombError):
        raise HTTPException(400, "INVALID_RECEIPT_IMAGE") from None
    if cleaned.tell() > MAX_RECEIPT_BYTES:
        raise HTTPException(413, "RECEIPT_TOO_LARGE")
    return cleaned.getvalue()


def receipt_order(db, order_id, owner):
    row = db.execute("SELECT * FROM orders WHERE id=? AND owner=?", (order_id, owner)).fetchone()
    if not row:
        raise HTTPException(404)
    if row["status"] == "AWAITING_RECEIPT" and row["expires"] <= now():
        raise HTTPException(409)
    return row


@app.post("/v1/billing/orders/{order_id}/receipt")
def submit_reference(order_id: str, tracking_number: str = Form(default="", max_length=64),
                     payer_card_last4: str = Form(default="", max_length=4),
                     receipt_image: Annotated[UploadFile | None, File()] = None, actor=Depends(billing_actor)):
    reference = tracking_number.strip().translate(DIGITS)
    last4 = payer_card_last4.strip().translate(DIGITS)
    if ((not reference and receipt_image is None) or (reference and not re.fullmatch(r"[0-9]{4,64}", reference))
            or (last4 and not re.fullmatch(r"[0-9]{4}", last4))):
        raise HTTPException(400, "RECEIPT_DETAILS_REQUIRED")
    with transaction(write=False) as db:
        row = receipt_order(db, order_id, actor.owner)
        if row["status"] == "REJECTED" or (row["status"] != "AWAITING_RECEIPT" and receipt_image is None):
            return present(row)
        if receipt_image is not None and json.loads(row["details"]).get("has_receipt_image"):
            return present(row)
    image = receipt_jpeg(receipt_image) if receipt_image is not None else None
    with transaction() as db:
        row = receipt_order(db, order_id, actor.owner)
        if row["status"] == "REJECTED" or (row["status"] != "AWAITING_RECEIPT" and image is None):
            return present(row)
        data = json.loads(row["details"])
        current = now()
        if image is not None and not data.get("has_receipt_image"):
            db.execute("INSERT INTO receipts (order_id, image, uploaded) VALUES (?, ?, ?)", (order_id, image, current))
            data["has_receipt_image"] = True
        if reference and not data.get("tracking_number"):
            data["tracking_number"] = reference
        if last4 and not data.get("payer_card_last4"):
            data["payer_card_last4"] = last4
        data.setdefault("submitted_at_ms", current)
        status = "PENDING_REVIEW" if row["status"] == "AWAITING_RECEIPT" else row["status"]
        db.execute("UPDATE orders SET details=?, status=? WHERE id=?", (json.dumps(data), status, order_id))
        return present(db.execute("SELECT * FROM orders WHERE id=?", (order_id,)).fetchone())


def administrator(credentials: HTTPBasicCredentials = Depends(basic)):
    if not (hmac.compare_digest(credentials.username.encode(), b"admin")
            and hmac.compare_digest(credentials.password.encode(), setting("ADMIN_PASSWORD").encode())):
        raise HTTPException(401, headers={"WWW-Authenticate": "Basic"})


admin = APIRouter(prefix="/admin", dependencies=[Depends(administrator)])


@admin.get("")
def dashboard():
    return FileResponse(WEB / "admin.html", headers=ADMIN_HEADERS)


@admin.get("/dashboard.css")
def dashboard_styles():
    return FileResponse(WEB / "admin.css", media_type="text/css", headers=ADMIN_HEADERS)


@admin.get("/dashboard.js")
def dashboard_script():
    return FileResponse(WEB / "admin.js", media_type="text/javascript", headers=ADMIN_HEADERS)


@admin.get("/api/overview")
def admin_overview(days: int = 7):
    if days not in (7, 30):
        raise HTTPException(422)
    current = now()
    today = datetime.fromtimestamp(current / 1000, ZoneInfo("Asia/Tehran")).replace(
        hour=0, minute=0, second=0, microsecond=0,
    )
    boundaries = [int((today - timedelta(days=i)).timestamp() * 1000) for i in range(days - 1, -2, -1)]
    with transaction(write=False) as db:
        approved = db.execute(
            """SELECT o.amount, d.timestamp, d.decision FROM decisions d
               JOIN orders o ON o.id=d.order_id
               WHERE d.timestamp>=? AND d.timestamp<=?
               AND d.decision IN ('SMS_APPROVED', 'ADMIN_APPROVED') AND o.status='APPROVED'""",
            (boundaries[0], current),
        ).fetchall()
        pending = db.execute("SELECT count(*) FROM orders WHERE status='PENDING_REVIEW'").fetchone()[0]
        unmatched = db.execute(
            "SELECT count(*) FROM deposits WHERE order_id IS NULL AND received BETWEEN ? AND ?",
            (boundaries[0], current),
        ).fetchone()[0]
        recent_orders = [present(row, admin=True) for row in db.execute(
            "SELECT * FROM orders ORDER BY created DESC, id DESC LIMIT 6"
        )]
        card = destination_card(db)
    series = []
    for start, end in pairwise(boundaries):
        amounts = [row["amount"] for row in approved if start <= row["timestamp"] < end]
        series.append({"start": start, "amount": sum(amounts), "count": len(amounts)})
    return {
        "series": series,
        "approved_amount": sum(row["amount"] for row in approved),
        "approved_count": len(approved),
        "automatic_count": sum(row["decision"] == "SMS_APPROVED" for row in approved),
        "pending_count": pending,
        "unmatched_count": unmatched,
        "orders": recent_orders,
        "card_last4": card["number"][-4:],
        "bank": card["bank"],
        "sms_format": os.getenv("SMS_FORMAT", "account"),
        "sms_account_configured": bool(os.getenv("SMS_ACCOUNT", "")),
    }


@admin.get("/api/orders")
def admin_orders(status: str = "", q: str = Query(default="", max_length=100),
                 page: int = Query(default=1, ge=1, le=1_000_000)):
    if status and status not in ORDER_STATES:
        raise HTTPException(422)
    current = now()
    search = q.strip().translate(DIGITS).lower()
    with transaction(write=False) as db:
        query = """
            FROM (
                SELECT *, CASE WHEN status='AWAITING_RECEIPT' AND expires<=?
                    THEN 'EXPIRED' ELSE status END AS shown_status
                FROM orders
            )
            WHERE (?='' OR shown_status=?) AND (?='' OR instr(lower(id), ?)>0
                OR instr(lower(json_extract(details, '$.subscriber')), ?)>0
                OR instr(lower(json_extract(details, '$.plan_title_fa')), ?)>0
                OR instr(json_extract(details, '$.tracking_number'), ?)>0)
        """
        values = (current, status, status, search, search, search, search, search)
        total = db.execute("SELECT count(*) " + query, values).fetchone()[0]
        rows = db.execute(
            "SELECT * " + query + " ORDER BY created DESC, id DESC LIMIT ? OFFSET ?",
            (*values, PAGE_SIZE, (page - 1) * PAGE_SIZE),
        )
        return {"total": total, "page_size": PAGE_SIZE, "orders": [present(row, admin=True) for row in rows]}


@admin.get("/api/orders/{order_id}")
def admin_order(order_id: str):
    with transaction(write=False) as db:
        row = db.execute("SELECT * FROM orders WHERE id=?", (order_id,)).fetchone()
        if not row:
            raise HTTPException(404)
        decisions = db.execute(
            "SELECT decision, timestamp FROM decisions WHERE order_id=? ORDER BY timestamp", (order_id,),
        )
        return {"order": present(row, admin=True), "decisions": [dict(item) for item in decisions]}


@admin.get("/api/orders/{order_id}/receipt")
def admin_receipt(order_id: str):
    with transaction(write=False) as db:
        row = db.execute("SELECT image FROM receipts WHERE order_id=?", (order_id,)).fetchone()
        if not row:
            raise HTTPException(404)
        return Response(row["image"], media_type="image/jpeg", headers={
            "Content-Disposition": 'inline; filename="payment-receipt.jpg"',
            "X-Content-Type-Options": "nosniff", "Cross-Origin-Resource-Policy": "same-origin",
        })


@admin.get("/api/deposits")
def admin_deposits(match: str = "", page: int = Query(default=1, ge=1, le=1_000_000)):
    if match not in ("", "matched", "unmatched"):
        raise HTTPException(422)
    query = """FROM deposits WHERE (?='' OR (?='matched' AND order_id IS NOT NULL)
               OR (?='unmatched' AND order_id IS NULL))"""
    params = (match,) * 3
    with transaction(write=False) as db:
        total = db.execute("SELECT count(*) " + query, params).fetchone()[0]
        rows = db.execute(
            "SELECT amount, received, order_id " + query + " ORDER BY received DESC, digest LIMIT ? OFFSET ?",
            (*params, PAGE_SIZE, (page - 1) * PAGE_SIZE),
        )
        return {"total": total, "page_size": PAGE_SIZE, "deposits": [dict(row) for row in rows]}


@admin.get("/api/card")
def admin_card():
    with transaction(write=False) as db:
        history = db.execute(
            "SELECT previous_last4, current_last4, timestamp FROM card_changes ORDER BY id DESC LIMIT 20"
        )
        return {"card": destination_card(db), "history": [dict(item) for item in history]}


def verify_admin_origin(request):
    if request.headers.get("origin") != setting("BILLING_PUBLIC_URL"):
        raise HTTPException(403)


class ProvisioningKey(BaseModel):
    api_key: str = Field(default="", max_length=2048)


class ProvisioningUpdate(ProvisioningKey):
    group_ids: list[int] = Field(min_length=1, max_length=32)


@admin.get("/api/provisioning")
def admin_provisioning():
    try:
        config = provision_config()
    except provisioning.PanelError:
        config = None
    return {"configured": bool(config), "selected_group_ids": config[1] if config else []}


def provisioning_groups(api_key):
    try:
        if not api_key:
            config = provision_config()
            api_key = config[0] if config else ""
        if not re.fullmatch(r"[!-~]{10,2048}", api_key):
            raise HTTPException(422, "PANEL_API_KEY_REQUIRED")
        return api_key, provisioning.available_groups(api_key, now() // MINUTE_MS)
    except provisioning.PanelError:
        raise HTTPException(502, "PANEL_GROUPS_UNAVAILABLE") from None


@admin.post("/api/provisioning/groups")
def read_provisioning_groups(body: ProvisioningKey, request: IncomingRequest):
    verify_admin_origin(request)
    _, groups = provisioning_groups(body.api_key.strip())
    return {"groups": [{"id": key, "name": value} for key, value in sorted(groups.items())]}


@admin.post("/api/provisioning")
def update_provisioning(body: ProvisioningUpdate, request: IncomingRequest):
    verify_admin_origin(request)
    api_key, groups = provisioning_groups(body.api_key.strip())
    group_ids = sorted(set(body.group_ids))
    if not set(group_ids).issubset(groups):
        raise HTTPException(422, "INVALID_GROUPS")
    with transaction() as db:
        db.execute(
            """INSERT INTO provisioning_settings VALUES (1, ?, ?) ON CONFLICT(id)
               DO UPDATE SET api_key=excluded.api_key, group_ids=excluded.group_ids""",
            (key_cipher().encrypt(api_key.encode()), json.dumps(group_ids)),
        )
    return Response(status_code=204)


class CardUpdate(BaseModel):
    number: str = Field(max_length=32)
    holder: str = Field(max_length=100)
    bank: str = Field(max_length=80)
    same_account: bool


@admin.post("/api/card")
def update_admin_card(body: CardUpdate, request: IncomingRequest):
    verify_admin_origin(request)
    number = re.sub(r"[\s-]", "", body.number.translate(DIGITS))
    holder, bank = body.holder.strip(), body.bank.strip()
    if not (re.fullmatch(r"[0-9]{16}", number) and holder and bank and body.same_account):
        raise HTTPException(422)
    with transaction() as db:
        previous = destination_card(db)
        if (previous["number"], previous["holder"], previous["bank"]) == (number, holder, bank):
            return Response(status_code=204)
        current = now()
        db.execute(
            """INSERT INTO destination_card VALUES(1, ?, ?, ?, ?) ON CONFLICT(id) DO UPDATE
               SET number=excluded.number, holder=excluded.holder, bank=excluded.bank, updated=excluded.updated""",
            (number, holder, bank, current),
        )
        db.execute(
            "INSERT INTO card_changes (previous_last4, current_last4, timestamp) VALUES (?, ?, ?)",
            (previous["number"][-4:], number[-4:], current),
        )
    return Response(status_code=204)


@admin.post("/orders/{order_id}")
def review(order_id: str, request: IncomingRequest, decision: str = Form()):
    verify_admin_origin(request)
    if decision not in ("APPROVED", "REJECTED"):
        raise HTTPException(422)
    with transaction() as db:
        row = db.execute("SELECT status FROM orders WHERE id=?", (order_id,)).fetchone()
        if not row or row["status"] != "PENDING_REVIEW":
            raise HTTPException(409)
        decide(db, order_id, decision, "ADMIN_" + decision)
    if request.headers.get("accept") == "application/json":
        return Response(status_code=204)
    return RedirectResponse("/admin", status_code=303)


app.include_router(admin)
