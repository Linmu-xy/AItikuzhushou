alter table exam_project_generation_items
  drop constraint if exists exam_project_generation_items_status_check;

alter table exam_project_generation_items
  add constraint exam_project_generation_items_status_check
  check (status in ('PLANNED','GENERATING','REVIEW_REQUIRED','REVIEW_PENDING','REJECTED','FAILED','APPROVED','REMOVED'));
