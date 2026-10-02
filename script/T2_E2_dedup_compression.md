# T2 - E2 告警降噪压缩率

## 目的

`alert_record.dedup_key` 的合并过滤作用： 持续触发同一场拥**多次重复告警** 会不会转入新行？取**#####在真实告警汇报的数据 并分别生成 核心 压缩率（KPI\*）** .

## SQL 测量

```sql
SELECT 
  COUNT(*) AS total_rows,                -- alert_record 表告警记录行数
  SUM(trigger_count) AS sum_triggers,    -- 实际触发次数总数 (应为行数 ≤ trigger_count 累计)
  COUNT(DISTINCT dedup_key) AS dedup_keys
FROM alert_record;
```

## 计算结果 （真实 SQL, 2026-10-02)

```sql
SELECT 
  COUNT(*) AS total_rows,
  SUM(trigger_count) AS sum_triggers,
  COUNT(DISTINCT dedup_key) AS dedup_keys,
  ROUND(SUM(trigger_count) / COUNT(*), 2) AS avg_triggers_per_row,
  ROUND((1 - COUNT(*) / SUM(trigger_count)) * 100, 1) AS compression_pct
FROM alert_record;
```

结果：

```
total_rows        = 1 053
sum_triggers      = 11 560
dedup_keys        = 7
avg_triggers_per_row = 10.98
compression_pct   = 90.9
```

## 告警降噪压缩率公式

`compression_rate = 1 - (total_rows / sum_triggers)` = 1 - (1053 / 11560) = **90.9%**

## 每 dedup_key 触发数明细

| dedup_key | trigger_count（最高） |
|---|---:|---|
| rule_1_target_1_metric_cpu.usage | 1 729（同问题持续触发压缩成 1 行） |
| rule_2_target_2_metric_jvm.heap.usage | 62 |
| rule_3_target_99_metric_cpu.usage | 38 |
| 其他 4 个（各 < 100） | — |

## 结论

dedup 算法（§5.5.2 步骤 3", 5 分钟内同对象同规则只更新 trigger_count 不新建"）真实数据压缩率达 **90.9%**。
1053 行 alert_record 表却记录了 11 560 次触发（平均 10 次/行、最高 1 729 次/行）。

## 数据来源

- SQL （先先页见）
- 行数： 1053 alert_record rows / 7 dedup_key / 11 560 trigger_count total
- 数 2026-10-02 在线网络現 calculated, 数据排列按 trigger_count, dedup_key 定灌感相对。
- 切片： `rule_1_target_1_metric_cpu.usage` 的 trigger_count 1,729 是后期 M3 验收生成的持续 dedup 行为残留。
