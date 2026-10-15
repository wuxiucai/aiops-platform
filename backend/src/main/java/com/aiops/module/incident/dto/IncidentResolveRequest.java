package com.aiops.module.incident.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import lombok.Data;

/** 标记事件已解决。备注写入时间线；前端 Incident.vue 以 note 字段提交 */
@Data
public class IncidentResolveRequest {
    @JsonAlias("note")
    private String remark;
}
