# -*- coding: utf-8 -*-
"""M4 蓄水：造 500 条 ERROR 日志，4 个固定模式，分布在 5 分钟窗口内。"""
import urllib.parse, urllib.request, json, time


def post(url):
    try:
        req = urllib.request.Request(url, data=b"", method="POST")
        with urllib.request.urlopen(req, timeout=10) as r:
            return r.read().decode()
    except Exception as e:
        return f"ERR {e}"


B1 = "http://localhost:8081"
B2 = "http://localhost:8082"
# 4 个固定模式；每次 error-log 打 100 条
patterns = ["CONN-TIMEOUT", "DB-DUP-KEY", "LOCK-WAIT-TIMEOUT", "NULL-RATE-HIGH"]
for i, pat in enumerate(patterns):
    url = f"{B1}/demo/fault/error-log?pattern={urllib.parse.quote(pat)}"
    # 打 2 次（200 条每个模式）
    print(pat, post(url))
    print(pat, post(url))

# error_rate 配置：混杂一些 ERROR 拉比率
# 不打更多：让 800 条足够
print("done")
