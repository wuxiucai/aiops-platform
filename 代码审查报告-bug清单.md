# aiops-platform 代码审查报告

> 审查日期：2026-10-12。范围：backend（全部 Java 源码 + 配置 + mapper XML）、frontend/src（全部 Vue/JS）、agent、demo-service。
> 标注「已核实」的问题均已逐行读取源码确认；其余为审查中发现、逻辑可论证但未复跑验证的问题。

---

## 一、高危 Bug（已核实，建议优先修复）

### 1. 编辑用户/角色会把已有的角色、权限清空（前端 + 后端共同造成）
- `frontend/src/views/system/User.vue` `openDialog()`（约 128-141 行）：编辑时固定 `roleIds: []`，从不回显该用户已有角色。
- `frontend/src/views/system/Role.vue` `openDialog()`（113-127 行）：同样不回显权限，且固定 `setCheckedKeys([])` 清空勾选。
- 后端 `SysUserController.update()`（107-111 行）与 `SysRoleController.update()`（96-100 行）：只要 `roleIds/permissionIds != null` 就先删后插；前端传的是空数组（非 null），于是「删光 + 插入 0 条」。
- **触发**：管理员编辑任意用户/角色（哪怕只改昵称）→ 保存 → 该用户的角色、该角色的权限被全部清空，用户登录后菜单/权限消失。
- 严重程度：高 | 已核实。

### 2. 事件详情页 AI 诊断面板永远为空（响应双重解包）
- `frontend/src/views/alert/Incident.vue` `refreshAi()`（329-330 行）：`aiData.value = rc.data || null`。
- `utils/request.js` 拦截器（26-28 行）在 `code===200` 时已返回 `res.data` 本体；后端 `AiScenarioController.rootCause()` 返回的是 `Result.ok(Map)`，Map 里并没有名为 `data` 的字段。
- 结果 `rc.data` 恒为 undefined，根因分析/相似案例面板永远显示空。同文件 `runReport()`（344-346 行）的用法（`r?.markdown`）才是对的，两处写法自相矛盾。
- 同类问题：`frontend/src/views/ai/Prompt.vue`（139 行 `previewRow.value = r.data`、145 行 `editRow.value = r.data`）——预览/编辑弹窗疑似空白。
- 严重程度：高 | 已核实（Incident.vue 确认；Prompt.vue 需按同一逻辑确认）。

### 3. LLM 成本统计成功/失败比例恒为 0/100%（状态值写错）
- `backend/.../module/stat/controller/StatController.java` `llmCost()`（141-142 行）：SQL 统计 `status='ok'`。
- 但所有写入 `llm_call_log` 的代码写的都是 `'success'` / `'fail'`（AiScenarioService、Nl2QueryService、IncidentReportService）。
- 结果：`ok_calls` 恒为 0，`failed_calls` = 全部调用，成本页面成功率为 0%。
- 严重程度：高 | 已核实。

### 4. app.qps 指标计算根本性错误（把累计值当 60 秒增量）
- `backend/.../datasource/metric/ActuatorCollector.java`（71-73 行）：`app.qps = httpMetrics.count / 60.0`。
- Micrometer `http.server.requests` 的 `COUNT` 是 **JVM 启动以来的累计请求数**，不是「近 60 秒」的值。服务运行 1 天后该指标虚高约 1440 倍，且随运行时长线性膨胀。基于它的告警规则、图表、AI 问答全部失真。
- 严重程度：高 | 已核实。

### 5. 指标采集任务可被并发重复提交（竞态）
- `backend/.../schedule/MetricCollectJob.java`（43-63 行）+ `MetricCollectService.runTask()`：是否到点依据 DB 的 `lastRunTime`，而 `lastRunTime` 是采集**完成之后**才更新；采集在 4~8 线程池异步执行。
- 只要单次采集耗时 > 扫描节拍（1s）或超过 interval（Actuator 5s 超时、Prometheus 8s 超时、`net.conn.count` 还要 spawn PowerShell），下一拍就会把同一任务再次提交 → 同一任务并发重复执行、指标重复入库。类注释声称「不会让重复任务并发跑」，与实现不符。
- 连带问题：`datasource/metric/OshiCollector.java` 的 `prevTicks` 无同步，并发进入会导致 CPU 使用率计算错误（-1 或偏差极大）。
- 严重程度：高 | 已核实（竞态机制确定）。

