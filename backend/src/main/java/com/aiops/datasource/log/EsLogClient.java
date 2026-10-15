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

    /**
     * 日志检索/模板训练的响应体可能到 MB 级（5000 条 _source），
     * WebClient 默认 maxInMemorySize=256KB 会抛
     * "Exceeded limit on max bytes to buffer : 262144"，故显式抬高到 32MB。
     */
    private final WebClient.Builder webClientBuilder = WebClient.builder()
            .codecs(c -> c.defaultCodecs().maxInMemorySize(32 * 1024 * 1024));

    /** 显式禁止的写操作端点（先查黑名单，防止白名单关键词被子串绕过） */
    private static final Set<String> DENIED_PATH_KEYWORDS = Set.of(
            "_bulk", "_index", "_update", "_delete");

    private static final Set<String> ALLOWED_PATH_KEYWORDS = Set.of(
            "_search", "_mapping", "_cat", "_cluster", "_count", "_msearch", "_field_caps");

    /** 只读白名单校验（GET / 为集群根信息，连通测试必需） */
    public void assertReadOnly(String path, HttpMethod method) {
        if (method == HttpMethod.DELETE || method == HttpMethod.PUT) {
            throw new BizException("ES只读");
        }
        // 集群根路径只读信息放行
        if (path.equals("/") || path.isEmpty()) {
            return;
        }
        // 黑名单优先：任何写端点直接拒绝（含白名单关键词伪装场景）
        for (String denied : DENIED_PATH_KEYWORDS) {
            if (path.contains(denied)) {
                throw new BizException("ES只读");
            }
        }
        boolean allowed = ALLOWED_PATH_KEYWORDS.stream().anyMatch(path::contains)
                || path.startsWith("/_cat") || path.startsWith("/_cluster");
        if (!allowed) {
            throw new BizException("ES只读");
        }
    }

    /**
     * path normalize: caller may pass "aiops-log-*\/_search" (no leading slash),
     * so that baseUrl + path always parses correctly.
     */
    private static String norm(String path) {
        if (path == null || path.isBlank()) {
            return "/";
        }
        return path.startsWith("/") ? path : "/" + path;
    }

    /** GET 请求（连通测试/_cat/_mapping 等） */
    public String get(String baseUrl, String path) {
        path = norm(path);
        assertReadOnly(path, HttpMethod.GET);
        long start = System.currentTimeMillis();
        try {
            String resp = webClientBuilder.baseUrl(baseUrl).build()
                    .get()
                    .uri(path)
                    .exchangeToMono(r -> r.bodyToMono(String.class).defaultIfEmpty("")
                            .map(body -> requireSuccess(r.statusCode().value(), body)))
                    .block(Duration.ofSeconds(30));
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
        path = norm(path);
        assertReadOnly(path, HttpMethod.POST);
        if (!path.contains("_search") && !path.contains("_msearch") && !path.contains("_count")) {
            throw new BizException("ES只读");
        }
        long start = System.currentTimeMillis();
        try {
            // 用 exchangeToMono：retrieve() 在某些线程上下文（调度池）里会把 200 OK 的纯文本响应当作错误
            String resp = webClientBuilder.baseUrl(baseUrl).build()
                    .post()
                    .uri(path)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(jsonBody)
                    .exchangeToMono(r -> r.bodyToMono(String.class)
                            .defaultIfEmpty("")
                            .map(body -> requireSuccess(r.statusCode().value(), body)))
                    .block(Duration.ofSeconds(60));
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

    private static final java.util.regex.Pattern ES_REASON =
            java.util.regex.Pattern.compile("\"reason\"\\s*:\\s*\"([^\"]{1,300})\"");

    /**
     * ES 返回 4xx/5xx 时响应体是 {"error":{...},"status":400}，不检查状态码就会被调用方当作
     * "查询成功、0 命中"——索引不存在、DSL 写错、权限不足在界面上都表现为"没有日志"。
     */
    static String requireSuccess(int status, String body) {
        if (status >= 200 && status < 300) {
            return body;
        }
        java.util.regex.Matcher m = ES_REASON.matcher(body == null ? "" : body);
        String reason = m.find() ? m.group(1) : (body == null || body.isBlank() ? "无响应体" : body.substring(0, Math.min(body.length(), 200)));
        throw new BizException("ES 返回 " + status + "：" + reason);
    }
}
