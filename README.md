# 题库助手

面向职业技能等级认定的 AI 题库系统，使用 React + TypeScript 与 Spring Boot 3 / Java 21 构建。

## 当前运行模式

- 开发：H2 本地数据库、本地文件存储、本地异步任务，可直接在 Windows 运行。
- 生产：原生 PostgreSQL + pgvector、MinIO、Redis 兼容队列；不使用 Docker。

生产部署步骤、环境变量、备份恢复与故障排查见 [Windows 部署手册](docs/windows-deployment.md)。不要将模型密钥、数据库密码或管理员密码提交到仓库。

## 本地开发

1. 设置 `APP_BOOTSTRAP_ADMIN_PASSWORD` 环境变量。
2. 在 `backend` 执行 `mvn spring-boot:run`。
3. 在 `frontend` 执行 `npm install` 与 `npm run dev`。
4. 打开前端地址并使用管理员账号登录。

系统支持职业标准、教材等 PDF/Word/PPT/图片资料上传，随后进行逐页解析/OCR、片段编辑、证据化标准抽取、人工确认、细目表、DeepSeek AI 命题、质量门禁与 Excel 导出。规则层只分配题型、难度和考点，最终题干、选项、答案与解析由模型依据职业标准和 RAG 原文生成。

交付工作台包含逐题人工审核与版本留痕、导出前风险预检、Excel 校验后导入、动态预计完成时间、文档解析质量提示、响应式布局，以及仅管理员可见的 PostgreSQL/pgvector、Redis、MinIO、DeepSeek 生产健康面板。普通用户仍只可访问本人数据。

命题与质量链路详见 [`docs/ai-quality-chain.md`](docs/ai-quality-chain.md)。
本机验收证据见 [`docs/local-acceptance-report.md`](docs/local-acceptance-report.md)。
