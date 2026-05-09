ALTER TABLE auctions
  ADD COLUMN is_featured       BOOLEAN     NOT NULL DEFAULT FALSE,
  ADD COLUMN featured_until    TIMESTAMPTZ NULL;

CREATE INDEX ix_auctions_featured_active
    ON auctions (ends_at ASC, id ASC)
    WHERE is_featured = TRUE AND status = 'ACTIVE';
