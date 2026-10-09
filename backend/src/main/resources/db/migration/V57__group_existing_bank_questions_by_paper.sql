update user_question_bank_entries
set variant_no = (
  select i.variant_no
  from exam_project_generation_items i
  where i.id = user_question_bank_entries.source_id
    and i.run_id = user_question_bank_entries.source_run_id
)
where entry_type = 'QUESTION'
  and source_type = 'PROJECT'
  and source_run_id is not null
  and variant_no is null
  and exists (
    select 1
    from exam_project_generation_items i
    where i.id = user_question_bank_entries.source_id
      and i.run_id = user_question_bank_entries.source_run_id
  );

update user_question_bank_entries
set title = (
  select p.name || ' · ' || i.variant_label || ' · 第 ' || i.sequence_no || ' 题'
  from exam_project_generation_items i
  join exam_project_generation_runs r on r.id = i.run_id
  join exam_projects p on p.id = r.project_id
  where i.id = user_question_bank_entries.source_id
    and i.run_id = user_question_bank_entries.source_run_id
)
where entry_type = 'QUESTION'
  and source_type = 'PROJECT'
  and source_run_id is not null
  and exists (
    select 1
    from exam_project_generation_items i
    where i.id = user_question_bank_entries.source_id
      and i.run_id = user_question_bank_entries.source_run_id
  );
