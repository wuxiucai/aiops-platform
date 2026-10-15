package com.aiops.module.monitor.dto;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * agent 上报时间戳：只认字符串（ISO 或 "yyyy-MM-dd HH:mm:ss"），其余一律返回 null（由服务端用接收时间）。
 * <p>
 * 现有 agent 用 Jackson 默认格式发出数组 [y,M,d,H,m,s,nanos]，这里有意不解析：
 * 那是 agent 主机的本地时间，各主机的时钟与时区不受平台校准，统一用接收时间更可靠。
 * 与此前 AgentController.parseTime 的实际效果一致。
 */
public class AgentTimestampDeserializer extends JsonDeserializer<LocalDateTime> {

    private static final DateTimeFormatter DF = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Override
    public LocalDateTime deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        if (p.currentToken() != JsonToken.VALUE_STRING) {
            p.skipChildren();
            return null;
        }
        String s = p.getText().trim();
        try { return LocalDateTime.parse(s, DateTimeFormatter.ISO_LOCAL_DATE_TIME); } catch (Exception ignore) { }
        try { return LocalDateTime.parse(s, DF); } catch (Exception ignore) { }
        return null;
    }
}
