# AIops Platform — 智能运维告警与日志分析平台

> **2027 届本科毕业设计** · 三峡大学科技学院 · 计算机科学与技术
> **指导教师**：郑晓东 · **评阅教师**：宋建萍

一个生产可用的 AIOps 平台：**接入指标 + 接入日志 + 接入大模型**，做"统计检测 + 智能分析 + 治理闭环"。

## 一、当前已落地的核心能力（14 项）

| 领域 | 能力 | 关键技术 |
|---|---|---|
| **监控接入** | host / agent / prometheus 三类数据源 | OSHI / Spring Actuator / Prom text format 自解析 |
| **Linux Agent** | Push 模式分布式监控 | 单 jar (~27MB) + agent_key 鉴权 + 心跳上报 |
| **ES 数据源** | 远程集群接入 + 字段自动探测 | WebClient 只读白名单 + 字段映射配置 |
| **日志检索** | 多条件 + 高亮 + 上下文 + 直方图 | Query DSL 构建器 + IK/standard 兼容 |
| **Drain 日志模板** | 自研解析树（约 250 行）| token 预处理 + 相似度匹配 + 模板 hash 幂等 |
| **日志异常** | 4 类规则型检测 | new_template / rare_template / spike / error_rate |
| **指标检测** | 静态 + 3σ + EWMA + 动态基线 | hour_bucket 桶化训练（E1 误报减 74.89%）|
| **告警降噪** | 三级去重 → 聚合 → incident | dedup 91.65% + aggregate 94.87% = 99.57% 总压缩 |
| **通知通道** | inapp + webhook + **SMTP 邮件** | JavaMailSender + AES 加密授权码 |
| **LLM 集成** | 7 场景统一适配 | OpenAI 兼容协议 + SiliconFlow 嵌入 + Schema 校验 + 兜底降级 |
| **嵌入相似案例 (RAG)** | 真实语义检索 | BGE-zh-v1.5 1024 维 vectors + 暴力余弦 |
| **NL2ES-DSL** | 自然语言 → Query DSL | 三重安全校验（语法/字段白名单/危险拦截）96% 合法率 |
| **自定义大盘** | 拖拽布局 + 分组管理 + 字段化 widget | HTML5 原生 drag + 12 列网格 + 6 种 widget + 可选 targetId/service 过滤器 |
| **知识库** | 文档上传 + 分块 + 嵌入 + 案例 | Longblob chunk + embedding_status 状态机 |

## 二、关键实验数据（论文 §6 引用）

| 实验 | 数据 |
|---|---|
| **E1 基线 vs 静态** | 115 vs 458（**74.89% 减少误报**） |
| **E2 告警压缩** | dedup 91.65% → aggregate 94.87% → **99.57% 总压缩** |
| **E3 Drain 压缩** | 1800 条 ERROR → 2 模板 = **99.89% 压缩率** |
| **E7 NL2DSL** | 30 题 **96%** 合法 + **100%** 危险拦截 |
| **E9 LLM 延迟** | avg **2.23s**（6 场景），268 次调用累计 **¥2.80** |

---

## 三、仓库结构

```
aiops-platform/
├── backend/                     Spring Boot 3.2.5 + JDK 17 + MyBatis-Plus + MySQL 8
│   └── src/main/java/com/aiops/
│       ├── module/{system,alert,log,monitor,llm,esa,incident,kb,notify,stat}/
│       ├── datasource/{metric,log}/   # OshiCollector / ActuatorCollector / PrometheusCollector / EsLogClient
│       └── schedule/                  # 7 个定时任务
├── frontend/                    Vite + Vue 3 + Element Plus + ECharts + Pinia
│   └── src/views/{monitor,log,alert,ai,kb,stat,system}/
├── demo-service/
│   ├── demo-order-service/      :8081 业务模拟 + Logback logstash-encoder
│   └── demo-payment-service/    :8082 同上
├── agent/                       Linux Agent 独立 jar (~27MB)
│   (Push 模式，agent_key 鉴权)
├── script/
│   ├── ddl.sql                  # 42 张表完整建库脚本
│   ├── init_data.sql            # 种子数据（角色/权限/指标/7 个 LLM 场景）
│   ├── fault_inject/            # 故障注入脚本（cpu-burn / jvm-stress / error-log / slow）
│   └── experiments/             # 论文 9 大实验真实数据 (E1~E9)
└── doc/
    ├── MILESTONE-N-report.md    # M0~M7 交付报告
    ├── THESIS_EVIDENCE.md       # 论文素材证据汇编
    └── A扩展-实施任务书.md       # A 方案扩展（Linux Agent + SMTP + 自定义大盘）
```

## 四、技术栈速查

| 层 | 选型 | 关键版本 |
|---|---|---|
| 后端框架 | Spring Boot | 3.2.5 |
| JVM | Temurin | 17 |
| 数据持久层 | MyBatis-Plus | 3.5.7 |
| 数据库 | MySQL | 8.0.x（utf8mb4） |
| ES 接入 | HTTP REST 直发 | ES 7.17.29（3 节点 cluster） |
| 鉴权 | Spring Security 6 + jjwt | 0.12.x |
| HTTP 客户端 | Spring WebClient | — （**未引入** spring-data-elasticsearch） |
| 主机指标 | OSHI | 6.x |
| 业务指标 | Spring Actuator + Micrometer | 随 Boot |
| LLM Chat | DeepSeek | deepseek-chat |
| LLM Embedding | SiliconFlow | BAAI/bge-large-zh-v1.5（1024 维） |
| 前端框架 | Vue 3 | ^3.5 |
| 构建工具 | Vite | v8.3.1 |
| UI 组件 | Element Plus | — |
| 图表 | ECharts | — |
| 状态 | Pinia | — |

