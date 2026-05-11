-- V25__realty_groups_listing.sql
-- Sub-project C of realty groups (issue #237).
-- Snapshot the group's agent_fee_split onto the auction at listing-create time so the
-- listing's fee terms are a frozen contract even if the group rebalances split later.
-- NULL for individual listings (auctions.realty_group_id IS NULL).

ALTER TABLE auctions
  ADD COLUMN agent_fee_split DECIMAL(5,4) NULL;
