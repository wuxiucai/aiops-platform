package com.aiops.module.alert.controller;

import com.aiops.common.BizException;
import com.aiops.common.Result;
import com.aiops.module.alert.entity.AlertSilence;
import com.aiops.module.alert.mapper.AlertSilenceMapper;
import com.aiops.security.RequirePerm;
import com.aiops.security.UserContext;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 告警静默管理
 */
@Slf4j
@Tag(name = "告警静默")
@RestController
@RequestMapping("/api/alert/silence")
@RequiredArgsConstructor
public class AlertSilenceController {

    private final AlertSilenceMapper alertSilenceMapper;

    @Operation(summary = "分页列表")
    @RequirePerm("alert:silence:list")
    @GetMapping("/page")
    public Result<Page<AlertSilence>> page(@RequestParam(defaultValue = "1") long current,
                                           @RequestParam(defaultValue = "10") long size,
                                           @RequestParam(required = false) String keyword) {
        return Result.ok(alertSilenceMapper.selectPage(new Page<>(current, size),
                new LambdaQueryWrapper<AlertSilence>()
                        .like(keyword != null && !keyword.isBlank(), AlertSilence::getName, keyword)
                        .orderByDesc(AlertSilence::getId)));
    }

    @Operation(summary = "新增")
    @RequirePerm("alert:silence:update")
    @PostMapping
    public Result<Void> add(@RequestBody AlertSilence silence) {
        validate(silence);
        silence.setId(null);
        if (silence.getStatus() == null) silence.setStatus(1);
        if (UserContext.get() != null) {
            silence.setCreator(UserContext.get().getUsername());
        }
        alertSilenceMapper.insert(silence);
        log.info("[AlertSilence] 新增 id={}, name={}", silence.getId(), silence.getName());
        return Result.ok();
    }

    @Operation(summary = "修改")
    @RequirePerm("alert:silence:update")
    @PutMapping
    public Result<Void> update(@RequestBody AlertSilence silence) {
        if (silence.getId() == null) {
            throw new BizException("id 缺失");
        }
        validate(silence);
        alertSilenceMapper.updateById(silence);
        return Result.ok();
    }

    @Operation(summary = "删除")
    @RequirePerm("alert:silence:update")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        alertSilenceMapper.deleteById(id);
        return Result.ok();
    }

    private void validate(AlertSilence s) {
        if (s.getName() == null || s.getName().isBlank()) {
            throw new BizException("名称必填");
        }
        if (s.getStartTime() == null || s.getEndTime() == null) {
            throw new BizException("起止时间必填");
        }
        if (s.getStartTime().isAfter(s.getEndTime())) {
            throw new BizException("start_time 不能晚于 end_time");
        }
    }
}
