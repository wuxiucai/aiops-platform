# -*- coding: utf-8 -*-
"""M3 验收（§13 M3 基础项 + 审查方扩展 M3-9/10/11）。"""
import base64, json, re, subprocess, sys, time, urllib.request, urllib.error

B = "http://localhost:8080"
res = []

def rec(name, ok, detail=""):
    res.append((name, ok, detail))
    print(f"[{'PASS' if ok else 'FAIL'}] {name}  {detail}")

def http(m, url, d=None, tok=None):
    body = json.dumps(d, ensure_ascii=False).encode("utf-8") if d is not None else None
    req = urllib.request.Request(url, data=body, method=m)
    req.add_header("Content-Type", "application/json; charset=utf-8")
    if tok:
        req.add_header("Authorization", "Bearer " + tok)
    try:
        return json.loads(urllib.request.urlopen(req, timeout=20).read().decode())
    except urllib.error.HTTPError as e:
        return json.loads(e.read().decode())

def mysql(sql):
    r = subprocess.run(["mysql", "-h127.0.0.1", "-P3306", "-uroot", "-p123456",
                        "-Daiops", "-sN", "--default-character-set=utf8mb4", "-e", sql],
                       capture_output=True, timeout=15)
    raw = r.stdout or b""
    for enc in ("utf-8", "gb18030"):
        try:
            return raw.decode(enc).strip()
        except UnicodeDecodeError:
            continue
    return raw.decode("utf-8", errors="replace").strip()

def login():
    c = http("GET", B + "/api/auth/captcha")["data"]
    code = "".join(re.findall(r">([A-Za-z0-9])</text>", base64.b64decode(c["svg"]).decode()))
    return http("POST", B + "/api/auth/login",
                {"username": "admin", "password": "123456",
                 "captchaCode": code, "captchaKey": c["key"]})["data"]["token"]

tok = login()
rec("M3-0 平台 admin 登录", tok is not None)

# 1. 造 cpu 告警已触发（在前置 setup 已存在 id=1 的 pending alert）
cnt = mysql("SELECT COUNT(*) FROM alert_record WHERE status IN ('pending','processing') AND trigger_count >= 1")
rec("M3-1 手工造数据能触发 alert_record", int(cnt or 0) >= 1, f"pending_or_processing={cnt}")

# 2. M3-11 inapp 通知可见 → sys_message 未读、title 含级别
msg = mysql("SELECT CONCAT_WS('|', id, title, IFNULL(content,''), is_read) FROM sys_message WHERE msg_type='alert' ORDER BY id DESC LIMIT 1")
title_part = msg.split("|")[1] if msg else ""
level_in_title = any(lv in title_part for lv in ("WARN", "CRITICAL", "INFO", "ERROR"))
is_unread = msg.endswith("|0")
rec("M3-11 inapp 通知（未读、title 含级别）", bool(msg) and level_in_title and is_unread,
    f"title={title_part[:40]} unread={is_unread}")

# 3. claim → resolve 状态流转
d = http("GET", B + "/api/alert/record/page?size=1&status=pending", tok=tok)
records = d["data"]["records"]
aid = records[0]["id"] if records else 1
ok_claim = http("PUT", f"{B}/api/alert/record/{aid}/claim", tok=tok).get("code") == 200
st = http("GET", f"{B}/api/alert/record/{aid}", tok=tok)["data"]["status"]
rec("M3-2a 认领 → processing", ok_claim and st == "processing", f"status={st}")
ok_res = http("PUT", f"{B}/api/alert/record/{aid}/resolve", {"remark": "M3验收解决"}, tok=tok).get("code") == 200
st2 = http("GET", f"{B}/api/alert/record/{aid}", tok=tok)["data"]["status"]
rec("M3-2b 解决 → resolved + remark 留存", ok_res and st2 == "resolved", f"status={st2}")

# 4. dedup：同对象 5 分钟内同规则只更新 trigger_count，不新建
mysql("UPDATE alert_record SET status='pending' WHERE id=1")
time.sleep(70)
row = mysql("SELECT trigger_count, status FROM alert_record WHERE id=1")
tc = row.split("\t")[0] if row else "0"
# dedup：当前 pending/processing 状态下，同一 dedup_key 只能有 ≤1 条活跃记录；历史 closed 是恢复后新建属正常
cnt_open = mysql("SELECT COUNT(*) FROM alert_record WHERE dedup_key='rule_1_target_1_metric_cpu.usage' AND status IN ('pending','processing')")
rec("M3-3 dedup 生效（同问题持续 trigger_count 累加，不新增活跃记录）",
    int(cnt_open or 0) <= 1 and int(tc or 0) >= 2,
    f"trigger_count={tc} open_rows_for_dedup={cnt_open}")

