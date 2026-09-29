alter table app_users add column if not exists display_name varchar(80);
alter table app_users add column if not exists phone_number varchar(32);
alter table app_users add column if not exists phone_verified boolean not null default false;
alter table app_users add column if not exists avatar_object_key varchar(1024);
alter table app_users add column if not exists avatar_revision integer not null default 0;
alter table app_users add column if not exists assistant_effort varchar(16) not null default 'STANDARD';
alter table app_users add column if not exists assistant_web_search boolean not null default false;

create unique index if not exists uq_app_users_email_lower
    on app_users (lower(email));
create unique index if not exists uq_app_users_phone_number
    on app_users (phone_number);

alter table app_users add constraint ck_app_users_assistant_effort
    check (assistant_effort in ('FAST', 'STANDARD', 'DEEP'));
