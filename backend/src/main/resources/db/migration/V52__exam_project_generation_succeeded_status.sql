alter table exam_project_generation_runs
  drop constraint if exists exam_project_generation_runs_status_check;

-- H2 names the original unnamed check constraint differently from PostgreSQL.
alter table exam_project_generation_runs
  drop constraint if exists CONSTRAINT_EE5044A9;

alter table exam_project_generation_runs
  add constraint exam_project_generation_runs_status_check
  check (status in ('QUEUED','RUNNING','REVIEW_REQUIRED','REVIEW_PENDING','PARTIAL','SUCCEEDED','FAILED','CANCELLED'));
