"""Register the existing Compose project for aaPanel Docker management."""
import datetime
import os
import sqlite3
import time

path = "/www/server/panel/data/compose/BYDMate-Remote/compose.yaml"
database = "/www/server/panel/data/docker.db"
con = sqlite3.connect(database)
row = con.execute("SELECT path FROM stacks WHERE name='bydmate-remote'").fetchone()
if row:
    if row[0] != path:
        raise SystemExit("Unrelated Compose project exists; refusing overwrite")
    print("Compose already registered")
else:
    backup = database + ".bydmate-" + datetime.datetime.now().strftime("%Y%m%d-%H%M%S")
    with sqlite3.connect(backup) as target:
        con.backup(target)
    os.chmod(backup, 0o600)
    with con:
        con.execute("INSERT INTO stacks(name,status,path,template_id,time,remark) VALUES(?,'1',?,NULL,?,?)",
                    ("bydmate-remote", path, int(time.time()), "BYDMate Remote Climate"))
    print("Compose registered in aaPanel; recovery snapshot:", backup)
con.close()
