create table if not exists app_users (id uuid primary key, username varchar(80) not null unique, password_hash varchar(255) not null, status varchar(24) not null, created_at timestamp with time zone not null);
create table if not exists app_user_roles (user_id uuid not null references app_users(id), role varchar(40) not null, primary key(user_id,role));
create index if not exists idx_knowledge_bases_owner on knowledge_bases(owner_id);
