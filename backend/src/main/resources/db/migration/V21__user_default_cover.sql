--
-- Per-user default cover image. Auto-inserted at sortOrder=0 on every new
-- listing draft created after the user sets it. Existing rows start unset
-- (all three columns null together, or all three non-null when set).
--

ALTER TABLE public.users
    ADD COLUMN IF NOT EXISTS default_cover_object_key   varchar(500),
    ADD COLUMN IF NOT EXISTS default_cover_content_type varchar(100),
    ADD COLUMN IF NOT EXISTS default_cover_size_bytes   bigint;

-- Partial unique index — at most one default-cover row per auction.
-- Mirrors the SL_PARCEL_SNAPSHOT pattern from V11. Application code also
-- guards via existsByAuctionIdAndSource; the index is defense-in-depth so
-- a buggy double-invoke surfaces as a constraint violation instead of a
-- silent duplicate row.
CREATE UNIQUE INDEX IF NOT EXISTS uq_auction_photos_user_default_cover
    ON public.auction_photos (auction_id)
    WHERE source = 'USER_DEFAULT_COVER';
