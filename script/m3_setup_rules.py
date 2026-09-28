# -*- coding: utf-8 -*-
"""快速建两条 M3 验收规则（避免 shell heredoc 编码问题）。"""
import base64, json, re, urllib.request

B = "http://localhost:8080"

def http(m, url, d=None, tok=None):
    body = json.dumps(d, ensure_ascii=False).encode("utf-8") if d else None
    req = urllib.request.Request(url, data=body, method=m)
    req.add_header("Content-Type", "application/json; charset=utf-8")
    if tok: req.add_header("Authorization", "Bearer " + tok)
    return json.loads(urllib.request.urlopen(req, timeout=10).read().decode())

c = http("GET", B + "/api/auth/captcha")["data"]
code = "".join(re.findall(r">([A-Za-z0-9])</text>", base64.b64decode(c["svg"]).decode()))
tok = http("POST", B + "/api/auth/login",
           {"username": "admin", "password": "123456", "captchaCode": code, "captchaKey": c["key"]})["data"]["token"]

rules = [
    {"name": "M3验收-CPU低阈值", "targetId": 1, "groupId": 1,
     "metricKey": "cpu.usage", "ruleType": "static", "operator": "gt",
     "threshold": 1, "durationSec": 30, "level": "WARN",
     "notifyChannels": "[\"inapp\"]", "enabled": 1},
    {"name": "M3验收-JVM堆低阈值", "targetId": 2, "groupId": 2,
     "metricKey": "jvm.heap.usage", "ruleType": "static", "operator": "gt",
     "threshold": 1, "durationSec": 30, "level": "CRITICAL",
     "notifyChannels": "[\"inapp\"]", "enabled": 1},
]
for r in rules:
    print(http("POST", B + "/api/alert/rule", r, tok))

page = http("GET", B + "/api/alert/rule/page?size=20", tok=tok)
for r in page["data"]["records"]:
    print(r["id"], r["name"], r["metricKey"], "enabled=", r["enabled"])
