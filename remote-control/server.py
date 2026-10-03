"""Narrow climate gateway. No shell, raw ADB, or vehicle-start API."""
import argparse
import hashlib
import hmac
import json
import math
import os
import secrets
import sqlite3
import time
from contextlib import contextmanager
from flask import Flask, request, jsonify, abort, g

app = Flask(__name__, static_folder="static", static_url_path="/static")
app.config.update(MAX_CONTENT_LENGTH=32768)
DATABASE = os.environ.get("DATABASE", "bydmate.db")
PUBLIC_URL = os.environ.get("PUBLIC_URL", "https://byd.slk-soft.ru").rstrip("/")


def digest(value):
    return hashlib.sha256(value.encode()).hexdigest()


def now():
    return int(time.time())


@contextmanager
def db():
    con = sqlite3.connect(DATABASE, timeout=15)
    con.row_factory = sqlite3.Row
    con.execute("PRAGMA foreign_keys=ON")
    try:
        con.execute("BEGIN IMMEDIATE")
        yield con
        con.commit()
    except BaseException:
        con.rollback()
        raise
    finally:
        con.close()


def init_db():
    with db() as c:
        c.executescript("""
        PRAGMA journal_mode=WAL;
        CREATE TABLE IF NOT EXISTS users(id TEXT PRIMARY KEY, email TEXT UNIQUE, password TEXT);
        CREATE TABLE IF NOT EXISTS invites(token TEXT PRIMARY KEY, expires INTEGER);
        CREATE TABLE IF NOT EXISTS sessions(token TEXT PRIMARY KEY, user_id TEXT REFERENCES users, expires INTEGER);
        CREATE TABLE IF NOT EXISTS devices(id TEXT PRIMARY KEY, token TEXT UNIQUE, owner TEXT REFERENCES users,
          name TEXT, pairing TEXT UNIQUE, pairing_expires INTEGER, seen INTEGER DEFAULT 0,
          telemetry TEXT DEFAULT '{}', config TEXT DEFAULT '{}', revision INTEGER DEFAULT 0);
        CREATE TABLE IF NOT EXISTS commands(id TEXT PRIMARY KEY, device TEXT REFERENCES devices ON DELETE CASCADE,
          session TEXT, payload TEXT, created INTEGER, expires INTEGER, status TEXT DEFAULT 'pending', result TEXT);
        CREATE UNIQUE INDEX IF NOT EXISTS startup_once ON commands(device,session) WHERE session IS NOT NULL;
        CREATE TABLE IF NOT EXISTS rate_limits(key TEXT PRIMARY KEY, count INTEGER, reset INTEGER);
        """)


def limit(c, key, maximum, seconds=3600):
    row = c.execute("SELECT * FROM rate_limits WHERE key=?", (key,)).fetchone()
    count = row["count"] + 1 if row and row["reset"] > now() else 1
    reset = row["reset"] if row and row["reset"] > now() else now() + seconds
    c.execute("INSERT OR REPLACE INTO rate_limits VALUES(?,?,?)", (key, count, reset))
    # Return error outside transaction so a rejected attempt cannot roll back the counter.
    return count <= maximum


def throttle(prefix, maximum):
    # Proxy supplies X-Real-IP; app only listens on the loopback-published Docker port.
    address = request.headers.get("X-Real-IP", request.remote_addr)
    with db() as c:
        allowed = limit(c, prefix + ":" + address, maximum)
    if not allowed:
        abort(429, "Слишком много попыток. Попробуйте позже.")


def password_hash(password, salt=None):
    salt = salt or secrets.token_hex(16)
    return salt + ":" + hashlib.scrypt(password.encode(), salt=salt.encode(), n=16384, r=8, p=1).hex()


def body():
    data = request.get_json(silent=True)
    if not isinstance(data, dict):
        abort(400, "Ожидается JSON-объект")
    return data


def user(c):
    row = c.execute("SELECT user_id FROM sessions WHERE token=? AND expires>?",
                    (digest(request.cookies.get("session", "")), now())).fetchone()
    if not row:
        abort(401, "Войдите в кабинет")
    return row["user_id"]


def device(c):
    auth = request.headers.get("Authorization", "")
    row = c.execute("SELECT * FROM devices WHERE token=?", (digest(auth.removeprefix("Bearer ")),)).fetchone()
    if not auth.startswith("Bearer ") or not row:
        abort(401)
    return row


