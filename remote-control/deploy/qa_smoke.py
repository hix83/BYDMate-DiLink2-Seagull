"""Disposable HTTP integration fixture. Run via docker exec -i ... python < this_file."""
import http.cookiejar
import json
import secrets
import urllib.request
import server as s

EMAIL = "qa-remote-mvp@example.invalid"
PASSWORD = "bydmate-qa-disposable-password-2026"


def request(path, data=None, method="POST", token=None):
    headers = {"Content-Type": "application/json", "Origin": s.PUBLIC_URL}
    if token:
        headers["Authorization"] = "Bearer " + token
    req = urllib.request.Request(s.PUBLIC_URL + path, json.dumps(data).encode() if data is not None else None, headers, method=method)
    with client.open(req, timeout=20) as response:
        return json.load(response)


client = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
with s.db() as c:
    if c.execute("SELECT 1 FROM users WHERE email=?", (EMAIL,)).fetchone():
        raise SystemExit("QA fixture already exists")
    invite = secrets.token_urlsafe(24)
    c.execute("INSERT INTO invites VALUES(?,?)", (s.digest(invite), s.now() + 300))
request("/api/register", {"email": EMAIL, "password": PASSWORD, "invite": invite})
request("/api/login", {"email": EMAIL, "password": PASSWORD})
for name in ("Тестовый Seagull · не реальная машина", "Вторая тестовая машина"):
    car = request("/api/device/register", {"name": name})
    token = car["token"]
    pair = request("/api/device/pair", {}, token=token)
    request("/api/claim", {"token": pair["url"].split("#pair=")[1]})
    config = s.default_config()
    config["startup"] = True
    config["winter"]["enabled"] = True
    request(f"/api/cars/{car['id']}/config", {"revision": 0, "config": config}, method="PUT")
    payload = {"session": "qa-startup", "telemetry": {"adb_ready": True, "outside": -12, "soc": 83,
               "latitude": 55.0302, "longitude": 82.9204, "location_time": s.now()}}
    cmd = request("/api/device/poll", payload, token=token)["command"]
    assert cmd["climate"]["temperature"] == 25
    assert request("/api/device/poll", payload, token=token)["command"]["id"] == cmd["id"]
    request("/api/device/result", {"id": cmd["id"], "status": "success", "details": "Тест API: зимнее правило. Это симуляция; команды на автомобиль не отправлялись."}, token=token)
    assert request("/api/device/poll", payload, token=token)["command"] is None
assert len(request("/api/cars", method="GET")["cars"]) == 2
print("HTTPS smoke passed: register, login, 2 cars, pair, settings, startup, replay, result")