### 6. JWT / AES 密钥、数据库密码默认值硬编码在仓库中
- `backend/src/main/resources/application.yml`：
  - 第 51 行 `jwt-secret: ${JWT_SECRET:aiops-platform-dev-secret-key-change-me-...}` —— 不设置环境变量时使用仓库内默认密钥，任何知道该值的人可离线伪造任意用户（含 ADMIN）的合法 token，完全绕过登录和验证码。
  - 第 52 行 `aes-key: ${AES_KEY:16bytes-1234abcd}` —— 用于加密 SMTP 授权码、LLM api_key、ES 密码落库，密钥公开等于密文等于明文。
  - 第 20 行 `password: ${MYSQL_PWD:123456}` —— root 弱密码默认值。
- 另外 `common/util/AesUtil.java` 使用 **AES/ECB** 模式（无 IV，相同明文块产生相同密文块）。
- 严重程度：高（安全） | 已核实。

### 7. ES 查询 DSL 注入：`analyzer` 参数未转义
- `backend/.../datasource/log/EsQueryBuilder.java`（96-98 行）：keyword/levels/services/traceId 都过了 `escape()`，唯独 `analyzer` 原样 `String.format` 拼进查询 JSON；`analyzer` 直接来自用户请求体（`LogSearchService` 第 146 行，无校验）。
- 用户可闭合字符串注入任意查询子句（改查询语义、注入聚合/脚本子句）。另外 analyzer 传 null 时会拼成字符串 `"analyzer":"null"`，ES 直接报 400。
- 严重程度：高 | 已核实。

### 8. 告警抑制逻辑错误：任意一台 host 掉线就抑制全部 service 告警
- `backend/.../alert/service/impl/AlertDetectServiceImpl.java` `isSuppressed()`（302-323 行）：注释意图是「service 所在 host down 则抑制」，实现却是查出**全部** host 类型 target，只要**任何一台** host `status=0` 或 agent 掉线，就对**所有** service 告警返回抑制——host 与 service 之间没有任何归属匹配。
- 一台无关机器 agent 离线 → 平台上所有服务类告警静默漏报；且被抑制的活跃告警会被直接置为 `closed`（164-166 行），静默解除后也不再恢复。
- 严重程度：高（功能性错误） | 已核实。

### 9. DslSafetyValidator（NL2DSL 安全校验器）多处可绕过
- `backend/.../log/safety/DslSafetyValidator.java`：
  - `top_hits.docvalue_fields` / `filter` 聚合内的字段不进白名单校验 → 可读取索引任意字段；
  - `query_string` 的 query 串可写任意 Lucene 字段语法 → 字段白名单整体失效；
  - `{"exists":{"field":"任意字段"}}` 的 `field` 被当作参数 key 跳过 → 任意字段存在性探测；
  - 强制时间范围检查扫描整棵树（含 aggs），聚合内 range 即可满足 → 实际查询可无时间边界；
  - `terms` lookup 形式（`{"terms":{...{"index":...}}}`）可跨索引读取。
- 严重程度：高（对一个以字段白名单为安全底线的组件属于核心绕过） | 未复跑，逻辑论证确定。

---

## 二、中危问题

