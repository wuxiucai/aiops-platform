package com.aiops.module.monitor.mapper;

import com.aiops.module.monitor.entity.MetricData;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Mapper
public interface MetricDataMapper extends BaseMapper<MetricData> {

    /** 时序查询：按 step 分桶聚合。rawFormat=false 时 expression 作 DATE_FORMAT 的格式串；
     *  rawFormat=true 时 expression 作 SQL 表达式直接 SELECT（用于 CONCAT/FLOOR 复合桶）。 */
    List<Map<String, Object>> querySeries(@Param("targetIds") List<Long> targetIds,
                                          @Param("metricKeys") List<String> metricKeys,
                                          @Param("startTime") LocalDateTime startTime,
                                          @Param("endTime") LocalDateTime endTime,
                                          @Param("expression") String expression,
                                          @Param("rawFormat") boolean rawFormat,
                                          @Param("aggregation") String aggregation);
}
