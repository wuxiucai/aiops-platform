-- =====================================================================
-- V1 基线：数据库结构
-- 来源：2026-10-15 从运行中的 aiops 库导出（mysqldump --no-data），45 张表
-- 取代 script/ddl.sql 作为唯一结构来源；已含 es_datasource.type、
-- notify_channel.mail_config_id、chat_session / chat_message
-- 已有库由 Flyway baseline 跳过本文件，不会重复执行
-- =====================================================================
CREATE TABLE IF NOT EXISTS `alert_incident` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `incident_no` varchar(32) COLLATE utf8mb4_general_ci NOT NULL,
  `title` varchar(255) COLLATE utf8mb4_general_ci NOT NULL,
  `level` varchar(16) COLLATE utf8mb4_general_ci DEFAULT NULL,
  `status` varchar(16) COLLATE utf8mb4_general_ci DEFAULT 'open' COMMENT 'open|processing|resolved|closed',
  `start_time` datetime DEFAULT NULL,
  `end_time` datetime DEFAULT NULL,
  `duration_sec` bigint DEFAULT NULL,
  `primary_target_id` bigint DEFAULT NULL,
  `alert_count` int DEFAULT '0',
  `llm_summary` text COLLATE utf8mb4_general_ci,
  `llm_root_cause` text COLLATE utf8mb4_general_ci,
  `llm_suggestion` text COLLATE utf8mb4_general_ci,
  `llm_report` text COLLATE utf8mb4_general_ci,
  `llm_log_evidence` text COLLATE utf8mb4_general_ci,
  `analysis_status` varchar(16) COLLATE utf8mb4_general_ci DEFAULT 'none' COMMENT 'none|running|done|fail',
  `analysis_time` datetime DEFAULT NULL,
  `similar_case_id` bigint DEFAULT NULL,
  `similar_score` decimal(5,4) DEFAULT NULL,
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted` tinyint NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `incident_no` (`incident_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
CREATE TABLE IF NOT EXISTS `alert_record` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `rule_id` bigint NOT NULL,
  `target_id` bigint NOT NULL,
  `metric_key` varchar(64) COLLATE utf8mb4_general_ci NOT NULL,
  `level` varchar(16) COLLATE utf8mb4_general_ci DEFAULT NULL,
  `title` varchar(255) COLLATE utf8mb4_general_ci NOT NULL,
  `content` text COLLATE utf8mb4_general_ci,
  `trigger_value` decimal(20,4) DEFAULT NULL,
  `threshold_value` decimal(20,4) DEFAULT NULL,
  `baseline_upper` decimal(20,4) DEFAULT NULL,
  `baseline_lower` decimal(20,4) DEFAULT NULL,
  `status` varchar(16) COLLATE utf8mb4_general_ci DEFAULT 'pending' COMMENT 'pending|processing|resolved|closed|false_positive',
  `dedup_key` varchar(255) COLLATE utf8mb4_general_ci NOT NULL,
  `first_trigger_time` datetime DEFAULT NULL,
  `last_trigger_time` datetime DEFAULT NULL,
  `trigger_count` int DEFAULT '1',
  `claimed_by` varchar(64) COLLATE utf8mb4_general_ci DEFAULT NULL,
  `claimed_time` datetime DEFAULT NULL,
  `resolved_by` varchar(64) COLLATE utf8mb4_general_ci DEFAULT NULL,
  `resolved_time` datetime DEFAULT NULL,
  `handle_remark` text COLLATE utf8mb4_general_ci,
  `incident_id` bigint DEFAULT NULL,
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_dedup` (`dedup_key`,`create_time`),
  KEY `idx_status` (`status`),
  KEY `idx_rule_time` (`rule_id`,`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
