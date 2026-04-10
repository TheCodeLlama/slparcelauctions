# Task 09-03: SL IM Delivery Queue

> **Before starting:** read [CONVENTIONS.md](../CONVENTIONS.md) for project-wide rules (no new migrations, Lombok required, vertical slices, feature-based packages).

## Goal

Build the queue and endpoint for delivering notifications as Second Life instant messages via the in-world terminal.

## Context

See DESIGN.md Section 11. SL IMs are sent by the in-world terminal script using `llInstantMessage()`. The backend queues messages and the terminal polls for pending deliveries. The terminal LSL script is built in Epic 11.

## What Needs to Happen

- **SL IM message queue:**
  - Store pending IM messages: recipient_sl_uuid, message_text, priority, created_at, delivered_at, status (PENDING/DELIVERED/FAILED/EXPIRED)
  - Messages expire after 24 hours if not delivered (terminal offline)
  - Message text formatted for SL IM: plain text, max ~1024 chars, include a shortened link to the relevant page

- **Terminal polling endpoint:**
  - GET /api/v1/internal/sl-im/pending - returns pending messages for delivery
    - Authenticated with shared secret (same as other terminal endpoints)
    - Returns batch of messages (up to 10 at a time)
    - Ordered by priority then created_at
  - POST /api/v1/internal/sl-im/{id}/delivered - terminal confirms delivery
  - POST /api/v1/internal/sl-im/{id}/failed - terminal reports delivery failure (avatar offline, etc.)

- **Quiet hours enforcement:**
  - Before queuing a SL IM: check user's quiet hours setting
  - If current time (in SL time / Pacific) falls within quiet hours: skip the IM entirely
  - User still gets website notification regardless

- **Message formatting:**
  - Keep messages concise for SL IM format
  - Include: what happened + link to act
  - Examples:
    - "You've been outbid on [Parcel Name]! Current bid: L$5,000. View: slparcelauctions.com/auctions/123"
    - "Your auction for [Parcel Name] has ended. Winner: [Name]. Check escrow status: slparcelauctions.com/auctions/123/escrow"
    - "Escrow complete! L$4,750 has been paid out for [Parcel Name]."

- **Rate limiting:**
  - Max 1 SL IM per user per 5 minutes per category (prevent spam)
  - Outbid IMs: same debounce as email (only latest bid)

## Acceptance Criteria

- Messages queued when SL IM is enabled for the notification category
- Terminal can poll for pending messages and confirm delivery
- Quiet hours prevent message queuing during configured window
- Messages expire after 24 hours if terminal never picks them up
- Rate limiting prevents IM spam
- Message text is concise and includes relevant link
- Delivery status tracked (pending/delivered/failed/expired)

## Notes

- `llInstantMessage()` in LSL can send an IM to any avatar UUID. The terminal script handles the actual sending.
- SL IMs have a character limit (~1024). Keep messages well under this.
- If the terminal is offline (no delivery confirmations coming in), messages will pile up and expire. This is acceptable - website notifications are the fallback.
- The terminal polls on a timer (e.g., every 60 seconds). Don't expect real-time delivery for SL IMs.
- This task creates the backend queue. The LSL terminal script that polls this endpoint is in Epic 11.
