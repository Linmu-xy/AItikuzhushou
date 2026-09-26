alter table source_documents add column if not exists extracted_text text;
alter table source_documents add column if not exists updated_at timestamp with time zone;
create index if not exists idx_source_documents_kb_created on source_documents(knowledge_base_id, created_at);
create index if not exists idx_generation_jobs_created on generation_jobs(created_at);
