package com.aiops.datasource.log;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * ES 字段探测：调用 _mapping 展开字段树，返回字段名 + type + 启发式建议角色。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EsFieldProbe {

    private final EsLogClient esLogClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public record FieldInfo(String name, String type, String suggestedRole) {
    }

    /**
     * 探测指定索引模式的字段。
     * 启发式：date 类型 → timeField；名称含 level/severity → levelField；
     * 名称含 message/msg/log → messageField；名称含 service/app → serviceField；名称含 trace → traceIdField。
     */
    public List<FieldInfo> probe(String baseUrl, String indexPattern) {
        String resp = esLogClient.get(baseUrl, "/" + indexPattern + "/_mapping");
        List<FieldInfo> fields = new ArrayList<>();
        try {
            JsonNode root = objectMapper.readTree(resp);
            // 多索引时取并集
            Map.Entry<String, JsonNode> first = root.fields().next();
            JsonNode mappings = first.getValue().path("mappings");
            flatten(mappings.path("properties"), "", fields);
        } catch (Exception e) {
            log.error("[ES] 字段探测解析失败: {}", e.getMessage());
        }
        log.info("[ES] 字段探测完成: indexPattern={}, fields={}", indexPattern, fields.size());
        return fields;
    }

    private void flatten(JsonNode properties, String prefix, List<FieldInfo> out) {
        if (!properties.isObject()) {
            return;
        }
        Iterator<Map.Entry<String, JsonNode>> it = properties.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            String name = prefix.isEmpty() ? e.getKey() : prefix + "." + e.getKey();
            JsonNode def = e.getValue();
            String type = def.path("type").asText("object");
            if ("object".equals(type) && def.has("properties")) {
                flatten(def.path("properties"), name, out);
                continue;
            }
            out.add(new FieldInfo(name, type, suggestRole(name, type)));
        }
    }

    private String suggestRole(String name, String type) {
        String lower = name.toLowerCase();
        if ("date".equals(type) || "long".equals(type) && (lower.contains("time") || lower.contains("timestamp"))) {
            return "timeField";
        }
        if (lower.contains("level") || lower.contains("severity")) {
            return "levelField";
        }
        if (lower.contains("message") || lower.contains("msg") || lower.contains("log")) {
            return "messageField";
        }
        if (lower.contains("service") || lower.contains("app") || lower.contains("application")) {
            return "serviceField";
        }
        if (lower.contains("trace")) {
            return "traceIdField";
        }
        return null;
    }
}
