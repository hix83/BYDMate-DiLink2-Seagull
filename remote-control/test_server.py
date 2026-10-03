import copy
import os
import tempfile
import unittest
import uuid

os.environ["DATABASE"] = os.path.join(tempfile.mkdtemp(prefix="bydmate-tests-"), "test.db")
import server as s


class GatewayTest(unittest.TestCase):
    def setUp(self):
        self.client = s.app.test_client()
        self.headers = {"Origin": s.PUBLIC_URL}
        self.email = uuid.uuid4().hex + "@example.com"
        self.password = "correct-test-password-123"
        token = uuid.uuid4().hex
        with s.db() as c:
            c.execute("INSERT INTO invites VALUES(?,?)", (s.digest(token), s.now() + 500))
        self.assertEqual(self.call("/api/register", dict(email=self.email, password=self.password, invite=token)).status_code, 200)
        self.assertEqual(self.call("/api/login", dict(email=self.email, password=self.password)).status_code, 200)
        self.agent = s.app.test_client()
        data = self.agent.post("/api/device/register", json={"name": "Test car"}).json
        self.ident, self.token = data["id"], data["token"]
        self.device_headers = {"Authorization": "Bearer " + self.token}

    def call(self, path, data=None, method="POST", client=None):
        return (client or self.client).open(path, method=method, json=data, headers=self.headers, base_url=s.PUBLIC_URL)

    def agent_call(self, path, data=None):
        return self.agent.post(path, json=data or {}, headers=self.device_headers)

    def pair(self):
        url = self.agent_call("/api/device/pair").json["url"]
        return self.call("/api/claim", {"token": url.split("#pair=")[1]})

    def config(self, config):
        return self.call(f"/api/cars/{self.ident}/config", {"config": config, "revision": 0}, "PUT")

    def poll(self, session="session1", outside=0):
        return self.agent_call("/api/device/poll", {"session": session, "telemetry": {"adb_ready": True, "outside": outside}})

    def test_owner_isolation_and_pair_single_use(self):
        url = self.agent_call("/api/device/pair").json["url"]
        payload = {"token": url.split("#pair=")[1]}
        self.assertEqual(self.call("/api/claim", payload).status_code, 200)
        self.assertEqual(self.call("/api/claim", payload).status_code, 400)
        foreign = s.app.test_client()
        foreign_id, foreign_token = uuid.uuid4().hex, uuid.uuid4().hex
        with s.db() as c:
            c.execute("INSERT INTO users VALUES(?,?,?)", (foreign_id, "other" + self.email, "unused"))
            c.execute("INSERT INTO sessions VALUES(?,?,?)", (s.digest(foreign_token), foreign_id, s.now() + 500))
        foreign.set_cookie("session", foreign_token, domain="byd.slk-soft.ru")
        self.assertEqual(self.call(f"/api/cars/{self.ident}/apply", {}, client=foreign).status_code, 404)
        self.assertEqual(foreign.get("/api/cars", base_url=s.PUBLIC_URL).json["cars"], [])

    def test_season_startup_dedup_and_result_retry(self):
        self.pair()
        config = s.default_config()
        config["startup"] = True
        config["winter"]["enabled"] = True
        self.assertEqual(self.config(config).status_code, 200)
        self.assertIsNone(self.poll(outside=None).json["command"])
        cmd = self.poll().json["command"]
        self.assertEqual(cmd["climate"]["temperature"], 25)
        self.assertEqual(self.poll().json["command"]["id"], cmd["id"])
        result = {"id": cmd["id"], "status": "partial", "details": "Seat not supported"}
        self.assertEqual(self.agent_call("/api/device/result", result).status_code, 200)
        self.assertEqual(self.agent_call("/api/device/result", result).status_code, 200)
        self.assertIsNone(self.poll().json["command"])
        self.assertIsNotNone(self.poll(session="session2").json["command"])

    def test_expiration_and_unlink(self):
        self.pair()
        cmd = self.call(f"/api/cars/{self.ident}/apply", {}).json["id"]
        with s.db() as c:
            c.execute("UPDATE commands SET expires=? WHERE id=?", (s.now() - 1, cmd))
        self.assertIsNone(self.poll().json["command"])
        self.call(f"/api/cars/{self.ident}/apply", {})
        self.call(f"/api/cars/{self.ident}/unlink", {})
        self.assertFalse(self.poll().json["linked"])
        self.assertIsNone(self.poll().json["command"])

    def test_validation_and_csrf(self):
        self.pair()
        config = s.default_config()
        config["climate"]["fan"] = 99
        self.assertEqual(self.config(config).status_code, 400)
        self.assertEqual(self.client.post(f"/api/cars/{self.ident}/apply", json={}).status_code, 403)
        self.assertEqual(self.agent_call("/api/device/poll", {"telemetry": {"latitude": 999}}).status_code, 400)

    def test_no_fake_map_location(self):
        self.pair()
        self.agent_call("/api/device/poll", {"telemetry": {"latitude": 0, "longitude": 0}})
        car = self.client.get("/api/cars", base_url=s.PUBLIC_URL).json["cars"][0]
        self.assertNotIn("latitude", car["telemetry"])
        self.agent_call("/api/device/poll", {"telemetry": {"latitude": 55, "longitude": 83, "location_time": s.now() - 1000}})
        car = self.client.get("/api/cars", base_url=s.PUBLIC_URL).json["cars"][0]
        self.assertEqual(car["telemetry"]["latitude"], 55)

    def test_unauthenticated_device_and_foreign_result(self):
        self.pair()
        self.assertEqual(self.agent.post("/api/device/poll", json={}).status_code, 401)
        self.assertEqual(self.agent_call("/api/device/result", {"id": "foreign", "status": "success", "details": "ok"}).status_code, 404)

    def test_invites_and_expired_pair(self):
        self.assertEqual(self.call("/api/register", {"email": "bad@example.com", "password": self.password, "invite": "bad"}).status_code, 400)
        url = self.agent_call("/api/device/pair").json["url"]
        with s.db() as c:
            c.execute("UPDATE devices SET pairing_expires=0 WHERE id=?", (self.ident,))
        self.assertEqual(self.call("/api/claim", {"token": url.split("#pair=")[1]}).status_code, 400)


if __name__ == "__main__":
    unittest.main()
