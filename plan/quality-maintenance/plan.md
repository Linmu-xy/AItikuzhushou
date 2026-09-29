# Quality workbench and maintainable knowledge base

User-approved scope: these two features only. No navigation redesign, quota change,
sharing, embedding migration or large-batch generation. Preserve existing data.

## Visual contract

Reuse studio.css: #282b39 ink, #737684 muted, #545bce accent, #eeeffb selected,
#e7e8ee border, #fafbfc canvas. Existing system Chinese sans, 14px body,
21px section headings. White surfaces, restrained borders, visible focus states.
No decorative labels, gradients or new global control styles.

    Knowledge: existing base list | title + settings
                                  documents / knowledge points
                                  searchable list | inline editor
    Quality:   counts + filters
               question list | preview / edit + actionable checks
                             | AI proposal (explicit adoption) + history

Small screens stack columns. Editors stay in normal flow; menus are not clipped.
Long stems/filenames wrap; async operations show status and preserve input.

## Delivery boundaries

1. Versioned knowledge-point CRUD, archive/restore/history and optional AI draft
   extraction from authorized documents. AI never replaces confirmed points.
   Reuse document upload/parse/preview and existing knowledge-base metadata APIs.
2. Per-question quality inspection, run-local similarity hints, structured scoring
   sum checks, teacher editing, review history, and AI revision preview.
   AI proposals do not mutate or approve the original. Version conflicts reject.
   Generalized V2 questions must not require source excerpts.
3. Connect confirmed knowledge points as optional planning context, not rigid
   one-point/one-excerpt constraints. Maintain independent subject reasoning.
4. Unit tests, full backend tests, TypeScript/build, authenticated API smoke tests,
   desktop/mobile browser review. Real AI tests bounded and announced separately.

No code/index.json or token-map exists; use the repository and studio.css as the
adapted design source. App.tsx wiring is the only shell-tier change, limited to
the user-approved knowledge workspace; do not alter navigation/auth/other pages.
