package com.aiops.module.log.safety;

import com.aiops.module.esa.entity.EsIndexConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * M6 W4-2 验收：DslSafetyValidator 白名单 / 危险操作 / 字段白名单 / size clamp / range(timeField) 全套用例。
 */
class DslSafetyValidatorTest {

    private EsIndexConfig cfg;
    private final ObjectMapper om = new ObjectMapper();

    @BeforeEach
    void setUp() {
        cfg = new EsIndexConfig();
        cfg.setId(1L);
        cfg.setDatasourceId(1L);
        cfg.setIndexPattern("aiops-log-*");
        cfg.setTimeField("@timestamp");
        cfg.setMessageField("message");
        cfg.setLevelField("level");
        cfg.setServiceField("service");
        cfg.setTraceIdField("traceId");
        cfg.setEnabled(1);
    }

    private JsonNode parse(String s) {
        try {
            return om.readTree(s);
        } catch (Exception e) {
            fail("test DSL not parseable: " + e.getMessage());
            return null;
        }
    }

    /* ============ 1. 合法 DSL（range+term+sort+size=20）通过 ============ */
    @Test
    void validDslPasses() {
        String dsl = """
                {
                  "query": {
                    "bool": {
                      "must": [ {"match": {"message": "timeout"}} ],
                      "filter": [
                        {"range": {"@timestamp": {"gte": "2026-10-01 00:00:00", "lte": "2026-10-02 00:00:00"}}},
                        {"term": {"level.keyword": "ERROR"}}
                      ]
                    }
                  },
                  "sort": [{"@timestamp": {"order": "desc"}}],
                  "size": 20
                }
                """;
        DslSafetyValidator.ValidationResult r = DslSafetyValidator.validate(parse(dsl), cfg);
        assertTrue(r.ok(), "errors: " + r.errors());
        assertNotNull(r.dsl());
        assertEquals(20, r.dsl().get("size").asInt());
        assertFalse(r.sizeClamped());
    }

    /* ============ 2. query 含 script_fields 拒 ============ */
    @Test
    void rejectsScriptFields() {
        String dsl = """
                {
                  "query": {"bool": {"filter": [{"range": {"@timestamp": {"gte": "now-1h"}}}]}},
                  "script_fields": {"x": {"script": {"source": "1+1"}}}
                }
                """;
        DslSafetyValidator.ValidationResult r = DslSafetyValidator.validate(parse(dsl), cfg);
        assertFalse(r.ok());
        assertTrue(r.errors().stream().anyMatch(s -> s.contains("script_fields")
                || s.contains("顶层非法") || s.contains("_") && s.contains("script_fields")));
    }

    /* ============ 3. query 含 painless 拒 ============ */
    @Test
    void rejectsPainless() {
        String dsl = """
                {
                  "query": {
                    "bool": {
                      "filter": [
                        {"range": {"@timestamp": {"gte": "now-1h"}}},
                        {"bool": {"must": [{"bool": {"must": [{"painless": {"source": "return true;"}}]}}]}}
                      ]
                    }
                  }
                }
                """;
        DslSafetyValidator.ValidationResult r = DslSafetyValidator.validate(parse(dsl), cfg);
        assertFalse(r.ok());
        assertTrue(r.errors().stream().anyMatch(s -> s.contains("painless")));
    }

    /* ============ 4. query 含 _update_by_query 拒 ============ */
    @Test
    void rejectsUpdateByQueryInside() {
        String dsl = """
                {
                  "query": {
                    "bool": {
                      "filter": [
                        {"range": {"@timestamp": {"gte": "now-1h"}}},
                        {"_update_by_query": {"filter": {"term": {"level.keyword": "ERROR"}}}}
                      ]
                    }
                  }
                }
                """;
        DslSafetyValidator.ValidationResult r = DslSafetyValidator.validate(parse(dsl), cfg);
        assertFalse(r.ok());
    }

