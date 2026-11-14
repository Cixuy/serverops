# ServerOps Backend

服务器智能运维平台后端（Spring Boot 3.3.5 + MyBatis-Plus 3.5.7 + MySQL 8 + Redis + InfluxDB）。

## 1. 技术栈与模块

- Java 17 / Spring Boot 3.3.5
- MyBatis-Plus（数据访问）、Spring Security（鉴权，dev 环境 `permit-all=true`）
- MySQL（业务数据）、Redis（缓存/通知）、InfluxDB（时序指标）
- WebSocket（前端/客户端实时推送）
- **agent 模块**（`com.qingruan.agent`）：告警 AI 分析（本地 LLM + 向量检索）

## 2. 本地构建与启动

```powershell
# 编译（本项目在 Windows + Git Bash 下直接用 mvn 启动器可能异常，
# 用 java -cp 显式加载 plexus-classworlds 方式最稳）
$MVN = "F:/apache-maven-3.9.9-bin/apache-maven-3.9.9"
java -cp "$MVN/boot/plexus-classworlds-2.8.0.jar" `
  -Dclassworlds.conf="$MVN/bin/m2.conf" `
  -Dmaven.home="$MVN" `
  -Dmaven.repo.local="$HOME/.m2/repository" `
  -Dmaven.multiModuleProjectDirectory="F:/summer_practice/2/ServerOps/backend" `
  org.codehaus.plexus.classworlds.launcher.Launcher `
  compile

# 运行
java -cp "$MVN/boot/plexus-classworlds-2.8.0.jar" `
  -Dclassworlds.conf="$MVN/bin/m2.conf" `
  -Dmaven.home="$MVN" `
  -Dmaven.repo.local="$HOME/.m2/repository" `
  -Dmaven.multiModuleProjectDirectory="F:/summer_practice/2/ServerOps/backend" `
  org.codehaus.plexus.classworlds.launcher.Launcher `
  spring-boot:run
```

> 或用你习惯的 IDE 直接运行 `ServerOpsApplication`。
> 应用默认上下文路径：`/api/v1`。

## 3. 部署前提：AI 告警分析（agent）模块的 3 个服务

`com.qingruan.agent` 的「告警 AI 分析」依赖 **3 个常驻服务**（均跑在本机/内网，无 Docker 也行），
后端通过 HTTP 与它们通信，**无需改动 pom 依赖**。

| 服务 | 端口 | 作用 | 模型 |
|------|------|------|------|
| llama.cpp chat 实例 | 8081 | 分析告警（根因/建议/思维链） | Qwen3.5-9B-Q4 |
| llama.cpp embedding 实例 | 8082 | 文本向量化（RAG 检索用） | bge-m3 |
| Chroma | 8000 | 向量存储 + 相似度检索 | — |

> 一个 llama.cpp server 同一时间只能加载一个模型，故 chat 与 embedding 必须分开两个实例。
> 显存紧张时（如 RTX 4060 8G）：chat 实例占满 GPU，embedding 实例加 `-ngl 0` 走 CPU 即可。

### 3.1 llama.cpp chat 实例（端口 8081）

```powershell
llama-server.exe `
  -m models\Qwen_Qwen3.5-9B-Q4_K_M.gguf `
  -a Qwen3.5-9B-Local `
  --host 0.0.0.0 --port 8081 `
  --reasoning auto --reasoning-budget 2048
```

- 端点：`http://127.0.0.1:8081/v1/chat/completions`（OpenAI 兼容）
- 对应配置：`ai.analysis.ollama.base-url=http://127.0.0.1:8081/v1`

### 3.2 llama.cpp embedding 实例（端口 8082，加载 bge-m3）

```powershell
llama-server.exe `
  -m models\bge-m3-q4_k_m.gguf `
  -a bge-m3 `
  --host 0.0.0.0 --port 8082 `
  --embeddings --pooling mean
```

> `--pooling mean` 是 bge-m3 等 pooled embedding 模型的常用设置；若你的 llama.cpp build 不支持该参数，请以本地 build 文档为准。
> 显存不足时追加 `-ngl 0` 让 embedding 走 CPU（慢一点不影响实时分析）。

- 端点：`http://127.0.0.1:8082/v1/embeddings`
- 对应配置：`ai.analysis.embedding.base-url=http://127.0.0.1:8082/v1`

### 3.3 Chroma 向量库（端口 8000）

```powershell
pip install chromadb
chromadb run --path ./chroma_data --port 8000
```

- 端点：`http://127.0.0.1:8000`（后端经 `/api/v2/...` 调用）
- 对应配置：`ai.analysis.vector.chroma.base-url=http://127.0.0.1:8000`，`collection=serverops_alert_analysis`
- Chroma 纯 Python 服务，后端用 `RestTemplate` 走 HTTP，无需新增 Maven 依赖；集合首次写入时自动创建（余弦距离 `cosine`）。

## 4. 相关配置（application.yml 摘要）

```yaml
ai:
  analysis:
    ollama:
      base-url: http://127.0.0.1:8081/v1
      api-key: ""          # 本地无需
      model: Qwen3.5-9B-Local
      timeout-ms: 120000
      temperature: 0.2
    embedding:
      base-url: http://127.0.0.1:8082/v1
      model: bge-m3
      timeout-ms: 30000
    vector:
      chroma:
        base-url: http://127.0.0.1:8000
        collection: serverops_alert_analysis
        top-k: 5
        timeout-ms: 30000
```

