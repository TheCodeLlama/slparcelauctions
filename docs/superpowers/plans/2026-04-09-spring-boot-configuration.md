# Task 01-01: Spring Boot Configuration — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Configure Spring Boot with PostgreSQL, Redis, Flyway, CORS, security filter chain placeholder, and health check endpoint using YAML profile-based configuration.

**Architecture:** Three YAML config files (shared, dev, prod) with a SecurityConfig class for CORS/security and a HealthController for the health endpoint. Flyway owns the schema; Hibernate validates only.

**Tech Stack:** Spring Boot 4.0.5, Java 26, PostgreSQL, Redis, Flyway, Spring Security

---

## File Structure

```
backend/
├── src/main/java/com/slparcelauctions/backend/
│   ├── BackendApplication.java              (exists — no changes unless Redis session autoconfiguration barks)
│   ├── config/
│   │   └── SecurityConfig.java              (CREATE — security filter chain, CORS)
│   └── controller/
│       └── HealthController.java            (CREATE — GET /api/health)
├── src/main/resources/
│   ├── application.properties               (DELETE — replaced by YAML)
│   ├── application.yml                      (CREATE — shared config)
│   ├── application-dev.yml                  (CREATE — local Docker connections)
│   ├── application-prod.yml                 (CREATE — env var placeholders)
│   └── db/migration/
│       └── .gitkeep                         (CREATE — keeps empty dir in git)
└── src/test/java/com/slparcelauctions/backend/
    ├── BackendApplicationTests.java         (exists — no changes)
    ├── controller/
    │   └── HealthControllerTest.java        (CREATE — health endpoint test)
    └── config/
        └── SecurityConfigTest.java          (CREATE — CORS and security tests)
```

---

### Task 1: YAML Configuration Files

**Files:**
- Delete: `backend/src/main/resources/application.properties`
- Create: `backend/src/main/resources/application.yml`
- Create: `backend/src/main/resources/application-dev.yml`
- Create: `backend/src/main/resources/application-prod.yml`
- Create: `backend/src/main/resources/db/migration/.gitkeep`

- [ ] **Step 1: Delete `application.properties`**

```bash
cd C:/Users/heath/Repos/Personal/slpa/backend
rm src/main/resources/application.properties
```

- [ ] **Step 2: Create `application.yml` (shared config)**

Create `backend/src/main/resources/application.yml`:

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
  allowed-origin: http://localhost:3000
```

- [ ] **Step 3: Create `application-dev.yml`**

Create `backend/src/main/resources/application-dev.yml`:

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

- [ ] **Step 4: Create `application-prod.yml`**

Create `backend/src/main/resources/application-prod.yml`:

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

- [ ] **Step 5: Create `.gitkeep` in migration directory**

```bash
touch src/main/resources/db/migration/.gitkeep
```

- [ ] **Step 6: Commit**

```bash
git add -A src/main/resources/
git commit -m "feat(config): add YAML profile-based configuration

