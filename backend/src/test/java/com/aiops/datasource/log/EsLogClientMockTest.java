package com.aiops.datasource.log;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * EsLogClient 请求构造单测（M1 审查补充项）。
 * 用 MockWebServer 模拟 ES 响应，验证 method/path/body 的构造与只读白名单拦截，
 * 保证 M4 实测时客户端不是"从未真的发过请求"。
 */
class EsLogClientMockTest {

    private MockWebServer server;
    private EsLogClient client;
    private String baseUrl;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        baseUrl = server.url("/").toString().replaceAll("/$", "");
        client = new EsLogClient();
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    @Test
    void getClusterRootSendsCorrectRequest() throws Exception {
        server.enqueue(new MockResponse()
                .setBody("{\"version\":{\"number\":\"7.17.10\"},\"cluster_name\":\"es-local\"}")
                .setHeader("Content-Type", "application/json"));

        String resp = client.get(baseUrl, "/");
        assertTrue(resp.contains("7.17.10"));

        RecordedRequest recorded = server.takeRequest();
        assertEquals("GET", recorded.getMethod());
        assertEquals("/", recorded.getPath());
    }

    @Test
    void postSearchSendsJsonBody() throws Exception {
        server.enqueue(new MockResponse()
                .setBody("{\"hits\":{\"total\":{\"value\":1},\"hits\":[]}}")
                .setHeader("Content-Type", "application/json"));

        String dsl = "{\"query\":{\"match_all\":{}},\"size\":10}";
        String resp = client.postSearch(baseUrl, "/aiops-log-*/_search", dsl);
        assertTrue(resp.contains("\"value\":1"));

        RecordedRequest recorded = server.takeRequest();
        assertEquals("POST", recorded.getMethod());
        assertEquals("/aiops-log-*/_search", recorded.getPath());
        String body = recorded.getBody().readString(StandardCharsets.UTF_8);
        assertEquals(dsl, body);
    }

    @Test
    void getMappingAndCatPathsPass() throws Exception {
        server.enqueue(new MockResponse().setBody("{}"));
        client.get(baseUrl, "/aiops-log-*/_mapping");
        RecordedRequest r1 = server.takeRequest();
        assertEquals("GET", r1.getMethod());
        assertEquals("/aiops-log-*/_mapping", r1.getPath());

        server.enqueue(new MockResponse().setBody("[]"));
        client.get(baseUrl, "/_cat/indices?format=json");
        RecordedRequest r2 = server.takeRequest();
        assertEquals("/_cat/indices?format=json", r2.getPath());
    }

    @Test
    void deleteMethodRejected() {
        // DELETE/PUT 方法在 assertReadOnly 直接拒绝
        assertThrows(com.aiops.common.BizException.class,
                () -> client.assertReadOnly("/aiops-log-*/_doc/1", HttpMethod.DELETE));
        assertThrows(com.aiops.common.BizException.class,
                () -> client.assertReadOnly("/aiops-log-1", HttpMethod.PUT));
        // GET 到不在白名单的写路径同样拒绝
        assertThrows(com.aiops.common.BizException.class,
                () -> client.get(baseUrl, "/aiops-log-1/_doc/1"));
    }

    @Test
    void writePathRejectedByWhitelist() {
        // _bulk / _index / _update / _delete_by_query 均不在白名单
        assertThrows(com.aiops.common.BizException.class,
                () -> client.postSearch(baseUrl, "/_bulk", "{}"));
        assertThrows(com.aiops.common.BizException.class,
                () -> client.postSearch(baseUrl, "/aiops-log-1/_update/1", "{}"));
        assertThrows(com.aiops.common.BizException.class,
                () -> client.postSearch(baseUrl, "/aiops-log-*/_delete_by_query", "{}"));
    }

    @Test
    void postToNonSearchPathRejected() {
        // POST 但 path 不含 _search/_msearch/_count → 拒绝
        assertThrows(com.aiops.common.BizException.class,
                () -> client.postSearch(baseUrl, "/aiops-log-1/_mapping", "{}"));
    }
}
