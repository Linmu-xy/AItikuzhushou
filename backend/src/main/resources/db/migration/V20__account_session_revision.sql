alter table app_users add column if not exists session_revision integer not null default 1;

update app_users set session_revision = 1 where session_revision is null or session_revision < 1;
