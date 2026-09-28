package com.aiops.datasource.log;

import com.aiops.common.BizException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.Set;

/**
 * ES HTTP 客户端（§9 只读红线）。
 * - 只允许 GET / POST _search / _mapping / _cat / _cluster；
 * - 任何写操作（_bulk/_index/_update/_delete_by_query/DELETE 方法）在代码层面直接拒绝；
 * - 统一使用 Spring WebClient，绝不引入 ES Java 客户端。
 */
@Slf4j
@Component
public class EsLogClient {

    private final WebClient.Builder webClientBuilder = WebClient.builder();

    private static final Set<String> ALLOWED_PATH_KEYWORDS = Set.of(
            "_search", "_mapping", "_cat", "_cluster", "_count", "_msearch", "_field_caps");

    /** 只读白名单校验（GET / 为集群根信息，连通测试必需） */
    public void assertReadOnly(String path, HttpMethod method) {
        if (method == HttpMethod.DELETE || method == HttpMethod.PUT) {
            throw new BizException("ES只读");
        }
        // 集群根路径与 _plugins/_nodes 等只读信息放行
        if (path.equals("/") || path.isEmpty()) {
            return;
        }
        boolean allowed = ALLOWED_PATH_KEYWORDS.stream().anyMatch(path::contains)
                || path.startsWith("/_cat") || path.startsWith("/_cluster");
        if (!allowed) {
            throw new BizException("ES只读");
        }
    }

    /** GET 请求（连通测试/_cat/_mapping 等） */
    public String get(String baseUrl, String path) {
        assertReadOnly(path, HttpMethod.GET);
        long start = System.currentTimeMillis();
        try {
            String resp = webClientBuilder.baseUrl(baseUrl).build()
                    .get()
                    .uri(path)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(Duration.ofSeconds(15));
            log.info("[ES] GET {}{} 耗时 {}ms", baseUrl, path, System.currentTimeMillis() - start);
            return resp;
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.error("[ES] GET {}{} 失败: {}", baseUrl, path, e.getMessage());
            throw new BizException("ES 调用失败: " + e.getMessage());
        }
    }

    /** POST _search 请求 */
    public String postSearch(String baseUrl, String path, String jsonBody) {
        assertReadOnly(path, HttpMethod.POST);
        if (!path.contains("_search") && !path.contains("_msearch") && !path.contains("_count")) {
            throw new BizException("ES只读");
        }
        long start = System.currentTimeMillis();
        try {
            String resp = webClientBuilder.baseUrl(baseUrl).build()
                    .post()
                    .uri(path)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(jsonBody)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(Duration.ofSeconds(30));
            log.info("[ES] POST {}{} bodyLen={} 耗时 {}ms",
                    baseUrl, path, jsonBody.length(), System.currentTimeMillis() - start);
            return resp;
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.error("[ES] POST {}{} 失败: {}", baseUrl, path, e.getMessage());
            throw new BizException("ES 调用失败: " + e.getMessage());
        }
    }
}
