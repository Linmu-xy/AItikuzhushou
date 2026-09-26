-- Preserve administrators' intentional non-Flash choices while upgrading every persisted Flash text route.
update model_provider_configs
set text_model = 'deepseek-v4-pro',
    updated_at = current_timestamp
where text_model = 'deepseek-v4-flash';
