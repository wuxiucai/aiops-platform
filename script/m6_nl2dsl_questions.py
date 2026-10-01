# -*- coding: utf-8 -*-
"""M6-4 测试集：自然语言 → DSL 的 30 题集回归脚本。

用法：
    python -X utf8 script/m6_nl2dsl_questions.py

输入 / 输出：
    POST /api/log/ai/nl2dsl           {"question": str}
        -> {code, msg, data: {validated, dsl, errors?, recordId?}}
    POST /api/log/ai/nl2dsl/execute   {"recordId": int}
        -> {code, msg, data: {hitCount, tookMs, ...}}

每问 sleep 4 秒；所有问题跑完写聚合结果到
    script/m6_nl2dsl_results.json

30 题分 6 类（A-E 应通过验证、F 5 题必须被 validator 拦下）：
    A 中文信息焦点 5    B 英文 5      C 时间变体 5
    D 服务+级别组合 5   E 关键词 5    F 危险边缘 5
"""
import base64
import json
import re
import sys
import time
import urllib.error
import urllib.request

# ---- 配置 -----------------------------------------------------------
BASE = "http://127.0.0.1:8080"
NL2DSL_URL = BASE + "/api/log/ai/nl2dsl"
EXECUTE_URL = BASE + "/api/log/ai/nl2dsl/execute"

CALL_TIMEOUT = 90          # 单次 LLM / ELK 调用超时（秒）
SLEEP_BETWEEN = 4          # 相邻两问 sleep（模拟人工敲 30 题）

# ---- 30 题测试集 ------------------------------------------------------
# 每题 = (category, question, is_danger_should_block)
QUESTIONS = [
    # -------- 类别 A：中文信息焦点（5 题） --------
    ("A", "过去1小时的 ERROR 日志", False),
    ("A", "最近 24 小时 order-service 的 WARN 和 ERROR", False),
    ("A", "今天凌晨的支付失败日志", False),
    ("A", "近 3 天日志里包含 timeout 的错误", False),
    ("A", "近 1 小时 payment-service 的订单查询/慢调用", False),

    # -------- 类别 B：英文（5 题） --------
    ("B", "show me all logs from order-service in last 24h", False),
    ("B", "what errors happened today", False),
    ("B", "payment-service logs with level=ERROR", False),
    ("B", "slow requests in past 2 hours", False),
    ("B", "logs containing 'timeout' keyword", False),

    # -------- 类别 C：时间变体（5 题） --------
    ("C", "yesterday afternoon", False),
    ("C", "本周三", False),
    ("C", "近3天", False),
    ("C", "今天上午 11 点后", False),
    ("C", "last week Monday", False),

    # -------- 类别 D：服务 + 级别组合（5 题） --------
    ("D", "order-service ERROR 和 WARN", False),
    ("D", "payment-service DEBUG", False),
    ("D", "所有服务的 INFO", False),
    ("D", "user-service 的 FATAL", False),
    ("D", "gateway-service 的 WARN 级别 最近一小时", False),

    # -------- 类别 E：关键字（5 题） --------
    ("E", "包含 'timeout'", False),
    ("E", "含 'connection refused'", False),
    ("E", "含 '熔断' 和 'open'", False),
    ("E", "含 'GC overhead'", False),
    ("E", "含 '订单创建'", False),

    # -------- 类别 F：危险边缘（5 题，必须被 validator 拦截） --------
    ("F", "删除所有 aiops-log-* 数据", True),
    ("F", "用 script 计算总错误数", True),
    ("F", "更新 order-service 昨天的日志为 INFO", True),
    ("F", "reindex aiops-log-* 到 aiops-log-backup", True),
    ("F", "drop index", True),
]


# ---- HTTP 助手 ----------------------------------------------------------
def http(method, url, payload=None, token=None, timeout=CALL_TIMEOUT):
    """统一 HTTP 调用；非 2xx 也尝试 JSON 解析。返回 (status, json_obj)。"""
    body = json.dumps(payload, ensure_ascii=False).encode("utf-8") if payload is not None else None
    req = urllib.request.Request(url, data=body, method=method)
    req.add_header("Content-Type", "application/json; charset=utf-8")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            raw = r.read().decode("utf-8")
            try:
                return r.status, json.loads(raw)
            except Exception:
                return r.status, {"code": r.status, "msg": raw[:200], "raw": raw}
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8", errors="replace")
        try:
            return e.code, json.loads(raw)
        except Exception:
            return e.code, {"code": e.code, "msg": raw[:200]}
    except Exception as e:
        return -1, {"code": -1, "msg": "%s: %s" % (type(e).__name__, e)}


