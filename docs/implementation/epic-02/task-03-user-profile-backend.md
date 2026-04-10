# Task 02-03: User Profile API & Image Upload

## Goal

Build the backend API for viewing and editing user profiles, including profile picture upload with image processing.

## Context

The `users` table has profile fields (display_name, bio, profile_pic_url) from Epic 01 migrations. Users are verified via Tasks 02-01/02-02. This task exposes profile data through REST endpoints and handles image uploads.

## What Needs to Happen

- Create REST endpoints:
  - GET /api/v1/users/{id} - public profile view (returns display name, bio, profile pic URL, verified status, SL avatar name, account age, seller/buyer ratings, completion stats)
  - GET /api/v1/users/me - authenticated, returns full profile (same as above plus email, notification prefs, verification details)
  - PUT /api/v1/users/me - authenticated, update display name, bio
  - POST /api/v1/users/me/avatar - authenticated, upload profile picture (multipart file upload)
- Profile picture processing:
  - Accept JPG, PNG, WebP (reject other formats)
  - Max upload size: 2MB
  - Crop to square (center crop if not already square)
  - Generate multiple sizes: 64px, 128px, 256px
  - Store on local filesystem (configurable path, can be swapped to S3/R2 later)
  - Serve via a static file endpoint or configure static resource serving
  - Update user's profile_pic_url to the served URL
- Public profile should hide sensitive fields (email, notification prefs)
- Public profile should show reputation data (avg rating, review count, completed sales)

## Acceptance Criteria

- GET /api/v1/users/{id} returns profile data for any user (public fields only)
- GET /api/v1/users/me returns full profile for authenticated user
- PUT /api/v1/users/me updates display name and bio
- Profile picture upload accepts valid image files and returns the new URL
- Uploaded images are cropped to square and available in 3 sizes
- Oversized files (>2MB) are rejected with appropriate error
- Non-image files are rejected
- Profile picture URL is accessible and serves the image

## Notes

- For image processing, use a Java library like Thumbnailator or imgscalr - keep it simple.
- Local filesystem storage is fine for now. Put images in a configurable directory (e.g., ./uploads/avatars/{userId}/). This can be migrated to cloud storage later.
- Default profile pic: use a placeholder/generic avatar icon until the user uploads one.
- Don't expose email in public profile responses.
