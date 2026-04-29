# Implementation Conventions

This document defines conventions that apply to **all** tasks in this directory. Individual task files may reference these conventions without repeating them.

---

## Database & Schema

### Schema changes via Flyway migrations

**Database schema changes go through Flyway migrations**, not JPA `ddl-auto`. Add a new file at `backend/src/main/resources/db/migration/V<N>__<short_description>.sql` where `<N>` is the next sequential integer. Spring Boot runs Flyway at startup before the app accepts requests; failed migrations halt boot.

The current baseline is `V1__initial_schema.sql` (captured via `pg_dump --schema-only` from the dev DB on 2026-04-29). Every schema evolution after that ships as a numbered migration:

- Add or alter a table → write `V<N>__add_xyz_table.sql` (or `alter_xyz_*`)
- Add a partial unique index, GIN index, CHECK constraint Hibernate can't represent → either add to a migration *or* use the existing runtime initializer pattern (`*IndexInitializer` / `*CheckConstraintInitializer`) for backfilling on existing DBs
- Modify an entity → write the migration in the **same commit** as the entity change. Drift between entity and migration causes Hibernate `validate` to fail at boot.

### JPA configuration

`application.yml` and `application-dev.yml` both use `spring.jpa.hibernate.ddl-auto: validate`. `application-prod.yml` inherits `validate` and adds `spring.flyway.baseline-on-migrate: true`. Hibernate no longer modifies the schema in any profile — it only validates that the schema matches the entity model on startup.

Runtime initializer classes (e.g. `ParcelLockingIndexInitializer`, `ProxyBidPartialUniqueIndexInitializer`, the `*CheckConstraintInitializer` family) handle schema features Hibernate cannot represent: partial unique indexes, JSONB GIN indexes, CHECK constraints for enum-backed columns. They run idempotently after Hibernate validation; they are *not* a substitute for migrations on net-new schema changes.

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

### `PagedResponse<T>` — use this, not raw `Page<T>`

All REST endpoints that return paginated data MUST return `PagedResponse<T>` (located at `backend/src/main/java/com/slparcelauctions/backend/common/PagedResponse.java`), NOT `Page<T>` directly.

**Rationale:** Spring Data 3.3+ emits a startup warning — `"Serializing PageImpl instances as-is is not supported, meaning that there is no guarantee about the stability of the resulting JSON structure!"` — and the JSON shape may drift between Spring versions. `PagedResponse<T>` pins a flat `{content, totalElements, totalPages, number, size}` shape matching the frontend's `types/page.ts` type.

**Controller pattern:**

```java
return PagedResponse.from(page.map(dtoMapper::toDto));
```

A reviewer who sees `ResponseEntity<Page<T>>` or `Page<T>` as a controller return type in a new PR should reject it and ask for `PagedResponse<T>`. See FOOTGUNS §F.73 for the full story.

---

## Frontend (Next.js)

### React Server Components first

Use RSC where possible. Drop to client components only when you need state, effects, or browser APIs.

### TanStack Query for API state

Use TanStack Query (react-query) for all API calls from client components. Provides caching, refetching, and optimistic updates for free.

### Form handling

React Hook Form + Zod for form validation. DTOs from the backend can be mirrored in Zod schemas.

### Design system — "The Digital Curator"

The design system lives in [`docs/stitch_generated-design/`](../stitch_generated-design/). It is the single source of truth for visual language. Do not invent new colors, typography, spacing, or components — if you need something, read the design docs first and only extend the system when a genuine gap exists.

- **Strategy document:** [`docs/stitch_generated-design/DESIGN.md`](../stitch_generated-design/DESIGN.md) — read this first. It defines the "Digital Curator" creative north star, the no-line rule, surface hierarchy, typography scale, elevation, and component-level rules (buttons, cards, inputs, chips, curator tray). Treat it as binding.
- **Reference HTML (both modes):**
  - Light mode: [`docs/stitch_generated-design/light_mode/`](../stitch_generated-design/light_mode/)
  - Dark mode: [`docs/stitch_generated-design/dark_mode/`](../stitch_generated-design/dark_mode/)
  - Each subdirectory contains one folder per page (`landing_page/`, `sign_in/`, `sign_up/`, `forgot_password/`, `browse_auctions/`, `auction_detail/`, `user_dashboard/`) with a `code.html` and a `screen.png`.
- **How to use the reference HTML:** Read it for **layout, spacing, typography, and component composition** — not as something to copy-paste. Rebuild everything as proper React components. The HTML is a visual target, not production code.
- **Both modes are first-class.** Every page and every component must work in both dark and light mode. Check both `light_mode/` and `dark_mode/` reference HTML for the page you are building and make sure your implementation matches both. Dark mode is not an afterthought.
- **Tailwind tokens:** Configure Tailwind with the color / typography / spacing tokens from `DESIGN.md`. No inline styles. No CSS modules unless there's a specific reason. No hardcoded hex values in components — reference tokens.

