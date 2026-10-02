package com.aiops.module.monitor.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 自定义大盘模板。is_default 同 user 全表只有一个 1。 */
@Data
@TableName("dashboard_template")
public class DashboardTemplate {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("user_id")
    private Long userId;

    @TableField("name")
    private String name;

    @TableField("is_default")
    private Integer isDefault;

    /** 网格布局 [{widgetId,x,y,w,h}] JSON */
    @TableField("layout_config")
    private String layoutConfig;

    @TableField("create_time")
    private LocalDateTime createTime;

    @TableField("update_time")
    private LocalDateTime updateTime;

    @TableField("deleted")
    private Integer deleted;
}
