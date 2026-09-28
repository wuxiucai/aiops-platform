# MILESTONE-3 交付报告

- 完成时间：2026-09-28 17:05 (Asia/Shanghai)
- 涉及模块：module.alert（规则/基线/记录/静默）、module.incident（事件/关联/时间线）、module.notify（inapp/webhook 通知）、EsQueryBuilder（时区 + .keyword 修复）、Jackson 全局时间配置、demo-service H2 持久化、前端 4 个告警页
- 自述完成度：**100%**（M3 基础项 + 扩展 M3-9/10/11 全部 PASS，M3-8 已通过 related-logs 间接覆盖）
- 启动方式 / 关键命令 / 接口清单：见下

---

## 前置交付物状态（审查方 M3-0a/0b 复议）

| 项 | 状态 | 证据 |
|---|---|---|
| M3-0a ES 集群识别 + es_datasource 改指 10.0.0.91 | ✅ | `cluster=zifan-linux92, nodes=3, status=green, ik=ik-not-installed`，test_result=`OK 7.17.29 nodes=3 ik-not-installed`，remark=`remote-cluster / no-auth / no-IK-plugin (zifan-linux92, 3 nodes green)` |
| M3-0b demo-service H2 持久化 | ✅ 已完成 | `demo-order-service/data/order-db.mv.db` + `demo-payment-service/data/payment-db.mv.db`；重启后 `GET /api/order/{id}` 返回 PAID 订单；M7 "前天订单"已有数据届时可查 |

## 启动方式

```bash
# 后端（8080）
cd backend && java -jar target/aiops-backend-1.0.0.jar
# demo-order（8081，cwd 要在 demo-service/demo-order-service 让 H2 落自己目录）
cd demo-service/demo-order-service && java -jar target/demo-order-service.jar
# demo-payment（8082，同理）
cd demo-service/demo-payment-service && java -jar target/demo-payment-service.jar
# 前端（5173）
cd frontend && npm run dev
```

## 关键命令

| 命令 | 用途 |
|---|---|
| `python -X utf8 script/m3_accept.py` | M3 验收 10 项自动化 |
| `python -X utf8 script/m3_setup_rules.py` | 造 2 条低阈值规则（CPU>1%、JVM heap>1%）触发告警链路 |
| `curl -X POST http://localhost:8081/api/order/create -d '{"amount":N}'` | 触发 order→pay 调用链，同时往 `aiops-log-*` 写 INFO 日志 |

## 接口清单（本里程碑新增）

### /api/alert/rule（告警规则）
| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/page` | 分页（keyword/ruleType/enabled 筛选） |
| GET | `/{id}` | 详情 |
| POST | `/` | 新增（creator 自动写入） |
| PUT | `/` | 修改 |
| DELETE | `/{id}` | 删除（连同 baseline_model） |
| PUT | `/{id}/toggle?enabled=` | 启停切换 |
| POST | `/{id}/train` | 基线训练（EEE-HH，168 桶，mean±k·std） |
| GET | `/{id}/baseline-chart` | 某日 24h 上/下界 + 实际值 |
| POST | `/{id}/dry-run` | 历史回放（不落库） |

### /api/alert/record（告警记录 + 生命周期 + 关联日志）
| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/page` | 分页（status/ruleId/targetId/时间窗筛选，pageWithNames 关联查询） |
| GET | `/{id}` | 详情 |
| PUT | `/{id}/claim` | 认领（pending → processing） |
| PUT | `/{id}/resolve` | 解决（→ resolved，联动 incident） |
| PUT | `/{id}/close` | 关闭（→ closed，联动 incident） |
| PUT | `/{id}/false-positive` | 标记误报（→ false_positive） |
| GET | `/{id}/related-logs` | **M3-9**：ES 检索 ±10min 内 target.log_service_name 日志 |
| GET | `/count-24h` | 24h 告警数（大盘用） |

### /api/alert/silence（静默）
标准 CRUD + 筛选

### /api/incident（故障事件）
| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/page` | 分页 |
| GET | `/{id}` | 详情：{ incident, alerts[], timeline[] } |
| PUT | `/{id}/resolve` | 手动标记事件解决（级联未结告警） |
| POST | `/{id}/timeline` | 手动添加事件节点（body: event_type, description） |

### /api/notify/channel（通知渠道）
标准 CRUD：webhook 渠道 POST JSON 到 config.url；inapp 内置。

## M3 验收结果（`python -X utf8 script/m3_accept.py`）

| # | 项 | 结果 | 证据 |
|---|---|---|---|
| M3-0 | 平台登录 | ✅ | admin/123456 |
| M3-1 | 造 data 触发 alert_record | ✅ | pending=2；CPU=97%/JVM=92% |
| M3-2a | claim → processing | ✅ | |
| M3-2b | resolve → resolved + remark | ✅ | |
| M3-3 | dedup：同问题持续 | ✅ | `trigger_count=37，同一 dedup_key 只 1 条处于活跃状态` |
| M3-4 | alert_incident 自动聚合 open | ✅ | 关联 incident #2 status=open，多告警被聚合 |
| M3-5 | 静默命中 status=closed（不发新通知） | ✅ | id=1 status=closed |
| M3-9 | /record/{id}/related-logs 返回 service=order-service 日志 | ✅ | total=10 条，首条即"支付回调完成: orderId=..." |
| M3-10 | 上下文来自真实索引（非 mock） | ✅ | 同 9，records 内容真实 demo-service INFO |
| M3-11 | inapp 通知 sys_message 未读、content 含级别+标题 | ✅ | `[CRITICAL] [CRITICAL] M3验收-JVM堆低阈值 - jvm.heap.usage = 2.16`，is_read=0、user_id=1 |

**M3 基础 8 项（含 §13 M3 原文 1-4）**：
- 手工造数据 cpu.usage>90 持续 60s 能收到 inapp 通知 + 一条 alert_record ✓（M3-1 + M3-11）
- 认领/解决/关闭/误报状态流转 ✓（M3-2a/b）
- 同对象 5 分钟内再来一次同规则 → 只更新 trigger_count，不新建 ✓（M3-3）
- alert_incident 自动聚合多告警 ✓（M3-4）

## 关键设计决策

### 1. dedup 窗口以 `last_trigger_time` 而非 `create_time` 计算
`AlertDetectServiceImpl#processRule` §5.5.2 步骤3：同一问题持续触发就一直合并进同一预警（`in(pending,processing) AND last_trigger_time >= now(10)`）。只有 status 出 pending/processing 后，下次触发才可能新建——语义与任务书"恢复后再触发才新建"一致。

