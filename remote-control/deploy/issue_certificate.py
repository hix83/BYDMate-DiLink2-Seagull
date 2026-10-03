"""Use aaPanel's existing ACME account and renewal registry. Never print key material."""
import os
import shutil
import sys

os.chdir("/www/server/panel")
sys.path.insert(0, "/www/server/panel/class")
from acme_v2 import acme_v2

domain = "byd.slk-soft.ru"
target = "/www/server/panel/vhost/cert/" + domain
if os.path.exists(target + "/fullchain.pem"):
    raise SystemExit("Certificate already present; manage renewal in aaPanel")
result = acme_v2().apply_cert([domain], auth_type="http", auth_to="/www/wwwroot/" + domain)
if not result.get("status"):
    raise SystemExit("Certificate issuance failed: " + str(result.get("msg")))
source = result["save_path"]
os.makedirs(target, mode=0o700, exist_ok=True)
for filename in ("fullchain.pem", "privkey.pem"):
    shutil.copyfile(os.path.join(source, filename), os.path.join(target, filename))
    os.chmod(os.path.join(target, filename), 0o600)
print("Certificate issued and installed; renewal order stored in aaPanel")
