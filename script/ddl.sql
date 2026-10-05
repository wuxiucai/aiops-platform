-- =====================================================================
-- 智能运维告警与日志分析平台 — 完整建库脚本（与《实施任务书》§3 逐表一致）
-- 库名 aiops，字符集 utf8mb4 / utf8mb4_general_ci，逻辑删除字段 deleted
-- =====================================================================
CREATE DATABASE IF NOT EXISTS aiops DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
USE aiops;

-- ============ A. 系统域 ============
CREATE TABLE sys_user (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  username VARCHAR(64) NOT NULL UNIQUE,
  password VARCHAR(128) NOT NULL COMMENT 'BCrypt',
  nickname VARCHAR(64), email VARCHAR(128), phone VARCHAR(32), avatar VARCHAR(512),
  status TINYINT NOT NULL DEFAULT 1 COMMENT '0禁用1启用',
  last_login_time DATETIME,
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted TINYINT NOT NULL DEFAULT 0
);

CREATE TABLE sys_role (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  role_code VARCHAR(32) NOT NULL UNIQUE,   -- ADMIN | SRE | VIEWER
  role_name VARCHAR(64), description VARCHAR(255), status TINYINT DEFAULT 1,
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted TINYINT NOT NULL DEFAULT 0
);

CREATE TABLE sys_user_role (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT NOT NULL, role_id BIGINT NOT NULL,
  UNIQUE KEY uk_ur (user_id, role_id)
);

CREATE TABLE sys_permission (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  parent_id BIGINT NOT NULL DEFAULT 0,
  name VARCHAR(64) NOT NULL,
  perm_type CHAR(1) NOT NULL COMMENT 'M菜单 B按钮',
  path VARCHAR(255), component VARCHAR(255), perms VARCHAR(128),
  icon VARCHAR(64), sort INT DEFAULT 0, status TINYINT DEFAULT 1,
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  deleted TINYINT NOT NULL DEFAULT 0
);

CREATE TABLE sys_role_permission (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  role_id BIGINT NOT NULL, permission_id BIGINT NOT NULL,
  UNIQUE KEY uk_rp (role_id, permission_id)
);

CREATE TABLE sys_oper_log (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT, username VARCHAR(64),
  module VARCHAR(64), operation VARCHAR(128), method VARCHAR(16), request_uri VARCHAR(512),
  params TEXT, ip VARCHAR(64), status TINYINT, error_msg TEXT, cost_time BIGINT,
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  KEY idx_ct (create_time)
);

-- ============ B. LLM 配置域 ============
CREATE TABLE llm_provider (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  name VARCHAR(64) NOT NULL,
  provider_type VARCHAR(32) NOT NULL COMMENT 'openai_compatible|ollama|custom',
  base_url VARCHAR(512) NOT NULL,
  api_key VARCHAR(1024) COMMENT 'AES加密存储',
  model_name VARCHAR(128) NOT NULL,
  embedding_model VARCHAR(128),
  temperature DECIMAL(3,2) DEFAULT 0.30,
  max_tokens INT DEFAULT 4096,
  timeout_ms INT DEFAULT 60000,
  is_default TINYINT DEFAULT 0,
  status TINYINT DEFAULT 1, remark VARCHAR(255),
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted TINYINT NOT NULL DEFAULT 0
);

CREATE TABLE llm_call_log (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  provider_id BIGINT, scene_code VARCHAR(64) NOT NULL, ref_id BIGINT,
  prompt_tokens INT DEFAULT 0, completion_tokens INT DEFAULT 0, total_tokens INT DEFAULT 0,
  latency_ms BIGINT, status VARCHAR(16) COMMENT 'success|fail|timeout', error_msg TEXT,
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  KEY idx_scene_time (scene_code, create_time)
);

CREATE TABLE llm_prompt_template (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  scene_code VARCHAR(64) NOT NULL, scene_name VARCHAR(128),
  system_prompt TEXT, user_prompt_tpl TEXT, output_schema JSON,
  version INT DEFAULT 1, enabled TINYINT DEFAULT 1,
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_scene_ver (scene_code, version)
);