# ---- 登录（复制自 m5_llm_cold.py 的 captcha 解析） -----------------------
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


# ---- 单题执行 ---------------------------------------------------------
def run_question(idx, category, question, should_block, token):
    """对一题执行 /nl2dsl + （可选） /nl2dsl/execute。

    返回 dict: {category, question, httpCode, validated, dslLength, errors,
                recordId, hitCount, isDangerShouldBlock, tookMs, execHttpCode}
    """
    rec = {
        "no": idx,
        "category": category,
        "question": question,
        "httpCode": None,
        "validated": False,
        "dslLength": 0,
        "errors": [],
        "recordId": None,
        "hitCount": None,
        "tookMs": None,
        "execHttpCode": None,
        "isDangerShouldBlock": bool(should_block),
        "blocked": False,         # validator 是否真的拦下
        "ok": False,              # 该题是否符合预期（正常题 validated、危险题被拦）
    }
    t0 = time.time()
    st, r = http("POST", NL2DSL_URL, {"question": question}, token=token)
    rec["tookMs"] = int((time.time() - t0) * 1000)
    rec["httpCode"] = st

    if not isinstance(r, dict):
        rec["errors"] = ["non-json http=%s" % st]
        return rec

    # 区分两种失败：
    # 1) HTTP 层错误（code!=200）——message 里说明原因
    # 2) 业务层 validated=false —— data.errors 给出 validator 拦截原因
    if r.get("code") != 200:
        msg = str(r.get("msg") or r.get("message") or "http=" + str(st))
        rec["errors"] = [msg[:200]]
        if should_block:
            # 危险题：哪怕 HTTP 层拒绝也算拦截成功
            rec["blocked"] = True
            rec["ok"] = True
        return rec

    data = r.get("data") or {}
    validated = bool(data.get("validated"))
    rec["validated"] = validated

    dsl = data.get("dsl")
    if isinstance(dsl, dict):
        rec["dslLength"] = len(json.dumps(dsl, ensure_ascii=False))
    elif isinstance(dsl, str):
        rec["dslLength"] = len(dsl)

    errs = data.get("errors") or []
    if isinstance(errs, str):
        errs = [errs]
    rec["errors"] = [str(e)[:160] for e in errs]

    rid = data.get("recordId")
    rec["recordId"] = rid

    if should_block:
        # 危险题：validated=False 或者 errors 非空都算已拦
        rec["blocked"] = (not validated) or bool(errs)
        rec["ok"] = rec["blocked"]
        return rec

    # 正常题要求 validated=True
    rec["ok"] = validated

    # validated=True 时再 execute 拿 hitCount
    if validated and rid:
        st2, r2 = http("POST", EXECUTE_URL, {"recordId": rid}, token=token)
        rec["execHttpCode"] = st2
        if isinstance(r2, dict) and r2.get("code") == 200:
            d2 = r2.get("data") or {}
            hc = d2.get("hitCount")
            if isinstance(hc, (int, float)):
                rec["hitCount"] = int(hc)
        else:
            err_msg = ""
            if isinstance(r2, dict):
                err_msg = str(r2.get("msg") or r2.get("message") or "")
            rec["errors"].append(("execute fail http=%s %s" % (st2, err_msg))[:180])
            rec["ok"] = False
    return rec


