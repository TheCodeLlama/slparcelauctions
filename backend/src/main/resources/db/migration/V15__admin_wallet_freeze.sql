-- Admin wallet ops: per-user wallet freeze.
--
-- Setting wallet_frozen_at gates every outflow path (withdraw, pay-penalty,
-- pay-listing-fee, bid-reservation). Inflows (deposits, admin adjustments) are
-- not blocked. wallet_frozen_by_admin_id and wallet_frozen_reason are
-- denormalised from the AdminAction audit row so the freeze state can be
-- displayed cheaply on every wallet read.

ALTER TABLE users
    ADD COLUMN wallet_frozen_at TIMESTAMP WITH TIME ZONE NULL,
    ADD COLUMN wallet_frozen_by_admin_id BIGINT NULL REFERENCES users(id),
    ADD COLUMN wallet_frozen_reason TEXT NULL;
