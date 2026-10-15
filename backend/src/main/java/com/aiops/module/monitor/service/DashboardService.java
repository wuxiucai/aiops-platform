package com.aiops.module.monitor.service;

import com.aiops.common.BizException;
import com.aiops.module.monitor.dto.DashboardTemplateRequest;
import com.aiops.module.monitor.entity.DashboardTemplate;
import com.aiops.module.monitor.entity.DashboardWidget;
import com.aiops.module.monitor.mapper.DashboardTemplateMapper;
import com.aiops.module.monitor.mapper.DashboardWidgetMapper;
import com.aiops.security.UserContext;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 自定义大盘模板服务（CRUD + is_default 互斥）.
 */
@Service
@RequiredArgsConstructor
public class DashboardService {

    private final DashboardTemplateMapper templateMapper;
    private final DashboardWidgetMapper widgetMapper;
    private final com.aiops.module.monitor.mapper.DashboardGroupMapper dashboardGroupMapper;
    private final ObjectMapper om = new ObjectMapper();

    /** 列出当前用户全部模板（含 widgets）。 */
    public List<Map<String, Object>> listAll() {
        Long uid = currentUserId();
        List<DashboardTemplate> ts = templateMapper.selectList(
                new LambdaQueryWrapper<DashboardTemplate>()
                        .eq(DashboardTemplate::getUserId, uid)
                        .orderByDesc(DashboardTemplate::getIsDefault)
                        .orderByDesc(DashboardTemplate::getId));
        List<Map<String, Object>> out = new ArrayList<>();
        for (DashboardTemplate t : ts) {
            out.add(toTemplateOut(t));
        }
        return out;
    }

    /** 单个详情。 */
    public Map<String, Object> detail(Long id) {
        DashboardTemplate t = templateMapper.selectById(id);
        if (t == null) throw new BizException("模板不存在");
        return toTemplateOut(t);
    }

    /** 新建。 */
    @Transactional
    public DashboardTemplate create(DashboardTemplateRequest body) {
        DashboardTemplate t = new DashboardTemplate();
        t.setUserId(currentUserId());
        t.setName(body.getName() == null ? "未命名大盘" : body.getName());
        t.setIsDefault(0);
        t.setLayoutConfig("[]");
        t.setCreateTime(java.time.LocalDateTime.now());
        t.setUpdateTime(java.time.LocalDateTime.now());
        templateMapper.insert(t);
        return t;
    }

    /** 保存：name + widgets（增删改） + layout_config. */
    @Transactional
    public DashboardTemplate save(Long id, DashboardTemplateRequest body) {
        DashboardTemplate t = templateMapper.selectById(id);
        if (t == null) throw new BizException("模板不存在");
        if (body.getName() != null) t.setName(body.getName());

        // widgets：前端将整个 widgets 数组发过来（含 id/config/sort) — 以 id 匹配
        if (body.getWidgets() != null) {
            List<Long> keepIds = new ArrayList<>();
            for (DashboardTemplateRequest.Widget w : body.getWidgets()) {
                if (w == null) continue;
                Long wid = w.getId();
                String type = w.getWidgetType();
                String title = w.getTitle();
                // 新增时前端传 JSON 字符串，回存时传解析后的对象：字符串原样存，对象序列化
                String config = w.getConfig() != null && w.getConfig().isTextual()
                        ? w.getConfig().asText() : writeJson(w.getConfig());
                Integer sort = w.getSort() == null ? 0 : w.getSort();
                DashboardWidget up = null;
                if (wid != null) {
                    up = widgetMapper.selectById(wid);
                }
                if (up == null) {
                    up = new DashboardWidget();
                    up.setTemplateId(id);
                    up.setCreateTime(java.time.LocalDateTime.now());
                }
                up.setWidgetType(type);
                up.setTitle(title);
                up.setConfig(config);
                up.setSort(sort);
                widgetMapper.insertOrUpdate(up);
                keepIds.add(up.getId());
            }
            // 删除不在 keepIds 里的
            widgetMapper.delete(new LambdaQueryWrapper<DashboardWidget>()
                    .eq(DashboardWidget::getTemplateId, id)
                    .notIn(DashboardWidget::getId, keepIds.isEmpty() ? List.of(-1L) : keepIds));
        }

        // layout_config
        if (body.getLayoutConfig() != null && !body.getLayoutConfig().isNull()) {
            t.setLayoutConfig(writeJson(body.getLayoutConfig()));
        }
        t.setUpdateTime(java.time.LocalDateTime.now());
        templateMapper.updateById(t);
        return t;
    }

