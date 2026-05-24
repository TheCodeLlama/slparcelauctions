-- Per-auction bot-driven parcel scan. Produces two sibling rasters
-- (AuctionParcelLayout + AuctionParcelHeightMap) per ACTIVE auction.
-- See docs/superpowers/specs/2026-05-23-parcel-scanner-design.md.

-- Future-paid-upgrade flag. True today for every new auction; the
-- entitlement check is future work. The DEFAULT exists for migration
-- safety on any pre-existing rows; AuctionService.create writes the
-- value explicitly on every new auction.
ALTER TABLE auctions
  ADD COLUMN parcel_scan_included BOOLEAN NOT NULL DEFAULT true;

-- Region-wide parcel-membership bitmap. 64x64 cells, 4m each = 256m
-- region. Row-major from the south-west corner. cells = packed bitmap,
-- 1 bit per cell, MSB-first within each byte. 4096 bits = 512 bytes.
CREATE TABLE auction_parcel_layouts (
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

-- Region-wide elevation raster on the same grid as the layout. cells
-- = 4096 uint8s, row-major SW-first. Decode:
--   elevationMeters = base_meters + (cell & 0xFF) * step_meters
-- step is auto-fitted at encode time so that 255 * step covers the
-- region's actual elevation range. ~4 KB + 8-byte header total.
CREATE TABLE auction_parcel_height_maps (
  auction_id       BIGINT PRIMARY KEY REFERENCES auctions(id) ON DELETE CASCADE,
  public_id        UUID NOT NULL UNIQUE,
  grid_size        INT NOT NULL,
  cell_size_meters INT NOT NULL,
  base_meters      REAL NOT NULL,
  step_meters      REAL NOT NULL,
  cells            BYTEA NOT NULL,
  scanned_at       TIMESTAMPTZ NOT NULL,
  created_at       TIMESTAMPTZ NOT NULL,
  updated_at       TIMESTAMPTZ NOT NULL,
  version          BIGINT NOT NULL DEFAULT 0
);
