package com.aiops.module.monitor.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 模板下的单个组件。 */
@Data
@TableName("dashboard_widget")
public class DashboardWidget {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("template_id")
    private Long templateId;

    /** stat_card | line_chart | top_alert | top_template | incident_list | metric_compare */
    @TableField("widget_type")
    private String widgetType;

    @TableField("title")
    private String title;

    /** 按 widget_type 定义的 config JSON */
    @TableField("config")
    private String config;

    @TableField("sort")
    private Integer sort;

    @TableField("create_time")
    private LocalDateTime createTime;

    @TableField("deleted")
    private Integer deleted;
}