CREATE TABLE IF NOT EXISTS `alert_rule` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `name` varchar(128) COLLATE utf8mb4_general_ci NOT NULL,
  `target_id` bigint DEFAULT NULL,
  `group_id` bigint DEFAULT NULL,
  `metric_key` varchar(64) COLLATE utf8mb4_general_ci NOT NULL,
  `rule_type` varchar(16) COLLATE utf8mb4_general_ci NOT NULL COMMENT 'static|baseline|ratio|trend',
  `operator` varchar(8) COLLATE utf8mb4_general_ci NOT NULL COMMENT 'gt|gte|lt|lte|outside|inside',
  `threshold` decimal(20,4) DEFAULT NULL,
  `duration_sec` int DEFAULT '60',
  `level` varchar(16) COLLATE utf8mb4_general_ci DEFAULT 'WARN',
  `sensitivity` decimal(3,2) DEFAULT '0.80',
  `baseline_config` json DEFAULT NULL,
  `silence_window` json DEFAULT NULL,
  `notify_channels` json DEFAULT NULL,
  `enabled` tinyint DEFAULT '1',
  `creator` varchar(64) COLLATE utf8mb4_general_ci DEFAULT NULL,
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted` tinyint NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
CREATE TABLE IF NOT EXISTS `alert_silence` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `name` varchar(128) COLLATE utf8mb4_general_ci NOT NULL,
  `target_id` bigint DEFAULT NULL,
  `rule_id` bigint DEFAULT NULL,
  `start_time` datetime NOT NULL,
  `end_time` datetime NOT NULL,
  `reason` varchar(255) COLLATE utf8mb4_general_ci DEFAULT NULL,
  `creator` varchar(64) COLLATE utf8mb4_general_ci DEFAULT NULL,
  `status` tinyint DEFAULT '1',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `deleted` tinyint NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
CREATE TABLE IF NOT EXISTS `baseline_model` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `rule_id` bigint NOT NULL,
  `target_id` bigint NOT NULL,
  `metric_key` varchar(64) COLLATE utf8mb4_general_ci NOT NULL,
  `model_type` varchar(16) COLLATE utf8mb4_general_ci NOT NULL COMMENT '3sigma|ewma|stl|hour_bucket',
  `params` json DEFAULT NULL,
  `bucket_key` varchar(16) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '如 MON-14',
  `upper_bound` decimal(20,4) DEFAULT NULL,
  `lower_bound` decimal(20,4) DEFAULT NULL,
  `mean_value` decimal(20,4) DEFAULT NULL,
  `std_value` decimal(20,4) DEFAULT NULL,
  `sample_count` int DEFAULT '0',
  `train_start` datetime DEFAULT NULL,
  `train_end` datetime DEFAULT NULL,
  `last_train_time` datetime DEFAULT NULL,
  `status` tinyint DEFAULT '1',
  PRIMARY KEY (`id`),
  KEY `idx_rtm` (`rule_id`,`target_id`,`metric_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
