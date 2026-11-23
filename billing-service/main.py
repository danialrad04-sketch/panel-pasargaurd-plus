"""Independent PasarGuard billing service."""
import asyncio
import base64
import csv
import fcntl
import hashlib
import hmac
import ipaddress
import json
import logging
import os
import re
import secrets
import sqlite3
import time
from contextlib import contextmanager
from dataclasses import dataclass
from datetime import datetime, timedelta
from functools import lru_cache
from io import BytesIO, StringIO
from itertools import pairwise
from pathlib import Path
from typing import Annotated, Literal
from urllib.error import HTTPError as UpstreamHTTPError, URLError
from urllib.parse import urlsplit
from urllib.request import HTTPRedirectHandler, Request, build_opener
from uuid import UUID, uuid4
from zipfile import BadZipFile, ZipFile
from zoneinfo import ZoneInfo

import customer_accounts
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
from fastapi.responses import FileResponse, HTMLResponse, RedirectResponse, Response
from jdatetime import datetime as JalaliDateTime
from PIL import Image, ImageOps
from pydantic import BaseModel, Field

app = FastAPI(docs_url=None, redoc_url=None, openapi_url=None)
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
    "Referrer-Policy": "same-origin",
    "Content-Security-Policy": "default-src 'none'; script-src 'self'; style-src 'self'; "
    "connect-src 'self'; img-src 'self'; font-src 'self'; form-action 'self'; "
    "frame-ancestors 'none'; base-uri 'none'",
}
ORDER_STATES = ("AWAITING_RECEIPT", "PENDING_REVIEW", "APPROVED", "REJECTED", "EXPIRED")
PAGE_SIZE = 12
MAX_RECEIPT_BYTES = 512 * 1024
ADMIN_COOKIE = "__Host-billing-admin"
ADMIN_SESSION_SECONDS = 8 * 60 * 60
LOGIN_WINDOW_MS = 15 * MINUTE_MS
LOGIN_MAX_FAILURES = 8
EXPORT_MAX_ROWS = 10_000


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
        customer_accounts.initialize(db)
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

        db.execute("""CREATE TABLE IF NOT EXISTS admin_sessions (
            digest TEXT PRIMARY KEY, expires INTEGER NOT NULL
        )""")
        db.execute("""CREATE TABLE IF NOT EXISTS admin_login_attempts (
            source TEXT PRIMARY KEY, failures INTEGER NOT NULL, expires INTEGER NOT NULL
        )""")
        db.execute("CREATE TABLE IF NOT EXISTS catalog_display (id INTEGER PRIMARY KEY CHECK(id=1), maximum INTEGER NOT NULL)")
        db.execute("""CREATE TABLE IF NOT EXISTS admin_activity (
            id INTEGER PRIMARY KEY, action TEXT NOT NULL, timestamp INTEGER NOT NULL
        )""")
        db.execute("CREATE INDEX IF NOT EXISTS decisions_timestamp ON decisions(timestamp)")
        db.execute("CREATE INDEX IF NOT EXISTS admin_activity_timestamp ON admin_activity(timestamp)")
        db.execute("""CREATE TABLE IF NOT EXISTS customer_refunds (
            order_id TEXT PRIMARY KEY REFERENCES orders(id), customer_id TEXT NOT NULL REFERENCES customer_accounts(id),
            requested INTEGER NOT NULL, next_run INTEGER NOT NULL, attempts INTEGER NOT NULL DEFAULT 0,
            amount INTEGER, finished INTEGER
        )""")
        db.execute("""CREATE TABLE IF NOT EXISTS wallet_entries (
            order_id TEXT PRIMARY KEY REFERENCES customer_refunds(order_id),
            customer_id TEXT NOT NULL REFERENCES customer_accounts(id), amount INTEGER NOT NULL CHECK (amount>=0),
            created INTEGER NOT NULL
        )""")
        db.execute("CREATE INDEX IF NOT EXISTS wallet_entries_customer ON wallet_entries(customer_id, created)")
        db.execute("""CREATE TABLE IF NOT EXISTS wallet_debits (
            order_id TEXT PRIMARY KEY REFERENCES orders(id),
            customer_id TEXT NOT NULL REFERENCES customer_accounts(id), amount INTEGER NOT NULL CHECK (amount>0),
            created INTEGER NOT NULL
        )""")
        db.execute("CREATE INDEX IF NOT EXISTS wallet_debits_customer ON wallet_debits(customer_id)")
        db.execute("""CREATE TABLE IF NOT EXISTS customer_order_transfers (
            order_id TEXT PRIMARY KEY REFERENCES orders(id), owner TEXT NOT NULL, digest TEXT NOT NULL UNIQUE,
            expires INTEGER NOT NULL
        )""")
        db.execute("""CREATE TABLE IF NOT EXISTS app_releases (
            platform TEXT PRIMARY KEY, config TEXT NOT NULL
        )""")
        db.execute("""CREATE TABLE IF NOT EXISTS billing_plan_overrides (
            id TEXT PRIMARY KEY, config TEXT NOT NULL
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
               AND NOT EXISTS (
                 SELECT 1 FROM provisioning_jobs prior JOIN orders p ON p.id=prior.order_id
                 WHERE prior.ready=0 AND p.status='APPROVED'
                   AND json_extract(p.details,'$.renewal_target')=json_extract(o.details,'$.renewal_target')
                   AND (p.created < o.created OR (p.created=o.created AND p.id<o.id))
               ) ORDER BY j.next_run LIMIT 1""", (current,),
        ).fetchone()
        if not job:
            return
        db.execute("UPDATE provisioning_jobs SET attempts=attempts+1, next_run=? WHERE order_id=?",
                   (current + 120_000, job["order_id"]))
    order_id = job["order_id"]
    try:
        details = json.loads(job["details"])
        config = provision_config()
        if target := details.get("renewal_target"):
            account = provisioning.renewal_account(target, config)
            changes = details.get("renewal_changes")
            if changes is None:
                changes, start = provisioning.renewal_changes(account, details["purchased_plan"], current)
                details.update(renewal_changes=changes, activation_started_at_ms=start)
                with transaction() as db:
                    db.execute("UPDATE orders SET details=? WHERE id=?", (json.dumps(details), order_id))
            provisioning.apply_renewal(target, config, account, changes)
        else:
            provisioning.provision(order_id, details["purchased_plan"], config)
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
        details.setdefault("activation_started_at_ms", now())
        db.execute("UPDATE orders SET details=? WHERE id=?", (json.dumps(details), order_id))


