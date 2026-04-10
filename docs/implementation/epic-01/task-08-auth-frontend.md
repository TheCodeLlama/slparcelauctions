# Task 01-08: Authentication - Frontend Pages

> **Before starting:** read [CONVENTIONS.md](../CONVENTIONS.md) for project-wide rules (no new migrations, Lombok required, vertical slices, feature-based packages).

## Goal

Build the sign-up, sign-in, and forgot-password pages on the frontend and wire them to the backend auth API. Users should be able to register, log in, and navigate as authenticated users.

## Context

The backend JWT auth endpoints exist from Task 01-07. The Next.js layout shell, theme, and component library exist from Task 01-06. Stitch-generated designs for these pages are in:

- `docs/stitch_generated-design/light_mode/sign_up/`, `sign_in/`, `forgot_password/`
- `docs/stitch_generated-design/dark_mode/sign_up/`, `sign_in/`, `forgot_password/`

Read both mode references for each page. Also re-read `docs/stitch_generated-design/DESIGN.md` — auth pages lean heavily on the input and button rules in §5.

**Component reuse is mandatory.** Before writing any new JSX, check `components/ui/` for existing primitives (`Button`, `Input`, `Card`, `Chip`, etc.) built in Task 01-06. If a primitive you need is missing, add it to `components/ui/` as a reusable component first, then use it — do not inline one-off UI into the auth pages. The three auth pages share enormous structural overlap (centered card, logo, title, form, footer link) and must be built from a shared `AuthCard` layout component, not three near-duplicate page bodies.

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
- Pages match the "Digital Curator" aesthetic in both dark and light mode, driven entirely by Tailwind tokens (no hardcoded colors).
- All three auth pages share a common `AuthCard` layout component; no copy-pasted JSX between them.
- All form inputs reuse the `Input` primitive from `components/ui/`; all buttons reuse `Button`.

## Notes

- Match the Stitch designs visually but rebuild as proper React components composed from the existing `components/ui/` primitives.
- The "Remember me" checkbox can control whether the token is stored in localStorage (persistent) vs sessionStorage (tab-only).
- Don't implement actual password reset email flow yet - the forgot password page is just the UI for now.
- If you find yourself writing the same form-field block twice (even across two different pages), extract it.
