# Windows 原生部署

本项目不使用 Docker。开发模式使用内置 H2 与本地文件目录；生产模式使用 Windows 原生 PostgreSQL、MinIO 与 Redis。应用以 PostgreSQL 业务记录为恢复依据，Redis 仅承载待执行任务标识。

## 前置条件

- JDK 21，Node.js 20+，PostgreSQL 17，MinIO 与 Redis 8.10.1 for Windows。
- pgvector 需要与 PostgreSQL 主版本和 x64 架构一致。官方 pgvector 提供 Windows 源码构建说明；本机 PostgreSQL 安装包不含该扩展时，系统会安全退回 JSON embedding 检索，不会阻断解析或题库生成。详见 [pgvector Windows 安装手册](pgvector-windows.md)。
- MinIO 与 Redis 在生产服务器应注册为受限服务账号运行；首次安装、复制 pgvector 扩展文件和注册服务通常需要管理员权限。

## 配置

将 `.env.example` 的变量设置为当前 PowerShell 会话或服务器的系统环境变量，至少设置：`DEEPSEEK_API_KEY`、`APP_CONFIG_ENCRYPTION_KEY`、`APP_BOOTSTRAP_ADMIN_PASSWORD`、`JDBC_DATABASE_URL`、`POSTGRES_USER`、`POSTGRES_PASSWORD`、`MINIO_ROOT_USER`、`MINIO_ROOT_PASSWORD`。不要把密钥写入前端构建目录或提交到仓库。`APP_CORS_ORIGINS` 必须填写用户实际访问前端的完整来源（协议、域名或 IP、端口），多个来源以英文逗号分隔；例如本机同时允许 `http://127.0.0.1:4173,http://localhost:4173`。

### 模型密钥加密主密钥（APP_CONFIG_ENCRYPTION_KEY）——生产必读

- **必须固定设置**：`APP_CONFIG_ENCRYPTION_KEY` 用于加密“管理端保存的 DeepSeek API Key”，生产首次启动前应生成一个随机值并**固定不再改变**，长度 ≥16 字符（建议 64 位十六进制）。生成示例（PowerShell）：
  ```powershell
  $b = New-Object byte[] 32; [System.Security.Cryptography.RNGCryptoServiceProvider]::new().GetBytes($b)
  -join ($b | ForEach-Object { $_.ToString('x2') })
  ```
- **启动脚本强制校验**：`Start-Tiku.ps1` 在生产启动前检查该变量存在且 ≥16 字符，缺失会直接报错并提示。
- **更换或丢失的后果**：已托管的模型密钥将无法解密，模型配置接口返回 `CONFIG_ENCRYPTION_KEY_MISMATCH`，健康面板仍可能显示“已配置”（它只检查行存在）。恢复方式：找回原加密密钥并重启；若确实丢失，需在管理端“模型与密钥管理”重新粘贴并保存一次 DeepSeek Key（会以新密钥重新加密）。
- **切勿**用 `APP_BOOTSTRAP_ADMIN_PASSWORD` 或 `DEEPSEEK_API_KEY` 充当加密密钥：本系统已不再支持此类兜底，环境变量变动会永久锁死已托管密钥。
- 加密密钥属于敏感配置：随 `.env`/密钥管理安全备份，不入库、不入前端、不入交付包。

生产数据库默认地址为 `jdbc:postgresql://localhost:5432/tikuzhushou`。创建数据库后，以 `SPRING_PROFILES_ACTIVE=prod` 启动后端；Flyway 会运行既有业务迁移。检测到 pgvector 后，应用会创建向量列与 HNSW 余弦索引。首次升级前请先执行 `pg_dump` 备份。

## 启动与验证

1. 在当前 PowerShell 设置上述环境变量，执行 `./scripts/Start-Tiku.ps1 -Build`。脚本会检查 PostgreSQL，启动 MinIO、Redis、后端和前端。
2. 执行 `./scripts/Test-Tiku.ps1`，应看到 PostgreSQL、MinIO、Redis、后端和前端均为 `True`。
3. 访问 `http://127.0.0.1:4173`，使用管理员账号登录，创建知识库，上传职业标准 PDF，解析、确认标准、生成细目表和题库，然后导出 Excel。
4. 服务器正式发布时，将 `frontend/dist` 部署到 IIS 或 Nginx，并将 `/api` 反向代理至后端端口；将 MinIO、Redis 与后端注册为 Windows 服务。

### 职业区分度命题门禁