def refundable_amount(amount, purchased, request_time, panel_expire):
    duration = purchased["purchased_plan"]["duration_days"] * 86_400_000
    if duration <= 0:
        return 0
    starts = purchased.get("activation_started_at_ms") or purchased["reviewed_at_ms"]
    try:
        if isinstance(panel_expire, str):
            expiration = datetime.fromisoformat(panel_expire)
            if expiration.tzinfo is None:
                raise ValueError
            expire_ms = int(expiration.timestamp() * 1000)
        elif type(panel_expire) is int:
            expire_ms = panel_expire if panel_expire >= 1_000_000_000_000 else panel_expire * 1000
        else:
            raise ValueError
    except (ValueError, OverflowError):
        raise provisioning.PanelError() from None
    remaining = max(0, min(duration, min(starts + duration, expire_ms) - request_time))
    return amount * remaining // duration


def subscription_renewals(db, order_id):
    return db.execute(
        """SELECT o.owner,o.status,o.expires,o.details,
                  COALESCE(json_extract(o.details,'$.wallet_paid_toman'), o.amount) AS amount
           FROM orders o WHERE json_extract(o.details,'$.renewal_target')=?""",
        (provisioning.panel_username(order_id),),
    ).fetchall()


def process_refund_job():
    current = now()
    with transaction() as db:
        job = db.execute(
            """SELECT r.*, COALESCE(json_extract(o.details, '$.wallet_paid_toman'), o.amount) AS order_amount,
               o.details FROM customer_refunds r
               JOIN orders o ON o.id=r.order_id WHERE r.finished IS NULL AND r.next_run<=?
               ORDER BY r.next_run LIMIT 1""", (current,),
        ).fetchone()
        if not job:
            return
        db.execute("UPDATE customer_refunds SET attempts=attempts+1, next_run=? WHERE order_id=?",
                   (current + 120_000, job["order_id"]))
    order_id = job["order_id"]
    try:
        config = provision_config()
        account = provisioning.refund_account(order_id, config)
        amount = job["amount"]
        if amount is None:
            amount = refundable_amount(job["order_amount"], json.loads(job["details"]), job["requested"],
                                       account.get("expire"))
            with transaction() as db:
                for renewal in subscription_renewals(db, order_id):
                    if renewal["status"] == "APPROVED":
                        amount += refundable_amount(renewal["amount"], json.loads(renewal["details"]),
                                                    job["requested"], account.get("expire"))
                db.execute("UPDATE customer_refunds SET amount=? WHERE order_id=? AND amount IS NULL",
                           (amount, order_id))
        provisioning.disable_for_refund(order_id, config, account)
    except provisioning.PanelError:
        delay = min(300_000, 10_000 * 2**min(job["attempts"], 5))
        with transaction() as db:
            db.execute("UPDATE customer_refunds SET next_run=? WHERE order_id=? AND finished IS NULL",
                       (now() + delay, order_id))
        return
    with transaction() as db:
        completed = now()
        result = db.execute("UPDATE customer_refunds SET finished=? WHERE order_id=? AND finished IS NULL",
                            (completed, order_id))
        if result.rowcount:
            db.execute("""INSERT INTO wallet_entries (order_id, customer_id, amount, created)
                          SELECT order_id, customer_id, amount, ? FROM customer_refunds WHERE order_id=?""",
                       (completed, order_id))
            db.execute("INSERT INTO decisions VALUES (?, ?, ?)", (order_id, "CUSTOMER_CANCELLED", completed))


@app.on_event("startup")
async def start_provisioning_worker():
    async def run():
        while True:
            try:
                await asyncio.to_thread(process_provisioning_job)
                await asyncio.to_thread(process_refund_job)
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
    if request.url.path.startswith(("/v1/billing/", "/v1/customer/", "/v1/app/", "/account", "/admin")):
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


@app.get("/assets/fonts/{name}")
def web_font(name: str):
    if name not in {"vazirmatn.woff2", "estedad.woff2"}:
        raise HTTPException(404)
    return FileResponse(WEB / "fonts" / name, media_type="font/woff2", headers={
        "Cache-Control": "public, max-age=604800",
        "X-Content-Type-Options": "nosniff",
    })


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
    account_id: str | None = None

    @property
    def is_guest(self):
        return self.subscriber is None


def billing_actor(request: IncomingRequest, x_subscription_token: str = Header(default=""), x_purchase_token: str = Header(default="")):
    account = customers.require(request) if customers.bearer(request) else customers.current(request)
    if account:
        if request.method not in ("GET", "HEAD"):
            customers.protect(request)
        subscriber = customer(x_subscription_token) if x_subscription_token else None
        return BillingActor(account_owner(account["id"]), subscriber, account["id"])
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


def configured_plans(db=None, *, include_hidden=False):
    plans = json.loads(Path(setting("BILLING_PLANS")).read_text(encoding="utf-8"))["plans"]
    items = {}
    for index, plan in enumerate(plans):
        if type(plan["priceToman"]) is not int or plan["priceToman"] < 100:
            raise ValueError("Invalid plan price")
        item = {re.sub(r"[A-Z]", lambda m: "_" + m[0].lower(), key): value for key, value in plan.items()}
        items[item["id"]] = dict(item, visible=True, sort_order=index * 10)
    if db is not None:
        items.update((row["id"], json.loads(row["config"])) for row in db.execute(
            "SELECT id, config FROM billing_plan_overrides"
        ))
    return sorted((item for item in items.values() if include_hidden or item["visible"]),
                  key=lambda item: (item["sort_order"], item["id"]))


