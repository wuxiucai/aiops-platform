package com.aiops.module.notify.service.impl;

import com.aiops.module.alert.entity.AlertRecord;
import com.aiops.module.alert.entity.AlertRule;
import com.aiops.module.notify.entity.NotifyChannel;
import com.aiops.module.notify.entity.NotifyRecord;
import com.aiops.module.notify.entity.SysMessage;
import com.aiops.module.notify.mapper.NotifyChannelMapper;
import com.aiops.module.notify.mapper.NotifyRecordMapper;
import com.aiops.module.notify.mapper.SysMessageMapper;
import com.aiops.module.notify.service.NotifyService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 通知发送实现：
 * - inapp：写 sys_message（user_id=规则创建者，未读）
 * - webhook：对 notify_channel 中 enabled=1 的 webhook 渠道 POST JSON，失败 status=fail
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotifyServiceImpl implements NotifyService {

    private final SysMessageMapper sysMessageMapper;
    private final NotifyRecordMapper notifyRecordMapper;
    private final NotifyChannelMapper notifyChannelMapper;
    private final WebClient.Builder webClientBuilder = WebClient.builder();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public void sendAlert(AlertRule rule, AlertRecord record, Long creatorUserId) {
        List<String> channels = parseChannels(rule.getNotifyChannels());
        if (channels.isEmpty()) {
            channels = List.of("inapp"); // 默认渠道：站内消息
        }
        for (String ch : channels) {
            try {
                if ("inapp".equalsIgnoreCase(ch)) {
                    sendInapp(rule, record, creatorUserId);
                } else if (ch.toLowerCase().startsWith("webhook")) {
                    sendWebhookAll(rule, record);
                } else {
                    log.info("[Notify] 忽略未实现渠道 {}", ch);
                }
            } catch (Exception e) {
                log.error("[Notify] 渠道 {} 发送失败: {}", ch, e.getMessage());
            }
        }
    }

    /** 站内核：写 sys_message，is_read=0 */
    private void sendInapp(AlertRule rule, AlertRecord record, Long creatorUserId) {
        SysMessage msg = new SysMessage();
        msg.setUserId(creatorUserId == null ? 1L : creatorUserId);
        msg.setTitle("[" + (record.getLevel() == null ? "WARN" : record.getLevel()) + "] " + record.getTitle());
        msg.setContent(record.getContent());
        msg.setMsgType("alert");
        msg.setRefId(record.getId());
        msg.setIsRead(0);
        msg.setCreateTime(LocalDateTime.now());
        sysMessageMapper.insert(msg);
        log.info("[Notify] inapp 已写 sys_message user={}, alertId={}", msg.getUserId(), record.getId());

        NotifyRecord nr = new NotifyRecord();
        nr.setRefType("alert");
        nr.setRefId(record.getId());
        nr.setChannelId(null);
        nr.setReceiver("user#" + msg.getUserId());
        nr.setContent(record.getTitle());
        nr.setStatus("success");
        nr.setSendTime(LocalDateTime.now());
        notifyRecordMapper.insert(nr);
    }

    /** 对所有 enabled=1 且 channel_type=webhook 的渠道发送 */
    private void sendWebhookAll(AlertRule rule, AlertRecord record) {
        List<NotifyChannel> list = notifyChannelMapper.selectList(new LambdaQueryWrapper<NotifyChannel>()
                .eq(NotifyChannel::getChannelType, "webhook")
                .eq(NotifyChannel::getEnabled, 1));
        for (NotifyChannel ch : list) {
            String url = extractUrl(ch.getConfig());
            NotifyRecord nr = new NotifyRecord();
            nr.setRefType("alert");
            nr.setRefId(record.getId());
            nr.setChannelId(ch.getId());
            nr.setReceiver(url);
            nr.setSendTime(LocalDateTime.now());
            try {
                if (url == null || url.isBlank()) {
                    throw new IllegalStateException("webhook url 未配置");
                }
                String body = buildWebhookBody(rule, record);
                nr.setContent(body);
                webClientBuilder.build().post()
                        .uri(url)
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(body)
                        .retrieve().toBodilessEntity()
                        .block(Duration.ofSeconds(10));
                nr.setStatus("success");
                log.info("[Notify] webhook 发送成功 url={}, alertId={}", url, record.getId());
            } catch (Exception e) {
                nr.setStatus("fail");
                nr.setErrorMsg(e.getMessage());
                log.error("[Notify] webhook 发送失败 url={}, err={}", url, e.getMessage());
            }
            notifyRecordMapper.insert(nr);
        }
    }

    private String extractUrl(String configJson) {
        if (configJson == null || configJson.isBlank()) return null;
        try {
            JsonNode node = objectMapper.readTree(configJson);
            return node.path("url").asText(null);
        } catch (Exception e) {
            return null;
        }
    }

    private String buildWebhookBody(AlertRule rule, AlertRecord record) throws Exception {
        var map = new java.util.HashMap<String, Object>();
        map.put("alertId", record.getId());
        map.put("ruleId", rule.getId());
        map.put("ruleName", rule.getName());
        map.put("title", record.getTitle());
        map.put("level", record.getLevel());
        map.put("metricKey", record.getMetricKey());
        map.put("triggerValue", record.getTriggerValue());
        map.put("threshold", record.getThresholdValue());
        map.put("targetId", record.getTargetId());
        map.put("firstTriggerTime", record.getFirstTriggerTime());
        map.put("content", record.getContent());
        return objectMapper.writeValueAsString(map);
    }

    private List<String> parseChannels(String json) {
        List<String> out = new ArrayList<>();
        if (json == null || json.isBlank()) return out;
        try {
            JsonNode arr = objectMapper.readTree(json);
            if (arr.isArray()) {
                arr.forEach(n -> out.add(n.asText()));
            }
        } catch (Exception ignore) { /* ignore */ }
        return out;
    }
}