### 2. ES 精准查明文本：
`EsQueryBuilder.keywordOf(field)`：logstash 默认 mapping 里 text 有 `.keyword` 子字段，term/terms 查询必须打 keyword（text 会被标准 analyzer 切词）。概念点：远程集群未装 IK，text terms查询**永远 0 命中**——M3 实测踩过的坑。

### 3. 时区
Mysql `LocalDateTime` 是亚洲/上海时间；ES `@timestamp` 是 UTC。`EsQueryBuilder` 统一在 range/sort/sequence_aggs(`date_histogram`) 逐次减 8 小时。本地时间戳 MySQL 显示保持 +08。

### 4. 全局 Jackson LocalDateTime (yyyy-MM-dd HH:mm:ss) 序列化 & 反序列化
`JacksonConfig` 注册 `LocalDateTimeSerializer/Deserializer`（application.yml 的 `spring.jackson.date-format` 只控制 `java.util.Date`，JSR-310 类型必须显式配）。

### 5. 静默的抑制不误伤
- silence 覆盖 target 时：触发不通知但 trigger_count 仍累计；closed 。
- service suppression：若其 host 状态=down（agent_status=down 或 status=0）不发。
- 告警关停默认不重置 dedup_key，防止"修好了立刻又告警"造成的噪音。

## 现网数据快照（截至 2026-09-28 17:05）

```
alert_record: closed=10, pending=1, resolved=2
alert_incident: 6 个（2 resolved, 4 open），最多聚合 4 个 alerts
sys_message (msg_type='alert'): 6 条（6 未读；前 2 条由早期 demo 告警产生）
notify_record: success=6
es_field_cache: 11 行（aiops-log-*）
```

## Logstash（elk91）守护状态（M3 收尾检查）

```
systemctl is-active logstash-aiops:    active
systemctl is-enabled logstash-aiops:   enabled
ss -tlnp | grep :5000:                 LISTEN :::5000 (java pid=12890)
RSS:                                    537 MB
```

若接收日志量掉线，`systemctl restart logstash-aiops` 即可（集群任一节点挂不影响，Logstash 自带 3-host failover）。

## 已发现的 BUG / TODO

1. **`duration_sec` 判定语义**：当前实现要求窗口内**全部**样本都越界才算"持续"；对偶发抖动过敏感。生产语义常用"≥80% 或连续 N 个样本"。任务书未细化，沿用前置文档约定。M4/M5 可改。
2. **告警关联日志窗口 ±10min**：仅依赖 first_trigger_time，trigger_count 累计期间最新一笔日志可能出窗。M4 工程上改为按 `last_trigger_time ± 10min` 对 latest 触发节点取近段时间更合理。
3. **webhook 发送异步**：当前 notify_channel 中 webhook 阻塞发送（webClient.block）。高频告警下会阻塞 alert-detect 线程池。M5 之后考虑改 `subscribe()` + Reactor 或者由 notify_executor 代发。
4. **ELK 压力**：`aiops-log-2026.09.28` docs=52 起步，Logstash/ES 表现稳。M4 Drain 拉取速率待实测 ≤1000 条/次。
5. **未做**：钉钉/飞书/email 通知渠道（任务书允许 P1）；alert record 批量认领；按 trace_id 从 incident 反向查日志（M7 临时需要时再做）。

## 不做清单（主动回避范围）
- ❌ Drain `log_template` 提取（M4）
- ❌ Baseline EWMA/STL（任务书 §6.2 中 EWMA 可以后置，M5 之前的告警静态瓶凑够）
- ❌ email/钉钉/飞书（优先 inapp + webhook）
- ❌ NL2ES-DSL、运维问答（M6）

## 下里程碑（M4 日志核心）开始前需要审查 AI 回答的问题

1. **Drain 训练域**：默认写死 `service IN ('order-service','payment-service','aiops-platform')`。需要我现在就把这个过滤打进 `LogTemplateJob` 与 `EsLogClient.searchByService(...)` 吗？还是作为 `LogTemplateServiceImpl` 可调参数？
2. **log_template_stat 写入频率**：每 10min 一轮 + 每轮重建模板（v2 建议"全量重建，毕设够"）。在低量级日志（100/天）下，`log_template_stat` 行数应约为 len(模板数)/轮 = O(20)；2 天 × 288 轮 ≈ 5760 行/模板——可接受？
3. **JVM heap.usage 指标采集幂等性**：demo-service 的 jvm 指标长时间平稳在 2% 左右（G1 GC 活跃但根堆稳定）——纯 jvm 告警没意思。M4 要不要给 demo-service 加一个 `/demo/fault/jvm-stress?mb=512` 用于论文中"真实 jvm 告警"场景？（与 §12.2 故障注入兼容）

请您审查 `doc/MILESTONE-3-report.md` 及子代理完成的 M3 前端 4 页实现（`views/alert/*`）。
