"""Remove only the disposable fixture created by qa_smoke.py, with recovery snapshot."""
import os
import sqlite3
import server as s

email = "qa-remote-mvp@example.invalid"
with s.db() as c:
    row = c.execute("SELECT * FROM users WHERE email=?", (email,)).fetchone()
    if not row:
        raise SystemExit("No QA fixture")
    devices = c.execute("SELECT id,name FROM devices WHERE owner=?", (row["id"],)).fetchall()
    if len(devices) != 2 or any("тестов" not in car["name"].lower() for car in devices):
        raise SystemExit("Refusing cleanup: fixture differs from expected disposable data")
    # Back up through a separate read connection before modifying QA records.
    backup_path = s.DATABASE + ".before-qa-cleanup"
    with sqlite3.connect(s.DATABASE) as source, sqlite3.connect(backup_path) as target:
        source.backup(target)
    os.chmod(backup_path, 0o600)
    c.execute("DELETE FROM devices WHERE owner=?", (row["id"],))
    c.execute("DELETE FROM sessions WHERE user_id=?", (row["id"],))
    c.execute("DELETE FROM users WHERE id=?", (row["id"],))
print("Removed disposable QA account and 2 simulated vehicles; SQLite snapshot retained")
