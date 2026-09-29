package com.aiops.module.log.drain;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Drain 日志模板提取算法参数（§7.2）。
 *
 * 与 LogPAI 开源版本字段语义保持一致；改动任一字段都必须重建解析树
 * （解析器内部状态依赖这些超参），由 DrainService.updateParams 触发。
 */
@Data
@Component
@ConfigurationProperties(prefix = "aiops.drain")
public class DrainConfig {

    /**
     * 前缀树最大深度（第 1 层 length / 第 2 层 firstToken / 第 3+ 层按 token 进入子节点），>=3。
     */
    private int depth = 4;

    /**
     * 模板相似度阈值 simTh，(0,1)。
     * SeqDist >= simTh 视作同一模板；分母不计入 &lt;*&gt; 占位符。
     */
    private double simTh = 0.4;

    /**
     * 每个内部节点的最大子节点数。超出后统一进 &lt;*&gt; 桶，
     * 防止在第一/第二个 token 上爆炸（典型：服务名/token 高基数字段）。
     */
    private int maxChildren = 100;

    /**
     * 单解析器允许的最大 cluster 数（LRU 淘汰最久未命中）。
     * 防止内存被一次性突发事件打爆。
     */
    private int maxCluster = 1000;

    /**
     * 在空白之外的额外 token 分隔符。出现于整行时统一替换为空格后再分词。
     * 经典默认集：, = | : ( ) [ ] { } " '
     */
    private char[] extraDelimiters = ",=|:()[]{}\"'".toCharArray();

    /** 深拷贝，用于在线调参触发重建时不共享底层数组。 */
    public DrainConfig copy() {
        DrainConfig c = new DrainConfig();
        c.depth = this.depth;
        c.simTh = this.simTh;
        c.maxChildren = this.maxChildren;
        c.maxCluster = this.maxCluster;
        c.extraDelimiters = this.extraDelimiters == null ? null : this.extraDelimiters.clone();
        return c;
    }
}
