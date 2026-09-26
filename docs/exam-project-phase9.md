# 命题项目第 9 阶段：审核后交付导出

## 本阶段已完成

- 新增项目导出任务和导出制品的持久化表（Flyway V43）。导出任务绑定项目、生成批次和命题依据快照，服务重启后可恢复排队或运行中的任务。
- 新增服务端审核门禁：提交导出和 Worker 实际执行前都会重新检查生成批次的题目数量、人工审核状态和驳回/失败数量；只有全部题目为 `APPROVED` 才能生成交付文件。
- 新增可选择的四类交付内容：
  - `PAPER_XLSX`：按变式卷分别成页，题型归组，难度按简单、中等、困难顺序排列，只包含试卷内容。
  - `ANSWER_XLSX`：答案、解析、评分细则、资料来源、题目版本和审核人。
  - `APPROVAL_XLSX`：审批栏、逐题审核记录，以及冻结时的资料版本清单。
  - `ATTACHMENTS_ZIP`：项目关联的任务书、样题、模板、工程图、三维模型和其他原始附件，同时写入 `manifest.json` 和资料清单。
- 新增项目级导出列表、创建、状态查询和制品下载 API。原有 `/api/generation/**` 题库导出接口未改动。
- 前端新增第 09 步“审核后交付”，支持勾选导出内容、查看异步任务状态、查看失败原因和下载各个制品。
- 下载、提交导出和审核动作均通过现有认证、项目隔离和操作审计链路。

## 接口

- `GET /api/exam-projects/{projectId}/variant-generation-runs/{runId}/exports`
- `POST /api/exam-projects/{projectId}/variant-generation-runs/{runId}/exports`
  - Body：`{"outputTypes":["PAPER_XLSX","ANSWER_XLSX","APPROVAL_XLSX","ATTACHMENTS_ZIP"]}`
- `GET /api/exam-projects/{projectId}/variant-generation-runs/{runId}/exports/{exportId}`
- `GET /api/exam-projects/{projectId}/variant-generation-runs/{runId}/exports/{exportId}/artifacts/{artifactId}`

## 验证结果

- Maven 测试：47 项通过。
- Flyway H2 测试迁移：43 个版本通过。
- 前端 `pnpm run build`：通过；保留既有 ObjPreview 大分包提示，不影响本阶段功能。
- 生产包重新构建并启动：通过，`/api/health` 返回 `UP`。
- 新导出列表和提交接口未认证访问：均返回 401。

## 交付边界

本阶段沿用当前系统的 Excel 交付版式，并将原始附件独立打包；Word/PDF 的可配置模板排版保留在后续阶段扩展，当前不会影响审核门禁、版本追溯和生产导出链路。
