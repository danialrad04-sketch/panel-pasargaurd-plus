"""Verified customer accounts for the billing service."""
import hashlib
import hmac
import os
import re
import secrets
import smtplib
import ssl
from email.message import EmailMessage
from threading import BoundedSemaphore
from uuid import uuid4

from fastapi import APIRouter, HTTPException, Request
from fastapi.responses import JSONResponse, Response
from pydantic import BaseModel, Field

COOKIE = "__Host-pasarguard-customer"
SESSION_SECONDS = 7 * 24 * 60 * 60
CODE_MS = 10 * 60 * 1000
PASSWORD_WORKERS = BoundedSemaphore(2)


def initialize(db):
    db.execute("""CREATE TABLE IF NOT EXISTS customer_accounts (
        id TEXT PRIMARY KEY, email TEXT NOT NULL UNIQUE, password_hash TEXT NOT NULL, created INTEGER NOT NULL
    )""")
    db.execute("""CREATE TABLE IF NOT EXISTS customer_sessions (
        digest TEXT PRIMARY KEY, customer_id TEXT NOT NULL REFERENCES customer_accounts(id), expires INTEGER NOT NULL
    )""")
    db.execute("""CREATE TABLE IF NOT EXISTS customer_email_codes (
        email TEXT PRIMARY KEY, digest TEXT NOT NULL, expires INTEGER NOT NULL, attempts INTEGER NOT NULL DEFAULT 0
    )""")
    db.execute("""CREATE TABLE IF NOT EXISTS customer_rate_limits (
        scope TEXT PRIMARY KEY, count INTEGER NOT NULL, expires INTEGER NOT NULL
    )""")


def gmail(email):
    raw = email.strip().lower()
    if not re.fullmatch(r"[a-z0-9.]{1,30}(?:\+[a-z0-9._-]{1,30})?@gmail\.com", raw):
        raise HTTPException(422, "GMAIL_REQUIRED")
    local = raw.split("@", 1)[0].split("+", 1)[0].replace(".", "")
    if not local:
        raise HTTPException(422, "GMAIL_REQUIRED")
    return local + "@gmail.com"


def derive(password, salt):
    if not PASSWORD_WORKERS.acquire(blocking=False):
        raise HTTPException(429)
    try:
        return hashlib.scrypt(password.encode(), salt=salt, n=2**15, r=8, p=1, maxmem=64 * 1024**2).hex()
    finally:
        PASSWORD_WORKERS.release()


def password_hash(password):
    salt = secrets.token_bytes(16)
    return salt.hex() + ":" + derive(password, salt)


def password_ok(password, stored):
    salt, expected = stored.split(":") if stored else ("0" * 32, "0" * 128)
    return hmac.compare_digest(derive(password, bytes.fromhex(salt)), expected)


def send_code(email, code):
    host = os.getenv("CUSTOMER_SMTP_HOST")
    sender = os.getenv("CUSTOMER_SMTP_FROM")
    if not host or not sender:
        raise HTTPException(503, "CUSTOMER_EMAIL_UNAVAILABLE")
    message = EmailMessage()
    message["From"] = sender
    message["To"] = email
    message["Subject"] = "PasarGuard verification code"
    message.set_content(f"کد تأیید حساب پاسارگارد: {code}\nاین کد تا ۱۰ دقیقه اعتبار دارد.")
    try:
        with smtplib.SMTP_SSL(host, int(os.getenv("CUSTOMER_SMTP_PORT", "465")),
                              timeout=8, context=ssl.create_default_context()) as smtp:
            if username := os.getenv("CUSTOMER_SMTP_USER"):
                smtp.login(username, os.getenv("CUSTOMER_SMTP_PASSWORD", ""))
            smtp.send_message(message)
    except (OSError, smtplib.SMTPException, ValueError):
        raise HTTPException(503, "CUSTOMER_EMAIL_UNAVAILABLE") from None


class Address(BaseModel):
    email: str = Field(max_length=128)


class Login(Address):
    password: str = Field(min_length=1, max_length=128)


class Verify(Address):
    password: str = Field(min_length=12, max_length=128)
    code: str = Field(pattern=r"^[0-9]{6}$")


