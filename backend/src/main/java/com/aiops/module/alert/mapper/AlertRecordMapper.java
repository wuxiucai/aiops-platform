package com.aiops.module.alert.mapper;

import com.aiops.module.alert.entity.AlertRecord;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 告警记录 Mapper
 */
@Mapper
public interface AlertRecordMapper extends BaseMapper<AlertRecord> {

    /** 分页查询（关联规则名/目标名） */
    Page<AlertRecord> pageWithNames(Page<AlertRecord> page,
                                    @Param("status") String status,
                                    @Param("ruleId") Long ruleId,
                                    @Param("targetId") Long targetId,
                                    @Param("startTime") LocalDateTime startTime,
                                    @Param("endTime") LocalDateTime endTime);

    /** 最近 N 小时内告警数（大盘用） */
    Long countSince(@Param("since") LocalDateTime since);

    /** 最近 top N 告警（大盘用） */
    List<AlertRecord> topRecent(@Param("limit") int limit);
}
