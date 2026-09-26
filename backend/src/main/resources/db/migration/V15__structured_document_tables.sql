create table if not exists document_tables (
  id uuid primary key,
  document_id uuid not null references source_documents(id) on delete cascade,
  page_no integer not null,
  table_index integer not null,
  title varchar(500),
  headers_json text not null,
  rows_json text not null,
  markdown text not null,
  confidence double precision not null default 0,
  review_status varchar(32) not null default 'AI_EXTRACTED',
  warnings_json text,
  created_at timestamp with time zone not null,
  updated_at timestamp with time zone not null,
  unique(document_id,page_no,table_index)
);

create index if not exists idx_document_tables_document_page
  on document_tables(document_id,page_no,table_index);

alter table document_chunks add column if not exists content_type varchar(32) default 'TEXT';
alter table document_chunks add column if not exists metadata_json text;
create index if not exists idx_document_chunks_document_type
  on document_chunks(document_id,content_type,chunk_index);
