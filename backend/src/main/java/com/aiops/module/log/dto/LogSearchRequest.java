package com.aiops.module.log.dto;

import lombok.Data;

import java.util.List;

/**
 * 日志检索 / 时间直方图请求。
 * startTime / endTime 为平台本地时间 "yyyy-MM-dd HH:mm:ss"（也接受 T 分隔）；
 * datasourceId / indexConfigId 为空时由 LogSourceResolver 落到默认数据源。
 */
@Data
public class LogSearchRequest {
    private Long datasourceId;
    private Long indexConfigId;
    private String startTime;
    private String endTime;
    private List<String> levels;
    private List<String> services;
    private String keyword;
    private String traceId;
    private Integer page;
    private Integer size;
    private String analyzer;
}