新建题库默认启用 `APP_AI_PROFESSIONAL_REVIEW_ENABLED=true`：生成器会先输出不面向考生的岗位情境设计卡，独立审题模型再检查原文复述、常识可解、选择项过弱、答案/评分点空泛、证据不足等问题。选择题还会逐项检查是否存在“无需、随意、直接跳过”等行业外人员一眼可排除的错误项，并要求每个干扰项对应真实的条件遗漏、时序颠倒或控制点错位。未通过的题不会经“择优”直接入库：先仅重写四个选项，仍失败时再进行一次高质量完整重构。默认阈值为 `APP_AI_PROFESSIONAL_REVIEW_THRESHOLD=75`、行业外可解性上限为 `APP_AI_PROFESSIONAL_OUTSIDER_SOLVABLE_THRESHOLD=35`；`APP_AI_OPTION_REVIEW_MODE=ENFORCE` 会正式拦截，首次灰度可改为 `SHADOW` 仅记录审计。默认执行一次审核重试、一次选项重写和一次完整修复（均不含首次生成），可通过 `APP_AI_PROFESSIONAL_REVIEW_RETRY_ATTEMPTS`、`APP_AI_OPTION_REWRITE_MAX_ATTEMPTS`、`APP_AI_QUESTION_FULL_REWRITE_MAX_ATTEMPTS` 调整。审核服务异常只保留候选题并标记 `REVIEW_PENDING`，不会触发重新出题。

质量页的 `GET /api/quality/jobs/{jobId}/professional-audits` 可查看每道题的审题分、失败标记、重试反馈与内部设计卡；`GET /api/quality/jobs/{jobId}/professional-audits/summary` 汇总通过数、选项问题数、行业外可解性超阈值数及平均分，用于灰度验收和运营复盘。题库导出和考生题目接口不会包含设计卡。此门禁按任务记录启用状态，升级前的历史题库不会被追溯判定失败。

### DeepSeek Pro 深度命题

深度命题使用 `generationMode=EXPERT`（兼容旧值 `PROFESSIONAL_PRO`）：按批生成岗位判断设计卡，再按批生成最终题目；默认每批 3 题，设计阶段使用高思考，写题阶段使用低思考。FAST 也会经过独立职业审题器，只有失败题进入定向修复。它不会在模型不可用时静默降级为普通模式。新增配置项如下，可在灰度期将 `APP_AI_QUESTION_PRO_ENABLED` 设为 `false`，此时专家模式不可提交：

```text
DEEPSEEK_QUESTION_MODEL=deepseek-flash
APP_AI_QUESTION_PRO_ENABLED=true
APP_AI_QUESTION_PRO_BATCH_SIZE=3
APP_AI_QUESTION_PRO_CONTEXT_MAX_CHARS=4500
APP_AI_QUESTION_PRO_DESIGN_EFFORT=high
APP_AI_QUESTION_PRO_WRITE_EFFORT=low
APP_AI_QUESTION_PRO_OPTION_REWRITE_EFFORT=low
APP_AI_PROFESSIONAL_REVIEW_RETRY_ATTEMPTS=1
```

升级时先执行数据库备份；Flyway 会继续执行 V29–V32 的既有迁移与 V33（将托管的文本、命题模型切换为 Flash）。背景资料必须由管理员审核为 `APPROVED` 且未过有效期，才会进入新的深度命题任务；职业标准仍是唯一的正确答案依据。可使用以下受权限保护的接口复核而不暴露模型思维链：`GET /api/quality/jobs/{jobId}/evidence-snapshot` 与 `GET /api/quality/jobs/{jobId}/generation-designs`。普通题目接口和 Excel 导出不会返回内部设计卡或未发布资料。

## 日常运维

- 每日备份 PostgreSQL；同时备份 MinIO 数据目录和应用 `.env` 的安全副本。
- 任务中心可查看进度、失败信息、重试与取消；应用重启后会重新提交排队/执行中的任务。
- 操作日志保留 30 天，知识库默认单库配额为 10GB，模型调用按用户的每日额度预留。
- 应用运行日志由 Logback 写入 `logs/` 目录（`Start-Tiku.ps1` 会自动创建并设置 `LOG_PATH`，可用环境变量覆盖）：
  - `logs/tiku-api.log`：滚动业务日志（单文件 50MB、保留 30 天、压缩归档）；
  - `logs/tiku-api-error.log`：仅 WARN/ERROR，用于快速定位异常；
  - `logs/tiku-api.out.log` / `tiku-api.err.log`：进程 stdout/stderr 兜底捕获；
  - `logs/frontend.out.log` / `frontend.err.log`：前端 Vite 进程输出。
  - 所有 API 日志行包含 `[requestId]` 追踪号，与响应头 `X-Request-Id` 对应，便于把前端报错与后端日志关联。数据库 `operation_logs` 表保留 30 天请求审计，可作为请求轨迹的时间线补充。
- 发生检索异常时，检查 pgvector 扩展、`document_chunks.embedding_vector` 与 HNSW 索引；扩展缺失时系统使用 JSON embedding 回退，并在安装扩展后自动切换。