def owned(c, ident):
    row = c.execute("SELECT * FROM devices WHERE id=? AND owner=?", (ident, user(c))).fetchone()
    if not row:
        abort(404)
    return row


@app.before_request
def csrf():
    if request.method not in ("GET", "HEAD", "OPTIONS") and not request.path.startswith("/api/device/"):
        if request.headers.get("Origin") != PUBLIC_URL:
            abort(403, "Неверный источник запроса")


@app.after_request
def headers(response):
    response.headers["Cache-Control"] = "no-store"
    response.headers["X-Content-Type-Options"] = "nosniff"
    response.headers["Referrer-Policy"] = "no-referrer"
    response.headers["X-Frame-Options"] = "DENY"
    response.headers["Content-Security-Policy"] = "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; frame-src https://yandex.ru; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'"
    return response


@app.errorhandler(Exception)
def error(e):
    from werkzeug.exceptions import HTTPException
    if isinstance(e, HTTPException):
        return jsonify(error=e.description), e.code
    app.logger.exception("Request failed")
    return jsonify(error="Ошибка сервера"), 500


@app.get("/")
def index():
    return app.send_static_file("index.html")


@app.get("/health")
def health():
    with db() as c:
        c.execute("SELECT 1")
    return jsonify(ok=True)


@app.post("/api/register")
def register():
    throttle("register", 20)
    data = body()
    email = str(data.get("email", "")).strip().lower()
    password = data.get("password", "")
    if not isinstance(password, str) or not 12 <= len(password) <= 128 or not 3 <= len(email) <= 254 or "@" not in email:
        abort(400, "Нужны почта и пароль длиной от 12 до 128 символов")
    encoded = password_hash(password)
    with db() as c:
        invite = c.execute("SELECT * FROM invites WHERE token=? AND expires>?",
                           (digest(str(data.get("invite", ""))), now())).fetchone()
        if not invite:
            abort(400, "Приглашение недействительно или истекло")
        if c.execute("SELECT 1 FROM users WHERE email=?", (email,)).fetchone():
            abort(400, "Не удалось зарегистрировать аккаунт")
        c.execute("INSERT INTO users VALUES(?,?,?)", (secrets.token_hex(16), email, encoded))
        c.execute("DELETE FROM invites WHERE token=?", (invite["token"],))
    return jsonify(ok=True)


@app.post("/api/login")
def login():
    throttle("login", 30)
    data = body()
    password = data.get("password", "")
    if not isinstance(password, str) or len(password) > 128:
        abort(400)
    with db() as c:
        row = c.execute("SELECT * FROM users WHERE email=?", (str(data.get("email", "")).strip().lower(),)).fetchone()
        stored = row["password"] if row else password_hash("dummy-password", "0" * 32)
        matches = hmac.compare_digest(password_hash(password, stored.split(":")[0]), stored)
        if not row or not matches:
            abort(401, "Неверная почта или пароль")
        token = secrets.token_urlsafe(32)
        c.execute("INSERT INTO sessions VALUES(?,?,?)", (digest(token), row["id"], now() + 86400 * 14))
        c.execute("DELETE FROM sessions WHERE expires<?", (now(),))
    response = jsonify(ok=True)
    response.set_cookie("session", token, secure=True, httponly=True, samesite="Strict", max_age=86400 * 14)
    return response


@app.post("/api/logout")
def logout():
    with db() as c:
        c.execute("DELETE FROM sessions WHERE token=?", (digest(request.cookies.get("session", "")),))
    response = jsonify(ok=True)
    response.delete_cookie("session", secure=True, httponly=True, samesite="Strict")
    return response


DEFAULT_CLIMATE = dict(enabled=True, temperature=22, fan=3, driver_heat=0, passenger_heat=0)


def climate(value):
    if not isinstance(value, dict) or set(value) != set(DEFAULT_CLIMATE):
        abort(400, "Некорректные поля климата")
    if type(value["enabled"]) is not bool:
        abort(400)
    for key, low, high in (("temperature", 16, 33), ("fan", 1, 7), ("driver_heat", 0, 3), ("passenger_heat", 0, 3)):
        if type(value[key]) is not int or not low <= value[key] <= high:
            abort(400, "Недопустимое значение: " + key)
    return value


