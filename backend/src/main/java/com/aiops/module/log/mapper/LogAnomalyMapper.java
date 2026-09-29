package com.aiops.module.log.mapper;

import com.aiops.module.log.entity.LogAnomaly;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface LogAnomalyMapper extends BaseMapper<LogAnomaly> {
}