## 五、快速开始

### 0. 前置

- JDK 17 + Maven 3.9+
- Node 18+（含 npm）
- MySQL 8.0.x
- ES 7.x 集群可达（默认读 `http://10.0.0.91:9200`，可在 `es_datasource` 表修改）
- Logstash 监听 5000（rdp 模式可选）

### 1. 数据库初始化

```bash
mysql -uroot -p<密码> < script/ddl.sql
mysql -uroot -p<密码> < script/init_data.sql
```

### 2. 一键启动（Windows，**包含清理僵尸 + 拉起 4 服务**）

```bat
script\m8_startup.bat
```

或：

```bash
# Linux/Mac 启动（不用 m8_startup.bat）
cd backend && mvn -DskipTests package && java -jar target/aiops-backend-1.0.0.jar &
cd demo-service/demo-order-service   && java -jar target/demo-order-service.jar &
cd demo-service/demo-payment-service && java -jar target/demo-payment-service.jar &
cd frontend && npm install && npm run dev
```

### 3. 访问

| 模块 | URL |
|---|---|
| 前端 | <http://localhost:5173> |
| Backend API | <http://localhost:8080/api> |
| 默认账号 | `admin` / `123456` |

## 六、Linux Agent（远程监控）

新建 agent 后下载 `aiops-agent.jar` 到目标 Linux 机器：

```bash
nohup java -jar aiops-agent.jar \
  --platform.url=http://<platform-host>:8080 \
  --agent.key=<agent_key> \
  --target.id=<target_id> \
  > aiops-agent.log 2>&1 &
```

Agent 每 30s 上报 cpu/mem/disk/net 指标，5 分钟一次心跳。

## 七、Prometheus 数据源

```bash
# 新建监控对象（target_type=prometheus + target_url）
POST /api/monitor/target
{
  "name": "prometheus-node-1",
  "targetType": "prometheus",
  "targetUrl": "http://x.x.x.x:9100/metrics"
}
```

平台每 30s 自推广指标文本格式解析（无 prom-client 依赖）：node_cpu_seconds_total / node_memory_MemAvailable_bytes / node_filesystem_avail_bytes 映射到 cpu.usage / mem.usage / disk.usage。

## 八、SMTP 邮件告警

新建邮箱配置 + 把它绑定到通知渠道：

| 字段 | 示例 |
|---|---|
| smtp_host | smtp.qq.com |
| smtp_port | 465 |
| username | your@qq.com |
| password_enc | AES 加密后的授权码（在 QQ 邮箱设置生成） |

然后给告警规则的 `notify_channels='["email"]'`，触发即.sendAlert。

## 九、自定义大盘

支持 6 种 widget（`stat_card / line_chart / top_alert / top_template / incident_list / metric_compare`），每种可 filter 可选：

- **stat_card** 可加 `targetId`（默认全平台，选了变单对象）
- **top_alert** 可加 `targetId`
- **top_template** 可加 `service`
- **incident_list** 可加 `targetId`
- line_chart/metric_compare 必须指定 targetId + metricKey

支持**拖拽布局（HTML5 原生 drag）+ 12 列网格 + 保存布局 + 多模板分组管理**。

## 十、安全与合规

- **ES 集群只读**：`EsLogClient.assertReadOnly` 在代码层拒绝 `_bulk / _index / _update / _delete` — 平台永不写 ES。平台自身日志走 Logback→Logstash→ES，新空间 `aiops-log-*` 与业务隔离。
- **密码保护**：
  - LLM api_key / 邮箱授权码 / ES 密码 → 全部 AES-128-ECB 加密落库
  - HTTP 响应永不返回明文
  - JWT HS512 + 滑动续期（X-Refresh-Token）
- **NL2ES-DSL 三重校验**：语法合法 + 字段白名单 + 危险操作拦截（递归禁 `script/_update/_delete`），size ≤ 100，强制时间范围
- **agent_key 鉴权**：Linux Agent 凭据 64 位随机串 + 不可枚举

## 十一、文档导航

| 文档 | 用途 |
|---|---|
| `doc/THESIS_EVIDENCE.md` | 论文素材证据汇编（**必读**，九实验数据 + 演示剧本） |
| `doc/A扩展-实施任务书.md` | A 方案扩展任务书 |
| `doc/MILESTONE-N-report.md` | M0~M7 各里程碑交付报告 |
| `script/experiments/E*.md` | 论文九实验数据文件 |

## 十二、关键设计哲学

**数据形态决定方法选择**（v2 §0.2）：
| 数据 | 处理方法 | 为什么 |
|---|---|---|
| 指标（数值时序）| 统计算法（3σ / EWMA / 动态基线）| LLM 算不准数值、上下文塞不下 |
| 日志（文本）| **★ Drain 压缩 + LLM 解读** | 文本理解与归纳正是 LLM 的强项 |
| 告警事件 | 规则引擎 + LLM 辅助 | 规则做确定性判断，LLM 做语义归纳 |

这是论文核心论点之一：**同一系统内两种数据用两套方法，不是技术拼凑，而是数据形态决定的必然选择**。

---

## 仓库状态说明

- **当前为 Private**，论文答辩通过（2027-04-10）前不公开
- 历史分支：`main`（活跃） / `feat/prometheus-client`（已合并）
- 提交数：**约 60 commits**（2026-09-28 ~ 2026-10-09，11 天）
- **代码量**：约 **7000 行 Java + 4500 行 Vue/JS**
- **mvn test**：**76/76 PASS**（M5-W6 后未再退化）
- **演示 demo**：4 服务可脚本一键拉起（`script/m8_startup.bat`）

---

**Deliverable**：基于大模型的智能运维告警与日志分析平台——可演示、可验收、可论文答辩。
