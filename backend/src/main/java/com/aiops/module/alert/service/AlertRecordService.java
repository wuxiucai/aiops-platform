package com.aiops.module.alert.service;

import java.util.Map;

/**
 * 告警记录生命周期接口
 */
public interface AlertRecordService {

    /** 认领 / 解决 / 关闭 / 误报 */
    void changeStatus(Long alertId, String action, String username, String remark);

    /** 取该告警关联日志：target.log_service_name + 首触发时间 ± 10min → ES */
    Map<String, Object> relatedLogs(Long alertId);
}
