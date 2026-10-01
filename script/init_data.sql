-- =====================================================================
-- 种子数据（§3.1 必须项）
-- 用户 admin/123456（BCrypt），角色 ADMIN/SRE/VIEWER，权限树覆盖 9 模块，
-- metric_definition 17 行，llm_prompt_template 7 场景，监控分组与目标
-- =====================================================================
USE aiops;

-- ---------- 用户 ----------
-- 密码 123456 的 BCrypt(strength=10)
INSERT INTO sys_user (id, username, password, nickname, status) VALUES
(1, 'admin', '$2a$10$.HBMYkjOqHZyng09L7G4o.QKtq831x3Ki/2Fp8j4hol9Ssis84nJi', '管理员', 1)
ON DUPLICATE KEY UPDATE username=username;

INSERT INTO sys_user (id, username, password, nickname, status) VALUES
(2, 'sre', '$2a$10$.HBMYkjOqHZyng09L7G4o.QKtq831x3Ki/2Fp8j4hol9Ssis84nJi', 'SRE工程师', 1),
(3, 'viewer', '$2a$10$.HBMYkjOqHZyng09L7G4o.QKtq831x3Ki/2Fp8j4hol9Ssis84nJi', '只读用户', 1)
ON DUPLICATE KEY UPDATE username=username;

-- ---------- 角色 ----------
INSERT INTO sys_role (id, role_code, role_name, description, status) VALUES
(1, 'ADMIN', '管理员', '全部权限', 1),
(2, 'SRE', '运维工程师', '业务操作权限', 1),
(3, 'VIEWER', '只读用户', '仅查看', 1)
ON DUPLICATE KEY UPDATE role_code=role_code;

INSERT INTO sys_user_role (user_id, role_id) VALUES (1,1),(2,2),(3,3)
ON DUPLICATE KEY UPDATE user_id=user_id;

-- ---------- 权限树（M菜单 + B按钮，覆盖 9 个模块） ----------
INSERT INTO sys_permission (id, parent_id, name, perm_type, path, component, perms, icon, sort) VALUES
-- 一级菜单
(100, 0, '监控中心', 'M', '/monitor', 'Monitor', NULL, 'Odometer', 1),
(200, 0, '日志中心', 'M', '/log', 'Log', NULL, 'Document', 2),
(300, 0, '告警中心', 'M', '/alert', 'Alert', NULL, 'Bell', 3),
(400, 0, 'AI能力', 'M', '/ai', 'Ai', NULL, 'MagicStick', 4),
(500, 0, '知识库', 'M', '/kb', 'Kb', NULL, 'Collection', 5),
(600, 0, '统计报表', 'M', '/stat', 'Stat', NULL, 'DataAnalysis', 6),
(700, 0, '系统管理', 'M', '/system', 'System', NULL, 'Setting', 7),
-- 监控中心二级
(101, 100, '监控大盘', 'M', '/monitor/dashboard', 'monitor/Dashboard', 'monitor:overview', 'DataBoard', 1),
(102, 100, '监控对象', 'M', '/monitor/target', 'monitor/Target', 'monitor:target:list', 'Monitor', 2),
(103, 100, '采集任务', 'M', '/monitor/collect', 'monitor/Collect', 'monitor:collect:list', 'Timer', 3),
(104, 100, '数据源管理', 'M', '/monitor/datasource', 'monitor/Datasource', 'es:datasource:list', 'Coin', 4),
-- 日志中心二级
(201, 200, '日志检索', 'M', '/log/search', 'log/Search', 'log:search', 'Search', 1),
(202, 200, '日志模板', 'M', '/log/template', 'log/Template', 'log:template:list', 'Files', 2),
(203, 200, '日志异常', 'M', '/log/anomaly', 'log/Anomaly', 'log:anomaly:list', 'WarningFilled', 3),
(204, 200, '日志检测规则', 'M', '/log/rule', 'log/Rule', 'log:rule:list', 'Filter', 4),
-- 告警中心二级
(301, 300, '告警规则', 'M', '/alert/rule', 'alert/Rule', 'alert:rule:list', 'BellFilled', 1),
(302, 300, '告警记录', 'M', '/alert/record', 'alert/Record', 'alert:record:list', 'List', 2),
(303, 300, '故障事件', 'M', '/alert/incident', 'alert/Incident', 'incident:list', 'AlarmClock', 3),
(304, 300, '静默管理', 'M', '/alert/silence', 'alert/Silence', 'alert:silence:list', 'Mute', 4),
-- AI 能力二级
(401, 400, '运维助手', 'M', '/ai/chat', 'ai/Chat', 'ai:chat', 'ChatDotRound', 1),
(402, 400, '日志智能分析', 'M', '/ai/nl2dsl', 'ai/Nl2dsl', 'ai:nl2dsl', 'Coordinate', 2),
(403, 400, '模型配置', 'M', '/ai/provider', 'ai/Provider', 'llm:provider:list', 'Cpu', 3),
(404, 400, '提示词管理', 'M', '/ai/prompt', 'ai/Prompt', 'llm:prompt:list', 'EditPen', 4),
-- 知识库二级
(501, 500, '文档管理', 'M', '/kb/document', 'kb/Document', 'kb:doc:list', 'Notebook', 1),
(502, 500, '故障案例', 'M', '/kb/case', 'kb/Case', 'kb:case:list', 'FirstAidKit', 2),
-- 统计报表二级
(601, 600, '效果评估', 'M', '/stat/effect', 'stat/Effect', 'stat:effect', 'TrendCharts', 1),
(602, 600, '成本统计', 'M', '/stat/llmcost', 'stat/LlmCost', 'stat:llmcost', 'Coin', 2),
-- 系统管理二级
(701, 700, '用户管理', 'M', '/system/user', 'system/User', 'system:user:list', 'User', 1),
(702, 700, '角色管理', 'M', '/system/role', 'system/Role', 'system:role:list', 'UserFilled', 2),
(703, 700, '权限管理', 'M', '/system/permission', 'system/Permission', 'system:permission:list', 'Key', 3),
(704, 700, '操作日志', 'M', '/system/operlog', 'system/OperLog', 'system:log:list', 'Memo', 4)
ON DUPLICATE KEY UPDATE name=VALUES(name);

