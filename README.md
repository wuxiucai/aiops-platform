# AIops Platform — 智能运维告警与日志分析平台

> 2027 届本科毕业设计 · 三峡大学科技学院

## 项目简介

一个 SpringBoot + Vue 的智能运维平台：

- **接入指标**（OSHI / Spring Actuator + Micrometer）
- **接入日志**（本机已有 ELK 集群，只读访问，Logback→Logstash→ES）
- **统计检测**（静态阈值 / 3-σ / EWMA / 动态基线 hour_bucket）
- **LLM 分析**（告警解读 / 根因分析 / 日志解读 / NL2ES-DSL；OpenAI 兼容协议 + Ollama）
- **治理闭环**（去重 → 静默 → 聚合 incident → 认领/解决/误报 → 沉淀案例 RAG）

## 核心算法亮点

| 算法 | 实现 |
|---|---|
| **Drain 日志模板提取** | 自研 Java 实现，纯算法 ~250 行；strom 树+相似度匹配；压缩原始日志 1000 倍 |
| **动态基线（hour_bucket）** | 按"星期×小时"分桶训练 mean±k·std，自动适应业务周期 |
| **两级根因排序** | 规则预筛（时间/拓扑/相关性/日志） + LLM 精判 |
| **NL2ES-DSL** | 自然语言 → Query DSL，三重安全校验（语法/字段白名单/危险操作拦截） |

## 仓库结构

```
backend/      Spring Boot 3 + JDK 17 + MyBatis-Plus + MySQL 8
frontend/     Vite + Vue 3 + Element Plus + ECharts
demo-service/ 两个独立 Spring Boot 演示服务（order / payment），产出真实日志
script/       DDL、init_data.sql、故障注入脚本、各里程碑自动化验收脚本
doc/          MILESTONE-N-report.md（各里程碑交付报告）
```

## 快速开始

```bash
# 0. 前置: ES 7 集群可用 + Logstash 监听 5000
# 1. 数据库
mysql -uroot -p<密码> < script/ddl.sql
mysql -uroot -p<密码> < script/init_data.sql
# 2. 后端
cd backend && mvn -DskipTests package && java -jar target/aiops-backend-1.0.0.jar
# 3. demo 服务（产生业务日志与 JVM 指标）
cd demo-service/demo-order-service   && java -jar target/demo-order-service.jar
cd demo-service/demo-payment-service && java -jar target/demo-payment-service.jar
# 4. 前端
cd frontend && npm install && npm run dev    # http://localhost:5173
```

默认账号：`admin / 123456`（开发用，生产请改 ENV `MYSQL_PWD` `JWT_SECRET` `AES_KEY`）

## 里程碑

- M0/M1 ✅ —— 工程骨架 + 系统管理 + JWT + AES + ES 客户端
- M2 ✅ —— demo-service + Actuator 通道 + 监控对象页
- M3 ✅ —— 告警规则 / 基线 / 记录 / 静默 / 通知 + incident 聚合
- M4 🚧 —— Drain + 日志检索 + AI 日志解读（进行中）

> **本仓库 Private，论文答辩通过（2027-04）前不公开。**
