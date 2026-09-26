alter table app_users add column if not exists daily_model_quota integer not null default 1000;
create table if not exists model_usage_daily (user_id uuid not null references app_users(id), usage_date date not null, request_count integer not null, input_chars bigint not null default 0, output_chars bigint not null default 0, primary key(user_id,usage_date));
