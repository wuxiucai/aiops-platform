package com.aiops.module.monitor.service;

import com.aiops.common.BizException;
import com.aiops.module.monitor.entity.DashboardTemplate;
import com.aiops.module.monitor.entity.DashboardWidget;
import com.aiops.module.monitor.mapper.DashboardTemplateMapper;
import com.aiops.module.monitor.mapper.DashboardWidgetMapper;
import com.aiops.security.UserContext;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
    public DashboardTemplate create(Map<String, Object> body) {
        DashboardTemplate t = new DashboardTemplate();
        t.setUserId(currentUserId());
        t.setName((String) body.getOrDefault("name", "未命名大盘"));
        t.setIsDefault(0);
        t.setLayoutConfig("[]");
        t.setCreateTime(java.time.LocalDateTime.now());
        t.setUpdateTime(java.time.LocalDateTime.now());
        templateMapper.insert(t);
        return t;
    }

    /** 保存：name + widgets（增删改） + layout_config. */
    @Transactional
    public DashboardTemplate save(Long id, Map<String, Object> body) {
        DashboardTemplate t = templateMapper.selectById(id);
        if (t == null) throw new BizException("模板不存在");
        if (body.get("name") != null) t.setName((String) body.get("name"));

        // widgets：前端将整个 widgets 数组发过来（含 id/topK/config/sort) — 以 id 匹配
        Object widgetsRaw = body.get("widgets");
        if (widgetsRaw instanceof List<?> list) {
            List<Long> keepIds = new ArrayList<>();
            for (Object o : list) {
                if (!(o instanceof Map)) continue;
                @SuppressWarnings("unchecked")
                Map<String, Object> w = (Map<String, Object>) o;
                Long wid = w.get("id") == null ? null : ((Number) w.get("id")).longValue();
                String type = (String) w.get("widgetType");
                String title = (String) w.get("title");
                String config = w.get("config") instanceof String s ? s : writeJson(w.get("config"));
                Integer sort = w.get("sort") instanceof Number n ? n.intValue() : 0;
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
        if (body.get("layoutConfig") != null) {
            t.setLayoutConfig(writeJson(body.get("layoutConfig")));
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
        Long uid = target.getUserId();
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
        templateMapper.deleteById(id);
        widgetMapper.delete(new LambdaQueryWrapper<DashboardWidget>()
                .eq(DashboardWidget::getTemplateId, id));
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
