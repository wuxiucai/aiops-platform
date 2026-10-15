package com.aiops.module.monitor.service;

import com.aiops.common.BizException;
import com.aiops.module.monitor.entity.DashboardGroup;
import com.aiops.module.monitor.entity.DashboardTemplate;
import com.aiops.module.monitor.mapper.DashboardGroupMapper;
import com.aiops.module.monitor.mapper.DashboardTemplateMapper;
import com.aiops.security.UserContext;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

/**
 * 大盘分组（按用户隔离的树）。
 * 删除分组涉及三张表的改动（模板移入未归类、子分组上挂、删分组本身），放在同一事务。
 */
@Service
@RequiredArgsConstructor
public class DashboardGroupService {

    private final DashboardGroupMapper dashboardGroupMapper;
    private final DashboardTemplateMapper dashboardTemplateMapper;

    private Long currentUserId() {
        if (UserContext.get() != null && UserContext.get().getUserId() != null) {
            return UserContext.get().getUserId();
        }
        return 1L;
    }

    /** 平铺分组节点（含各分组模板数）+ 未归类模板数 */
    public Map<String, Object> tree() {
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
        return r;
    }

    public DashboardGroup add(DashboardGroup body) {
        if (body.getName() == null || body.getName().isBlank()) throw new BizException("name 必填");
        body.setId(null);
        body.setUserId(currentUserId());
        if (body.getParentId() == null) body.setParentId(0L);
        if (body.getSort() == null) body.setSort(0);
        body.setDeleted(0);
        body.setCreateTime(LocalDateTime.now());
        body.setUpdateTime(LocalDateTime.now());
        dashboardGroupMapper.insert(body);
        return body;
    }

    /** 重命名 / 移动；不允许改归属 */
    public void update(DashboardGroup body) {
        if (body.getId() == null) throw new BizException("id 必填");
        requireOwned(body.getId(), "无权修改他人分组");
        body.setUserId(null);
        body.setUpdateTime(LocalDateTime.now());
        dashboardGroupMapper.updateById(body);
    }

    /** 删除分组：其下模板移入未归类，子分组挂到被删分组的父节点 */
    @Transactional
    public void delete(Long id) {
        DashboardGroup exist = requireOwned(id, "无权删除他人分组");
        // MyBatis-Plus 默认 NOT_NULL 策略会在 updateById 中省略 null 字段，置空必须显式 set
        dashboardTemplateMapper.update(null, new LambdaUpdateWrapper<DashboardTemplate>()
                .eq(DashboardTemplate::getGroupId, id)
                .set(DashboardTemplate::getGroupId, null));
        dashboardGroupMapper.update(null, new LambdaUpdateWrapper<DashboardGroup>()
                .eq(DashboardGroup::getParentId, id)
                .set(DashboardGroup::getParentId, exist.getParentId()));
        dashboardGroupMapper.deleteById(id);
    }

    private DashboardGroup requireOwned(Long id, String denyMsg) {
        DashboardGroup g = dashboardGroupMapper.selectById(id);
        if (g == null) throw new BizException("分组不存在");
        if (!Objects.equals(g.getUserId(), currentUserId())) throw new BizException(denyMsg);
        return g;
    }
}
