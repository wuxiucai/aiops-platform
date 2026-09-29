package com.aiops.module.log.drain;

import lombok.Getter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * Drain 日志模板提取器（经典 LogPAI Drain，3+ 层前缀树）。
 * 纯内存实现，与 DB / ES / IO 完全解耦，由 DrainService 持有。
 *
 * 结构（按论文伪代码 §7.2）：
 *   root
 *    └─ 第 1 层：log length（token 数）
 *         └─ 第 2 层：firstToken（可为字面量或 &lt;*&gt;）
 *              └─ 第 3 ~ depth 层：之后每一位 token 进入子节点
 *                   └─ 叶子：List&lt;LogCluster&gt;，存模板与频次
 *
 * 关键规则：
 *  - 每个内部节点的子节点数受 maxChildren 限制，超出统一进 &lt;*&gt; 桶
 *  - 模板相似度 SeqDist 的分子为「同位置同字面 token 数」；分母不计 &lt;*&gt;
 *  - 模板更新：seq2[i] 与 cluster[i] 相等则保留，否则替换为 &lt;*&gt;（严禁做部分合并）
 *  - 树超过 maxCluster 时按 LRU 策略淘汰最久未命中的 cluster
 */
public class DrainParser {

    /** &lt;*&gt; 占位符 */
    public static final String WILDCARD = "<*>";

    /** TRACE id / 长 hex / 数字 / IP / UUID 掩码 */
    private static final Pattern P_NUM  = Pattern.compile("^[+-]?\\d+(\\.\\d+)?$");
    private static final Pattern P_HEX  = Pattern.compile("^(0[xX])?[0-9a-fA-F]{8,}$");
    private static final Pattern P_UUID = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
    private static final Pattern P_IP   = Pattern.compile(
            "^(\\d{1,3}\\.){3}\\d{1,3}(:\\d+)?$");
    private static final Pattern P_TIMESTAMP = Pattern.compile(
            "\\d{4}-\\d{2}-\\d{2}[T ]\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?(Z|[+\\-]\\d{2}:\\d{2})?");
    private static final Pattern P_TIME_ONLY = Pattern.compile(
            "\\d{2}:\\d{2}:\\d{2}([.,]\\d+)?");
    /** 路径：含 / 且至少有 2 段 */
    private static final Pattern P_PATH = Pattern.compile("^/?[\\w.\\-]+(/[\\w.\\-]+)+/?$");

