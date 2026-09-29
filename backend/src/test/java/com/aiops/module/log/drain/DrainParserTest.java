package com.aiops.module.log.drain;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DrainParser 单测（§7.2 论文核心算法验收）。
 * 不依赖 Spring，纯算法。
 */
class DrainParserTest {

    private DrainConfig cfg;
    private DrainParser parser;

    @BeforeEach
    void setUp() {
        cfg = new DrainConfig();          // depth=4, simTh=0.4, maxChildren=100, maxCluster=1000
        parser = new DrainParser(cfg);
    }

    /* ================= 1) 预处理：时间戳 / IP / 数字 / uuid / hex / 路径归一化 ================= */

    @Test
    void preprocessStripsIsoTimestamp() {
        List<String> tk = parser.preprocess(
                "2027-05-01T12:34:56.789+08:00 INFO order-service Order placed");
        assertFalse(tk.stream().anyMatch(s -> s.contains("2027-05-01")),
                "时间戳应被清除；实际=" + tk);
        assertEquals(List.of("INFO", "order-service", "Order", "placed"), tk);
    }

    @Test
    void preprocessStripsSpaceSeparatedTimestamp() {
        List<String> tk = parser.preprocess("2027-05-01 12:34:56 INFO hello");
        assertEquals(List.of("INFO", "hello"), tk);
    }

    @Test
    void preprocessMasksIpNumHexUuidPath() {
        List<String> tk = parser.preprocess(
                "conn from 192.168.1.10 port 8080 txn=abc123def456 "
                        + "trace=550e8400-e29b-41d4-a716-446655440000 "
                        + "path=/api/v1/orders/123");
        assertTrue(tk.contains("<IP>"),  "IP 未掩码：" + tk);
        assertTrue(tk.contains("<NUM>"), "8080 未掩码：" + tk);
        assertTrue(tk.contains("<HEX>"), "abc123def456 未掩码：" + tk);
        assertTrue(tk.contains("<UUID>"),"uuid 未掩码：" + tk);
        assertTrue(tk.contains("<PATH>"),"路径未掩码：" + tk);
    }

    @Test
    void preprocessExtraDelimitersAreReplaced() {
        // 默认集 ,=|:()[]{}"' 出现于行内会被替换为空格
        List<String> tk = parser.preprocess("user=(alice),action=\"login\"");
        assertEquals(List.of("user", "alice", "action", "login"), tk);
    }

    /* ================= 2) 新模板：首条日志必新建 ================= */

    @Test
    void firstMessageCreatesNewCluster() {
        DrainParser.LogCluster c = parser.addLogMessage("INFO UserService login success");
        assertTrue(c.isNewCluster());
        assertEquals(4, c.getTokenCount());
        assertEquals("INFO UserService login success", c.getTemplateText());
        assertEquals(1, parser.clusterCount());
    }

    /* ================= 3) 相似度阈值：< vs >= simTh ================= */

    @Test
    void aboveThresholdMergesToExisting() {
        parser.addLogMessage("INFO UserService login success");
        DrainParser.LogCluster c2 = parser.addLogMessage("INFO UserService login failed");
        assertFalse(c2.isNewCluster(), "3/4 相同 → sim=0.75 ≥ 0.4，应合并");
        assertEquals(1, parser.clusterCount());
        // 末尾 success vs failed → <*>
        assertEquals("INFO UserService login <*>", c2.getTemplateText());
    }

    @Test
    void belowThresholdCreatesNewCluster() {
        cfg.setSimTh(0.9); // 收紧
        parser = new DrainParser(cfg);
        parser.addLogMessage("INFO A B C D E F G H");
        // 与上面只有 4/8 相同 → 0.5 < 0.9，新建
        DrainParser.LogCluster c2 = parser.addLogMessage("INFO A B C X Y Z W Q");
        assertTrue(c2.isNewCluster(), "低于 simTh 应新建：" + c2.getTemplateText());
        assertEquals(2, parser.clusterCount());
    }

    /* ================= 4) 参数提取 ================= */

    @Test
    void extractParametersFromWildcardPositions() {
        // 用 depth=3 让「变化位」位于叶子层（depth=3 时第 2 层后不再深入，模板存于叶子）
        cfg.setDepth(3);
        parser = new DrainParser(cfg);

        parser.addLogMessage("login from <*> port <*>");
        DrainParser.LogCluster c = parser.addLogMessage("login from 10.0.0.1 port 8080");
        assertFalse(c.isNewCluster(), "应命中已有模板");
        // 模板位置 2 / 4 是 <*>；raw 对应位置经预处理已被掩码为 <IP> / <NUM>
        assertEquals(List.of("<IP>", "<NUM>"), c.getLastParameters());
    }

    @Test
    void extractParametersPreservesLiteralTokens() {
        cfg.setDepth(3);
        parser = new DrainParser(cfg);
        parser.addLogMessage("order id <*> created");
        DrainParser.LogCluster c = parser.addLogMessage("order id ORD-999 created");
        assertFalse(c.isNewCluster());
        assertEquals(List.of("ORD-999"), c.getLastParameters());
    }

    /* ================= 5) 模板泛化：5 条不同 traceId 的同模式日志合并 ================= */

    @Test
    void generalizeAcrossTraceIdsIntoSingleTemplate() {
        String[] traces = {"t-001", "t-002", "t-003", "t-004", "t-005"};
        for (String t : traces) {
            parser.addLogMessage("INFO OrderService create order traceId=" + t + " success");
        }
        // 5 条同模式日志合并为 1 个 cluster
        assertEquals(1, parser.clusterCount());

        // 再来一条不同 traceId 的日志：仍命中已有模板
        DrainParser.LogCluster c = parser.addLogMessage(
                "INFO OrderService create order traceId=t-999 success");
        assertFalse(c.isNewCluster());

        // 模板中恰好 1 个 <*>
        long star = java.util.Arrays.stream(c.getTemplateText().split(" "))
                .filter("<*>"::equals).count();
        assertEquals(1, star, "模板应只含 1 个 <*>：" + c.getTemplateText());

        // 参数提取应包含 raw traceId t-999
        assertTrue(c.getLastParameters().contains("t-999"),
                "parameters 应包含 t-999：" + c.getLastParameters());
    }

    /* ================= 6) maxChildren 收紧后落到 <*> 桶 ================= */

    @Test
    void maxChildrenPushesIntoWildcardBucket() {
        cfg.setMaxChildren(2);
        parser = new DrainParser(cfg);
        parser.addLogMessage("AAA x y");
        parser.addLogMessage("BBB x y");
        DrainParser.LogCluster c1 = parser.addLogMessage("CCC x y"); // 落到 <*> 桶
        assertTrue(c1.isNewCluster());
        // 第四条 DDD 也落 <*> 桶，且与 CCC 仅 firstTok 不同 → sim=2/3=0.667 ≥ 0.4 合并
        DrainParser.LogCluster c2 = parser.addLogMessage("DDD x y");
        assertFalse(c2.isNewCluster(), "同样落 <*> 桶且 sim=2/3 应合并");
        assertEquals("<*> x y", c2.getTemplateText());
    }

    /* ================= 7) length 不同必新建（树第 1 层是 length） ================= */

    @Test
    void differentLengthFormsDifferentCluster() {
        parser.addLogMessage("INFO hello world");
        DrainParser.LogCluster c = parser.addLogMessage("INFO hello world again");
        assertTrue(c.isNewCluster(), "长度不同落到不同 length node，必新建");
        assertEquals(2, parser.clusterCount());
    }
}
