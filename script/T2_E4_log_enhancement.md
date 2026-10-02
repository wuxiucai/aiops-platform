# T2 - E4 日志增强根因 （真 LLM A/B)

- Incident: `alert_incident.id = 48` (cpu.usage = 50.0)
- LLM: deepseek-chat
- A 组 prompt: 无 ${logTemplateSummary}
- B 组 prompt: 含 ${logTemplateSummary}
- 各 3 发真调用

| # | latency_ms | conf | isLlmFallback |
|---|---:|---:|---|
| A1 | 5298 | 0.75 | None |
| A2 | 3699 | 0.80 | None |
| A3 | 3066 | 0.80 | None |
| B1 | 4650 | 0.60 | None |
| B2 | 6781 | 0.72 | None |
| B3 | 4503 | 0.72 | None |

- A 平均 conf = 0.78，mean latency = 4021 ms
- B 平均 conf = 0.68，mean latency = 5311 ms

## 结论

加入 log_template_summary 上下文后，LLM 返回 primaryCause.confidence 平均从 0.78 降到 0.68（下降 0.10)，latency 从 4021ms 增至 5311ms(+32%).在本例中 log enhancement 未提升 confidence（反而下降）, 即该上下文效果有限（在该 incident 案例中）

## 数据来源

- LLM 调用： `/api/ai/scenario/root-cause/48` 通过 DeepSeek deepseek-chat（非 mock)
- incident #48 （真实 M3静默专用规则 cpu.usage=50.0 告警）
- A prompt 修改： `UPDATE llm_prompt_template SET user_prompt_tpl=REPLACE(user_prompt_tpl,'${logTemplateSummary}','(未接入日志线索)') WHERE scene_code='root_cause';`
- B prompt: 该上面行反 REPLACE 恢复
- latency: 客户 time.time() 前后核测量
