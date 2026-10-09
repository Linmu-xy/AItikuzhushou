-- Keep the explicit risk acceptance in the same immutable review history as
-- approve/reject/reopen actions.
alter table exam_project_question_review_events
  drop constraint if exists exam_project_question_review_events_action_check;
alter table exam_project_question_review_events
  drop constraint if exists CONSTRAINT_BDB4;
alter table exam_project_question_review_events
  add constraint exam_project_question_review_events_action_check
  check (action in ('EDITED','APPROVED','REJECTED','SAVED','REOPENED','RISK_ACCEPTED'));
