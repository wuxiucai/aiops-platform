#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""M7-T2-E3 真实 Drain sweep
9 组循环 (depth 3/4/5 × simTh 0.4/0.5/0.6):
  a. POST /api/log/drain/params ( setting param)
  b. DELETE log_template & log_template_stat
  c. wait for LogTemplateJob 触发 (~600s)
  d. SELECT COUNT(*) FROM log_template + SUM(total_count)
  e. PRINT raw row for {depth, simTh, template_count, compression}

Output (Per sweep): plain text rows to stdout, plus collected as JSON at end.
"""
import base64, json, re, subprocess, sys, time, urllib.request, urllib.error

B = "http://127.0.0.1:8080"
SWEEPS = [(d, s) for d in (3, 4, 5) for s in (0.4, 0.5, 0.6)]
WAIT_SEC = 600  # 10 min per LogTemplateJob cycle


def mysql(sql):
    r = subprocess.run(["mysql", "-uroot", "-p123456", "aiops", "-sN",
                        "--default-character-set=utf8mb4", "-e", sql],
                       capture_output=True, timeout=15)
    return r.stdout.decode("utf-8", errors="replace").strip() or "0"


def http(m, url, d=None, tok=None, t=90):
    b = json.dumps(d, ensure_ascii=False).encode() if d is not None else None
    r = urllib.request.Request(url, data=b, method=m)
    r.add_header("Content-Type", "application/json")
    if tok: r.add_header("Authorization", "Bearer " + tok)
    try: return json.loads(urllib.request.urlopen(r, timeout=t).read())
    except urllib.error.HTTPError as e:
        try: return json.loads(e.read())
        except Exception: return {"code": "http" + str(e.code), "msg": str(e)}


def login():
    c = http("GET", B + "/api/auth/captcha")["data"]
    code = "".join(re.findall(r">([A-Za-z0-9])</text>", base64.b64decode(c["svg"]).decode()))
    body = {"username": "admin", "password": "123456", "captchaCode": code, "captchaKey": c["key"]}
    return http("POST", B + "/api/auth/login", body)["data"]["token"]


def safe_int(v):
    try: return int(v)
    except Exception: return 0


def wait_for_template_job_change(prev_last_run):
    """等到 log_template_stat 表里出现 fresh rows (stat_time > prev)."""
    deadline = time.time() + WAIT_SEC
    while time.time() < deadline:
        stat_time = mysql("SELECT COALESCE(MAX(stat_time), '') FROM log_template_stat")
        if stat_time and stat_time > prev_last_run:
            return stat_time
        time.sleep(15)
    return None


def main():
    tok = login()
    out = []
    for depth, sim in SWEEPS:
        print(f"[sweep] {depth}/{sim}: set params")
        http("POST", B + "/api/log/drain/params",
             {"datasourceId": 1, "indexConfigId": 1,
              "depth": depth, "simTh": sim, "maxChildren": 100, "maxCluster": 1000},
             tok)

        prev = mysql("SELECT COALESCE(MAX(stat_time), '') FROM log_template_stat")

        mysql("DELETE FROM log_template_stat;")
        mysql("DELETE FROM log_template;")

        stat = wait_for_template_job_change(prev)
        # 再补 90s 缓冲，让 job 真正落 rows
        time.sleep(90)
        tpl = safe_int(mysql("SELECT COUNT(*) FROM log_template"))
        logs = safe_int(mysql("SELECT COALESCE(SUM(total_count),0) FROM log_template"))
        rate = round(1 - tpl / max(logs, 1), 4) if logs > 0 else 0
        row = {"depth": depth, "simTh": sim, "template_count": tpl,
               "total_logs": logs, "compression_rate": rate, "stat_triggered": stat}
        out.append(row)
        # 直接 stdout 打报格， 不重 .md 文件
        print(f"[sweep-result] depth={depth} simTh={sim} templates={tpl} logs={logs} rate={rate}")
        sys.stdout.flush()
    # Restore default depth=4 simTh=0.4
    http("POST", B + "/api/log/drain/params",
         {"datasourceId": 1, "indexConfigId": 1,
          "depth": 4, "simTh": 0.4, "maxChildren": 100, "maxCluster": 1000}, tok)
    print("[cleanup] restored default depth=4/simTh=0.4")
    # 最后 dump JSON 到标准输出 末位
    print(json.dumps(out, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
