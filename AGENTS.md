# ServerOps 协作指南

## 项目结构

- `backend/`：Java 17、Spring Boot 3 后端，使用 Maven 构建。
- `frontend/`：Vue 3、Vite 前端，使用 npm 管理依赖。
- `client/`：Python 监控客户端，负责采集主机指标并上报。

## 开发约定

- 修改前先阅读所在模块的现有代码，保持命名、分层和格式一致。
- 改动应聚焦当前需求，不提交生成文件、依赖目录、日志、密钥或本机配置。
- API、配置项或数据结构发生变化时，同步更新调用方、示例配置和必要文档。
- 密钥、Token、数据库口令和服务器地址只能通过环境变量或未跟踪的本地配置提供。
- 新逻辑应包含与风险相匹配的测试；提交前至少运行受影响模块的检查。

## 常用命令

- 后端测试：`cd backend && mvn test`
- 后端运行：`cd backend && mvn spring-boot:run`
- 前端安装：`cd frontend && npm ci`
- 前端开发：`cd frontend && npm run dev`
- 前端构建：`cd frontend && npm run build`
- Python 测试：`cd client && python -m pytest`

## Python 客户端约定

- 支持的最低 Python 版本、依赖及启动方式应记录在 `client/README.md`。
- 将指标采集、上报传输、本地配置、缓存重试和 UI 分成独立模块，避免 UI 线程执行阻塞任务。
- 指标统一携带主机标识、采集时间和客户端版本；单位和字段名必须稳定且有文档说明。
- 网络失败时采用有限指数退避并保留有上限的本地队列，不因上报失败中断采集。
- 对系统采集代码使用可替换接口，测试中不得依赖真实服务器或特定机器负载。
