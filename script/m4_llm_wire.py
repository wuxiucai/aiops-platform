# -*- coding: utf-8 -*-
"""M4 LLM 链路接线：PUT 更新 DeepSeek key + POST test + POST explain 一次跑通。
key 从环境变量 AIOPS_LLM_KEY 读，不落盘。
"""
import base64, json, os, re, subprocess, sys, time, urllib.request, urllib.error

B = "http://localhost:8080"
KEY = os.environ.get("AIOPS_LLM_KEY")
if not KEY:
    print("FATAL: AIOPS_LLM_KEY not set")
    sys.exit(2)


def http(m, url, d=None, tok=None, timeout=60):
    body = json.dumps(d, ensure_ascii=False).encode("utf-8") if d is not None else None
    req = urllib.request.Request(url, data=body, method=m)
    req.add_header("Content-Type", "application/json; charset=utf-8")
    if tok:
        req.add_header("Authorization", "Bearer " + tok)
    try:
        return json.loads(urllib.request.urlopen(req, timeout=timeout).read().decode())
    except urllib.error.HTTPError as e:
        return json.loads(e.read().decode())


def mysql(sql):
    r = subprocess.run(
        ["mysql", "-h127.0.0.1", "-P3306", "-uroot", "-p123456", "-Daiops",
         "-sN", "--default-character-set=utf8mb4", "-e", sql],
        capture_output=True, timeout=15)
    raw = r.stdout or b""
    for enc in ("utf-8", "gb18030"):
        try:
            return raw.decode(enc).strip()
        except UnicodeDecodeError:
            continue
    return raw.decode("utf-8", errors="replace").strip()


# login
c = http("GET", B + "/api/auth/captcha")["data"]
code = "".join(re.findall(r">([A-Za-z0-9])</text>", base64.b64decode(c["svg"]).decode()))
tok = http("POST", B + "/api/auth/login",
           {"username": "admin", "password": "123456",
            "captchaCode": code, "captchaKey": c["key"]})["data"]["token"]
print("[1] login ok")

# PUT（最小字段集，后端按 id 增量更新）
r = http("PUT", B + "/api/llm/provider", {
    "id": 1,
    "apiKey": KEY,
    "status": 1,
    "isDefault": 1}, tok)
print("[2] PUT provider:", r)

# 探活
r = http("POST", B + "/api/llm/provider/1/test", tok=tok)
print("[3] POST test:", r)

# AI explain
tid = mysql("SELECT id FROM log_template ORDER BY id LIMIT 1").strip()
if not tid or not tid.isdigit():
    mysql(
        "INSERT INTO log_template (datasource_id,index_config_id,cluster_id,template_text,token_count,template_hash,"
        "first_seen,last_seen,total_count,last_window_count,sample_log,variables,level,service,status) VALUES ("
        "1,1,999,'PaymentService charge orderId <*> amount <NUM> result FAIL',9,"
        "'aabbccddeeff00112233445566778899aabbccdd',NOW(),NOW(),250,12,"
        "'PaymentService charge orderId ORD-abc amount 99 result FAIL','[\"ORD-abc\",\"99\"]','ERROR','payment-service',0)"
    )
    tid = mysql("SELECT id FROM log_template ORDER BY id LIMIT 1").strip()
print("[4] template id =", tid)

t0 = time.time()
r = http("POST", B + "/api/log/ai/explain",
         {"scene": "template_explain", "refId": int(tid)}, tok)
el = time.time() - t0
print("[5] explain elapsed=%.1fs code=%s msg=%s" % (el, r.get("code"), r.get("msg")))
if r.get("code") == 200:
    data = r.get("data") or {}
    a = data.get("analysis") or {}
    for k in ("summary", "likelyCause", "suggestion", "confidence"):
        v = a.get(k)
        if isinstance(v, str):
            v = v[:100]
        print(f"    {k}: {v}")
    print("    latencyMs:", data.get("latencyMs"), "tokenCost:", data.get("tokenCost"))
print("[6] log_analysis_record rows =", mysql("SELECT COUNT(*) FROM log_analysis_record").strip())
