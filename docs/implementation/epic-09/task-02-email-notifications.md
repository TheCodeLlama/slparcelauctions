# Task 09-02: Email Notification Channel

> **Before starting:** read [CONVENTIONS.md](../CONVENTIONS.md) for project-wide rules (no new migrations, Lombok required, vertical slices, feature-based packages).

## Goal

Wire up email delivery for notifications, with templates for each category and unsubscribe handling.

## Context

NotificationService dispatches from Task 09-01. This task adds the email channel implementation.

## What Needs to Happen

- **Email provider integration:**
  - Integrate with an email service (SendGrid, AWS SES, or similar)
  - Config-driven: provider API key, from address, reply-to address
  - Send via provider API (not SMTP - more reliable)

- **Email templates:**
  - HTML + plain text versions for each notification category
  - Consistent branding (SLPA logo, Gilded Slate colors)
  - Templates should include:
    - Subject line (clear, actionable: "You've been outbid on [Parcel Name]")
    - Body with relevant details (auction name, current bid, time remaining, etc.)
    - Call-to-action button/link (e.g., "View Auction", "Complete Escrow")
    - Unsubscribe link at bottom
  - At minimum, create templates for: outbid, auction won, auction lost, escrow funded, escrow payout, listing verified, review received

- **Unsubscribe handling:**
  - Unsubscribe link includes a signed token (user + category)
  - GET /api/v1/notifications/unsubscribe?token=... - one-click unsubscribe (no login required)
  - Updates user's email preference for that category to OFF
  - Shows confirmation page: "You've been unsubscribed from [category] emails"
  - List-Unsubscribe header in email for email client integration

- **Rate limiting / deduplication:**
  - Don't send duplicate emails for the same event
  - Batch outbid notifications: if user is outbid 5 times in 2 minutes, send one email with latest bid (not 5 emails)
  - Simple approach: debounce outbid emails per user per auction (e.g., 5-minute window, send latest only)

## Acceptance Criteria

- Emails sent for enabled notification categories
- HTML emails render correctly in major email clients (Gmail, Outlook, Apple Mail)
- Plain text fallback included
- Unsubscribe link works without requiring login
- Unsubscribe updates user preferences correctly
- Outbid emails debounced (no spam during bidding wars)
- From address and branding consistent
- Email delivery failures logged (don't crash the notification pipeline)

## Notes

- SendGrid free tier: 100 emails/day. SES: very cheap at scale. Pick based on expected volume.
- Email template rendering: use a template engine (Thymeleaf for Spring Boot, or just string templates for simplicity).
- The outbid debounce is important - active auctions with snipe protection can generate many outbid events rapidly.
- Don't block on email delivery. Fire and forget with error logging.
