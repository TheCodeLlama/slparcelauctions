# Task 01-02: Flyway Migrations - Core Tables

## Goal

Create the foundational database tables that everything else depends on: users, parcels, and auctions. These are the three central tables of the entire application.

## Context

See DESIGN.md Section 7 (Database Schema) for the exact column definitions, types, constraints, and indexes. Use those definitions as the source of truth.

## What Needs to Happen

- Create Flyway migration V1__core_tables.sql (or split into V1, V2, V3 if preferred)
- Create the `users` table with all columns from DESIGN.md (identity fields, SL avatar fields, reputation fields, notification preferences as JSONB, timestamps)
- Create the `parcel_tag` enum type with all 27 tag values from DESIGN.md
- Create the `parcels` table with all columns (parcel UUID, owner reference, SL fields, grid coordinates, layout map fields, timestamps)
- Create the `auctions` table with all columns (seller, agent, realty group references, status, verification fields, bidding fields, snipe protection, commission fields, timestamps)
- Create the `auction_tags` join table linking auctions to parcel_tag enum values
- Add all indexes specified in DESIGN.md for these tables

## Acceptance Criteria

- Flyway migration runs successfully on a fresh database
- All three core tables exist with correct column types, constraints, and defaults
- Foreign key relationships between auctions→users, auctions→parcels are correct
- The parcel_tag enum has all 27 values
- auction_tags join table works with the enum
- All specified indexes are created
- Application starts and Flyway reports successful migration

## Notes

- Primary keys are BIGSERIAL (auto-incrementing bigint), NOT UUIDs. UUID columns exist for SL references but are data columns, not PKs.
- The `users` table has `email` defined twice in DESIGN.md (once in main fields, once in notification section) - use a single email column.
- Auction status is a VARCHAR(30), not an enum - this makes adding new statuses easier without migration.