# 5. incident 聚合：alert#1 与某条 target=1 的 incident 关联了 open
inc_id = mysql("SELECT incident_id FROM alert_record WHERE id=1")
inc_status = ""
if inc_id and inc_id != "NULL":
    inc_status = mysql(f"SELECT status FROM alert_incident WHERE id={inc_id}")
rec("M3-4 alert_incident 自动聚合 open", bool(inc_id and inc_id != "NULL") and inc_status in ("open", "processing"),
    f"incident_id={inc_id} status={inc_status}")

# 6. M3-9 related-logs 返回真实日志（service=order-service）
d = http("GET", B + "/api/alert/record/2/related-logs", tok=tok)
data = d.get("data") or {}
total9 = data.get("total", 0)
recs = data.get("records") or []
svc = recs[0].get("service", "") if recs else ""
rec("M3-9 /record/2/related-logs 返回 service=order-service", total9 >= 1 and svc == "order-service",
    f"total={total9} first_service={svc}")

# 7. M3-10 上下文真实（非 mock；含 orderId= 真实业务日志）
has_real = any(r.get("service") == "order-service" and "orderId=" in (r.get("message") or "")
               for r in recs)
rec("M3-10 日志内容来自真实索引", has_real,
    f"sample={(recs[0].get('message','')[:40] if recs else 'NONE')}")

# 8. 静默：P0 修复——用独立 dedup_key 隔离，不再与前面测试用 id=1 共享生命周期；
#   新建独立 target=99 的规则 → 静默覆盖 target=99 → 触发它 → 该 alert 的 status 必须 closed
#   先清掉旧测试残留
mysql("UPDATE alert_silence SET status=0 WHERE name='M3验收静默' OR name LIKE 'M3静默%'")
http("POST", B + "/api/alert/rule",
     {"name": "M3静默专用规则", "targetId": 99, "groupId": 1, "metricKey": "cpu.usage",
      "ruleType": "static", "operator": "gt", "threshold": 1, "durationSec": 30,
      "level": "WARN", "notifyChannels": "[\"inapp\"]", "enabled": 1}, tok=tok)
# 拿 rule id（最新的）
new_rule_id = mysql("SELECT id FROM alert_rule ORDER BY id DESC LIMIT 1").strip()
# 给 target=99 造数据：直接插 metric_data（不需要真能跑的 agent）
now_str = time.strftime("%Y-%m-%d %H:%M:%S", time.localtime())
for i in range(5):
    mysql("INSERT INTO metric_data (target_id, metric_key, metric_value, collect_time) "
          f"VALUES (99, 'cpu.usage', 50.0, DATE_SUB('{now_str}', INTERVAL {i*9} SECOND))")
# 覆盖 target=99 的静默（先删掉已有的）
mysql("DELETE FROM alert_silence WHERE target_id=99 AND status=1")
http("POST", B + "/api/alert/silence",
     {"name": "M3静默专用", "targetId": 99,
      "startTime": time.strftime("%Y-%m-%d %H:%M:%S", time.localtime(time.time() - 3600)),
      "endTime": time.strftime("%Y-%m-%d %H:%M:%S", time.localtime(time.time() + 3600)),
      "reason": "M3-5专用", "status": 1}, tok=tok)
time.sleep(70)  # 等 AlertDetectJob 跑
row99 = mysql(f"SELECT status, trigger_count FROM alert_record WHERE dedup_key='rule_{new_rule_id}_target_99_metric_cpu.usage' ORDER BY id DESC LIMIT 1")
status99 = row99.split("\t")[0] if row99 else ""
rec("M3-5 静默命中 → status=closed（独立 target=99 dedup_key）",
    status99 == "closed", f"rule={new_rule_id} status={status99}")

print()
print("=" * 60)
fails = [n for n, ok, _ in res if not ok]
print(f"PASS {len(res) - len(fails)} / {len(res)}")
for n in fails:
    print("  FAIL -", n)
sys.exit(1 if fails else 0)
