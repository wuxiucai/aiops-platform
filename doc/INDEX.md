# 执行 AI 全开任务包（S1 → S2 → S3 → S4）

**你的工作模式（重申）**:
1. 按 stage 顺序逐项完成 — 一个 stage 完成立即 push 一次到 main
2. 不写 .md 文档 — 只 ≤30 行简报（path changed / commit hash / build status）
3. 不动既有代码架构 — 只按下面契约实施
4. 完成一条不返工 — 我们说"完成"才进下一 stage

═══════════════════════════════════════════════════

## S1 — Agent.vue 下拉空 bug（P0，~30 分钟）

**BUG**: 创建 agent 弹窗 target 下拉为空，但 `GET /api/monitor/target/page?current=1&size=100` 实测返回 3 条记录。

**debug 自查步骤**：
1. 打开 `frontend/src/views/monitor/Agent.vue`
2. 找 `openCreateDialog` 函数 — 检查是否在该函数里调用 `listTargets()` 并赋值 `targets.value`
3. 检查 `el-select` 的 `v-model` 是否绑定到 `createForm.targetId`（可能写成了别的字段）
4. 检查 `<el-option>` 的 `:value` 与 `:label` 字段是否正确（label 不能为 undefined）
5. 看 Network 面板 `/api/monitor/target/page` 是否真返回了记录（response 内容是 `data.records`）
6. 修复后实测：新建 dialog 弹起 → 下拉显示 3 项

**验收**：弹窗下拉非空，能选 target=2（order-service）创建 agent 成功。

═══════════════════════════════════════════════════

## S2 — 6 个空页填实（P0，~2 天，**每页单独 push 一次**）

> 6 个页面后端接口都是 200 OK，仅前端是空 EmptyPlaceholder。**做完一页 push 一次，不要 bundle**。

**全局规范**（每页都遵守）：
- 标题区（h2 + 面包屑）
- 顶部工具条（搜索 + 新建按钮，若有）
- el-table 分页，10/页
- 列宽自适应，`empty-text="暂无数据"`
- 删除/停用带 `ElMessageBox.confirm`
- 异常时 `ElMessage.error(res.msg)`

### 2.1 `views/kb/Document.vue` 知识库-文档
- 列表列：title / doc_type / chunk_count / embedding_status / create_time / 操作
- 操作：新建录入（dialog 表单：title + doc_type select + content textarea）/ 上传文件（el-upload，POST /api/kb/document/upload）/ 触发 embedding（POST /api/kb/document/{id}/embedding）/ 删除
- embedding_status 显示徽标（pending=灰，done=绿，failed=红）

### 2.2 `views/kb/Case.vue` 知识库-案例
- 列表列：title / occurred_time / tags / related_incident_id / 操作
- 操作：详情弹窗（症状/根因/方案 markdown 渲染）/ 手工新增 / 从 incident 一键沉淀（POST /api/kb/case/from-incident/{id}）
- 默认显示种子 3 案例

### 2.3 `views/monitor/Collect.vue` 监控-采集任务
- 列表列：target_name / metric_keys（JSON 展开徽标）/ interval_sec / last_run_time / fail_count / status / 操作
- 操作：新建（选 target + 选 metric_keys 多选 + interval）/ 启停 / 手动触发一次（POST /api/monitor/collect/task/{id}/run） / 删除

### 2.4 `views/monitor/Datasource.vue` 监控-数据源
- 已经在 III 阶段做过，这里是**补丁**——如果实际页面是空，移到完整版
- 列表列：name / es_scheme+host+port / cluster_name / es_version / status / is_default / last_test_time / 操作
- 操作：新建 / 编辑 / 测试连接 / 设为默认 / 删除
- 注：如果该页面已存在，跳过本页

### 2.5 `views/stat/Effect.vue` 统计-效果评估 ★ 论文核心
- 顶部时间筛选（默认最近 7 天）
- 5 个统计卡片（el-card）：误报率 / 压缩率 / MTTA / MTTR / 模板压缩率
- 底部 2 个图表（el-chart 折线）：alert 数量趋势 / incident 创建趋势
- 调 `/api/stat/effect-metrics` + `/api/stat/alert-trend`

### 2.6 `views/stat/LlmCost.vue` 统计-LLM 成本
- 卡片：总成本（¥）/ 总 tokens / 平均延迟 / 成功率
- 折线图：按日的 tokens 消耗
- 表格：按 scene_code 分组的（scene / 调用数 / 成功率 / 平均延迟 / 总 tokens）
- 调 `/api/stat/llm-cost`