-- 按钮权限（写操作，VIEWER 不授权）
INSERT INTO sys_permission (parent_id, name, perm_type, perms, sort) VALUES
(102, '监控对象写', 'B', 'monitor:target:update', 1),
(104, '数据源写', 'B', 'es:datasource:update', 1),
(201, '日志检索执行', 'B', 'log:search:exec', 1),
(202, '日志模板更新', 'B', 'log:template:update', 1),
(203, '日志异常处理', 'B', 'log:anomaly:handle', 1),
(204, '日志检测规则更新', 'B', 'log:rule:update', 1),
(301, '告警规则写', 'B', 'alert:rule:update', 1),
(302, '告警处理', 'B', 'alert:record:handle', 1),
(303, '事件处理', 'B', 'incident:handle', 1),
(304, '静默写', 'B', 'alert:silence:update', 1),
(403, '模型配置写', 'B', 'llm:provider:update', 1),
(701, '用户写', 'B', 'system:user:update', 1)
ON DUPLICATE KEY UPDATE name=VALUES(name);

-- Drain 模板提取演示/在线调参（§7.2 论文核心算法演示入口）
INSERT INTO sys_permission (parent_id, name, perm_type, perms, sort) VALUES
(202, 'Drain模板提取演示', 'B', 'log:drain:edit', 2),
(202, 'Drain参数在线调优', 'B', 'log:drain:params', 3)
ON DUPLICATE KEY UPDATE name=VALUES(name);

-- ---------- 角色-权限（ADMIN=全部；SRE=除系统管理写外全部；VIEWER=仅菜单+list） ----------
INSERT INTO sys_role_permission (role_id, permission_id)
SELECT 1, id FROM sys_permission ON DUPLICATE KEY UPDATE role_id=role_id;

-- SRE：全部菜单 + 除 system:*:update/delete/add 外的按钮
INSERT INTO sys_role_permission (role_id, permission_id)
SELECT 2, id FROM sys_permission
WHERE perm_type = 'M' OR perms NOT LIKE 'system:%'
ON DUPLICATE KEY UPDATE role_id=role_id;

