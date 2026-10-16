package com.aiops.common.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import lombok.Data;

/**
 * 处理类操作（认领 / 解决 / 关闭 / 标记误报）的可选备注。
 * 前端以 note 字段提交，后端此前只读 remark，备注一直没有存下来；两者都接受。
 */
@Data
public class HandleRemarkRequest {
    @JsonAlias("note")
    private String remark;

    public static String of(HandleRemarkRequest body) {
        return body == null ? null : body.getRemark();
    }
}
