#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""M5-12 30 发完整抽样 + 落 raw JSON
按场景各 10 发 → 30 发总； 每发记 isValid/latency/tokens/error；输出 JSON 到 script/benchmark_result.json
"""
import argparse
import base64
import json
import re
import subprocess
import sys
import time
import urllib.request
import urllib.error
from datetime import datetime

B = "http://127.0.0.1:8080"
MYSQL_USER, MYSQL_PASS, MYSQL_DB = "root", "123456", "aiops"  # labcfg: demo only
SCENES = ["log_explain", "alert_explain", "root_cause"]


def mysql(sql):
    r = subprocess.run(["mysql", "-h127.0.0.1", "-P3306", "-u" + MYSQL_USER,
                        "-p" + MYSQL_PASS, MYSQL_DB, "-sN",
                        "--default-character-set=utf8mb4", "-e", sql],
                       capture_output=True, timeout=10)
    return r.stdout.decode("utf-8", errors="replace").strip()


def login():
    r = urllib.request.urlopen(B + "/api/auth/captcha", timeout=10)
    d = json.loads(r.read())
    key = d["data"]["key"]
    svg = base64.b64decode(d["data"]["svg"]).decode("utf-8", errors="ignore")
    code = "".join(re.findall(r">([A-Za-z0-9])</text>", svg)[:4])
    body = {"username": "admin", "password": "123456", "captchaCode": code, "captchaKey": key}
    r = urllib.request.urlopen(
        urllib.request.Request(B + "/api/auth/login", data=json.dumps(body).encode(),
                               headers={"Content-Type": "application/json"}), timeout=10)
    return json.loads(r.read())["data"]["token"]


def http(m, url, d=None, tok=None, t=90):
    b = json.dumps(d, ensure_ascii=False).encode() if d is not None else None
    r = urllib.request.Request(url, data=b, method=m)
    r.add_header("Content-Type", "application/json")
    if tok: r.add_header("Authorization", "Bearer " + tok)
    try:
        return json.loads(urllib.request.urlopen(r, timeout=t).read())
    except urllib.error.HTTPError as e:
        try: return json.loads(e.read())
        except Exception: return {"code": "http" + str(e.code), "msg": str(e)}
    except Exception as e:
        return {"code": "urlerr", "msg": str(e)}


def call(scene, tok, ref):
    if scene == "log_explain":
        return http("POST", B + "/api/log/ai/explain",
                    {"templateId": ref, "sceneCode": "log_explain"}, tok=tok, t=90)
    if scene == "alert_explain":
        return http("GET", f"{B}/api/ai/scenario/alert-explain/{ref}", tok=tok, t=90)
    if scene == "root_cause":
        return http("GET", f"{B}/api/ai/scenario/root-cause/{ref}", tok=tok, t=90)
    return {"code": 400, "msg": "unknown"}


def pick_ref(scene):
    if scene == "log_explain":
        return mysql("SELECT id FROM log_template ORDER BY RAND() LIMIT 1")
    if scene == "alert_explain":
        return mysql("SELECT id FROM alert_record WHERE status IN ('pending','processing') ORDER BY RAND() LIMIT 1")
    if scene == "root_cause":
        return mysql("SELECT id FROM alert_incident ORDER BY RAND() LIMIT 1")
    return ""


def schema_valid(scene, data):
    if not isinstance(data, dict):
        return False, "data-not-dict"
    if data.get("isLlmFallback"):
        return True, "fallback"
    if scene == "log_explain":
        return ("summary" in data, "log_explain")
    if scene == "alert_explain":
        return (("explanation" in data and "confidence" in data), "alert_explain")
    if scene == "root_cause":
        return (("rootCauses" in data or "primaryCause" in data), "root_cause")
    return False, "unknown"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--scene", choices=SCENES)
    ap.add_argument("--count", type=int, default=10)
    ap.add_argument("--out", default="script/benchmark_result.json")
    args = ap.parse_args()

    tok = login()
    scenes = [args.scene] if args.scene else SCENES

    results = {"timestamp": datetime.now().isoformat(),
               "scenes": {},
               "raw_calls": []}
    for scene in scenes:
        print(f"=== scene={scene} ===")
        stats = {"calls": args.count, "success": 0, "fail": 0, "avg_latency_ms": 0, "total_tokens": 0}
        lat_sum = 0
        for i in range(args.count):
            ref = pick_ref(scene)
            if not ref.strip() or not str(ref).strip().isdigit():
                rec = {"scene": scene, "index": i, "success": False, "error": "no-ref-row",
                       "latency_ms": 0, "tokens": 0}
                results["raw_calls"].append(rec)
                stats["fail"] += 1
                print(f"  {i:2}: FAIL (no ref)")
                continue
            t0 = time.time()
            r = call(scene, tok, int(ref))
            ms = int((time.time() - t0) * 1000)
            code = r.get("code")
            data = r.get("data") or {}
            valid, tag = schema_valid(scene, data)
            success = (code == 200 and valid)
            tok_used = 0
            if isinstance(data.get("tokenCost"), (int, float)):
                tok_used = int(data["tokenCost"])
            elif isinstance(data.get("totalTokens"), (int, float)):
                tok_used = int(data["totalTokens"])
            rec = {"scene": scene, "index": i, "ref": int(ref),
                   "success": success,
                   "http_code": code,
                   "schema_tag": tag if success else "schema-invalid:" + tag,
                   "latency_ms": ms, "tokens": tok_used,
                   "isLlmFallback": data.get("isLlmFallback") if isinstance(data, dict) else None}
            if not success:
                rec["error_preview"] = (r.get("msg") or json.dumps(data, ensure_ascii=False)[:120])
            results["raw_calls"].append(rec)
            if success:
                stats["success"] += 1
            else:
                stats["fail"] += 1
            lat_sum += ms
            stats["total_tokens"] += tok_used
            print(f"  {i:2}: ref={ref} {'OK' if success else 'FAIL'} ({tag}) lat={ms}ms tok={tok_used}")
            time.sleep(2.5)
        stats["avg_latency_ms"] = round(lat_sum / args.count, 1)
        results["scenes"][scene] = stats
        r_line = f"{scene:<15} calls={stats['calls']} success={stats['success']} rate={stats['success']/stats['calls']*100:.1f}% avg={stats['avg_latency_ms']}ms"
        print(r_line)

    total_calls = sum(s["calls"] for s in results["scenes"].values())
    total_succ = sum(s["success"] for s in results["scenes"].values())
    results["summary"] = {
        "total_calls": total_calls,
        "total_success": total_succ,
        "success_rate_pct": round(total_succ / total_calls * 100, 1) if total_calls else 0,
        "threshold_pct": 90,
        "passed": (total_succ / total_calls) >= 0.9 if total_calls else False
    }
    print(f"TOTAL {total_succ}/{total_calls} = {results['summary']['success_rate_pct']}%")
    with open(args.out, "w", encoding="utf-8") as f:
        json.dump(results, f, ensure_ascii=False, indent=2)
    print(f"saved → {args.out}")


if __name__ == "__main__":
    main()
