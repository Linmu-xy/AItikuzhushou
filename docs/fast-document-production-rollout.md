# 快速文档建库生产上线说明

本功能是现有知识库、题库任务和项目命题流程之外的增量能力。默认关闭，开启后也只通过新增入口运行，不改变 `FAST`、V2、Pro 和原有 Excel 导入导出接口。

## 上线顺序

1. 发布后端和前端代码，让 Flyway 自动执行 `V49__fast_document_generation.sql`。
2. 先保持以下开关关闭，检查 `/api/health`、数据库迁移和原有题库任务。

```dotenv
APP_FAST_DOCUMENT_ENABLED=false
APP_QUESTION_NORMALIZATION_ENABLED=false
```

3. 在测试环境打开开关，用已解析的小文档验证“提交 → 后台生成 → 人工审核 → 导入题库 → 原有审核/导出”。
4. 生产灰度时只打开需要的能力，并观察任务失败率、模型额度、生成耗时和人工驳回率。

```dotenv
APP_FAST_DOCUMENT_ENABLED=true
APP_QUESTION_NORMALIZATION_ENABLED=true
APP_FAST_DOCUMENT_MAX_CHARACTERS=200000
APP_FAST_DOCUMENT_SEGMENT_CHARACTERS=1000
APP_FAST_DOCUMENT_MAX_QUESTIONS=2500
APP_FAST_DOCUMENT_MAX_SEGMENTS=400
```

## 新增接口

- `POST /api/fast-document/jobs`：提交已解析文档的快速建库任务。
- `GET /api/fast-document/jobs/{id}`：查询进度和状态。
- `GET /api/fast-document/jobs/{id}/items`：查看题目、证据和基础门禁结果。
- `POST /api/fast-document/jobs/{id}/retry`、`/cancel`：重试或取消。
- `POST /api/fast-document/jobs/{id}/import`：将通过基础门禁的题目导入原题库，后续仍走原审核和导出链路。
- `POST /api/question-normalization/jobs`：上传 Word/Excel 题库并整理为现有字段。
- `GET /api/question-normalization/jobs/{id}/items`：查看整理结果和错误项。
- `POST /api/question-normalization/jobs/{id}/confirm`：只导入通过校验的题目。

## 运行和回滚

- 快速建库任务落在独立表中，后台 worker 会在进程重启后恢复排队/运行中的任务，并对同一进程内重复提交做去重。
- 关闭开关不会删除任务或原题库数据；只会阻止新的增量任务提交和恢复。需要回滚功能时先将两个开关设为 `false`，无需回滚数据库迁移。
- `V49` 只新增表和索引，不修改原有表结构。代码回滚后保留这些空表不会影响旧版本；再次发布新版本可继续复用。
- 生成结果默认进入 `READY_FOR_REVIEW` 或 `PARTIAL_SUCCESS`，不会自动作为正式交付题库，避免未经人工确认的题目污染原题库。

## 生产门禁

- 文档必须先完成原有解析，快速出题只使用解析后的段落和来源定位。
- 单次字符数、段数和题数均受配置限制；超过限制应拆分文档后提交。
- Word/Excel 整理使用现有题目结构校验，错误项保留在整理任务中供修正，不会静默导入。
- 权限按当前用户和知识库隔离，管理操作写入审计日志。
