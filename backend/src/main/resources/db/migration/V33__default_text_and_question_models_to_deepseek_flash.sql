-- Keep already-managed deployments aligned with the configured Flash rollout.
-- Custom models selected by administrators are intentionally preserved.
update model_provider_configs
set text_model = 'deepseek-flash'
where text_model = 'deepseek-v4-pro';

update model_provider_configs
set question_model = 'deepseek-flash'
where question_model = 'deepseek-v4-pro';
