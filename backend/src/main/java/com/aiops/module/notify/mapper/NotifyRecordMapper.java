package com.aiops.module.notify.mapper;

import com.aiops.module.notify.entity.NotifyRecord;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 通知发送记录 Mapper
 */
@Mapper
public interface NotifyRecordMapper extends BaseMapper<NotifyRecord> {
}
