-- V3：NL2DSL 记录生成时所用的数据源与索引映射
-- 背景：execute() 原先固定查 datasource=1，与 generate() 选用的索引可能不一致——
--       DSL 在 A 索引上通过字段白名单校验，却被拿去 B 索引执行。
--       记录下来后 execute 只按记录里的来源执行，保证"校验什么就执行什么"。
-- 历史记录统一视为生成于 1 号数据源/1 号映射（改造前唯一可用的来源）
ALTER TABLE nl_query_log
  ADD COLUMN datasource_id   BIGINT NULL COMMENT '生成 DSL 时所用 ES 数据源' AFTER user_id,
  ADD COLUMN index_config_id BIGINT NULL COMMENT '生成 DSL 时所用索引映射'   AFTER datasource_id;

UPDATE nl_query_log SET datasource_id = 1, index_config_id = 1 WHERE datasource_id IS NULL;
