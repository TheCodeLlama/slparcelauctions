--
-- Promote region data to a first-class entity. Until this migration the only
-- persistent home for region grid coords was Redis (CachedRegionResolver) +
-- denormalized columns on parcels; maturity rating was wrongly scoped to the
-- parcel and read from a parcel-page meta tag that doesn't reliably exist.
-- Region is now its own table, populated from world.secondlife.com/region/{uuid}
-- on every parcel lookup; parcels FK into it.
--
-- Coords stored in region units (1 unit = 1 region = 256m), matching what SL
-- exposes on the region page. Mainland check at insert time multiplies by 256
-- before consulting MainlandContinents (which is keyed on world meters).
--

CREATE TABLE public.regions (
    id              BIGSERIAL PRIMARY KEY,
    sl_uuid         UUID NOT NULL UNIQUE,
    name            VARCHAR(100) NOT NULL UNIQUE,
    grid_x          DOUBLE PRECISION NOT NULL,
    grid_y          DOUBLE PRECISION NOT NULL,
    maturity_rating VARCHAR(10) NOT NULL,
    created_at      TIMESTAMP(6) WITH TIME ZONE NOT NULL DEFAULT now()
);

CREATE INDEX ix_regions_grid_coords ON public.regions (grid_x, grid_y);
CREATE INDEX ix_regions_maturity    ON public.regions (maturity_rating);

ALTER TABLE public.parcels
    ADD COLUMN region_id  BIGINT REFERENCES public.regions(id),
    ADD COLUMN owner_name VARCHAR(255);

-- Parcels are wiped on this migration boundary. The prod DB was wiped on
-- 2026-05-02 and the PR-1-precursor parser bug prevented any successful
-- parcel-lookup before this migration shipped, so production has no rows
-- to backfill. Dev/test DBs may still hold stale parcel rows from prior
-- test runs whose foreign keys the new schema can't reconstruct — clear
-- them so the FK can become NOT NULL safely.
TRUNCATE TABLE public.parcels CASCADE;
ALTER TABLE public.parcels ALTER COLUMN region_id SET NOT NULL;

CREATE INDEX ix_parcels_region ON public.parcels (region_id);

DROP INDEX IF EXISTS ix_parcels_grid_coords;
DROP INDEX IF EXISTS ix_parcels_maturity;

ALTER TABLE public.parcels
    DROP COLUMN region_name,
    DROP COLUMN grid_x,
    DROP COLUMN grid_y,
    DROP COLUMN continent_name,
    DROP COLUMN maturity_rating;
