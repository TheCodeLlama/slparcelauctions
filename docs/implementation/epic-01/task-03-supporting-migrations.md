# Task 01-03: Flyway Migrations - Supporting Tables

## Goal

Create all remaining database tables that support the core tables: bidding, escrow, bot infrastructure, reviews, moderation, realty groups, and verification.

## Context

See DESIGN.md Section 7 (Database Schema) for exact column definitions. These tables all have foreign key references to the core tables created in Task 01-02.

## What Needs to Happen

Create Flyway migration(s) for all remaining tables:

**Bidding:**
- `bids` - individual bid records with auction/bidder references, amount, IP address
- `proxy_bids` - proxy bid max amounts with unique constraint per user per auction

**Escrow:**
- `escrow_transactions` - payment/payout/refund records with SL transaction IDs

**Bot Infrastructure:**
- `bot_accounts` - SL bot accounts with PRIMARY/WORKER roles, unique constraint allowing only one PRIMARY
- `bot_tasks` - verification and monitoring tasks assigned to bots

**Reviews & Moderation:**
- `reviews` - buyer/seller ratings with blind visibility, unique per reviewer per auction
- `cancellation_log` - cancellation records with penalty tracking
- `report_reason` enum and `report_status` enum
- `listing_reports` - listing report records, one per user per listing
- `bans` - IP and/or avatar bans with optional expiry
- `fraud_flags` - automatic fraud detection flags

**Realty Groups:**
- `realty_groups` - group/brokerage definitions with leader reference
- `realty_group_members` - membership with role, unique user constraint
- `realty_group_invitations` - invitation records with status and expiry
- `realty_group_sl_groups` - SL group correlation records

**Verification:**
- `verification_codes` - 6-digit codes for player/parcel verification

Add all indexes specified in DESIGN.md for these tables.

## Acceptance Criteria

- All migrations run successfully on a fresh database (including the core tables from Task 01-02)
- All foreign key relationships are correct and reference the right parent tables
- Unique constraints are in place (one proxy bid per user per auction, one report per user per listing, one PRIMARY bot, etc.)
- All specified indexes exist
- Enum types (report_reason, report_status) are created
- Application starts cleanly with all migrations applied

## Notes

- Realty Groups are Phase 2 functionality, but creating the tables now avoids migration headaches later. The auction table already references realty_group_id.
- The `bot_accounts` table needs a partial unique index: only one row can have role='PRIMARY'.
- The `reviews` table's `visible` column defaults to FALSE - the blind review logic is application-level.
