# DeepSeek Pro 深度命题方案

## 1. 目标与边界

本方案把当前“根据职业标准批量生成题目”的流程升级为**深度命题模式**：以 `deepseek-flash` 完成命题设计和正式出题，使选择题的错误选项成为从业者在真实工作中可能做出的、但在特定条件下不成立的决策。

目标是接近高质量对话式命题的效果，而不是复刻 DeepSeek 网页版的内部实现。网页版所使用的模型路由、系统提示词、上下文处理方式均未公开，不能将其视为可依赖接口能力。

本节描述原有职业标准 Pro 命题边界，不抓取或复制未授权的往年真题。知识库项目的新建计划使用 `OPEN_ASSESSMENT_V1` 开放命题链路：知识库确定学科与素材，稳定的通用专业知识可用于设计答案，必需的联网事实按题定向检索并冻结，详见 `docs/ai-quality-chain.md` 和 `docs/assistant-web-search.md`。职业标准模式仍以已确认的标准为优先依据。

## 2. 当前问题和设计决策

当前实现已经具备职业标准证据、内部设计卡和独立审题能力；V3 继续解决深度不足、重复输出和单题串行调用问题：

1. 旧流程对每道题无条件执行完整设计、写题和修订，重复消耗推理 Token；
2. 普通模式只做结构性门禁，外行可猜中的浅题容易进入题库；
3. provider 的真实 prompt、completion 和 reasoning Token 以前没有持久化，无法定位高消耗阶段。

本方案的核心决策如下：

| 决策 | 说明 |
| --- | --- |
| 文本模型 | 全部文本调用统一改为 `deepseek-flash`；OCR/图片理解继续使用视觉专用模型。 |
| 深度命题模式 | `PROFESSIONAL_PRO` 按批生成紧凑设计卡和最终题目；独立审题只返回分数/失败码，失败题才修复。 |
| 正确性与真实性分层 | 职业标准是唯一答案依据；行业背景资料只用于补全岗位、对象、异常和协作关系，不得单独决定正确答案。 |
| 可审计而非保存思维链 | 保存结构化“命题设计卡”和来源证据，不保存或展示 `reasoning_content`。 |
| 不合格不交付 | 任一次候选题未通过门禁均不能兜底入库；只允许按限定次数修复或完整重建。 |

## 3. 总体流程

```text
职业标准 / 已审核背景资料 / 真题画像
                 │
                 ▼
           证据包组装与版本冻结
                 │
                 ▼
  阶段 A：Pro 命题设计（批量，完整证据，仅返回设计卡）
                 │  结构化命题设计卡
                 ▼
  阶段 B：Pro 正式出题（批量，仅返回最终五个字段）
                 │
                 ▼
  本地结构、证据、岗位情境、绝对化措辞、同维度选项检查
                 │
                 ▼
  独立审题：职业合理性 + 行业外可猜性
        │ 通过                         │ 不通过
        ▼                              ▼
     交付题库             仅修复失败题一次 → 失败留档
```

一个题库任务开始时先冻结“证据包版本”。同一任务中的所有题目均使用这份冻结版本，避免资料更新导致同一套试卷依据不一致。

## 4. 知识来源模型

### 4.1 资料类型

| 类型 | 用途 | 是否可作为答案依据 |
| --- | --- | --- |
| `STANDARD` | 国家职业技能标准、已确认评价规范、企业受控规范 | 是，且优先级最高 |
| `CONTEXT` | 已审核行业流程、设备说明、岗位协作、典型异常案例 | 否，仅补全场景与干扰项合理性 |
| `EXAM_PATTERN` | 用户上传或已获授权的真题结构、设问方式、难度标签 | 否，仅生成题型画像 |
| `TEMPORAL_CONTEXT` | 有发布日期、适用范围和失效时间的行业动态包 | 否，且仅在有效期内可用 |

所有资料都必须标记来源机构、上传者、版权/授权状态、适用职业、等级、地域、发布时间、生效时间、失效时间、内容哈希和审核状态。

### 4.2 不联网时的资料更新

管理员可上传 PDF、Word、网页导出文件或受控 Excel。解析完成后先进入“待审核资料区”；审核通过才可成为命题上下文。对具有时效性的资料设置 `valid_from`、`valid_until`，到期后自动不参与新任务。

