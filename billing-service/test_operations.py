"""SQLite migration and HTTP regressions for Donino's operations features."""
import os
from pathlib import Path
from concurrent.futures import ThreadPoolExecutor
from tempfile import TemporaryDirectory
import unittest
from unittest.mock import patch
from uuid import uuid4

from fastapi.testclient import TestClient
import main
from operations import endpoint_key


class OperationsTest(unittest.TestCase):
    def setUp(self):
        self.folder = TemporaryDirectory()
        self.addCleanup(self.folder.cleanup)
        self.origin = "https://billing.example.com"
        env = patch.dict(os.environ, {
            "BILLING_DB": self.folder.name + "/orders.sqlite",
            "BILLING_PLANS": str(Path(__file__).resolve().parent.parent /
                                 "android-client/app/src/main/assets/plans_config.json"),
            "BILLING_KEY": "b" * 32,
            "ADMIN_PASSWORD": "a" * 32,
            "SMS_WEBHOOK_SECRET": "s" * 32,
            "PANEL_URL": "https://panel.example.com",
            "BILLING_PUBLIC_URL": self.origin,
        })
        env.start()
        self.addCleanup(env.stop)
        main.initialize()
        self.client = TestClient(main.app, base_url=self.origin)
        self.addCleanup(self.client.close)
        admin_token = "z" * 43
        self.admin = {"Cookie": f"{main.ADMIN_COOKIE}={admin_token}", "Origin": self.origin}
        self.customer = {"Authorization": "Bearer " + "c" * 43, "X-PasarGuard-Client": "native"}
        self.other = {"Authorization": "Bearer " + "d" * 43, "X-PasarGuard-Client": "native"}
        with main.transaction() as db:
            db.execute("INSERT INTO admin_sessions VALUES (?,?)",
                       (main.admin_session_digest(admin_token), main.now() + 60_000))
            for account, letter in (("customer-one", "c"), ("customer-two", "d")):
                db.execute("INSERT INTO customer_accounts VALUES (?,?,?,?)",
                           (account, f"{account}@gmail.com", "synthetic", main.now()))
                db.execute("INSERT INTO customer_sessions VALUES (?,?,?)",
                           (main.secret_digest("customer-session:" + letter * 43), account, main.now() + 60_000))
            db.execute("INSERT INTO orders VALUES (?,?,?,?,?,?,?,?)",
                       ("a"*32, main.account_owner("customer-one"), "test-key", 50_000, "PENDING_REVIEW",
                        main.now(), main.now() + 60_000, "{}"))
            db.execute("INSERT INTO deposits VALUES (?,?,?,?)", ("demo-deposit", 50001, main.now(), None))
        main.initialize()  # An update migrates an existing database in place.

    def create(self, key=None, **fields):
        payload = dict(request_id=key or str(uuid4()), subject="Connection help", category="connection", body="Help me connect.")
        return self.client.post("/v1/customer/support", json={**payload, **fields}, headers=self.customer)

    def test_thread_retries_ownership_reply_and_reads(self):
        key = str(uuid4())
        with ThreadPoolExecutor(2) as threads:
            result = list(threads.map(lambda _: self.create(key, order_id="a"*32), range(2)))
        self.assertEqual([response.status_code for response in result], [200, 200])
        thread = result[0].json()["id"]
        self.assertEqual(result[1].json()["id"], thread)
        url = "/v1/customer/support/" + thread
        self.assertEqual(self.client.get(url, headers=self.other).status_code, 404)
        self.assertEqual(self.client.get(url).status_code, 401)
        self.assertEqual(self.client.post("/v1/customer/support", headers=self.other, json={
            "request_id": str(uuid4()), "subject": "other", "body": "message", "category": "payment",
            "order_id": "a"*32,
        }).status_code, 404)
        reply = {"request_id": str(uuid4()), "body": "Try connecting again."}
        staff_url = f"/admin/api/operations/support/{thread}/messages"
        self.assertEqual(self.client.post(staff_url, json=reply, headers=self.admin).status_code, 200)
        self.assertEqual(self.client.post(staff_url, json=reply, headers=self.admin).status_code, 200)
        messages = self.client.get(url, headers=self.customer).json()["messages"]
        self.assertEqual(len(messages), 2)
        summaries = self.client.get("/v1/customer/support", headers=self.customer).json()
        self.assertEqual(summaries[0]["unread"], 1)
        self.client.post(url + "/read", json={"sequence": messages[-1]["sequence"]}, headers=self.customer)
        self.assertEqual(self.client.get("/v1/customer/support", headers=self.customer).json()[0]["unread"], 0)

    def test_queue_and_scoped_incident_revision(self):
        thread = self.create().json()["id"]
        self.assertEqual(self.client.get("/admin/api/operations").status_code, 401)
        queue = self.client.get("/admin/api/operations", headers=self.admin).json()
        self.assertEqual(queue["counts"], {"deposit": 1, "receipt": 1, "support": 1})
        self.assertEqual([row["id"] for row in queue["items"] if row["kind"] == "support"], [thread])
        path = "/admin/api/operations/incidents/" + "b"*32
        notice = {"title": "Example", "body": "Investigating", "scope": "connection",
                  "server_ids": ["example-server:443"], "status": "investigating"}
        self.assertEqual(self.client.put(path, headers=self.admin, json=notice).status_code, 200)
        self.assertEqual(self.client.put(path, headers=self.admin, json=notice).status_code, 409)
        notice.update(status="resolved", expected_revision=1)
        self.assertEqual(self.client.put(path, headers=self.admin, json=notice).status_code, 200)
        item = self.client.get("/v1/service/incidents").json()[0]
        self.assertEqual((item["status"], item["revision"], item["server_ids"]), ("resolved", 2, [endpoint_key("example-server:443")]))


if __name__ == "__main__":
    unittest.main()
