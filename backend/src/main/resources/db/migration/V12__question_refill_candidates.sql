alter table generation_job_items add column if not exists candidate_history_json text;
alter table generation_job_items add column if not exists best_score integer;
alter table generation_job_items add column if not exists fallback_selected boolean not null default false;
create index if not exists idx_generation_items_fallback on generation_job_items(job_id, fallback_selected);
