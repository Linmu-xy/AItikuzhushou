-- 管理审计：语义化的管理动作 + 普通用户关键业务动作，管理员专属查询。
-- 与 operation_logs（请求级）互补：本表记录"谁对哪个对象做了什么、前后变化"。
create table if not exists admin_operation_log (
  id          uuid primary key,
  actor_id    uuid,
  actor_name  varchar(80) not null,
  action      varchar(50) not null,
  target_type varchar(40) not null,
  target_id   uuid,
  target_name varchar(160),
  detail      text,
  http_method varchar(8),
  path        varchar(255),
  status      integer not null default 200,
  request_id  varchar(64),
  created_at  timestamp with time zone not null
);
create index if not exists idx_admin_operation_created on admin_operation_log(created_at desc);
create index if not exists idx_admin_operation_actor on admin_operation_log(actor_name, created_at desc);
create index if not exists idx_admin_operation_target on admin_operation_log(target_type, target_id, created_at desc);

-- 通用请求审计补充请求追踪号，便于与日志文件 [requestId] 与前端 X-Request-Id 关联。
alter table operation_logs add column if not exists request_id varchar(64);
