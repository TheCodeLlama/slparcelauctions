-- Active bid reservations for the wallet model's hard-reservation bid flow.
--
-- Per spec: docs/superpowers/specs/2026-04-30-wallet-model-design.md §3.3.
--
-- One row per (user, auction) bid reservation. When user A becomes high
-- bidder, a row is inserted; when outbid, the row's released_at is set
-- (with release_reason=OUTBID), and a fresh row is inserted for the new
-- high bidder. Auction close consumes the winning row by setting
-- release_reason=ESCROW_FUNDED and decrementing the user's reserved_lindens.
--
-- Partial unique index ensures at most one active row per (user, auction).

CREATE TABLE bid_reservations (
    id              BIGSERIAL PRIMARY KEY,
    user_id         BIGINT NOT NULL REFERENCES users(id),
    auction_id      BIGINT NOT NULL REFERENCES auctions(id),
    bid_id          BIGINT NOT NULL REFERENCES bids(id),
    amount          BIGINT NOT NULL CHECK (amount > 0),
    created_at      TIMESTAMPTZ NOT NULL,
    released_at     TIMESTAMPTZ NULL,
    release_reason  VARCHAR(32) NULL,

    CONSTRAINT bid_reservations_release_reason_check CHECK (
        release_reason IS NULL OR release_reason IN (
            'OUTBID',
            'AUCTION_CANCELLED',
            'AUCTION_FRAUD_FREEZE',
            'AUCTION_BIN_ENDED',
            'ESCROW_FUNDED',
            'USER_BANNED'
        )
    ),

    CONSTRAINT bid_reservations_release_consistency_check CHECK (
        (released_at IS NULL AND release_reason IS NULL) OR
        (released_at IS NOT NULL AND release_reason IS NOT NULL)
    )
);

-- Partial unique index: at most one active reservation per (user, auction).
-- Outbid → release prior, insert new for same user/auction works because
-- the prior row's released_at is no longer NULL.
CREATE UNIQUE INDEX bid_reservations_active_idx
    ON bid_reservations (user_id, auction_id) WHERE released_at IS NULL;

-- Per-user active-reservation lookup (used by /me/wallet endpoints + dormancy).
CREATE INDEX bid_reservations_user_active_idx
    ON bid_reservations (user_id) WHERE released_at IS NULL;

-- Per-auction active-reservation lookup (used by bid swap + cancellation).
CREATE INDEX bid_reservations_auction_active_idx
    ON bid_reservations (auction_id) WHERE released_at IS NULL;
