package com.aiops.module.incident.service;

import java.util.Map;

/**
 * 故障事件业务
 */
public interface IncidentService {

    /** 详情：incident + alerts + timeline */
    Map<String, Object> detail(Long incidentId);

    /** 一键解决：所有关联告警置 resolved，incident 置 resolved，写时间线 */
    void resolve(Long incidentId, String operator, String remark);

    /** 手动添加时间线 */
    void appendTimeline(Long incidentId, String eventType, String description, String operator);
}
