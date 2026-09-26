# Windows PostgreSQL 17 安装 pgvector

本机已于 2026-08-29 以 PostgreSQL 17 验证 pgvector 0.8.6：`vector` 扩展、向量列和 HNSW 索引均已实际启用。下面的步骤仍保留，用于新服务器复现安装。

本项目使用 PostgreSQL 17 x64。pgvector 必须与 PostgreSQL 主版本、编译器 ABI 和 x64 架构匹配。未安装扩展时，应用会继续使用 JSON embedding 回退；安装完成并重启后端后会自动创建 `embedding_vector vector(128)` 与 HNSW 余弦索引。

## 前置检查

1. 使用管理员 PowerShell 确认 PostgreSQL 服务已停止或处于维护窗口。
2. 安装 Visual Studio Build Tools 的“Desktop development with C++”工作负载，确保可使用 x64 `cl.exe` 和 `nmake.exe`。
3. 从官方 [pgvector 仓库](https://github.com/pgvector/pgvector) 下载与审核后的源码版本；不要使用来源不明的 DLL。
4. 确认以下目录存在：

```powershell
Test-Path 'C:\Program Files\PostgreSQL\17\include\server\postgres.h'
Test-Path 'C:\Program Files\PostgreSQL\17\lib\postgres.lib'
```

## 编译与安装

在“x64 Native Tools Command Prompt for VS”中执行。将 `<source>` 替换为已审计的 pgvector 源码目录：

```bat
set "PGROOT=C:\Program Files\PostgreSQL\17"
cd /d <source>
nmake /F Makefile.win
nmake /F Makefile.win install
```

安装会向 PostgreSQL 的 `lib` 与 `share\extension` 写入文件，因此必须在维护窗口内以管理员身份执行。随后启动 PostgreSQL 服务并启用扩展：

```sql
CREATE EXTENSION IF NOT EXISTS vector;
SELECT extname, extversion FROM pg_extension WHERE extname = 'vector';
```

## 应用验收与回退

1. 重启题库助手后端。
2. 上传或重新解析一份文档。
3. 验证：

```sql
\d document_chunks
\di idx_document_chunks_vector_hnsw
```

4. 通过 `/api/retrieval/search` 检索已解析文档并确认有来源片段。

若扩展加载失败，停止后端，移除本次复制的 `vector.dll`、`vector.control` 和对应 SQL 文件后重启 PostgreSQL；应用会保留业务数据并自动退回 JSON embedding 路径。
