-- Index for the admin listings table's "saves per auction" aggregation.
-- The existing ix_saved_auctions_user_saved_at on (user_id, saved_at DESC)
-- covers the user-side "list my saves" path. This one supports the
-- per-auction COUNT(*) aggregation used in AuctionRepository.searchAdmin.
CREATE INDEX IF NOT EXISTS ix_saved_auctions_auction_id
    ON saved_auctions (auction_id);
