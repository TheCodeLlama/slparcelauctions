# Task 01-04: Users Vertical Slice

## Goal

Build the complete User domain end-to-end: entity, repository, service, DTOs, controller, and validation. This is the first vertical slice and establishes the pattern for subsequent domains.

## Context

Task 01-01 established the Spring Boot config. This task builds the first working backend feature - user CRUD - so we have something runnable and testable before the rest of the stack gets built.

**No new migrations.** JPA/Hibernate with `ddl-auto: update` handles schema evolution in dev automatically. Migrations are reserved for (a) things JPA can't handle (custom types, triggers, complex indexes) or (b) post-production schema changes. The existing V1/V2 migrations stay as-is - this task simply matches entities to them.

**Lombok is required.** Every entity, DTO, and service class should use Lombok annotations to eliminate boilerplate: `@Data`, `@Getter`, `@Setter`, `@NoArgsConstructor`, `@AllArgsConstructor`, `@Builder`, `@RequiredArgsConstructor`, `@Slf4j` as appropriate. No hand-written getters, setters, or loggers.

## What Needs to Happen

### Entity Layer

- **User entity** mapping to the users table
  - Lombok: `@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder`
  - Primary key: `Long id` (BIGSERIAL)
  - Fields: username, email, password_hash, sl_avatar_uuid, sl_username, sl_display_name, sl_legacy_name, sl_account_age_days, sl_payment_info, sl_verified_at, display_name, bio, profile_pic_url, email_verified, rating_avg, rating_count, auctions_completed, auctions_cancelled, cancellation_count, is_admin, is_suspended, suspension_reason, suspended_until, notification_preferences (JSONB), created_at, updated_at
  - Use `java.util.UUID` for UUID columns
  - Use `java.time.OffsetDateTime` or `Instant` for TIMESTAMPTZ columns
  - JSONB notification_preferences: map as `Map<String, Object>` or a typed record with a `@JdbcTypeCode(SqlTypes.JSON)` annotation (Hibernate 6 handles this natively)
  - `@PrePersist` / `@PreUpdate` hooks set created_at / updated_at, or use Hibernate's `@CreationTimestamp` / `@UpdateTimestamp`

### Repository Layer

- **UserRepository** extending `JpaRepository<User, Long>`
  - `Optional<User> findByEmail(String email)`
  - `Optional<User> findByUsername(String username)`
  - `Optional<User> findBySlAvatarUuid(UUID uuid)`
  - `boolean existsByEmail(String email)`
  - `boolean existsByUsername(String username)`

### DTO Layer

- Use Java records for DTOs (immutable, concise, no Lombok needed)
- **CreateUserRequest** - username, email, password (with Bean Validation: `@NotBlank`, `@Email`, `@Size(min=8)`)
- **UpdateUserRequest** - display_name, bio (all optional)
- **UserResponse** - public-facing fields only (no password_hash, no email for other users' profiles)
- **UserProfileResponse** - extended public profile (rating, completion stats)

### Service Layer

- **UserService** with Lombok `@RequiredArgsConstructor` and `@Slf4j`
  - `UserResponse createUser(CreateUserRequest request)` - hashes password with BCrypt, validates uniqueness
  - `UserResponse getUserById(Long id)` - throws `UserNotFoundException` if missing
  - `UserProfileResponse getPublicProfile(Long id)`
  - `UserResponse updateUser(Long id, UpdateUserRequest request)`
  - `void deleteUser(Long id)` - soft delete or hard delete as appropriate
  - Use `@Transactional` on write methods

### Exception Handling

- **UserNotFoundException** (extends `RuntimeException`)
- **UserAlreadyExistsException** for duplicate email/username
- **GlobalExceptionHandler** with `@RestControllerAdvice` - maps exceptions to `ResponseEntity` with appropriate HTTP status and problem-detail JSON body

### Controller Layer

- **UserController** with `@RestController @RequestMapping("/api/users") @RequiredArgsConstructor`
- `POST /api/users` - create user (returns 201 + UserResponse)
- `GET /api/users/{id}` - get public profile (returns UserProfileResponse)
- `GET /api/users/me` - get current user (authenticated, returns full UserResponse) - placeholder until JWT auth exists
- `PUT /api/users/me` - update current user
- `DELETE /api/users/me` - delete own account

### Password Hashing

- BCrypt via Spring Security's `PasswordEncoder`
- Register `BCryptPasswordEncoder` as a `@Bean` in a `PasswordConfig` class (or extend SecurityConfig)

### Testing

- **UserServiceTest** - unit tests with Mockito for the service layer (create, duplicate email, get by id, update, delete)
- **UserControllerTest** - `@WebMvcTest` with mocked service for controller endpoints
- **UserIntegrationTest** - `@SpringBootTest` with real Postgres (Testcontainers or the dev container), persists and retrieves a user through the full stack
- All three test types should pass

## Acceptance Criteria

- `POST /api/users` creates a user with a hashed password, returns 201 + UserResponse (no password_hash in response)
- `GET /api/users/{id}` returns public profile
- Duplicate email/username returns 409 with problem-detail JSON
- Invalid request (missing fields, bad email format, short password) returns 400 with validation errors
- Password is BCrypt-hashed in the database
- Notification preferences JSONB column round-trips correctly
- All tests pass: unit, controller, integration
- Backend starts and stays running on `./mvnw spring-boot:run`
- Lombok used throughout - no hand-written getters, setters, or constructors

## Notes

- This is the template for every subsequent domain. Get the layers right here and the rest of the epics are cookie-cutter.
- Don't bolt on JWT auth in this task - that's Task 01-07. For now, `/api/users/me` can return 401 or use a placeholder auth resolver.
- Skip realty group and verification logic - those come later. User entity has those columns but nothing reads them yet.
- If Lombok isn't already on the classpath, add it in this task.
