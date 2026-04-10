# Task 10-02: Automatic Fraud Flag Generation

> **Before starting:** read [CONVENTIONS.md](../CONVENTIONS.md) for project-wide rules (no new migrations, Lombok required, vertical slices, feature-based packages).

## Goal

Detect suspicious patterns and create fraud flags for admin review. No automated actions - just surface the signals.

## Context

See DESIGN.md Section 8. The `fraud_flags` table exists from Epic 01 migrations. Two patterns to detect: same-IP multi-account bidding and last-second new-account bids.

## What Needs to Happen

- **Same-IP multi-account detection:**
  - On each bid placement: record the bidder's IP address
  - After bid recorded: check if any OTHER user has bid on the SAME auction from the same IP
  - If match found: create fraud flag with type SAME_IP_BIDDING
  - Flag data: auction_id, flagged_user_ids (both accounts), ip_address, bid_ids, timestamp
  - Don't flag if IPs match but it's the same user (obviously)

- **Last-second new-account bids:**
  - On each bid placement: check if bidder is a "new account" (< 3 completed auctions)
  - Check if bid was placed in the final 5 minutes of the auction (before any snipe extension)
  - If both conditions true: create fraud flag with type LAST_SECOND_NEW_ACCOUNT
  - Flag data: auction_id, user_id, account_age, completed_auctions_count, time_remaining_at_bid, bid_id

- **Post-cancellation ownership change (from Epic 08 Task 03):**
  - If ownership monitoring detects a transfer within 48 hours of a cancelled-with-bids auction
  - Create fraud flag with type CANCEL_AND_SELL
  - Flag data: auction_id, seller_id, new_owner_id, cancelled_at, transfer_detected_at

- **Fraud flag storage:**
  - Each flag: id, type (enum), auction_id, data (JSONB with details), status (OPEN/REVIEWED/DISMISSED/ACTIONED), admin_notes, created_at, reviewed_at, reviewed_by
  - Flags are never auto-resolved - admin must review

- **REST endpoints (for admin dashboard, Task 10-04):**
  - GET /api/v1/admin/fraud-flags - paginated list, filterable by type and status
  - PUT /api/v1/admin/fraud-flags/{id}/review - update status (dismiss/action) with admin notes

## Acceptance Criteria

- Same-IP bidding across different accounts on same auction generates flag
- Last-second bid from new account (< 3 completed, within 5 min of end) generates flag
- Post-cancellation ownership change generates flag
- Duplicate flags not created for same pattern on same auction
- Flags store relevant context in JSONB data field
- Admin can review and update flag status
- Flag generation does not block or delay the bid placement
- No automated actions taken (bids not rejected, accounts not banned)

## Notes

- IP logging: store hashed or raw IPs based on privacy requirements. For MVP, raw is simpler.
- The same-IP check should be lightweight - index on (auction_id, ip_address) in the bids table or a separate bid_ips table.
- "Last second" threshold (5 minutes) and "new account" threshold (3 completed auctions) should be configurable.
- These flags are signals, not proof. Many legitimate scenarios produce same-IP bids (shared household, VPN). The admin decides.
