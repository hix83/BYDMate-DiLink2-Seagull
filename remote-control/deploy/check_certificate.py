"""Verify aaPanel's auto-renew record without displaying certificate private material."""
import os
import sys

os.chdir("/www/server/panel")
sys.path[:0] = ["/www/server/panel/class", "/www/server/panel/class_v2"]
from public.hook_import import hook_import
hook_import()
from ssl_domainModelV2.model import DnsDomainSSL

matches = [record for record in DnsDomainSSL.objects.all() if "byd.slk-soft.ru" in record.dns]
if len(matches) != 1:
    raise SystemExit("Expected one aaPanel renewal certificate record, found " + str(len(matches)))
record = matches[0]
print("aaPanel certificate: auto_renew=" + str(record.auto_renew) + "; auth_type=" + str(record.auth_info.get("auth_type")))
if record.auto_renew != 1 or record.auth_info.get("auth_type") != "http":
    raise SystemExit("Certificate renewal is not correctly configured")
