create table if not exists document_pages (
  id uuid primary key,
  document_id uuid not null references source_documents(id) on delete cascade,
  page_no integer not null,
  markdown text not null,
  review_status varchar(32) not null default 'AI_EXTRACTED',
  version integer not null default 1,
  active boolean not null default true,
  warnings_json text,
  confirmed_by varchar(120),
  created_at timestamp with time zone not null,
  updated_at timestamp with time zone not null,
  unique(document_id, page_no)
);

create index if not exists idx_document_pages_document_active
  on document_pages(document_id, active, page_no);

create table if not exists document_page_versions (
  id uuid primary key,
  document_page_id uuid not null references document_pages(id) on delete cascade,
  version integer not null,
  markdown text not null,
  review_status varchar(32) not null,
  action varchar(40) not null,
  actor varchar(120) not null,
  created_at timestamp with time zone not null,
  unique(document_page_id, version)
);

create index if not exists idx_document_page_versions_page
  on document_page_versions(document_page_id, version desc);
