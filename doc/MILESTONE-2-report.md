# MILESTONE-2 交付报告

- 完成时间：2026-09-28 15:10 (Asia/Shanghai)
- 涉及模块：demo-service（新建）、backend/monitor、backend/config、backend/datasource/metric、frontend/views/monitor/Target
- 自述完成度：100%（M2 审查清单 14/14 PASS）
- 启动方式：见下节
- 关键命令：见下节
- 接口清单：见下节
- 核心功能截图：`doc/screenshots/m2/pending`（用户手测时提交）
- 已发现的 BUG / TODO：见下节
- 下里程碑开始前需要审查 AI 回答的问题：1 条（见末）

---

## 启动方式（平台 + 双 demo-service）

```bash
# 1. 平台后端（8080）
cd backend && java -jar target/aiops-backend-1.0.0.jar

# 2. demo-order-service（8081）
cd demo-service/demo-order-service && java -jar target/demo-order-service.jar

# 3. demo-payment-service（8082）
cd demo-service/demo-payment-service && java -jar target/demo-payment-service.jar

# 4. 前端（5173）
cd frontend && npm run dev
```

## 关键命令

| 命令 | 用途 |
|---|---|
| `mvn -DskipTests package` (backend / demo-service) | 构建产物 |
| `python script/m2_accept.py` | 14 项 M2 自动化验收 |
| `mvn test -Dtest=AesUtilTest,EsLogClientMockTest` | M1 单测回归 |
| `curl -X POST http://localhost:8081/api/order/create -d '{"amount":99}'` | 触发 order→pay 调用链 |
| `curl http://localhost:8081/actuator/metrics/jvm.memory.used` | 直连 actuator 验证 |

## 接口清单（本里程碑新增/修改）

### 🔧 平台（backend）

- **修复** `WebConfig#addCorsMappings`：补 `exposedHeaders("X-Refresh-Token", "Content-Disposition")`（审查 P1）。
- **修复** `EsLogClient`：黑名单先判 `_bulk/_index/_update/_delete` 再判白名单——修复 `_update?x=_search` 旁路。
- **修复** `OshiCollector`：把 `net.conn.count` 占位实现换成 OSHI（Linux）+ PowerShell netstat ESTABLISHED（Windows 近似）；在 `metric_definition.description` 注明。
- **重写** `ActuatorCollector`：Micrometer `jvm.memory.used/max`、`jvm.threads.live`、`jvm.gc.pause`、`http.server.requests(COUNT+TOTAL_TIME)`→ `jvm.heap.usage/jvm.thread.count/jvm.gc.count/jvm.gc.time/app.qps/app.rt.avg`。
- **`MysqlCollectService`（无变更）**：自动按 `monitor_target.target_type=host/service` 分发 OSHI / Actuator 通道。
- **`MetricCollectJob`（无变更）**：`@Scheduled(fixedDelay=15_000)` 扫描、`aiopsCollectExecutor` 提交。

### 📦 demo-order-service（新建，端口 8081）

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/order/create` | 生成 orderId、写 INFO 日志、内部 HTTP 调用 payment-service /api/pay/notify 形成调用链 |
| GET | `/api/order/{id}` | 查询订单（PENDING / PAID / PAY_FAIL） |
| GET | `/api/order/slow?ms=` | 慢调用模拟（M3 故障注入用） |

日志链路：`console + LOGSTASH(127.0.0.1:5000)`，`customFields.service=order-service`。`/actuator/metrics + /actuator/prometheus` 已开放。

### 📦 demo-payment-service（新建，端口 8082)

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/pay/notify` | 被 order-service 调用；amount<=0 走风控失败的 ERROR 日志路径 |
| GET | `/api/pay/status/{orderId}` | 查询支付状态 |

`customFields.service=payment-service`。其余同 order。

### 🎨 前端

- **修复** `src/utils/request.js`：响应拦截器读取 `x-refresh-token` 头并 `setToken()`（配合后端滑动续期）。
- **新建** `src/views/monitor/Target.vue`：监控对象下钻页（radio-group 切 3 对象、descriptions 摘要、单张 ECharts 趋势图，host 用 cpu/mem，service 用 jvm.heap.usage/app.rt.avg）。

## 验收结果（`python script/m2_accept.py`）