def catalog(db=None):
    items = [{key: value for key, value in item.items() if key not in ("visible", "sort_order")}
             for item in configured_plans(db)]
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
    data.pop("renewal_changes", None)
    data.pop("renewal_target", None)
    amount = data.pop("wallet_paid_toman", row["amount"])
    if not admin:
        data.pop("subscriber", None)
    status = row["status"]
    if status == "AWAITING_RECEIPT" and row["expires"] <= now():
        status = "EXPIRED"
    return dict(data, id=row["id"], status=status, final_price_toman=amount,
                created_at_ms=row["created"], expires_at_ms=row["expires"])


@app.get("/v1/billing/plans")
def list_plans(actor=Depends(billing_actor)):
    with transaction(write=False) as db:
        result = catalog(db)
        result["plans"] = sale_plans(db, result)
        return result


def display_limit(db):
    row = db.execute("SELECT maximum FROM catalog_display WHERE id=1").fetchone()
    return row["maximum"] if row else 0


def sale_plans(db, config):
    eligible = [plan for plan in config["plans"] if plan["category"] == "RENEW"]
    return eligible[:display_limit(db) or None]



class OrderRequest(BaseModel):
    plan_id: str = Field(max_length=100)
    payment_method: str = "CARD_TO_CARD"
    discount_code: str | None = None


@app.post("/v1/billing/orders")
def create_order(body: OrderRequest, idempotency_key: Annotated[UUID, Header()], request: IncomingRequest,
                 actor=Depends(billing_actor)):
    owner = actor.owner
    if body.payment_method not in ("CARD_TO_CARD", "WALLET") or body.discount_code:
        raise HTTPException(422, "Unsupported payment method or discount")
    with_wallet = body.payment_method == "WALLET"
    if with_wallet and not actor.account_id:
        raise HTTPException(401)
    if actor.is_guest:
        ensure_guest_checkout()
    else:
        try:
            account = provisioning.renewal_account(actor.subscriber, provision_config())
        except provisioning.PanelError:
            raise HTTPException(503, "RENEWAL_UNAVAILABLE") from None
        if account.get("status") == "disabled":
            raise HTTPException(409, "SUBSCRIPTION_DISABLED")
    with transaction() as db:
        key = str(idempotency_key)
        previous = db.execute(
            "SELECT * FROM orders WHERE owner=? AND request_key=?", (owner, key)
        ).fetchone()
        if previous:
            details = json.loads(previous["details"])
            if details["plan_id"] != body.plan_id or details["payment_method"] != body.payment_method:
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
        plan = next((p for p in sale_plans(db, config) if p["id"] == body.plan_id), None)
        if plan is None:
            raise HTTPException(404)
        if plan["category"] != "RENEW":
            raise HTTPException(422)
        if not actor.is_guest:
            try:
                changes, _ = provisioning.renewal_changes(account, plan, current)
            except provisioning.PanelError:
                raise HTTPException(503, "RENEWAL_UNAVAILABLE") from None
            if not any(account.get(key) != value for key, value in changes.items()):
                raise HTTPException(409, "RENEWAL_NOT_APPLICABLE")
        price = plan["price_toman"]
        if with_wallet:
            if wallet_balance(db, actor.account_id) < price:
                raise HTTPException(409, "INSUFFICIENT_WALLET")
            # Wallet orders cannot match a positive bank deposit. The amount column remains
            # unique for existing installations. Display and ledgers use the debit amount.
            lowest = db.execute("SELECT MIN(amount) FROM orders").fetchone()[0]
            amount = min(lowest or 0, 0) - 1
        else:
            used = {r[0] for r in db.execute(
                """SELECT amount FROM orders WHERE amount BETWEEN ? AND ?
                   UNION SELECT amount FROM deposits WHERE amount BETWEEN ? AND ?""",
                (price, price + 999, price, price + 999),
            )}
            amount = next((n for n in range(price, price + 1000) if n not in used), None)
            if amount is None:
                raise HTTPException(503, "Payment amount range exhausted")
        details = {
            "plan_id": plan["id"],
            "plan_title_fa": plan["title_fa"],
            "plan_title_en": plan["title_en"],
            "plan_category": plan["category"],
            "payment_method": body.payment_method,
            "base_price_toman": price,
            "destination_card": config["destination_card"],
            "subscriber": actor.subscriber or "خرید اشتراک جدید",
        }
        details["purchased_plan"] = {key: plan[key] for key in ("duration_days", "data_limit_gb", "max_devices")}
        if actor.is_guest:
            details["new_subscription"] = True
        else:
            origin = db.execute(
                """SELECT r.order_id FROM customer_refunds r WHERE 'pg_' || substr(r.order_id,1,28)=?""",
                (actor.subscriber,),
            ).fetchone()
            if origin:
                raise HTTPException(409, "SUBSCRIPTION_CANCELLED")
            details["renewal_target"] = actor.subscriber
        if with_wallet:
            details["wallet_paid_toman"] = price
        order_id = uuid4().hex
        db.execute("INSERT INTO orders VALUES (?,?,?,?,?,?,?,?)", (
            order_id, owner, key, amount, "AWAITING_RECEIPT", current,
            current + WINDOW_MS, json.dumps(details),
        ))
        if actor.is_guest:
            db.execute("INSERT INTO guest_reservations VALUES (?,?,?)", (order_id, source, current))
        if with_wallet:
            db.execute("INSERT INTO wallet_debits VALUES (?, ?, ?, ?)", (order_id, actor.account_id, price, current))
            decide(db, order_id, "APPROVED", "WALLET_PAID")
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
            """SELECT o.status, j.ready, r.order_id AS cancelled,
                      json_extract(o.details, '$.new_subscription') AS new_subscription FROM orders o
               LEFT JOIN provisioning_jobs j ON j.order_id=o.id
               LEFT JOIN customer_refunds r ON r.order_id=o.id WHERE o.id=? AND o.owner=?""", (order_id, actor.owner),
        ).fetchone()
        if not row or not row["new_subscription"]:
            raise HTTPException(404)
        if row["cancelled"]:
            raise HTTPException(409, "SUBSCRIPTION_CANCELLED")
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
    if status == "APPROVED" and (details.get("new_subscription") or details.get("renewal_target")):
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


