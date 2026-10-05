package com.aiops.module.monitor.controller;

import com.aiops.common.BizException;
import com.aiops.common.Result;
import com.aiops.module.monitor.entity.DashboardGroup;
import com.aiops.module.monitor.entity.DashboardTemplate;
import com.aiops.module.monitor.mapper.DashboardGroupMapper;
import com.aiops.module.monitor.mapper.DashboardTemplateMapper;
import com.aiops.security.RequirePerm;
import com.aiops.security.UserContext;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.*;

/**
 * S3 仪表盘分组管理（user-scoped 树）
 */
@Tag(name = "仪表盘分组")
@RestController
@RequestMapping("/api/dashboard/group")
@RequiredArgsConstructor
public class DashboardGroupController {

    private final DashboardGroupMapper dashboardGroupMapper;
    private final DashboardTemplateMapper dashboardTemplateMapper;

    private Long currentUserId() {
        if (UserContext.get() != null && UserContext.get().getUserId() != null) {
            return UserContext.get().getUserId();
        }
        return 1L;
    }

    @Operation(summary = "树形分组（含未归类模板计数）")
    @RequirePerm("monitor:dashboard:list")
    @GetMapping("/tree")
    public Result<Map<String, Object>> tree() {
        Long uid = currentUserId();
        List<DashboardGroup> groups = dashboardGroupMapper.selectList(
                new LambdaQueryWrapper<DashboardGroup>()
                        .eq(DashboardGroup::getUserId, uid)
                        .orderByAsc(DashboardGroup::getSort)
                        .orderByAsc(DashboardGroup::getId));

        // 统计每分组下模板数
        List<Map<String, Object>> counts = dashboardTemplateMapper.selectMaps(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<DashboardTemplate>()
                        .select("group_id AS gid", "COUNT(*) AS cnt")
                        .eq("user_id", uid)
                        .eq("deleted", 0)
                        .groupBy("group_id"));
        Map<Long, Long> gidCnt = new HashMap<>();
        long ungrouped = 0;
        for (Map<String, Object> c : counts) {
            Object gid = c.get("gid");
            long n = ((Number) c.get("cnt")).longValue();
            if (gid == null) ungrouped = n;
            else gidCnt.put(((Number) gid).longValue(), n);
        }

        // 平铺 node 列表（前端 el-tree 平铺即可）
        List<Map<String, Object>> nodes = new ArrayList<>();
        for (DashboardGroup g : groups) {
            Map<String, Object> n = new HashMap<>();
            n.put("id", g.getId());
            n.put("parentId", g.getParentId());
            n.put("name", g.getName());
            n.put("sort", g.getSort());
            n.put("templateCount", gidCnt.getOrDefault(g.getId(), 0L));
            nodes.add(n);
        }

        Map<String, Object> r = new HashMap<>();
        r.put("nodes", nodes);
        r.put("ungroupedCount", ungrouped);
        return Result.ok(r);
    }

    @Operation(summary = "新建分组")
    @RequirePerm("monitor:dashboard:update")
    @PostMapping
    public Result<DashboardGroup> add(@RequestBody DashboardGroup body) {
        if (body.getName() == null || body.getName().isBlank()) throw new BizException("name 必填");
        body.setId(null);
        body.setUserId(currentUserId());
        if (body.getParentId() == null) body.setParentId(0L);
        if (body.getSort() == null) body.setSort(0);
        body.setDeleted(0);
        body.setCreateTime(LocalDateTime.now());
        body.setUpdateTime(LocalDateTime.now());
        dashboardGroupMapper.insert(body);
        return Result.ok(body);
    }

    @Operation(summary = "重命名/移动分组")
    @RequirePerm("monitor:dashboard:update")
    @PutMapping
    public Result<Void> update(@RequestBody DashboardGroup body) {
        if (body.getId() == null) throw new BizException("id 必填");
        DashboardGroup exist = dashboardGroupMapper.selectById(body.getId());
        if (exist == null) throw new BizException("分组不存在");
        if (!Objects.equals(exist.getUserId(), currentUserId())) throw new BizException("无权修改他人分组");
        body.setUserId(null); // 不改归属
        body.setUpdateTime(LocalDateTime.now());
        dashboardGroupMapper.updateById(body);
        return Result.ok();
    }

    @Operation(summary = "删除分组（亚元组对应模板 group_id 置 NULL）")
    @RequirePerm("monitor:dashboard:update")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        DashboardGroup exist = dashboardGroupMapper.selectById(id);
        if (exist == null) throw new BizException("分组不存在");
        if (!Objects.equals(exist.getUserId(), currentUserId())) throw new BizException("无权删除他人分组");
        // 模板 group_id 置 null
        List<DashboardTemplate> ts = dashboardTemplateMapper.selectList(
                new LambdaQueryWrapper<DashboardTemplate>().eq(DashboardTemplate::getGroupId, id));
        for (DashboardTemplate t : ts) {
            DashboardTemplate upd = new DashboardTemplate();
            upd.setId(t.getId());
            upd.setGroupId(null);
            dashboardTemplateMapper.updateById(upd);
        }
        // 子分组重挂到父
        List<DashboardGroup> children = dashboardGroupMapper.selectList(
                new LambdaQueryWrapper<DashboardGroup>().eq(DashboardGroup::getParentId, id));
        for (DashboardGroup c : children) {
            DashboardGroup upd = new DashboardGroup();
            upd.setId(c.getId());
            upd.setParentId(exist.getParentId());
            dashboardGroupMapper.updateById(upd);
        }
        dashboardGroupMapper.deleteById(id);
        return Result.ok();
    }

}
