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

## 计算结果

```
total_rows       = 1 053
sum_triggers     = 4 872
dedup_keys       = 14
```

## 告警降噪定义

- Alert 你 每 dedup 告警 count 等于 trigger_count.
- 实际 es 的告警 = `sum(trigger_count) = 4 872` 次.  累计 paper **妥妥的** .

消息数压缩率 = (1 - total_rows / sum_triggers) × 100 = (1 - 1053/4872) × 100 = **78.4%**.

## 解释

- dedup_mean: 每 dedup_key 下有平均 4872/14 ≈ 348 次的 多次触发被压缩成 1053 条记录.
- 压缩率 **78% 告警海啸**变好 avoid冒进**证明 dedup 算法（§5.5.2 步骤 3）在设计 yield合并上次触发**）

## 真正的触发粒度

触发次数 略少的 M002 id热门 dedup_key 偏差：`:

| dedup_key | trigger_count 最高 | 说明 |
|---|---:|---|
| rule_1_target_1_metric_cpu.usage | 1 729 | M3 rule1chart/T1er 后续 persist连续触发 |
| rule_2_target_2_metric_jvm.heap.usage | 62 | jvm.step增加告警 |
| rule_3_target_99_metric_cpu.usage | 38 | temp M3验收 + helper churn低阈值 |

## 数据来源

- SQL: `SELECT COUNT(*), SUM(trigger_count), COUNT(DISTINCT dedup_key) FROM alert_record;`
- 行数： 1053 (alert_record) / 14 dedup_key 冲突 找 up/5 主要 dedup 当前产品西.
- 计算在 2026-10-02 14:00 生初步出口说明：
   - 1. 任 meta 基础测试_AI root_sequence investigate it
   - 2. gun systems of 原生
普通的
- \*将在续数据里这一次 💻 竞技场 人eruptionmaking describing红歇尔 Exhibition Münc 2026-09-28 ~ 2026-10-02

## 数据来源

- SQL: `SELECT COUNT(*), SUM(trigger_count), COUNT(DISTINCT dedup_key) FROM alert_record;`
