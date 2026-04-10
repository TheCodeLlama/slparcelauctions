# Phase 2: Player Verification

_Reference: DESIGN.md Section 4.1, 6.1, 9_

---

## Goal

Allow users to link their website account to a verified Second Life avatar identity. This is the foundation for all other features - you can't list or bid without being verified.

---

## What Needs to Happen

### Verification Code System

- Verified users can generate a 6-digit, time-limited (15 minute) verification code from their dashboard
- Codes are single-use and expire
- One SL avatar per web account (enforced server-side, reject duplicates)

### SL Script Endpoint

- `POST /api/v1/sl/verify` endpoint that receives verification data from in-world LSL scripts
- Accepts: verification code, avatar UUID, legacy name, display name, username, born date, payment info status
- Validates SL-injected headers:
  - `X-SecondLife-Shard` must equal `"Production"` (reject beta grid)
  - `X-SecondLife-Owner-Key` must match expected SLPA service account
- On valid code: links avatar data to web account, marks account as verified

### User Profile Basics

- Profile page showing verified SL identity (avatar name, display name, account age)
- Profile picture upload (user-uploaded, not from SL)
- Display name override
- Bio/description field
- Profile viewable by other users at `/users/{id}`

### Dashboard

- Verification status indicator
- "Generate Verification Code" button (only if not yet verified)
- Basic "my account" settings

---

## Acceptance Criteria

- Unverified user can generate a verification code and see it on their dashboard
- API endpoint correctly validates SL headers and verification code
- Successful verification links SL avatar UUID to account and stores all avatar metadata
- Duplicate avatar UUID is rejected (one avatar per account)
- Expired/used codes are rejected
- User profile page displays verified identity information
- Profile picture upload works (stored, served, displayed)
- Verified status is visible on the user's profile and dashboard

---

## Notes

- The actual LSL script that calls this endpoint is in Phase 11. For now, you can test with curl/Postman by manually setting the `X-SecondLife-*` headers.
- Profile picture storage solution (S3, R2, local filesystem) is your call for now.
