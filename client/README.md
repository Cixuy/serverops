# ServerOps Python Client

ServerOps 客户端采集本机 CPU、内存、网络、磁盘 IO 和磁盘空间数据，并按照
[`docs/默认模块.openapi.yaml`](../docs/默认模块.openapi.yaml) 注册、发送心跳和上报指标。

## 环境与启动

要求 Python 3.11 或更高版本。

```powershell
cd client
py -3 -m venv .venv
.\.venv\Scripts\Activate.ps1
python -m pip install -e ".[dev]"
serverops-client
```

首次打开后进入“设置”，填写服务器地址和 `X-Agent-Token`。配置及服务端返回的
`serverCode` 保存在当前用户的应用数据目录，不会写入仓库。

也可以通过环境变量提供配置，适合服务器部署：

```powershell
$env:SERVEROPS_SERVER_URL = "http://127.0.0.1:8080"
$env:SERVEROPS_AGENT_TOKEN = "your-agent-token"
$env:SERVEROPS_ALERT_COOLDOWN_MINUTES = "30"
$env:SERVEROPS_HEALING_TIMEOUT_RECHECK_DELAY = "5"
serverops-client
```

如果服务器地址已经包含 `/api/v1`，客户端会自动避免重复拼接路径。

## 运行流程

1. 启动时向 `POST /api/v1/servers/register` 发送主机信息并持久化 `serverCode`。
2. 默认每 5 秒采集并向 `POST /api/v1/metrics` 上报一次指标。
3. 默认每 30 秒向 `POST /api/v1/servers/heartbeat` 发送心跳。
4. 按服务端下发的监控配置检查进程和 TCP 端口，并向
   `POST /api/v1/agent/service-status/report` 上报服务状态。
5. 连接 `/api/v1/ws/client` 并发送 `PULL_MONITOR_CONFIG` 拉取配置；收到
   `MONITOR_CONFIG_CHANGED` 后立即重新拉取，断线时自动重连。
6. 检测到进程停止或端口异常时向 `POST /api/v1/alert-events` 上报告警；同一服务在
   持续异常期间只上报一次，恢复后再次异常还会受本地告警冷却限制。默认冷却 30 分钟，
   可在“连接设置”中调整。若当前异常匹配绑定的自愈规则，告警携带 `rule_id`；无匹配
   规则时省略该字段。上报失败后的再次尝试也遵守相同冷却时间。
7. 指标先写入本地 SQLite 队列；断网时指数退避，恢复后按顺序补发。
8. 队列默认最多保存 10,000 条，超出时优先删除最早的数据。
9. 按服务配置的检测间隔查询每项服务绑定的自愈规则，递归计算 `triggerConfig` 中的指标、
   进程/端口状态和日志正则条件；匹配后按规则的超时与重试参数执行命令，并向
   `POST /api/v1/healing-action-logs` 上报结果；告警创建成功时结果会携带对应的
   `alertId`，执行起止时间使用带时区的 ISO 8601 格式。`disk_usage` 使用所有挂载点中的
   最大利用率，日志匹配最多读取文件末尾 1 MiB，且文件修改时间须在匹配窗口内。
   命令以退出码 `0` 且无明确 Shell 错误输出视为成功；非零退出码以及找不到路径、文件或
   命令等错误视为失败并按规则重试。命令开始 3 秒后会每秒重新采集指标和服务状态，触发条件
   一旦恢复便提前判定成功。命令最终超时后默认再等待 5 秒复检，触发条件已恢复则改判
   成功，否则保持超时。复检延时可在“设置”中调整，也可通过
   `SERVEROPS_HEALING_TIMEOUT_RECHECK_DELAY` 配置。
   收到 `HEALING_RULE_BINDING_CHANGE` WebSocket 通知后会立即重新拉取并检查规则。
10. 点击“刷新”或按 `F5` 会通过 HTTP 重新拉取监控配置，随后立即重新探测服务并
    拉取、检查各服务绑定的自愈规则。

客户端 WebSocket 处理 `PULL_MONITOR_CONFIG_RESP`、`MONITOR_CONFIG_CHANGED`、
`HEALING_RULE_BINDING_CHANGE`、`pong` 和 `error`；主动发送的消息为
`PULL_MONITOR_CONFIG` 与 `ping`。未知的 `action` 会被忽略。

## 控制台日志

客户端使用统一格式向控制台输出启动、连接、配置刷新、服务状态、告警、自愈和
WebSocket 事件：

```text
2026-08-27 14:30:00 [INFO] [serverops-agent] Agent 注册成功 (serverCode=SRV-001)
2026-08-27 14:30:05 [WARN] [serverops-agent] 心跳请求失败
2026-08-27 14:30:10 [ERROR] [serverops-agent] 自愈规则配置无效
```

交互式终端中的日志级别会使用不同颜色；设置环境变量 `NO_COLOR=1` 可关闭颜色。
默认输出 `INFO` 及以上级别，设置 `SERVEROPS_LOG_LEVEL=DEBUG` 可查看每次 HTTP 请求的
方法、地址、脱敏参数和成功响应状态。请求失败时，无论日志级别如何，都会输出请求地址、
脱敏参数以及 HTTP 或业务错误原因；过长参数会被截断。

OpenAPI 中 `networkInKbps`、`networkOutKbps`、`diskReadKbps` 和 `diskWriteKbps`
字段名称包含 `Kbps`，但字段说明规定单位为 `KB/s`；客户端按文档说明使用 `KB/s`。

## 测试

```powershell
cd client
python -m pytest
python -m ruff check .
```