Replace application.properties with application.yml, application-dev.yml,
and application-prod.yml. Add empty Flyway migration directory."
```

---

### Task 2: Health Check Endpoint (TDD)

**Files:**
- Create: `backend/src/test/java/com/slparcelauctions/backend/controller/HealthControllerTest.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/controller/HealthController.java`

- [ ] **Step 1: Write the failing test**

Create `backend/src/test/java/com/slparcelauctions/backend/controller/HealthControllerTest.java`:

```java
package com.slparcelauctions.backend.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(HealthController.class)
class HealthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void healthEndpointReturnsUpStatus() throws Exception {
        mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
cd C:/Users/heath/Repos/Personal/slpa/backend
./mvnw test -Dtest=HealthControllerTest -pl .
```

Expected: FAIL — `HealthController` class does not exist.

- [ ] **Step 3: Write the implementation**

Create `backend/src/main/java/com/slparcelauctions/backend/controller/HealthController.java`:

```java
package com.slparcelauctions.backend.controller;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class HealthController {

    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of("status", "UP"));
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

```bash
cd C:/Users/heath/Repos/Personal/slpa/backend
./mvnw test -Dtest=HealthControllerTest -pl .
```

Expected: PASS — `healthEndpointReturnsUpStatus` green.

Note: This test uses `@WebMvcTest` which only loads the web layer, not the full application context. It will not need PostgreSQL or Redis running. If Spring Security autoconfiguration interferes (403 on the endpoint), that gets fixed in Task 3 when we add SecurityConfig. If so, temporarily annotate the test with `@AutoConfigureMockMvc(addFilters = false)` and remove it after Task 3.

- [ ] **Step 5: Commit**

```bash
git add src/test/java/com/slparcelauctions/backend/controller/HealthControllerTest.java
git add src/main/java/com/slparcelauctions/backend/controller/HealthController.java
git commit -m "feat(api): add health check endpoint

GET /api/health returns 200 OK with {\"status\": \"UP\"}."
```

---

### Task 3: Security Configuration (TDD)

**Files:**
- Create: `backend/src/test/java/com/slparcelauctions/backend/config/SecurityConfigTest.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/config/SecurityConfig.java`

- [ ] **Step 1: Write the failing tests**

Create `backend/src/test/java/com/slparcelauctions/backend/config/SecurityConfigTest.java`:

```java
package com.slparcelauctions.backend.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import com.slparcelauctions.backend.controller.HealthController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(HealthController.class)
@Import(SecurityConfig.class)
class SecurityConfigTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void healthEndpointIsAccessibleWithoutAuth() throws Exception {
        mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk());
    }

    @Test
    void corsAllowsRequestsFromConfiguredOrigin() throws Exception {
        mockMvc.perform(options("/api/health")
                        .header("Origin", "http://localhost:3000")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:3000"))
                .andExpect(header().exists("Access-Control-Allow-Methods"));
    }

    @Test
    void corsRejectsRequestsFromUnknownOrigin() throws Exception {
        mockMvc.perform(options("/api/health")
                        .header("Origin", "http://evil.com")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isForbidden());
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

```bash
cd C:/Users/heath/Repos/Personal/slpa/backend
./mvnw test -Dtest=SecurityConfigTest -pl .
```

Expected: FAIL — `SecurityConfig` class does not exist.

- [ ] **Step 3: Write the implementation**

Create `backend/src/main/java/com/slparcelauctions/backend/config/SecurityConfig.java`:

```java
package com.slparcelauctions.backend.config;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Value("${cors.allowed-origin}")
    private String allowedOrigin;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/health", "/api/auth/**").permitAll()
                        // TODO(Task 01-07): Change to .authenticated() when JWT is implemented
                        .anyRequest().permitAll());

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(List.of(allowedOrigin));
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("*"));
        configuration.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

```bash
cd C:/Users/heath/Repos/Personal/slpa/backend
./mvnw test -Dtest=SecurityConfigTest -pl .
```

Expected: PASS — all three tests green.

If any test fails due to Spring Security's default CORS handling, check that the `cors.allowed-origin` property is being injected. The `@WebMvcTest` slice loads `application.yml` from the classpath, which has the default value `http://localhost:3000`.

- [ ] **Step 5: Remove `@AutoConfigureMockMvc(addFilters = false)` from HealthControllerTest if it was added in Task 2**

If the Task 2 test needed `addFilters = false` to pass before SecurityConfig existed, remove that annotation now and re-run:

```bash
cd C:/Users/heath/Repos/Personal/slpa/backend
./mvnw test -Dtest=HealthControllerTest -pl .
```

Expected: PASS — SecurityConfig now permits `/api/health`.

- [ ] **Step 6: Commit**

```bash
git add src/test/java/com/slparcelauctions/backend/config/SecurityConfigTest.java
git add src/main/java/com/slparcelauctions/backend/config/SecurityConfig.java
git commit -m "feat(security): add security filter chain with CORS

Permit-all placeholder for authorization (JWT in Task 01-07).
CORS configured for frontend origin with credentials support."
```

---

### Task 4: Integration Verification

**Files:** No new files — this task verifies everything works together.

This task requires PostgreSQL and Redis running locally. If Docker Compose (Task 01-05) isn't set up yet, start them manually:

```bash
docker run -d --name slpa-postgres -p 5432:5432 \
  -e POSTGRES_DB=slpa -e POSTGRES_USER=slpa -e POSTGRES_PASSWORD=slpa \
  postgres:17

docker run -d --name slpa-redis -p 6379:6379 redis:latest
```

- [ ] **Step 1: Start the application with dev profile**

```bash
cd C:/Users/heath/Repos/Personal/slpa/backend
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

Expected: Application starts successfully. Look for:
- `Started BackendApplication` in logs
- No Flyway errors (it runs with zero migrations and succeeds)
- No Redis connection errors

If you see `RedisConnectionException` or autoconfiguration noise from `spring-boot-starter-session-data-redis`, add the exclusion to `BackendApplication.java`:

```java
import org.springframework.boot.autoconfigure.session.SessionAutoConfiguration;

@SpringBootApplication(exclude = {SessionAutoConfiguration.class})
```

- [ ] **Step 2: Test the health endpoint**

```bash
curl -s http://localhost:8080/api/health
```

Expected: `{"status":"UP"}`

- [ ] **Step 3: Test CORS preflight**

```bash
curl -s -X OPTIONS http://localhost:8080/api/health \
  -H "Origin: http://localhost:3000" \
  -H "Access-Control-Request-Method: GET" \
  -D - -o /dev/null
```

Expected: Response includes:
- `Access-Control-Allow-Origin: http://localhost:3000`
- `Access-Control-Allow-Methods` header listing GET, POST, PUT, DELETE, OPTIONS

- [ ] **Step 4: Test CORS rejection**

```bash
curl -s -X OPTIONS http://localhost:8080/api/health \
  -H "Origin: http://evil.com" \
  -H "Access-Control-Request-Method: GET" \
  -D - -o /dev/null
```

Expected: `403 Forbidden` — no `Access-Control-Allow-Origin` header.

- [ ] **Step 5: Run full test suite**

```bash
cd C:/Users/heath/Repos/Personal/slpa/backend
./mvnw test
```

Expected: All tests pass (HealthControllerTest, SecurityConfigTest, BackendApplicationTests).

Note: `BackendApplicationTests` uses `@SpringBootTest` which loads the full context. It will need PostgreSQL and Redis running. If it fails due to database connection, that's expected — it was a skeleton test from scaffolding. Either ensure Docker containers are running or annotate it to exclude the full context load. The recommended fix is to keep it and have Docker running.

- [ ] **Step 6: Stop Docker containers (if started manually)**

```bash
docker stop slpa-postgres slpa-redis
docker rm slpa-postgres slpa-redis
```

- [ ] **Step 7: Commit any fixes from integration verification**

If any fixes were needed (e.g., session autoconfiguration exclusion):

```bash
git add -A
git commit -m "fix(config): resolve integration issues from startup verification"
```

If no fixes were needed, skip this step.
