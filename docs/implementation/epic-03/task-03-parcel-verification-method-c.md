# Task 03-03: Parcel Verification - Method C (Sale-to-Bot)

## Goal

Build the backend infrastructure for Sale-to-Bot verification: the task queue, verification workflow, and status tracking. The actual bot that performs the check is Epic 06 - this task creates everything the bot will plug into.

## Context

See DESIGN.md Section 4.2 (Method C) and Section 5.4 (Bot Pool). Method C is the ONLY verification method that works for group-owned land. The seller sets their land for sale to SLPAEscrow Resident at L$999,999,999, and a worker bot confirms this via the viewer protocol.

## What Needs to Happen

- Create endpoint: POST /api/v1/parcels/verify/sale-to-bot
  - Authenticated (JWT required), user must be verified
  - Accepts: parcel UUID, region name
  - Does NOT require ownertype check (works for both individual and group land)
- On request:
  - Create parcel record in VERIFICATION_PENDING state
  - Fetch basic metadata from World API (owner, area, region, etc.)
  - Store the expected sentinel price (L$999,999,999) and primary escrow UUID on the auction record
  - Create a bot_task record with type='VERIFY', status='PENDING', the region/parcel details
  - Return task ID and instructions for the seller (set land for sale to SLPAEscrow at L$999,999,999)

- Create verification completion endpoint: POST /api/v1/admin/verification/{taskId}/complete
  - Admin-only (or dev-only for now)
  - Accepts: result data (AuthBuyerID, SalePrice, parcel owner, area, name, etc.)
  - Validates the result: AuthBuyerID matches primary escrow UUID, SalePrice is sentinel value
  - On success: marks parcel as verified, sets verification_tier to 'BOT', transitions auction to ACTIVE
  - On failure: marks task as FAILED, returns auction to DRAFT, refunds listing fee

- Create bot task query endpoint: GET /api/v1/bot/tasks/pending
  - Returns pending verification tasks for bots to pick up
  - This is what the bot service (Epic 06) will poll

- Create bot task status endpoint: PUT /api/v1/bot/tasks/{taskId}
  - Bot reports task result (success/failure with ParcelProperties data)
  - This is what the bot service will call after checking

- Handle the 48-hour verification timeout:
  - If no bot completes the task within 48 hours → auto-fail
  - Return listing to DRAFT, refund listing fee, notify seller
  - Can be a scheduled job or checked on-demand

## Acceptance Criteria

- User can request Sale-to-Bot verification and receives clear instructions
- A bot_task record is created with correct region, parcel UUID, and status
- Admin/test endpoint can manually complete a verification task with mock result data
- Successful completion transitions the auction from VERIFICATION_PENDING to ACTIVE
- Failed verification returns auction to DRAFT and marks listing fee for refund
- Bot task query endpoint returns pending tasks in priority order
- Bot task status endpoint accepts results from bot workers
- Primary escrow account UUID is configurable via application properties
- 48-hour timeout handled (at minimum, documented how it should work)

## Notes

- The primary escrow account (SLPAEscrow) UUID and the sentinel price (L$999,999,999) should be configurable constants.
- The admin completion endpoint is a temporary bridge until the bot service is built in Epic 06. It lets you test the full listing lifecycle without needing an actual SL bot.
- Bot task assignment logic (which worker picks up which task) is Epic 06 territory. For now, tasks just sit in PENDING.
- The `result_data` JSONB column on bot_tasks stores whatever the bot reads from ParcelProperties.
