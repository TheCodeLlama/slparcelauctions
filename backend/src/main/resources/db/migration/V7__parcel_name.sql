--
-- Persist the parcel's display name (`<meta name="parcel">` from the SL
-- World API). The wizard pre-fills the listing title from this value when
-- the seller first picks a parcel — saves them retyping the SL-side name.
-- Nullable because pre-existing rows (none in prod, possibly some in dev
-- DBs) won't have it; new lookups always populate it.
--

ALTER TABLE public.parcels
    ADD COLUMN parcel_name VARCHAR(255);
