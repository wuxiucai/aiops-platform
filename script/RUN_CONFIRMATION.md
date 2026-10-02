# M0-M7 + A 方案 运行确认 (Lock 1)

以下命令在本机 （辅助命令机房 is OK) 单机测试一切矩不超过 60s 可全哦 OK.

## 启动顺序（5 服务 + 2 数据基础设施）

| # | 服务 / 数据点 | 启动命令 | 监控端口 | 期等等级 检查 |
|---|---|---|---|---|
| 1 | MySQL | 常态运行中 | 3306 | mysql -uroot -p123456 -e "SELECT 1" |
| 2 | Elasticsearch + Logstash | ELK 集群 | 9200 / 5000 | curl http://10.0.0.91:9200/_cluster/health (zifan-linux92 status green nodes=3) |
| 3 | **backend** | `cd backend && java -jar target/aiops-backend-1.0.0.jar` | 8080 | curl http://localhost:8080/actuator/health → HTTP 200 |
| 4 | demo-order-service | `cd demo-service/demo-order-service && java -jar target/demo-order-service.jar` | 8081 | curl http://localhost:8081/actuator/health → HTTP 200 |
| 5 | demo-payment-service | `cd demo-service/demo-payment-service && java -jar target/demo-payment-service.jar` | 8082 | curl http://localhost:8082/actuator/health → HTTP 200 |
| 6 | frontend (vite dev) | `cd frontend && npm run dev` | 5173 | 访问 http://localhost:5173 走登录 |
| 7 | aiops-agent (optional,Push 集成） | `cd agent && java -jar target/aiops-agent-1.0.0.jar --platform.url=http://127.0.0.1:8080 --agent.key=<64-char> --target.id=1` | (push only) | 控制台 see "[agent] starting scheduler collect=30s heartbeat=300s", metric_data 持续新增 |

## 一个包含依赖关系检查

```bash
# 检查 backend / ELK / MySQL
curl -s http://localhost:8080/actuator/health && echo OK-backend
curl -s http://10.0.0.91:9200/_cluster/health | head -c 100
mysql -uroot -p123456 aiops -e "SELECT COUNT(*) FROM llm_provider;"

#验证扩展 A:
curl -s -X POST http://localhost:8080/api/agent/heartbeat -H "X-Agent-Key: <valid-key>" | head -c 100
curl -s http://localhost:8080/api/dashboard/template/2 -H 'Authorization: Bearer <your-jwt>'
curl -s -X POST "http://localhost:8080/api/system/mail-config/1/test" \
  -H 'Authorization: Bearer <your-jwt>' \
  -H 'Content-Type: application/json' -d '{"to":"<any@qq.com>"}'
```

## 版本与检查点

- Backend jar size: `backend/target/aiops-backend-1.0.0.jar` = 54.6 MB
- Frontend bundle (`npm run build`): 2321 modules transformed, dist assets/pra graph 过每小 chunk 完整 layout config
- mvn test PASS: **101/101**（包含 NL2DSL DslSafetyValidatorTest 14 + LogDetect 4 + Mock 19等新增回归）
- DDL `script/ddl.sql` 与 live db 对齐（42 张业务表 + mysql system)

## 验收事项回顾 (M0-M7+A)

| 阶段 | 状态 |
|---|---|
| M0 工程初始化 | 已完成 |
| M1 系统管理/告警 | 已完成 |
| M2 demo-service 增量采集 | 已完成 |
| M3 告警 incident 闭环 | 已完成 |
| M4 Logstash + Drain + AI 解读 | 已完成 |
| M5 LLM 5 场景 + RAG + Mock + resiliency | 已完成 |
| M6 NL2DSL + DslSafetyValidator + 30 题 | 已完成 |
| M7 九实验素材 + data pack | 已完成 |
| **A 方案 3 扩展** | I Linux Agent push mode / II SMTP mail / III 自定义大盘 — 全部已 push |

## 服务状态最佳进行

- backend 8080 ✓ (running)
- order-service 8081 ✓
- payment-service 8082 ✓
- frontend 5173 ✓ (vue-grid-layout v3 dashboards)
- aiops-agent push working ✓ (agentId=1 metric accepted=5 持续一股）
- ELK 集群 3 nodes green ✓

## M02 (block_warnbits 需要授权码 —— 审云方将）

**注一章**:QQ 邮箱 smtp.qq.com /授权码需审查方提供一下是一个待定 pending。

## 备注

本运行确认基于本机 2026-10-02 21:00 的实测状态。

## 封板

M0-M7 主体 + A 方案 3 扩展已完整落地。
M02（QQ 邮箱授权码）与论文撰写由审查方接管。
