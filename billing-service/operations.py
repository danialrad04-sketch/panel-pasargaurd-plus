"""Customer support, service notices and the operations dashboard."""
import hashlib
import json
import os
import re
import uuid
from urllib.error import HTTPError
from urllib.request import Request as HttpRequest, urlopen

from fastapi import HTTPException, Query, Request
from pydantic import BaseModel, Field, field_validator


def endpoint_key(endpoint):
    return hashlib.sha256(endpoint.encode()).hexdigest()


def initialize(db):
    schema = """
    CREATE TABLE IF NOT EXISTS support_threads (
        id TEXT PRIMARY KEY, customer TEXT NOT NULL REFERENCES customer_accounts(id),
        request_id TEXT NOT NULL, subject TEXT NOT NULL, category TEXT NOT NULL,
        order_id TEXT REFERENCES orders(id), status TEXT NOT NULL, updated INTEGER NOT NULL,
        UNIQUE(customer, request_id)
    );
    CREATE TABLE IF NOT EXISTS support_messages (
        sequence INTEGER PRIMARY KEY, thread_id TEXT NOT NULL REFERENCES support_threads(id),
        request_id TEXT NOT NULL, sender TEXT NOT NULL, body TEXT NOT NULL,
        diagnostic TEXT, created INTEGER NOT NULL, UNIQUE(thread_id, sender, request_id)
    );
    CREATE TABLE IF NOT EXISTS support_reads (
        thread_id TEXT PRIMARY KEY REFERENCES support_threads(id), sequence INTEGER NOT NULL
    );
    CREATE INDEX IF NOT EXISTS support_threads_customer ON support_threads(customer, updated);
    CREATE TABLE IF NOT EXISTS service_incidents (
        id TEXT PRIMARY KEY, title TEXT NOT NULL, body TEXT NOT NULL, guidance TEXT NOT NULL,
        scope TEXT NOT NULL, server_ids TEXT NOT NULL, status TEXT NOT NULL,
        revision INTEGER NOT NULL, updated INTEGER NOT NULL
    );
    CREATE TABLE IF NOT EXISTS support_devices (
        installation_id TEXT PRIMARY KEY, token TEXT UNIQUE NOT NULL,
        customer TEXT REFERENCES customer_accounts(id), session_digest TEXT, server_id TEXT, updated INTEGER NOT NULL
    );
    CREATE TABLE IF NOT EXISTS service_push (
        id INTEGER PRIMARY KEY, token TEXT NOT NULL, kind TEXT NOT NULL,
        resource TEXT NOT NULL, revision INTEGER NOT NULL,
        attempts INTEGER NOT NULL DEFAULT 0, next_run INTEGER NOT NULL,
        UNIQUE(token, kind, resource, revision)
    );
    """
    for statement in schema.split(";"):
        if statement.strip():
            db.execute(statement)


class Message(BaseModel):
    request_id: uuid.UUID
    body: str = Field(min_length=1, max_length=4000)

    @field_validator("body")
    @classmethod
    def clean_body(cls, value):
        if not value.strip():
            raise ValueError("Message cannot be blank")
        return value.strip()


class Diagnostic(BaseModel):
    network: str = Field(pattern=r"^(wifi|cellular|other|offline)$")
    failure: str = Field(pattern=r"^(network|dns|subscription|server|unknown)$")
    version: str = Field(pattern=r"^[0-9]+(?:\.[0-9]+){1,3}$")


class NewThread(Message):
    subject: str = Field(min_length=1, max_length=120)
    category: str = Field(pattern=r"^(connection|payment|subscription|other)$")
    order_id: str | None = Field(default=None, pattern=r"^[a-f0-9]{32}$")
    diagnostic: Diagnostic | None = None


class StatusChange(BaseModel):
    status: str = Field(pattern=r"^(waiting|investigating|resolved)$")


class ReadThrough(BaseModel):
    sequence: int = Field(ge=0)


