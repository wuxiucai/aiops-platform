package com.aiops.module.log.controller;

import com.aiops.module.log.entity.NlQueryLog;
import com.aiops.module.log.service.Nl2DslService;
import com.aiops.security.LoginUser;
import com.aiops.security.UserContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * M6-3 controller 单测：用 standaloneSetup MockMvc，确保未做真 Spring 启动。
 *
 * <p>
 * 因为不进 Spring Context，故 RequirePerm 也不会被 HandlerInterceptor 触发；
 * 本测试在调用前手工 UserContext.set(admin-user) 校验控制器层 userId 提取路径。
 */
class Nl2DslControllerTest {

    private Nl2DslService service;
    private Nl2DslController controller;
    private MockMvc mvc;
    private final ObjectMapper om = new ObjectMapper();

    @BeforeEach
    void setUp() {
        service = Mockito.mock(Nl2DslService.class);
        controller = new Nl2DslController(service);
        mvc = MockMvcBuilders.standaloneSetup(controller).build();

        // 手工装一个 admin 用户（拥有全部权限），让 controller.currentUserId 拿到非空 id
        LoginUser admin = new LoginUser();
        admin.setUserId(99L);
        admin.setUsername("admin");
        admin.setPerms(Set.of("*:*:*"));
        admin.setRoles(Set.of("ADMIN"));
        UserContext.set(admin);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    /* ================== POST /api/log/ai/nl2dsl (应成功 → validated=true) ================== */
    @Test
    void postNl2DslReturnsOk() throws Exception {
        Map<String, Object> fakeOut = new LinkedHashMap<>();
        fakeOut.put("dsl", Map.of("query", Map.of("bool", Map.of()), "size", 20));
        fakeOut.put("validated", true);
        fakeOut.put("retryCount", 0);
        fakeOut.put("recordId", 7L);
        when(service.generate(eq(99L), anyString())).thenReturn(fakeOut);

        Map<String, Object> body = new HashMap<>();
        body.put("question", "过去 1 小时 ERROR 日志");

        mvc.perform(post("/api/log/ai/nl2dsl")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.validated").value(true))
                .andExpect(jsonPath("$.data.recordId").value(7))
                .andExpect(jsonPath("$.data.retryCount").value(0));

        Mockito.verify(service).generate(eq(99L), eq("过去 1 小时 ERROR 日志"));
    }

    /* ================== POST /api/log/ai/nl2dsl (空 question → 4xx-style err) ================== */
    @Test
    void postNl2DslBlankQuestionThrows() throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("question", "");
        // 控制器抛 BizException —— standaloneSetup 下没有 GlobalExceptionHandler，
        // 断言 servlet 抛错即可。
        try {
            mvc.perform(post("/api/log/ai/nl2dsl")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(om.writeValueAsString(body)))
                    .andReturn();
        } catch (Exception expected) {
            // BizException wrapped by servlet - expected
        }
        Mockito.verify(service, Mockito.never()).generate(anyLong(), anyString());
    }

    /* ================== POST /api/log/ai/nl2dsl/execute ================== */
    @Test
    void postExecuteReturnsOk() throws Exception {
        Map<String, Object> fakeOut = new LinkedHashMap<>();
        fakeOut.put("hits", List.of(Map.of("service", "order-service", "level", "ERROR")));
        fakeOut.put("total", 42);
        fakeOut.put("serviceCounts", Map.of("order-service", 42L));
        fakeOut.put("elapsedMs", 12L);
        fakeOut.put("answer", "命中 42 条");
        when(service.execute(eq(99L), eq(7L))).thenReturn(fakeOut);

        Map<String, Object> body = new HashMap<>();
        body.put("recordId", 7L);

        mvc.perform(post("/api/log/ai/nl2dsl/execute")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.total").value(42))
                .andExpect(jsonPath("$.data.answer").value("命中 42 条"))
                .andExpect(jsonPath("$.data.elapsedMs").value(12));

        Mockito.verify(service).execute(99L, 7L);
    }

    /* ================== POST /api/log/ai/nl2dsl/execute 缺 recordId → BizException ================== */
    @Test
    void postExecuteMissingRecordIdThrows() throws Exception {
        Map<String, Object> body = new HashMap<>();
        try {
            mvc.perform(post("/api/log/ai/nl2dsl/execute")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(om.writeValueAsString(body)))
                    .andReturn();
        } catch (Exception expected) {
            // BizException wrapped by servlet
        }
        Mockito.verify(service, Mockito.never()).execute(anyLong(), anyLong());
    }

    /* ================== GET /api/log/nl-query-history ================== */
    @Test
    void getHistoryReturnsList() throws Exception {
        NlQueryLog l1 = new NlQueryLog();
        l1.setId(1L);
        l1.setUserId(99L);
        l1.setQuestion("最近 ERROR");
        l1.setValidated(1);
        l1.setExecuted(1);
        l1.setHitCount(3);
        l1.setCreateTime(LocalDateTime.now());
        when(service.history(eq(99L), eq(20))).thenReturn(List.of(l1));

        mvc.perform(get("/api/log/nl-query-history"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data[0].id").value(1))
                .andExpect(jsonPath("$.data[0].question").value("最近 ERROR"))
                .andExpect(jsonPath("$.data[0].hitCount").value(3));

        Mockito.verify(service).history(99L, 20);
    }

    /* ================== GET /api/log/nl-query-history?limit=5 ================== */
    @Test
    void getHistoryWithLimitParam() throws Exception {
        when(service.history(eq(99L), eq(5))).thenReturn(List.of());
        mvc.perform(get("/api/log/nl-query-history?limit=5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").isArray());
        Mockito.verify(service).history(99L, 5);
    }
}
