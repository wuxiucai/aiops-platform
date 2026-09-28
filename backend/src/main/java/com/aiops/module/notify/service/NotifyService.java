package com.aiops.module.notify.service;

import com.aiops.module.alert.entity.AlertRecord;
import com.aiops.module.alert.entity.AlertRule;

/**
 * 通知发送：inapp（写 sys_message） + webhook（POST JSON）
 */
public interface NotifyService {

    /** 按规则的通知渠道发送；失败渠道写 notify_record status=fail */
    void sendAlert(AlertRule rule, AlertRecord record, Long creatorUserId);
}
