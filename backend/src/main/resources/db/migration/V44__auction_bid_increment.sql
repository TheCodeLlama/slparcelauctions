-- Per-auction minimum bid increment. The DEFAULT 50 satisfies NOT NULL for any
-- pre-existing non-live rows (ended / cancelled); there are no live auctions.
-- AuctionService writes the creator's resolved value explicitly on every new
-- auction, so the column default is never the operative value for a real one.
ALTER TABLE auctions
  ADD COLUMN bid_increment BIGINT NOT NULL DEFAULT 50;