    /** 设为默认（互斥，全表只允许一个 is_default=1 for this user）。 */
    @Transactional
    public void setDefault(Long id) {
        DashboardTemplate target = templateMapper.selectById(id);
        if (target == null) throw new BizException("模板不存在");
        // 属主校验：只能修改自己创建的 template
        Long uid = currentUserId();
        if (!java.util.Objects.equals(target.getUserId(), uid)) {
            throw new BizException("无权修改他人模板");
        }
        List<DashboardTemplate> all = templateMapper.selectList(
                new LambdaQueryWrapper<DashboardTemplate>().eq(DashboardTemplate::getUserId, uid));
        for (DashboardTemplate t : all) {
            if (t.getIsDefault() != null && t.getIsDefault() == 1) {
                t.setIsDefault(0);
                templateMapper.updateById(t);
            }
        }
        target.setIsDefault(1);
        templateMapper.updateById(target);
    }

    /** 删除模板及其 widgets. */
    @Transactional
    public void delete(Long id) {
        DashboardTemplate target = templateMapper.selectById(id);
        if (target == null) throw new BizException("模板不存在");
        // 属主校验：只能删除自己创建的 template
        Long uid = currentUserId();
        if (!java.util.Objects.equals(target.getUserId(), uid)) {
            throw new BizException("无权删除他人模板");
        }
        templateMapper.deleteById(id);
        widgetMapper.delete(new LambdaQueryWrapper<DashboardWidget>()
                .eq(DashboardWidget::getTemplateId, id));
    }

    /** S3： 把模板移入某分组；groupId null = 移出到未归类 */
    @Transactional
    public void move(Long templateId, Long groupId) {
        Long uid = currentUserId();
        DashboardTemplate t = templateMapper.selectById(templateId);
        if (t == null) throw new com.aiops.common.BizException("模板不存在");
        if (!java.util.Objects.equals(t.getUserId(), uid)) {
            throw new com.aiops.common.BizException("无权修改他人模板");
        }
        if (groupId != null) {
            com.aiops.module.monitor.entity.DashboardGroup g = dashboardGroupMapper.selectById(groupId);
            if (g == null || !java.util.Objects.equals(g.getUserId(), uid)) {
                throw new com.aiops.common.BizException("目标分组不存在");
            }
        }
        // 修复 #11：updateById 的默认 NOT_NULL 策略会跳过 null 字段，
        // SET group_id=NULL 必须用 LambdaUpdateChainWrapper.set() 显式生成
        com.baomidou.mybatisplus.extension.conditions.update.LambdaUpdateChainWrapper<
                com.aiops.module.monitor.entity.DashboardTemplate> w =
                new com.baomidou.mybatisplus.extension.conditions.update.LambdaUpdateChainWrapper<>(templateMapper)
                        .eq(com.aiops.module.monitor.entity.DashboardTemplate::getId, templateId)
                        .set(com.aiops.module.monitor.entity.DashboardTemplate::getGroupId, groupId)
                        .set(com.aiops.module.monitor.entity.DashboardTemplate::getUpdateTime,
                                java.time.LocalDateTime.now());
        w.update();
    }

    /* ================== 内部 ================== */

    private Long currentUserId() {
        if (UserContext.get() != null && UserContext.get().getUserId() != null) {
            return UserContext.get().getUserId();
        }
        return 1L;
    }

    private Map<String, Object> toTemplateOut(DashboardTemplate t) {
        Map<String, Object> m = new java.util.HashMap<>();
        m.put("id", t.getId());
        m.put("userId", t.getUserId());
        m.put("name", t.getName());
        m.put("isDefault", t.getIsDefault());
        m.put("groupId", t.getGroupId());
        m.put("layoutConfig", parseJson(t.getLayoutConfig()));
        m.put("createTime", t.getCreateTime());
        m.put("updateTime", t.getUpdateTime());
        List<DashboardWidget> ws = widgetMapper.selectList(
                new LambdaQueryWrapper<DashboardWidget>()
                        .eq(DashboardWidget::getTemplateId, t.getId())
                        .orderByAsc(DashboardWidget::getSort));
        List<Map<String, Object>> wOut = new ArrayList<>();
        for (DashboardWidget w : ws) {
            Map<String, Object> wm = new java.util.HashMap<>();
            wm.put("id", w.getId());
            wm.put("widgetType", w.getWidgetType());
            wm.put("title", w.getTitle());
            wm.put("config", parseJson(w.getConfig()));
            wm.put("sort", w.getSort());
            wOut.add(wm);
        }
        m.put("widgets", wOut);
        return m;
    }

    private Object parseJson(String s) {
        if (s == null || s.isBlank()) return null;
        try { return om.readTree(s); } catch (Exception e) { return s; }
    }

    private String writeJson(Object o) {
        try { return om.writeValueAsString(o); } catch (Exception e) { return "{}"; }
    }
}
