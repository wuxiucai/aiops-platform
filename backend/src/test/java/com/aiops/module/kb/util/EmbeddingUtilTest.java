package com.aiops.module.kb.util;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * M5-11 嵌入 cosine 相似度工具单测。
 * 核心用例：cosine 数学性质 + JSON 序列化往返 + 兜底（null/短向量/不同维度）。
 */
class EmbeddingUtilTest {

    @Test
    void cosineIdenticalVectorsIsOne() {
        List<Float> v = List.of(1f, 2f, 3f);
        assertEquals(1.0, EmbeddingUtil.cosine(v, v), 1e-6);
    }

    @Test
    void cosineOrthogonalIsZero() {
        List<Float> a = List.of(1f, 0f, 0f);
        List<Float> b = List.of(0f, 1f, 0f);
        assertEquals(0.0, EmbeddingUtil.cosine(a, b), 1e-6);
    }

    @Test
    void cosineNormalizedBetweenMinusOneAndOne() {
        List<Float> a = List.of(1f, 1f);
        List<Float> b = List.of(1f, -1f);
        assertEquals(0.0, EmbeddingUtil.cosine(a, b), 1e-6);
    }

    @Test
    void cosineMismatchedDimsTruncates() {
        List<Float> a = List.of(1f, 0f, 0f);
        List<Float> b = List.of(1f, 0f);
        // 只在交集 [0,1] 上计算 → cos = 1
        assertEquals(1.0, EmbeddingUtil.cosine(a, b), 1e-6);
    }

    @Test
    void cosineZeroVectorsReturnZero() {
        List<Float> z = List.of(0f, 0f);
        List<Float> v = List.of(1f, 2f);
        assertEquals(0.0, EmbeddingUtil.cosine(z, v), 1e-6);
    }

    @Test
    void parseEmbeddingValid() {
        String json = "[0.1, 0.2, 0.3]";
        List<Float> v = EmbeddingUtil.parseEmbedding(json);
        assertNotNull(v);
        assertEquals(3, v.size());
        assertEquals(0.1f, v.get(0), 1e-6);
    }

    @Test
    void parseEmbeddingInvalidReturnsNull() {
        assertNull(EmbeddingUtil.parseEmbedding(null));
        assertNull(EmbeddingUtil.parseEmbedding(""));
        assertNull(EmbeddingUtil.parseEmbedding("not json"));
        assertNull(EmbeddingUtil.parseEmbedding("{\"a\":1}"));
    }

    @Test
    void toJsonRoundTrip() {
        List<Float> v = List.of(0.5f, -0.3f, 0.7f);
        String json = EmbeddingUtil.toJson(v);
        assertNotNull(json);
        List<Float> back = EmbeddingUtil.parseEmbedding(json);
        assertNotNull(back);
        assertEquals(v.size(), back.size());
        for (int i = 0; i < v.size(); i++) {
            assertEquals(v.get(i), back.get(i), 1e-6);
        }
    }
}