class Incident(BaseModel):
    title: str = Field(min_length=1, max_length=140)
    body: str = Field(min_length=1, max_length=2000)
    guidance: str = Field(default="", max_length=500)
    scope: str = Field(pattern=r"^(connection|payment|update)$")
    server_ids: list[str] = Field(default_factory=list, max_length=40)
    status: str = Field(pattern=r"^(investigating|resolved)$")
    expected_revision: int = Field(default=0, ge=0)

    @field_validator("server_ids")
    @classmethod
    def validate_servers(cls, values):
        endpoints = []
        for value in values:
            host, _, port = value.strip().lower().rpartition(":")
            host = host.strip("[]").rstrip(".")
            if not re.fullmatch(r"[a-z0-9_.:-]{1,253}", host) or not port.isdigit() or not 1 <= int(port) <= 65535:
                raise ValueError("Expected a server host:port")
            endpoints.append(f"{host}:{int(port)}")
        return list(dict.fromkeys(endpoints))


class Device(BaseModel):
    installation_id: uuid.UUID
    token: str = Field(min_length=20, max_length=4096, pattern=r"^[A-Za-z0-9_:\-]+$")
    server_id: str | None = Field(default=None, pattern=r"^[a-f0-9]{64}$")


class Operations:
    def __init__(self, transaction, clock, customers, owner, verify_origin, audit):
        self.transaction = transaction
        self.now = clock
        self.customers = customers
        self.owner = owner
        self.verify_origin = verify_origin
        self.audit = audit
        self.credentials = None

    @staticmethod
    def thread(db, thread_id, customer=None):
        row = db.execute(
            """SELECT * FROM support_threads WHERE id=? AND (? IS NULL OR customer=?)""",
            (thread_id, customer, customer),
        ).fetchone()
        if not row:
            raise HTTPException(404, "SUPPORT_THREAD_NOT_FOUND")
        return row

    @staticmethod
    def list_threads(db, customer, limit, offset):
        rows = db.execute("""
            SELECT t.id, t.subject, t.category, t.status, t.order_id, t.updated,
                (SELECT count(*) FROM support_messages m WHERE m.thread_id=t.id AND m.sender='staff'
                    AND m.sequence>COALESCE(r.sequence, 0)) AS unread
            FROM support_threads t LEFT JOIN support_reads r ON r.thread_id=t.id
            WHERE (? IS NULL OR t.customer=?) ORDER BY t.updated DESC, t.id DESC LIMIT ? OFFSET ?
        """, (customer, customer, limit, offset))
        return [dict(row) for row in rows]

    @staticmethod
    def messages(db, thread_id, before, limit):
        rows = db.execute("""SELECT sequence, sender, body, diagnostic, created FROM support_messages
            WHERE thread_id=? AND sequence<? ORDER BY sequence DESC LIMIT ?""", (thread_id, before, limit))
        result = [dict(row) for row in rows]
        for row in result:
            row["diagnostic"] = json.loads(row["diagnostic"]) if row["diagnostic"] else None
        return result[::-1]

    def customer_list(self, request: Request, limit: int = Query(50, ge=1, le=100), offset: int = Query(0, ge=0)):
        customer = self.customers.require(request)
        with self.transaction(write=False) as db:
            return self.list_threads(db, customer["id"], limit, offset)

    def create(self, request: Request, body: NewThread):
        self.customers.protect(request)
        customer = self.customers.require(request)["id"]
        key = str(body.request_id)
        with self.transaction() as db:
            existing = db.execute("SELECT id FROM support_threads WHERE customer=? AND request_id=?",
                                  (customer, key)).fetchone()
            if existing:
                return {"id": existing["id"]}
            if body.order_id and not db.execute(
                "SELECT 1 FROM orders WHERE id=? AND owner=?", (body.order_id, self.owner(customer)),
            ).fetchone():
                raise HTTPException(404, "ORDER_NOT_FOUND")
            thread_id = uuid.uuid4().hex
            current = self.now()
            db.execute("INSERT INTO support_threads VALUES (?,?,?,?,?,?,?,?)",
                       (thread_id, customer, key, body.subject.strip() or body.category, body.category,
                        body.order_id, "waiting", current))
            db.execute("""INSERT INTO support_messages(thread_id,request_id,sender,body,diagnostic,created)
                          VALUES (?,?,'customer',?,?,?)""",
                       (thread_id, key, body.body, body.diagnostic.model_dump_json() if body.diagnostic else None, current))
            return {"id": thread_id}

    def detail(self, request: Request, thread_id: str, before: int = Query(2**63-1, ge=1, le=2**63-1)):
        customer = self.customers.require(request)["id"]
        with self.transaction(write=False) as db:
            row = dict(self.thread(db, thread_id, customer))
            return {"id": row["id"], "subject": row["subject"], "status": row["status"],
                    "order_id": row["order_id"], "messages": self.messages(db, thread_id, before, 50)}

    def read(self, request: Request, thread_id: str, body: ReadThrough):
        self.customers.protect(request)
        customer = self.customers.require(request)["id"]
        with self.transaction() as db:
            self.thread(db, thread_id, customer)
            sequence = db.execute("SELECT COALESCE(MAX(sequence),0) FROM support_messages WHERE thread_id=? AND sequence<=?",
                                  (thread_id, body.sequence)).fetchone()[0]
            db.execute("""INSERT INTO support_reads VALUES (?,?) ON CONFLICT(thread_id)
                          DO UPDATE SET sequence=MAX(sequence, excluded.sequence)""", (thread_id, sequence))
        return {"ok": True}

    def customer_reply(self, request: Request, thread_id: str, body: Message):
        self.customers.protect(request)
        return self.reply(thread_id, body, "customer", self.customers.require(request)["id"])

    def staff_reply(self, request: Request, thread_id: str, body: Message):
        self.verify_origin(request)
        return self.reply(thread_id, body, "staff")

    def reply(self, thread_id, body, sender, customer=None):
        current = self.now()
        with self.transaction() as db:
            thread = self.thread(db, thread_id, customer)
            row = db.execute("""INSERT INTO support_messages(thread_id,request_id,sender,body,created)
                VALUES (?,?,?,?,?) ON CONFLICT(thread_id,sender,request_id) DO NOTHING RETURNING sequence""",
                (thread_id, str(body.request_id), sender, body.body, current)).fetchone()
            if not row:
                return {"ok": True}
            db.execute("UPDATE support_threads SET status=?, updated=? WHERE id=?",
                       ("waiting" if sender == "customer" else "investigating", current, thread_id))
            if sender == "staff":
                self.audit(db, "SUPPORT_REPLY", current)
                db.execute("""INSERT OR IGNORE INTO service_push(token,kind,resource,revision,next_run)
                    SELECT d.token,'support',?,?,? FROM support_devices d
                    JOIN customer_sessions s ON s.digest=d.session_digest
                    WHERE d.customer=? AND s.expires>? AND d.updated>?""",
                    (thread_id, row["sequence"], current, thread["customer"], current, current - 30*86400000))
        return {"ok": True}

    def staff_list(self, limit: int = Query(50, ge=1, le=100), offset: int = Query(0, ge=0)):
        with self.transaction(write=False) as db:
            return self.list_threads(db, None, limit, offset)

    def staff_detail(self, thread_id: str, before: int = Query(2**63-1, ge=1, le=2**63-1)):
        with self.transaction(write=False) as db:
            row = self.thread(db, thread_id)
            return {"subject": row["subject"], "status": row["status"], "order_id": row["order_id"],
                    "messages": self.messages(db, thread_id, before, 50)}

    def staff_status(self, request: Request, thread_id: str, body: StatusChange):
        self.verify_origin(request)
        with self.transaction() as db:
            self.thread(db, thread_id)
            db.execute("UPDATE support_threads SET status=?, updated=? WHERE id=?", (body.status, self.now(), thread_id))
            self.audit(db, "SUPPORT_STATUS", self.now())
        return {"ok": True}

    def incidents(self):
        return self.list_incidents(public=True)

    def staff_incidents(self):
        return self.list_incidents(public=False)

    def list_incidents(self, public):
        with self.transaction(write=False) as db:
            rows = db.execute("""SELECT * FROM service_incidents
                WHERE (?=0 OR status='investigating' OR updated>?)
                ORDER BY (status='investigating') DESC, updated DESC LIMIT 100""",
                (public, self.now()-7*86400000))
            return [dict(row, server_ids=[
                endpoint_key(value) if public else value for value in json.loads(row["server_ids"])
            ]) for row in rows]

    def save_incident(self, request: Request, incident_id: str, body: Incident):
        self.verify_origin(request)
        if not re.fullmatch(r"[a-f0-9]{32}", incident_id):
            raise HTTPException(422, "INVALID_INCIDENT_ID")
        if body.server_ids and body.scope != "connection":
            raise HTTPException(422, "SERVER_SCOPE_REQUIRES_CONNECTION")
        current = self.now()
        revision = body.expected_revision + 1
        with self.transaction() as db:
            prior = db.execute("SELECT revision, server_ids FROM service_incidents WHERE id=?", (incident_id,)).fetchone()
            if (prior["revision"] if prior else 0) != body.expected_revision:
                raise HTTPException(409, "INCIDENT_CHANGED")
            db.execute("""INSERT INTO service_incidents VALUES (?,?,?,?,?,?,?,?,?)
                ON CONFLICT(id) DO UPDATE SET title=excluded.title, body=excluded.body,
                    guidance=excluded.guidance, scope=excluded.scope, server_ids=excluded.server_ids,
                    status=excluded.status, revision=excluded.revision, updated=excluded.updated""",
                (incident_id, body.title, body.body, body.guidance, body.scope, json.dumps(body.server_ids),
                 body.status, revision, current))
            previous_endpoints = json.loads(prior["server_ids"]) if prior else body.server_ids
            audience = set(body.server_ids) | set(previous_endpoints) if body.server_ids and previous_endpoints else set()
            target_keys = json.dumps(sorted(endpoint_key(endpoint) for endpoint in audience))
            db.execute("""INSERT OR IGNORE INTO service_push(token,kind,resource,revision,next_run)
                SELECT token,'incident',?,?,? FROM support_devices WHERE updated>?
                AND (?='[]' OR server_id IN (SELECT value FROM json_each(?)))""",
                (incident_id, revision, current, current-30*86400000, target_keys, target_keys))
            self.audit(db, "SERVICE_INCIDENT", current)
        return {"id": incident_id, "revision": revision}

    def register(self, request: Request, body: Device):
        if not self.customers.native(request):
            self.customers.protect(request)
        account = self.customers.current(request)
        installation_id = str(body.installation_id)
        with self.transaction() as db:
            db.execute("DELETE FROM support_devices WHERE token=? AND installation_id<>?", (body.token, installation_id))
            db.execute("""INSERT INTO support_devices VALUES (?,?,?,?,?,?) ON CONFLICT(installation_id) DO UPDATE SET
                 token=excluded.token, customer=excluded.customer, session_digest=excluded.session_digest,
                 server_id=excluded.server_id, updated=excluded.updated""",
                 (installation_id, body.token, account["id"] if account else None,
                  self.customers.session_digest(request) if account else None, body.server_id, self.now()))
        return {"enabled": bool(os.getenv("FCM_SERVICE_ACCOUNT_FILE"))}

    def queue(self, kind: str = "", limit: int = Query(50, ge=1, le=100), offset: int = Query(0, ge=0)):
        if kind not in ("", "receipt", "deposit", "activation", "support"):
            raise HTTPException(422)
        with self.transaction(write=False) as db:
            query = """
                 SELECT 'receipt' AS kind, id, created, status, amount, NULL AS subject FROM orders WHERE status='PENDING_REVIEW'
                 UNION ALL SELECT 'deposit', digest, received, 'UNMATCHED', amount, NULL FROM deposits WHERE order_id IS NULL
                 UNION ALL SELECT 'activation', o.id, o.created, 'PENDING', o.amount, NULL
                   FROM orders o JOIN provisioning_jobs j ON j.order_id=o.id
                   WHERE o.status='APPROVED' AND j.ready=0
                 UNION ALL SELECT 'support', id, updated, status, NULL, subject FROM support_threads WHERE status='waiting'
            """
            counts = {row["kind"]: row["count"] for row in db.execute("SELECT kind, COUNT(*) AS count FROM (" + query + ") GROUP BY kind")}
            items = db.execute("SELECT * FROM (" + query + ") WHERE (?='' OR kind=?) ORDER BY created,kind,id LIMIT ? OFFSET ?",
                               (kind, kind, limit, offset))
            return {"counts": counts, "items": [dict(row) for row in items]}

    def deliver_push(self):
        path = os.getenv("FCM_SERVICE_ACCOUNT_FILE")
        if not path:
            return
        current = self.now()
        with self.transaction() as db:
            item = db.execute("SELECT * FROM service_push WHERE next_run<=? AND attempts<6 ORDER BY id LIMIT 1",
                              (current,)).fetchone()
            if not item:
                return
            db.execute("UPDATE service_push SET attempts=attempts+1,next_run=? WHERE id=?",
                       (current + 120000 * 2**item["attempts"], item["id"]))
            device = db.execute("""SELECT d.token, s.digest, s.customer_id FROM support_devices d
                LEFT JOIN customer_sessions s ON s.digest=d.session_digest AND s.expires>?
                WHERE d.token=?""", (current, item["token"])).fetchone()
            owner = db.execute("SELECT customer FROM support_threads WHERE id=?", (item["resource"],)).fetchone() if item["kind"] == "support" else None
            if not device or (item["kind"] == "support" and
                              (not owner or device["customer_id"] != owner["customer"])):
                db.execute("DELETE FROM service_push WHERE id=?", (item["id"],))
                return
        from google.auth.transport.requests import Request as GoogleRequest
        from google.oauth2.service_account import Credentials
        if self.credentials is None:
            self.credentials = Credentials.from_service_account_file(path, scopes=["https://www.googleapis.com/auth/firebase.messaging"])
        if not self.credentials.valid:
            self.credentials.refresh(GoogleRequest())
        payload = json.dumps({"message": {"token": item["token"], "data": {
            "kind": item["kind"], "id": item["resource"], "revision": str(item["revision"]),
        }}}).encode()
        request = HttpRequest(f"https://fcm.googleapis.com/v1/projects/{self.credentials.project_id}/messages:send",
                              data=payload, headers={"Content-Type": "application/json",
                              "Authorization": "Bearer " + self.credentials.token})
        try:
            with urlopen(request, timeout=8):
                pass
        except HTTPError as error:
            if error.code != 404:
                raise
            with self.transaction() as db:
                db.execute("DELETE FROM support_devices WHERE token=?", (item["token"],))
        with self.transaction() as db:
            db.execute("DELETE FROM service_push WHERE id=?", (item["id"],))


def mount(app, admin, operations):
    customer = "/v1/customer/support"
    for router, prefix, routes in (
        (app, customer, (("", "GET", operations.customer_list), ("", "POST", operations.create),
            ("/{thread_id}", "GET", operations.detail), ("/{thread_id}/messages", "POST", operations.customer_reply),
            ("/{thread_id}/read", "POST", operations.read))),
        (app, "/v1/service", (("/incidents", "GET", operations.incidents), ("/devices", "PUT", operations.register))),
        (admin, "/api/operations", (("", "GET", operations.queue), ("/support", "GET", operations.staff_list),
            ("/support/{thread_id}", "GET", operations.staff_detail), ("/support/{thread_id}/messages", "POST", operations.staff_reply),
            ("/support/{thread_id}/status", "PUT", operations.staff_status), ("/incidents", "GET", operations.staff_incidents),
            ("/incidents/{incident_id}", "PUT", operations.save_incident))),
    ):
        for suffix, method, endpoint in routes:
            router.add_api_route(prefix + suffix, endpoint, methods=[method])