    /* ============ 5. 顶层 _delete_by_query 拒 ============ */
    @Test
    void rejectsTopLevelDeleteByQuery() {
        String dsl = """
                {
                  "_delete_by_query": true,
                  "query": {"bool": {"filter": [{"range": {"@timestamp": {"gte": "now-1h"}}}]}}
                }
                """;
        DslSafetyValidator.ValidationResult r = DslSafetyValidator.validate(parse(dsl), cfg);
        assertFalse(r.ok());
    }

    /* ============ 6. 顶层 index 拒 ============ */
    @Test
    void rejectsTopLevelIndex() {
        String dsl = """
                {
                  "index": "aiops-log-1",
                  "query": {"bool": {"filter": [{"range": {"@timestamp": {"gte": "now-1h"}}}]}}
                }
                """;
        DslSafetyValidator.ValidationResult r = DslSafetyValidator.validate(parse(dsl), cfg);
        assertFalse(r.ok());
        assertTrue(r.errors().stream().anyMatch(s -> s.contains("顶层") || s.contains("index")));
    }

    /* ============ 7. size=200 → 强制 clamp 到 100，仍 ok，sizeClamped=true ============ */
    @Test
    void sizeClampedFrom200To100() {
        String dsl = """
                {
                  "query": {"bool": {"filter": [{"range": {"@timestamp": {"gte": "now-1h"}}}]}},
                  "size": 200
                }
                """;
        DslSafetyValidator.ValidationResult r = DslSafetyValidator.validate(parse(dsl), cfg);
        assertTrue(r.ok(), "errors: " + r.errors());
        assertEquals(100, r.dsl().get("size").asInt());
        assertTrue(r.sizeClamped(), "size from 200 → 100 must be flagged");
    }

    /* ============ 8. 缺 range(timeField) 拒 ============ */
    @Test
    void rejectsMissingTimeRange() {
        String dsl = """
                {
                  "query": {"bool": {"filter": [{"term": {"level.keyword": "ERROR"}}]}},
                  "size": 20
                }
                """;
        DslSafetyValidator.ValidationResult r = DslSafetyValidator.validate(parse(dsl), cfg);
        assertFalse(r.ok());
        assertTrue(r.errors().stream().anyMatch(s -> s.contains("range") || s.contains("时间字段")));
    }

    /* ============ 9. query 使用非白名单 field 拒 ============ */
    @Test
    void rejectsNonWhitelistedField() {
        String dsl = """
                {
                  "query": {
                    "bool": {
                      "filter": [
                        {"range": {"@timestamp": {"gte": "now-1h"}}},
                        {"term": {"user_id.keyword": "u123"}}
                      ]
                    }
                  },
                  "size": 20
                }
                """;
        DslSafetyValidator.ValidationResult r = DslSafetyValidator.validate(parse(dsl), cfg);
        assertFalse(r.ok());
        assertTrue(r.errors().stream().anyMatch(s -> s.contains("白名单") || s.contains("user_id")));
    }

    /* ============ 10. query 用 .keyword 后缀（合法）通过 ============ */
    @Test
    void allowsKeywordSuffix() {
        String dsl = """
                {
                  "query": {
                    "bool": {
                      "filter": [
                        {"range": {"@timestamp": {"gte": "now-1h"}}},
                        {"terms": {"service.keyword": ["order-service", "user-service"]}},
                        {"term": {"level.keyword": "ERROR"}}
                      ]
                    }
                  },
                  "size": 20
                }
                """;
        DslSafetyValidator.ValidationResult r = DslSafetyValidator.validate(parse(dsl), cfg);
        assertTrue(r.ok(), "errors: " + r.errors());
    }

