# 命题项目第 8 阶段：人工审核与版本留痕

## 本阶段目标

把变式题目从“AI 生成完成”推进到“教师逐题确认”。AI 审题通过只代表题目进入人工审核队列，不代表可以导出。

## 已实现功能

- 项目题目独立审核链路，不改动旧题库 `question_reviews` 流程。
- 教师可编辑题干、选项、答案、解析和评分细则。
- 题型、分值、资料来源、来源片段、证据快照和模板版本不可通过编辑操作删除或篡改。
- 支持保存修改、审核通过、驳回、重新打开已通过题目。
- 使用 `expectedVersion` 做乐观锁，避免多人同时编辑时互相覆盖。
- 每次教师编辑生成新的题目版本；审核状态变化写入独立操作事件。
- 页面可查看题目版本、变更说明、操作者、时间和状态流转。
- 新增导出前置检查：只有全部题目为 `APPROVED` 且没有失败/驳回题目时才返回可导出。

## 审核状态

```text
REVIEW_REQUIRED / REJECTED
        │ 教师编辑、保存、审核
        ├──────────────► REVIEW_REQUIRED
        ├──────────────► REJECTED
        └──────────────► APPROVED ──重新打开──► REVIEW_REQUIRED
```

`REVIEW_PENDING`、`FAILED`、`PLANNED` 和 `GENERATING` 不能直接进入教师审核，必须先由生成链路完成或重试。

## 新增接口

```text
PATCH /api/exam-projects/{projectId}/variant-generation-runs/{runId}/items/{itemId}/review
GET   /api/exam-projects/{projectId}/variant-generation-runs/{runId}/items/{itemId}/versions
GET   /api/exam-projects/{projectId}/variant-generation-runs/{runId}/items/{itemId}/review-events
GET   /api/exam-projects/{projectId}/variant-generation-runs/{runId}/export-readiness
```

审核请求示例：

```json
{
  "decision": "APPROVE",
  "expectedVersion": 1,
  "comment": "已核对答案和评分细则",
  "question": {
    "stem": "修改后的题干",
    "options": "A. … | B. … | C. … | D. …",
    "answer": "A",
    "analysis": "修改后的解析",
    "scoringRubric": "答对得 2 分；答错 0 分。"
  }
}
```

## 数据与生产边界

V42 新增题目版本表和审核事件表，并为生成题目增加当前版本、审核人、审核意见和审核时间。历史生成结果首次进入审核记录时会补建 AI 初始版本，不会丢失既有结果。

当前阶段没有开放导出按钮，也没有把未审核题目接入任何导出流程；后续导出阶段必须调用 `export-readiness` 作为服务端门禁，不能只依赖前端按钮状态。

## 验证

- 后端集成测试：47 项通过。
- Flyway：H2 与生产配置均迁移至 V42。
- 前端 TypeScript/Vite 构建通过。
- Windows 启动脚本：PostgreSQL、MinIO、Redis、后端、前端健康检查全部通过。
- 未登录访问审核和导出检查接口返回 401。

## 下一阶段

第 9 阶段可在本审核闭环之上开发试卷、答案、审批表、图纸/附件压缩包的导出编排，并强制复用服务端审核门禁和快照版本。