真题只允许在授权或用户拥有使用权的前提下上传。系统提取“考点、题型、情境类型、常见误判机制、难度”画像，不向后续模型直接回传原题干、原选项、原答案或完整解析。

## 5. 模型与配置设计

### 5.1 模型路由

新增命题专用配置，不通过覆盖全局 `text-model` 的方式切换。

```yaml
app:
  ai:
    text-model: "${DEEPSEEK_TEXT_MODEL:deepseek-flash}"
    question-model: "${DEEPSEEK_QUESTION_MODEL:deepseek-flash}"
    question-pro-enabled: "${APP_AI_QUESTION_PRO_ENABLED:true}"
    question-pro-batch-size: "${APP_AI_QUESTION_PRO_BATCH_SIZE:3}"
    question-pro-context-max-chars: "${APP_AI_QUESTION_PRO_CONTEXT_MAX_CHARS:4500}"
    question-pro-design-effort: "${APP_AI_QUESTION_PRO_DESIGN_EFFORT:high}"
    question-pro-write-effort: "${APP_AI_QUESTION_PRO_WRITE_EFFORT:low}"
    question-pro-option-rewrite-effort: "${APP_AI_QUESTION_PRO_OPTION_REWRITE_EFFORT:low}"
```

`PROFESSIONAL_PRO` 任务必须使用 `question-model`。若该模型不可用，任务应明确失败或由用户重新选择普通模式；不得静默降级到 Flash 并把结果标识为深度命题。

