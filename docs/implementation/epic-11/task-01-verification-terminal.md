# Task 11-01: Verification Terminal LSL Script

## Goal

Build the in-world verification terminal that players touch to link their SL avatar to their SLPA website account.

## Context

See DESIGN.md Section 5.1. The user creates an account on slparcelauctions.com, gets a 6-digit verification code, then touches this terminal in SL to complete verification. Backend endpoint exists from Epic 02 Task 02.

## What Needs to Happen

- **Idle state:**
  - Floating text above object: "SLPA Verification Terminal\nTouch to verify your account"
  - Object should look like a terminal/kiosk (texture/description, not script concern)

- **Touch interaction:**
  - `touch_start` event: greet the user via `llSay` or `llRegionSayTo`
  - Prompt for 6-digit verification code via `llTextBox()`
  - `listen` event captures the code from `llTextBox` response

- **Avatar data gathering (after code received):**
  - UUID: `llDetectedKey(0)` (from touch_start, store in variable)
  - Legacy name: `llDetectedName(0)`
  - Display name: `llGetDisplayName(avatar_uuid)`
  - Username: `llGetUsername(avatar_uuid)`
  - Account creation date: `llRequestAgentData(avatar_uuid, DATA_BORN)` → async `dataserver` event
  - Payment info: `llRequestAgentData(avatar_uuid, DATA_PAYINFO)` → async `dataserver` event
  - Must handle two async `dataserver` callbacks - correlate by request key

- **POST to backend:**
  - After all data gathered: `llHTTPRequest` POST to backend endpoint
  - URL: configured as a constant at top of script (backend verification endpoint)
  - Body: JSON with code, uuid, legacy_name, display_name, username, born_date, pay_info
  - SL automatically adds headers: `X-SecondLife-Owner-Key`, `X-SecondLife-Shard`, `X-SecondLife-Region`, `X-SecondLife-Object-Key`, etc.
  - `http_response` event handles the backend response

- **Result display:**
  - On success (200): `llRegionSayTo(avatar, 0, "✓ Verification successful! Your SL account is now linked.")`
  - On failure (4xx): parse error message from response body, display to user
  - On timeout/error: "Verification failed. Please try again or contact support."
  - Return to idle state after response

- **Security:**
  - Validate shard: check `llGetEnv("sim_channel")` equals "Second Life Server" (Production)
  - Script should only work on Production grid, not Beta/Aditi

- **Edge cases:**
  - Multiple people touching at once: queue or reject with "Terminal busy, please wait"
  - Timeout: if dataserver events don't arrive within 30 seconds, abort and notify user
  - Invalid code format (not 6 digits): reject immediately without hitting backend

## Acceptance Criteria

- Touch → code prompt → data gathered → POST to backend → result shown to user
- All avatar data fields sent correctly (UUID, names, born date, payment info)
- Async dataserver events handled correctly (both DATA_BORN and DATA_PAYINFO)
- Success and failure messages displayed appropriately
- Terminal handles concurrent touches gracefully
- Only works on Production shard
- Floating text visible when idle
- Timeouts handled (30-second abort)

## Notes

- LSL `llTextBox()` returns the user's text via a `listen` event on a specific channel. Use a random negative channel to avoid interference.
- `llRequestAgentData` returns a key that matches the key in the `dataserver` event. Store both request keys and match them.
- `DATA_PAYINFO` returns: 0 = no payment info, 1 = on file, 2 = used, 3 = on file + used. Useful for trust signals.
- Keep the script under 64KB. LSL scripts are memory-constrained.
- The backend validates the `X-SecondLife-Owner-Key` header matches the expected SLPA service account. The terminal must be owned by that account.