def configuration(value):
    if not isinstance(value, dict) or set(value) != {"startup", "climate", "winter", "summer"} or type(value["startup"]) is not bool:
        abort(400, "Некорректная конфигурация")
    climate(value["climate"])
    for season in ("winter", "summer"):
        rule = value[season]
        if not isinstance(rule, dict) or set(rule) != {"enabled", "threshold", "climate"} or type(rule["enabled"]) is not bool:
            abort(400)
        if type(rule["threshold"]) not in (int, float) or not math.isfinite(rule["threshold"]) or not -50 <= rule["threshold"] <= 60:
            abort(400)
        climate(rule["climate"])
    if value["winter"]["threshold"] >= value["summer"]["threshold"]:
        abort(400, "Зимний порог должен быть ниже летнего")
    return value


def default_config():
    return dict(startup=False, climate=dict(DEFAULT_CLIMATE),
                winter=dict(enabled=False, threshold=5, climate={**DEFAULT_CLIMATE, "temperature": 25, "driver_heat": 2}),
                summer=dict(enabled=False, threshold=25, climate={**DEFAULT_CLIMATE, "temperature": 20}))


def command(c, row, payload, session=None):
    ident = secrets.token_hex(16)
    c.execute("INSERT INTO commands(id,device,session,payload,created,expires) VALUES(?,?,?,?,?,?)",
              (ident, row["id"], session, json.dumps(payload), now(), now() + 900))
    return ident


@app.post("/api/device/register")
def register_device():
    throttle("device-register", 60)
    data = body()
    token = secrets.token_urlsafe(32)
    ident = secrets.token_hex(16)
    with db() as c:
        c.execute("INSERT INTO devices(id,token,name,config) VALUES(?,?,?,?)",
                  (ident, digest(token), str(data.get("name", "BYD"))[:80], json.dumps(default_config())))
    return jsonify(id=ident, token=token)


@app.post("/api/device/pair")
def pair_device():
    with db() as c:
        row = device(c)
        if row["owner"]:
            abort(409, "Автомобиль уже привязан. Сначала отключите его в кабинете.")
        token = secrets.token_urlsafe(32)
        c.execute("UPDATE devices SET pairing=?,pairing_expires=? WHERE id=?", (digest(token), now() + 600, row["id"]))
    # Fragment stays out of proxy access logs and HTTP referrers.
    return jsonify(url=PUBLIC_URL + "/#pair=" + token, expires=now() + 600)


@app.post("/api/claim")
def claim():
    with db() as c:
        owner = user(c)
        row = c.execute("SELECT * FROM devices WHERE pairing=? AND pairing_expires>? AND owner IS NULL",
                        (digest(str(body().get("token", ""))), now())).fetchone()
        if not row:
            abort(400, "QR-код истёк или автомобиль уже привязан")
        c.execute("UPDATE devices SET owner=?,pairing=NULL,pairing_expires=NULL WHERE id=?", (owner, row["id"]))
    return jsonify(ok=True)


@app.get("/api/cars")
def cars():
    with db() as c:
        rows = c.execute("SELECT * FROM devices WHERE owner=?", (user(c),)).fetchall()
        result = []
        for row in rows:
            last = c.execute("SELECT id,created,expires,status,result FROM commands WHERE device=? ORDER BY created DESC,rowid DESC LIMIT 1", (row["id"],)).fetchone()
            result.append(dict(id=row["id"], name=row["name"], seen=row["seen"], telemetry=json.loads(row["telemetry"]),
                               config=json.loads(row["config"]), revision=row["revision"], last_command=dict(last) if last else None))
    return jsonify(cars=result)


@app.put("/api/cars/<ident>/config")
def set_config(ident):
    data = body()
    config = configuration(data.get("config"))
    name = str(data.get("name", "")).strip()
    if "name" in data and not 1 <= len(name) <= 80:
        abort(400, "Название должно содержать от 1 до 80 символов")
    with db() as c:
        row = owned(c, ident)
        if data.get("revision") != row["revision"]:
            abort(409, "Настройки изменены в другом окне. Обновите страницу.")
        c.execute("UPDATE devices SET config=?,revision=revision+1 WHERE id=?", (json.dumps(config), ident))
        if "name" in data:
            c.execute("UPDATE devices SET name=? WHERE id=?", (name, ident))
    return jsonify(ok=True)


@app.post("/api/cars/<ident>/apply")
def apply_now(ident):
    with db() as c:
        row = owned(c, ident)
        # Never silently stack a second set of climate commands.
        c.execute("UPDATE commands SET status='superseded' WHERE device=? AND status='pending'", (ident,))
        ident = command(c, row, {"climate": json.loads(row["config"])["climate"], "reason": "Кнопка в кабинете"})
    return jsonify(id=ident)


