# Task 01-08: Authentication - Frontend Pages

## Goal

Build the sign-up, sign-in, and forgot-password pages on the frontend and wire them to the backend auth API. Users should be able to register, log in, and navigate as authenticated users.

## Context

The backend JWT auth endpoints exist from Task 01-07. The Next.js layout shell and routing exist from Task 01-06. Stitch-generated designs for these pages are in `docs/stitch-generated-design/sign_up/`, `sign_in/`, and `forgot_password/` - use as visual reference.

## What Needs to Happen

- Build the Sign Up page (`/register`):
  - Email, password, confirm password fields
  - Password strength indicator
  - "Create Account" button
  - Link to sign-in page
  - Calls POST /api/auth/register, stores JWT on success, redirects to dashboard
- Build the Sign In page (`/login`):
  - Email, password fields
  - "Remember me" checkbox
  - "Sign In" button
  - "Forgot password?" link
  - Link to sign-up page
  - Calls POST /api/auth/login, stores JWT on success, redirects to dashboard
- Build the Forgot Password page (`/forgot-password`):
  - Email field + "Send Reset Link" button
  - Success state message (UI only - backend password reset is not implemented yet, just show the confirmation)
  - Link back to sign-in
- Implement JWT token management:
  - Store access token and refresh token (localStorage or httpOnly cookie)
  - Attach token to all API requests via the API client from Task 01-06
  - Auto-refresh token before expiry
  - Redirect to login on 401 responses
- Update the header to show user state:
  - Logged out: Show "Sign In" and "Sign Up" buttons
  - Logged in: Show user avatar placeholder and dropdown with "Sign Out"
- Form validation with inline error messages (red border + text below field)

## Acceptance Criteria

- User can register with email/password and gets redirected to dashboard
- User can sign in with existing credentials and gets redirected to dashboard
- Invalid credentials show an error message on the form
- Duplicate email registration shows an error message
- Password confirmation mismatch is caught before submission
- JWT token is stored and attached to subsequent API requests
- Sign out clears the token and redirects to home
- Header updates to reflect logged-in/logged-out state
- Forgot password page renders correctly (UI only, no backend integration needed)
- Pages match the Stitch design aesthetic (Gilded Slate theme, dark/light mode)

## Notes

- Match the Stitch designs visually but build as proper React components with proper state management.
- The "Remember me" checkbox can control whether the token is stored in localStorage (persistent) vs sessionStorage (tab-only).
- Don't implement actual password reset email flow yet - the forgot password page is just the UI for now.
