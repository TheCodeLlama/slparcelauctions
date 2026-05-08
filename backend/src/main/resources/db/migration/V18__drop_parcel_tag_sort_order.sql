-- Drop the parcel_tags.sort_order column. Manual sort was YAGNI — the
-- public catalogue and admin list now sort tags alphabetically by label
-- within each category. See docs/superpowers/specs/2026-05-08-admin-parcel-tags-design.md.
ALTER TABLE parcel_tags DROP COLUMN IF EXISTS sort_order;