> 若后端跑在 WSL/Docker，而 3 个服务在 Windows 宿主，需把 `127.0.0.1` 改为宿主局域网 IP（如 `192.168.x.x` 或 `host.docker.internal`）。

## 5. 历史告警回填（初始化向量语料）

向量库「边分析边长」——每次 `analyze` 成功会自动把该告警写入 Chroma。
但**分析前已存在的告警**从未入库，需手动灌一次，否则新告警检索不到历史经验。

```powershell
curl.exe -X POST http://localhost:8080/api/v1/ai-analysis/index/backfill
# 返回 {"code":0,"message":"success","data": <成功条数>}
```

- Chroma 不可达时返回 `0` 且不报错；相同 `alertId` 覆盖，可重复跑（幂等）。

## 6. AI 分析接口一览

| 方法 | 路径 | 说明 |
|------|------|------|
| GET  | `/api/v1/ai-analysis/alerts/{alertId}` | 获取当前告警的 AI 分析结果（单条或 null） |
| POST | `/api/v1/ai-analysis/alerts/{alertId}/analyze` | 幂等触发分析（首次调 LLM，再次直接返回已有；每告警仅一次） |
| POST | `/api/v1/ai-analysis/{id}/feedback` | 回写用户反馈（ACCURATE/INACCURATE/PARTIAL） |
| POST | `/api/v1/ai-analysis/index/backfill` | 历史告警向量回填（一次性） |

> 完整 Swagger/OpenAPI 定义见 `docs/ai-analysis-openapi.yaml`。

### 分析流程（含 RAG）

```
POST /alerts/{alertId}/analyze
 ├─ 幂等：已分析过 → 直接返回
 ├─ 取 alert_event（无 → 404）
 ├─ embed(告警文本) → 向量
 ├─ Chroma 检索 Top-K 相似历史告警（排除自身）
 ├─ 相似案例注入 prompt → 调 Qwen3.5 分析
 ├─ 落库 ai_analysis_result
 └─ 把本条索引进 Chroma（语料自增长）
```

任一外部服务（LLM / embedding / Chroma）不可用时，`analyze` 主流程仍正常返回（仅缺 RAG 上下文），不会 500。

## 7. 数据库

业务表由项目既有脚本初始化；AI 分析相关需自行执行（由你执行，不在此自动建）：

```sql
-- 分析结果表（每告警一条，alert_id 唯一约束保证「只分析一次」）
ALTER TABLE ai_analysis_result
  DROP INDEX idx_alert_id,
  ADD UNIQUE KEY uk_alert_id (alert_id);
```

## 8. 团队协作部署（集中部署模式）

AI 分析依赖的 3 个服务（Qwen3.5 chat / bge-m3 embed / Chroma）**只在一台共享机器上运行**，
其他开发者**无需本机部署大模型**，只需把后端配置指向该共享机即可。

### 8.1 共享机（运行模型服务的一方）

1. 按第 3 节用 `start-all.bat` 起 chat(8081) / embed(8082) / chroma(8000)，确认三者均监听 `0.0.0.0`。
2. **防火墙放行入站**（管理员 PowerShell 执行一次）：
   ```powershell
   New-NetFirewallRule -DisplayName "ServerOps-LLM-8081" -Direction Inbound -Protocol TCP -LocalPort 8081 -Action Allow
   New-NetFirewallRule -DisplayName "ServerOps-LLM-8082" -Direction Inbound -Protocol TCP -LocalPort 8082 -Action Allow
   New-NetFirewallRule -DisplayName "ServerOps-Chroma-8000" -Direction Inbound -Protocol TCP -LocalPort 8000 -Action Allow
   ```
3. 查本机局域网 IP（用 IPv4，非 127. 开头、非 VMnet 虚拟网卡）：
   ```powershell
   ipconfig | findstr /i IPv4
   ```
   记下该 IP（下文称 `共享机IP`），例如 `192.168.31.182`。

### 8.2 其他开发者（只连共享机，不装模型）

二选一：

- **方式 A（profile）**：复制本文件旁的 `application-shared.yml`，把里面的 `192.168.31.182` 改成实际 `共享机IP`，
  启动后端时激活 profile：
  ```powershell
  # IDE: VM options 加 -Dspring.profiles.active=shared
  # 或命令行
  java -jar serverops.jar --spring.profiles.active=shared
  ```
- **方式 B（直接覆盖）**：在自己 `application.yml` 的 `ai.analysis.*` 三段把 `127.0.0.1` 改成 `共享机IP`。

### 8.3 连通性自检（从开发者机器）

```powershell
curl.exe http://共享机IP:8081/v1/models
curl.exe http://共享机IP:8082/v1/models
curl.exe http://共享机IP:8000/api/v2/heartbeat
```
三者均返回 200 即打通；之后 `analyze` 自动走共享机模型，本机零模型依赖。

> 注意：共享机需保持开机且 3 服务运行；多人并发分析会共享同一 GPU，Qwen3.5-9B 并发能力有限，
> 高并发场景需升级显存或改用云端部署（协议同为 OpenAI 兼容，仅改 `base-url` + `api-key` 即可）。
