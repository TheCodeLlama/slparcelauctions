--
-- Phase 3 parcel refactor: replace the shared parcels table with a per-auction
-- snapshot. Each auction carries its own copy of the parcel data at list time.
--
-- Steps:
--   1. Create auction_parcel_snapshots (1:1 child of auctions, PK = auction_id).
--   2. Add sl_parcel_uuid to auctions and backfill from joined parcels row.
--   3. Drop the old parcel_id FK + column from auctions.
--   4. Drop the parcels table (now superseded by the snapshot).
--

-- Step 1: create the auction_parcel_snapshots table.
CREATE TABLE IF NOT EXISTS public.auction_parcel_snapshots (
    auction_id             bigint                       NOT NULL,
    sl_parcel_uuid         uuid                         NOT NULL,
    owner_uuid             uuid,
    owner_type             character varying(255),
    owner_name             character varying(255),
    parcel_name            character varying(255),
    description            text,
    region_id              bigint,
    region_name            character varying(255),
    region_maturity_rating character varying(255),
    area_sqm               integer,
    position_x             double precision,
    position_y             double precision,
    position_z             double precision,
    slurl                  character varying(255),
    layout_map_url         character varying(255),
    layout_map_data        text,
    layout_map_at          timestamp(6) with time zone,
    verified_at            timestamp(6) with time zone,
    last_checked           timestamp(6) with time zone,
    CONSTRAINT auction_parcel_snapshots_pkey PRIMARY KEY (auction_id)
);

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'fkchcmuirfnjo3jmv41upsh69ir'
    ) THEN
        ALTER TABLE public.auction_parcel_snapshots
            ADD CONSTRAINT fkchcmuirfnjo3jmv41upsh69ir
            FOREIGN KEY (auction_id) REFERENCES public.auctions(id);
    END IF;
END $$;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'fk57gblkqd1kp7bnsp0cxo5riy5'
    ) THEN
        ALTER TABLE public.auction_parcel_snapshots
            ADD CONSTRAINT fk57gblkqd1kp7bnsp0cxo5riy5
            FOREIGN KEY (region_id) REFERENCES public.regions(id);
    END IF;
END $$;

-- Seed snapshots for any pre-existing auctions by copying from parcels.
INSERT INTO public.auction_parcel_snapshots (
    auction_id, sl_parcel_uuid, owner_uuid, owner_type, owner_name,
    parcel_name, region_id, region_name, region_maturity_rating, area_sqm,
    verified_at, last_checked
)
SELECT
    a.id,
    p.sl_parcel_uuid,
    p.owner_uuid,
    p.owner_type,
    p.owner_name,
    p.parcel_name,
    p.region_id,
    r.name,
    r.maturity_rating,
    p.area_sqm,
    p.verified_at,
    p.last_checked
FROM   public.auctions a
JOIN   public.parcels p ON p.id = a.parcel_id
LEFT   JOIN public.regions r ON r.id = p.region_id
WHERE  a.parcel_id IS NOT NULL
  AND  NOT EXISTS (
    SELECT 1 FROM public.auction_parcel_snapshots s WHERE s.auction_id = a.id
  );

-- Step 2: add sl_parcel_uuid to auctions (if not already present), backfill.
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = 'public' AND table_name = 'auctions'
          AND column_name = 'sl_parcel_uuid'
    ) THEN
        ALTER TABLE public.auctions ADD COLUMN sl_parcel_uuid uuid;
    END IF;
END $$;

UPDATE public.auctions a
SET    sl_parcel_uuid = p.sl_parcel_uuid
FROM   public.parcels p
WHERE  p.id = a.parcel_id
  AND  a.sl_parcel_uuid IS NULL;

-- Step 3: drop parcel_id from auctions.
DO $$
DECLARE
    con_name text;
BEGIN
    -- Drop any FK constraints from auctions that reference the parcel_id column.
    FOR con_name IN
        SELECT c.conname
        FROM   pg_constraint c
        JOIN   pg_attribute  a ON a.attrelid = c.conrelid
                               AND a.attnum = ANY(c.conkey)
        WHERE  c.conrelid = 'public.auctions'::regclass
          AND  c.contype  = 'f'
          AND  a.attname  = 'parcel_id'
    LOOP
        EXECUTE 'ALTER TABLE public.auctions DROP CONSTRAINT ' || quote_ident(con_name);
    END LOOP;

    -- Drop the unique index on (parcel_id) if it exists.
    IF EXISTS (
        SELECT 1 FROM pg_indexes
        WHERE tablename = 'auctions' AND indexname = 'uq_auctions_parcel_locked_status'
    ) THEN
        DROP INDEX public.uq_auctions_parcel_locked_status;
    END IF;

    -- Drop the column itself.
    IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = 'public' AND table_name = 'auctions'
          AND column_name = 'parcel_id'
    ) THEN
        ALTER TABLE public.auctions DROP COLUMN parcel_id;
    END IF;
END $$;

-- Step 4: drop the parcels table (data is now in auction_parcel_snapshots).
-- CASCADE handles parcel_tags and any remaining FKs.
DROP TABLE IF EXISTS public.parcels CASCADE;