### 后端
10. **DashboardService 缺属主校验（IDOR）**：`detail/save/delete/setDefault` 都不校验模板归属（`move()` 反而校验了），任意登录用户可用他人模板 id 读/改/删他人大盘；`currentUserId()` 未登录时兜底返回 `1L`。
11. **MyBatis-Plus null 字段不更新 → 「分组移出/删除后 group_id 置 NULL」不生效**：`DashboardGroupController.delete()`（124-128 行）与 `DashboardService.move()` 用 `updateById` 设置 `groupId=null`，MP 默认 NOT_NULL 策略会跳过 null 字段，SQL 里根本没有 `SET group_id=NULL`。已核实。
12. **指标聚合参数被忽略**：`MetricDataMapper.xml` 写死 `AVG(metric_value)`，Controller 校验了 max/min/sum/count 白名单但 XML 从未引用该参数——前端选任何聚合都返回均值。
13. **事务内发通知**：`AlertDetectServiceImpl.processRule` 的 `@Transactional` 内同步发 webhook（每渠道阻塞最多 10s）/SMTP，多个不可达 webhook 可耗尽 DB 连接池（仅 10）。
14. **LLM 客户端超时配置无效**：`OpenAiCompatibleClient`/`OllamaClient` 接收 `timeoutMs` 但从不使用，硬编码 `block(120s)`，且未设置连接超时；界面配置 10s 超时不生效，慢 LLM 挂住请求线程 120 秒。`AiChatController.doChatStream` 还在返回 emitter 前同步执行工具路由（根因分析最多 2 次 LLM 调用），必然撞 SSE 120s 超时。
15. **mem.used 单位不一致**：OSHI 通道写 MB，Prometheus 通道写 GB，同一 metric_key 差 1024 倍，告警阈值与跨目标图表失真。
16. **Prometheus 文本解析丢样本**：`parseExposition` 用 `lastIndexOf(' ')` 切分，标签值含空格（如挂载点 `My Disk`）的行解析失败被静默吞掉。
17. **EmbeddingUtil.cosine 维度不匹配时截断计算**（Javadoc 说返回 0）——更换 embedding 模型后新旧向量混存时，相似案例检索会给出无意义但可能过阈值（0.30）的匹配。
18. **agent 网络速率少除以采集间隔**：`net.rx/tx.kb.per.sec` 实际是「每采集周期增量」，默认 30s 间隔下数值被低估 30 倍。
19. **新增用户 password 无 @NotBlank**：`@Size` 对 null 放行，`BCrypt.encode(null)` 抛 500。已核实。
20. **DrainParser 淘汰策略与设计不符 + 非线程安全**：超过 max-cluster 时整树清空（注释称 LRU），模板全量重建可引发 new_template 告警风暴；解析器实例被定时 Job 与手动 API 共享但内部 HashMap 无同步。
21. **EsLogClient 不检查 HTTP 状态码**：ES 4xx/5xx 错误体被当正常结果解析，索引不存在/DSL 错/权限不足统统表现为「查询成功 0 命中」。
22. **LogTemplateJobService.fetchLogs 训练数据静默丢失**：拉取不带 service 过滤、固定 5000 条上限（配置的 50000 未被引用），截断后 watermark 直接跳过被丢的时间段。
23. **demo-order-service**：支付服务返回 HTTP 200 + `code=400` 时，`retrieve()` 不抛异常，订单仍被置为 `PAID`——支付被拒的演示链路是坏的。

### 前端
24. **Agent.vue 轮询定时器永不清理**（302 行 `setInterval(load, 30000)` 未保存、无 onBeforeUnmount）：离开页面后持续轮询，反复进出叠加多个定时器。已核实。
25. **动态路由只增不删**：退出登录不移除 `addRoute` 注册的路由，低权限用户登录后手输 URL 可渲染前账号的页面（数据接口仍受后端权限保护，属菜单级越权）。
26. **Chat.vue 流式回答期间切换会话 → 消息持久化到错误的会话**（`persistTurn` 用实时的 `current.value`）。
27. **LineChart.vue / MetricCompare.vue 的 ECharts 实例不 dispose、ResizeObserver 不 disconnect**，自定义大盘反复进出内存持续上涨（BaseChart.vue 是正确写法可参照）。
28. **Target.vue / Dashboard.vue loadTrend 无竞态保护**：快速切换目标时旧响应覆盖新图；自动刷新定时器放大概率。
29. **Incident.vue 打开另一事件不重置 aiData/similar/report**，串显上一事件的 AI 诊断内容，易误导排障。
30. **MailConfig.vue 编辑/启用切换传空授权码**：`save`/`toggle` 原样提交 `passwordEnc: ''`（对比 Datasource.vue 的「空则不传」写法），若后端全量更新会清空 SMTP 授权码，邮件渠道静默失效。

---

## 三、低危 / 建议改进（摘要）

