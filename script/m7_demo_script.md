# M7-T1 故障演示分镜脚本（10 分镜）

每个分镜 ≤ 30 秒，总时长 ≤ 6 分钟。每个分镜列出：演示要点 / 关键 UI / 截图路径占位（用户自行截图）。

---

## 分镜 1 — 登录 + 大盘

- **要点**：admin/123456 登录，进入 `/monitor/dashboard`
- **UI**：登录页验证码 → 4 张指标卡 + CPU/内存实时曲线
- **截图**：`screens/T1_01_dashboard.png`
- **旁白**：这是 aiops-platform 主界面，左侧 7 个菜单按 RBAC 权限驱动，中央是实时指标趋势。

## 分镜 2 — 触发故障注入

- **要点**：新 tab 调用 `http://localhost:8081/demo/fault/cpu-burn?seconds=120&threads=8`
- **UI**：返回 `{ok:true, seconds:120, threads:8}`
- **截图**：`screens/T1_02_fault_cpu.png`
- **旁白**：接下来注入 CPU 高压故障——8 线程跑 120 秒，模拟真实生产过载。

## 分镜 3 — 指标飙升可视化

- **要点**：大盘 CPU 曲线从 ~30% 跳到 90%+
- **UI**：监控大盘曲线明显突跃
- **截图**：`screens/T1_03_cpu_spike.png`
- **旁白**：cpu.usage 明显飙升，触发我们定义的阈值规则。

## 分镜 4 — 告警触发 & InApp 通知

- **要点**：AlertDetectJob 每 30s 检测，落 alert_record + sys_message
- **UI**：右上铃铛红点 → 告警详情页 `"[WARN] CPU 高负载 cpu.usage=92.3"`
- **截图**：`screens/T1_04_alert_record.png` + `screens/T1_04b_inapp.png`
- **旁白**：系统检测到 CPU 过高并持续 30s，自动落一条 alert_record 并发站内通知。

## 分镜 5 — Alert 聚合到 Incident

- **要点**：同 target 的多条告警自动聚合到同一个 incident（dedup）
- **UI**：`/alert/incident` 新 incident open，alert_count ≥ 1
- **截图**：`screens/T1_05_incident.png`
- **旁白**：抖动告警被 dedup_key 归并到同一个 incident，便于集中处置。

## 分镜 6 — AI 根因分析

- **要点**：进入 incident 详情，点"刷新"，调 `/api/ai/scenario/root-cause/{id}` 给 LLM 分析
- **UI**：右侧 AI 面板，primaryCause / rootCauses / confidence 进度条 / reasoningChain / suggestions
- **截图**：`screens/T1_06_root_cause.png`
- **旁白**：LLM 基于并发告警（含时间序）给出 primaryCause="cpu.usage" + confidence=0.85 + evidence 引用（首次触发最早 + trigger_value）。

## 分镜 7 — 相似案例（embedding）

- **要点**：同一面板下方，相似案例 3 张卡片按 similarity 排序展示
- **UI**：卡片含 title / symptom / rootCause / solution / similarity 进度条 + matchType 徽标（"语义检索"绿 / "关键词兜底"灰）
- **截图**：`screens/T1_07_similar_case.png`
- **旁白**：SiliconFlow BGE 1024 维嵌入向量 + 余弦相似度，把 incident 种子文本与 kb_fault_case 3 案例匹配排序。

## 分镜 8 — NL2ES-DSL 自然语言查日志

- **要点**：`/ai/nl2dsl` 输入"过去 1 小时的 ERROR 日志"，看 LLM 生成 DSL → 校验通过 → 执行 → 结果
- **UI**：左侧 problem + prettyJson DSL 显示 validated=true；右侧 hitCount + by_level 直方图 + records
- **截图**：`screens/T1_08_nl2dsl.png`
- **旁白**：自然语言查日志 DSL，生成后被 DslSafetyValidator 三重校验，然后被 ES 执行；下方历史 tab 可见本次提问落 `nl_query_log`。

## 分镜 9 — 故障报告生成（Markdown 6 节）

- **要点**：Incident 详情点"生成故障报告"，LLM 写出 一、故障概述 ... 六、改进措施
- **UI**：报告完整 markdown 六节全部命中；再次点会显示 "（来自缓存）"
- **截图**：`screens/T1_09_report.png`
- **旁白**：DeepSeek 一次调用生成结构化 Markdown 报告，首轮写 alert_incident.llm_report，二次调用直接缓存不重复付费。

## 分镜 10 — 数据落库证明 + 收尾

- **要点**：打开 MySQL 客户端，跑 3 条汇总 SQL：llm_call_log 行数、alert_record 近 1 小时、kb_similarity_log 数据
- **UI**：MySQL 终端或 DataGrip 界面显示真实行数
- **截图**：`screens/T1_10_data.png`
- **旁白**：所有过程数据都落在 MySQL，可被论文引用；GitHub 仓库 `wuxiucai/aiops-platform` commit 已 push 到 main 分支。

---

## 演示顺序（心灵验 2-cinema轮备）

1. 启动 backend / demo-order / demo-payment（用 `m7_startup.bat | .sh`）
2. admin/123456 登录 → 大盘
3. cpu-burn → 等待 90s 指标飙升 → 告警 + inapp + incident
4. 详情页 → AI 根因 + 相似案例（同时落 llm_call_log + kb_similarity_log）
5. `/ai/nl2dsl` 自然语言查询（同时落 nl_query_log）
6. 生成报告（markdown 6 节）
7. MySQL 数据证明收尾

**总时长 ≤ 6 分钟，3 服务 + 3 网络连接（MySQL / ES / LLM）全要在线。**

## 截图动作要点

每个分镜截图强调 **一个数据点**（不要 UI 空白）。截图文件名按 `screens/T1_NN_<cinematic>.png` 命名方便后续评片与论文 §7/6 的引用。

最终验证：演示完成后立刻跑 `python script/m5_llm_cold.py 1`，把后台 llm_call_log 由演示期间的真实调用记录到 200+ 行 ——这是你 §7.2 实验数据的真实而生。