class CustomerAccounts:
    def __init__(self, transaction, digest, clock, source, verify_origin):
        self.transaction = transaction
        self.digest = digest
        self.now = clock
        self.source = source
        self.verify_origin = verify_origin
        self.router = APIRouter(prefix="/v1/customer")
        self.router.add_api_route("/code", self.issue_code, methods=["POST"])
        self.router.add_api_route("/verify", self.verify, methods=["POST"])
        self.router.add_api_route("/login", self.login, methods=["POST"])
        self.router.add_api_route("/logout", self.logout, methods=["POST"])
        self.router.add_api_route("/me", self.me, methods=["GET"])

    @staticmethod
    def native(request):
        return not request.headers.get("origin") and request.headers.get("x-pasarguard-client") == "native"

    @staticmethod
    def bearer(request):
        scheme, _, token = request.headers.get("authorization", "").partition(" ")
        return token if scheme.lower() == "bearer" else ""

    def protect(self, request):
        if not self.native(request) and not self.bearer(request):
            self.verify_origin(request)

    def session_digest(self, request):
        token = self.bearer(request) or request.cookies.get(COOKIE, "")
        return self.digest("customer-session:" + token) if re.fullmatch(r"[A-Za-z0-9_-]{43}", token) else None

    def current(self, request):
        digest = self.session_digest(request)
        if not digest:
            return None
        with self.transaction(write=False) as db:
            return db.execute("""SELECT a.id, a.email FROM customer_sessions s
                                 JOIN customer_accounts a ON s.customer_id=a.id
                                 WHERE s.digest=? AND s.expires>?""", (digest, self.now())).fetchone()

    def require(self, request):
        account = self.current(request)
        if not account:
            raise HTTPException(401)
        return account

    def rate(self, request, action, email, *, source_limit, email_limit, window):
        current = self.now()
        scopes = ((f"{action}:source:{self.source(request)}", source_limit),
                  (f"{action}:email:{self.digest(email)}", email_limit))
        with self.transaction() as db:
            db.execute("DELETE FROM customer_rate_limits WHERE expires<=?", (current,))
            for scope, limit in scopes:
                row = db.execute("SELECT count FROM customer_rate_limits WHERE scope=?", (scope,)).fetchone()
                if row and row["count"] >= limit:
                    raise HTTPException(429)
                db.execute("""INSERT INTO customer_rate_limits VALUES (?, 1, ?) ON CONFLICT(scope)
                              DO UPDATE SET count=count+1""", (scope, current + window))

    def issue_code(self, body: Address, request: Request):
        self.protect(request)
        email = gmail(body.email)
        self.rate(request, "mail", email, source_limit=20, email_limit=5, window=60 * 60 * 1000)
        code = str(secrets.randbelow(1_000_000)).zfill(6)
        digest = self.digest(f"customer-code:{email}:{code}")
        with self.transaction() as db:
            row = db.execute("SELECT expires FROM customer_email_codes WHERE email=?", (email,)).fetchone()
            if row and row["expires"] > self.now() + CODE_MS - 60_000:
                raise HTTPException(429)
            db.execute("INSERT OR REPLACE INTO customer_email_codes VALUES (?, ?, ?, 0)",
                       (email, digest, self.now() + CODE_MS))
        try:
            send_code(email, code)
        except HTTPException:
            with self.transaction() as db:
                db.execute("DELETE FROM customer_email_codes WHERE email=? AND digest=?", (email, digest))
            raise
        return Response(status_code=204)

    def verify(self, body: Verify, request: Request):
        self.protect(request)
        email = gmail(body.email)
        self.rate(request, "verify", email, source_limit=20, email_limit=10, window=CODE_MS)
        digest = self.digest(f"customer-code:{email}:{body.code}")
        hashed = password_hash(body.password)
        with self.transaction() as db:
            row = db.execute("SELECT * FROM customer_email_codes WHERE email=?", (email,)).fetchone()
            valid = row and row["expires"] > self.now() and row["attempts"] < 5 and hmac.compare_digest(row["digest"], digest)
            if valid:
                db.execute("DELETE FROM customer_email_codes WHERE email=?", (email,))
                db.execute("""INSERT INTO customer_accounts VALUES (?, ?, ?, ?) ON CONFLICT(email)
                              DO UPDATE SET password_hash=excluded.password_hash""",
                           (uuid4().hex, email, hashed, self.now()))
                account_id = db.execute("SELECT id FROM customer_accounts WHERE email=?", (email,)).fetchone()[0]
                db.execute("DELETE FROM customer_sessions WHERE customer_id=?", (account_id,))
            elif row:
                db.execute("UPDATE customer_email_codes SET attempts=attempts+1 WHERE email=?", (email,))
        if not valid:
            raise HTTPException(422, "INVALID_VERIFICATION_CODE")
        return self.start_session(account_id, request, hashed)

    def login(self, body: Login, request: Request):
        self.protect(request)
        email = gmail(body.email)
        self.rate(request, "login", email, source_limit=30, email_limit=12, window=CODE_MS)
        with self.transaction(write=False) as db:
            account = db.execute("SELECT * FROM customer_accounts WHERE email=?", (email,)).fetchone()
        if not password_ok(body.password, account["password_hash"] if account else None):
            raise HTTPException(401, "INVALID_CUSTOMER_LOGIN")
        return self.start_session(account["id"], request, account["password_hash"])

    def start_session(self, account_id, request, expected_hash):
        token = secrets.token_urlsafe(32)
        with self.transaction() as db:
            current_hash = db.execute("SELECT password_hash FROM customer_accounts WHERE id=?", (account_id,)).fetchone()[0]
            if not hmac.compare_digest(expected_hash, current_hash):
                raise HTTPException(401)
            db.execute("DELETE FROM customer_sessions WHERE expires<=? OR digest=?",
                       (self.now(), self.session_digest(request)))
            db.execute("INSERT INTO customer_sessions VALUES (?, ?, ?)",
                       (self.digest("customer-session:" + token), account_id, self.now() + SESSION_SECONDS * 1000))
        if self.native(request):
            return JSONResponse({"access_token": token})
        response = Response(status_code=204)
        response.set_cookie(COOKIE, token, secure=True, httponly=True, samesite="strict", path="/",
                            max_age=SESSION_SECONDS)
        return response

    def logout(self, request: Request):
        self.protect(request)
        with self.transaction() as db:
            db.execute("DELETE FROM customer_sessions WHERE digest=?", (self.session_digest(request),))
        response = Response(status_code=204)
        response.delete_cookie(COOKIE, secure=True, httponly=True, samesite="strict", path="/")
        return response

    def me(self, request: Request):
        return {"email": self.require(request)["email"]}