    /* ============ 11. 嵌套 5 层深的 script 递归拒 ============ */
    @Test
    void rejectsNestedScript5LevelsDeep() throws Exception {
        // 用 Jackson 构造嵌套 5 层 bool/must 最深的 script
        com.fasterxml.jackson.databind.node.ObjectNode scriptNode = om.createObjectNode();
        com.fasterxml.jackson.databind.node.ObjectNode inner = scriptNode.putObject("script");
        inner.putObject("script").put("source", "true");

        // 手工构造：bool.must[ bool.must[ bool.must[ bool.must[ bool.filter[ script ] ] ] ] ]
        com.fasterxml.jackson.databind.node.ObjectNode deepest = om.createObjectNode();
        com.fasterxml.jackson.databind.node.ArrayNode dFilter = deepest.putObject("bool").putArray("filter");
        dFilter.add(scriptNode);
        com.fasterxml.jackson.databind.node.ObjectNode cur = deepest;
        for (int i = 0; i < 4; i++) {
            com.fasterxml.jackson.databind.node.ObjectNode outer = om.createObjectNode();
            com.fasterxml.jackson.databind.node.ArrayNode must = outer.putObject("bool").putArray("must");
            must.add(cur);
            cur = outer;
        }
        com.fasterxml.jackson.databind.node.ObjectNode root = om.createObjectNode();
        com.fasterxml.jackson.databind.node.ObjectNode topBool = om.createObjectNode();
        com.fasterxml.jackson.databind.node.ArrayNode topFilter = topBool.putArray("filter");
        com.fasterxml.jackson.databind.node.ObjectNode rangeNode = om.createObjectNode();
        rangeNode.putObject("range").putObject("@timestamp").put("gte", "now-1h");
        topFilter.add(rangeNode);
        topFilter.add(cur);
        root.putObject("query").set("bool", topBool);

        DslSafetyValidator.ValidationResult r = DslSafetyValidator.validate(root, cfg);
        assertFalse(r.ok());
        assertTrue(r.errors().stream().anyMatch(s -> s.contains("script")));
    }

    /* ============ 12. track_total_hits + aggs by_level/by_service 通过 ============ */
    @Test
    void allowsTrackTotalHitsAndAggs() {
        String dsl = """
                {
                  "track_total_hits": true,
                  "query": {"bool": {"filter": [{"range": {"@timestamp": {"gte": "now-1h"}}}]}},
                  "size": 0,
                  "aggs": {
                    "by_level":   {"terms": {"field": "level.keyword"}},
                    "by_service": {"terms": {"field": "service.keyword", "size": 20}}
                  }
                }
                """;
        DslSafetyValidator.ValidationResult r = DslSafetyValidator.validate(parse(dsl), cfg);
        assertTrue(r.ok(), "errors: " + r.errors());
        // size=0 → 被 validator 默认化到 20（任务书规则："size <= 0 或缺失默认 20"）
        assertEquals(20, r.dsl().get("size").asInt());
        assertTrue(r.sizeClamped(), "size=0 → 20 must be flagged as clamped");
    }

    /* ============ 附：缺省 size 自动补 20（不算 clamped） ============ */
    @Test
    void defaultSizeApplied() {
        String dsl = """
                {
                  "query": {"bool": {"filter": [{"range": {"@timestamp": {"gte": "now-1h"}}}]}}
                }
                """;
        DslSafetyValidator.ValidationResult r = DslSafetyValidator.validate(parse(dsl), cfg);
        assertTrue(r.ok(), "errors: " + r.errors());
        assertEquals(20, r.dsl().get("size").asInt());
        assertFalse(r.sizeClamped());
    }

    /* ============ 附：service 白名单穷举 → 未知服务拒绝 ============ */
    @Test
    void rejectsUnknownServiceWhenWhitelistGiven() {
        String dsl = """
                {
                  "query": {
                    "bool": {
                      "filter": [
                        {"range": {"@timestamp": {"gte": "now-1h"}}},
                        {"term": {"service.keyword": "evil-service"}}
                      ]
                    }
                  }
                }
                """;
        DslSafetyValidator.ValidationResult r = DslSafetyValidator.validate(
                parse(dsl), cfg, List.of("order-service", "user-service"));
        assertFalse(r.ok());
        assertTrue(r.errors().stream().anyMatch(s -> s.contains("evil-service")));
    }
}
