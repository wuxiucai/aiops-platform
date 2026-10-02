# T2 - E5 根因准确率人工标注模板（20 场景）

**说明**：20 个 incident 来自现网 `alert_incident.id 31~50`（按 id 升序）,**所有场景都被认定"有已知 root cause"**（因历史已知故障注入或数据库情况已知）。请你人工查看每个 incident，**在最后一列填 `correct` 或 `incorrect`**，并简要写 reasoning（≤20 字）。

## 填表说明

- **correct**:AI 根因分析的主要 primaryCause 与你的判断一致
- **incorrect**：不一致或完全错误（如把 cpu 比作 db)
- 数 同记 你现化一致性 （知识点 > 名义上)

## 20 个场景 （待你判定）

| # | incident_id | title | level | alert_count | AI primaryCause （摘要） | correct | reasoning |
|---|---|---|---:|---|---|---|---|
| 1 | 31 | [CRITICAL] M3验收-JVM堆低阈值 - jvm.heap.usage = 4.0692 | CRITICAL | 1 | — |  |  |
| 2 | 32 | [CRITICAL] M3验收-JVM堆低阈值 - jvm.heap.usage = 2.9933 | CRITICAL | 1 | — |  |  |
| 3 | 33 | [CRITICAL] M3验收-JVM堆低阈值 - jvm.heap.usage = 3.9707 | CRITICAL | 1 | — |  |  |
| 4 | 34 | [CRITICAL] M3验收-JVM堆低阈值 - jvm.heap.usage = 2.9205 | CRITICAL | 1 | — |  |  |
| 5 | 35 | [CRITICAL] M3验收-JVM堆低阈值 - jvm.heap.usage = 3.8966 | CRITICAL | 1 | — |  |  |
| 6 | 36 | [CRITICAL] M3验收-JVM堆低阈值 - jvm.heap.usage = 2.4714 | CRITICAL | 1 | — |  |  |
| 7 | 37 | [CRITICAL] M3验收-JVM堆低阈值 - jvm.heap.usage = 3.4860 | CRITICAL | 1 | — |  |  |
| 8 | 38 | [CRITICAL] M3验收-JVM堆低阈值 - jvm.heap.usage = 2.4733 | CRITICAL | 1 | — |  |  |
| 9 | 39 | [WARN] M3验收-CPU低阈值 - cpu.usage = 59.6600 | WARN | 1 | — |  |  |
| 10 | 40 | [CRITICAL] M3验收-JVM堆低阈值 - jvm.heap.usage = 1.9139 | CRITICAL | 1 | — |  |  |
| 11 | 41 | [CRITICAL] M3验收-JVM堆低阈值 - jvm.heap.usage = 2.7741 | CRITICAL | 1 | — |  |  |
| 12 | 42 | [WARN] M4自动恢复验证 - jvm.heap.usage = 2.7741 | WARN | 1 | — |  |  |
| 13 | 43 | [WARN] M4自动恢复验证 - jvm.heap.usage = 2.0985 | WARN | 1 | — |  |  |
| 14 | 44 | [WARN] M4自动恢复验证 - jvm.heap.usage = 2.5557 | WARN | 1 | — |  |  |
| 15 | 45 | [WARN] M4自动恢复验证 - jvm.heap.usage = 1.8780 | WARN | 1 | — |  |  |
| 16 | 46 | [WARN] M4自动恢复验证 - jvm.heap.usage = 2.5583 | WARN | 1 | — |  |  |
| 17 | 47 | [WARN] M3静默专用规则 - cpu.usage = 50.0000 | WARN | 2 | — |  |  |
| 18 | 48 | [WARN] M3静默专用规则 - cpu.usage = 50.0000 | WARN | 2 | — |  |  |
| 19 | 49 | [WARN] M3验收-CPU低阈值 - cpu.usage = 19.1500 | WARN | 4 | — |  |  |
| 20 | 50 | [CRITICAL] M3验收-JVM堆低阈值 - jvm.heap.usage = 2.6940 | CRITICAL | 2 | — |  |  |

## 最后再加 2 列 （本次生成结论用）你填完后回来

```
correct_count = ?
incorrect_count = ?
accuracy = correct / 20 = ?
```

## 数据来源

- SQL: `SELECT id, title, level, alert_count FROM alert_incident WHERE id BETWEEN 31 AND 50 ORDER BY id;` → 20 rows
- `llm_root_cause` 现在仍是 NULL（M5-W6 联调阶段还没批量跑过 root_cause），等你指定具体哪些跑 LLM判定时回到 artificial本届 adding 再核对.
- incident 31-38, 40-41 都是 JVM 注入场景 (`jvm.heap.usage` value 变化） 触发而 incident 39 是 cpu，如果要求 AI 判定的原则是已经判定的 main特征比如 jvm.heap.usage 异常才是压扮是 (jvm-behavior**）
- 开始 **10-02 20:00 发指令**画 microscopy ready 的处理 java-kernel 标题解释： in the course of testing.