-- ============ C. ES 数据源域 ============
CREATE TABLE es_datasource (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  name VARCHAR(64) NOT NULL,
  es_scheme VARCHAR(8) DEFAULT 'http',
  es_host VARCHAR(128) NOT NULL, es_port INT NOT NULL DEFAULT 9200,
  username VARCHAR(128), password_enc VARCHAR(1024), api_key VARCHAR(1024),
  es_version VARCHAR(32), cluster_name VARCHAR(128),
  status TINYINT DEFAULT 1, is_default TINYINT DEFAULT 0,
  last_test_time DATETIME, test_result VARCHAR(255), remark VARCHAR(255),
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted TINYINT NOT NULL DEFAULT 0
);

CREATE TABLE es_index_config (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  datasource_id BIGINT NOT NULL,
  name VARCHAR(64) NOT NULL,
  index_pattern VARCHAR(255) NOT NULL COMMENT '如 log-*',
  time_field VARCHAR(64) DEFAULT '@timestamp',
  message_field VARCHAR(64) DEFAULT 'message',
  level_field VARCHAR(64) DEFAULT 'level',
  service_field VARCHAR(64) DEFAULT 'service',
  trace_id_field VARCHAR(64) DEFAULT 'traceId',
  level_mapping JSON COMMENT '{"ERROR":["ERROR","err","SEVERE"]}',
  default_time_range_hours INT DEFAULT 24,
  enabled TINYINT DEFAULT 1,
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted TINYINT NOT NULL DEFAULT 0,
  KEY idx_ds (datasource_id)
);

CREATE TABLE es_field_cache (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  datasource_id BIGINT NOT NULL, index_pattern VARCHAR(255),
  field_name VARCHAR(128), field_type VARCHAR(32), sample_value VARCHAR(1024),
  last_scan_time DATETIME,
  KEY idx_ds_idx (datasource_id, index_pattern)
);

-- ============ D. 日志分析域 ============
CREATE TABLE log_template (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  datasource_id BIGINT NOT NULL, index_config_id BIGINT NOT NULL,
  cluster_id INT COMMENT 'Drain 内部 cluster id，仅本轮计算有效',
  template_text TEXT NOT NULL,
  token_count INT,
  template_hash VARCHAR(64) NOT NULL,
  first_seen DATETIME, last_seen DATETIME,
  total_count BIGINT DEFAULT 0,
  last_window_count INT DEFAULT 0,
  sample_log TEXT,
  variables JSON,
  level VARCHAR(16), service VARCHAR(128),
  status TINYINT DEFAULT 0 COMMENT '0正常 1已关注 2已忽略',
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_hash (datasource_id, template_hash)
);

CREATE TABLE log_template_stat (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  template_id BIGINT NOT NULL,
  stat_time DATETIME NOT NULL,
  window_count INT NOT NULL,
  KEY idx_tpl_time (template_id, stat_time)
);

CREATE TABLE log_detect_rule (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  datasource_id BIGINT NOT NULL, index_config_id BIGINT NOT NULL,
  name VARCHAR(128) NOT NULL,
  rule_type VARCHAR(32) NOT NULL COMMENT 'new_template|rare_template|spike|error_rate',
  params JSON, level VARCHAR(16) DEFAULT 'WARN', enabled TINYINT DEFAULT 1,
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted TINYINT NOT NULL DEFAULT 0
);

CREATE TABLE log_anomaly (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  rule_id BIGINT, template_id BIGINT, datasource_id BIGINT,
  anomaly_type VARCHAR(32), title VARCHAR(255), description TEXT, level VARCHAR(16),
  trigger_value DECIMAL(20,4), baseline_value DECIMAL(20,4),
  status VARCHAR(16) DEFAULT 'pending' COMMENT 'pending|processing|resolved|false_positive',
  first_time DATETIME, last_time DATETIME, count INT DEFAULT 1,
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  KEY idx_status_time (status, create_time)
);

