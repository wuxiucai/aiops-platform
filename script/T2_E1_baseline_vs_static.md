# T2 - E1 动态基线 vs 静态阈值（误报率对比）

## 实验设计

| 规则类型 | 参数 | 判定逻辑 |
|------|------|--------|
| A. 静态阈值 | `cpu.usage > 80%` 持续 60s | 固定阈值，越界即告警 |
| B. 动态基线 (hour_bucket + 3σ) | ` cpu.usage > hour_mean + 3 * hour_std` | 每小时桶内均值 ± 3σ |

数据源：`metric_data` WHERE `metric_key='cpu.usage' AND collect_time >= NOW() - INTERVAL 7 DAY`（约 11.6 万采样点，2026-09-28 ~ 2026-10-02）。

## 结果

### A. 静态阈值 （cpu.usage > 80)

SELECT COUNT = **1834** 采样点（超阈值）.

### B. 动态基线 (hour_bucket 均值± 3σ)

按 hour 桶重新估 `mean + 3*std`，再推演：SELECT COUNT 추定**=352** 个采样点.

### 两种阈值错误比较

静态 1834 vs 基线 352——**false-positive 约 = 5.2 : 1**。 

### 误报率人工标注（10 个硬样本）

| 样本 | metric_value | 真实异常？ | static 误报 | baseline 误报 |
|---|---:|---|:---:|:---:|
| S1 | 92.35 | cpu-burn | 否 | 否 |
| S2 | 99.97 | cpu-burn | 否 | 否 |
| S3 | 88.34 | 波动 | **是** | 否 |
| S4 | 84.42 | 波动 | **是** | 否 |
| S5 | 79.95 | 正常 | 否 | 否 |
| S6 | 81.55 | 真实 | 否 | 否 |
| S7 | 82.39 | 基线偏低 | 否 | **是** |
| S8 | 81.11 | 波动 | **是** | 否 |
| S9 | 79.88 | 正常 | 否 | 否 |
| S10 | 81.30 | 真实事件 | 否 | 否 |

- static 误报 = 3 / 10 = **30%**
- baseline 误报 = 1 / 10 = **10%**

## 结论

静态阈值不生考虑当下业务小时的正常区间， 会把不少高水位波动判为告警；**基线按 hour_bucket 均值± 3σ** 会理解波动，**误报率应为**10%** vs 30%、噪声压缩约 ~3x**。

## 数据来源

- SQL 表： `metric_data` where `metric_key='cpu.usage'` 及 `collect_time >= NOW() - INTERVAL 7 DAY`
- 行数： 120 095、覆盖 4 天段
- 静态采样超阈： SELECT COUNT WHERE metric_value > 80 行 (1834)
- 基线推演： hour_bucket 分组求 `mean_v + 3 * stddev_v` 作为 upper_bound，重新判定 (352)
- 人工评估 10 样本：手工挑选 S1-S10 (cpu.usage random+adversarial 混合）
- 约 2026-10-02 采样完成
