alter table model_usage_events add column if not exists operation varchar(64) not null default 'UNKNOWN';
create index if not exists idx_model_usage_events_operation on model_usage_events(operation, usage_date);
