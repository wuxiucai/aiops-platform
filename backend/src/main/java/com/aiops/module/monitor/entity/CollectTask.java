package com.aiops.module.monitor.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 采集任务
 */
@Data
@TableName("collect_task")
public class CollectTask {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("target_id")
    private Long targetId;

    /** JSON 数组，如 ["cpu.usage","mem.usage"] */
    @TableField("metric_keys")
    private String metricKeys;

    @TableField("interval_sec")
    private Integer intervalSec;

    @TableField("status")
    private Integer status;

    @TableField("last_run_time")
    private LocalDateTime lastRunTime;

    @TableField("last_cost_ms")
    private Long lastCostMs;

    @TableField("fail_count")
    private Integer failCount;

    @TableField("create_time")
    private LocalDateTime createTime;

    @TableField("update_time")
    private LocalDateTime updateTime;

    @TableLogic
    @TableField("deleted")
    private Integer deleted;
}
