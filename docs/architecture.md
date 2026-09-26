# 架构与升级路径

## 技术栈

| 范围 | 选型 | 原因 |
| --- | --- | --- |
| 前端 | React 19、TypeScript、Vite | 类型安全、快速迭代、可独立部署 |
| 后端 | Java 21、Spring Boot、Spring Security | 企业级分层、审计和权限扩展 |
| 数据 | PostgreSQL + pgvector | 事务数据与向量检索统一管理 |
| 文件 | MinIO | S3 兼容、便于私有化部署 |
| 任务 | Redis + Worker | 文档解析和千题生成可异步、可重试 |
| 模型 | DeepSeek V4 Flash / Vision Exp | 文本结构化生成与扫描件/图表理解分离 |

## 领域模块

```text
identity      用户、角色、配额、审计
knowledge     知识库、文件、解析、OCR、分块、向量检索
standard      职业标准 Schema、原文证据、确认版本
blueprint     细目表参数、规则分配、人工编辑、Excel 往返
question      九类题型、批处理、去重、评分、导出
job           队列、进度、重试、失败恢复
```

## DeepSeek 使用策略

- 文档文本、职业标准 Schema、细目表和题目生成：`deepseek-flash`，要求 JSON 输出。
- JPG/PNG/GIF/WebP、扫描页和表格截图：`deepseek-v4-flash-vision-exp`，以用户消息 `image_url` 内容块发送。
- 视觉文件不得作为 system/assistant 消息发送；单图和请求总大小由后端强制限制。
- API Key 只允许存在于 Windows 服务或 Docker 的环境变量中，永不发送到浏览器。

## 当前状态

已实现的基础：安全认证入口、模型网关、视觉上传入口、文件安全入库、Flyway 数据库迁移、生成任务 API、React 工作台、容器部署定义。

当前已完成用户/角色持久化、MinIO、逐页 PDF/Office/OCR、pgvector 混合检索、Redis Worker、标准与题库持久化及 Excel 往返。AI 命题采用证据化 JSON 批量生成并设置交付门禁；具体规则见 `docs/ai-quality-chain.md`。甲方样本到位后建立正式 OCR/抽取准确率测试集。
