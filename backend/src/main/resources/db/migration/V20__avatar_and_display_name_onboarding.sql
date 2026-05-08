-- Two new boolean columns gating the post-verify onboarding flow:
--   avatar_step_completed:        true once the user uploads, picks their SL
--                                 profile photo, or explicitly skips
--   display_name_step_completed:  true once the user saves a name or skips
-- Both flags drive the new (onboarded) frontend layout redirect.
ALTER TABLE users
    ADD COLUMN avatar_step_completed boolean NOT NULL DEFAULT false,
    ADD COLUMN display_name_step_completed boolean NOT NULL DEFAULT false;

-- Backfill: pre-existing users who already have a profile pic OR a custom
-- display name set are not re-prompted. Verified users with neither set
-- will hit the new gate on next visit.
UPDATE users SET avatar_step_completed       = true WHERE profile_pic_url IS NOT NULL;
UPDATE users SET display_name_step_completed = true WHERE display_name    IS NOT NULL;
