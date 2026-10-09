-- V50 added REMOVED for PostgreSQL's generated constraint name, but H2 keeps
-- the original unnamed check under a generated name. Drop both variants so
-- the same status contract is enforced on every supported database.
alter table exam_project_generation_items
  drop constraint if exists exam_project_generation_items_status_check;

alter table exam_project_generation_items
  drop constraint if exists CONSTRAINT_DB38E6;

alter table exam_project_generation_items
  add constraint exam_project_generation_items_status_check
  check (status in ('PLANNED','GENERATING','REVIEW_REQUIRED','REVIEW_PENDING','REJECTED','FAILED','APPROVED','REMOVED'));
