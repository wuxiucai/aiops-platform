package com.aiops.module.monitor.dto;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** agent 上报的单个指标点；metricKey / value 缺失的点会被跳过 */
@Data
public class AgentMetricPoint {
    private String metricKey;
    private BigDecimal value;
    /** 时间戳规则见 {@link AgentTimestampDeserializer}；为 null 时用接收时间 */
    @JsonDeserialize(using = AgentTimestampDeserializer.class)
    private LocalDateTime timestamp;
}
