package com.aiops.module.llm.mapper;

import com.aiops.module.llm.entity.LlmPromptTemplate;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface LlmPromptTemplateMapper extends BaseMapper<LlmPromptTemplate> {
}