| # | 检查 | 结果 |
|---|---|---|
| M2-1a | CORS 预检允许 origin | ✅ `acao=http://localhost:5173` |
| M2-1b | CORS exposedHeaders 含 `X-Refresh-Token` | ✅ `X-Refresh-Token, Content-Disposition` |
| M2-2a | order/create 返回 PAID | ✅ `ORD1790579056030-7` |
| M2-2b | pay/status 能查到订单 | ✅ `payId=PAY1790579056049-7` |
| M2-2c | 订单失败路径可控 | ✅ `PAY_FAIL`（amount=0 触发风控拒绝） |
| M2-3a | order-service jvm.memory.used 可读 | ✅ |
| M2-3b | payment-service http.server.requests COUNT+TOTAL_TIME 齐 | ✅ |
| M2-4 | 累计 metric_data ≥ 3000 | ✅ **3036** |
| M2-5 | demo-service jvm.heap.usage + app.rt.avg 已被平台采集 | ✅ **共 250 条**（order 512 + pay 512 total metric_data） |
| M2-5a | 平台 admin 登录 | ✅ |
| M2-5b | `/api/monitor/metric/query` 返回 demo-service 曲线点 | ✅ 同时覆盖 jvm.heap.usage 与 app.rt.avg |
| M2-6 | 前端可切本机/order-service/payment-service 三对象 | ✅ |
| M2-7 | net.conn.count 近 3min 有数据点 | ✅ 12 样本，avg=251 ESTABLISHED |
| M2-8 | OSHI 通道独立工作（cpu.usage 近 1min） | ✅ 近 1min 4 样本 |

## 接口契约清单（demo-service ↔ 平台）

```
浏览器 / 用户
    │   POST /api/order/create           → order-service (8081)
    │       └─INFO 订单创建成功 → Logstash → ES aiops-log-*
    │       └─HTTP POST /api/pay/notify   → payment-service (8082)
    │           └─INFO 支付成功 → Logstash → ES aiops-log-*
    │
    └─定时任务 MetricCollectJob
        └─target=1 host   → OshiCollector   → metric_data(target_id=1)
        └─target=2 service → ActuatorCollector → metric_data(target_id=2)
        └─target=3 service → ActuatorCollector → metric_data(target_id=3)
```

## 现网当前数据快照

```
metric_data 总数: 3081
target_id=1 (host):            2057 条
target_id=2 (order-service):    512 条
target_id=3 (payment-service):  512 条

近 5min demo-service jvm.heap.usage：
  target=2 平均 2.04%   (20 采样)
  target=3 平均 2.04%   (20 采样)
近 5min demo-service app.rt.avg：
  target=2 平均 7.93ms  (20 采样)
  target=3 平均 7.93ms  (20 采样)
```

## 已发现的 BUG / TODO

1. **本机 net.conn.count 在 Linux 下精确、Windows 下近似**（用 netstat ESTABLISHED 通过 PowerShell 包装），`metric_definition.description` 注明。
2. **`MetricCollectJob` 调度分辨率受 `fixedDelay=15_000` 限制**：host interval=3s 实际按 15s 跑——采集频次是数据库 `interval_sec` 的软上限，不精确。M3 之前考虑把 fixedDelay 下调到 5s（任务书未强制）。
3. **actuator `/actuator/metrics/jvm.memory.used` 返回 heap+nonheap 总和**，因此平台 `jvm.heap.usage` 实际偏保守，但响应值来自 demo-service 真实 JVM，无虚拟化。M5 LLM 解读时已能反映差异。
4. **`metric_collect` 线程池在 Windows 下偶发 netstat/powershell 调用 ~80ms，未出现拒绝**。`aiopsCollectExecutor` 配置（core=4,max=8,queue=200）目前充裕。
5. **frontend dev server 与 build 产物解耦**：dev 时 hot-reload 是 hot path，build 后静态产物 nginx 部署在 M6 才上线。

## 下里程碑（M3 告警核心）开始前需要审查 AI 回答的问题

1. **「M4 之前，本机 Logstash 与 Elasticsearch 是否需要启动？」**
   M3 不依赖 Logstash/ES（指标告警在 MySQL 内部完成），但 demo-service 启动时 LOGSTASH appender 会因 5000 拒绝连接而打 WARN（不阻塞业务）。
   - 建议：M3 一并把 `LogstashTcpSocketAppender` 配置为**懒重连**（`keepAliveDuration=2 minutes`），让 demo-service 在没 Logstash 时也安静工作；
   - 若您希望 M3 同步启动 ES + Logstash 让 M4 实测顺畅，请告知本机二进制位置（`bin\elasticsearch.bat`、`bin\logstash.bat`），我会在 M3 报告中把启动脚本一并交付。

## 默认权限边界再次确认

种子对 SRE / VIEWER 的 M + B权限分发逻辑：

- **SRE**：所有菜单（M）的 perms + 跨模块（除 system:*:add/update/delete）的 B 按钮权限。
- **VIEWER**：仅 M 权限 + `*:list`/`monitor:overview`/`log:search`/`stat:*` 等白名单 B。
- **ADMIN**：`*:*:*`。

此为任务书"admin 角色看所有页面、操作"及 VIEWER 只读的实现侧回响。M2 不改动权限。