def verify_admin_origin(request):
    if request.headers.get("origin") != setting("BILLING_PUBLIC_URL"):
        raise HTTPException(403)


customers = customer_accounts.CustomerAccounts(transaction, secret_digest, now, reservation_source, verify_admin_origin)
app.include_router(customers.router)


def account_owner(account_id):
    return secret_digest("customer-account:" + account_id)


def wallet_balance(db, account_id):
    return db.execute("""SELECT
        (SELECT COALESCE(SUM(amount), 0) FROM wallet_entries WHERE customer_id=?) -
        (SELECT COALESCE(SUM(amount), 0) FROM wallet_debits WHERE customer_id=?)
    """, (account_id, account_id)).fetchone()[0]


@app.get("/v1/customer/wallet")
def customer_wallet(request: IncomingRequest):
    account = customers.require(request)
    with transaction(write=False) as db:
        balance = wallet_balance(db, account["id"])
        refunds = [dict(row) for row in db.execute(
            """SELECT order_id, CASE WHEN finished IS NULL THEN NULL ELSE amount END AS amount,
               requested, finished FROM customer_refunds WHERE customer_id=? ORDER BY requested DESC LIMIT 100""",
            (account["id"],),
        )]
    return {"balance_toman": balance, "refunds": refunds}


@app.post("/v1/billing/orders/{order_id}/transfer-ticket")
def order_transfer_ticket(order_id: str, x_purchase_token: str = Header(default="")):
    if not re.fullmatch(r"[0-9a-f]{64}", x_purchase_token):
        raise HTTPException(401)
    owner = secret_digest("purchase:" + x_purchase_token)
    ticket = secrets.token_urlsafe(32)
    with transaction() as db:
        row = db.execute("""SELECT 1 FROM orders o JOIN provisioning_jobs j ON j.order_id=o.id
                            WHERE o.id=? AND o.owner=? AND j.ready=1 AND o.status='APPROVED'""",
                         (order_id, owner)).fetchone()
        if not row:
            raise HTTPException(404)
        db.execute("DELETE FROM customer_order_transfers WHERE expires<=?", (now(),))
        db.execute("INSERT OR REPLACE INTO customer_order_transfers VALUES (?, ?, ?, ?)",
                   (order_id, owner, secret_digest("transfer:" + ticket), now() + 15 * MINUTE_MS))
    return {"ticket": ticket}


class TransferRequest(BaseModel):
    ticket: str = Field(pattern=r"^[A-Za-z0-9_-]{43}$")


@app.post("/v1/customer/claim")
def claim_customer_order(body: TransferRequest, request: IncomingRequest):
    customers.protect(request)
    account = customers.require(request)
    with transaction() as db:
        ticket = db.execute("SELECT * FROM customer_order_transfers WHERE digest=? AND expires>?",
                            (secret_digest("transfer:" + body.ticket), now())).fetchone()
        if not ticket:
            raise HTTPException(404, "TRANSFER_EXPIRED")
        updated = db.execute("UPDATE orders SET owner=? WHERE id=? AND owner=?",
                             (account_owner(account["id"]), ticket["order_id"], ticket["owner"]))
        if updated.rowcount != 1:
            raise HTTPException(409)
        db.execute("DELETE FROM customer_order_transfers WHERE order_id=?", (ticket["order_id"],))
    return Response(status_code=204)


@app.post("/v1/customer/orders/{order_id}/cancel")
def cancel_customer_subscription(order_id: str, request: IncomingRequest):
    customers.protect(request)
    account = customers.require(request)
    with transaction() as db:
        order = db.execute(
            """SELECT o.owner, o.status, o.details, j.ready FROM orders o
               LEFT JOIN provisioning_jobs j ON j.order_id=o.id WHERE o.id=? AND o.owner=?""",
            (order_id, account_owner(account["id"])),
        ).fetchone()
        if not order:
            raise HTTPException(404)
        if order["status"] != "APPROVED" or not order["ready"]:
            raise HTTPException(409, "SUBSCRIPTION_NOT_READY")
        current = now()
        details = json.loads(order["details"])
        if not details.get("new_subscription"):
            raise HTTPException(404)
        started = details.get("activation_started_at_ms") or details["reviewed_at_ms"]
        if db.execute("SELECT 1 FROM customer_refunds WHERE order_id=?", (order_id,)).fetchone():
            return Response(status_code=202)
        end = started + details["purchased_plan"]["duration_days"] * 86_400_000
        for renewal in subscription_renewals(db, order_id):
            item = json.loads(renewal["details"])
            if (renewal["status"] == "PENDING_REVIEW"
                    or (renewal["status"] == "AWAITING_RECEIPT" and renewal["expires"] > current)):
                raise HTTPException(409, "RENEWAL_IN_PROGRESS")
            if renewal["status"] == "APPROVED":
                if item.get("activation_status") != "READY":
                    raise HTTPException(409, "RENEWAL_IN_PROGRESS")
                renewal_end = item["activation_started_at_ms"] + item["purchased_plan"]["duration_days"] * 86_400_000
                if renewal_end > current and renewal["owner"] != order["owner"]:
                    raise HTTPException(409, "RENEWAL_OWNER_MISMATCH")
                end = max(end, renewal_end)
        if end <= current:
            raise HTTPException(409, "SUBSCRIPTION_EXPIRED")
        db.execute("""INSERT INTO customer_refunds (order_id, customer_id, requested, next_run)
                      VALUES (?, ?, ?, ?) ON CONFLICT(order_id) DO NOTHING""",
                   (order_id, account["id"], current, current))
    return Response(status_code=202)


def admin_session_digest(token):
    return secret_digest("admin-session:" + setting("ADMIN_PASSWORD") + ":" + token)


