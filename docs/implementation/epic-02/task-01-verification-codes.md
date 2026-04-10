# Task 02-01: Verification Code Generation & Management

> **Before starting:** read [CONVENTIONS.md](../CONVENTIONS.md) for project-wide rules (no new migrations, Lombok required, vertical slices, feature-based packages).

## Goal

Build the backend service for generating, storing, and validating 6-digit verification codes that users will use to link their SL avatar to their SLPA account.

## Context

The `verification_codes` table exists from Epic 01 migrations. Users create a web account first (email/password from Epic 01), then generate a code to verify their SL identity in-world.

## What Needs to Happen

- Create a service for verification code lifecycle:
  - Generate a random 6-digit numeric code
  - Store in `verification_codes` table with user_id, type='PLAYER', expires_at (15 minutes from now)
  - Invalidate any existing unused codes for the same user when generating a new one
  - Validate a code: check it exists, isn't expired, isn't already used, and return the associated user
  - Mark code as used after successful verification
- Create REST endpoint:
  - POST /api/v1/verification/generate - authenticated, returns the 6-digit code + expiration time
  - Should reject if user is already verified (already has sl_avatar_uuid linked)
- Codes must be single-use - once validated, they cannot be reused
- Expired codes should be ignored during validation (treat as invalid)

## Acceptance Criteria

- Authenticated user can generate a verification code via API
- Code is 6 digits, numeric, and expires after 15 minutes
- Generating a new code invalidates any previous unused code for that user
- Already-verified users cannot generate a new code (returns appropriate error)
- Code validation correctly rejects: expired codes, used codes, non-existent codes
- Code validation returns the associated user_id on success

## Notes

- The actual SL script endpoint that consumes these codes is Task 02-02. This task just handles generation and validation logic.
- Type field supports 'PLAYER' and 'PARCEL' - only 'PLAYER' is used here. 'PARCEL' is for Epic 03.
- No cleanup job needed yet - expired codes can sit in the table. A cleanup cron is a nice-to-have later.
