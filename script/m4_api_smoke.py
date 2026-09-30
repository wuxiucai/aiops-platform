# -*- coding: utf-8 -*-
"""M4 API 冒烟：search / histogram / anomaly / rule 一次跑完。"""
import base64, datetime, json, re, urllib.request, urllib.error

B = "http://localhost:8080"


def http(m, url, d=None, tok=None):
    body = json.dumps(d, ensure_ascii=False).encode("utf-8") if d is not None else None
    req = urllib.request.Request(url, data=body, method=m)
    req.add_header("Content-Type", "application/json; charset=utf-8")
    if tok:
        req.add_header("Authorization", "Bearer " + tok)
    try:
        return json.loads(urllib.request.urlopen(req, timeout=30).read().decode())
    except urllib.error.HTTPError as e:
        return json.loads(e.read().decode())


c = http("GET", B + "/api/auth/captcha")["data"]
code = "".join(re.findall(r">([A-Za-z0-9])</text>", base64.b64decode(c["svg"]).decode()))
tok = http("POST", B + "/api/auth/login",
           {"username": "admin", "password": "123456",
            "captchaCode": code, "captchaKey": c["key"]})["data"]["token"]
print("[login] ok")

fmt = "%Y-%m-%d %H:%M:%S"
now = datetime.datetime.now()
start = (now - datetime.timedelta(hours=24)).strftime(fmt)
end = now.strftime(fmt)

# 1. search
body = {"datasourceId": 1, "indexConfigId": 1, "startTime": start, "endTime": end,
        "levels": ["ERROR"], "services": [], "keyword": "", "traceId": "",
        "page": 1, "size": 5, "analyzer": "standard"}
r = http("POST", B + "/api/log/search", body, tok)
d = r.get("data") or {}
print("[search] total=", d.get("total"), "dsl_ok=", "query" in (d.get("dsl") or ""))
for x in (d.get("records") or [])[:2]:
    print("   -", x.get("time"), x.get("service"), (x.get("message") or "")[:50])

# 2. histogram
r2 = http("POST", B + "/api/log/search/histogram", body, tok)
buckets = (r2.get("data") or {}).get("buckets") or []
print("[histogram] buckets=", len(buckets))

# 3. anomaly page
r3 = http("GET", B + "/api/log/anomaly/page?size=10", tok=tok)
arr = (r3.get("data") or {}).get("records") or []
print("[anomaly] records=", len(arr))
for a in arr[:3]:
    print("   -", a.get("id"), a.get("anomalyType"), (a.get("title") or "")[:50])

# 4. rule page
r4 = http("GET", B + "/api/log/rule/page?size=10", tok=tok)
rules = (r4.get("data") or {}).get("records") or []
print("[rule] records=", len(rules))

# 5. template page
r5 = http("GET", B + "/api/log/template/page?size=10", tok=tok)
tpls = (r5.get("data") or {}).get("records") or []
print("[template] records=", len(tpls))
for t in tpls[:3]:
    print("   -", t.get("id"), (t.get("templateText") or "")[:60], "total=", t.get("totalCount"))