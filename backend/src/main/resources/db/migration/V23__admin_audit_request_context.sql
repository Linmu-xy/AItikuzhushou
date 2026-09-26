-- V22 removed unused request-context columns. They are now populated by the
-- consolidated audit centre, so restore them in a forward-only migration.
alter table admin_operation_log add column if not exists actor_id uuid;
alter table admin_operation_log add column if not exists http_method varchar(8);
alter table admin_operation_log add column if not exists path varchar(255);
alter table admin_operation_log add column if not exists status integer not null default 200;
alter table admin_operation_log add column if not exists category varchar(32) not null default 'BUSINESS';
alter table admin_operation_log add column if not exists outcome varchar(24) not null default 'SUCCESS';
alter table admin_operation_log add column if not exists risk_level varchar(16) not null default 'NORMAL';

create index if not exists idx_admin_operation_actor_window on admin_operation_log(actor_id, created_at desc);
create index if not exists idx_admin_operation_request_id on admin_operation_log(request_id);
create index if not exists idx_admin_operation_window on admin_operation_log(created_at desc, category, outcome);
create index if not exists idx_admin_operation_action_window on admin_operation_log(action, created_at desc);
