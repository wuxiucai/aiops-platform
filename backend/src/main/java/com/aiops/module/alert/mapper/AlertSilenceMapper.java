package com.aiops.module.alert.mapper;

import com.aiops.module.alert.entity.AlertSilence;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 告警静默 Mapper
 */
@Mapper
public interface AlertSilenceMapper extends BaseMapper<AlertSilence> {
}
