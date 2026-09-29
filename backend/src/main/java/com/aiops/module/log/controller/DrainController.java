package com.aiops.module.log.controller;

import com.aiops.common.BizException;
import com.aiops.common.Result;
import com.aiops.module.log.drain.DrainConfigPatch;
import com.aiops.module.log.drain.DrainParseResult;
import com.aiops.module.log.drain.DrainService;
import com.aiops.security.RequirePerm;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Drain 日志模板提取演示 / 在线参数调优接口。
 * 所有接口不落库、不读 ES，仅调用内存中的 DrainParser。
 */
@Tag(name = "Drain 日志模板")
@RestController
@RequestMapping("/api/log/drain")
@RequiredArgsConstructor
public class DrainController {

    private final DrainService drainService;

    /* ================= parse ================= */

    @Operation(summary = "解析一条日志（演示用）")
    @RequirePerm("log:drain:edit")
    @PostMapping("/parse")
    public Result<DrainParseResult> parse(@RequestBody ParseRequest req) {
        if (req == null || req.getDatasourceId() == null || req.getIndexConfigId() == null) {
            throw new BizException("datasourceId / indexConfigId 必填");
        }
        return Result.ok(drainService.parse(req.getDatasourceId(), req.getIndexConfigId(), req.getContent()));
    }

    /* ================= 参数查看 / 调优 ================= */

    @Operation(summary = "查看当前参数 + cluster 计数")
    @RequirePerm("log:drain:edit")
    @GetMapping("/params")
    public Result<Map<String, Object>> getParams(@RequestParam long datasourceId,
                                                 @RequestParam long indexConfigId) {
        return Result.ok(drainService.snapshotParams(datasourceId, indexConfigId));
    }

    @Operation(summary = "在线调整参数并触发重训练")
    @RequirePerm("log:drain:params")
    @PostMapping("/params")
    public Result<Map<String, Object>> updateParams(@RequestBody ParamsRequest req) {
        if (req == null || req.getDatasourceId() == null || req.getIndexConfigId() == null) {
            throw new BizException("datasourceId / indexConfigId 必填");
        }
        DrainConfigPatch patch = new DrainConfigPatch();
        patch.setDepth(req.getDepth());
        patch.setSimTh(req.getSimTh());
        patch.setMaxChildren(req.getMaxChildren());
        patch.setMaxCluster(req.getMaxCluster());
        if (req.getExtraDelimiters() != null) {
            patch.setExtraDelimiters(req.getExtraDelimiters().toCharArray());
        }
        return Result.ok(drainService.updateParams(req.getDatasourceId(), req.getIndexConfigId(), patch));
    }

    /* ================= DTO ================= */

    @Data
    public static class ParseRequest {
        private Long datasourceId;
        private Long indexConfigId;
        private String content;
    }

    @Data
    public static class ParamsRequest {
        private Long datasourceId;
        private Long indexConfigId;
        private Integer depth;
        private Double simTh;
        private Integer maxChildren;
        private Integer maxCluster;
        /** 字符串形式，例如 ",=|:()[]{}\"'" */
        private String extraDelimiters;
    }
}