def active_admin_session(request):
    token = request.cookies.get(ADMIN_COOKIE, "")
    if not re.fullmatch(r"[A-Za-z0-9_-]{43}", token):
        return None
    digest = admin_session_digest(token)
    with transaction(write=False) as db:
        session = db.execute(
            "SELECT 1 FROM admin_sessions WHERE digest=? AND expires>?", (digest, now()),
        ).fetchone()
        return digest if session else None


def administrator(request: IncomingRequest):
    digest = active_admin_session(request)
    if digest is None:
        raise HTTPException(401)
    return digest


def log_admin_activity(db, action, timestamp):
    db.execute("INSERT INTO admin_activity (action, timestamp) VALUES (?, ?)", (action, timestamp))


def login_page(message="", status_code=200, headers=None):
    html = (WEB / "admin-login.html").read_text(encoding="utf-8")
    if message:
        html = html.replace("<!-- login-error -->", f'<p class="login-error" role="alert">{message}</p>')
    return HTMLResponse(html, status_code=status_code, headers={**ADMIN_HEADERS, **(headers or {})})


@app.get("/admin/login")
def admin_login(request: IncomingRequest):
    if active_admin_session(request):
        return RedirectResponse("/admin", status_code=303)
    return login_page()


@app.post("/admin/login")
def create_admin_session(request: IncomingRequest, username: str = Form(max_length=128),
                         password: str = Form(max_length=4096)):
    verify_admin_origin(request)
    source = reservation_source(request)
    current = now()
    valid = hmac.compare_digest(password.encode(), setting("ADMIN_PASSWORD").encode())
    valid = hmac.compare_digest(username.encode(), b"admin") and valid
    with transaction() as db:
        db.execute("DELETE FROM admin_login_attempts WHERE expires<=?", (current,))
        attempt = db.execute(
            "SELECT failures, expires FROM admin_login_attempts WHERE source=?", (source,),
        ).fetchone()
        if attempt and attempt["failures"] >= LOGIN_MAX_FAILURES:
            retry = (attempt["expires"] - current + 999) // 1000
            return login_page("تعداد تلاش‌ها زیاد بود. پس از چند دقیقه دوباره امتحان کنید.", 429,
                              {"Retry-After": str(retry)})
        if not valid:
            db.execute(
                """INSERT INTO admin_login_attempts VALUES (?, 1, ?) ON CONFLICT(source)
                   DO UPDATE SET failures=failures+1""",
                (source, current + LOGIN_WINDOW_MS),
            )
            return login_page("نام کاربری یا رمز عبور صحیح نیست.", 401)
        token = secrets.token_urlsafe(32)
        previous = request.cookies.get(ADMIN_COOKIE, "")
        if re.fullmatch(r"[A-Za-z0-9_-]{43}", previous):
            db.execute("DELETE FROM admin_sessions WHERE digest=?", (admin_session_digest(previous),))
        db.execute("DELETE FROM admin_login_attempts WHERE source=?", (source,))
        db.execute("DELETE FROM admin_sessions WHERE expires<=?", (current,))
        db.execute("INSERT INTO admin_sessions VALUES (?, ?)",
                   (admin_session_digest(token), current + ADMIN_SESSION_SECONDS * 1000))
        log_admin_activity(db, "LOGIN", current)
    response = RedirectResponse("/admin", status_code=303)
    response.set_cookie(ADMIN_COOKIE, token, max_age=ADMIN_SESSION_SECONDS, secure=True, httponly=True,
                        samesite="strict", path="/")
    return response


@app.post("/admin/logout")
def admin_logout(request: IncomingRequest):
    verify_admin_origin(request)
    digest = active_admin_session(request)
    if digest:
        with transaction() as db:
            if db.execute("DELETE FROM admin_sessions WHERE digest=?", (digest,)).rowcount:
                log_admin_activity(db, "LOGOUT", now())
    response = RedirectResponse("/admin/login", status_code=303)
    response.delete_cookie(ADMIN_COOKIE, secure=True, httponly=True, samesite="strict", path="/")
    return response


@app.get("/admin")
def dashboard(request: IncomingRequest):
    if not active_admin_session(request):
        return RedirectResponse("/admin/login", status_code=303)
    return FileResponse(WEB / "admin.html", headers=ADMIN_HEADERS)


@app.get("/admin/dashboard.css")
def dashboard_styles():
    return FileResponse(WEB / "admin.css", media_type="text/css", headers=ADMIN_HEADERS)


@app.get("/admin/utilities.css")
def admin_utilities():
    return FileResponse(WEB / "admin-utilities.css", media_type="text/css", headers=ADMIN_HEADERS)


@app.get("/admin/login.js")
def login_script():
    return FileResponse(WEB / "admin-login.js", media_type="text/javascript", headers=ADMIN_HEADERS)


@app.get("/admin/dashboard.js")
def dashboard_script():
    return FileResponse(WEB / "admin.js", media_type="text/javascript", headers=ADMIN_HEADERS)


admin = APIRouter(prefix="/admin", dependencies=[Depends(administrator)])


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
            """SELECT COALESCE(json_extract(o.details, '$.wallet_paid_toman'), o.amount) AS amount,
                      d.timestamp, d.decision FROM decisions d
               JOIN orders o ON o.id=d.order_id
               WHERE d.timestamp>=? AND d.timestamp<=?
               AND d.decision IN ('SMS_APPROVED', 'ADMIN_APPROVED', 'WALLET_PAID') AND o.status='APPROVED'""",
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
        review_queue = [present(row, admin=True) for row in db.execute(
            "SELECT * FROM orders WHERE status='PENDING_REVIEW' ORDER BY created, id LIMIT 4"
        )]
        card = destination_card(db)
        oldest = db.execute("SELECT MIN(created) FROM orders WHERE status='PENDING_REVIEW'").fetchone()[0]
        awaiting = db.execute(
            "SELECT count(*) FROM orders WHERE status='AWAITING_RECEIPT' AND expires>?", (current,),
        ).fetchone()[0]
    series = []
    for start, end in pairwise(boundaries):
        amounts = [row["amount"] for row in approved if start <= row["timestamp"] < end]
        series.append({"start": start, "amount": sum(amounts), "count": len(amounts)})
    return {
        "series": series,
        "approved_amount": sum(row["amount"] for row in approved),
        "approved_count": len(approved),
        "automatic_count": sum(row["decision"] in ("SMS_APPROVED", "WALLET_PAID") for row in approved),
        "pending_count": pending,
        "unmatched_count": unmatched,
        "orders": recent_orders,
        "review_queue": review_queue,
        "oldest_pending_ms": oldest,
        "awaiting_count": awaiting,
        "card_last4": card["number"][-4:],
        "bank": card["bank"],
        "sms_format": os.getenv("SMS_FORMAT", "account"),
        "sms_account_configured": bool(os.getenv("SMS_ACCOUNT", "")),
    }


