alter table question_professional_audits
  add column if not exists outsider_solvable_score integer;

alter table question_professional_audits
  add column if not exists option_reviews_json text not null default '[]';
