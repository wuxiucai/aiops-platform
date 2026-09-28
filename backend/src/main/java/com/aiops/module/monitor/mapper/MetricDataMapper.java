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

    /** 时序查询：按 step 分桶聚合（DATE_FORMAT + AVG/MAX/MIN），时间升序 */
    List<Map<String, Object>> querySeries(@Param("targetIds") List<Long> targetIds,
                                          @Param("metricKeys") List<String> metricKeys,
                                          @Param("startTime") LocalDateTime startTime,
                                          @Param("endTime") LocalDateTime endTime,
                                          @Param("format") String format,
                                          @Param("aggregation") String aggregation);
}
