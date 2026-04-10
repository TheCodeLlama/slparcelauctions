# Task 03-06: Ongoing Ownership Monitoring

> **Before starting:** read [CONVENTIONS.md](../CONVENTIONS.md) for project-wide rules (no new migrations, Lombok required, vertical slices, feature-based packages).

## Goal

Build a scheduled job that periodically checks parcel ownership via the World API and suspends listings if ownership has changed unexpectedly.

## Context

See DESIGN.md Section 4.5.2 (Ongoing Monitoring by Tier). All active listings need periodic ownership verification. Script-Verified listings use World API polling. Bot-Verified listings also get bot checks, but that's Epic 06.

## What Needs to Happen

- Create a scheduled job that runs every 30 minutes:
  - Queries all ACTIVE and escrow-phase auctions
  - For each: calls World API `place/{parcel_uuid}` and checks `ownerid`
  - If ownership matches seller → update `last_checked` timestamp, continue
  - If ownership changed to the auction's winner → this may be a legitimate escrow completion (check auction status)
  - If ownership changed to an unknown third party → suspend listing, flag for review
  - If World API returns 404 (parcel deleted/merged) → suspend listing, notify seller

- Create suspension logic:
  - Set auction status to a suspended state (can reuse CANCELLED or add logic to pause)
  - Notify seller (placeholder - just log for now, notification system is Epic 09)
  - Notify active bidders (placeholder)
  - Create a fraud_flag record if ownership changed to unknown party during active auction

- Handle rate limiting:
  - Don't blast the World API with hundreds of requests simultaneously
  - Process parcels in batches with delays between batches
  - If World API is down, skip the cycle and retry next interval

- Track monitoring state:
  - Update `last_checked` on the parcel record after each successful check
  - Track consecutive failures (World API errors vs actual ownership changes)

## Acceptance Criteria

- Scheduled job runs every 30 minutes and checks ownership for all active listings
- Listings with unchanged ownership get their `last_checked` updated
- Listings with unexpected ownership changes are suspended with a fraud_flag created
- Deleted parcels (404) trigger suspension
- World API rate limiting is respected (batched requests with delays)
- Job handles World API downtime gracefully without crashing
- Job can be manually triggered for testing (via admin endpoint or actuator)

## Notes

- This is a background job, not a user-facing feature. Keep it robust and quiet.
- The 30-minute interval is a balance between freshness and API load. Make it configurable.
- For Bot-Verified listings, the bot's own 30-minute checks (Epic 06) provide additional verification. This World API check is the baseline for ALL listings regardless of tier.
- Don't worry about notification delivery yet - just create the right records and log events. Epic 09 wires up actual notifications.