- `AlertDetectJob` 日志里 `triggered` 恒为 0（异步提交后立即读计数）。
- `IncidentServiceImpl.resolve` 把已 closed/false_positive 的告警也无条件改成 resolved，破坏终态语义。
- 用户/角色 CRUD、基线「先删后插」等多表写操作均无 `@Transactional`，中途失败留半截数据。
- `incident_alert_rel` 插入异常被 `catch (Exception ignore)` 吞掉；incident `alertCount` 读-改-写有丢失更新风险。
- 401/403 一律返回 HTTP 200（JwtAuthFilter 手拼 JSON、RequirePermInterceptor），网关/监控无法按状态码识别鉴权失败；且 401 报文 msg 含引号会拼出非法 JSON。
- JWT 滑动续期用旧 claims 重签，用户被禁用/角色回收后旧权限一直有效；`permCache` 只增不减。
- CORS：`allowedOriginPatterns("*") + allowCredentials(true)`。
- `/api/agent/download/{id}` 完全无鉴权可匿名枚举下载；SSE 把 JWT 放 URL query。
- ES 时间换算硬编码 `minusHours(8)`/`plusHours(8)`（多处），换部署时区即整体偏移；前端 `Rule.vue` 基线图用 `toISOString()` 取 UTC 日期，北京时间 0-8 点会查成昨天。
- `LogRuleController` 的 ruleType 用 `String.contains` 校验，任意子串可通过并静默失效。
- `AiLogExplainController` 的 scene 参数解析后从未使用，`anomaly_explain` 场景不可达（死代码）。
- `Nl2DslService.execute` 数据源硬编码 `selectById(1L)`；`history` 在 userId 为 null 时返回全部用户历史；`hitCount` 存的是本页条数而非 total。
- `EsFieldProbe` 只取第一个索引的 mapping（注释称并集），失败时还会「先删后插」清空字段缓存。
- `Nl2QueryService.execMetricQuery` 对 target_id 为 NULL 的脏数据 `Long.parseLong("null")` 抛 500。
- 后端 OshiCollector：Windows 下 `cpu.load` 采到 -1 直接入库；`netConnCount` 的 `p.waitFor()` 无超时可占死采集线程。
- 列表页通用问题：改 page-size / 加筛选条件后不重置 current，落在空页显示空表格（仅 log/Rule.vue 正确处理）。
- `DashboardCustom.vue doAddWidget` 局部变量遮蔽 reactive 表单导致重置失效；自动刷新「暂停(0)」选项无法持久化（`Number('0') || 30000`）。
- `log/Rule.vue` 开关切换失败不回滚，UI 与服务端状态不一致。
- `AiScenarioService` 失败时内外两层 catch 各写一条 fail 日志（双写）；`providerId` 硬编码 1。
- `LlmSchemaRetryService.extractJson` 花括号计数不感知字符串字面量，值中含 `}` 会提前截断。

---

## 四、核实过但确认不是问题的点（避免误修）

- `JwtAuthFilter` 在 finally 中正确清理 ThreadLocal，无泄漏。
- 定时 Job 均为 `fixedDelay` 不重叠；唯一并发重复执行即上文 #5。
- `AgentAuthFilter` 对 agent 上报接口的鉴权成立（Controller 内有二次校验），未发现绕过。
- 验证码一次性消费 + 5 分钟过期，无重放漏洞。
- `MetricDataMapper.xml` 的 `${expression}` 来源是 admin 白名单，无注入面；其余 mapper XML 均 `#{}` 参数化。
- MP 全局配置了 `logic-delete-field: deleted`，软删过滤正常。
- `BaseChart.vue` 正确 dispose；`utils/time.js` 全部本地时间处理无时区问题。

---

## 五、建议修复优先级

1. **清空角色/权限**（#1）与 **AI 面板双重解包**（#2）——影响演示最直观；
2. **统计口径错误**（#3、#4、#12、#15、#18）——图表/告警/AI 问答的数据基础全错；
3. **安全三件套**：默认密钥硬编码（#6）、ES DSL 注入（#7）、安全校验器绕过（#9）；
4. **告警抑制误杀**（#8）与采集竞态（#5）；
5. 其余中危按模块顺手修。
