package com.aiops.module.log.drain;

import lombok.Data;

/**
 * Drain 参数在线调优的 PATCH DTO（全部字段可选；null = 不修改）。
 */
@Data
public class DrainConfigPatch {
    private Integer depth;
    private Double simTh;
    private Integer maxChildren;
    private Integer maxCluster;
    private char[] extraDelimiters;
}
