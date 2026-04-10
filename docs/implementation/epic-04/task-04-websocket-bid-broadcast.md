# Task 04-04: WebSocket Real-Time Bid Broadcasting

## Goal

Wire up bid events to the WebSocket infrastructure (Epic 01 Task 09) so all users viewing an auction see live bid updates, snipe extensions, and countdown changes in real-time.

## Context

STOMP WebSocket setup exists from Epic 01 Task 09. BidService and proxy/snipe logic exist from Tasks 04-01 through 04-03. Topic pattern: `/topic/auction/{auctionId}`.

## What Needs to Happen

- After every successful bid placement (manual or proxy), broadcast a message to `/topic/auction/{auctionId}` containing:
  - Event type (NEW_BID, PROXY_BID, SNIPE_EXTENSION, AUCTION_ENDED, BUY_NOW)
  - Current bid amount
  - Bid count
  - Current winner display name
  - Updated ends_at (important for snipe extensions)
  - Time remaining (server-calculated)

- Broadcast snipe extension events:
  - When ends_at changes due to snipe protection, broadcast the new end time
  - Clients update their countdown timers based on server time

- Broadcast auction end events:
  - When auction timer expires, broadcast AUCTION_ENDED with final results
  - Include: winning bid, winner display name, reserve met/not met

- Broadcast buy-it-now events:
  - If a bid matches buy_now_price, broadcast immediate auction end

- Frontend subscription:
  - When viewing an auction detail page, subscribe to that auction's topic
  - On receiving a message, update the UI in place (current bid, bid count, countdown timer)
  - Show a brief visual indicator when a new bid arrives (flash, animation, etc.)
  - Handle snipe extension: smoothly update the countdown to the new end time
  - Unsubscribe when leaving the page

## Acceptance Criteria

- A bid placed by user A is visible to user B viewing the same auction within ~1 second
- Snipe extension updates the countdown timer on all connected clients
- Buy-it-now triggers immediate auction end broadcast
- Auction end event is broadcast when timer expires
- Frontend updates bid amount, count, and timer without page refresh
- New bid arrival has a visual indicator (highlight, animation, etc.)
- Clients reconnect and re-subscribe after brief disconnections
- Subscribing to an auction topic does not require authentication (read-only public data)

## Notes

- Bid placement itself still requires JWT auth (Task 04-01). WebSocket subscriptions for viewing are public.
- Include server timestamp in every broadcast so clients can calculate accurate time remaining even if there's clock skew.
- Consider throttling: if proxy bids resolve rapidly (two proxies dueling), batch or debounce broadcasts so the UI doesn't flicker.
- The auction end broadcast needs to be triggered by a reliable server-side mechanism, not client-side timers (see Task 04-05).
