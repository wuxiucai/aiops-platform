# AIOps 平台 A 方案扩展实施任务书

**给执行 AI 的指令**：严格按本文档实施所有 3 个扩展。每项工作完成后**用 ≤30 行简报汇报**，不再要求写 .md 文档。**不要推进任何代码上 production 或源仓库 main 分支**——所有工作都在你的本地分支 `feature/extension-a` 上推进，逐项合到 main。

**禁止**：在每个任务中做任何"文档美化/创新"，仅按下面契约实现。每完成一项立即 push。

---

## 一、Linux 远程监控（5~7 天）

### 1.1 总体架构

**Push 模式**：部署到目标 Linux 的 agent 主动 POST 指标到平台。

```
目标 Linux 主机
  └─ aiops-agent-1.0.jar (单 jar, ~20MB)
     ├─ 定时每 30 秒调用 OSHI 采集 cpu.usage / mem.usage / disk.usage / net.rx/tx
     ├─ HTTP POST http://<platform-host>:8080/api/agent/metric
     │   Header: X-Agent-Key: <64 位 agent_key>
     │   Body: [{metricKey, value, targetAgentKey, timestamp}]
     └─ 心跳每 5 分钟: POST /api/agent/heartbeat

平台侧（你部署的 aiops-platform）
  ├─ AgentController (新)
  │   └─ 接收 metric 上报 + 心跳，鉴权 agent_key
  ├─ 新表 monitor_agent + monitor_agent_config
  └─ 复用现有 metric_data 表（agent 数据写入）
```

### 1.2 SQL DDL（新增 2 张表）

```sql
CREATE TABLE monitor_agent (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  target_id BIGINT NOT NULL COMMENT '关联 monitor_target.id',
  agent_key VARCHAR(64) NOT NULL UNIQUE COMMENT '鉴权密钥（生成后下发给 agent）',
  status TINYINT DEFAULT 1 COMMENT '0禁用1启用',
  version VARCHAR(32),
  last_heartbeat DATETIME,
  last_metric_time DATETIME,
  install_command TEXT COMMENT '生成给用户的安装脚本（含 agent_key）',
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted TINYINT NOT NULL DEFAULT 0,
  KEY idx_target (target_id)
);

CREATE TABLE monitor_agent_config (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  agent_id BIGINT NOT NULL,
  config_key VARCHAR(64) NOT NULL COMMENT '如 collect.interval.sec / log.path',
  config_value VARCHAR(255),
  UNIQUE KEY uk_agent_key (agent_id, config_key)
);
```

### 1.3 后端接口（全部加 @RequirePerm，路径前缀 /api/agent）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/agent/list` | 当前用户看到的 agent 列表（关联 target 信息） |
| POST | `/api/agent/create` | 创建 agent：选定 target_id → 生成 agent_key → 返回 install_command |
| PUT | `/api/agent/{id}/toggle` | 启停 |
| GET | `/api/agent/download/{id}` | **下载 aiops-agent.jar（包含此 agent_key）**——把 jar + application.yml 打包返回 |
| POST | `/api/agent/metric` | **agent 上报指标**（鉴权：X-Agent-Key 头）— 写 metric_data |
| POST | `/api/agent/heartbeat` | **agent 心跳**（更新 last_heartbeat） |
| GET | `/api/agent/status/{targetId}` | 前端轮询 agent 是否在线（>2 分钟无心跳视为 down） |

**鉴权设计**：
- `POST /metric`/`POST /heartbeat` 走 **AgentAuthFilter**：读 `X-Agent-Key` 头，查 monitor_agent 表；找不到→401；status=0→403
- 其他接口走 JWT

### 1.4 aiops-agent 模块（新建 backend 同级目录 agent/）

```
agent/
├── pom.xml                         # 独立 module，不打进 aiops-backend
├── src/main/java/com/aiops/agent/
│   ├── AgentApplication.java       # 主类
│   ├── config/AgentConfig.java     # 读 application.yml
│   ├── collect/OshiCollector.java  # 仅 OSHI（不要 Actuator）
│   ├── push/MetricsPusher.java     # 用 WebClient POST /api/agent/metric
│   └── schedule/HeartbeatJob.java
└── src/main/resources/
    └── application.yml             # 此处含 platformUrl/agentKey/targetId（下载时填充）
```

**打包方式**：spring-boot-maven-plugin repackage，产出 ~18MB 单 jar。

### 1.5 平台 install_command 生成

前端 `/api/agent/{id}/install-command` 返回：
```bash
wget http://<platform>/api/agent/download/{id} -O aiops-agent.jar
nohup java -jar aiops-agent.jar \
  --platform.url=http://<platform>:8080 \
  --agent.key=<agent_key> \
  --target.id={target_id} \
  > aiops-agent.log 2>&1 &
```

### 1.6 前端页面（`views/monitor/Agent.vue`）

- 列表：agent_id / target_name / status（在线/离线）/ last_heartbeat / version / 操作
- 操作：新建（选 target）/ 启停 / **下载 jar** / **复制安装命令** / 强制刷新状态
- 卡片：状态灯（绿/灰）

