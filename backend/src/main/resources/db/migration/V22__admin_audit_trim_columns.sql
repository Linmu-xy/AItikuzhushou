-- AdminAuditService.record() 实际只写入 9 列；移除 V21 中从未使用的预留列，
-- 避免"死列"误导后续维护者以为有 http 语义/操作者 UUID 审计。
alter table admin_operation_log drop column if exists actor_id;
alter table admin_operation_log drop column if exists http_method;
alter table admin_operation_log drop column if exists path;
alter table admin_operation_log drop column if exists status;
