alter table document_chunks add column if not exists embedding_model varchar(80);
create index if not exists idx_document_chunks_document on document_chunks(document_id, chunk_index);
