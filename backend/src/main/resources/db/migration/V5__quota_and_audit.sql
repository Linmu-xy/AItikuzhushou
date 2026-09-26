alter table knowledge_bases add column if not exists storage_quota_bytes bigint not null default 10737418240;
create table if not exists operation_logs (id uuid primary key, actor varchar(120) not null, method varchar(12) not null, path varchar(500) not null, status integer not null, elapsed_ms bigint not null, remote_ip varchar(80), created_at timestamp with time zone not null);
create index if not exists idx_operation_logs_created on operation_logs(created_at);
create index if not exists idx_operation_logs_actor_created on operation_logs(actor, created_at);
