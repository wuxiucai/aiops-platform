package com.aiops.module.log.safety;

import com.aiops.module.esa.entity.EsIndexConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * M6 核心验收 W4-2：对 LLM 生成的 ES DSL 做 3 重（白名单 + 危险操作 + field 白名单）校验。
 * <p>
 * 规则一览（毕设验收对照）：
 * <ol>
 *   <li>顶层键白名单：仅 query / sort / size / aggs / track_total_hits / from / timeout；</li>
 *   <li>递归危险操作拦截（任意深度，含嵌套 key）：script / script_fields / painless /
 *       _update / _delete_by_query / _bulk / _index / _delete / _id / _ingest / _reindex / _create /
 *       任何 "_" 开头且不在白名单的 key；</li>
 *   <li>字段白名单（来自 EsIndexConfig）：timeField / messageField / levelField /
 *       serviceField / traceIdField 及各自 .keyword 后缀；@timestamp 恒允许。</li>
 *   <li>size clamp：size &gt; 100 强制改为 100；size &lt;= 0 或缺失默认 20；</li>
 *   <li>强制时间范围：query 子树中至少要有一个 range 作用于 EsIndexConfig.timeField（@timestamp）；</li>
 *   <li>executeOnly：本校验仅会批准最终发往 _search 端点的 DSL，调用方严禁给其他端点使用。</li>
 * </ol>
 */
public final class DslSafetyValidator {

    /** 顶层 DSL 允许的 key */
    private static final Set<String> ALLOWED_TOP_KEYS = Set.of(
            "query", "sort", "size", "aggs", "track_total_hits", "from", "timeout");

    /** 顶层允许的 "_" 前缀 key（如 _source 不放行；track_total_hits 已含上） */
    private static final Set<String> ALLOWED_UNDERSCORE_KEYS = Set.of(); // 顶层无 _ 前缀放行项

    /** 任意深度禁止出现的 key（命中即拒） */
    private static final Set<String> DENIED_KEYS = Set.of(
            "script", "script_fields", "script_field", "painless",
            "_update", "_update_by_query", "_delete_by_query", "_delete",
            "_bulk", "_index", "_create", "_id", "_ingest", "_reindex"
    );

    /** ES domain operators：这些 key 的值可能是对象或数组，其下的子 key 可能是字段名 */
    private static final Set<String> OPERATORS_WITH_FIELD_CHILDREN = Set.of(
            "term", "terms", "match", "match_phrase", "match_all", "range",
            "exists", "prefix", "wildcard", "regexp", "fuzzy", "ids",
            "date_histogram", "histogram", "terms_agg");

    /** 关键词/布尔/join 等不参与字段名检测 */
    private static final Set<String> OPERATORS_NO_FIELD_CHILDREN = Set.of(
            "bool", "must", "should", "must_not", "filter",
            "and", "or", "not", "nested", "has_child", "has_parent",
            "query_string", "simple_query_string", "constant_score",
            "dis_max", "function_score", "boosting", "intervals");

    /** 聚合允许的关键 key */
    private static final Set<String> AGG_OPERATORS = Set.of(
            "terms", "date_histogram", "histogram", "range", "date_range",
            "avg", "sum", "min", "max", "value_count", "cardinality",
            "filter", "filters", "top_hits", "stats");

    private static final ObjectMapper OM = new ObjectMapper();

    private DslSafetyValidator() {
    }

    /** 校验结果：ok + errors + 归一后的 dsl（size clamp 已应用）。sizeClamped=true → 调用方可单独提示 */
    public record ValidationResult(boolean ok, List<String> errors, JsonNode dsl, boolean sizeClamped) {
    }

