--
-- Add the source column to auction_photos that distinguishes seller-uploaded
-- photos from the SL_PARCEL_SNAPSHOT image fetched at parcel-lookup time.
-- The entity defaults new rows to 'SELLER_UPLOAD'; the column-level DEFAULT
-- is set so this migration is safe to apply against tables with existing
-- rows.
--

ALTER TABLE public.auction_photos
    ADD COLUMN IF NOT EXISTS source character varying(255) NOT NULL DEFAULT 'SELLER_UPLOAD';

-- Partial unique index — at most one SL-derived photo per auction. Re-lookup
-- replaces the row in place (same auction_photos.id, refreshed bytes).
CREATE UNIQUE INDEX IF NOT EXISTS uq_auction_photos_sl_snapshot
    ON public.auction_photos (auction_id)
    WHERE source = 'SL_PARCEL_SNAPSHOT';
