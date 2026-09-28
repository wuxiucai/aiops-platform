package com.aiops.module.notify.mapper;

import com.aiops.module.notify.entity.SysMessage;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 站内消息 Mapper
 */
@Mapper
public interface SysMessageMapper extends BaseMapper<SysMessage> {
}