DeepSeek 思考模式开启后，`temperature`、`top_p` 等采样参数不生效，因此该模式不以调温度为优化手段。以 `reasoning_effort=high` 作为默认；复杂案例、严重风险或多约束权衡使用 `max`。参考：[DeepSeek Thinking Mode](https://api-docs.deepseek.com/guides/thinking_mode/)。

### 5.2 后台模型配置

新增迁移 `V30__question_model_configuration.sql`：

```sql
alter table model_provider_configs
  add column if not exists question_model varchar(128);
```

`ModelConfigurationService.Settings` 新增 `questionModel`，管理 API 可单独编辑、校验和展示它。已有行为空时回退到 `app.ai.question-model`。`DeepSeekService` 新增 `analyseQuestionJson(...)`，只在命题链路读取该字段。

普通文本、职业审查和 FAST 写题使用 `text-model`；EXPERT 的设计卡和写题使用 `question-model`，两条路由都保留可观测的真实 Token 数据。

## 6. 命题实现

### 6.1 任务模式

扩展 `generationMode`：

| 模式 | 模型与调用 | 适用场景 |
| --- | --- | --- |
| `FAST` | 批量、低思考 + 独立职业审查 | 普通正式题库 |
| `BALANCED` / `TIERED` | 归一化为 FAST，低思考批量成题 + 独立审查 | 一般正式题库 |
| `PROFESSIONAL_PRO` | 每批最多 3 题，设计卡 → 写题 → 紧凑审查 | 对外考试、重点职业、选择题质量专项 |

创建任务时记录 `questionQualityVersion=EXPERT_V3` 或 `FAST_EXAM_V3`、模型名、资料快照 ID、设计/写作思考档位和生效配置。历史任务不受新规则追溯影响。

### 6.2 阶段 A：命题设计

输入包含：题型、等级、认知目标、职业标准原文片段、相邻工序证据、最多三条已审核背景资料摘要、真题画像标签及题位约束。

模型按批输出如下结构化设计卡：

```json
{
  "taskContext": "岗位、对象、任务目标与异常条件",
  "competencyAction": "需要执行的专业动作",
  "assessmentDecision": "需要作出的职业判断",
  "conditions": ["会改变答案的必要条件"],
  "correctBasis": ["标准证据 ID"],
  "distractorMechanisms": ["条件遗漏", "控制点错位"],
  "optionDesign": {"A": {"conditionFit": "", "decisionBasis": ""}}
}
```

选择题必须有三个错误设计卡，且机制在“条件遗漏、时序颠倒、角色错配、控制点错位、不当泛化”中不重复。设计卡不接受“因为不符合规范”“绝不能这样做”等空泛理由。

阶段 A 的模型输出不是思维链；它是可审计、可保存、可供下一阶段使用的业务设计数据。

### 6.3 阶段 B：正式出题

输入是通过本地校验的设计卡、紧凑标准证据和输出格式。每次批量生成多个题位，只输出题干、四个选项、答案、解析和评分规则；选项设计映射保存在内部设计卡。

要求：

1. 题干含岗位、对象、任务目标与足够作答条件；
2. 四个选项回答同一个决策问题，长度、专业词密度和语气接近；
3. 正确项必须同时满足设计卡前提和职业标准证据；
4. 错误项必须是设计卡对应的“看似可行但在当前条件下错误”的职业行为；
5. 禁止用绝对化词汇、明显越权、无关行为或口语荒谬性作为错误信号；
6. 解析只说明关键条件、依据和处置后果，限制在简短可读范围内。

继续使用 JSON 输出，避免落库时出现非结构化文本。DeepSeek API 支持 JSON 输出，但系统提示词仍要显式声明 JSON 并给出字段示例。参考：[DeepSeek JSON Output](https://api-docs.deepseek.com/guides/json_mode/)。

### 6.4 上下文组装

深度模式不再仅传 `sourceExcerpt` 前 1000 字。由 `QuestionContextAssetService` 组装并冻结证据包，按以下优先级去重：

1. 当前考点的直接标准证据；
2. 同一职业功能下的相邻步骤、岗位边界和核验要求；
3. 已审核且未过期的背景资料摘要；
4. 已授权真题画像标签。

包内每段都保留 `sourceId`、类型和可信等级。`STANDARD` 证据不足时直接拒绝生成；`CONTEXT` 缺失时仍可生成，只是不额外虚构行业细节。

## 7. 校验、审题和修复

### 7.1 本地硬门禁

保留并扩展既有 `QuestionRefinementService` 校验：

- 答案、题型、选项数量和选项同维度；
- 正确项能映射到 `STANDARD` 证据；
- 禁止显眼绝对化词、明显长度差、唯一专业名词堆砌和正确项独有的标准原文短语；
- 三个错误项都必须含有机制、违反条件和合理性描述；
- 题干与已有题干去重，并检查相同考点的情境、误判机制和正确动作不能重复。

### 7.2 双视角独立审题

现有 `QuestionProfessionalReviewService` 拆分为两个逻辑评分：

| 审题视角 | 允许使用的资料 | 判定目标 |
| --- | --- | --- |
| 职业合理性审题 | 标准 + 已审核背景资料 | 错误项是否是从业者在该条件下可能犯的错误；正确项是否确有证据支持 |
| 行业外可猜性审题 | 题面和公开的标准证据 | 非从业者是否可凭语气、绝对词、长度或常识排除错误项 |

职业合理性审题不允许让背景资料推翻标准答案。任一视角未通过时都记录独立标记和反馈。

### 7.3 修复策略

`PROFESSIONAL_PRO` 的顺序固定为：

1. 初次阶段 A + 阶段 B；
2. 仅因选项问题失败时，使用低思考定向重写选项，锁定题干、答案、解析和评分规则；复杂失败题才升级思考强度；
3. 题干、证据或答案问题失败时，定向重建该题的设计卡和题目；
4. 默认最多一次完整修复，仍失败则题位失败留档，不交付替代的未通过候选题。

审核服务异常单独归类为 `REVIEW_SERVICE_ERROR`。此类故障只重试审核调用；候选题保留在 `REVIEW_PENDING` 题位，用户显式重试时优先执行审核专用流程，避免因基础设施异常再次支付设计和写题费用。

失败题的选项修复和完整修复次数按任务快照保存，便于审计和复现；通过题不再执行无条件完整重写。

## 8. 数据库、接口与前端

### 8.1 迁移

| 迁移 | 变更 |
| --- | --- |
| `V29__default_text_model_to_deepseek_v4_pro.sql` | 将已有托管文本模型从 Flash 升级为 Pro |
| `V30__question_model_configuration.sql` | `model_provider_configs.question_model` |
| `V31__question_context_assets.sql` | 背景资料/真题画像的来源、标签、有效期、哈希、审核状态，以及题库任务冻结的资料快照和来源映射 |
| `V32__question_generation_designs.sql` | 阶段 A 命题设计卡、版本、模型、耗时与校验结果 |
| `V34__question_style_memories.sql` | 题组风格记忆，降低重复考法 |
| `V35__model_usage_tokens.sql` | provider 报告的 prompt/completion/reasoning Token、调用事件和耗时 |
| `V36__model_usage_operation.sql` | 为调用事件标记命题设计、写题、审查、修复和 OCR 等操作 |

数据迁移只能新增字段和表；历史题库、历史审题记录、现有 `question_professional_audits` 必须保持可读。

### 8.2 API

新增或扩展以下接口：

- `POST /api/knowledge-bases/{id}/context-assets`：上传背景资料、真题画像材料；
- `PATCH /api/context-assets/{id}`：编辑职业/等级/资料类型/有效期；
- `POST /api/context-assets/{id}/approve`：审核并发布；
- `GET /api/context-assets?knowledgeBaseId=...`：查看资料状态、失效时间和来源；
- 题库提交接口增加 `generationMode=PROFESSIONAL_PRO`；
- `GET /api/quality/jobs/{jobId}/evidence-snapshot`：查看任务冻结的资料版本和来源；
- `GET /api/quality/jobs/{jobId}/generation-designs`：仅授权用户查看内部设计卡和失败原因。

考生题目接口、Excel 导出和普通用户题目详情不得返回内部设计卡、资料版权字段、模型思考内容或未发布资料。

### 8.3 前端

在“题库任务”中保留“深度命题（Pro）”选项，提示批量岗位判断设计和独立职业审查。模型额度页展示 prompt、completion、reasoning Token，以及按模型/思考强度的消耗排行。

## 9. 可观测性与验收

每个题位记录：模型、配置版本、证据快照、阶段 A/B 耗时、输入/输出 token（若提供）、修复次数、审题得分、行业外可解性、失败标记和最终结果。日志中不得写入 API Key、完整受版权保护的真题或模型思维链。

验收使用一组经专家标注的“金标考点集”，至少覆盖单选、多选、判断、简答和案例题，比较普通模式与深度命题模式：

- 职业专家盲审通过率；
- “错误项一眼可排除”比例；
- 行业外答题者的偶然正确率；
- 标准证据可追溯率；
- 平均生成耗时、失败率和单题模型消耗；
- 同考点题目之间的场景、正确动作和干扰机制重复率。

上线门槛由业务方根据金标集基线确定；未达到门槛时保持 `PROFESSIONAL_PRO` 不可默认选中，不以主观观感替代验收数据。

## 10. 发布与回滚

1. 先备份生产数据库，再执行全部待应用的 Flyway 迁移（包括 V35、V36）；
2. 部署代码后关闭深度模式入口，仅对内部样本生成并人工盲审；
3. 以 `APP_AI_OPTION_REVIEW_MODE=SHADOW` 收集选项问题数据；
4. 通过金标验收后，将深度模式开放给指定知识库/职业；
5. 再将选项门禁切换为 `ENFORCE`，并逐步扩大范围；
6. 出现模型不可用、质量显著下降或成本异常时，关闭 `APP_AI_QUESTION_PRO_ENABLED`，新任务回到原有模式；已生成任务继续保留快照与审计记录。

回滚不删除资料、快照和审计数据；仅停止创建深度命题任务。数据库迁移保持向前兼容。

## 11. 实施顺序

1. 增加命题专用模型配置和 `DeepSeekService.analyseQuestionJson`；
2. 增加 `EXPERT`（兼容 `PROFESSIONAL_PRO`）模式、任务配置快照和批量设计/写题调度；
3. 实现 `QuestionEvidencePackBuilder` 和资料有效期过滤；
4. 实现阶段 A 命题设计卡及阶段 B 正式出题；
5. 扩展双视角审题、Pro 选项重写和完整重建；
6. 完成 V29–V36、资料审核 API、前端入口与审计页；
7. 编写单元、集成、迁移、权限、失败恢复和金标回归测试；
8. 按发布步骤灰度，确认指标后全量启用。

## 12. 主要风险与控制

| 风险 | 控制措施 |
| --- | --- |
| Pro 的延迟和成本上升 | 批量设计与写题、低思考写题、失败题定向修复、额度预留和真实 Token 监控 |
| 背景资料污染正确答案 | 来源分级，`STANDARD` 才能支持答案，背景资料只用于情境 |
| 真题版权/保密风险 | 仅上传授权材料；提取画像而非回传原题 |
| 资料过期 | 有效期过滤、到期提醒、任务快照冻结 |
| 提示词注入 | 将资料作为不可信数据包，禁止资料内容改变系统规则或调用权限 |
| Pro 不可用 | 显式失败或用户选择普通模式，禁止静默降级后冒充深度模式 |
