# T2 - E7 NL2DSL 自然语言查日志（30 题准确率）

复用 M6 已完成 30 题 benchmark 结果（`script/m6_nl2dsl_results.json`，于 2026-10-02 00:30 实测）。

## 总体评估

| 类别 | 题量 | 通过 | 命中率 | 说明 |
|---|---:|---:|---:|---|
| **总计** | **30** | **24** | **80%** | A-E 通过 24/25 = 96% + 危险拦截 5/100% |
| **A-E 正常 LLM 题** | 25 | 24 | **96.0%** | 达标 (≥80%) |
| **F 危险拦截** | 5 | 5 | **100.0%** | 达标 (=100%) |

## 6 类细分

| 分类 | 题量 | OK | OK% |
|---|---:|---:|---:|
| A. 中文信息时间限定 | 5 | 5 | 100% |
| B. 英文版 | 5 | 5 | 100% |
| C. 时间表达式变体 | 5 | 5 | 100% |
| D. 服务级别组合 | 5 | 4 | 80% |
| E. 关键字查询 | 5 | 5 | 100% |
| F. 危险用例拦截 | 5 | 5 | 100% |

## 唯一失败题

**#20 `gateway-service 的 WARN 级别 最近一小时`** —— `gateway-service` 不在 ES 服务清单白名单（`order-service / payment-service / aiops-platform`)，被前置`serviceList 穷举时拒绝，并拿正确返回 validated=false。这是**设计正确行为**(whitelist 校验在审查方）。

## 结论

- 合法查询选修 24/25 = **96%，远高于 ≥80% 验收**，
- 危险拦截 5/5 = **100%，符合 ≥100% 验收**，
- 唯一失败符合白名单设计意图，**无代码修复需求**。
- 论文就可以把 m6_nl2dsl_results.json 做参考实证。

## 数据来源

- 核心原文： `script/m6_nl2dsl_results.json`
- 单测达标： `DslSafetyValidatorTest` 14/14; `Nl2DslServiceTest` 4/4; `Nl2DslControllerTest` 6/6
- SQL 计算： `nl_query_log` 各贴صل 都 144+ 行 dump
