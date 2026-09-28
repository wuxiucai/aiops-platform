package com.aiops.module.notify.mapper;

import com.aiops.module.notify.entity.NotifyChannel;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 通知渠道 Mapper
 */
@Mapper
public interface NotifyChannelMapper extends BaseMapper<NotifyChannel> {
}
