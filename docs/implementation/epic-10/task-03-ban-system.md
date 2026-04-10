# Task 10-03: Ban System

> **Before starting:** read [CONVENTIONS.md](../CONVENTIONS.md) for project-wide rules (no new migrations, Lombok required, vertical slices, feature-based packages).

## Goal

Implement bans by IP address and/or SL avatar UUID, enforced at login, registration, verification, and bidding.

## Context

See DESIGN.md Section 8. The `bans` table exists from Epic 01 migrations. Bans are created manually by admins.

## What Needs to Happen

- **Ban storage:**
  - Each ban: id, ban_type (IP, SL_AVATAR, BOTH), ip_address (nullable), sl_avatar_uuid (nullable), reason (required text), expires_at (nullable for permanent), created_by (admin user_id), created_at
  - Active ban = not expired (expires_at is null OR expires_at > now)

- **Ban enforcement (middleware/filter):**
  - On login attempt: check IP against active IP bans → reject with "Account suspended" message
  - On registration: check IP against active IP bans → reject
  - On SL verification: check avatar UUID against active SL_AVATAR bans → reject verification and explain
  - On bid placement: check both IP and linked SL avatar → reject bid
  - On listing creation: check both → reject listing
  - Banned users who are already logged in: check on each authenticated request (or on sensitive actions only for performance)

- **Ban expiry:**
  - Time-limited bans: check expires_at on each enforcement check
  - No scheduled job needed - just check `expires_at > NOW()` in the query
  - Expired bans remain in the table for history but are inactive

- **REST endpoints (admin only):**
  - POST /api/v1/admin/bans - create ban (ip, sl_avatar_uuid, or both + reason + optional expires_at)
  - GET /api/v1/admin/bans - list active bans (paginated, filterable by type)
  - GET /api/v1/admin/bans/history - list all bans including expired
  - DELETE /api/v1/admin/bans/{id} - lift a ban early (logged with reason)
  - GET /api/v1/admin/bans/check?ip=...&sl_uuid=... - check if an IP or UUID is currently banned

- **Admin action logging:**
  - All ban create/delete actions logged with admin user ID, timestamp, reason
  - Stored in an admin_actions audit table or within the bans table itself

## Acceptance Criteria

- IP ban blocks login, registration, and bidding from that IP
- SL avatar ban blocks verification with that UUID (new account can't re-verify)
- Combined ban blocks both
- Time-limited bans expire correctly (user can act again after expiry)
- Permanent bans persist indefinitely
- Active sessions from banned users blocked on next sensitive action
- Ban reason required on creation
- Admin can lift bans early with reason
- Ban check endpoint returns accurate status
- Expired bans visible in history but not enforced

## Notes

- IP bans can be circumvented (VPN, dynamic IP). They're a speed bump, not a wall. SL avatar bans are stronger since UUIDs are permanent.
- For performance: cache active bans in Redis with short TTL (e.g., 5 min). Don't hit the DB on every request.
- The middleware/filter approach: check on sensitive actions (login, register, bid, list) rather than every single request.
- Consider a ban notification: when banning a currently-active user, invalidate their session. For MVP, just blocking on next action is fine.
