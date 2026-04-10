# Task 01-01: Spring Boot Configuration — Design Spec

_April 9, 2026_

## Goal

Configure the Spring Boot application with PostgreSQL connection, Flyway migration support, CORS, environment-based profiles (dev/prod), a security filter chain placeholder, and a health check endpoint.

## Approach

YAML configuration with profile-specific files (Approach A from brainstorming):
- `application.yml` — shared config
- `application-dev.yml` — local Docker connection strings
- `application-prod.yml` — environment variable placeholders

## Configuration Files

### `application.yml` (shared)

```yaml
server:
  port: 8080

spring:
  application:
    name: slpa-backend
  jpa:
    open-in-view: false
    hibernate:
      ddl-auto: validate
  flyway:
    enabled: true

cors:
  allowed-origin: http://localhost:3000  # default, overridden per profile
```

- `open-in-view: false` — avoids lazy-loading pitfalls outside transactions
- `ddl-auto: validate` — Flyway owns the schema, Hibernate only validates
- `flyway.enabled: true` — explicit in shared config
- Flyway location omitted (default `classpath:db/migration` is correct)
- No `spring.session.store-type` — JWT will be the auth mechanism (Task 01-07), no server-side sessions needed yet

### `application-dev.yml`

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/slpa
    username: slpa
    password: slpa
  data:
    redis:
      host: localhost
      port: 6379

cors:
  allowed-origin: http://localhost:3000

logging:
  level:
    org.hibernate.SQL: DEBUG
    org.hibernate.type.descriptor.sql.BasicBinder: TRACE
```

- Hardcoded credentials for local Docker dev database
- Hibernate SQL logging with bound parameter values (better than `show-sql: true`)
- Redis required (Docker Compose from Task 01-05 provides it)

### `application-prod.yml`

```yaml
spring:
  datasource:
    url: ${SPRING_DATASOURCE_URL}
    username: ${SPRING_DATASOURCE_USERNAME}
    password: ${SPRING_DATASOURCE_PASSWORD}
    driver-class-name: org.postgresql.Driver
  data:
    redis:
      host: ${SPRING_DATA_REDIS_HOST}
      port: ${SPRING_DATA_REDIS_PORT:6379}
  flyway:
    baseline-on-migrate: true

cors:
  allowed-origin: ${CORS_ALLOWED_ORIGIN}
```

- All secrets and hosts via environment variables
- `driver-class-name` hardcoded to avoid auto-detection flakiness in containers
- `baseline-on-migrate: true` as a safety net for pointing Flyway at an existing database (harmless for greenfield)

### Deleted

- `application.properties` — replaced by YAML files

## Security Configuration

**Class:** `com.slparcelauctions.backend.config.SecurityConfig`

`@Configuration` + `@EnableWebSecurity` with a `SecurityFilterChain` bean:

- **CORS:** `.cors(cors -> cors.configurationSource(corsConfigurationSource()))` using a `CorsConfigurationSource` bean
  - Allowed origin from `@Value("${cors.allowed-origin}")`
  - Allowed methods: GET, POST, PUT, DELETE, OPTIONS
  - Allowed headers: `*`
  - `allowCredentials(true)` for future cookie/auth header support
- **CSRF:** Disabled (REST API, JWT auth coming in Task 01-07)
- **Session management:** `SessionCreationPolicy.STATELESS`
- **Authorization:**
  - `/api/health` and `/api/auth/**` — `permitAll()`
  - `anyRequest()` — `permitAll()` as placeholder (Task 01-07 changes to `.authenticated()`)
  - Comment on the `anyRequest()` line indicating this is a placeholder

## Health Check Endpoint

**Class:** `com.slparcelauctions.backend.controller.HealthController`

- `@RestController` + `@RequestMapping("/api")`
- `GET /api/health` → `200 OK` with body `{ "status": "UP" }`
- Returns a simple Map (no dedicated DTO needed)
- Not using Spring Boot Actuator — lightweight custom endpoint at the API prefix

## Flyway Migration Directory

- Ensure `src/main/resources/db/migration/` exists with a `.gitkeep` file
- No migrations yet — empty directory so Flyway starts without errors

## Known Issue: Redis Session Autoconfiguration

The `spring-boot-starter-session-data-redis` dependency in pom.xml may trigger autoconfiguration noise at startup since we're not using server-side sessions. If this happens, the fix is:

```java
@SpringBootApplication(exclude = {RedisSessionAutoConfiguration.class})
```

This is a "fix if it barks" item — don't pre-apply the exclusion.

## Files Created/Modified

| Action | Path (relative to `backend/`) |
|--------|------|
| Delete | `src/main/resources/application.properties` |
| Create | `src/main/resources/application.yml` |
| Create | `src/main/resources/application-dev.yml` |
| Create | `src/main/resources/application-prod.yml` |
| Create | `src/main/java/com/slparcelauctions/backend/config/SecurityConfig.java` |
| Create | `src/main/java/com/slparcelauctions/backend/controller/HealthController.java` |
| Create | `src/main/resources/db/migration/.gitkeep` |

## Acceptance Criteria

- Application starts successfully with `dev` profile when PostgreSQL and Redis are available
- `GET /api/health` returns `200 OK` with `{ "status": "UP" }`
- Flyway runs on startup without errors (empty migration directory)
- CORS allows requests from `http://localhost:3000`
- Spring Security does not block the health endpoint
- Configuration values can be overridden via environment variables for production