═══════════════════════════════════════════════════

## S3 — dashboard 分组管理（P1，~1 天）

### 3.1 SQL（直接执行）

```sql
CREATE TABLE dashboard_group (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  name VARCHAR(64) NOT NULL,
  description VARCHAR(255),
  sort INT DEFAULT 0,
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted TINYINT NOT NULL DEFAULT 0
);

ALTER TABLE dashboard_template ADD COLUMN group_id BIGINT NULL COMMENT '所属分组';

ALTER TABLE dashboard_group ADD UNIQUE KEY uk_user_name (user_id, name);
```

### 3.2 后端

新建 `DashboardGroupController` + `DashboardGroupService` + mapper / entity。
两个 endpoint：
- `GET/POST/PUT/DELETE /api/dashboard/group` - CRUD
- `PUT /api/dashboard/template/{id}/move` body: `{"groupId": N | null}` - 把模板移到分组 / 移出（null 表示不分组）

`GET /api/dashboard/templates` 返回时新增 `groupId` 字段。

### 3.3 前端 `DashboardCustom.vue`

- 左侧 GroupTree（el-tree）：分组节点 + 模板节点
- 操作：右键分组 → 新增/重命名/删除；拖拽模板到分组 → 调 move 接口
- 顶部"未分组"显示 group_id IS NULL 的模板
- URL 参数 `?group=<id>`，刷新时根据当前选中分组过滤显示

═══════════════════════════════════════════════════

## S4 — Prometheus metrics 客户端接入（P2，~3 天）

> 这是给"类似 Prometheus"的最大加分项——但**这次不是部署 K8s，是写 HTTP 拉 Prometheus text-format metrics 的客户端**。

### 4.1 SQL

```sql
ALTER TABLE monitor_target 
  ADD COLUMN target_url VARCHAR(255) NULL COMMENT 'Prometheus metrics URL（target_type=prometheus 时必填）',
  MODIFY COLUMN target_type VARCHAR(16) NOT NULL COMMENT 'host|service|prometheus';
```

### 4.2 后端

1. **PrometheusCollector**（datasource/metric/PrometheusCollector.java）
   - 用 WebClient GET `<target_url>`
   - 解析 Prometheus text exposition format（每行 `metric_name{label="v"} value timestamp`, 跳过 `#` 注释行）
   - 至少支持 3 个核心指标映射：`node_cpu_seconds_total → cpu.usage`, `node_memory_MemAvailable_bytes / node_memory_MemTotal_bytes → mem.usage`, `node_filesystem_avail_bytes → disk.usage`
   - 写出 MetricPoint 列表

2. **MonitorTargetService 改造**
   - target_type=prometheus 时强制 `target_url` 非空
   - `agent_status='healthy'` 标记改由"近 60s 是否成功拉到 metrics"决定

3. **MetricCollectJob 改造**
   - 按 target_type 分发：host → OshiCollector；service → ActuatorCollector；prometheus → PrometheusCollector

### 4.3 前端

`views/monitor/Target.vue` 新建/编辑表单：
- target_type 新增选项 `prometheus`
- 选 prometheus 时显示 `target_url` 输入框（required）
- 列表显示 type= prometheus 的目标
- 详情页正常下钻

### 4.4 验收

- demo-service 自己的 `/actuator/prometheus` 端点能拉（`http://localhost:8082/actuator/prometheus`）
- 建一个 target `name=prometheus-demo, target_type=prometheus, target_url=http://localhost:8082/actuator/prometheus`
- 跑一次采集，metric_data 出 cpu.usage / mem.usage 至少 2 个 metric 数据
- collect_task.last_cost_ms 显示 ms 数

═══════════════════════════════════════════════════

## 简报规范（每 stage 完成后）

```
S<N> 完成。commit=<hash>; 文件改=[path1, path2]; build 状态=<ok/fail>; 数据库改动=<sql 行数>; push=ok
```

## 边界（红线）

- 不动 sys_permission 现有权限分发（除非你加新菜单需要插入权限种子）
- 不动 backend/alert, backend/incident, backend/log 既有逻辑
- 不改 es_query / drain / agent / mail 的实现（这些是已交付模块）
- 不写 .md 文档
- 完成后立即 push
- 任何卡点 ≤3 行（**不要一大段乱码**）

═══════════════════════════════════════════════════
**开始**: S1。
