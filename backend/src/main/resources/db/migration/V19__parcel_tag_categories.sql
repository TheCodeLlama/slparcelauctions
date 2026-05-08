-- Promote parcel-tag categories from a free-text string column to a
-- first-class entity. See docs/superpowers/specs/2026-05-08-admin-parcel-tag-categories-design.md.

-- 1. New table.
CREATE TABLE parcel_tag_categories (
    id          BIGSERIAL PRIMARY KEY,
    public_id   UUID NOT NULL UNIQUE,
    code        VARCHAR(50) NOT NULL UNIQUE,
    label       VARCHAR(100) NOT NULL,
    description TEXT,
    active      BOOLEAN NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    version     BIGINT NOT NULL DEFAULT 0
);

-- 2. Seed from distinct values currently on parcel_tags.
--    Code = uppercase, non-alphanumeric runs collapse to a single underscore,
--    leading/trailing underscores trimmed.
INSERT INTO parcel_tag_categories (public_id, code, label, active)
SELECT
    gen_random_uuid(),
    regexp_replace(
        regexp_replace(upper(category), '[^A-Z0-9]+', '_', 'g'),
        '^_+|_+$', '', 'g'),
    category,
    TRUE
FROM (SELECT DISTINCT category FROM parcel_tags) c;

-- 3. Add the FK column, backfill, then enforce NOT NULL.
ALTER TABLE parcel_tags ADD COLUMN category_id BIGINT;

UPDATE parcel_tags SET category_id = (
    SELECT id FROM parcel_tag_categories
    WHERE parcel_tag_categories.label = parcel_tags.category
);

ALTER TABLE parcel_tags
    ALTER COLUMN category_id SET NOT NULL,
    ADD CONSTRAINT fk_parcel_tags_category
        FOREIGN KEY (category_id) REFERENCES parcel_tag_categories(id);

-- 4. Drop the old free-text column.
ALTER TABLE parcel_tags DROP COLUMN category;

CREATE INDEX ix_parcel_tags_category_id ON parcel_tags(category_id);