CREATE TABLE IF NOT EXISTS `chat_message` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `session_id` bigint NOT NULL,
  `role` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL,
  `content` text COLLATE utf8mb4_unicode_ci,
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_session` (`session_id`,`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE IF NOT EXISTS `chat_session` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint NOT NULL,
  `title` varchar(128) COLLATE utf8mb4_unicode_ci NOT NULL,
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted` tinyint NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  KEY `idx_user_update` (`user_id`,`deleted`,`update_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE IF NOT EXISTS `collect_task` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `target_id` bigint NOT NULL,
  `metric_keys` json NOT NULL,
  `interval_sec` int NOT NULL DEFAULT '30',
  `status` tinyint DEFAULT '1',
  `last_run_time` datetime DEFAULT NULL,
  `last_cost_ms` bigint DEFAULT NULL,
  `fail_count` int DEFAULT '0',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted` tinyint NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
CREATE TABLE IF NOT EXISTS `dashboard_group` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint NOT NULL COMMENT '归属用户(user-scoped)',
  `name` varchar(64) NOT NULL,
  `parent_id` bigint NOT NULL DEFAULT '0' COMMENT '父分组id, 0=根',
  `sort` int DEFAULT '0',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted` tinyint NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  KEY `idx_user_parent` (`user_id`,`parent_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='S3 仪表盘分组';
CREATE TABLE IF NOT EXISTS `dashboard_template` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint NOT NULL,
  `name` varchar(64) COLLATE utf8mb4_general_ci NOT NULL,
  `is_default` tinyint DEFAULT '0',
  `layout_config` json DEFAULT NULL,
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted` tinyint NOT NULL DEFAULT '0',
  `group_id` bigint DEFAULT NULL COMMENT 'S3 归属分组',
  PRIMARY KEY (`id`),
  KEY `idx_user_group` (`user_id`,`group_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
CREATE TABLE IF NOT EXISTS `dashboard_widget` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `template_id` bigint NOT NULL,
  `widget_type` varchar(32) COLLATE utf8mb4_general_ci NOT NULL COMMENT 'stat_card|line_chart|top_alert|top_template|incident_list|metric_compare',
  `title` varchar(128) COLLATE utf8mb4_general_ci NOT NULL,
  `config` json DEFAULT NULL,
  `sort` int DEFAULT '0',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `deleted` tinyint NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  KEY `idx_template` (`template_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
CREATE TABLE IF NOT EXISTS `es_datasource` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `name` varchar(64) COLLATE utf8mb4_general_ci NOT NULL,
  `type` varchar(32) COLLATE utf8mb4_general_ci NOT NULL DEFAULT 'elasticsearch' COMMENT '数据源类型: elasticsearch/opensearch/compatible',
  `es_scheme` varchar(8) COLLATE utf8mb4_general_ci DEFAULT 'http',
  `es_host` varchar(128) COLLATE utf8mb4_general_ci NOT NULL,
  `es_port` int NOT NULL DEFAULT '9200',
  `username` varchar(128) COLLATE utf8mb4_general_ci DEFAULT NULL,
  `password_enc` varchar(1024) COLLATE utf8mb4_general_ci DEFAULT NULL,
  `api_key` varchar(1024) COLLATE utf8mb4_general_ci DEFAULT NULL,
  `es_version` varchar(32) COLLATE utf8mb4_general_ci DEFAULT NULL,
  `cluster_name` varchar(128) COLLATE utf8mb4_general_ci DEFAULT NULL,
  `status` tinyint DEFAULT '1',
  `is_default` tinyint DEFAULT '0',
  `last_test_time` datetime DEFAULT NULL,
  `test_result` varchar(255) COLLATE utf8mb4_general_ci DEFAULT NULL,
  `remark` varchar(255) COLLATE utf8mb4_general_ci DEFAULT NULL,
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted` tinyint NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
CREATE TABLE IF NOT EXISTS `es_field_cache` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `datasource_id` bigint NOT NULL,
  `index_pattern` varchar(255) COLLATE utf8mb4_general_ci DEFAULT NULL,
  `field_name` varchar(128) COLLATE utf8mb4_general_ci DEFAULT NULL,
  `field_type` varchar(32) COLLATE utf8mb4_general_ci DEFAULT NULL,
  `sample_value` varchar(1024) COLLATE utf8mb4_general_ci DEFAULT NULL,
  `last_scan_time` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_ds_idx` (`datasource_id`,`index_pattern`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
CREATE TABLE IF NOT EXISTS `es_index_config` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `datasource_id` bigint NOT NULL,
  `name` varchar(64) COLLATE utf8mb4_general_ci NOT NULL,
  `index_pattern` varchar(255) COLLATE utf8mb4_general_ci NOT NULL COMMENT '如 log-*',
  `time_field` varchar(64) COLLATE utf8mb4_general_ci DEFAULT '@timestamp',
  `message_field` varchar(64) COLLATE utf8mb4_general_ci DEFAULT 'message',
  `level_field` varchar(64) COLLATE utf8mb4_general_ci DEFAULT 'level',
  `service_field` varchar(64) COLLATE utf8mb4_general_ci DEFAULT 'service',
  `trace_id_field` varchar(64) COLLATE utf8mb4_general_ci DEFAULT 'traceId',
  `level_mapping` json DEFAULT NULL COMMENT '{"ERROR":["ERROR","err","SEVERE"]}',
  `default_time_range_hours` int DEFAULT '24',
  `enabled` tinyint DEFAULT '1',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted` tinyint NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  KEY `idx_ds` (`datasource_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
CREATE TABLE IF NOT EXISTS `incident_alert_rel` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `incident_id` bigint NOT NULL,
  `alert_id` bigint NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ia` (`incident_id`,`alert_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
CREATE TABLE IF NOT EXISTS `incident_timeline` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `incident_id` bigint NOT NULL,
  `event_time` datetime NOT NULL,
  `event_type` varchar(32) COLLATE utf8mb4_general_ci NOT NULL COMMENT 'trigger|aggregate|claim|comment|resolve|close|ai_analysis|log_analysis',
  `description` text COLLATE utf8mb4_general_ci,
  `operator` varchar(64) COLLATE utf8mb4_general_ci DEFAULT NULL,
  `ref_id` bigint DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_inc_time` (`incident_id`,`event_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
CREATE TABLE IF NOT EXISTS `kb_chunk` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `document_id` bigint NOT NULL,
  `chunk_index` int NOT NULL,
  `content` text COLLATE utf8mb4_general_ci,
  `embedding` text COLLATE utf8mb4_general_ci COMMENT 'JSON数组',
  `token_count` int DEFAULT NULL,
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_doc` (`document_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
CREATE TABLE IF NOT EXISTS `kb_document` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `title` varchar(255) COLLATE utf8mb4_general_ci NOT NULL,
  `doc_type` varchar(16) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT 'manual|faq|case|other',
  `content` longtext COLLATE utf8mb4_general_ci,
  `source` varchar(512) COLLATE utf8mb4_general_ci DEFAULT NULL,
  `tags` json DEFAULT NULL,
  `chunk_count` int DEFAULT '0',
  `embedding_status` varchar(16) COLLATE utf8mb4_general_ci DEFAULT 'none',
  `creator` varchar(64) COLLATE utf8mb4_general_ci DEFAULT NULL,
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted` tinyint NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
CREATE TABLE IF NOT EXISTS `kb_fault_case` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `title` varchar(255) COLLATE utf8mb4_general_ci NOT NULL,
  `symptom` text COLLATE utf8mb4_general_ci,
  `root_cause` text COLLATE utf8mb4_general_ci,
  `solution` text COLLATE utf8mb4_general_ci,
  `tags` json DEFAULT NULL,
  `occurred_time` datetime DEFAULT NULL,
  `related_incident_id` bigint DEFAULT NULL,
  `source` varchar(64) COLLATE utf8mb4_general_ci DEFAULT NULL,
  `embedding` text COLLATE utf8mb4_general_ci,
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `deleted` tinyint NOT NULL DEFAULT '0',
  `embedding_status` varchar(16) COLLATE utf8mb4_general_ci DEFAULT 'pending' COMMENT 'done|pending|failed',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
CREATE TABLE IF NOT EXISTS `kb_similarity_log` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `incident_id` bigint DEFAULT NULL,
  `case_id` bigint DEFAULT NULL,
  `score` decimal(5,4) DEFAULT NULL,
  `is_adopted` tinyint DEFAULT '0',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
CREATE TABLE IF NOT EXISTS `llm_call_log` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `provider_id` bigint DEFAULT NULL,
  `scene_code` varchar(64) COLLATE utf8mb4_general_ci NOT NULL,
  `ref_id` bigint DEFAULT NULL,
  `prompt_tokens` int DEFAULT '0',
  `completion_tokens` int DEFAULT '0',
  `total_tokens` int DEFAULT '0',
  `latency_ms` bigint DEFAULT NULL,
  `status` varchar(16) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT 'success|fail|timeout',
  `error_msg` text COLLATE utf8mb4_general_ci,
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_scene_time` (`scene_code`,`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
CREATE TABLE IF NOT EXISTS `llm_prompt_template` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `scene_code` varchar(64) COLLATE utf8mb4_general_ci NOT NULL,
  `scene_name` varchar(128) COLLATE utf8mb4_general_ci DEFAULT NULL,
  `system_prompt` text COLLATE utf8mb4_general_ci,
  `user_prompt_tpl` text COLLATE utf8mb4_general_ci,
  `output_schema` json DEFAULT NULL,
  `version` int DEFAULT '1',
  `enabled` tinyint DEFAULT '1',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_scene_ver` (`scene_code`,`version`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
CREATE TABLE IF NOT EXISTS `llm_provider` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `name` varchar(64) COLLATE utf8mb4_general_ci NOT NULL,
  `provider_type` varchar(32) COLLATE utf8mb4_general_ci NOT NULL COMMENT 'openai_compatible|ollama|custom',
  `base_url` varchar(512) COLLATE utf8mb4_general_ci NOT NULL,
  `api_key` varchar(1024) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT 'AES加密存储',
  `model_name` varchar(128) COLLATE utf8mb4_general_ci NOT NULL,
  `embedding_model` varchar(128) COLLATE utf8mb4_general_ci DEFAULT NULL,
  `temperature` decimal(3,2) DEFAULT '0.30',
  `max_tokens` int DEFAULT '4096',
  `timeout_ms` int DEFAULT '60000',
  `is_default` tinyint DEFAULT '0',
  `status` tinyint DEFAULT '1',
  `remark` varchar(255) COLLATE utf8mb4_general_ci DEFAULT NULL,
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted` tinyint NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
CREATE TABLE IF NOT EXISTS `log_analysis_record` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `datasource_id` bigint DEFAULT NULL,
  `index_config_id` bigint DEFAULT NULL,
  `scene_code` varchar(64) COLLATE utf8mb4_general_ci DEFAULT NULL,
  `ref_id` bigint DEFAULT NULL,
  `time_start` datetime DEFAULT NULL,
  `time_end` datetime DEFAULT NULL,
  `log_count` int DEFAULT NULL,
  `template_count` int DEFAULT NULL,
  `input_summary` text COLLATE utf8mb4_general_ci COMMENT '送给 LLM 的上下文，论文展示用',
  `result` text COLLATE utf8mb4_general_ci COMMENT 'LLM 输出 JSON',
  `token_cost` int DEFAULT NULL,
  `latency_ms` bigint DEFAULT NULL,
  `status` varchar(16) COLLATE utf8mb4_general_ci DEFAULT NULL,
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
CREATE TABLE IF NOT EXISTS `log_anomaly` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `rule_id` bigint DEFAULT NULL,
  `template_id` bigint DEFAULT NULL,
  `datasource_id` bigint DEFAULT NULL,
  `anomaly_type` varchar(32) COLLATE utf8mb4_general_ci DEFAULT NULL,
  `title` varchar(255) COLLATE utf8mb4_general_ci DEFAULT NULL,
  `description` text COLLATE utf8mb4_general_ci,
  `level` varchar(16) COLLATE utf8mb4_general_ci DEFAULT NULL,
  `trigger_value` decimal(20,4) DEFAULT NULL,
  `baseline_value` decimal(20,4) DEFAULT NULL,
  `status` varchar(16) COLLATE utf8mb4_general_ci DEFAULT 'pending' COMMENT 'pending|processing|resolved|false_positive',
  `first_time` datetime DEFAULT NULL,
  `last_time` datetime DEFAULT NULL,
  `count` int DEFAULT '1',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_status_time` (`status`,`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
CREATE TABLE IF NOT EXISTS `log_detect_rule` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `datasource_id` bigint NOT NULL,
  `index_config_id` bigint NOT NULL,
  `name` varchar(128) COLLATE utf8mb4_general_ci NOT NULL,
  `rule_type` varchar(32) COLLATE utf8mb4_general_ci NOT NULL COMMENT 'new_template|rare_template|spike|error_rate',
  `params` json DEFAULT NULL,
  `level` varchar(16) COLLATE utf8mb4_general_ci DEFAULT 'WARN',
  `enabled` tinyint DEFAULT '1',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted` tinyint NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
CREATE TABLE IF NOT EXISTS `log_template` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `datasource_id` bigint NOT NULL,
  `index_config_id` bigint NOT NULL,
  `cluster_id` int DEFAULT NULL COMMENT 'Drain 内部 cluster id，仅本轮计算有效',
  `template_text` text COLLATE utf8mb4_general_ci NOT NULL,
  `token_count` int DEFAULT NULL,
  `template_hash` varchar(64) COLLATE utf8mb4_general_ci NOT NULL,
  `first_seen` datetime DEFAULT NULL,
  `last_seen` datetime DEFAULT NULL,
  `total_count` bigint DEFAULT '0',
  `last_window_count` int DEFAULT '0',
  `sample_log` text COLLATE utf8mb4_general_ci,
  `variables` json DEFAULT NULL,
  `level` varchar(16) COLLATE utf8mb4_general_ci DEFAULT NULL,
  `service` varchar(128) COLLATE utf8mb4_general_ci DEFAULT NULL,
  `status` tinyint DEFAULT '0' COMMENT '0正常 1已关注 2已忽略',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_hash` (`datasource_id`,`template_hash`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
CREATE TABLE IF NOT EXISTS `log_template_stat` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `template_id` bigint NOT NULL,
  `stat_time` datetime NOT NULL,
  `window_count` int NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_tpl_time` (`template_id`,`stat_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
CREATE TABLE IF NOT EXISTS `metric_data` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `target_id` bigint NOT NULL,
  `metric_key` varchar(64) COLLATE utf8mb4_general_ci NOT NULL,
  `metric_value` decimal(20,4) NOT NULL,
  `collect_time` datetime NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_tmt` (`target_id`,`metric_key`,`collect_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
CREATE TABLE IF NOT EXISTS `metric_definition` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `metric_key` varchar(64) COLLATE utf8mb4_general_ci NOT NULL,
  `metric_name` varchar(64) COLLATE utf8mb4_general_ci NOT NULL,
  `unit` varchar(16) COLLATE utf8mb4_general_ci DEFAULT NULL,
  `category` varchar(32) COLLATE utf8mb4_general_ci DEFAULT NULL,
  `value_type` varchar(16) COLLATE utf8mb4_general_ci DEFAULT 'gauge',
  `description` varchar(255) COLLATE utf8mb4_general_ci DEFAULT NULL,
  `default_threshold` decimal(20,4) DEFAULT NULL,
  `deleted` tinyint NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `metric_key` (`metric_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
CREATE TABLE IF NOT EXISTS `monitor_agent` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `target_id` bigint NOT NULL COMMENT '鍏宠仈 monitor_target.id',
  `agent_key` varchar(64) COLLATE utf8mb4_general_ci NOT NULL COMMENT '閴存潈瀵嗛挜锛堢敓鎴愬悗涓嬪彂缁?agent锛',
  `status` tinyint DEFAULT '1' COMMENT '0绂佺敤1鍚?敤',
  `version` varchar(32) COLLATE utf8mb4_general_ci DEFAULT NULL,
  `last_heartbeat` datetime DEFAULT NULL,
  `last_metric_time` datetime DEFAULT NULL,
  `install_command` text COLLATE utf8mb4_general_ci COMMENT '鐢熸垚缁欑敤鎴风殑瀹夎?鑴氭湰锛堝惈 agent_key锛',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted` tinyint NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `agent_key` (`agent_key`),
  KEY `idx_target` (`target_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
CREATE TABLE IF NOT EXISTS `monitor_agent_config` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `agent_id` bigint NOT NULL,
  `config_key` varchar(64) COLLATE utf8mb4_general_ci NOT NULL COMMENT '濡?collect.interval.sec / log.path',
  `config_value` varchar(255) COLLATE utf8mb4_general_ci DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_agent_key` (`agent_id`,`config_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
CREATE TABLE IF NOT EXISTS `monitor_group` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `name` varchar(64) COLLATE utf8mb4_general_ci NOT NULL,
  `parent_id` bigint DEFAULT '0',
  `description` varchar(255) COLLATE utf8mb4_general_ci DEFAULT NULL,
  `sort` int DEFAULT '0',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `deleted` tinyint NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
CREATE TABLE IF NOT EXISTS `monitor_target` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `name` varchar(64) COLLATE utf8mb4_general_ci NOT NULL,
  `target_type` varchar(16) COLLATE utf8mb4_general_ci NOT NULL COMMENT 'host|service',
  `ip` varchar(64) COLLATE utf8mb4_general_ci DEFAULT NULL,
  `target_url` varchar(255) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT 'S4 prometheus 拉取 URL， 如 http://x.x.x.x:9100/metrics',
  `port` int DEFAULT NULL,
  `os` varchar(64) COLLATE utf8mb4_general_ci DEFAULT NULL,
  `group_id` bigint DEFAULT NULL,
  `tags` json DEFAULT NULL,
  `owner` varchar(64) COLLATE utf8mb4_general_ci DEFAULT NULL,
  `description` varchar(255) COLLATE utf8mb4_general_ci DEFAULT NULL,
  `agent_status` varchar(16) COLLATE utf8mb4_general_ci DEFAULT 'unknown',
  `last_heartbeat` datetime DEFAULT NULL,
  `status` tinyint DEFAULT '1',
  `log_service_name` varchar(128) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '打通指标与日志：告警时按它去 ES 查日志',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted` tinyint NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
CREATE TABLE IF NOT EXISTS `nl_query_log` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint DEFAULT NULL,
  `question` text COLLATE utf8mb4_general_ci NOT NULL,
  `generated_dsl` text COLLATE utf8mb4_general_ci,
  `validated` tinyint DEFAULT '0',
  `executed` tinyint DEFAULT '0',
  `hit_count` int DEFAULT NULL,
  `answer` text COLLATE utf8mb4_general_ci,
  `retry_count` int DEFAULT '0',
  `latency_ms` bigint DEFAULT NULL,
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
CREATE TABLE IF NOT EXISTS `notify_channel` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `name` varchar(64) COLLATE utf8mb4_general_ci NOT NULL,
  `channel_type` varchar(16) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT 'email|webhook|dingtalk|feishu|inapp',
  `config` json DEFAULT NULL,
  `enabled` tinyint DEFAULT '1',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `deleted` tinyint NOT NULL DEFAULT '0',
  `mail_config_id` bigint DEFAULT NULL COMMENT '鍏宠仈 sys_mail_config.id锛宑hannel_type=email 鏃朵娇鐢',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
CREATE TABLE IF NOT EXISTS `notify_record` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `ref_type` varchar(32) COLLATE utf8mb4_general_ci DEFAULT NULL,
  `ref_id` bigint DEFAULT NULL,
  `channel_id` bigint DEFAULT NULL,
  `receiver` varchar(255) COLLATE utf8mb4_general_ci DEFAULT NULL,
  `content` text COLLATE utf8mb4_general_ci,
  `status` varchar(16) COLLATE utf8mb4_general_ci DEFAULT NULL,
  `send_time` datetime DEFAULT NULL,
  `error_msg` text COLLATE utf8mb4_general_ci,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
CREATE TABLE IF NOT EXISTS `sys_mail_config` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `name` varchar(64) NOT NULL,
  `smtp_host` varchar(128) NOT NULL COMMENT 'smtp.qq.com / smtp.163.com',
  `smtp_port` int NOT NULL DEFAULT '465',
  `username` varchar(128) NOT NULL,
  `password_enc` varchar(1024) NOT NULL COMMENT 'AES 鍔犲瘑鎺堟潈鐮',
  `from_name` varchar(64) DEFAULT NULL COMMENT 'AIOps 鍙戜欢浜烘樉绀哄悕',
  `ssl` tinyint DEFAULT '1',
  `enabled` tinyint DEFAULT '1',
  `is_default` tinyint DEFAULT '0',
  `remark` varchar(255) DEFAULT NULL,
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted` tinyint NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE IF NOT EXISTS `sys_message` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint NOT NULL,
  `title` varchar(255) COLLATE utf8mb4_general_ci DEFAULT NULL,
  `content` text COLLATE utf8mb4_general_ci,
  `msg_type` varchar(32) COLLATE utf8mb4_general_ci DEFAULT NULL,
  `ref_id` bigint DEFAULT NULL,
  `is_read` tinyint DEFAULT '0',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_user_read` (`user_id`,`is_read`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
CREATE TABLE IF NOT EXISTS `sys_oper_log` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint DEFAULT NULL,
  `username` varchar(64) COLLATE utf8mb4_general_ci DEFAULT NULL,
  `module` varchar(64) COLLATE utf8mb4_general_ci DEFAULT NULL,
  `operation` varchar(128) COLLATE utf8mb4_general_ci DEFAULT NULL,
  `method` varchar(16) COLLATE utf8mb4_general_ci DEFAULT NULL,
  `request_uri` varchar(512) COLLATE utf8mb4_general_ci DEFAULT NULL,
  `params` text COLLATE utf8mb4_general_ci,
  `ip` varchar(64) COLLATE utf8mb4_general_ci DEFAULT NULL,
  `status` tinyint DEFAULT NULL,
  `error_msg` text COLLATE utf8mb4_general_ci,
  `cost_time` bigint DEFAULT NULL,
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_ct` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
CREATE TABLE IF NOT EXISTS `sys_permission` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `parent_id` bigint NOT NULL DEFAULT '0',
  `name` varchar(64) COLLATE utf8mb4_general_ci NOT NULL,
  `perm_type` char(1) COLLATE utf8mb4_general_ci NOT NULL COMMENT 'M菜单 B按钮',
  `path` varchar(255) COLLATE utf8mb4_general_ci DEFAULT NULL,
  `component` varchar(255) COLLATE utf8mb4_general_ci DEFAULT NULL,
  `perms` varchar(128) COLLATE utf8mb4_general_ci DEFAULT NULL,
  `icon` varchar(64) COLLATE utf8mb4_general_ci DEFAULT NULL,
  `sort` int DEFAULT '0',
  `status` tinyint DEFAULT '1',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `deleted` tinyint NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
CREATE TABLE IF NOT EXISTS `sys_role` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `role_code` varchar(32) COLLATE utf8mb4_general_ci NOT NULL,
  `role_name` varchar(64) COLLATE utf8mb4_general_ci DEFAULT NULL,
  `description` varchar(255) COLLATE utf8mb4_general_ci DEFAULT NULL,
  `status` tinyint DEFAULT '1',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted` tinyint NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `role_code` (`role_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
CREATE TABLE IF NOT EXISTS `sys_role_permission` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `role_id` bigint NOT NULL,
  `permission_id` bigint NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_rp` (`role_id`,`permission_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
CREATE TABLE IF NOT EXISTS `sys_user` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `username` varchar(64) COLLATE utf8mb4_general_ci NOT NULL,
  `password` varchar(128) COLLATE utf8mb4_general_ci NOT NULL COMMENT 'BCrypt',
  `nickname` varchar(64) COLLATE utf8mb4_general_ci DEFAULT NULL,
  `email` varchar(128) COLLATE utf8mb4_general_ci DEFAULT NULL,
  `phone` varchar(32) COLLATE utf8mb4_general_ci DEFAULT NULL,
  `avatar` varchar(512) COLLATE utf8mb4_general_ci DEFAULT NULL,
  `status` tinyint NOT NULL DEFAULT '1' COMMENT '0禁用1启用',
  `last_login_time` datetime DEFAULT NULL,
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted` tinyint NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `username` (`username`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
CREATE TABLE IF NOT EXISTS `sys_user_role` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint NOT NULL,
  `role_id` bigint NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ur` (`user_id`,`role_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