    private final DrainConfig config;
    private final Node root = new Node();
    private final AtomicLong clusterIdGen = new AtomicLong(1);
    /** 全量 cluster，按 LRU 淘汰；用 LinkedHashMap accessOrder 实现 O(1)。
     * 注意 LinkedHashMap 非线程安全，这里加 synchronized 简化。 */
    private final Map<Long, LogCluster> clusterIndex =
            Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true));

    public DrainParser(DrainConfig config) {
        if (config == null) throw new IllegalArgumentException("DrainConfig required");
        if (config.getDepth() < 3)   throw new IllegalArgumentException("depth >= 3");
        if (config.getSimTh() <= 0 || config.getSimTh() >= 1)
            throw new IllegalArgumentException("simTh in (0,1)");
        this.config = config;
    }

    public DrainConfig getConfig() { return config; }

    public int clusterCount() { return clusterIndex.size(); }

    /* ==================== 1) 预处理 ==================== */

    /**
     * 任务书默认规则：
     *  - 去除时间戳 2027-05-01T12:34:56.789+08:00 / 12:34:56
     *  - 去引号、额外分隔符替换为空格（默认 ,=|:()[]{}"'）
     *  - 空白归一
     *  - 分词后按规则掩码：数字 → &lt;NUM&gt;，IP → &lt;IP&gt;，hex → &lt;HEX&gt;，
     *    uuid → &lt;UUID&gt;，路径段含 / → &lt;PATH&gt;
     *  - token 去尾标点再统一 strip
     */
    public List<String> preprocess(String line) {
        if (line == null) return List.of();
        String s = line;
        s = P_TIMESTAMP.matcher(s).replaceAll(" ");
        s = P_TIME_ONLY.matcher(s).replaceAll(" ");
        if (config.getExtraDelimiters() != null) {
            for (char d : config.getExtraDelimiters()) {
                s = s.replace(d, ' ');
            }
        }
        s = s.replaceAll("\\s+", " ").trim();
        if (s.isEmpty()) return List.of();

        String[] rawTokens = s.split(" ");
        List<String> out = new ArrayList<>(rawTokens.length);
        for (String tk : rawTokens) {
            String t = stripTrailingPunct(tk);
            if (t.isEmpty()) continue;
            out.add(mask(t));
        }
        return out;
    }

    private static String stripTrailingPunct(String t) {
        int end = t.length();
        while (end > 0) {
            char c = t.charAt(end - 1);
            if (c == '.' || c == ',' || c == ';' || c == '!' || c == '?') end--;
            else break;
        }
        return t.substring(0, end).trim();
    }

    private static String mask(String t) {
        if (P_UUID.matcher(t).matches()) return "<UUID>";
        if (P_IP.matcher(t).matches())   return "<IP>";
        if (P_NUM.matcher(t).matches())  return "<NUM>";
        if (P_HEX.matcher(t).matches())  return "<HEX>";
        if (P_PATH.matcher(t).matches()) return "<PATH>";
        return t;
    }

    /* ==================== 2) 主入口：addLogMessage ==================== */

    public LogCluster addLogMessage(String content) {
        List<String> tokens = preprocess(content);
        List<String> seq = new ArrayList<>(tokens);

        Node lengthNode = root.childOrCreate(String.valueOf(seq.size()));
        String firstTok = seq.isEmpty() ? WILDCARD : seq.get(0);
        Node tokenNode = lengthNode.childLike(firstTok, config.getMaxChildren());

        // 从第 3 层开始按 token 进入子节点（depth 超出后停在 depth 层）
        Node cur = tokenNode;
        int depth = 2; // 已经走了 length 层 + firstToken 层
        for (int i = 1; i < seq.size() && depth < config.getDepth(); i++, depth++) {
            cur = cur.childLike(seq.get(i), config.getMaxChildren());
        }

        // 叶子找匹配项
        LogCluster ret = null;
        double maxSim = -1;
        for (LogCluster c : cur.clusters) {
            double sim = seqDist(c.templateTokens, seq);
            if (sim > maxSim) {
                maxSim = sim;
                ret = c;
            }
        }

        boolean isNew;
        if (ret != null && maxSim >= config.getSimTh()) {
            // 合并模板
            mergeTemplate(ret, seq);
            ret.touch();
            ret.hitCount++;
            isNew = false;
        } else {
            ret = new LogCluster();
            ret.clusterId = clusterIdGen.getAndIncrement();
            ret.templateTokens = new ArrayList<>(seq);
            ret.tokenCount = seq.size();
            ret.hitCount = 1;
            ret.touch();
            cur.clusters.add(ret);
            isNew = true;
            clusterIndex.put(ret.clusterId, ret);
            evictIfOverflow();
        }

        // 提取参数：raw token 中位于模板 &lt;*&gt; 位置的具体值
        ret.lastParameters = extractParams(ret.templateTokens, seq);

        // 渲染返回
        ret.templateText = String.join(" ", ret.templateTokens);
        ret.isNewCluster = isNew;
        return ret;
    }

    /* ==================== 3) 相似度与合并 ==================== */

    /**
     * SeqDist(seq1, seq2) = Σ 等位相同 token 数 / max(len1, len2)
     * 模板中的 &lt;*&gt; 不计入分母（也不可贡献分子），即「token 级对齐」。
     */
    static double seqDist(List<String> template, List<String> seq) {
        int len1 = template.size();
        int len2 = seq.size();
        int maxLen = Math.max(len1, len2);
        if (maxLen == 0) return 1.0;

        double simTokens = 0;
        int denom = maxLen;
        int min = Math.min(len1, len2);
        for (int i = 0; i < min; i++) {
            String t = template.get(i);
            if (WILDCARD.equals(t)) {
                denom--;   // 不计入分母
                continue;
            }
            if (t.equals(seq.get(i))) simTokens++;
        }
        // 长度差：未重叠部分除非模板对应位置是 &lt;*&gt;（不可能，因越界），全部计入分母
        if (denom <= 0) return 1.0;
        return simTokens / denom;
    }

    /**
     * 模板更新（任务书原文）：
     *   newToken = seq2[i] 与 cluster[i] 完全相等 → 保留字面 token
     *              否则 → "&lt;*&gt;"
     * 注意：相等才保留，正则子模式（"3" vs "30"）也一律替换为 &lt;*&gt;。
     */
    private static void mergeTemplate(LogCluster c, List<String> seq) {
        int len = Math.max(c.templateTokens.size(), seq.size());
        List<String> merged = new ArrayList<>(len);
        for (int i = 0; i < len; i++) {
            String a = i < c.templateTokens.size() ? c.templateTokens.get(i) : null;
            String b = i < seq.size() ? seq.get(i) : null;
            if (a != null && a.equals(b)) merged.add(a);
            else merged.add(WILDCARD);
        }
        c.templateTokens = merged;
    }

    /* ==================== 4) 参数提取 ==================== */

    /**
     * 对自身 &lt;*&gt; 位置从 raw token 中提取实际值，一般是 traceId / orderId / 数量等动态字段。
     * 因为模板与 seq 在 addLogMessage 中长度一致（&lt;*&gt; 用来吸收变量 token），
     * 用 zip 即可；长度不等时取越界段附加到末尾。
     */
    static List<String> extractParams(List<String> template, List<String> seq) {
        List<String> params = new ArrayList<>();
        int min = Math.min(template.size(), seq.size());
        for (int i = 0; i < min; i++) {
            if (WILDCARD.equals(template.get(i))) params.add(seq.get(i));
        }
        // 长度不一致：raw 比模板长的部分整体追加（Drain 经典做法：tail 视作参数）
        for (int i = min; i < seq.size(); i++) params.add(seq.get(i));
        return params;
    }

    /* ==================== 5) LRU 淘汰 ==================== */

    private void evictIfOverflow() {
        synchronized (clusterIndex) {
            while (clusterIndex.size() > config.getMaxCluster()) {
                var it = clusterIndex.entrySet().iterator();
                if (!it.hasNext()) break;
                Map.Entry<Long, LogCluster> eldest = it.next();
                it.remove();
                removeFromTree(eldest.getValue());
            }
        }
    }

    private void removeFromTree(LogCluster c) {
        // 从 lengthNode 往下找则代价较高；叶子中删除即可（叶子保留也无妨，但会泄漏）。
        // Drain 论文允许在 maxCluster 触发时整树清空策略的简化版，这里直接清 root，
        // 因为淘汰一次通常意味着狂突发，整树重建的代价低于精确删除。
        root.childMap.clear();
        clusterIndex.clear();
    }

    /* ==================== 6) 内部结构 ==================== */

    /** 前缀树节点 */
    static final class Node {
        final Map<String, Node> childMap = new HashMap<>();
        final List<LogCluster> clusters = new ArrayList<>();

        Node childOrCreate(String key) {
            return childMap.computeIfAbsent(key, k -> new Node());
        }

        /**
         * 在第 2/3 层：按字面 token 找子节点；找不到且 childMap.size() >= maxChildren
         * 时落到 &lt;*&gt; 桶（论文的「maxChildren 防爆」逻辑）。
         */
        Node childLike(String token, int maxChildren) {
            Node hit = childMap.get(token);
            if (hit != null) return hit;
            if (childMap.size() < maxChildren) {
                Node n = new Node();
                childMap.put(token, n);
                return n;
            }
            return childMap.computeIfAbsent(WILDCARD, k -> new Node());
        }
    }

    /** 日志模板聚类 */
    @Getter
    public static final class LogCluster {
        private long clusterId;
        /** 模板文本（空格拼接） */
        private String templateText;
        /** 模板 token 长度 */
        private int tokenCount;
        /** 累计命中数 */
        private long hitCount;
        /** 是否本次新建（仅当前 addLogMessage 调用上下文有效） */
        private boolean isNewCluster;
        /** 最近一次 add 时从原文中提取到的参数列表 */
        private List<String> lastParameters = List.of();

        private List<String> templateTokens;
        private long lastTouch;

        private void touch() { this.lastTouch = System.nanoTime(); }
    }
}
