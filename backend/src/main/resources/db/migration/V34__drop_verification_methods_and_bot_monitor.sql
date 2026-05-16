-- V34: Drop the three-method verification surface and the bot's
-- auction/escrow monitor paths.
--
-- After this migration verification is a single synchronous World API
-- ownership check (see AuctionVerificationService). The bot's
-- MONITOR_AUCTION and MONITOR_ESCROW paths are retired entirely; the
-- World API-based OwnershipCheckTask + EscrowOwnershipCheckTask are the
-- only surfaces watching ownership.
--
-- All current production listings have been cancelled per the spec
-- (docs/superpowers/specs/2026-05-16-ownership-only-verification-design.md
-- §7) so no data backfill is needed. The defensive bot_tasks DELETE
-- below cleans any leftover VERIFY/MONITOR_* rows so the dropped enum
-- values do not violate the rebuilt CHECK constraint
-- (BotTaskTypeCheckConstraintInitializer refreshes it at boot from the
-- in-code enum).
--
-- The FraudFlagReason enum values BOT_PRICE_DRIFT, BOT_AUTH_BUYER_REVOKED,
-- BOT_OWNERSHIP_CHANGED, and BOT_ACCESS_REVOKED stay in the Java enum so
-- historical fraud_flag rows that reference them remain readable. Those
-- values are stored as TEXT, so no DDL is required.

-- Defensive cleanup: remove bot_tasks rows whose task_type values are
-- being dropped. Empty for current prod (all listings cancelled) but
-- cheap to keep.
DELETE FROM bot_tasks WHERE task_type IN ('VERIFY', 'MONITOR_AUCTION', 'MONITOR_ESCROW');

-- pending_verification table -- legacy table from the earlier dispatch
-- model. Drop if it ever existed; no-op otherwise.
DROP TABLE IF EXISTS pending_verification;

-- Auction columns that only the three-method dispatcher / bot monitor
-- ever wrote.
ALTER TABLE auctions DROP COLUMN IF EXISTS verification_method;
ALTER TABLE auctions DROP COLUMN IF EXISTS assigned_bot_uuid;
ALTER TABLE auctions DROP COLUMN IF EXISTS sale_sentinel_price;
ALTER TABLE auctions DROP COLUMN IF EXISTS last_bot_check_at;
ALTER TABLE auctions DROP COLUMN IF EXISTS bot_check_failures;

-- Bot-task columns specific to MONITOR_AUCTION; MONITOR_ESCROW columns
-- (expected_winner_uuid, expected_seller_uuid, expected_max_sale_price_lindens)
-- are kept on disk for forensic queries against historical rows but are
-- no longer mapped on BotTask.
ALTER TABLE bot_tasks DROP COLUMN IF EXISTS expected_auth_buyer_uuid;
ALTER TABLE bot_tasks DROP COLUMN IF EXISTS expected_sale_price_lindens;

-- New: streak counter for ACTIVE-state owner mismatches. The
-- OwnershipCheckTask increments this on each consecutive World API owner
-- mismatch and suspends only when it crosses
-- slpa.ownership-monitor.mismatch-streak-threshold (default 2). Resets
-- to 0 on any owner match. Backfills as 0 for every existing row.
ALTER TABLE auctions
    ADD COLUMN consecutive_owner_mismatches INTEGER NOT NULL DEFAULT 0;
