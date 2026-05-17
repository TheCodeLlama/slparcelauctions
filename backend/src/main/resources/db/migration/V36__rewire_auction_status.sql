-- V36__rewire_auction_status.sql
-- Translates rows from the pre-rewire AuctionStatus enum (which sat every
-- post-close auction at ENDED) to the post-rewire enum (which reflects the
-- escrow phase directly).

BEGIN;

-- ENDED + COMPLETED escrow -> COMPLETED
UPDATE auctions a
SET status = 'COMPLETED'
FROM escrows e
WHERE a.id = e.auction_id
  AND a.status = 'ENDED'
  AND e.state = 'COMPLETED';

-- ENDED + TRANSFER_PENDING / FUNDED / ESCROW_PENDING escrow -> TRANSFER_PENDING
-- (FUNDED and ESCROW_PENDING are transient escrow stops; if any persisted
-- for legacy reasons we treat them as mid-flight.)
UPDATE auctions a
SET status = 'TRANSFER_PENDING'
FROM escrows e
WHERE a.id = e.auction_id
  AND a.status = 'ENDED'
  AND e.state IN ('TRANSFER_PENDING', 'FUNDED', 'ESCROW_PENDING');

-- ENDED + DISPUTED escrow -> DISPUTED
UPDATE auctions a
SET status = 'DISPUTED'
FROM escrows e
WHERE a.id = e.auction_id
  AND a.status = 'ENDED'
  AND e.state = 'DISPUTED';

-- ENDED + FROZEN escrow -> FROZEN
UPDATE auctions a
SET status = 'FROZEN'
FROM escrows e
WHERE a.id = e.auction_id
  AND a.status = 'ENDED'
  AND e.state = 'FROZEN';

-- ENDED + EXPIRED escrow -> EXPIRED
UPDATE auctions a
SET status = 'EXPIRED'
FROM escrows e
WHERE a.id = e.auction_id
  AND a.status = 'ENDED'
  AND e.state = 'EXPIRED';

-- ENDED + no escrow row -> EXPIRED (NO_BIDS / RESERVE_NOT_MET outcomes)
UPDATE auctions
SET status = 'EXPIRED'
WHERE status = 'ENDED'
  AND id NOT IN (SELECT auction_id FROM escrows WHERE auction_id IS NOT NULL);

-- Defensive: nothing should sit at ESCROW_PENDING / ESCROW_FUNDED today,
-- but if a stale row exists it maps to the same mid-flight state.
UPDATE auctions
SET status = 'TRANSFER_PENDING'
WHERE status IN ('ESCROW_PENDING', 'ESCROW_FUNDED');

COMMIT;
