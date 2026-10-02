# E2 实验：告警降噪压缩率

## 实验目的
验证本平台告警降噪机制（去重 → 聚合 → incident）对原始告警量的压缩效果。这是论文 §6.2 实验一的对照基础。

## 数据来源
- 表：`alert_record`、`alert_incident`
- 时间范围：2026-09-28 ~ 2026-10-02（4 天运行数据）
- 数据采集：M3~M5 期间真实告警触发结果

## 实验方法

### 三层降噪模型
```
原始触发 (metric 越界)
    ↓ dedup (10 min 窗口合并)
alert_record (单层记录)
    ↓ aggregate (5 min 窗口同 target/group 合并)
alert_incident (多层故障事件)
```

### 计算公式
- 触发→告警压缩率 = (触发总数 − 告警记录数) / 触发总数
- 告警→事件压缩率 = (告警记录数 − incident 数) / 告警记录数
- 总压缩率 = (触发总数 − incident 数) / 触发总数

## 实验结果

### Q1 触发→告警压缩率
```
total_triggers  = 12,617   (metric 检测脱触总数，含同 dedup_key 多次触发的 trigger_count 累加)
alert_records   =  1,053   (alert_record 表行数，dedup 已生效)
compression_dedup = (12,617 - 1,053) / 12,617 = 91.65%
```

**结论**：dedup（同 dedup_key 在 10 分钟窗口内合并）单独贡献了 **91.65%** 压缩率。

### Q2 事件级降噪
```
alert_records   = 1,053
incident_count  =    54   (alert_incident 表行数)
compression_agg = (1,053 - 54) / 1,053 = 94.87%
```

**结论**：incident 聚合（5 min 窗口内同对象或同分组告警合并为单一事件）单独贡献 **94.87%** 压缩率。

### Q3 三层总压缩率
```
total_triggers  = 12,617
incident_count  =     54
total_compression = (12,617 - 54) / 12,617 = 99.57%
```

**结论**：从原始触发到运维人员实际处理的事件量，平台共压缩 **99.57%**。平均一个 incident 对应 233 次原始触发（12,617/54）——人工处理成本下降两个数量级。

## 对论文的价值

| 论文论点 | 本实验支撑 |
|---|---|
| 告警降噪是 AIOps 核心价值 | 99.57% 总压缩率 |
| dedup 是降噪主体贡献 | dedup 单独贡献 91.65% |
| 告警治理闭环显著降低人工负担 | 233:1 的触发/事件比 |
| 与 Zabbix/PagerDuty 简单 dedup 的对比 | 三级模型 vs 一级（文献调研） |

## 数据来源说明
- SQL：`SELECT SUM(trigger_count), COUNT(*) FROM alert_record`
- SQL：`SELECT COUNT(*) FROM alert_incident`
- 时间戳：2026-10-02 16:55 实时抽取
- 数据采集期间，**没有任何人工标记误报**——所有数字是机器检测+治理的自然结果
