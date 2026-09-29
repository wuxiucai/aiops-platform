package com.aiops.module.log.drain;

import com.aiops.common.BizException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Drain 解析器路由 + 在线参数调优。
 *
 * 每个 (datasourceId, indexConfigId) 持有一个 DrainParser 实例（key="ds{dsId}_idx{idxId}"），
 * 因为不同业务/索引的日志 template 分布差异大，共享一棵树会互相污染。
 *
 * 在线调参通过 updateParams → 深拷贝 baseConfig → 应用 patch → 重建该 key 的
 * DrainParser（已有模板树丢弃）。论文 §7.2 说明：超参变更后必须重新训练。
 */
@Service
@RequiredArgsConstructor
public class DrainService {

    /** Spring 注入的 yml 默认参数；在线调参时覆写到 perKeyOverride，不影响其他 key。 */
    private final DrainConfig baseConfig;

    /** (dsId,idxId) → DrainParser */
    private final Map<String, DrainParser> parsers = new ConcurrentHashMap<>();

    /** 每个 key 的在线覆写参数；updateParams 后写入，reset 后保留 */
    private final Map<String, DrainConfig> perKeyOverride = new ConcurrentHashMap<>();

    private static String key(long dsId, long idxId) {
        return "ds" + dsId + "_idx" + idxId;
    }

    /** 不存在则建一棵空树。 */
    public DrainParser parser(long dsId, long idxId) {
        return parsers.computeIfAbsent(key(dsId, idxId),
                k -> new DrainParser(resolveConfig(k)));
    }

    private DrainConfig resolveConfig(String key) {
        DrainConfig override = perKeyOverride.get(key);
        return (override != null ? override : baseConfig).copy();
    }

    /** 核心：解析一条日志，不落库。 */
    public DrainParseResult parse(long dsId, long idxId, String content) {
        if (content == null || content.isBlank()) {
            throw new BizException("日志内容不能为空");
        }
        DrainParser.LogCluster c = parser(dsId, idxId).addLogMessage(content);
        return DrainParseResult.builder()
                .clusterId(c.getClusterId())
                .templateText(c.getTemplateText())
                .tokenCount(c.getTokenCount())
                .isNewCluster(c.isNewCluster())
                .parameters(c.getLastParameters())
                .build();
    }

    /** 重建空树。per-key 覆写参数保留。 */
    public void reset(long dsId, long idxId) {
        String k = key(dsId, idxId);
        parsers.put(k, new DrainParser(resolveConfig(k)));
    }

    /** 返回当前生效参数 + 实时 cluster 计数。 */
    public Map<String, Object> snapshotParams(long dsId, long idxId) {
        DrainParser p = parser(dsId, idxId);
        DrainConfig c = p.getConfig();
        Map<String, Object> m = new HashMap<>();
        m.put("depth", c.getDepth());
        m.put("simTh", c.getSimTh());
        m.put("maxChildren", c.getMaxChildren());
        m.put("maxCluster", c.getMaxCluster());
        m.put("extraDelimiters", c.getExtraDelimiters() == null ? "" : new String(c.getExtraDelimiters()));
        m.put("currentClusterCount", p.clusterCount());
        return m;
    }

    /**
     * 应用 patch → 覆写 per-key 参数 → 重建解析器（任务书：参数变了，树必须重新训练）。
     * @return 新参数快照
     */
    public Map<String, Object> updateParams(long dsId, long idxId, DrainConfigPatch patch) {
        if (patch == null) throw new BizException("patch 不能为空");
        String k = key(dsId, idxId);

        DrainConfig cur = perKeyOverride.getOrDefault(k, baseConfig).copy();
        if (patch.getDepth() != null)           cur.setDepth(patch.getDepth());
        if (patch.getSimTh() != null)           cur.setSimTh(patch.getSimTh());
        if (patch.getMaxChildren() != null)     cur.setMaxChildren(patch.getMaxChildren());
        if (patch.getMaxCluster() != null)      cur.setMaxCluster(patch.getMaxCluster());
        if (patch.getExtraDelimiters() != null) cur.setExtraDelimiters(patch.getExtraDelimiters().clone());

        // 边界校验（不合法直接抛，让用户明确的看到失败原因）
        if (cur.getDepth() < 3)                       throw new BizException("depth >= 3");
        if (cur.getSimTh() <= 0 || cur.getSimTh() >= 1) throw new BizException("simTh in (0,1)");
        if (cur.getMaxChildren() < 1)                 throw new BizException("maxChildren >= 1");
        if (cur.getMaxCluster() < 1)                  throw new BizException("maxCluster >= 1");

        perKeyOverride.put(k, cur);
        reset(dsId, idxId);
        return snapshotParams(dsId, idxId);
    }
}
