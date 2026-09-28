package com.aiops.module.incident.mapper;

import com.aiops.module.incident.entity.IncidentTimeline;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 事件时间线 Mapper
 */
@Mapper
public interface IncidentTimelineMapper extends BaseMapper<IncidentTimeline> {
}
