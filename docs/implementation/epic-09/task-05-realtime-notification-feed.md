# Task 09-05: Real-Time Notification Feed & Bell

> **Before starting:** read [CONVENTIONS.md](../CONVENTIONS.md) for project-wide rules (no new migrations, Lombok required, vertical slices, feature-based packages).

## Goal

Build the in-app notification feed with real-time WebSocket delivery and the notification bell in the site header.

## Context

Website notifications stored from Task 09-01. WebSocket infrastructure exists from Epic 04. This adds the user-facing notification experience.

## What Needs to Happen

- **Notification bell in header:**
  - Bell icon in the site header (visible when logged in)
  - Unread count badge (red dot with number, hide when 0)
  - Click bell → open notification dropdown/panel

- **Notification dropdown:**
  - Shows latest 10 notifications
  - Each notification: icon (by category), title, body preview (truncated), relative time ("2 min ago")
  - Unread notifications visually distinct (bold or highlighted background)
  - Click a notification → navigate to relevant page (auction detail, escrow page, profile, etc.) and mark as read
  - "Mark all as read" link at top
  - "View all" link → full notification page

- **Full notification page (`/notifications`):**
  - Paginated list of all notifications
  - Filter by: all / unread only
  - Each notification: full title, full body, category badge, timestamp, link to relevant page
  - Mark individual as read/unread

- **Real-time delivery via WebSocket:**
  - When a new notification is created for a user: push via WebSocket to their active connections
  - Update unread count badge without page refresh
  - Show a brief toast/pop-in for new notifications (auto-dismiss after 5 seconds)
  - Use the existing WebSocket connection from auction bidding (add a notification channel/topic)

- **WebSocket notification message format:**
  - `{ type: "notification", id: "...", category: "OUTBID", title: "...", body: "...", data: {...}, created_at: "..." }`

## Acceptance Criteria

- Bell icon shows accurate unread count
- Dropdown shows latest notifications with correct formatting
- Clicking notification navigates to correct page and marks as read
- "Mark all as read" clears unread count
- Full notification page works with pagination and unread filter
- New notifications appear in real-time via WebSocket (no page refresh needed)
- Toast/pop-in shown for new notifications
- Unread badge updates in real-time
- Works in dark/light mode, responsive

## Notes

- The WebSocket notification channel should be separate from the auction bid channel. Same connection, different message types.
- If WebSocket is disconnected: the unread count will be stale until next page load. That's fine for MVP.
- Toast notifications: keep them non-intrusive. Small pop-in at top-right, auto-dismiss, don't stack more than 3.
- The notification dropdown should close when clicking outside or pressing Escape.
