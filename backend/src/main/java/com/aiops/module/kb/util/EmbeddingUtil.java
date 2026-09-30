package com.aiops.module.kb.util;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;

/**
 * 嵌入向量工具：JSON 解析 + cosine 相似度。
 * 毕设尺度下暴力遍历足够：千级数据毫秒返回。
 */
public class EmbeddingUtil {

    private static final ObjectMapper OM = new ObjectMapper();

    /** cosine(a, b)，维度不匹配返回 0 */
    public static double cosine(List<Float> a, List<Float> b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) return 0;
        int n = Math.min(a.size(), b.size());
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < n; i++) {
            float x = a.get(i), y = b.get(i);
            dot += x * y;
            na += x * x;
            nb += y * y;
        }
        if (na == 0 || nb == 0) return 0;
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    /** JSON string → List<Float>，解析失败返回 null */
    public static List<Float> parseEmbedding(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return OM.readValue(json, new TypeReference<List<Float>>() {});
        } catch (Exception e) {
            return null;
        }
    }

    /** List<Float> → JSON string */
    public static String toJson(List<Float> vec) {
        if (vec == null) return null;
        try {
            return OM.writeValueAsString(vec);
        } catch (Exception e) {
            return null;
        }
    }
}