CREATE TABLE log_analysis_record (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  datasource_id BIGINT, index_config_id BIGINT,
  scene_code VARCHAR(64), ref_id BIGINT,
  time_start DATETIME, time_end DATETIME,
  log_count INT, template_count INT,
  input_summary TEXT COMMENT '送给 LLM 的上下文，论文展示用',
  result TEXT COMMENT 'LLM 输出 JSON',
  token_cost INT, latency_ms BIGINT, status VARCHAR(16),
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE nl_query_log (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT,
  question TEXT NOT NULL, generated_dsl TEXT,
  validated TINYINT DEFAULT 0, executed TINYINT DEFAULT 0, hit_count INT,
  answer TEXT, retry_count INT DEFAULT 0, latency_ms BIGINT,
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP
);

-- ============ E. 监控域 ============
CREATE TABLE monitor_group (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  name VARCHAR(64) NOT NULL, parent_id BIGINT DEFAULT 0, description VARCHAR(255), sort INT DEFAULT 0,
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP, deleted TINYINT NOT NULL DEFAULT 0
);

CREATE TABLE monitor_target (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  name VARCHAR(64) NOT NULL,
  target_type VARCHAR(16) NOT NULL COMMENT 'host|service',
  ip VARCHAR(64), port INT, os VARCHAR(64),
  group_id BIGINT, tags JSON, owner VARCHAR(64), description VARCHAR(255),
  agent_status VARCHAR(16) DEFAULT 'unknown', last_heartbeat DATETIME,
  status TINYINT DEFAULT 1,
  log_service_name VARCHAR(128) COMMENT '打通指标与日志：告警时按它去 ES 查日志',
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted TINYINT NOT NULL DEFAULT 0
);

CREATE TABLE metric_definition (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  metric_key VARCHAR(64) NOT NULL UNIQUE,
  metric_name VARCHAR(64) NOT NULL, unit VARCHAR(16), category VARCHAR(32),
  value_type VARCHAR(16) DEFAULT 'gauge', description VARCHAR(255), default_threshold DECIMAL(20,4),
  deleted TINYINT NOT NULL DEFAULT 0
);

CREATE TABLE metric_data (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  target_id BIGINT NOT NULL,
  metric_key VARCHAR(64) NOT NULL,
  metric_value DECIMAL(20,4) NOT NULL,
  collect_time DATETIME NOT NULL,
  KEY idx_tmt (target_id, metric_key, collect_time)
);

CREATE TABLE collect_task (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  target_id BIGINT NOT NULL,
  metric_keys JSON NOT NULL, interval_sec INT NOT NULL DEFAULT 30,
  status TINYINT DEFAULT 1,
  last_run_time DATETIME, last_cost_ms BIGINT, fail_count INT DEFAULT 0,
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted TINYINT NOT NULL DEFAULT 0
);

-- ============ F. 告警域 ============
CREATE TABLE alert_rule (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  name VARCHAR(128) NOT NULL,
  target_id BIGINT, group_id BIGINT,
  metric_key VARCHAR(64) NOT NULL,
  rule_type VARCHAR(16) NOT NULL COMMENT 'static|baseline|ratio|trend',
  operator VARCHAR(8) NOT NULL COMMENT 'gt|gte|lt|lte|outside|inside',
  threshold DECIMAL(20,4), duration_sec INT DEFAULT 60,
  level VARCHAR(16) DEFAULT 'WARN',
  sensitivity DECIMAL(3,2) DEFAULT 0.80,
  baseline_config JSON,
  silence_window JSON, notify_channels JSON,
  enabled TINYINT DEFAULT 1, creator VARCHAR(64),
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted TINYINT NOT NULL DEFAULT 0
);

CREATE TABLE baseline_model (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  rule_id BIGINT NOT NULL, target_id BIGINT NOT NULL, metric_key VARCHAR(64) NOT NULL,
  model_type VARCHAR(16) NOT NULL COMMENT '3sigma|ewma|stl|hour_bucket',
  params JSON,
  bucket_key VARCHAR(16) COMMENT '如 MON-14',
  upper_bound DECIMAL(20,4), lower_bound DECIMAL(20,4),
  mean_value DECIMAL(20,4), std_value DECIMAL(20,4),
  sample_count INT DEFAULT 0, train_start DATETIME, train_end DATETIME,
  last_train_time DATETIME, status TINYINT DEFAULT 1,
  KEY idx_rtm (rule_id, target_id, metric_key)
);

CREATE TABLE alert_record (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  rule_id BIGINT NOT NULL, target_id BIGINT NOT NULL,
  metric_key VARCHAR(64) NOT NULL, level VARCHAR(16),
  title VARCHAR(255) NOT NULL, content TEXT,
  trigger_value DECIMAL(20,4), threshold_value DECIMAL(20,4),
  baseline_upper DECIMAL(20,4), baseline_lower DECIMAL(20,4),
  status VARCHAR(16) DEFAULT 'pending' COMMENT 'pending|processing|resolved|closed|false_positive',
  dedup_key VARCHAR(255) NOT NULL,
  first_trigger_time DATETIME, last_trigger_time DATETIME, trigger_count INT DEFAULT 1,
  claimed_by VARCHAR(64), claimed_time DATETIME,
  resolved_by VARCHAR(64), resolved_time DATETIME, handle_remark TEXT,
  incident_id BIGINT,
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  KEY idx_dedup (dedup_key, create_time),
  KEY idx_status (status),
  KEY idx_rule_time (rule_id, create_time)
);

CREATE TABLE alert_silence (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  name VARCHAR(128) NOT NULL,
  target_id BIGINT, rule_id BIGINT,
  start_time DATETIME NOT NULL, end_time DATETIME NOT NULL,
  reason VARCHAR(255), creator VARCHAR(64), status TINYINT DEFAULT 1,
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP, deleted TINYINT NOT NULL DEFAULT 0
);

-- ============ G. 故障事件域 ============
CREATE TABLE alert_incident (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  incident_no VARCHAR(32) NOT NULL UNIQUE,
  title VARCHAR(255) NOT NULL, level VARCHAR(16),
  status VARCHAR(16) DEFAULT 'open' COMMENT 'open|processing|resolved|closed',
  start_time DATETIME, end_time DATETIME, duration_sec BIGINT,
  primary_target_id BIGINT, alert_count INT DEFAULT 0,
  llm_summary TEXT, llm_root_cause TEXT, llm_suggestion TEXT, llm_report TEXT,
  llm_log_evidence TEXT,
  analysis_status VARCHAR(16) DEFAULT 'none' COMMENT 'none|running|done|fail',
  analysis_time DATETIME,
  similar_case_id BIGINT, similar_score DECIMAL(5,4),
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted TINYINT NOT NULL DEFAULT 0
);

CREATE TABLE incident_alert_rel (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  incident_id BIGINT NOT NULL, alert_id BIGINT NOT NULL,
  UNIQUE KEY uk_ia (incident_id, alert_id)
);

CREATE TABLE incident_timeline (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  incident_id BIGINT NOT NULL,
  event_time DATETIME NOT NULL,
  event_type VARCHAR(32) NOT NULL COMMENT 'trigger|aggregate|claim|comment|resolve|close|ai_analysis|log_analysis',
  description TEXT, operator VARCHAR(64), ref_id BIGINT,
  KEY idx_inc_time (incident_id, event_time)
);

-- ============ H. 知识库域 ============
CREATE TABLE kb_document (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  title VARCHAR(255) NOT NULL,
  doc_type VARCHAR(16) COMMENT 'manual|faq|case|other',
  content LONGTEXT, source VARCHAR(512), tags JSON,
  chunk_count INT DEFAULT 0, embedding_status VARCHAR(16) DEFAULT 'none',
  creator VARCHAR(64), create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted TINYINT NOT NULL DEFAULT 0
);

CREATE TABLE kb_chunk (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  document_id BIGINT NOT NULL, chunk_index INT NOT NULL,
  content TEXT, embedding TEXT COMMENT 'JSON数组', token_count INT,
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  KEY idx_doc (document_id)
);

CREATE TABLE kb_fault_case (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  title VARCHAR(255) NOT NULL,
  symptom TEXT, root_cause TEXT, solution TEXT, tags JSON,
  occurred_time DATETIME, related_incident_id BIGINT, source VARCHAR(64),
  embedding TEXT,
  embedding_status VARCHAR(16) DEFAULT 'pending' COMMENT 'done|pending|failed',
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  deleted TINYINT NOT NULL DEFAULT 0
);

CREATE TABLE kb_similarity_log (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  incident_id BIGINT, case_id BIGINT, score DECIMAL(5,4), is_adopted TINYINT DEFAULT 0,
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP
);

-- ============ I. 通知域 ============
CREATE TABLE notify_channel (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  name VARCHAR(64) NOT NULL,
  channel_type VARCHAR(16) COMMENT 'email|webhook|dingtalk|feishu|inapp',
  config JSON, enabled TINYINT DEFAULT 1,
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP, deleted TINYINT NOT NULL DEFAULT 0
);

CREATE TABLE notify_record (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  ref_type VARCHAR(32), ref_id BIGINT, channel_id BIGINT,
  receiver VARCHAR(255), content TEXT, status VARCHAR(16), send_time DATETIME, error_msg TEXT
);

CREATE TABLE sys_message (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT NOT NULL, title VARCHAR(255), content TEXT,
  msg_type VARCHAR(32), ref_id BIGINT, is_read TINYINT DEFAULT 0,
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  KEY idx_user_read (user_id, is_read)
);

-- ============ A 方案扩展 (2026-10-02): Linux agent / SMTP / 自定义大盘 ============

CREATE TABLE monitor_agent (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  target_id BIGINT NOT NULL,
  agent_key VARCHAR(64) NOT NULL UNIQUE,
  status TINYINT DEFAULT 1,
  version VARCHAR(32),
  last_heartbeat DATETIME,
  last_metric_time DATETIME,
  install_command TEXT,
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted TINYINT NOT NULL DEFAULT 0,
  KEY idx_target (target_id)
);

CREATE TABLE monitor_agent_config (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  agent_id BIGINT NOT NULL,
  config_key VARCHAR(64) NOT NULL,
  config_value VARCHAR(255),
  UNIQUE KEY uk_agent_key (agent_id, config_key)
);

CREATE TABLE sys_mail_config (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  name VARCHAR(64) NOT NULL,
  smtp_host VARCHAR(128) NOT NULL,
  smtp_port INT NOT NULL DEFAULT 465,
  username VARCHAR(128) NOT NULL,
  password_enc VARCHAR(1024) NOT NULL,
  from_name VARCHAR(64) DEFAULT NULL,
  `ssl` TINYINT DEFAULT 1,
  enabled TINYINT DEFAULT 1,
  is_default TINYINT DEFAULT 0,
  remark VARCHAR(255),
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted TINYINT NOT NULL DEFAULT 0
);

-- (add via ALTER if upgrading: ALTER TABLE notify_channel ADD COLUMN mail_config_id BIGINT NULL;)

CREATE TABLE dashboard_template (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  name VARCHAR(64) NOT NULL,
  is_default TINYINT DEFAULT 0,
  layout_config JSON,
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted TINYINT NOT NULL DEFAULT 0
);

CREATE TABLE dashboard_widget (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  template_id BIGINT NOT NULL,
  widget_type VARCHAR(32) NOT NULL,
  title VARCHAR(128) NOT NULL,
  config JSON,
  sort INT DEFAULT 0,
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  deleted TINYINT NOT NULL DEFAULT 0,
  KEY ix_template (template_id)
);

-- ============== S3 仪表盘分组 ==============
CREATE TABLE dashboard_group (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT NOT NULL COMMENT '归属用户(user-scoped)',
  name VARCHAR(64) NOT NULL,
  parent_id BIGINT NOT NULL DEFAULT 0 COMMENT '父分组id, 0=根',
  sort INT DEFAULT 0,
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted TINYINT NOT NULL DEFAULT 0,
  KEY idx_user_parent (user_id, parent_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='S3 仪表盘分组';

ALTER TABLE dashboard_template ADD COLUMN group_id BIGINT NULL COMMENT 'S3 归属分组';
ALTER TABLE dashboard_template ADD INDEX idx_user_group (user_id, group_id);
