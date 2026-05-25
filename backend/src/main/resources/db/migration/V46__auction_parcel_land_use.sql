-- Per-auction region-wide land-use classification raster. 64x64 cells on
-- the same grid as auction_parcel_layouts / auction_parcel_height_maps.
-- cells = 4096 uint8s, row-major SW-first. Values 0..4:
--   0 = Other      (player-owned, not for sale; or missing-data fallback)
--   1 = Listed     (the auctioned parcel's cells; always wins precedence)
--   2 = Abandoned  (Linden, Name contains "Abandoned Land")
--   3 = ForSale    (player-owned, ParcelFlags.ForSale set)
--   4 = Protected  (Linden, Name contains "Protected Land")
--
-- Per-scan capture only -- no periodic refresh. Bidders see the
-- scanned_at timestamp in the UI legend so they can judge data age.
-- See docs/superpowers/specs/2026-05-25-parcel-map-land-use-design.md.
CREATE TABLE auction_parcel_land_use (
  auction_id       BIGINT PRIMARY KEY REFERENCES auctions(id) ON DELETE CASCADE,
  public_id        UUID NOT NULL UNIQUE,
  grid_size        INT NOT NULL,
  cell_size_meters INT NOT NULL,
  cells            BYTEA NOT NULL,
  scanned_at       TIMESTAMPTZ NOT NULL,
  created_at       TIMESTAMPTZ NOT NULL,
  updated_at       TIMESTAMPTZ NOT NULL,
  version          BIGINT NOT NULL DEFAULT 0
);
