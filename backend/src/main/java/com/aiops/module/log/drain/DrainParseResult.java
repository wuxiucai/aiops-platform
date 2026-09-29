package com.aiops.module.log.drain;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * Drain 解析返回结果（REST 出参；不落库、不依赖 entity）。
 */
@Data
@Builder
public class DrainParseResult {

    /** 模板聚类 ID（解析器内自增，重启即重置） */
    private long clusterId;

    /** 模板文本，&lt;*&gt; 表示动态参数位 */
    private String templateText;

    /** 模板 token 数 */
    private int tokenCount;

    /** 本次 parse 是否触发了新模板 */
    @JsonProperty("isNewCluster")
    private boolean isNewCluster;

    /** 从原始日志在 &lt;*&gt; 位置提取到的具体参数 */
    private List<String> parameters;
}
