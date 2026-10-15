package com.aiops.module.monitor.dto;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

import java.util.List;

/**
 * 新建 / 保存大盘模板。各字段为 null 表示"不修改"：
 * 只传 name 即改名；widgets 给出时为完整列表（按 id 匹配，库里有而列表里没有的会被删除）。
 */
@Data
public class DashboardTemplateRequest {

    private String name;
    private List<Widget> widgets;
    /** [{ widgetId, x, y, w, h }]，原样存为 JSON */
    private JsonNode layoutConfig;

    @Data
    public static class Widget {
        /** 为空表示新增 */
        private Long id;
        private String widgetType;
        private String title;
        /** 前端可能传 JSON 字符串或对象，两者都接受 */
        private JsonNode config;
        private Integer sort;
    }
}