### 1.7 验收（M8-L01 ~ L08）

| # | 检查 | 通过条件 |
|---|---|---|
| L01 | 后端编译过 + 接口路由存在 | grep 出 7 个 method |
| L02 | 创建一个 agent 后能下载到 jar | jar 启动 --help 不报错 |
| L03 | Linux VM（或 Mac/Docker）跑 agent | jar 起来无报错，控制台有 "agent started" |
| L04 | 30 秒后 metric_data 出现新行 | 该 agent 关联的 target_id + cpu.usage |
| L05 | 心跳 ≤ 5 min 一次 | monitor_agent.last_heartbeat 持续更新 |
| L06 | 错误 agent_key 被拒 | 401 |
| L07 | 状态显示 | 前端 agent page 显示"在线" |
| L08 | mvn test 不回归 | 100+ PASS |

---

## 二、SMTP 邮件通知（2~3 天）

### 2.1 SQL DDL（新增 1 张表 + 加 1 个字段）

```sql
CREATE TABLE sys_mail_config (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  name VARCHAR(64) NOT NULL,
  smtp_host VARCHAR(128) NOT NULL COMMENT 'smtp.qq.com / smtp.163.com',
  smtp_port INT NOT NULL DEFAULT 465,
  username VARCHAR(128) NOT NULL,
  password_enc VARCHAR(1024) NOT NULL COMMENT 'AES 加密授权码',
  from_name VARCHAR(64) DEFAULT 'AIOps 告警',
  ssl TINYINT DEFAULT 1,
  enabled TINYINT DEFAULT 1,
  is_default TINYINT DEFAULT 0,
  remark VARCHAR(255),
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted TINYINT NOT NULL DEFAULT 0
);

ALTER TABLE notify_channel ADD COLUMN mail_config_id BIGINT NULL COMMENT '关联 sys_mail_config.id，channel_type=email 时使用';
```

### 2.2 后端

**MailService.java** 核心：
```java
public void sendAlert(MailConfig config, String receiver, String subject, String content) {
    JavaMailSenderImpl sender = new JavaMailSenderImpl();
    sender.setHost(config.getSmtpHost());
    sender.setPort(config.getSmtpPort());
    sender.setUsername(config.getUsername());
    sender.setPassword(AesUtil.decrypt(config.getPasswordEnc(), aesKey));
    // props: mail.smtp.auth=true, mail.smtp.ssl.enable=true (ssl=1) 或 starttls
    MimeMessage msg = sender.createMimeMessage();
    MimeMessageHelper helper = new MimeMessageHelper(msg, true, "UTF-8");
    helper.setFrom(config.getUsername(), config.getFromName());
    helper.setTo(receiver);
    helper.setSubject(subject);
    helper.setText(content, false);  // text (M8 简化，不做 html)
}\\n```

**NotifyChannel 改造**：
- `channel_type='email'` 时, `config.mailConfigId` 关联到 sys_mail_config
- `config.receivers` JSON 数组（接收人邮箱列表）

**告警链路改造**：
- `AlertDetectStep` 推送通知时, 若渠道是 email, 调 MailService.sendAlert
- 邮件标题：`[{level}] {alert_title}`
- 邮件内容（text）:
  ```
  告警时间: {first_trigger_time}
  告警对象: {target_name}
  指标: {metric_key} = {trigger_value}
  阈值: {threshold}
  
  点击处理: http://<platform>/#/alert/detail/{alert_id}
  ```

### 2.3 后端接口

| 方法 | 路径 | 说明 |
|---|---|---|
| CRUD | `/api/system/mail-config` | 邮箱配置（多个，is_default 唯一） |
| POST | `/api/system/mail-config/{id}/test` | 发一封测试邮件到指定邮箱 |

### 2.4 前端页面（`views/system/MailConfig.vue`）

- 列表：name / smtp_host / username / is_default / enabled / 操作
- 操作：新建 / 编辑 / 删除 / **测试发送**（输入邮箱地址） / 设为默认
- 复用 system 模块权限 `system:mail:*`

### 2.5 验收（M8-M01 ~ M05）

| # | 检查 |
|---|---|
| M01 | QQ 邮箱 smtp.qq.com 465 测试通过 |
| M02 | 一个 alert_record 触发后，配置的接收邮箱收到邮件 |
| M03 | notify_channel 配置 email 渠道并绑定 mail_config_id，告警 email 实测 |
| M04 | 邮箱密码 aes 加密存库，response 不返回明文 |
| M05 | 测试发送接口正常 + 错误 smtp 显示错误信息 |

---

## 三、自定义监控大盘（4~5 天）

### 3.1 SQL DDL（新增 2 张表）

```sql
CREATE TABLE dashboard_template (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  name VARCHAR(64) NOT NULL,
  is_default TINYINT DEFAULT 0,
  layout_config JSON COMMENT '网格布局：[{widgetId, x, y, w, h}]',
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_user_default (user_id, is_default)
);

CREATE TABLE dashboard_widget (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  template_id BIGINT NOT NULL,
  widget_type VARCHAR(32) NOT NULL COMMENT 'stat_card|line_chart|top_alert|top_template|incident_list|metric_compare',
  title VARCHAR(128) NOT NULL,
  config JSON COMMENT '按 widget_type 不同：{"targetId":1,"metricKey":"cpu.usage","timeRangeHours":1}',
  sort INT DEFAULT 0,
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  deleted TINYINT NOT NULL DEFAULT 0,
  KEY idx_template (template_id)
);
```

