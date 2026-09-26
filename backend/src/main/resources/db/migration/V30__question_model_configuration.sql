alter table model_provider_configs
  add column if not exists question_model varchar(120);

update model_provider_configs
set question_model = text_model
where question_model is null or trim(question_model) = '';
