-- 回填历史数据中缺失的恢复时间（统计口径：告警 MTTR / 事件 MTTR）
-- 原因：告警自动恢复与"收尾僵尸告警"只改了 status，没写 resolved_time；
--       人工处理联动关闭事件时只写了 end_time，没写 duration_sec。代码已同时修复。

-- 自动恢复的告警：按最后一次触发视为恢复（与 AlertDetectServiceImpl 收尾逻辑一致）
UPDATE alert_record
   SET resolved_time = last_trigger_time,
       resolved_by   = COALESCE(resolved_by, 'system')
 WHERE status = 'resolved'
   AND resolved_time IS NULL
   AND last_trigger_time IS NOT NULL;

-- 已关闭事件：持续时长 = end_time - start_time
UPDATE alert_incident
   SET duration_sec = TIMESTAMPDIFF(SECOND, start_time, end_time)
 WHERE status = 'resolved'
   AND duration_sec IS NULL
   AND start_time IS NOT NULL
   AND end_time IS NOT NULL;