    /**
     * 校验入口：对 raw Dsl JSON 做完整 3 重校验 + size clamp + 强制 range 检查。
     * <p>
     * 返回 ValidationResult：
     * - ok=false → errors 非空，含具体原因；dsl 为 null；
     * - ok=true  → dsl 为归一后（size clamp 已应用）的 ObjectNode。
     */
    public static ValidationResult validate(JsonNode raw, EsIndexConfig cfg, List<String> knownServices) {
        List<String> errors = new ArrayList<>();
        if (raw == null || !raw.isObject()) {
            errors.add("DSL 必须是 JSON 对象");
            return new ValidationResult(false, errors, null, false);
        }
        if (cfg == null) {
            errors.add("索引配置缺失（EsIndexConfig==null）");
            return new ValidationResult(false, errors, null, false);
        }

        ObjectNode root = (ObjectNode) raw.deepCopy();

        // ---- 1. 顶层 key 白名单 ----
        Iterator<String> fields = root.fieldNames();
        while (fields.hasNext()) {
            String f = fields.next();
            if (!ALLOWED_TOP_KEYS.contains(f)) {
                if (f.startsWith("_") && ALLOWED_UNDERSCORE_KEYS.contains(f)) continue;
                errors.add("顶层非法字段：" + f);
            }
        }
        // 顶层 index / type / pipeline 等明显写入或越权一律拒
        if (root.has("index")) errors.add("顶层不允许 index 字段");
        if (root.has("type")) errors.add("顶层不允许 type 字段");
        if (root.has("_delete_by_query")) errors.add("顶层不允许 _delete_by_query");
        if (root.has("_update_by_query")) errors.add("顶层不允许 _update_by_query");
        if (root.has("_bulk")) errors.add("顶层不允许 _bulk");

        // ---- 2. 递归危险操作拦截 + 字段白名单收集 ----
        Set<String> usedFields = new LinkedHashSet<>();
        recursiveCheck(root, "root", errors, usedFields, cfg, 0);

        // ---- 3. 字段白名单：query 中引用到的所有字段必须是 EsIndexConfig 已知字段（含 .keyword 变体）或 @timestamp ----
        Set<String> allowedFieldNames = buildAllowedFieldNames(cfg);
        for (String f : usedFields) {
            if (allowedFieldNames.contains(f)) continue;
            errors.add("字段 " + f + " 不在索引字段白名单（允许：timeField/messageField/levelField/serviceField/traceIdField 及 .keyword）");
        }

        // ---- 4. size clamp ----
        boolean sizeClamped = applySizeClamp(root);

        // ---- 5. 强制 range(timeField) ----
        String timeField = cfg.getTimeField() == null ? "@timestamp" : cfg.getTimeField();
        boolean hasRangeOnTime = hasRangeOnField(root, timeField);
        if (!hasRangeOnTime && !hasRangeOnField(root, "@timestamp")) {
            errors.add("query 必须包含对时间字段 " + timeField + "（或 @timestamp）的 range 过滤");
        }

        // ---- 6. service 白名单穷举（可选；若调用方给了 then enforce）----
        if (cfg.getServiceField() != null && knownServices != null && !knownServices.isEmpty()) {
            // 收集所有 terms / term(serviceField) 处的值，必须落在 knownServices
            Set<String> serviceValues = new LinkedHashSet<>();
            collectServiceTerms(root.path("query"), cfg.getServiceField(), serviceValues, 0);
            for (String v : serviceValues) {
                if (!knownServices.contains(v)) {
                    errors.add("服务 " + v + " 不在已知服务清单");
                }
            }
        }

        if (!errors.isEmpty()) {
            return new ValidationResult(false, errors, null, sizeClamped);
        }
        return new ValidationResult(true, List.of(), root, sizeClamped);
    }

    /** 便捷：无 services 参与校验 */
    public static ValidationResult validate(JsonNode raw, EsIndexConfig cfg) {
        return validate(raw, cfg, null);
    }

    /* ================== 内部：递归扫 ================== */

