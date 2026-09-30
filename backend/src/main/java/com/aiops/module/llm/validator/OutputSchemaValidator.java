package com.aiops.module.llm.validator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * M5 审查方补充约束 3：OutputSchemaValidator
 * JSON Schema 标准子集校验器（type / required / properties / enum / minimum / maximum / minLength / minItems）。
 * 零外部依赖（不引入 everit/networknt），实现毕设规模下工程产品级 schema 校验。
 * <p>每个 prompt_template.output_schema 列存放该场景的 JSON Schema；改 schema 立即生效。
 */
public class OutputSchemaValidator {

    private static final ObjectMapper OM = new ObjectMapper();

    /** 校验入口。返回 null = 通过；否则返回可读错误描述。 */
    public static String validate(String schemaJson, JsonNode data) {
        if (schemaJson == null || schemaJson.isBlank()) return null;
        try {
            JsonNode schema = OM.readTree(schemaJson);
            List<String> errors = new ArrayList<>();
            validateNode(schema, data, "$", errors);
            return errors.isEmpty() ? null : String.join("; ", errors);
        } catch (Exception e) {
            return "schema 解析失败: " + e.getMessage();
        }
    }

    private static void validateNode(JsonNode schema, JsonNode data, String path, List<String> errors) {
        if (schema == null || !schema.isObject()) return;

        String type = schema.path("type").asText(null);
        if (type != null) {
            boolean ok = switch (type) {
                case "object"  -> data != null && data.isObject();
                case "array"   -> data != null && data.isArray();
                case "string"  -> data != null && data.isTextual();
                case "number"  -> data != null && data.isNumber();
                case "integer" -> data != null && data.isIntegralNumber();
                case "boolean" -> data != null && data.isBoolean();
                case "null"    -> data == null || data.isNull();
                default -> true;
            };
            if (!ok) {
                errors.add(path + " expected type=" + type);
                return;
            }
        }

        JsonNode required = schema.path("required");
        if (required.isArray() && data != null && data.isObject()) {
            for (JsonNode r : required) {
                String f = r.asText();
                if (!data.has(f) || data.get(f).isNull()) {
                    errors.add(path + "." + f + " is required");
                }
            }
        }

        JsonNode props = schema.path("properties");
        if (props.isObject() && data != null && data.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> it = props.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                JsonNode sub = data.get(e.getKey());
                if (sub != null) validateNode(e.getValue(), sub, path + "." + e.getKey(), errors);
            }
        }

        JsonNode enumArr = schema.path("enum");
        if (enumArr.isArray() && data != null) {
            boolean hit = false;
            for (JsonNode ev : enumArr) if (ev.equals(data)) { hit = true; break; }
            if (!hit) errors.add(path + " value not in enum: " + data);
        }

        if (data != null && data.isTextual()) {
            int len = data.asText().length();
            int min = schema.path("minLength").asInt(-1);
            int max = schema.path("maxLength").asInt(-1);
            if (min >= 0 && len < min) errors.add(path + " length " + len + " < minLength " + min);
            if (max >= 0 && len > max) errors.add(path + " length " + len + " > maxLength " + max);
        }

        if (data != null && data.isNumber()) {
            double v = data.asDouble();
            if (schema.has("minimum") && v < schema.get("minimum").asDouble())
                errors.add(path + " value " + v + " < minimum");
            if (schema.has("maximum") && v > schema.get("maximum").asDouble())
                errors.add(path + " value " + v + " > maximum");
        }

        if (data != null && data.isArray()) {
            int minItems = schema.path("minItems").asInt(-1);
            if (minItems >= 0 && data.size() < minItems)
                errors.add(path + " items " + data.size() + " < minItems " + minItems);
            JsonNode items = schema.path("items");
            if (items.isObject()) {
                int i = 0;
                for (JsonNode child : data) {
                    validateNode(items, child, path + "[" + i + "]", errors);
                    i++;
                }
            }
        }
    }
}
