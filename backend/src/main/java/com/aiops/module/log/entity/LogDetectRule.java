package com.aiops.module.log.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 日志异常检测规则
 */
@Data
@TableName("log_detect_rule")
public class LogDetectRule {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("datasource_id")
    private Long datasourceId;

    @TableField("index_config_id")
    private Long indexConfigId;

    @TableField("name")
    private String name;

    /** new_template|rare_template|spike|error_rate */
    @TableField("rule_type")
    private String ruleType;

    @TableField("params")
    private String params;

    @TableField("level")
    private String level;

    @TableField("enabled")
    private Integer enabled;

    @TableField("create_time")
    private LocalDateTime createTime;

    @TableField("update_time")
    private LocalDateTime updateTime;

    @TableLogic
    @TableField("deleted")
    private Integer deleted;
}