    private static void recursiveCheck(JsonNode node, String path, List<String> errors,
                                       Set<String> usedFields, EsIndexConfig cfg, int depth) {
        if (node == null) return;
        if (depth > 32) {
            errors.add("DSL 嵌套过深 (>32 层)");
            return;
        }
        if (node.isObject()) {
            ObjectNode obj = (ObjectNode) node;
            Iterator<Map.Entry<String, JsonNode>> it = obj.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                String k = e.getKey();
                JsonNode v = e.getValue();
                String childPath = path + "." + k;

                // 危险 key：绝对拦截
                if (DENIED_KEYS.contains(k)) {
                    errors.add("禁止操作：检测到 key " + k + "（路径 " + childPath + "）");
                    continue;
                }
                // _ 前缀且未明确放行 → 拒
                if (k.startsWith("_") && !ALLOWED_TOP_KEYS.contains(k) && !ALLOWED_UNDERSCORE_KEYS.contains(k)) {
                    errors.add("禁止操作：检测到 _ 前缀字段 " + k + "（路径 " + childPath + "）");
                    continue;
                }
                // 字段名探测：在 term/terms/match/range/… 等操作符下面，子对象 key 是字段名
                if (OPERATORS_WITH_FIELD_CHILDREN.contains(k)) {
                    collectFieldNamesUnderOperator(v, usedFields, depth + 1);
                }
                // 聚合 aggs 下的 operator 自身也可以携带 field
                if ("aggs".equals(k) || "aggregations".equals(k)) {
                    collectAggFieldNames(v, usedFields);
                    recursiveCheckAggs(v, childPath, errors, usedFields, cfg, depth + 1);
                    continue; // aggs 子树走专属递归，外层 user 别名不进入通用 recursiveCheck
                }
                recursiveCheck(v, childPath, errors, usedFields, cfg, depth + 1);
            }
        } else if (node.isArray()) {
            int i = 0;
            for (JsonNode child : node) {
                recursiveCheck(child, path + "[" + i + "]", errors, usedFields, cfg, depth + 1);
                i++;
            }
        }
    }

    /**
     * aggs / aggregations 专属递归：第一层 key 是聚合别名（用户命名，不参与通用白名单），
     * 第二层必须是 AGG_OPERATORS 之一（terms / date_histogram / avg / ...）。
     */
    private static void recursiveCheckAggs(JsonNode aggsNode, String path, List<String> errors,
                                           Set<String> usedFields, EsIndexConfig cfg, int depth) {
        if (aggsNode == null || !aggsNode.isObject() || depth > 20) return;
        Iterator<Map.Entry<String, JsonNode>> aliases = aggsNode.fields();
        while (aliases.hasNext()) {
            Map.Entry<String, JsonNode> alias = aliases.next();
            JsonNode body = alias.getValue();
            String aliasPath = path + "." + alias.getKey();
            if (body == null || !body.isObject()) continue;
            Iterator<Map.Entry<String, JsonNode>> ops = body.fields();
            while (ops.hasNext()) {
                Map.Entry<String, JsonNode> opEntry = ops.next();
                String opKey = opEntry.getKey();
                JsonNode opBody = opEntry.getValue();
                if ("aggs".equals(opKey) || "aggregations".equals(opKey)) {
                    recursiveCheckAggs(opBody, aliasPath, errors, usedFields, cfg, depth + 1);
                    continue;
                }
                // 危险 key 仍然拒
                if (DENIED_KEYS.contains(opKey) || (opKey.startsWith("_") && !ALLOWED_UNDERSCORE_KEYS.contains(opKey))) {
                    errors.add("aggs 中禁止操作：" + opKey + "（路径 " + aliasPath + "." + opKey + "）");
                    continue;
                }
                if (!AGG_OPERATORS.contains(opKey)) {
                    errors.add("aggs 中不支持的 operator：" + opKey + "（路径 " + aliasPath + "." + opKey + "）");
                    continue;
                }
                // operator 子树只递归危险 key 即可（field/size 等参数合法）
                if (opBody != null && opBody.isObject()) {
                    scanForDangerousKeys(opBody, aliasPath + "." + opKey, errors, depth + 1);
                }
            }
        }
    }

    /** 在已经判定 operator 合法之后，仅扫危险 key（不拦截参数 key） */
    private static void scanForDangerousKeys(JsonNode node, String path, List<String> errors, int depth) {
        if (node == null || depth > 20) return;
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> it = ((ObjectNode) node).fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                String k = e.getKey();
                if (DENIED_KEYS.contains(k)) {
                    errors.add("aggs 参数含禁止 key：" + k + "（路径 " + path + "." + k + "）");
                    continue;
                }
                if (k.startsWith("_") && !ALLOWED_UNDERSCORE_KEYS.contains(k)) {
                    errors.add("aggs 参数含 _ 前缀 key：" + k);
                    continue;
                }
                scanForDangerousKeys(e.getValue(), path + "." + k, errors, depth + 1);
            }
        } else if (node.isArray()) {
            int i = 0;
            for (JsonNode c : node) {
                scanForDangerousKeys(c, path + "[" + i + "]", errors, depth + 1);
                i++;
            }
        }
    }

    /**
     * 在 term / terms / match / range / exists / prefix / wildcard 之类的操作符之下，
     * 一级子 key 都是字段名 —— 收集到 usedFields。
     * <p>
     * 例：{"term": {"level.keyword":"ERROR"}} → usedFields += level.keyword
     * 例：{"range": {"@timestamp": {"gte": "..."} }} → usedFields += @timestamp
     */
    private static void collectFieldNamesUnderOperator(JsonNode opValue, Set<String> usedFields, int depth) {
        if (opValue == null || !opValue.isObject() || depth > 6) return;
        Iterator<String> it = opValue.fieldNames();
        while (it.hasNext()) {
            String k = it.next();
            // skip 已知参数 key
            if (isParamKey(k)) continue;
            usedFields.add(k);
        }
    }

    /** aggs 下，operator （如 terms / date_histogram / avg / max）有 "field" 参数 */
    private static void collectAggFieldNames(JsonNode aggsNode, Set<String> usedFields) {
        if (aggsNode == null || !aggsNode.isObject()) return;
        Iterator<Map.Entry<String, JsonNode>> outer = aggsNode.fields();
        while (outer.hasNext()) {
            Map.Entry<String, JsonNode> aggEntry = outer.next();
            JsonNode aggBody = aggEntry.getValue();
            if (aggBody == null || !aggBody.isObject()) continue;
            Iterator<Map.Entry<String, JsonNode>> ops = aggBody.fields();
            while (ops.hasNext()) {
                Map.Entry<String, JsonNode> opEntry = ops.next();
                if (!AGG_OPERATORS.contains(opEntry.getKey())) continue;
                JsonNode opConf = opEntry.getValue();
                if (opConf != null && opConf.isObject()) {
                    JsonNode field = opConf.get("field");
                    if (field != null && field.isTextual()) {
                        usedFields.add(field.asText());
                    }
                }
                // aggs 嵌套：继续递归
                collectAggFieldNames(opConf, usedFields);
            }
        }
    }

    /** term/range/... 的参数 key 白名单：这些不是 field 名 */
    private static boolean isParamKey(String k) {
        return switch (k) {
            case "query", "value", "boost", "analyzer", "operator", "minimum_should_match",
                 "fuzziness", "prefix_length", "max_expansions", "rewrite", "zero_terms_query",
                 "lenient", "auto_generate_synonyms_phrase_query", "fuzzy_transpositions",
                 "gte", "gt", "lte", "lt", "format", "time_zone", "relation",
                 "case_insensitive", "type", "flags", "max_determinized_states",
                 "slop", "phrase", "and", "or", "interval", "size", "shard_size",
                 "show_term_doc_count_error", "order", "min_doc_count", "include", "exclude",
                 "field", "fixed_interval", "calendar_interval", "offset",
                 "keyed", "ranges", "from", "to", "min", "max", "doc_count", "key" -> true;
            default -> false;
        };
    }

    /* ================== size clamp ================== */

    /**
     * size clamp：
     * - 无 size / size<=0  → 默认 20；
     * - size>100 → 强制 100；
     * 返回是否发生了 clamp。
     */
    private static boolean applySizeClamp(ObjectNode root) {
        JsonNode sz = root.get("size");
        if (sz == null || !sz.isNumber()) {
            root.set("size", IntNode.valueOf(20));
            return false; // 没"挪"，只是补默认
        }
        int v = sz.asInt();
        if (v <= 0) {
            root.set("size", IntNode.valueOf(20));
            return true;
        }
        if (v > 100) {
            root.set("size", IntNode.valueOf(100));
            return true;
        }
        return false;
    }

    /* ================== range on time field ================== */

    private static boolean hasRangeOnField(JsonNode node, String fieldName) {
        if (node == null) return false;
        if (node.isObject()) {
            ObjectNode o = (ObjectNode) node;
            JsonNode range = o.get("range");
            if (range != null && range.isObject() && range.has(fieldName)) {
                return true;
            }
            Iterator<JsonNode> it = o.elements();
            while (it.hasNext()) {
                if (hasRangeOnField(it.next(), fieldName)) return true;
            }
        } else if (node.isArray()) {
            for (JsonNode c : node) {
                if (hasRangeOnField(c, fieldName)) return true;
            }
        }
        return false;
    }

    /* ================== service terms collect ================== */

    private static void collectServiceTerms(JsonNode node, String serviceField,
                                            Set<String> out, int depth) {
        if (node == null || depth > 20) return;
        if (node.isObject()) {
            ObjectNode o = (ObjectNode) node;
            JsonNode term = o.get("term");
            if (term != null && term.isObject()) {
                JsonNode v = term.get(serviceField);
                if (v == null) v = term.get(serviceField + ".keyword");
                if (v != null && v.isTextual()) out.add(v.asText());
                if (v != null && v.isObject() && v.get("value") != null && v.get("value").isTextual()) {
                    out.add(v.get("value").asText());
                }
            }
            JsonNode terms = o.get("terms");
            if (terms != null && terms.isObject()) {
                JsonNode v = terms.get(serviceField);
                if (v == null) v = terms.get(serviceField + ".keyword");
                if (v != null && v.isArray()) {
                    for (JsonNode item : v) {
                        if (item.isTextual()) out.add(item.asText());
                    }
                }
            }
            Iterator<JsonNode> it = o.elements();
            while (it.hasNext()) {
                collectServiceTerms(it.next(), serviceField, out, depth + 1);
            }
        } else if (node.isArray()) {
            for (JsonNode c : node) {
                collectServiceTerms(c, serviceField, out, depth + 1);
            }
        }
    }

    /* ================== 字段白名单 ================== */

    private static Set<String> buildAllowedFieldNames(EsIndexConfig cfg) {
        Set<String> allowed = new HashSet<>();
        addFieldWithKeyword(allowed, cfg.getTimeField());
        addFieldWithKeyword(allowed, cfg.getMessageField());
        addFieldWithKeyword(allowed, cfg.getLevelField());
        addFieldWithKeyword(allowed, cfg.getServiceField());
        addFieldWithKeyword(allowed, cfg.getTraceIdField());
        // @timestamp 恒允许
        allowed.add("@timestamp");
        allowed.add("@timestamp.keyword");
        return allowed;
    }

    private static void addFieldWithKeyword(Set<String> set, String f) {
        if (f == null || f.isBlank()) return;
        set.add(f);
        if (!f.endsWith(".keyword")) {
            set.add(f + ".keyword");
        }
    }
}