@app.post("/api/cars/<ident>/unlink")
def unlink(ident):
    with db() as c:
        owned(c, ident)
        c.execute("UPDATE devices SET owner=NULL,config=?,revision=revision+1,pairing=NULL WHERE id=?", (json.dumps(default_config()), ident))
        c.execute("UPDATE commands SET status='cancelled' WHERE device=? AND status='pending'", (ident,))
    return jsonify(ok=True)


def telemetry(value):
    if not isinstance(value, dict):
        abort(400)
    result = {}
    for key, low, high in (("latitude", -90, 90), ("longitude", -180, 180), ("outside", -80, 80), ("soc", 0, 100), ("speed", 0, 400)):
        number = value.get(key)
        if number is not None:
            if type(number) not in (int, float) or not math.isfinite(number) or not low <= number <= high:
                abort(400)
            result[key] = number
    stamp = value.get("location_time")
    if type(stamp) is int and 0 < stamp <= now() + 60 and "latitude" in result and "longitude" in result:
        result["location_time"] = stamp
    else:
        result.pop("latitude", None)
        result.pop("longitude", None)
    result["adb_ready"] = value.get("adb_ready") is True
    return result


@app.post("/api/device/poll")
def poll():
    data = body()
    readings = telemetry(data.get("telemetry", {}))
    session = str(data.get("session", ""))[:120]
    with db() as c:
        row = device(c)
        previous = json.loads(row["telemetry"])
        # Missing GPS after a reboot must not erase the last known position; keep its
        # original fix timestamp, never present it as a new location.
        if previous.get("location_time", 0) > readings.get("location_time", 0):
            for key in ("latitude", "longitude", "location_time"):
                if key in previous:
                    readings[key] = previous[key]
        c.execute("UPDATE devices SET seen=?,telemetry=? WHERE id=?", (now(), json.dumps(readings), row["id"]))
        c.execute("UPDATE commands SET status='expired' WHERE device=? AND status='pending' AND expires<=?", (row["id"], now()))
        config = json.loads(row["config"])
        if row["owner"] and readings["adb_ready"] and session and config["startup"]:
            exists = c.execute("SELECT 1 FROM commands WHERE device=? AND session=?", (row["id"], session)).fetchone()
            if not exists:
                target, reason = config["climate"], "При запуске"
                outside = readings.get("outside")
                for season, matches in (("winter", outside is not None and outside <= config["winter"]["threshold"]),
                                        ("summer", outside is not None and outside >= config["summer"]["threshold"])):
                    if matches and config[season]["enabled"]:
                        target, reason = config[season]["climate"], "Зимнее правило" if season == "winter" else "Летнее правило"
                # If a seasonal rule is active, wait for a real outside-temperature reading.
                if outside is not None or not (config["winter"]["enabled"] or config["summer"]["enabled"]):
                    command(c, row, dict(climate=target, reason=reason), session)
        pending = c.execute("SELECT * FROM commands WHERE device=? AND status='pending' AND expires>? ORDER BY created,rowid LIMIT 1", (row["id"], now())).fetchone() if row["owner"] and readings["adb_ready"] else None
        result = dict(id=pending["id"], expires=pending["expires"], **json.loads(pending["payload"])) if pending else None
    return jsonify(linked=bool(row["owner"]), command=result)


@app.post("/api/device/result")
def report_result():
    data = body()
    if data.get("status") not in ("success", "partial", "failed", "uncertain") or not isinstance(data.get("details"), str) or len(data["details"]) > 4000:
        abort(400)
    with db() as c:
        row = device(c)
        cmd = c.execute("SELECT * FROM commands WHERE id=? AND device=?", (str(data.get("id", "")), row["id"])).fetchone()
        if not cmd:
            abort(404)
        if cmd["status"] in ("pending", "expired", "superseded", "cancelled"):
            c.execute("UPDATE commands SET status=?,result=? WHERE id=?", (data["status"], data["details"], cmd["id"]))
    return jsonify(ok=True)


init_db()

if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("action", choices=["invite"])
    args = parser.parse_args()
    token = secrets.token_urlsafe(24)
    with db() as c:
        c.execute("INSERT INTO invites VALUES(?,?)", (digest(token), now() + 86400 * 7))
    print(PUBLIC_URL + "/#invite=" + token)
