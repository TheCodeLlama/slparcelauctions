--
-- Migrate fraud_flags after the parcels table was removed in V8.
-- The DROP TABLE parcels CASCADE in V8 already dropped any FK constraint
-- from fraud_flags.parcel_id to parcels(id). This migration drops the
-- now-orphaned parcel_id column and adds the denormalised sl_parcel_uuid
-- column that FraudFlag entity uses instead.
--

DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = 'public' AND table_name = 'fraud_flags'
          AND column_name = 'parcel_id'
    ) THEN
        ALTER TABLE public.fraud_flags DROP COLUMN parcel_id;
    END IF;
END $$;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = 'public' AND table_name = 'fraud_flags'
          AND column_name = 'sl_parcel_uuid'
    ) THEN
        ALTER TABLE public.fraud_flags ADD COLUMN sl_parcel_uuid uuid;
    END IF;
END $$;
