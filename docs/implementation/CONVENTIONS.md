# Implementation Conventions

This document defines conventions that apply to **all** tasks in this directory. Individual task files may reference these conventions without repeating them.

---

## Database & Schema

### No more migrations (for now)

**Do not write new Flyway migrations.** Schema evolution in development happens through JPA entity changes with `spring.jpa.hibernate.ddl-auto: update`. When you add or modify an entity, Hibernate updates the schema automatically.

The existing `V1__core_tables.sql` and `V2__supporting_tables.sql` are the baseline schema and will remain in place. Future tasks should:

- Add/modify entities freely - Hibernate handles the DDL
- **Not** create new `V3__*.sql`, `V4__*.sql`, etc. files
- **Not** ask to "add a migration for X" - just add the entity/field and let JPA handle it

**When migrations DO come back:**
1. Things JPA/Hibernate cannot handle natively: custom Postgres types, triggers, stored procedures, complex partial indexes, check constraints that depend on external state
2. Post-production deployments where we need controlled, reversible schema changes

Until then, migrations are off the table. Entities are the source of truth.

### JPA configuration implication

`application-dev.yml` should use `spring.jpa.hibernate.ddl-auto: update` (not `validate`) so entity changes auto-apply to dev Postgres. `application-prod.yml` will use `validate` + migrations when we get to production.

---

## Backend (Java / Spring Boot)

### Lombok is required

Every backend Java class that can benefit from Lombok **must** use it. No hand-written getters, setters, constructors, or loggers unless there's a specific reason not to.

Standard annotations to reach for:

- **Entities:** `@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder`
- **DTOs (if not using records):** `@Data` or `@Value` for immutable
- **Services / Controllers:** `@RequiredArgsConstructor` (constructor injection for final fields), `@Slf4j` (logger)
- **Exception classes:** `@Getter` with `@RequiredArgsConstructor` or plain constructors as needed

Lombok is added to `pom.xml` with `<scope>provided</scope>` and annotation processing enabled in the build.

Records are preferred for simple DTOs (`public record UserResponse(...)`) since records are immutable and need no Lombok at all.

### Vertical slices, not horizontal layers

**Every feature task builds a complete vertical slice:**

1. Entity (with Lombok)
2. Repository (Spring Data JPA interface)
3. DTOs (records for requests/responses)
4. Service (business logic, `@Transactional`, `@Slf4j`)
5. Controller (REST endpoints, `@RequiredArgsConstructor`)
6. Exception handling (domain exceptions + `@RestControllerAdvice` if new)
7. Tests (unit + integration)

**Do not** create a task that only adds entities, or only adds controllers. Each task ships a working, testable feature end-to-end. The User domain is vertical slice #1 (Task 01-04). Subsequent domains follow the same shape.

### Package structure

```
com.slparcelauctions.backend
├── config/          (SecurityConfig, JpaConfig, WebSocketConfig, etc.)
├── common/          (shared exceptions, response wrappers, validators)
├── user/            (User entity, UserRepository, UserService, UserController, UserDto, exceptions)
├── parcel/          (...)
├── auction/         (...)
├── bid/             (...)
├── escrow/          (...)
└── ...
```

Feature-based packages, not layer-based. Each domain is self-contained.

### Testing

Every vertical slice includes three levels of tests:

1. **Unit tests** - Service layer with Mockito for dependencies
2. **Slice tests** - `@WebMvcTest` for controllers with mocked services
3. **Integration tests** - `@SpringBootTest` against a real Postgres (Testcontainers or shared dev container)

All three must pass before the task is considered done.

### JWT auth will land in Task 01-07

Until then, controllers can expose `/me` endpoints with placeholder auth (e.g., a fake authenticated user injected via `@AuthenticationPrincipal` resolver). Do not defer building the domain logic waiting on auth - build the logic, stub the auth.

---

## Frontend (Next.js)

### React Server Components first

Use RSC where possible. Drop to client components only when you need state, effects, or browser APIs.

### TanStack Query for API state

Use TanStack Query (react-query) for all API calls from client components. Provides caching, refetching, and optimistic updates for free.

### Form handling

React Hook Form + Zod for form validation. DTOs from the backend can be mirrored in Zod schemas.

### Styling

Tailwind with the Gilded Slate design tokens. No inline styles. No CSS modules unless there's a specific reason.

### Testing

- Vitest + React Testing Library for component tests
- Playwright for end-to-end (later phase)

---

## Git & PR workflow

- One task = one feature branch = one PR
- Branch naming: `task/XX-YY-short-description`
- Commit messages: conventional commits preferred (`feat:`, `fix:`, `chore:`, `docs:`, `test:`)
- PRs should be reviewable in one sitting - if a task feels too big, split it

---

## Things not to do

- ❌ Don't write new Flyway migrations
- ❌ Don't create layer-only tasks ("just entities" or "just controllers")
- ❌ Don't skip Lombok on backend classes that would benefit
- ❌ Don't defer tests to "a later task"
- ❌ Don't hand-roll what Spring provides (security, validation, serialization)
- ❌ Don't put business logic in controllers - controllers are thin, services own logic
