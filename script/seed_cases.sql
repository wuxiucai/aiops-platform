-- M5-11 相似案例种子数据（审查方手工指定的 3 条测试记录）
-- 字段顺序： title / symptom / root_cause / solution / tags / occurred_time / related_incident_id / source / embedding / embedding_status / create_time / deleted
INSERT INTO kb_fault_case (title, symptom, root_cause, solution, tags, occurred_time, related_incident_id, source, embedding, embedding_status, deleted)
VALUES
 ('CPU 高负载告警预警异常集中',
  'cpu.usage 突增 95%+，order-service / payment-service 有响应中并发瞬时请求加衡。',
  'JVM 进程 GC 频繁，频繁 Full GC 导致请求处理中断，影响用户体验。',
  '立即采用堆配置参数调优（增大年轻代+老年代比例），重启服务验证；长期：调优小池长效业务 bug 提交。',
  '["cpu", "jvm", "gc"]', '2026-09-01 09:30:00', NULL, 'manual', NULL, 'pending', 0),

 ('数据库连接失败排查指南',
  '支付回调 503 大量出现，数据库连接池"获取连接超时"日志频发，多条订单处于待支付状态。',
  'DB 连接池耗尽 核心根因：订单流程中串行执行的事务待长 retention，未随事务完成释放，以及 Simplify 的超长时间未释放。',
  '扩容连接池上限、优化慢 SQL、增加心跳检测；必要时降配业务耦合大幅度减少连接占用。',
  '["db", "connection_pool", "timeout"]', '2026-09-02 14:15:00', NULL, 'manual', NULL, 'pending', 0),

 ('慢调用平均响应上升采集急宕指标',
  'http.server.requests rt>3s 的响应分布集中上升，服务对象的整体呈现速度缓慢。',
  '下游服务响应慢 根本原因：seckill 应用中" slow 查询点"强耦合 项延迟 积累，像 Cold 瓶颈，期间服务的响应延迟均会随之上升。',
  '对该接口施加资源措施，从资源迟缓点和下游之间增加熔断降级机制；长期：重隔离此类慢调用。',
  '["latency", "rt", "downstream"]', '2026-09-03 18:45:00', NULL, 'manual', NULL, 'pending', 0)
ON DUPLICATE KEY UPDATE title=VALUES(title), symptom=VALUES(symptom);
