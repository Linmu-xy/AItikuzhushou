alter table generation_jobs add column if not exists error_message varchar(2000);
create index if not exists idx_generation_jobs_status on generation_jobs(status, updated_at);