def filtered_orders(status, q, receipt):
    if (status and status not in ORDER_STATES) or receipt not in ("", "image"):
        raise HTTPException(422)
    search = q.strip().translate(DIGITS).lower()
    query = """
        FROM (
            SELECT *, CASE WHEN status='AWAITING_RECEIPT' AND expires<=?
                THEN 'EXPIRED' ELSE status END AS shown_status
            FROM orders
        )
        WHERE (?='' OR shown_status=?)
        AND (?='' OR json_extract(details, '$.has_receipt_image')=1)
        AND (?='' OR instr(lower(id), ?)>0
            OR instr(lower(json_extract(details, '$.subscriber')), ?)>0
            OR instr(lower(json_extract(details, '$.plan_title_fa')), ?)>0
            OR instr(json_extract(details, '$.tracking_number'), ?)>0)
    """
    return query, (now(), status, status, receipt, search, search, search, search, search)


@admin.get("/api/orders")
def admin_orders(status: str = "", q: str = Query(default="", max_length=100), receipt: str = "",
                 page: int = Query(default=1, ge=1, le=1_000_000)):
    query, values = filtered_orders(status, q, receipt)
    with transaction(write=False) as db:
        total = db.execute("SELECT count(*) " + query, values).fetchone()[0]
        rows = db.execute(
            "SELECT * " + query + " ORDER BY created DESC, id DESC LIMIT ? OFFSET ?",
            (*values, PAGE_SIZE, (page - 1) * PAGE_SIZE),
        )
        return {"total": total, "page_size": PAGE_SIZE, "orders": [present(row, admin=True) for row in rows]}


@admin.get("/api/exports/orders")
def export_orders(status: str = "", q: str = Query(default="", max_length=100), receipt: str = ""):
    query, values = filtered_orders(status, q, receipt)
    output = StringIO()
    writer = csv.writer(output)
    writer.writerow(("order_id", "amount_toman", "status", "created_at_utc", "has_receipt_image"))
    with transaction(write=False) as db:
        rows = db.execute(
            """SELECT id, COALESCE(json_extract(details, '$.wallet_paid_toman'), amount) AS amount,
                      shown_status, created, json_extract(details, '$.has_receipt_image') AS image """
            + query + " ORDER BY created DESC, id DESC LIMIT ?",
            (*values, EXPORT_MAX_ROWS + 1),
        )
        for index, row in enumerate(rows):
            if index == EXPORT_MAX_ROWS:
                raise HTTPException(422, "EXPORT_LIMIT")
            exported_id = row["id"] if re.fullmatch(r"[0-9a-f]{32}", row["id"]) else ""
            stamp = datetime.fromtimestamp(row["created"] / 1000, ZoneInfo("UTC")).isoformat()
            writer.writerow((exported_id, row["amount"], row["shown_status"], stamp, int(bool(row["image"]))))
    return Response("\ufeff" + output.getvalue(), media_type="text/csv", headers={
        "Content-Disposition": 'attachment; filename="pasarguard-orders.csv"',
        "X-Content-Type-Options": "nosniff",
    })


@admin.get("/api/activity")
def admin_activity(kind: str = "", page: int = Query(default=1, ge=1, le=1_000_000)):
    if kind not in ("", "orders", "access", "settings"):
        raise HTTPException(422)
    query = """
        FROM (
            SELECT decision AS action, timestamp, order_id, 'orders' AS kind, rowid AS event_id FROM decisions
            UNION ALL
            SELECT action, timestamp, NULL, CASE WHEN action IN ('LOGIN', 'LOGOUT') THEN 'access'
                ELSE 'settings' END, id FROM admin_activity
            UNION ALL
            SELECT 'CARD_UPDATED', timestamp, NULL, 'settings', id FROM card_changes
        ) WHERE (?='' OR kind=?)
    """
    with transaction(write=False) as db:
        values = (kind, kind)
        total = db.execute("SELECT count(*) " + query, values).fetchone()[0]
        rows = db.execute(
            "SELECT action, timestamp, order_id, kind " + query
            + " ORDER BY timestamp DESC, kind, event_id DESC LIMIT ? OFFSET ?",
            (*values, PAGE_SIZE, (page - 1) * PAGE_SIZE),
        )
        return {"total": total, "page_size": PAGE_SIZE, "events": [dict(row) for row in rows]}


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


APK_LIMIT = 150 * 1024 * 1024
APK_CHUNK = 512 * 1024


def apk_directory():
    directory = Path(setting("BILLING_DB")).parent / "apk"
    directory.mkdir(mode=0o700, parents=True, exist_ok=True)
    return directory


@admin.get("/api/apk")
def uploaded_apks():
    files = []
    for path in apk_directory().glob("*.apk"):
        if not re.fullmatch(r"[0-9a-f]{64}", path.stem):
            continue
        try:
            info = path.stat()
        except FileNotFoundError:
            continue
        files.append({
            "download_url": setting("BILLING_PUBLIC_URL") + "/downloads/" + path.name,
            "sha256": path.stem,
            "size": info.st_size,
            "timestamp": int(info.st_mtime * 1000),
        })
    return {"files": sorted(files, key=lambda item: item["timestamp"], reverse=True)[:30]}


