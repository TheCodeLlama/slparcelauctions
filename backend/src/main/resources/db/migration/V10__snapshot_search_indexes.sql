--
-- Recreate the search-path indexes that existed on the parcels table,
-- now pointing at auction_parcel_snapshots. The original index names are
-- preserved so existing tooling and test assertions remain valid.
--

CREATE INDEX IF NOT EXISTS ix_parcels_region
    ON public.auction_parcel_snapshots (region_id);

CREATE INDEX IF NOT EXISTS ix_parcels_area_sqm
    ON public.auction_parcel_snapshots (area_sqm);
