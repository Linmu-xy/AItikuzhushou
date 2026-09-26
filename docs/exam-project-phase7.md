# 命题项目第 7 阶段：AI 变式题目生成

## 本阶段交付

第 6 阶段的题位计划现在可以提交为一个独立的项目生成运行。运行会绑定：

- 题位计划 ID；
- 命题依据快照 ID、版本和指纹；
- 生成模式（默认 `PROFESSIONAL_PRO`，也支持显式选择 `FAST`）；
- 每个题位的题型、难度、分值、题目、评分细则、资料来源和审题结果。

生成流程复用现有 `QuestionRefinementService` 和 `QuestionProfessionalReviewService`，不复制模型调用与质量规则：

1. 从冻结快照读取任务书、职业标准、样题/模板摘要和已确认模型/工程图事实；
2. 按现有 Pro 批大小逐批生成；
3. 单批未通过时对题位做一次有边界的完整重写；
4. 对候选题执行独立职业审题；
5. 通过 AI 门禁的结果标记为 `REVIEW_REQUIRED`，等待教师人工审核；
6. 模型异常标记为 `REVIEW_PENDING`，不会静默改用模板题或绕过质量门禁。

## 接口

- `POST /api/exam-projects/{projectId}/variant-generation-tasks/{taskId}/runs`：启动一次生成运行；默认请求体为 `{"generationMode":"PROFESSIONAL_PRO"}`；
- `GET /api/exam-projects/{projectId}/variant-generation-runs`：查看运行历史；
- `GET /api/exam-projects/{projectId}/variant-generation-runs/{runId}`：查询进度、生成题目和资料来源。

运行由数据库持久化，服务重启时会将中断的 `RUNNING` 运行恢复为 `QUEUED`。同一项目同时只能有一个排队或运行中的生成任务。

## 人工审核边界

当前阶段只生成并展示题目、答案、解析、评分细则和来源记录，不提供导出和自动通过。只有后续人工审核阶段将题位改为 `APPROVED` 后，才允许进入试卷、答案、审批表和附件导出链路。

## 下一阶段入口

下一阶段实现教师逐题审核：编辑题干、选项、答案和评分细则，查看来源与 AI 审题反馈，记录审核人和版本；审核未完成或被拒绝的题目不得导出。