### 3.2 Widget 类型与 config 规范

| widget_type | config 必填 | 说明 |
|---|---|---|
| stat_card | `{"refType":"alert_pending_count"}` | 单一大数字；refType 支持 alert_pending_count / incident_open_count / log_anomaly_24h / llm_call_24h |
| line_chart | `{"targetId":N,"metricKey":"cpu.usage","timeRangeHours":1}` | 时序折线 |
| top_alert | `{"limit":5,"timeRangeHours":24}` | top 5 触发最多告警 |
| top_template | `{"limit":5,"timeRangeHours":1}` | top 5 高频日志模板 |
| incident_list | `{"limit":10,"status":"open"}` | 进行中 incident |
| metric_compare | `{"targetId":N,"metricKeys":["cpu.usage","mem.usage"],"timeRangeHours":1}` | 同 target 多 metric 对比 |

### 3.3 后端接口

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/dashboard/templates` | 当前用户所有模板 |
| POST | `/api/dashboard/template` | 新建（含 layout + widgets） |
| GET | `/api/dashboard/template/{id}` | 详情 |
| PUT | `/api/dashboard/template/{id}` | 修改（含 layout 改动 + widget 增删） |
| DELETE | `/api/dashboard/template/{id}` | 删除 |
| PUT | `/api/dashboard/template/{id}/default` | 设为默认 |

**Widget 数据查询**复用现有 monitor/log/stat 接口，不新增数据 endpoint。

### 3.4 前端实现（`views/monitor/DashboardCustom.vue`）

**引入：vue-draggable-resizable 或 vue-grid-layout**（推荐 vue-grid-layout，~150kB）

```bash
npm install vue-grid-layout@^3.0.0-beta1
```

**核心结构**：
```vue
<template>
  <div>
    <el-radio-group v-model="mode" size="small">
      <el-radio-button value="view">查看</el-radio-button>
      <el-radio-button value="edit">编辑</el-radio-button>
    </el-radio-group>
    <grid-layout
      :layout.sync="layout"
      :col-num="12"
      :row-height="80"
      :is-draggable="mode==='edit'"
      :is-resizable="mode==='edit'"
    >
      <grid-item v-for="item in layout" :key="item.i" :i="item.i" :x="item.x" :y="item.y" :w="item.w" :h="item.h">
        <WidgetRenderer :widget="getWidget(item.i)" />
      </grid-item>
    </grid-layout>
  </div>
</template>
```

**WidgetRenderer** 按 widget_type 渲染：
- stat_card → `<StatCard :config="..."/>`
- line_chart → `<LineChart :config="..."/>`
- top_alert → `<TopAlertTable :config="..."/>`
- 等等

**编辑模式**：
- 拖拽 + 调整大小 → 实时写回 layout_config（在 vue 内部）
- 右上角"保存"按钮 → PUT `/api/dashboard/template/{id}` 持久化 layout
- 顶部"添加 widget"按钮 → 弹窗选 widget_type + 填写 config → 加入 layout

### 3.5 验收（M8-D01 ~ D08）

| # | 检查 |
|---|---|
| D01 | npm run build 通过 |
| D02 | 新建一个自定义模板加入 3 个 widget（stat_card/line_chart/top_alert） |
| D03 | 拖拽改变布局，保存后刷新仍然保留位置 |
| D04 | PUT `/api/dashboard/template/{id}` 后端实际持久化 layout_config |
| D05 | 设为默认后下次登录自动加载 |
| D06 | 删除一个 widget 后保存，刷新不存在 |
| D07 | 真实数据渲染（line_chart 能显示 cpu.usage 1h 折线） |
| D08 | mvn test 不回归 |

---

## 四、整体时间表

| Task | 工作量 | 截止 |
|---|---|---|
| I. Linux agent 模块（独立 jar） | 5 天 | **2026-10-07** |
| II. SMTP 邮件 | 2 天 | **2026-10-09** |
| III. 自定义大盘 | 4 天 | **2026-10-13** |
| 联调 + mvn test 不回归 | 1 天 | **2026-10-14** |
| push 到 main | 持续（每完成一项） | — |

**总计 12 天。** M7 演示（T4 故障演练）已就位可在新功能完成后跑一次。

---

## 五、汇报规则（重新确认）

1. 每完成一项 push 一次（commit message: `[ext] <name>: <one-line>`）
2. 完成后 ≤ 30 行简报：改了哪些文件 / commit hash / build 状态
3. **不写任何 .md 文档**——所有解释工作我处理
4. 你只能修改 feature/extension-a 分支，合到 main 由你（用户）review 后操作
5. 遇到**任何异常/模糊**先停下来 ≤3 行描述给我，**不要瞎做**

开始顺序：**I → II → III**。一项完成才开下一项。