-- VIEWER：仅菜单与 list 权限
INSERT INTO sys_role_permission (role_id, permission_id)
SELECT 3, id FROM sys_permission
WHERE perm_type = 'M' OR perms LIKE '%:list' OR perms IN ('monitor:overview','log:search','stat:effect','stat:llmcost','ai:chat')
ON DUPLICATE KEY UPDATE role_id=role_id;

-- ---------- 指标定义（v2 §2.5 清单 17 行） ----------
INSERT INTO metric_definition (metric_key, metric_name, unit, category, value_type, description, default_threshold) VALUES
('cpu.usage', 'CPU使用率', '%', 'cpu', 'gauge', '本机CPU整体使用率', 90.0000),
('cpu.load', 'CPU负载', '-', 'cpu', 'gauge', '系统平均负载', 4.0000),
('mem.usage', '内存使用率', '%', 'mem', 'gauge', '物理内存使用率', 90.0000),
('mem.used', '已用内存', 'MB', 'mem', 'gauge', '已用物理内存', NULL),
('swap.usage', 'Swap使用率', '%', 'mem', 'gauge', '交换分区使用率', 50.0000),
('disk.usage', '磁盘使用率', '%', 'disk', 'gauge', '分区最高使用率', 90.0000),
('disk.read.bytes', '磁盘读速率', 'KB/s', 'disk', 'gauge', '磁盘读取速率', NULL),
('disk.write.bytes', '磁盘写速率', 'KB/s', 'disk', 'gauge', '磁盘写入速率', NULL),
('net.rx.bytes', '网络接收速率', 'KB/s', 'net', 'gauge', '网卡接收速率', NULL),
('net.tx.bytes', '网络发送速率', 'KB/s', 'net', 'gauge', '网卡发送速率', NULL),
('net.conn.count', '网络连接数', '个', 'net', 'gauge', 'TCP连接数（Linux下精确；Windows下为netstat ESTABLISHED近似）', NULL),
('jvm.heap.usage', 'JVM堆使用率', '%', 'jvm', 'gauge', 'JVM堆内存使用率', 85.0000),
('jvm.gc.count', 'GC次数', '次', 'jvm', 'counter', 'GC累计次数', NULL),
('jvm.gc.time', 'GC耗时', 'ms', 'jvm', 'counter', 'GC累计耗时', NULL),
('jvm.thread.count', 'JVM线程数', '个', 'jvm', 'gauge', '活跃线程数', NULL),
('app.qps', '应用QPS', '次/s', 'business', 'gauge', '每秒请求数', NULL),
('app.rt.avg', '平均响应时间', 'ms', 'business', 'gauge', 'HTTP平均响应时间', 3000.0000),
('app.rt.p95', 'P95响应时间', 'ms', 'business', 'gauge', 'P95响应时间', NULL),
('app.error.rate', '错误率', '%', 'business', 'gauge', 'HTTP错误率', 5.0000),
('app.thread.active', '活跃线程数', '个', 'business', 'gauge', '应用活跃线程数', NULL)
ON DUPLICATE KEY UPDATE metric_name=VALUES(metric_name), description=VALUES(description);

