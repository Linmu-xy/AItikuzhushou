alter table question_reviews add column if not exists approved_at timestamp with time zone;
update question_reviews set approved_at=updated_at where review_status in ('APPROVED','LOCKED') and approved_at is null;
create index if not exists idx_question_reviews_archive on question_reviews(owner_id,review_status,approved_at desc);
create index if not exists idx_exam_project_items_archive on exam_project_generation_items(status,reviewed_at desc);
