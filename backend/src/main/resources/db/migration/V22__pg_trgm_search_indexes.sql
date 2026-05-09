-- pg_trgm trigram indexes for the header search overlay (spec
-- 2026-05-09-header-search-overlay-design.md §5.5). Powers ILIKE
-- substring matches and similarity() ranking on listing titles,
-- parcel names, and region names. pg_trgm is bundled with Postgres
-- and is one of the default extensions enabled on AWS RDS PG.
CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE INDEX IF NOT EXISTS idx_auctions_title_trgm
    ON public.auctions USING gin (title gin_trgm_ops);

CREATE INDEX IF NOT EXISTS idx_parcel_snapshots_parcel_name_trgm
    ON public.auction_parcel_snapshots USING gin (parcel_name gin_trgm_ops);

CREATE INDEX IF NOT EXISTS idx_regions_name_trgm
    ON public.regions USING gin (name gin_trgm_ops);
