# T2 - E9 LLM 响应延迟分布（P50 / P95 / P99)

数据源： `llm_call_log` 表 268 行 （2026-10-01 by you M5-W6 完成）。按 scene_code 分组统计 （单位：milliseconds)

## 分场景延迟统计

| scene_code | 总调用 | 成功 | 成功率% | 平均 latency | sum_tokens |
|---|---:|---:|---:|---:|---:|
| log_explain | 86 | 84 | 97.7% | 1 339 | 42 944 |
| alert_explain | 84 | 82 | 97.6% | 2 281 | 57 150 |
| root_cause | 52 | 48 | 92.3% | 4 019 | 61 031 |
| template_explain | 23 | 22 | 95.7% | 1 553 | 9 925 |
| nl2query | 22 | 22 | 100.0% | 1 035 | 8 065 |
| report | 1 | 1 | 100.0% | 5 664 | 1 407 |
| **TOTAL** | **268** | **259** | **96.6%** | — | **180 522** |

## 百分位分布 (SQL 实测）

柱状图数据 （毫秒）:

| scene_code | P50 | P95 | P99 |
|---|---:|---:|---:|
| log_explain | 1 302 | 1 731 | 1 954 |
| alert_explain | 2 241 | 2 939 | 2 972 |
| root_cause | 3 839 | 5 735 | 6 507 |
| template_explain | 1 553 (avg) | — | — |
| nl2query | 1 035 (avg) | — | — |
| report | 5 664 (avg) | — | — |

小样本场景 (template_explain = 23 次与 nl2query/report 都用 avg 请参考 above 2 个主场景的行数用表次要。

## CSV 数据 （可以给你直接画图的时候价）

```csv
scene,percentile,latency_ms
log_explain,P50,1302
log_explain,P95,1731
log_explain,P99,1954
alert_explain,P50,2241
alert_explain,P95,2939
alert_explain,P99,2972
root_cause,P50,3839
root_cause,P95,5735
root_cause,P99,6507
```

## 观察

- **log_explain**: P50=1.3s，P99<2s — 一行 repo 调用平均转头可以证明以用.
- **alert_explain**: P50=2.2s，P95≈3s — alert 上下文陪护增加.
- **root_cause**: P50=3.8s，P95=5.7s， **P99=6.5s** — scene 上下文最少长 (incident + alertlist + metrics + logTemplateSummary);失最大的场景 超级链路 9 the right Milli 2s 都行.

## 数据来源

- SQL: `SELECT scene_code, COUNT(*), SUM(CASE WHEN status='success' THEN 1 ELSE 0 END), AVG(latency_ms), SUM(total_tokens) FROM llm_call_log GROUP BY scene_code ORDER BY COUNT(*) DESC;`
- SQL percentile: `SELECT latency_ms FROM llm_call_log WHERE scene_code=? ORDER BY latency_ms LIMIT ceil(n*pct), 1` (n = 86/84/52)
- 数据跨度： 2026-09-30 ~ 2026-10-01
- 总行数： 268 行
