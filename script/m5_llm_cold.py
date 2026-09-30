# -*- coding: utf-8 -*-
"""M5 LLM 冷启动/样本积累脚本：周期性调用 3 个 AI 场景，落 llm_call_log。

用法：
    python -X utf8 script/m5_llm_cold.py        # 默认 3 轮
    python -X utf8 script/m5_llm_cold.py 5      # 5 轮

每轮遍历整个 id 池：10 tpl + 10 alert + 5 incident = 25 calls / round。
每次调用之间 sleep 4-7s 模拟人工节奏。
错误容忍：单次失败仅记 fail，不中断；HTTP 500 / schema_validation 走 fail 分支。
"""
import base64
import calendar
import json
import random
import re
import subprocess
import sys
import time
import urllib.error
import urllib.request

# ---- 配置 -----------------------------------------------------------
BASE = "http://127.0.0.1:8080"

# labcfg: demo only, replace before prod
MYSQL_HOST = "127.0.0.1"
MYSQL_PORT = "3306"
MYSQL_USER = "root"
MYSQL_PASS = "123456"
MYSQL_DB = "aiops"

CALL_TIMEOUT = 90               # 单次 LLM 调用超时（秒）
SLEEP_MIN, SLEEP_MAX = 4, 7     # 模拟人工节奏的调用间隔（秒）

TPL_PICK = 10                   # 随机挑 10 个模板
ALERT_PICK = 10                 # 随机挑 10 个告警
INCIDENT_PICK = 5               # 随机挑 5 个事件


