alter table question_professional_audits
  add column if not exists option_review_mode varchar(16) not null default 'ENFORCE';
