alter table document_tables add column if not exists structure_html text;
alter table document_tables add column if not exists table_bbox_json text;
alter table document_tables add column if not exists extraction_source varchar(48) default 'LEGACY';

create table if not exists document_table_cells (
  id uuid primary key,
  table_id uuid not null references document_tables(id) on delete cascade,
  row_no integer not null,
  column_no integer not null,
  row_span integer not null default 1,
  column_span integer not null default 1,
  cell_text text not null default '',
  confidence double precision not null default 0,
  bbox_json text,
  source varchar(48) not null default 'LEGACY',
  unique(table_id,row_no,column_no)
);
create index if not exists idx_document_table_cells_table_position
  on document_table_cells(table_id,row_no,column_no);