# ---- HTTP / MySQL 助手 ----------------------------------------------
def http(method, url, payload=None, token=None, timeout=CALL_TIMEOUT):
    """统一 HTTP 调用；错误体也尝试 JSON 解析。返回 (status, json_obj)。"""
    body = json.dumps(payload, ensure_ascii=False).encode("utf-8") if payload is not None else None
    req = urllib.request.Request(url, data=body, method=method)
    req.add_header("Content-Type", "application/json; charset=utf-8")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return r.status, json.loads(r.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8", errors="replace")
        try:
            return e.code, json.loads(raw)
        except Exception:
            return e.code, {"code": e.code, "msg": raw[:200]}
    except Exception as e:
        return -1, {"code": -1, "msg": "%s: %s" % (type(e).__name__, e)}


def mysql(sql):
    """通过 mysql CLI 查询，返回 stdout 文本（一行/列）。"""
    r = subprocess.run(
        ["mysql",
         "-h" + MYSQL_HOST, "-P" + MYSQL_PORT,
         "-u" + MYSQL_USER, "-p" + MYSQL_PASS,
         "-D" + MYSQL_DB,
         "-sN", "--default-character-set=utf8mb4",
         "-e", sql],
        capture_output=True, timeout=15)
    raw = r.stdout or b""
    for enc in ("utf-8", "gb18030"):
        try:
            return raw.decode(enc).strip()
        except UnicodeDecodeError:
            continue
    return raw.decode("utf-8", errors="replace").strip()


def pick_ids(sql, need):
    """从 mysql 查 id 列表，若不足 need 返回所有的。"""
    text = mysql(sql)
    ids = []
    for line in text.splitlines():
        line = line.strip()
        if line.isdigit():
            ids.append(int(line))
    return ids[:need]


# ---- 登录 -------------------------------------------------------------
def login():
    """admin/123456 登录，验证码 = svg base64 -> 正则提取。"""
    st, r = http("GET", BASE + "/api/auth/captcha", timeout=15)
    if st != 200 or r.get("code") != 200:
        raise RuntimeError("captcha fetch failed: %s %s" % (st, r))
    cap = r["data"]
    svg = base64.b64decode(cap["svg"]).decode("utf-8", errors="replace")
    code = "".join(re.findall(r">([A-Za-z0-9])</text>", svg))
    st, r = http("POST", BASE + "/api/auth/login", {
        "username": "admin",
        "password": "123456",
        "captchaCode": code,
        "captchaKey": cap["key"],
    }, timeout=15)
    if st != 200 or r.get("code") != 200:
        raise RuntimeError("login failed: %s %s" % (st, r))
    return r["data"]["token"]


# ---- 单次场景封装 ------------------------------------------------------
def call_log_explain(token, tpl_id):
    """POST /api/log/ai/explain  {templateId, sceneCode=log_explain}"""
    t0 = time.time()
    st, r = http("POST", BASE + "/api/log/ai/explain",
                 {"templateId": int(tpl_id), "sceneCode": "log_explain"},
                 token=token, timeout=CALL_TIMEOUT)
    return time.time() - t0, st, r


def call_alert_explain(token, alert_id):
    """GET /api/ai/scenario/alert-explain/{id}"""
    t0 = time.time()
    st, r = http("GET", BASE + "/api/ai/scenario/alert-explain/%d" % alert_id,
                 token=token, timeout=CALL_TIMEOUT)
    return time.time() - t0, st, r


def call_root_cause(token, incident_id):
    """GET /api/ai/scenario/root-cause/{id}"""
    t0 = time.time()
    st, r = http("GET", BASE + "/api/ai/scenario/root-cause/%d" % incident_id,
                 token=token, timeout=CALL_TIMEOUT)
    return time.time() - t0, st, r


def _tok(r):
    """从后端返回里抓 token 用量（字段名多源）。"""
    d = r.get("data") or {}
    if isinstance(d, dict):
        for k in ("tokenCost", "tokensUsed", "token", "tokenCount"):
            v = d.get(k)
            if isinstance(v, (int, float)) and v:
                return int(v)
    return 0


_ID_LABEL = {"log_explain": "tpl_id",
             "alert_explain": "alert_id",
             "root_cause": "incident_id"}


def run_one(round_no, scene, ref_id, fn, token):
    """统一入口：打印紧凑 stderr 行；返回 (ok, latency_ms)。"""
    label = _ID_LABEL[scene]
    try:
        el, st, r = fn(token, ref_id)
        lat_ms = int(el * 1000)
        ok = st == 200 and isinstance(r, dict) and r.get("code") == 200
        if ok:
            print("[cold] round=%d scene=%s %s=%s status=ok lat=%dms tok=%d"
                  % (round_no, scene, label, ref_id, lat_ms, _tok(r)),
                  file=sys.stderr)
            return True, lat_ms
        msg = ""
        if isinstance(r, dict):
            msg = r.get("msg") or r.get("message") or ("HTTP %s" % st)
        else:
            msg = "HTTP %s" % st
        print("[cold] round=%d scene=%s %s=%s status=fail msg=%s"
              % (round_no, scene, label, ref_id, str(msg)[:120]),
              file=sys.stderr)
        return False, lat_ms
    except Exception as e:
        print("[cold] round=%d scene=%s %s=%s status=fail msg=%s"
              % (round_no, scene, label, ref_id,
                 ("%s: %s" % (type(e).__name__, e))[:120]),
              file=sys.stderr)
        return False, 0


# ---- 主流程 ------------------------------------------------------------
def main():
    rounds = 3
    if len(sys.argv) > 1:
        try:
            rounds = max(1, int(sys.argv[1]))
        except ValueError:
            pass

    # 1) 登录
    token = login()

    # 2) 取样本池
    tpl_ids = pick_ids(
        "SELECT id FROM log_template ORDER BY RAND() LIMIT %d" % TPL_PICK, TPL_PICK)
    alert_ids = pick_ids(
        "SELECT id FROM alert_record ORDER BY RAND() LIMIT %d" % ALERT_PICK, ALERT_PICK)
    incident_ids = pick_ids(
        "SELECT id FROM alert_incident ORDER BY RAND() LIMIT %d" % INCIDENT_PICK,
        INCIDENT_PICK)

    if not tpl_ids or not alert_ids or not incident_ids:
        print("[cold] FATAL empty pool: tpl=%d alert=%d incident=%d"
              % (len(tpl_ids), len(alert_ids), len(incident_ids)), file=sys.stderr)
        sys.exit(2)

    # 3) 循环 N 轮：每轮遍历整个池（10 tpl + 10 alert + 5 incident = 25 calls / round）
    total = ok_n = fail_n = lat_sum = 0
    for rnd in range(1, rounds + 1):
        for tid in tpl_ids:
            ok, lat = run_one(rnd, "log_explain", tid, call_log_explain, token)
            total += 1
            ok_n += 1 if ok else 0
            fail_n += 0 if ok else 1
            lat_sum += lat
            time.sleep(random.uniform(SLEEP_MIN, SLEEP_MAX))

        for aid in alert_ids:
            ok, lat = run_one(rnd, "alert_explain", aid, call_alert_explain, token)
            total += 1
            ok_n += 1 if ok else 0
            fail_n += 0 if ok else 1
            lat_sum += lat
            time.sleep(random.uniform(SLEEP_MIN, SLEEP_MAX))

        for iid in incident_ids:
            ok, lat = run_one(rnd, "root_cause", iid, call_root_cause, token)
            total += 1
            ok_n += 1 if ok else 0
            fail_n += 0 if ok else 1
            lat_sum += lat
            # 末尾不再 sleep（除非是中间 round）
            if not (rnd == rounds and iid == incident_ids[-1]):
                time.sleep(random.uniform(SLEEP_MIN, SLEEP_MAX))

    avg = int(lat_sum / total) if total else 0
    print("[cold] total_calls=%d success_calls=%d fail_calls=%d avg_latency=%dms"
          % (total, ok_n, fail_n, avg))
    sys.exit(0)


if __name__ == "__main__":
    # calendar 在依赖白名单内；引入以标记脚本可能的 UTC 的时间窗口用途，未直接使用。
    _ = calendar.timegm(time.gmtime())
    main()
