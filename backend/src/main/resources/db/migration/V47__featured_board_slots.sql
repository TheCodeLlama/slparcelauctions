-- One row per (auction x board assignment). Auctions land on boards via
-- PROMO-01 purchase (the seller buying Featured exposure); slots release
-- when the auction ends, is cancelled/withdrawn, or admin force-releases.
-- Per-board queue order is `position` asc within the same board where
-- released_at IS NULL.

CREATE TABLE featured_board_slots (
    id           BIGSERIAL PRIMARY KEY,
    public_id    UUID NOT NULL UNIQUE,
    board_index  INTEGER NOT NULL,
    auction_id   BIGINT NOT NULL REFERENCES auctions(id),
    position     INTEGER NOT NULL,
    assigned_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    released_at  TIMESTAMPTZ,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    version      BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT featured_board_slots_board_index_range
        CHECK (board_index BETWEEN 1 AND 13)
);

-- Live queue lookup: rows for a specific active board, in display order.
CREATE INDEX featured_board_slots_live_queue_idx
    ON featured_board_slots (board_index, position)
    WHERE released_at IS NULL;

-- Exactly one active row per auction (an auction is on at most one board
-- at a time). Used as the idempotency guard for PROMO-01 purchase -- the
-- second buy attempt fails the constraint and the controller maps to 409.
CREATE UNIQUE INDEX featured_board_slots_active_per_auction_idx
    ON featured_board_slots (auction_id)
    WHERE released_at IS NULL;