@admin.put("/api/apk/{upload_id}")
async def upload_apk_chunk(upload_id: str, request: IncomingRequest, offset: int = Query(ge=0, lt=APK_LIMIT)):
    verify_admin_origin(request)
    if not re.fullmatch(r"[0-9a-f]{32}", upload_id):
        raise HTTPException(404)
    data = bytearray()
    async for block in request.stream():
        if len(data) + len(block) > APK_CHUNK:
            raise HTTPException(413)
        data.extend(block)
    if not data or offset + len(data) > APK_LIMIT:
        raise HTTPException(413)
    path = apk_directory() / (upload_id + ".part")
    if offset:
        if not path.is_file():
            raise HTTPException(404)
    else:
        cutoff = now() / 1000 - 86_400
        for partial in path.parent.glob("*.part"):
            try:
                if partial.stat().st_mtime < cutoff:
                    partial.unlink(missing_ok=True)
            except FileNotFoundError:
                continue
    with path.open("ab+" if offset == 0 else "rb+") as output:
        fcntl.flock(output, fcntl.LOCK_EX)
        if output.seek(0, 2) != offset:
            raise HTTPException(409)
        output.write(data)
    return Response(status_code=204)


@admin.post("/api/apk/{upload_id}")
def finish_apk_upload(upload_id: str, request: IncomingRequest, size: int = Query(ge=1, le=APK_LIMIT)):
    verify_admin_origin(request)
    if not re.fullmatch(r"[0-9a-f]{32}", upload_id):
        raise HTTPException(404)
    path = apk_directory() / (upload_id + ".part")
    if not path.is_file():
        raise HTTPException(404)
    with path.open("rb") as payload:
        fcntl.flock(payload, fcntl.LOCK_EX)
        if os.fstat(payload.fileno()).st_size != size:
            raise HTTPException(409)
        try:
            with ZipFile(payload) as archive:
                manifest = archive.getinfo("AndroidManifest.xml")
                if not 1 <= manifest.file_size <= 2 * 1024 * 1024:
                    raise ValueError
        except (BadZipFile, KeyError, ValueError):
            path.unlink(missing_ok=True)
            raise HTTPException(422, "INVALID_APK") from None
        payload.seek(0)
        sha256 = hashlib.file_digest(payload, "sha256").hexdigest()
        path.replace(path.with_name(sha256 + ".apk"))
    return {"download_url": setting("BILLING_PUBLIC_URL") + "/downloads/" + sha256 + ".apk", "sha256": sha256}


@app.get("/downloads/{filename}")
def download_apk(filename: str):
    if not re.fullmatch(r"[0-9a-f]{64}\.apk", filename):
        raise HTTPException(404)
    path = apk_directory() / filename
    if not path.is_file():
        raise HTTPException(404)
    return FileResponse(path, media_type="application/vnd.android.package-archive", filename="PasarGuard.apk",
                        headers={"Cache-Control": "public, max-age=31536000, immutable"})


class AppReleaseAsset(BaseModel):
    abi: Literal["arm64-v8a", "armeabi-v7a", "x86_64", "universal"]
    download_url: str = Field(max_length=1000)
    sha256: str = Field(pattern=r"^[0-9a-f]{64}$")


class AppReleaseUpdate(BaseModel):
    latest_version: str = Field(pattern=r"^[0-9]+\.[0-9]+(?:\.[0-9]+){0,2}$", max_length=30)
    latest_version_code: int = Field(ge=1, le=2_100_000_000)
    changelog: str = Field(default="", max_length=2000)
    package_name: str = Field(pattern=r"^[A-Za-z][A-Za-z0-9_-]*(?:\.[A-Za-z0-9_-]+)+$", max_length=160)
    store_url: str | None = Field(default=None, max_length=1000)
    assets: list[AppReleaseAsset] = Field(default_factory=list, max_length=4)
    visible: bool = False


def checked_release_url(value):
    try:
        parts = urlsplit(value)
        if parts.scheme == "https" and parts.hostname and not (parts.username or parts.password or parts.fragment):
            return parts
    except ValueError:
        pass
    raise HTTPException(422, "HTTPS_RELEASE_URL_REQUIRED")


@admin.get("/api/releases")
def admin_app_releases():
    with transaction(write=False) as db:
        return {row["platform"]: json.loads(row["config"]) for row in db.execute("SELECT * FROM app_releases")}


@admin.put("/api/releases/{platform}")
def admin_update_release(platform: Literal["android", "ios"], body: AppReleaseUpdate, request: IncomingRequest):
    verify_admin_origin(request)
    if platform == "android":
        if body.store_url or len({item.abi for item in body.assets}) != len(body.assets) or (body.visible and not body.assets):
            raise HTTPException(422, "ANDROID_APK_REQUIRED")
        for asset in body.assets:
            if not checked_release_url(asset.download_url).path.lower().endswith(".apk"):
                raise HTTPException(422, "ANDROID_APK_REQUIRED")
    else:
        if body.assets or (body.visible and not body.store_url):
            raise HTTPException(422, "APPLE_STORE_REQUIRED")
        if body.store_url and checked_release_url(body.store_url).netloc not in ("apps.apple.com", "testflight.apple.com"):
            raise HTTPException(422, "APPLE_STORE_REQUIRED")
    with transaction() as db:
        db.execute("""INSERT INTO app_releases VALUES (?, ?) ON CONFLICT(platform)
                      DO UPDATE SET config=excluded.config""", (platform, body.model_dump_json()))
        log_admin_activity(db, "RELEASE_UPDATED", now())
    return Response(status_code=204)


@admin.delete("/api/releases/android")
def restore_android_github_release(request: IncomingRequest):
    verify_admin_origin(request)
    if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", os.getenv("ANDROID_RELEASE_REPOSITORY", "")):
        raise HTTPException(409, "ANDROID_GITHUB_FEED_NOT_CONFIGURED")
    with transaction() as db:
        db.execute("DELETE FROM app_releases WHERE platform='android'")
        log_admin_activity(db, "RELEASE_UPDATED", now())
    return Response(status_code=204)


class ReleaseRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        target = urlsplit(newurl)
        if target.scheme != "https" or target.netloc not in ("github.com", "release-assets.githubusercontent.com"):
            return None
        return super().redirect_request(req, fp, code, msg, headers, newurl)


@lru_cache(maxsize=4)
def github_android_release(repository, _bucket):
    if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repository):
        return None
    url = f"https://github.com/{repository}/releases/latest/download/pasarguard-android.json"
    try:
        with build_opener(ReleaseRedirect()).open(url, timeout=4) as response:
            payload = response.read(65_537)
        if len(payload) > 65_536:
            return None
        release = AppReleaseUpdate.model_validate_json(payload)
        prefix = f"https://github.com/{repository}/releases/download/"
        if release.assets and all(asset.download_url.startswith(prefix) and asset.download_url.endswith(".apk")
                                  for asset in release.assets):
            return release.model_dump()
    except (OSError, URLError, ValueError):
        pass
    return None


@app.get("/v1/app/config")
def published_app_release(platform: Literal["android", "ios"], version_code: int = Query(ge=0),
                          package_name: str = Query(max_length=160), abi: str = Query(default="", max_length=30)):
    with transaction(write=False) as db:
        row = db.execute("SELECT config FROM app_releases WHERE platform=?", (platform,)).fetchone()
    release = json.loads(row["config"]) if row else None
    if not row and platform == "android" and (repository := os.getenv("ANDROID_RELEASE_REPOSITORY", "")):
        release = github_android_release(repository, now() // (5 * MINUTE_MS))
    if (not release or not release["visible"] or release["package_name"] != package_name
            or release["latest_version_code"] <= version_code):
        return Response(status_code=204)
    if platform == "android":
        assets = {item["abi"]: item for item in release["assets"]}
        asset = assets.get(abi) or assets.get("universal")
        if not asset:
            return Response(status_code=204)
        url, checksum = asset["download_url"], asset["sha256"]
    else:
        url, checksum = release["store_url"], None
    return {
        "latest_version": release["latest_version"], "latest_version_code": release["latest_version_code"],
        "download_url": url, "sha256": checksum, "changelog": release["changelog"],
    }


class BillingPlanUpdate(BaseModel):
    category: Literal["RENEW", "UPGRADE", "EXTRA_TRAFFIC", "EXTRA_DAYS"]
    title_fa: str = Field(min_length=1, max_length=100)
    title_en: str = Field(min_length=1, max_length=100)
    duration_days: int = Field(ge=0, le=3650)
    data_limit_gb: int | None = Field(default=None, ge=0, le=100_000)
    price_toman: int = Field(ge=100, le=1_000_000_000)
    old_price_toman: int | None = Field(default=None, ge=100, le=1_000_000_000)
    tag_fa: str | None = Field(default=None, max_length=50)
    tag_en: str | None = Field(default=None, max_length=50)
    is_popular: bool = False
    is_best_value: bool = False
    max_devices: int | None = Field(default=None, ge=1, le=1000)
    features_fa: list[Annotated[str, Field(min_length=1, max_length=120)]] = Field(default_factory=list, max_length=8)
    features_en: list[Annotated[str, Field(min_length=1, max_length=120)]] = Field(default_factory=list, max_length=8)
    visible: bool = True
    sort_order: int = Field(default=0, ge=0, le=100_000)


@admin.get("/api/catalog")
def admin_catalog():
    with transaction(write=False) as db:
        return {"plans": configured_plans(db, include_hidden=True), "maximum": display_limit(db)}


@admin.post("/api/catalog-display")
def save_display_limit(request: IncomingRequest, maximum: Annotated[int, Form(ge=0, le=10000)]):
    verify_admin_origin(request)
    with transaction() as db:
        db.execute("INSERT OR REPLACE INTO catalog_display VALUES (1, ?)", (maximum,))
        log_admin_activity(db, "CATALOG_UPDATED", now())
    return Response(status_code=204)


@admin.put("/api/catalog/{plan_id}")
def save_admin_plan(plan_id: str, body: BillingPlanUpdate, request: IncomingRequest):
    verify_admin_origin(request)
    if not re.fullmatch(r"[a-z0-9_][a-z0-9_-]{0,79}", plan_id):
        raise HTTPException(422, "INVALID_PLAN_ID")
    item = dict(body.model_dump(), id=plan_id)
    item["title_fa"] = body.title_fa.strip()
    item["title_en"] = body.title_en.strip()
    if (not item["title_fa"] or not item["title_en"]
            or (body.old_price_toman and body.old_price_toman < body.price_toman)
            or (body.duration_days == 0 and body.category != "EXTRA_TRAFFIC")
            or (body.category == "EXTRA_TRAFFIC" and not body.data_limit_gb)):
        raise HTTPException(422, "INVALID_PLAN_DETAILS")
    with transaction() as db:
        db.execute("""INSERT INTO billing_plan_overrides VALUES (?, ?) ON CONFLICT(id)
                      DO UPDATE SET config=excluded.config""", (plan_id, json.dumps(item)))
        log_admin_activity(db, "CATALOG_UPDATED", now())
    return Response(status_code=204)


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
        log_admin_activity(db, "PROVISIONING_UPDATED", now())
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
    if decision not in ("APPROVED", "REJECTED", "CANCEL"):
        raise HTTPException(422)
    with transaction() as db:
        row = db.execute("SELECT status, expires FROM orders WHERE id=?", (order_id,)).fetchone()
        required = "AWAITING_RECEIPT" if decision == "CANCEL" else "PENDING_REVIEW"
        if not row or row["status"] != required or (decision == "CANCEL" and row["expires"] <= now()):
            raise HTTPException(409)
        decide(db, order_id, "REJECTED" if decision == "CANCEL" else decision, "ADMIN_" + decision)
    if request.headers.get("accept") == "application/json":
        return Response(status_code=204)
    return RedirectResponse("/admin", status_code=303)


app.include_router(admin)
