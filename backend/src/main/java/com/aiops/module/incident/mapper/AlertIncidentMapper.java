package com.aiops.module.incident.mapper;

import com.aiops.module.incident.entity.AlertIncident;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 故障事件 Mapper
 */
@Mapper
public interface AlertIncidentMapper extends BaseMapper<AlertIncident> {
}
