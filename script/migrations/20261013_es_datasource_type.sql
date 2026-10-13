-- 迁移：数据源支持 ES 兼容类型（OpenSearch / Easysearch / AWS OpenSearch 等）
-- 日期：2026-10-13
-- 说明：这些数据源的 _search DSL 与 ES 7.x 兼容，平台查询客户端无需改动；
--       type 字段用于在数据源管理与日志检索中标识来源类型。
ALTER TABLE es_datasource
  ADD COLUMN type VARCHAR(32) NOT NULL DEFAULT 'elasticsearch'
  COMMENT '数据源类型: elasticsearch/opensearch/compatible' AFTER name;
