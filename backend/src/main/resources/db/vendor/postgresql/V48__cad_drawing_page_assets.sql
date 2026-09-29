alter table cad_preview_assets add column if not exists page_number integer;
create index if not exists idx_cad_preview_assets_page
    on cad_preview_assets(material_id, asset_type, page_number, created_at desc);
