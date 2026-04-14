# auth/test — authentication test infrastructure

This package ships the shared test helpers that every protected-endpoint test in the SLPA backend
uses. It's built in Task 01-07 and inherited by Task 01-09 onwards (bid, listing, escrow, etc.).

## Helpers

### `@WithMockAuthPrincipal`

Annotation that inserts an `AuthPrincipal` into the `SecurityContext` for the test method or class.
Use on any slice test (`@WebMvcTest`) or integration test that needs an authenticated caller:

```java
@Test
@WithMockAuthPrincipal(userId = 42)
void placesBid_whenAuthenticated() throws Exception {
    mockMvc.perform(post("/api/v1/bids")...).andExpect(status().isCreated());
}
```

**Defaults:** `userId=1L`, `email="test@example.com"`, `tokenVersion=0L`.

**Convention:** always use the annotation, never `MockMvc.with(authentication(...))` directly.
The annotation form is idiomatic Spring Security testing and keeps test setup terse.

**Wiring:** the annotation uses Spring Security 6+'s `@WithSecurityContext(factory = ...)`
auto-discovery path. No `META-INF/spring.factories` is needed. If a future test shows the
annotation being silently ignored (principal is `null` in the controller), the fallback is to
declare the factory in `META-INF/spring.factories` — but verify the primary path first. See
FOOTGUNS §B.3.

### `JwtTestFactory`

Issues real signed JWTs for tests. Two construction paths:

1. **`@Component` with `@Value("${jwt.secret}")`** — inject via `@Autowired` in slice and
   integration tests. Reads the same secret as the running filter, so tokens it issues validate
   against production code.
2. **`JwtTestFactory.forKey(String secretBase64)` static factory** — for pure-unit tests that
   don't bring up a Spring context (e.g., `JwtServiceTest`, `JwtAuthenticationFilterTest`). The
   test hands in a random secret directly.

Both paths share `JwtKeyFactory.buildKey(...)` so prod and test cannot drift. If the production
key derivation changes, update `JwtKeyFactory` once and both sides follow.

Methods: `validAccessToken`, `validAccessTokenWithLifetime`, `expiredAccessToken`,
`tokenWithWrongType`, `tokenWithBadSignature`, `malformedToken`.

### `RefreshTokenTestFixture`

`@Component` that inserts `RefreshToken` rows for integration tests that need to simulate
pre-existing sessions (e.g., the `logoutAll` test that seeds a "device 2" token). Methods:
`insertValid(userId)`, `insertRevoked(userId)`, `insertExpired(userId)`,
`insertWithExpiry(userId, expiresAt)`. Returns the raw token so tests can replay it through
`/api/v1/auth/refresh`.

Uses `TokenHasher.sha256Hex(...)` to hash the same way production does — changing the hashing
algorithm requires updating both call sites.

## FOOTGUNS references

- §B.1 — `@AuthenticationPrincipal AuthPrincipal principal`, never `UserDetails`
- §B.3 — `WithSecurityContextFactory` wiring in Spring Security 6
- §B.8 — Refresh token raw value never lives in the DB
