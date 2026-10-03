"""Idempotently register this proxy in aaPanel; backup its DB before adding a row."""
import copy
import datetime
import json
import os
import sqlite3

DOMAIN = "byd.slk-soft.ru"
ROOT = "/www/wwwroot/" + DOMAIN
DATABASE = "/www/server/panel/data/default.db"


def replace(value, old):
    if isinstance(value, str):
        return value.replace(old, DOMAIN).replace(old.replace(".", "_"), DOMAIN.replace(".", "_"))
    if isinstance(value, list):
        return [replace(v, old) for v in value]
    if isinstance(value, dict):
        return {k: replace(v, old) for k, v in value.items()}
    return value


con = sqlite3.connect(DATABASE)
con.row_factory = sqlite3.Row
existing = con.execute("SELECT * FROM sites WHERE name=?", (DOMAIN,)).fetchone()
if existing:
    if existing["ps"] != "BYDMate Remote Climate":
        raise SystemExit("Refusing to modify an existing unrelated site")
    print("aaPanel site already registered")
else:
    source = con.execute("SELECT name,project_config FROM sites WHERE project_type='proxy' AND project_config<>'{}' LIMIT 1").fetchone()
    if not source:
        raise SystemExit("aaPanel proxy template not found")
    backup = DATABASE + ".bydmate-" + datetime.datetime.now().strftime("%Y%m%d-%H%M%S")
    with sqlite3.connect(backup) as target:
        con.backup(target)
    os.chmod(backup, 0o600)
    config = replace(json.loads(source["project_config"]), source["name"])
    config.update(site_name=DOMAIN, domain_list=[DOMAIN], site_path=ROOT, remark="BYDMate Remote Climate")
    proxy = config.get("proxy_info")
    if not proxy:
        raise SystemExit("Proxy template is invalid")
    config["proxy_info"] = [proxy[0]]
    config["proxy_info"][0].update(proxy_pass="http://127.0.0.1:18183", remark="BYDMate climate gateway")
    config.setdefault("ssl_info", {}).update(ssl_status=True, force_https=True)
    stamp = datetime.datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    with con:
        cursor = con.execute("INSERT INTO sites(name,path,status,ps,type_id,addtime,project_type,project_config) VALUES(?,?,'1',?,0,?,'proxy',?)",
                             (DOMAIN, ROOT, "BYDMate Remote Climate", stamp, json.dumps(config)))
        con.execute("INSERT INTO domain(pid,name,port,addtime) VALUES(?,?,80,?)", (cursor.lastrowid, DOMAIN, stamp))
    print("aaPanel site registered; database snapshot:", backup)
con.close()