### Modular, component-based architecture (required)

**Almost everything is a component.** The bar for "this deserves to be a component" is very low and the bar for "this should be inlined JSX" is very high.

- If a piece of UI appears in more than one place → **component.**
- If a piece of UI appears in one place but is more than a few lines of markup → **component.**
- If a piece of UI has its own state, props, or conditional rendering → **component.**
- If two pieces of UI are similar but differ in minor details (color, size, icon, label, density) → **one component with props,** not two near-duplicate components. Variants go in props, not in copies.
- If a piece of UI is a natural atomic unit (Button, Badge, Avatar, Chip, Card, InputField, Modal, Dropdown, ListingCard, BidHistoryItem, CountdownTimer, etc.) → **component,** even if used only once today. Someone will reuse it tomorrow.

**Directory layout:**

```
frontend/src/
├── components/
│   ├── ui/              Atomic, reusable primitives (Button, Input, Badge, Card, Avatar, Chip, ...)
│   ├── layout/          Shell pieces (Header, Footer, Nav, MobileMenu, ThemeToggle, ...)
│   ├── auction/         Domain-specific composites (ListingCard, BidHistory, CountdownTimer, ...)
│   ├── user/            Domain-specific composites (ProfileHeader, VerificationBadge, ReputationStars, ...)
│   └── ...              One folder per domain
├── app/                 Next.js routes — pages should be thin, mostly composing components
└── lib/                 API client, utilities, hooks
```

**Rules:**

- **Pages are thin.** An `app/**/page.tsx` file should mostly be importing components and wiring them up. If a page file has a lot of JSX, extract it.
- **Props over duplication.** Two "similar" components almost always indicate one component with a missing prop. Before copying a component, ask: "what single prop distinguishes these?" and add that prop instead.
- **Composition over configuration explosion.** If a component needs 15 props, it probably wants sub-components passed as children (`<Card><Card.Header /><Card.Body /><Card.Footer /></Card>`) instead of a god-prop bag.
- **Variant props follow a convention.** Use `variant="primary" | "secondary" | "ghost"`, `size="sm" | "md" | "lg"`, `tone="default" | "success" | "warning" | "danger"`. Don't invent a new prop shape per component.
- **Every UI primitive gets a Storybook-style demo later.** For now, each primitive component should be usable in isolation without pulling in a full page's worth of context.
- **Every component that takes user input must be controllable.** Support both controlled (`value` + `onChange`) and uncontrolled (`defaultValue`) usage when possible, following React Hook Form's expectations.

**Examples of the split:**

- ✅ One `<Button variant="primary|secondary|tertiary" size="sm|md|lg" loading leftIcon rightIcon />` — not three separate button components.
- ✅ One `<ListingCard listing={listing} variant="compact|default|featured" />` — not separate `CompactListingCard`, `ListingCard`, `FeaturedListingCard`.
- ✅ One `<StatusChip status="active|ending-soon|ended|cancelled" />` — not one chip component per status.
- ✅ One `<PageHeader title subtitle actions breadcrumbs />` — used by every page in the app.
- ❌ Copying a 30-line block of JSX between `/dashboard/page.tsx` and `/profile/page.tsx` because "it's only used twice." Extract.

If you find yourself repeating JSX, stop and make a component. If you find yourself writing a very similar component to one that already exists, stop and add a prop to the existing one.

### Testing

- Vitest + React Testing Library for component tests
- Playwright for end-to-end (later phase)
- Every non-trivial component in `components/ui/` should have at least one test covering its main variants.

---

## Git & PR workflow

- One task = one feature branch = one PR
- Branch naming: `task/XX-YY-short-description`
- Commit messages: conventional commits preferred (`feat:`, `fix:`, `chore:`, `docs:`, `test:`)
- PRs should be reviewable in one sitting - if a task feels too big, split it

---

## Things not to do

- ❌ Don't rely on `ddl-auto: update` to evolve the schema (Hibernate runs in `validate` mode in every profile — schema changes go through Flyway migrations under `backend/src/main/resources/db/migration/`)
- ❌ Don't create layer-only tasks ("just entities" or "just controllers")
- ❌ Don't skip Lombok on backend classes that would benefit
- ❌ Don't defer tests to "a later task"
- ❌ Don't hand-roll what Spring provides (security, validation, serialization)
- ❌ Don't put business logic in controllers - controllers are thin, services own logic
