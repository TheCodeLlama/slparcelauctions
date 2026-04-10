# Task 01-07: JWT Authentication - Backend

## Goal

Implement JWT-based authentication on the Spring Boot backend: user registration, login, token generation, token validation, and protected endpoint access.

## Context

Spring Security is already a dependency. Task 01-01 set up a placeholder security config. This task replaces that with real JWT auth. The User entity, repository, and service exist from Task 01-04 (Users Vertical Slice).

See [CONVENTIONS.md](../CONVENTIONS.md) for Lombok and vertical-slice requirements. This task builds the `auth` vertical slice.

Note: JJWT is NOT currently in pom.xml - it needs to be added as a dependency.

## What Needs to Happen

- Add JJWT (io.jsonwebtoken) dependency to pom.xml
- All backend classes use Lombok per CONVENTIONS.md (`@RequiredArgsConstructor`, `@Slf4j`, entities with `@Getter @Setter @Builder`)
- Create auth endpoints:
  - POST /api/auth/register - email, password → creates user, returns JWT
  - POST /api/auth/login - email, password → validates credentials, returns JWT + refresh token
  - POST /api/auth/refresh - refresh token → returns new JWT
- Implement JWT token generation with configurable expiration (e.g., 15 min access token, 7 day refresh token)
- Implement JWT validation filter that extracts the token from Authorization header, validates it, and sets the Spring Security context
- Update Spring Security config: permit auth endpoints and health check, require valid JWT for all other /api/** routes
- Password hashing with BCrypt
- Return proper error responses: 401 for invalid/expired tokens, 409 for duplicate email on register, 400 for validation errors

## Acceptance Criteria

- POST /api/auth/register creates a user and returns a valid JWT
- POST /api/auth/login with correct credentials returns a JWT
- POST /api/auth/login with wrong credentials returns 401
- POST /api/auth/register with an existing email returns 409
- A request to a protected endpoint without a token returns 401
- A request to a protected endpoint with a valid token succeeds
- A request with an expired token returns 401
- POST /api/auth/refresh with a valid refresh token returns a new access token
- GET /api/health still works without authentication

## Notes

- JWT secret should come from application config (environment variable in prod).
- Don't implement email verification yet - that's a separate concern. Users are immediately usable after registration.
- The refresh token can be stored in the database or be a separate JWT with longer expiry - either approach is fine.
