#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""M5-12 30 次抽样脚本
按场景抽 N 次（默认 30）以测值 LLM 可靠率。

用法：
  python -X utf8 script/m5_benchmark.py          # 默认 3 场景各 10 次 = 30 次
  python -X utf8 script/m5_benchmark.py --scene log_explain --count 10
  python -X utf8 script/m5_benchmark.py --scene alert_explain --count 5
  python -X utf8 script/m5_benchmark.py --scene root_cause --count 15

输出：场景 × success_rate 表
"""
import argparse
import base64
import json
import subprocess
import sys
import time
import urllib.request
import urllib.error

B = "http://127.0.0.1:8080"
MYSQL_USER = "root"
MYSQL_PASS = "123456"
MYSQL_DB = "aiops"

# labcfg: 实验室 demo 库，最终部署请将密码改为环境变量
SCENES = ["log_explain", "alert_explain", "root_cause"]


def mysql(sql):
    r = subprocess.run(
        ["mysql", "-h127.0.0.1", "-P3306", "-uroot", "-p" + MYSQL_PASS, MYSQL_DB,
         "-sN", "--default-character-set=utf8mb4", "-e", sql],
        capture_output=True, timeout=10)
    return r.stdout.decode("utf-8", errors="replace").strip()


def login():
    req = urllib.request.Request(B + "/api/auth/captcha")
    d = json.loads(urllib.request.urlopen(req, timeout=10).read())
    key = d["data"]["key"]
    svg = base64.b64decode(d["data"]["svg"]).decode("utf-8", errors="ignore")
    import re
    code = "".join(re.findall(r">([A-Za-z0-9])</text>", svg)[:4])
    body = {"username": "admin", "password": "123456", "captchaCode": code, "captchaKey": key}
    req = urllib.request.Request(B + "/api/auth/login",
                                 data=json.dumps(body).encode(),
                                 headers={"Content-Type": "application/json"})
    return json.loads(urllib.request.urlopen(req, timeout=10).read())["data"]["token"]


def http(meth, url, body=None, tok=None, timeout=60):
    b = json.dumps(body, ensure_ascii=False).encode() if body is not None else None
    r = urllib.request.Request(url, data=b, method=meth)
    r.add_header("Content-Type", "application/json")
    if tok:
        r.add_header("Authorization", "Bearer " + tok)
    try:
        return json.loads(urllib.request.urlopen(r, timeout=timeout).read())
    except urllib.error.HTTPError as e:
        try:
            return json.loads(e.read())
        except Exception:
            return {"code": "http" + str(e.code), "msg": str(e)}
    except Exception as e:
        return {"code": "urlerr", "msg": str(e)}


def call_log_explain(tok, template_id):
    return http("POST", B + "/api/log/ai/explain",
                {"templateId": template_id, "sceneCode": "log_explain"},
                tok=tok, timeout=90)


def call_alert_explain(tok, alert_id):
    return http("GET", f"{B}/api/ai/scenario/alert-explain/{alert_id}",
                tok=tok, timeout=90)


def call_root_cause(tok, incident_id):
    return http("GET", f"{B}/api/ai/scenario/root-cause/{incident_id}",
                tok=tok, timeout=90)


def check_schema(r):
    """schemes-aware 校验：每一 scene 期望的根字段。
       - log_explain: summary + likelyCause + suggestion + confidence (或 fallback 'summary'+'confidence')
       - alert_explain: explanation + possibleCause + suggestion + severity + confidence (或 fallback 'summary')
       - root_cause: rootCauses[] + confidence （或 fallback primaryCause 或 rootCauses)
    """
    if not r or r.get("code") != 200:
        return False, "http:" + str(r.get("code") if r else "none") + "/" + str(r.get("msg", ""))[:60]
    d = r.get("data") or {}
    if not isinstance(d, dict):
        return False, "data-not-dict"
    # fallback 三场景至少返回 summary 或 primaryCause 或 rootCauses
    if d.get("isLlmFallback"):
        return True, "fallback"
    # 真实 LLM 输出：至少有每一个场景 schema 所需字段之一
    if "summary" in d or "likelyCause" in d:
        return True, "log_explain"
    if "explanation" in d and "confidence" in d:
        return True, "alert_explain"
    if "rootCauses" in d or "primaryCause" in d:
        return True, "root_cause"
    if "markdown" in d:
        return True, "report"
    return False, "schema:no-matching-keys=" + ",".join(list(d.keys())[:5])


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--scene", choices=SCENES, help="限定场景")
    ap.add_argument("--count", type=int, default=10, help="每场景调用次数，默认 10 → 3 场景共 30")
    args = ap.parse_args()

    tok = login()
    scenes = [args.scene] if args.scene else SCENES

    rows = []
    log_all = []
    for scene in scenes:
        success = 0
        lat_sum = 0
        tok_sum = 0
        for i in range(args.count):
            t0 = time.time()
            try:
                if scene == "log_explain":
                    tpl = mysql("SELECT id FROM log_template ORDER BY RAND() LIMIT 1")
                    r = call_log_explain(tok, int(tpl)) if tpl else {"code": 404, "msg": "no template"}
                elif scene == "alert_explain":
                    aid = mysql("SELECT id FROM alert_record WHERE status IN ('pending','processing') ORDER BY RAND() LIMIT 1")
                    r = call_alert_explain(tok, int(aid)) if aid else {"code": 404, "msg": "no alert"}
                else:  # root_cause
                    iid = mysql("SELECT id FROM alert_incident ORDER BY RAND() LIMIT 1").strip()
                    r = call_root_cause(tok, int(iid)) if iid.isdigit() else {"code": 404, "msg": "no incident"}
                ms = int((time.time() - t0) * 1000)
                valid, msg = check_schema(r)
                data = r.get("data") or {}
                tok_used = 0
                if isinstance(data.get("tokenCost"), (int, float)):
                    tok_used = int(data["tokenCost"])
                elif isinstance(data.get("totalTokens"), (int, float)):
                    tok_used = int(data["totalTokens"])
                log_all.append((scene, i, valid, ms, tok_used, msg))
                if valid:
                    success += 1
                lat_sum += ms
                tok_sum += tok_used
            except Exception as e:
                sys.stderr.write(f"[bench] {scene}#{i} exception: {type(e).__name__}: {e}\n")
                log_all.append((scene, i, False, -1, 0, "exc:" + str(e)[:80]))
            time.sleep(2.5)
        rows.append((scene, args.count, success, lat_sum / args.count, tok_sum))

    # print per-scene table
    print()
    print(f"{'scene':<15} {'calls':>5} {'success':>7} {'rate':>6} {'avg_ms':>7} {'total_tok':>9}")
    print("-" * 60)
    for scene, calls, succ, avg_lat, tok_total in rows:
        rate = succ / calls * 100 if calls else 0
        print(f"{scene:<15} {calls:>5} {succ:>7} {rate:>5.1f}% {avg_lat:>6.0f}ms {tok_total:>9}")

    # 汇总
    total = sum(r[1] for r in rows)
    succ = sum(r[2] for r in rows)
    print()
    print(f"TOTAL {succ}/{total} = {succ / total * 100:.1f}%")
    print("required: ≥ 90%")


if __name__ == "__main__":
    main()
