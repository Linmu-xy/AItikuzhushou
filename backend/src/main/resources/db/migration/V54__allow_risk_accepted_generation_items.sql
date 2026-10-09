-- A teacher may explicitly keep a failed/rejected original question after accepting
-- its AI/quality risk. Treat that state as exportable, but keep it distinguishable
-- from a normal approval in the review and approval exports.
alter table exam_project_generation_items
  drop constraint if exists exam_project_generation_items_status_check;
alter table exam_project_generation_items
  drop constraint if exists CONSTRAINT_DB38E6;
alter table exam_project_generation_items
  add constraint exam_project_generation_items_status_check
  check (status in ('PLANNED','GENERATING','REVIEW_REQUIRED','REVIEW_PENDING','REJECTED','FAILED','APPROVED','REMOVED','APPROVED_WITH_RISK'));
