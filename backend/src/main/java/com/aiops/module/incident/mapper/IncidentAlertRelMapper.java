package com.aiops.module.incident.mapper;

import com.aiops.module.incident.entity.IncidentAlertRel;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 事件-告警关联 Mapper
 */
@Mapper
public interface IncidentAlertRelMapper extends BaseMapper<IncidentAlertRel> {
}