-- ---------- LLM 提示词模板（§7 7 场景，version=1） ----------
-- M5 审查方约束：7 场景必须含 output_schema（JSON Schema），供 OutputSchemaValidator 用 JSON Schema 标准校验。
INSERT INTO llm_prompt_template (scene_code, scene_name, system_prompt, user_prompt_tpl, output_schema, version, enabled) VALUES
('alert_explain', '告警解读',
'你是资深SRE。基于告警信息和指标摘要，用简洁中文解释告警。严格输出JSON。',
'【告警】标题：${title} / 级别：${level} / 对象：${targetName}(${ip})
指标：${metricName} / 触发值：${triggerValue} / 阈值：${threshold}
首次触发：${firstTime} / 持续：${duration}分钟 / 累计${count}次
【指标摘要】当前：${current} 均值：${avg} 峰值：${max} 基线：[${lower},${upper}]
趋势：${trend} 突变点：${changePoint}
【历史同期】昨日同时段：${yesterdayAvg} 上周同日：${lastWeekAvg}
【相关日志摘要】${logTemplateSummary}

输出JSON：
{"summary":"一句话说明发生了什么（30字内）","severityAssessment":"高/中/低","possibleCauses":["..."],"logEvidence":"日志中支持该判断的证据（若无则说明）","impact":"可能影响的业务范围","suggestions":["..."],"needImmediateAction":true,"confidence":0.8}',
'{"type":"object","required":["summary","severityAssessment","possibleCauses","suggestions"],"properties":{"summary":{"type":"string","minLength":5},"severityAssessment":{"enum":["高","中","低"]},"possibleCauses":{"type":"array","minItems":1},"suggestions":{"type":"array","minItems":1},"confidence":{"type":"number","minimum":0,"maximum":1}},"additionalProperties":true}', 1, 1),
('nl2query', '监控问答NL2Query',
'你是数据查询助手，将自然语言转为结构化查询。可用指标：${metricList} 可用对象：${targetList}。严格输出JSON。',
'问题：${question}
输出JSON：
{"queryType":"metric_query|alert_query|incident_query|unknown","metricKeys":["cpu.usage"],"targetIds":[1],"startTime":"2026-09-27T15:00:00","endTime":"2026-09-27T16:00:00","aggregation":"avg","step":"1m","orderBy":"desc","limit":10,"explain":"查询意图说明"}
规则：只能用上述指标与对象，不得编造；无法理解则 queryType=unknown。', 1, 1),
('root_cause', '根因分析',
'你是资深SRE，擅长故障根因分析。给出按可能性排序的根因假设。分析原则：1.优先最早出现异常的组件（时间优先）2.优先拓扑层级更深的组件 3.优先近期有变更的组件 4.必须引用具体指标或日志作为证据，不得凭空推断。严格输出JSON。',
'【故障事件】标题：${title} 级别：${level} 开始：${startTime} 聚合告警数：${alertCount}
【并发告警】（按时间排序，格式：[时间] 对象|指标|触发值|描述）
${alertList}
【拓扑关系】${faultTarget} ├─宿主：${hostInfo} └─依赖：${dependencies}
【指标摘要】${metricsSummary}
【★日志证据】${logTemplateSummary}
【近期变更】${recentChanges}
【历史相似案例】相似度${score}%：${caseTitle} / 症状：${symptom} / 根因：${rootCause} / 方案：${solution}

输出JSON：
{"primaryCause":{"target":"","metric":"","reason":"","confidence":0.8,"evidence":["证据1（须引用具体数据或日志）"]},"otherCandidates":[{"target":"","metric":"","reason":"","confidence":0.5}],"reasoningChain":"推理链条","logEvidenceUsed":true,"suggestions":["..."],"needMoreInfo":["..."],"similarCaseReference":{"caseId":1,"howSimilar":"..."}}', 1, 1),
('log_explain', '日志解读',
'你是资深SRE。基于日志模板统计，分析日志中反映的问题并给出结论。严格输出JSON。',
'【时间范围】${timeStart} ~ ${timeEnd}
【日志概况】总量：${logCount} 模板数：${templateCount}
级别分布：${levelDistribution}
【模板统计】${templateStats}
【新增模板】${newTemplates}
【关联告警】${relatedAlerts}

输出JSON：
{"summary":"日志整体情况一句话总结（50字内）","abnormalTemplates":[{"templateId":1,"template":"","issue":"问题说明","severity":"高/中/低","possibleCause":"","count":0}],"correlationAnalysis":"多个异常模板之间的关联性分析","rootCauseHint":"从日志看最可能的根因方向","impact":"影响评估","suggestions":["..."],"confidence":0.8}', 1, 1),
('nl2es_dsl', '日志查询 DSL 生成',
'你是日志查询助手。将自然语言转换为 Elasticsearch Query DSL。ES版本：7.x。可用索引模式：aiops-log-*。字段映射：时间=@timestamp, 内容=message, 级别=level, 服务=service, traceId=traceId。级别取值：ERROR / WARN / INFO / DEBUG。严格输出 JSON，不要 markdown 围栏。',
'把下列自然语言查询转换为 ES Query DSL JSON：
问题：${question}
可用时间窗：${timeHint}
可用字段：${fieldInfo}
可用服务名集合：${serviceList}

输出 JSON 必须是完整的 query 对象（从 query 根开始），结构：
{
  "query": {...bool / match / term / range 任意合法 ES 7 DSL...},
  "sort": [{"@timestamp":"desc"}],
  "size": ≤50 的整数,
  "track_total_hits": true,
  "aggs": {"by_level":{"terms":{"field":"level.keyword","size":10}},"by_service":{"terms":{"field":"service.keyword","size":10}}} （可选）
}

规则：
1. timeFilter 恒为 {range:{"@timestamp":{"gte":"...","lte":"..."}}}，如无明确时间 默认近 24 小时
2. 只能使用上述字段，不要引入 script / painless / _update / _delete
3. size ≤ 100
4. 服务名仅在 serviceList 中选择
5. 输出不带解释，仅 JSON 对象', 2, 1),
('nl_answer', '查询结果归纳',
'你是运维助手。基于给定的日志查询结果，用简洁中文回答用户的原始问题。',
'用户问题：${question}
查询结果摘要：${resultSummary}

用中文总结回答，指出关键信息。', 1, 1),
('report', '故障报告',
'你是资深SRE。基于故障事件全部信息输出一份结构清晰的 Markdown 故障报告。',
'【故障事件】${incidentInfo}
【告警列表】${alertList}
【根因分析结论】${rootCause}
【时间线】${timeline}

输出 Markdown，目录固定为：
## 一、故障概述 ## 二、影响范围 ## 三、时间线
## 四、根因分析 ## 五、处置过程 ## 六、改进措施', 1, 1)
ON DUPLICATE KEY UPDATE scene_name=VALUES(scene_name);

-- ---------- ES 数据源（M3 前置：指向远程 3 节点集群 10.0.0.91/92/93，无认证） ----------
INSERT INTO es_datasource (id, name, es_scheme, es_host, es_port, status, is_default, test_result, remark) VALUES
(1, 'RemoteCluster', 'http', '10.0.0.91', 9200, 1, 1, NULL, 'remote-cluster / no-auth / no-IK-plugin (zifan-linux92, 3 nodes green)')
ON DUPLICATE KEY UPDATE es_host=VALUES(es_host), es_port=VALUES(es_port), remark=VALUES(remark);

-- es_index_config：用 Logstash 自定义字段映射（@timestamp/message/level/service）
INSERT INTO es_index_config (id, datasource_id, name, index_pattern, time_field, message_field, level_field, service_field, trace_id_field, default_time_range_hours, enabled) VALUES
(1, 1, 'aiops-log', 'aiops-log-*', '@timestamp', 'message', 'level', 'service', 'traceId', 24, 1)
ON DUPLICATE KEY UPDATE index_pattern=VALUES(index_pattern);

-- ---------- 监控分组与目标（§3.1 第4/5条） ----------
INSERT INTO monitor_group (id, name, parent_id, description, sort) VALUES
(1, '本机', 0, '开发机本机', 1),
(2, '演示服务', 0, '§12 演示微服务', 2)
ON DUPLICATE KEY UPDATE name=VALUES(name);

INSERT INTO monitor_target (id, name, target_type, ip, port, os, group_id, status, log_service_name, description) VALUES
(1, '开发本机', 'host', '127.0.0.1', NULL, 'Windows', 1, 1, 'aiops-platform', '本机 OSHI 采集'),
(2, 'order-service', 'service', '127.0.0.1', 8081, 'Windows', 2, 1, 'order-service', '演示订单服务'),
(3, 'payment-service', 'service', '127.0.0.1', 8082, 'Windows', 2, 1, 'payment-service', '演示支付服务')
ON DUPLICATE KEY UPDATE name=VALUES(name);

-- ========== M5 补充约束 (1)：7 场景 llm_prompt_template 全量替换（含 output_schema） ==========
-- 审查方要求：alert_explain / root_cause / log_explain / nl2query / log_summary / report / similar_case_input
-- REPLACE INTO 由 uk_scene_code（M0 已建）触发，覆盖旧 INSERT 的 5 行 + 重新导向 nl_answer/nl2es_dsl 两个 legacy 场景。
-- 注意： schema validator 用 JSON Schema 标准，所以 output_schema 必须是 {"required":[],"properties":{}} 完整格式。

REPLACE INTO llm_prompt_template (id, scene_code, scene_name, system_prompt, user_prompt_tpl, output_schema, version, enabled, create_time, update_time, deleted) VALUES
(1, 'alert_explain', '告警解读',
 '你是资深SRE。基于告警信息和指标摘要，用简洁中文解释告警。严格输出JSON。',
 '【告警】标题：${title} / 级别：${level} / 对象：${targetName}(${ip})
指标：${metricName} / 触发值：${triggerValue} / 阈值：${threshold}
首次触发：${firstTime} / 持续：${duration}分钟 / 累计${count}次
【指标摘要】当前：${current} 均值：${avg} 峰值：${max} 基线：[${lower},${upper}]
趋势：${trend} 突变点：${changePoint}
【历史同期】昨日同时段：${yesterdayAvg} 上周同日：${lastWeekAvg}
【相关日志摘要】${logTemplateSummary}

输出JSON（字段必填 summary/severityAssessment/possibleCauses/suggestions/confidence）：
{"summary":"一句话说明发生了什么（30字内）","severityAssessment":"高/中/低","possibleCauses":["..."],"logEvidence":"日志中支持该判断的证据","impact":"可能影响的业务范围","suggestions":["..."],"needImmediateAction":true,"confidence":0.8}',
 '{"type":"object","required":["summary","severityAssessment","possibleCauses","suggestions"],"properties":{"summary":{"type":"string","minLength":5},"severityAssessment":{"enum":["高","中","低"]},"possibleCauses":{"type":"array","minItems":1},"suggestions":{"type":"array","minItems":1},"confidence":{"type":"number","minimum":0,"maximum":1}},"additionalProperties":true}',
 2, 1, NOW(), NOW(), 0),
(3, 'root_cause', '根因分析',
 '你是资深SRE，擅长故障根因分析。给出按可能性排序的根因假设。分析原则：1.优先最早出现异常的组件（时间优先）2.优先拓扑层级更深的组件 3.优先近期有变更的组件 4.必须引用具体指标或日志作为证据。严格输出JSON。',
 '【故障事件】标题：${title} 级别：${level} 开始：${startTime} 聚合告警数：${alertCount}
【并发告警】（按时间排序，格式：[时间] 对象|指标|触发值|描述）
${alertList}
【拓扑关系】${faultTarget} ├─宿主：${hostInfo} └─依赖：${dependencies}
【指标摘要】${metricsSummary}
【★日志证据】${logTemplateSummary}
【近期变更】${recentChanges}
【历史相似案例】相似度${score}%：${caseTitle} / 症状：${symptom} / 根因：${rootCause} / 方案：${solution}

输出JSON（必填 rootCauses/timeline/confidence）：
{"primaryCause":{"target":"","metric":"","reason":"","confidence":0.8,"evidence":["证据1"]},"otherCandidates":[{"target":"","metric":"","reason":"","confidence":0.5}],"reasoningChain":"推理链条","logEvidenceUsed":true,"timeline":["..."],"suggestions":["..."],"needMoreInfo":["..."],"similarCaseReference":{"caseId":1,"howSimilar":"..."},"confidence":0.7}',
 '{"type":"object","required":["rootCauses","timeline","confidence"],"properties":{"rootCauses":{"type":"array","minItems":1},"timeline":{"type":"array"},"confidence":{"type":"number","minimum":0,"maximum":1}},"additionalProperties":true}',
 2, 1, NOW(), NOW(), 0),
(4, 'log_explain', '日志解读',
 '你是资深SRE。你正在分析一个日志模板的异常上下文。请严格按照以下 schema 输出合法 JSON，不要输出 markdown 围栏。R"output 必须严格满足：summary/likelyCause/suggestion 都是非空字符串；confidence 是 [0,1] 的数字。',
 '分析这个日志模板的异常上下文：

${inputSummary}

请只返回严格的 JSON 对象，无任何前置/后续文本：
{"summary":"<20-80字，这个日志反映了什么>","likelyCause":"<最可能的根因>","suggestion":"<具体的处置建议>","confidence":0.0~1.0}',
 '{"type":"object","required":["summary","likelyCause","suggestion","confidence"],"properties":{"summary":{"type":"string","minLength":10},"likelyCause":{"type":"string","minLength":5},"suggestion":{"type":"string","minLength":5},"confidence":{"type":"number","minimum":0,"maximum":1}},"additionalProperties":true}',
 2, 1, NOW(), NOW(), 0),
(2, 'nl2query', '监控问答NL2Query',
 '你是数据查询助手，将自然语言转为结构化查询。可用指标：${metricList} 可用对象：${targetList}。严格输出JSON。',
 '问题：${question}
输出JSON（必填 queryType/targetIds/timeRange）：
{"queryType":"metric_query|alert_query|incident_query|unknown","targetIds":[1],"metricKeys":["cpu.usage"],"timeRange":{"start":"2026-09-27T15:00:00","end":"2026-09-27T16:00:00"},"aggregation":"avg","step":"1m","orderBy":"desc","limit":10,"explain":"查询意图说明"}
规则：只能用上述指标与对象，不得编造；无法理解则 queryType=unknown。',
 '{"type":"object","required":["queryType","targetIds","timeRange"],"properties":{"queryType":{"enum":["metric_query","alert_query","incident_query","unknown"]},"targetIds":{"type":"array"},"timeRange":{"type":"object"},"metricKeys":{"type":"array"}}, "additionalProperties":true}',
 2, 1, NOW(), NOW(), 0),
(5, 'log_summary', '日志摘要归纳',
 '你是运维助手。基于给定的日志查询结果，用简洁中文回答用户的原始问题。严格按 JSON 输出。',
 '用户问题：${question}
查询结果摘要：${resultSummary}

输出 JSON（必填 summary/keyFindings/confidence）：
{"summary":"对查询结果的一句话总结","keyFindings":["关键发现 1","关键发现 2"],"confidence":0.8}',
 '{"type":"object","required":["summary","keyFindings","confidence"],"properties":{"summary":{"type":"string","minLength":20},"keyFindings":{"type":"array","minItems":1},"confidence":{"type":"number","minimum":0,"maximum":1}},"additionalProperties":true}',
 2, 1, NOW(), NOW(), 0),
(7, 'report', '故障报告',
 '你是资深SRE。基于故障事件全部信息输出一份结构清晰的 Markdown 故障报告。',
 '【故障事件】${incidentInfo}
【告警列表】${alertList}
【根因分析结论】${rootCause}
【时间线】${timeline}

输出 Markdown，目录固定为：
## 一、故障概述 ## 二、影响范围 ## 三、时间线 ## 四、根因分析 ## 五、恢复过程 ## 六、改进措施',
 '{"type":"object","required":["markdown"],"properties":{"markdown":{"type":"string","minLength":100}},"additionalProperties":true}',
 2, 1, NOW(), NOW(), 0),
(6, 'similar_case_input', '相似案例推断',
 '你是运维知识库专家。给定当前告警上下文与历史案例库候选，评估最相似的案例并说明依据。严格输出 JSON。',
 '当前告警上下文：
${currentContext}

历史案例库候选：
${candidateCases}

输出 JSON（必填 matchedCaseId/similarity/reason）：
{"matchedCaseId":1,"similarity":0.85,"reason":"匹配的关键词与指标特征说明"}',
 '{"type":"object","required":["matchedCaseId","similarity","reason"],"properties":{"matchedCaseId":{"type":"integer"},"similarity":{"type":"number","minimum":0,"maximum":1},"reason":{"type":"string","minLength":10}},"additionalProperties":true}',
 2, 1, NOW(), NOW(), 0);

-- 默认采集任务：本机核心指标 30s（覆盖尽量多的 metric，便于累计 3000 条 metric_data）
INSERT INTO collect_task (target_id, metric_keys, interval_sec, status) VALUES
(1, '["cpu.usage","cpu.load","mem.usage","mem.used","swap.usage","disk.usage","net.conn.count"]', 30, 1)
ON DUPLICATE KEY UPDATE target_id=target_id;

-- 演示服务通过 Actuator 通道采集（jvm.heap.usage / app.rt.avg 等），30s 周期
INSERT INTO collect_task (target_id, metric_keys, interval_sec, status) VALUES
(2, '["jvm.heap.usage","jvm.thread.count","app.qps","app.rt.avg"]', 30, 1),
(3, '["jvm.heap.usage","jvm.thread.count","app.qps","app.rt.avg"]', 30, 1)
ON DUPLICATE KEY UPDATE target_id=target_id;