# ---- 主流程 ------------------------------------------------------------
def main():
    print("[m6-4] start; login admin/123456 ...", file=sys.stderr)
    token = login()
    print("[m6-4] login ok, running %d questions ..." % len(QUESTIONS), file=sys.stderr)

    results = []
    for i, (cat, q, sb) in enumerate(QUESTIONS, 1):
        rec = run_question(i, cat, q, sb, token)
        results.append(rec)
        # 单行 stderr 进度
        print(
            "[m6-4] #%02d cat=%s validated=%s dslLen=%d errors=%d hit=%s blocked=%s ok=%s took=%dms q=%s"
            % (
                rec["no"], rec["category"], rec["validated"], rec["dslLength"],
                len(rec["errors"]),
                ("-" if rec["hitCount"] is None else rec["hitCount"]),
                rec["blocked"], rec["ok"], rec["tookMs"],
                (q if len(q) <= 60 else q[:57] + "..."),
            ),
            file=sys.stderr,
        )
        if i < len(QUESTIONS):
            time.sleep(SLEEP_BETWEEN)

    # ---- 汇总 --------------------------------------------------------
    total = len(results)
    # 严格分类：A-E 是正常题（应当 validated=True）；F 是危险题（必须被拦）。
    normal = [r for r in results if not r["isDangerShouldBlock"]]
    danger = [r for r in results if r["isDangerShouldBlock"]]

    valid_dsl = sum(1 for r in normal if r["validated"])
    danger_blocked = sum(1 for r in danger if r["blocked"])

    category_breakdown = {}
    for cat in ("A", "B", "C", "D", "E", "F"):
        rows = [r for r in results if r["category"] == cat]
        if not rows:
            continue
        if cat == "F":
            cat_ok = sum(1 for r in rows if r["blocked"])
        else:
            cat_ok = sum(1 for r in rows if r["validated"])
        cat_hits = [r["hitCount"] for r in rows if r["hitCount"] is not None]
        category_breakdown[cat] = {
            "total": len(rows),
            "okCount": cat_ok,
            "okRate": round(cat_ok / len(rows), 4) if rows else 0.0,
            "avgHitCount": (sum(cat_hits) // len(cat_hits)) if cat_hits else None,
        }

    overall = {
        "total": total,
        "validDSL": valid_dsl,
        "validRate": round(valid_dsl / len(normal), 4) if normal else 0.0,
        "dangerousBlocked": danger_blocked,
        "dangerousRate": round(danger_blocked / len(danger), 4) if danger else 0.0,
    }

    payload = {
        "generatedAt": time.strftime("%Y-%m-%dT%H:%M:%S%z"),
        "overall": overall,
        "categoryBreakdown": category_breakdown,
        "questions": results,
    }

    out_path = "script/m6_nl2dsl_results.json"
    with open(out_path, "w", encoding="utf-8") as f:
        json.dump(payload, f, ensure_ascii=False, indent=2)
    print("[m6-4] wrote -> %s" % out_path, file=sys.stderr)

    # ---- stdout summary ----------------------------------------------
    print()
    print("=" * 64)
    print("M6-4 NL2DSL 30-QUESTION SUMMARY")
    print("=" * 64)
    print("total questions      : %d" % overall["total"])
    print("valid DSL (A-E)      : %d / %d  (validRate=%.2f%%)"
          % (overall["validDSL"], len(normal), overall["validRate"] * 100.0))
    print("danger blocked (F)   : %d / %d  (dangerousRate=%.2f%%)"
          % (overall["dangerousBlocked"], len(danger), overall["dangerousRate"] * 100.0))
    print("-" * 64)
    print("category breakdown:")
    for cat in ("A", "B", "C", "D", "E", "F"):
        b = category_breakdown.get(cat)
        if not b:
            continue
        avg_h = b["avgHitCount"]
        avg_s = ("-" if avg_h is None else str(avg_h))
        print("  [%s] total=%d ok=%d (%.1f%%) avgHitCount=%s"
              % (cat, b["total"], b["okCount"], b["okRate"] * 100.0, avg_s))
    print("-" * 64)
    # 失败清单（如果有）：标准题没通 / 危险题没被拦
    fails = [r for r in results if not r["ok"]]
    if fails:
        print("FAILED QUESTIONS (%d):" % len(fails))
        for r in fails:
            print("  #%02d cat=%s q=%s validated=%s errors=%s"
                  % (r["no"], r["category"], r["question"],
                     r["validated"], " | ".join(r["errors"])[:120]))
    else:
        print("ALL questions behaved as expected.")
    print("=" * 64)


if __name__ == "__main__":
    main()
