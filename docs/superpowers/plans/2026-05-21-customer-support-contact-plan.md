# Customer Support Contact Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship the customer-support feature from `docs/superpowers/specs/2026-05-21-customer-support-contact-design.md` end-to-end. Authed users open tickets via a "Support" entry in the user-dropdown; admins triage from a queue mirroring the dispute admin pattern; replies are threaded with optional admin internal notes; image attachments up to 3 per message.

**Architecture:** New `support` backend package (entities, repos, service, rate limiter, two controllers, exception handler). Reuses the dispute-evidence S3 upload primitive with pending state in Redis + bucket lifecycle rule for orphan cleanup. New `Textarea` UI primitive. New `/support` user route + `/admin/support` admin route. Four new notification categories.

**Tech Stack:** Spring Boot 4 / Java 24 / Flyway / JPA / Lombok / Redis (for attachment pending state); Next.js 16 / React 19 / TypeScript 5 / Tailwind CSS 4; Vitest + RTL; Postman.

**LazyInit class-of-bug defense:** Every controller method that returns an entity-derived DTO is `@Transactional`. Repos' single-row finders use `@EntityGraph(attributePaths = {...})` for the collections the mapper accesses. Integration tests include a non-`@Transactional` regression class per controller surface. This is enforced as a per-task checklist item below — do not skip.

---

## File Structure

**Backend — new files under `backend/src/main/java/com/slparcelauctions/backend/support/`:**

| File | Responsibility |
|---|---|
| `SupportTicket.java` | Entity, extends `BaseMutableEntity`, `@OneToMany` to messages |
| `SupportTicketMessage.java` | Entity, extends `BaseMutableEntity`, `@OneToMany` to attachments |
| `SupportTicketAttachment.java` | Entity, extends `BaseMutableEntity` |
| `SupportTicketStatus.java` | Enum: OPEN, RESOLVED |
| `SupportTicketCategory.java` | Enum: ACCOUNT, BIDDING, LISTING, ESCROW, WALLET, OTHER |
| `SupportTicketAuthorRole.java` | Enum: USER, ADMIN |
| `SupportTicketRepository.java` | Spring Data + JpaSpecificationExecutor + `@EntityGraph` on `findByPublicId` |
| `SupportTicketMessageRepository.java` | Spring Data |
| `SupportTicketAttachmentRepository.java` | Spring Data |
| `SupportTicketRateLimiter.java` | Per-user 5/hour bucket on new-ticket only |
| `SupportTicketService.java` | Create, reply, resolve, reopen, assign, patch-category; orchestrates attachments + notifications |
| `SupportTicketAttachmentService.java` | Pre-upload + promotion + Redis pending state |
| `SupportTicketException.java` | Runtime exception carrying `SupportTicketError` |
| `SupportTicketError.java` | Enum: UNKNOWN_TICKET, NOT_OWNER, INTERNAL_NOTE_FROM_USER, RATE_LIMITED, INVALID_ATTACHMENT, INVALID_CATEGORY, ATTACHMENT_NOT_FOUND |
| `SupportTicketExceptionHandler.java` | `@RestControllerAdvice`, maps to RFC 9457 ProblemDetail |
| `SupportTicketMapper.java` | Entity to DTO mapping helpers |
| `MeSupportTicketController.java` | User-facing endpoints |
| `AdminSupportTicketController.java` | Admin queue + actions |
| `SupportTicketAttachmentController.java` | Pre-upload + signed-URL fetch |
| `dto/SupportTicketDto.java` | Full detail record |
| `dto/SupportTicketSummaryDto.java` | List-row record |
| `dto/SupportTicketMessageDto.java` | Message record |
| `dto/SupportTicketAttachmentDto.java` | Attachment record (public id + dimensions, NOT storage key) |
| `dto/AdminSupportTicketQueueRow.java` | Admin list row |
| `dto/CreateSupportTicketRequest.java` | Create body |
| `dto/ReplySupportTicketRequest.java` | User reply body |
| `dto/AdminReplyRequest.java` | Admin reply body (carries `internalNote`) |
| `dto/AssignTicketRequest.java` | Assign body |
| `dto/PatchTicketRequest.java` | Admin patch body (category only in MVP) |
| `dto/SupportTicketQueueStatsDto.java` | Sidebar badge counters |

**Backend — modifies:**

| File | Change |
|---|---|
| `backend/src/main/resources/db/migration/V42__support_tickets.sql` | new migration |
| `backend/src/main/resources/application.yml` | new `slpa.support.*` keys |
| `backend/src/main/java/com/slparcelauctions/backend/notification/NotificationCategory.java` | add 4 categories |
| `backend/src/main/java/com/slparcelauctions/backend/notification/NotificationPublisher.java` | add 4 methods |
| `backend/src/main/java/com/slparcelauctions/backend/notification/NotificationPublisherImpl.java` | impl + bodies |
| `backend/src/main/java/com/slparcelauctions/backend/notification/NotificationDataBuilder.java` | data builder entries |
| `backend/src/main/java/com/slparcelauctions/backend/notification/slim/SlImLinkResolver.java` | 4 new deep links |
| `backend/src/main/java/com/slparcelauctions/backend/config/SecurityConfig.java` | (no change expected; `/me/**` and `/admin/**` already covered) |

**Frontend — new files:**

| File | Responsibility |
|---|---|
| `frontend/src/components/ui/Textarea.tsx` | new UI primitive |
| `frontend/src/components/ui/Textarea.test.tsx` | sibling test |
| `frontend/src/types/support.ts` | TS types matching DTOs |
| `frontend/src/lib/api/support.ts` | typed API client |
| `frontend/src/hooks/useMySupportTickets.ts` | list query |
| `frontend/src/hooks/useMySupportTicket.ts` | detail query |
| `frontend/src/hooks/useCreateSupportTicket.ts` | mutation |
| `frontend/src/hooks/useReplySupportTicket.ts` | mutation |
| `frontend/src/hooks/useUploadSupportAttachment.ts` | mutation |
| `frontend/src/hooks/admin/useAdminSupportTickets.ts` | admin queue query |
| `frontend/src/hooks/admin/useAdminSupportTicket.ts` | admin detail query |
| `frontend/src/hooks/admin/useAdminSupportReply.ts` | admin reply mutation |
| `frontend/src/hooks/admin/useAdminSupportResolve.ts` | mutation |
| `frontend/src/hooks/admin/useAdminSupportReopen.ts` | mutation |
| `frontend/src/hooks/admin/useAdminSupportAssign.ts` | mutation |
| `frontend/src/hooks/admin/useAdminSupportQueueStats.ts` | polling query (30s) |
| `frontend/src/app/support/page.tsx` | server shell + `<SupportTicketList />` |
| `frontend/src/app/support/new/page.tsx` | `<NewSupportTicketForm />` |
| `frontend/src/app/support/[publicId]/page.tsx` | `<SupportTicketThread />` |
| `frontend/src/app/admin/support/page.tsx` | `<AdminSupportTicketQueue />` |
| `frontend/src/app/admin/support/[publicId]/page.tsx` | `<AdminSupportTicketDetail />` |
| `frontend/src/components/support/SupportTicketList.tsx` | client list component |
| `frontend/src/components/support/SupportTicketList.test.tsx` | RTL tests |
| `frontend/src/components/support/NewSupportTicketForm.tsx` | client form |
| `frontend/src/components/support/NewSupportTicketForm.test.tsx` | RTL tests |
| `frontend/src/components/support/SupportTicketThread.tsx` | client thread |
| `frontend/src/components/support/SupportTicketThread.test.tsx` | RTL tests |
| `frontend/src/components/support/SupportAttachmentDropzone.tsx` | shared dropzone (user + admin reuse) |
| `frontend/src/components/admin/support/AdminSupportTicketQueue.tsx` | admin queue table |
| `frontend/src/components/admin/support/AdminSupportTicketQueue.test.tsx` | RTL tests |
| `frontend/src/components/admin/support/AdminSupportTicketDetail.tsx` | admin thread + composer |
| `frontend/src/components/admin/support/AdminSupportTicketDetail.test.tsx` | RTL tests |

**Frontend — modifies:**

| File | Change |
|---|---|
| `frontend/src/components/layout/UserMenuDropdown.tsx` | add "Support" item |
| `frontend/src/components/layout/MobileMenu.tsx` | add "Support" in footer row |
| `frontend/src/components/admin/AdminShell.tsx` | add sidebar entry with badge |

---

## Task Execution Order

Tasks 1-11 backend; 12 + 13 notifications + auction-style integration helpers; 14-16 controllers; 17 the UI primitive; 18-21 user frontend; 22-24 admin frontend; 25 wraps up (Postman + README + smoke + PR). Sequential dependency graph; execute in order. One implementer subagent at a time.

**Per-task checklist (every task must obey):**

1. TDD: write failing test first when the change has testable behavior; verify the failure; implement; verify green.
2. Commit and **push** before declaring done.
3. For any new controller method that returns an entity-derived DTO: wrap in `@Transactional(readOnly = true)` (read) or `@Transactional` (write).
4. For any new `findByPublicId`-style single-row repo method whose result feeds a mapper: annotate with `@EntityGraph(attributePaths = {...})` listing every collection the mapper will access.
5. For any new controller surface: write a non-`@Transactional` regression test class that exercises the mapper-returning paths.
6. No emojis. No em-dashes in user/admin-visible copy or commit messages. No AI/Claude/Anthropic mentions in commits or PR bodies.
7. Use `Edit` for existing files; `Write` only for new files.

---

### Task 1: V42 Flyway migration + 3 entities + 3 enums + 3 repos + integration test

**Files:**
- Create: `backend/src/main/resources/db/migration/V42__support_tickets.sql`
- Create: `backend/src/main/java/com/slparcelauctions/backend/support/SupportTicket.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/support/SupportTicketMessage.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/support/SupportTicketAttachment.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/support/SupportTicketStatus.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/support/SupportTicketCategory.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/support/SupportTicketAuthorRole.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/support/SupportTicketRepository.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/support/SupportTicketMessageRepository.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/support/SupportTicketAttachmentRepository.java`
- Test: `backend/src/test/java/com/slparcelauctions/backend/support/SupportTicketRepositoryIntegrationTest.java`

- [ ] **Step 1: Write `V42__support_tickets.sql`**

Copy the SQL from spec §2 verbatim. Three tables (`support_tickets`, `support_ticket_messages`, `support_ticket_attachments`), three CHECK constraints on `support_tickets`, one CHECK constraint on `support_ticket_messages`, all indexes from §2 including the partial index on open tickets and the partial index on assigned-admin-id.

- [ ] **Step 2: Write the three enums**

```java
// SupportTicketStatus.java
package com.slparcelauctions.backend.support;
public enum SupportTicketStatus { OPEN, RESOLVED }

// SupportTicketCategory.java
package com.slparcelauctions.backend.support;
public enum SupportTicketCategory { ACCOUNT, BIDDING, LISTING, ESCROW, WALLET, OTHER }

// SupportTicketAuthorRole.java
package com.slparcelauctions.backend.support;
public enum SupportTicketAuthorRole { USER, ADMIN }
```

- [ ] **Step 3: Write `SupportTicket.java`**

Extends `BaseMutableEntity`. `@SuperBuilder`. `@OneToMany(mappedBy = "ticket", cascade = ALL, orphanRemoval = true)` for `messages`. `@ManyToOne(fetch = LAZY)` to `User` for `user`; raw `Long assignedAdminId` (no `@ManyToOne` — we don't need the admin user graph loaded on every ticket read). Columns: `subject` (String, 160), `category` (`@Enumerated(STRING)`), `status` (`@Enumerated(STRING)`, default OPEN), `lastMessageAt` (OffsetDateTime, not-null), `lastMessageAuthor` (`@Enumerated(STRING)`), `resolvedAt` (nullable). Do NOT redeclare BaseMutableEntity fields.

- [ ] **Step 4: Write `SupportTicketMessage.java`**

Extends `BaseMutableEntity`. `@SuperBuilder`. `@ManyToOne(fetch = LAZY)` to `SupportTicket` for `ticket` with `@JsonIgnore`; `@ManyToOne(fetch = LAZY)` to `User` for `authorUser` with `@JsonIgnore`. `@OneToMany(mappedBy = "message", cascade = ALL, orphanRemoval = true)` to `SupportTicketAttachment` for `attachments`. Columns: `authorRole` (`@Enumerated(STRING)`), `body` (TEXT, not-null), `visibleToUser` (boolean, default true).

- [ ] **Step 5: Write `SupportTicketAttachment.java`**

Extends `BaseMutableEntity`. `@SuperBuilder`. `@ManyToOne(fetch = LAZY)` to `SupportTicketMessage` for `message` with `@JsonIgnore`. Columns: `storageKey` (255), `mimeType` (64), `sizeBytes` (Integer), `width` (Integer, nullable), `height` (Integer, nullable).

- [ ] **Step 6: Write the three repositories**

```java
// SupportTicketRepository.java
package com.slparcelauctions.backend.support;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface SupportTicketRepository
        extends JpaRepository<SupportTicket, Long>, JpaSpecificationExecutor<SupportTicket> {

    @EntityGraph(attributePaths = {"messages", "messages.attachments"})
    Optional<SupportTicket> findByPublicId(UUID publicId);

    long countByUserIdAndCreatedAtAfter(long userId, java.time.OffsetDateTime threshold);
}

// SupportTicketMessageRepository.java
package com.slparcelauctions.backend.support;
import org.springframework.data.jpa.repository.JpaRepository;
public interface SupportTicketMessageRepository
        extends JpaRepository<SupportTicketMessage, Long> {}

// SupportTicketAttachmentRepository.java
package com.slparcelauctions.backend.support;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
public interface SupportTicketAttachmentRepository
        extends JpaRepository<SupportTicketAttachment, Long> {
    Optional<SupportTicketAttachment> findByPublicId(UUID publicId);
}
```

- [ ] **Step 7: Write `SupportTicketRepositoryIntegrationTest`**

`@SpringBootTest + @ActiveProfiles("dev") + @TestPropertySource` with scheduler-mute properties (mirror `CouponRepositoryIntegrationTest`). `@Transactional` rollback. Tests:

```java
@Test
void findByPublicId_eagerlyLoadsMessagesAndAttachments() {
    // persist a ticket + 2 messages + 3 attachments under one message
    // em.flush(); em.clear();
    // var loaded = repo.findByPublicId(t.getPublicId()).orElseThrow();
    // assert org.hibernate.Hibernate.isInitialized(loaded.getMessages())
    // assert loaded.getMessages() size 2
    // assert messages[0].getAttachments() loaded (no LazyInit because EntityGraph)
}

@Test
void countByUserIdAndCreatedAtAfter_countsOnlyMatchingRows() {
    // persist 3 tickets for user A: two within last 30 min, one from 2 hours ago
    // persist 1 ticket for user B in last 30 min
    // assert countByUserIdAndCreatedAtAfter(userA, now - 1h) == 2
}
```

- [ ] **Step 8: Run tests**

```bash
cd backend && ./mvnw test -Dtest=SupportTicketRepositoryIntegrationTest
```
Expected: 2/2 green; log line `Successfully applied 1 migration to schema "public", now at version v42`.

- [ ] **Step 9: Commit + push**

```bash
git add backend/src/main/resources/db/migration/V42__support_tickets.sql \
        backend/src/main/java/com/slparcelauctions/backend/support/ \
        backend/src/test/java/com/slparcelauctions/backend/support/SupportTicketRepositoryIntegrationTest.java
git commit -m "feat(support): V42 migration + SupportTicket/Message/Attachment entities + repos"
git push
```

---

### Task 2: SupportTicketException + SupportTicketError + ExceptionHandler

**Files:**
- Create: `backend/src/main/java/com/slparcelauctions/backend/support/SupportTicketError.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/support/SupportTicketException.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/support/SupportTicketExceptionHandler.java`

- [ ] **Step 1: Write `SupportTicketError`**

```java
package com.slparcelauctions.backend.support;

public enum SupportTicketError {
    UNKNOWN_TICKET,
    NOT_OWNER,
    INTERNAL_NOTE_FROM_USER,
    RATE_LIMITED,
    INVALID_ATTACHMENT,
    INVALID_CATEGORY,
    ATTACHMENT_NOT_FOUND
}
```

- [ ] **Step 2: Write `SupportTicketException`**

```java
package com.slparcelauctions.backend.support;
import lombok.Getter;

@Getter
public class SupportTicketException extends RuntimeException {
    private final SupportTicketError code;
    public SupportTicketException(SupportTicketError code) { super(code.name()); this.code = code; }
    public SupportTicketException(SupportTicketError code, String detail) { super(detail); this.code = code; }
}
```

- [ ] **Step 3: Write `SupportTicketExceptionHandler`**

```java
package com.slparcelauctions.backend.support;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(basePackages = "com.slparcelauctions.backend.support")
@Order(Ordered.LOWEST_PRECEDENCE - 100)
public class SupportTicketExceptionHandler {

    @ExceptionHandler(SupportTicketException.class)
    public ResponseEntity<ProblemDetail> handle(SupportTicketException e) {
        HttpStatus status = switch (e.getCode()) {
            case UNKNOWN_TICKET, ATTACHMENT_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case NOT_OWNER -> HttpStatus.FORBIDDEN;
            case INTERNAL_NOTE_FROM_USER, INVALID_ATTACHMENT, INVALID_CATEGORY -> HttpStatus.BAD_REQUEST;
            case RATE_LIMITED -> HttpStatus.TOO_MANY_REQUESTS;
        };
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, e.getMessage());
        pd.setTitle("Support ticket error");
        pd.setProperty("code", e.getCode().name());
        return ResponseEntity.status(status).body(pd);
    }
}
```

- [ ] **Step 4: Run build to confirm compile**

```bash
cd backend && ./mvnw test-compile
```
Expected: BUILD SUCCESS.

- [ ] **Step 5: Commit + push**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/support/SupportTicketError.java \
        backend/src/main/java/com/slparcelauctions/backend/support/SupportTicketException.java \
        backend/src/main/java/com/slparcelauctions/backend/support/SupportTicketExceptionHandler.java
git commit -m "feat(support): error enum + exception + RFC9457 handler"
git push
```

---

### Task 3: DTOs

**Files:**
- Create: `backend/src/main/java/com/slparcelauctions/backend/support/dto/SupportTicketDto.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/support/dto/SupportTicketSummaryDto.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/support/dto/SupportTicketMessageDto.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/support/dto/SupportTicketAttachmentDto.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/support/dto/AdminSupportTicketQueueRow.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/support/dto/CreateSupportTicketRequest.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/support/dto/ReplySupportTicketRequest.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/support/dto/AdminReplyRequest.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/support/dto/AssignTicketRequest.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/support/dto/PatchTicketRequest.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/support/dto/SupportTicketQueueStatsDto.java`

- [ ] **Step 1: Write the DTOs**

```java
// SupportTicketAttachmentDto.java
package com.slparcelauctions.backend.support.dto;
import java.util.UUID;
public record SupportTicketAttachmentDto(
        UUID publicId, String mimeType, int sizeBytes, Integer width, Integer height) {}

// SupportTicketMessageDto.java
package com.slparcelauctions.backend.support.dto;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import com.slparcelauctions.backend.support.SupportTicketAuthorRole;
public record SupportTicketMessageDto(
        UUID publicId, UUID authorPublicId, String authorDisplayName,
        SupportTicketAuthorRole authorRole, String body, boolean visibleToUser,
        OffsetDateTime createdAt, List<SupportTicketAttachmentDto> attachments) {}

// SupportTicketDto.java
package com.slparcelauctions.backend.support.dto;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import com.slparcelauctions.backend.support.SupportTicketCategory;
import com.slparcelauctions.backend.support.SupportTicketStatus;
import com.slparcelauctions.backend.support.SupportTicketAuthorRole;
public record SupportTicketDto(
        UUID publicId, UUID submitterPublicId, String submitterDisplayName,
        String subject, SupportTicketCategory category, SupportTicketStatus status,
        UUID assignedAdminPublicId, String assignedAdminDisplayName,
        OffsetDateTime lastMessageAt, SupportTicketAuthorRole lastMessageAuthor,
        OffsetDateTime resolvedAt, OffsetDateTime createdAt, OffsetDateTime updatedAt,
        List<SupportTicketMessageDto> messages) {}

// SupportTicketSummaryDto.java
package com.slparcelauctions.backend.support.dto;
import java.time.OffsetDateTime;
import java.util.UUID;
import com.slparcelauctions.backend.support.SupportTicketCategory;
import com.slparcelauctions.backend.support.SupportTicketStatus;
import com.slparcelauctions.backend.support.SupportTicketAuthorRole;
public record SupportTicketSummaryDto(
        UUID publicId, String subject, SupportTicketCategory category,
        SupportTicketStatus status, SupportTicketAuthorRole lastMessageAuthor,
        OffsetDateTime lastMessageAt) {}

// AdminSupportTicketQueueRow.java
package com.slparcelauctions.backend.support.dto;
import java.time.OffsetDateTime;
import java.util.UUID;
import com.slparcelauctions.backend.support.SupportTicketCategory;
import com.slparcelauctions.backend.support.SupportTicketStatus;
import com.slparcelauctions.backend.support.SupportTicketAuthorRole;
public record AdminSupportTicketQueueRow(
        UUID publicId, String subject, SupportTicketCategory category,
        SupportTicketStatus status, UUID submitterPublicId, String submitterDisplayName,
        UUID assignedAdminPublicId, String assignedAdminDisplayName,
        SupportTicketAuthorRole lastMessageAuthor, OffsetDateTime lastMessageAt) {}

// CreateSupportTicketRequest.java
package com.slparcelauctions.backend.support.dto;
import java.util.List;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import com.slparcelauctions.backend.support.SupportTicketCategory;
public record CreateSupportTicketRequest(
        @NotBlank @Size(max = 160) String subject,
        @NotNull SupportTicketCategory category,
        @NotBlank @Size(max = 10000) String body,
        List<String> attachmentKeys) {}

// ReplySupportTicketRequest.java
package com.slparcelauctions.backend.support.dto;
import java.util.List;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
public record ReplySupportTicketRequest(
        @NotBlank @Size(max = 10000) String body,
        List<String> attachmentKeys) {}

// AdminReplyRequest.java
package com.slparcelauctions.backend.support.dto;
import java.util.List;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
public record AdminReplyRequest(
        @NotBlank @Size(max = 10000) String body,
        List<String> attachmentKeys,
        Boolean internalNote) {}

// AssignTicketRequest.java
package com.slparcelauctions.backend.support.dto;
import java.util.UUID;
public record AssignTicketRequest(UUID adminPublicId) {}

// PatchTicketRequest.java
package com.slparcelauctions.backend.support.dto;
import com.slparcelauctions.backend.support.SupportTicketCategory;
public record PatchTicketRequest(SupportTicketCategory category) {}

// SupportTicketQueueStatsDto.java
package com.slparcelauctions.backend.support.dto;
public record SupportTicketQueueStatsDto(long openNeedingAdminReply, long openTotal) {}
```

- [ ] **Step 2: Compile**

```bash
cd backend && ./mvnw test-compile
```
Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit + push**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/support/dto/
git commit -m "feat(support): DTOs for ticket + message + attachment + admin queue"
git push
```

---

### Task 4: SupportTicketRateLimiter

**Files:**
- Create: `backend/src/main/java/com/slparcelauctions/backend/support/SupportTicketRateLimiter.java`
- Test: `backend/src/test/java/com/slparcelauctions/backend/support/SupportTicketRateLimiterTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.slparcelauctions.backend.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class SupportTicketRateLimiterTest {

    SupportTicketRepository repo;
    SupportTicketRateLimiter limiter;

    @BeforeEach
    void setUp() {
        repo = mock(SupportTicketRepository.class);
        limiter = new SupportTicketRateLimiter(repo);
        ReflectionTestUtils.setField(limiter, "ticketsPerHour", 5);
    }

    @Test
    void under_cap_does_not_throw() {
        when(repo.countByUserIdAndCreatedAtAfter(anyLong(), any())).thenReturn(4L);
        limiter.assertCanOpenNewTicket(1L);
    }

    @Test
    void at_cap_throws() {
        when(repo.countByUserIdAndCreatedAtAfter(anyLong(), any())).thenReturn(5L);
        assertThatThrownBy(() -> limiter.assertCanOpenNewTicket(1L))
                .isInstanceOf(SupportTicketException.class)
                .extracting("code").isEqualTo(SupportTicketError.RATE_LIMITED);
    }
}
```

- [ ] **Step 2: Run, verify compile failure**

```bash
cd backend && ./mvnw test -Dtest=SupportTicketRateLimiterTest
```
Expected: compile error (class does not exist).

- [ ] **Step 3: Implement the limiter**

```java
package com.slparcelauctions.backend.support;

import java.time.OffsetDateTime;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class SupportTicketRateLimiter {

    private final SupportTicketRepository ticketRepository;

    @Value("${slpa.support.rate-limit.tickets-per-hour:5}")
    private int ticketsPerHour;

    public void assertCanOpenNewTicket(long userId) {
        OffsetDateTime cutoff = OffsetDateTime.now().minusHours(1);
        long created = ticketRepository.countByUserIdAndCreatedAtAfter(userId, cutoff);
        if (created >= ticketsPerHour) {
            throw new SupportTicketException(
                    SupportTicketError.RATE_LIMITED,
                    "Too many new tickets - wait an hour or reply to an existing open ticket");
        }
    }
}
```

- [ ] **Step 4: Run, see green**

```bash
cd backend && ./mvnw test -Dtest=SupportTicketRateLimiterTest
```
Expected: 2/2 green.

- [ ] **Step 5: Commit + push**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/support/SupportTicketRateLimiter.java \
        backend/src/test/java/com/slparcelauctions/backend/support/SupportTicketRateLimiterTest.java
git commit -m "feat(support): per-user new-ticket rate limiter"
git push
```

---

### Task 5: Notification surface (categories + publisher methods + link resolver + bodies)

**Files:**
- Modify: `backend/src/main/java/com/slparcelauctions/backend/notification/NotificationCategory.java`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/notification/NotificationPublisher.java`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/notification/NotificationPublisherImpl.java`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/notification/NotificationDataBuilder.java`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/notification/slim/SlImLinkResolver.java`
- Test: `backend/src/test/java/com/slparcelauctions/backend/support/SupportTicketNotificationDispatchTest.java`

- [ ] **Step 1: Add the four category constants**

Open `NotificationCategory.java` and add (place after the existing `WALLET_*` block, before realty group entries):

```java
SUPPORT_TICKET_ADMIN_REPLIED(NotificationGroup.SYSTEM),
SUPPORT_TICKET_RESOLVED(NotificationGroup.SYSTEM),
SUPPORT_TICKET_OPENED(NotificationGroup.SYSTEM),
SUPPORT_TICKET_USER_REPLIED(NotificationGroup.SYSTEM),
```

- [ ] **Step 2: Add four methods to `NotificationPublisher`**

Append (in a clearly-labelled section near the existing comments):

```java
    // -- Customer support tickets ----------------------------------
    void supportTicketAdminReplied(long userId, UUID ticketPublicId,
                                     String subject, String adminDisplayName);
    void supportTicketResolved(long userId, UUID ticketPublicId, String subject);
    void supportTicketOpened(List<Long> adminUserIds, UUID ticketPublicId,
                              String subject, String submitterDisplayName, String category);
    void supportTicketUserReplied(List<Long> adminUserIds, UUID ticketPublicId,
                                    String subject, String submitterDisplayName);
```

- [ ] **Step 3: Implement in `NotificationPublisherImpl`**

Mirror the existing `walletAdjusted` / `couponGranted` shape. Body strings (no em-dashes, plain hyphens):

```java
// SUPPORT_TICKET_ADMIN_REPLIED body
String body = adminDisplayName + " replied to your support ticket: " + subject
        + ". View at slparcels.com/support/" + ticketPublicId;
// SUPPORT_TICKET_RESOLVED body
String body = "Your support ticket has been marked resolved: " + subject
        + ". View at slparcels.com/support/" + ticketPublicId;
// SUPPORT_TICKET_OPENED (in-app only, body shown in feed)
String body = submitterDisplayName + " opened a new " + category + " support ticket: " + subject;
// SUPPORT_TICKET_USER_REPLIED (in-app only)
String body = submitterDisplayName + " replied to a support ticket: " + subject;
```

Channel routing matches spec §6:
- `SUPPORT_TICKET_ADMIN_REPLIED`, `SUPPORT_TICKET_RESOLVED`: in-app + SL IM, gated by user's `notifySlIm` preference and the SYSTEM group.
- `SUPPORT_TICKET_OPENED`, `SUPPORT_TICKET_USER_REPLIED`: in-app only; the fan-out helper inserts a Notification row per admin user id and skips SL IM dispatch entirely.

Inject `SupportTicketRepository` only if needed for body lookup (the methods accept all required fields as parameters; no repo lookup required).

- [ ] **Step 4: Add data-builder entries in `NotificationDataBuilder`**

Add a small data-map for each category so the in-app feed UI can render `ticketPublicId`, `subject`, etc. without re-parsing the body string. Pattern matches `couponGranted` (which uses `{ "couponPublicId": "...", "code": "..." }`).

```java
// SUPPORT_TICKET_ADMIN_REPLIED
Map<String, Object> data = Map.of(
    "ticketPublicId", ticketPublicId.toString(),
    "subject", subject,
    "adminDisplayName", adminDisplayName
);
```

Similar maps for the other three categories.

- [ ] **Step 5: Add deep-link entries to `SlImLinkResolver`**

The resolver has an exhaustive switch over `NotificationCategory`. Add cases:

```java
case SUPPORT_TICKET_ADMIN_REPLIED, SUPPORT_TICKET_RESOLVED -> "/support/" + ticketPublicId;
case SUPPORT_TICKET_OPENED, SUPPORT_TICKET_USER_REPLIED -> "/admin/support/" + ticketPublicId;
```

If the resolver gets `ticketPublicId` from the data blob (it likely does for coupon `couponPublicId`), extract similarly.

- [ ] **Step 6: Write `SupportTicketNotificationDispatchTest`**

`@SpringBootTest` with `@MockBean` for SL IM dispatcher. Tests:

```java
@Test
void supportTicketAdminReplied_fires_inApp_andSlIm() { ... }

@Test
void supportTicketResolved_fires_inApp_andSlIm() { ... }

@Test
void supportTicketOpened_fires_inApp_only_to_each_admin() { ... }

@Test
void supportTicketUserReplied_fires_inApp_only() { ... }
```

Assert via the `NotificationRepository` count + the mocked SL IM dispatcher's invocation count.

- [ ] **Step 7: Run tests**

```bash
cd backend && ./mvnw test -Dtest=SupportTicketNotificationDispatchTest
```
Expected: 4/4 green.

- [ ] **Step 8: Commit + push**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/notification/ \
        backend/src/test/java/com/slparcelauctions/backend/support/SupportTicketNotificationDispatchTest.java
git commit -m "feat(notify): support ticket categories + publisher methods + deep links"
git push
```

---

### Task 6: SupportTicketMapper

**Files:**
- Create: `backend/src/main/java/com/slparcelauctions/backend/support/SupportTicketMapper.java`

- [ ] **Step 1: Implement the mapper**

```java
package com.slparcelauctions.backend.support;

import java.util.List;
import org.springframework.stereotype.Component;
import com.slparcelauctions.backend.support.dto.*;
import com.slparcelauctions.backend.user.User;
import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class SupportTicketMapper {

    public SupportTicketAttachmentDto toAttachmentDto(SupportTicketAttachment a) {
        return new SupportTicketAttachmentDto(
                a.getPublicId(), a.getMimeType(), a.getSizeBytes(),
                a.getWidth(), a.getHeight());
    }

    public SupportTicketMessageDto toMessageDto(SupportTicketMessage m) {
        User author = m.getAuthorUser();
        List<SupportTicketAttachmentDto> atts = m.getAttachments().stream()
                .map(this::toAttachmentDto).toList();
        return new SupportTicketMessageDto(
                m.getPublicId(),
                author.getPublicId(),
                author.getDisplayName() == null ? author.getUsername() : author.getDisplayName(),
                m.getAuthorRole(), m.getBody(), m.isVisibleToUser(),
                m.getCreatedAt(), atts);
    }

    /** Map for the user-facing detail. Filters out internal notes (visibleToUser=false). */
    public SupportTicketDto toUserDto(SupportTicket t, User assignedAdmin) {
        List<SupportTicketMessageDto> msgs = t.getMessages().stream()
                .filter(SupportTicketMessage::isVisibleToUser)
                .map(this::toMessageDto)
                .toList();
        return buildDto(t, assignedAdmin, msgs);
    }

    /** Admin-facing detail. Includes every message (internal notes shown). */
    public SupportTicketDto toAdminDto(SupportTicket t, User assignedAdmin) {
        List<SupportTicketMessageDto> msgs = t.getMessages().stream()
                .map(this::toMessageDto)
                .toList();
        return buildDto(t, assignedAdmin, msgs);
    }

    private SupportTicketDto buildDto(SupportTicket t, User assignedAdmin,
                                       List<SupportTicketMessageDto> msgs) {
        User submitter = t.getUser();
        return new SupportTicketDto(
                t.getPublicId(),
                submitter.getPublicId(),
                submitter.getDisplayName() == null ? submitter.getUsername() : submitter.getDisplayName(),
                t.getSubject(), t.getCategory(), t.getStatus(),
                assignedAdmin == null ? null : assignedAdmin.getPublicId(),
                assignedAdmin == null ? null :
                        (assignedAdmin.getDisplayName() == null ? assignedAdmin.getUsername() : assignedAdmin.getDisplayName()),
                t.getLastMessageAt(), t.getLastMessageAuthor(),
                t.getResolvedAt(), t.getCreatedAt(), t.getUpdatedAt(), msgs);
    }

    public SupportTicketSummaryDto toSummaryDto(SupportTicket t) {
        return new SupportTicketSummaryDto(
                t.getPublicId(), t.getSubject(), t.getCategory(), t.getStatus(),
                t.getLastMessageAuthor(), t.getLastMessageAt());
    }

    public AdminSupportTicketQueueRow toAdminRow(SupportTicket t, User assignedAdmin) {
        User submitter = t.getUser();
        return new AdminSupportTicketQueueRow(
                t.getPublicId(), t.getSubject(), t.getCategory(), t.getStatus(),
                submitter.getPublicId(),
                submitter.getDisplayName() == null ? submitter.getUsername() : submitter.getDisplayName(),
                assignedAdmin == null ? null : assignedAdmin.getPublicId(),
                assignedAdmin == null ? null :
                        (assignedAdmin.getDisplayName() == null ? assignedAdmin.getUsername() : assignedAdmin.getDisplayName()),
                t.getLastMessageAuthor(), t.getLastMessageAt());
    }
}
```

- [ ] **Step 2: Compile**

```bash
cd backend && ./mvnw test-compile
```
Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit + push**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/support/SupportTicketMapper.java
git commit -m "feat(support): mapper for user-side + admin-side DTOs (filters internal notes)"
git push
```

---

### Task 7: SupportTicketAttachmentService (pre-upload + promotion)

**Files:**
- Create: `backend/src/main/java/com/slparcelauctions/backend/support/SupportTicketAttachmentService.java`
- Test: `backend/src/test/java/com/slparcelauctions/backend/support/SupportTicketAttachmentServiceTest.java`

- [ ] **Step 1: Implement the service**

```java
package com.slparcelauctions.backend.support;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.slparcelauctions.backend.media.ImageUploadValidator;
import com.slparcelauctions.backend.storage.S3ObjectStorageService;
import com.slparcelauctions.backend.user.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class SupportTicketAttachmentService {

    private final S3ObjectStorageService storage;
    private final ImageUploadValidator imageValidator;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    @Value("${slpa.support.attachments.max-file-bytes:5242880}")
    private long maxFileBytes;

    @Value("${slpa.support.attachments.allowed-mime-types:image/png,image/jpeg,image/webp,image/gif}")
    private String allowedMimeTypesCsv;

    @Value("${slpa.support.attachments.max-per-message:3}")
    private int maxPerMessage;

    @Value("${slpa.support.attachments.pending-ttl-seconds:3600}")
    private long pendingTtlSeconds;

    public String preUpload(User uploader, MultipartFile file) {
        if (file.getSize() > maxFileBytes) {
            throw new SupportTicketException(SupportTicketError.INVALID_ATTACHMENT,
                    "file exceeds " + maxFileBytes + " bytes");
        }
        List<String> allowed = List.of(allowedMimeTypesCsv.split(","));
        if (!allowed.contains(file.getContentType())) {
            throw new SupportTicketException(SupportTicketError.INVALID_ATTACHMENT,
                    "mime " + file.getContentType() + " not in allowlist");
        }
        var dims = imageValidator.validate(file);  // returns width + height or throws
        String ext = switch (file.getContentType()) {
            case "image/png" -> "png";
            case "image/jpeg" -> "jpg";
            case "image/webp" -> "webp";
            case "image/gif" -> "gif";
            default -> "bin";
        };
        String attachmentKey = UUID.randomUUID().toString();
        String storageKey = "support-attachments/pending/" + uploader.getPublicId() + "/" + attachmentKey + "." + ext;
        storage.uploadBytes(storageKey, file.getBytes(), file.getContentType());

        PendingAttachment pending = new PendingAttachment(
                uploader.getId(), storageKey, file.getContentType(),
                (int) file.getSize(), dims.width(), dims.height());
        try {
            redis.opsForValue().set("support:upload:" + attachmentKey,
                    objectMapper.writeValueAsString(pending),
                    Duration.ofSeconds(pendingTtlSeconds));
        } catch (Exception e) {
            // best-effort: try to delete the S3 object we just wrote
            try { storage.delete(storageKey); } catch (Exception ignored) {}
            throw new RuntimeException("failed to cache pending attachment", e);
        }
        return attachmentKey;
    }

    /**
     * Promote a list of pending attachment keys onto a persisted message.
     * Must be called inside the same transaction as the message insert.
     * On any post-copy failure, the just-copied promoted objects are best-effort deleted.
     */
    @Transactional
    public List<SupportTicketAttachment> promote(
            List<String> attachmentKeys, long expectedOwnerId,
            SupportTicketMessage targetMessage,
            SupportTicketAttachmentRepository repo) {
        if (attachmentKeys == null || attachmentKeys.isEmpty()) return List.of();
        if (attachmentKeys.size() > maxPerMessage) {
            throw new SupportTicketException(SupportTicketError.INVALID_ATTACHMENT,
                    "max " + maxPerMessage + " attachments per message");
        }
        java.util.List<String> promotedKeysForCleanup = new java.util.ArrayList<>();
        try {
            List<SupportTicketAttachment> result = new java.util.ArrayList<>();
            for (String key : attachmentKeys) {
                String json = redis.opsForValue().get("support:upload:" + key);
                if (json == null) {
                    throw new SupportTicketException(SupportTicketError.ATTACHMENT_NOT_FOUND,
                            "attachment " + key + " missing or expired");
                }
                PendingAttachment p = objectMapper.readValue(json, PendingAttachment.class);
                if (p.userId() != expectedOwnerId) {
                    throw new SupportTicketException(SupportTicketError.NOT_OWNER,
                            "attachment " + key + " not owned by caller");
                }
                String promotedKey = "support-attachments/" + targetMessage.getId() + "/" + key;
                storage.copy(p.storageKey(), promotedKey);
                promotedKeysForCleanup.add(promotedKey);
                storage.delete(p.storageKey());
                SupportTicketAttachment att = SupportTicketAttachment.builder()
                        .message(targetMessage).storageKey(promotedKey)
                        .mimeType(p.mime()).sizeBytes(p.size())
                        .width(p.width()).height(p.height()).build();
                result.add(repo.save(att));
                redis.delete("support:upload:" + key);
            }
            return result;
        } catch (RuntimeException ex) {
            // Best-effort cleanup of any objects we already copied to the promoted path.
            for (String k : promotedKeysForCleanup) {
                try { storage.delete(k); } catch (Exception ignored) {}
            }
            throw ex;
        } catch (Exception ex) {
            for (String k : promotedKeysForCleanup) {
                try { storage.delete(k); } catch (Exception ignored) {}
            }
            throw new RuntimeException(ex);
        }
    }

    public String signedDownloadUrl(SupportTicketAttachment att) {
        return storage.signedUrl(att.getStorageKey(), Duration.ofMinutes(5));
    }

    private record PendingAttachment(long userId, String storageKey, String mime,
                                       int size, int width, int height) {}
}
```

If `S3ObjectStorageService` doesn't have a `copy(srcKey, dstKey)` method, add one (server-side copy via the S3 SDK). Check the existing method names before assuming.

If `ImageUploadValidator.validate(MultipartFile)` doesn't return `{width, height}` as a record, adapt to the actual return type and capture width / height separately.

- [ ] **Step 2: Write `SupportTicketAttachmentServiceTest`**

`@SpringBootTest` with `@MockBean S3ObjectStorageService` and a real `StringRedisTemplate` (the dev profile has Redis). Tests:

```java
@Test
void preUpload_rejects_over_size() { ... }   // assert SupportTicketException with INVALID_ATTACHMENT
@Test
void preUpload_rejects_disallowed_mime() { ... }
@Test
void preUpload_happy_path_returns_key_and_caches_in_redis() { ... }
@Test
void promote_unknown_key_throws_ATTACHMENT_NOT_FOUND() { ... }
@Test
void promote_owner_mismatch_throws_NOT_OWNER() { ... }
@Test
void promote_happy_path_copies_object_and_inserts_row() { ... }
@Test
void promote_over_max_throws_INVALID_ATTACHMENT() { ... }
@Test
void promote_db_failure_after_copy_cleans_up_promoted_object() {
    // mock repo.save to throw; assert storage.delete was called on the promoted key
}
```

- [ ] **Step 3: Run tests**

```bash
cd backend && ./mvnw test -Dtest=SupportTicketAttachmentServiceTest
```
Expected: 8/8 green.

- [ ] **Step 4: Commit + push**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/support/SupportTicketAttachmentService.java \
        backend/src/test/java/com/slparcelauctions/backend/support/SupportTicketAttachmentServiceTest.java
git commit -m "feat(support): attachment service (pre-upload + promote with cleanup-on-failure)"
git push
```

---

### Task 8: SupportTicketService core (create + reply + helpers)

**Files:**
- Create: `backend/src/main/java/com/slparcelauctions/backend/support/SupportTicketService.java`
- Test: `backend/src/test/java/com/slparcelauctions/backend/support/SupportTicketServiceCreateReplyTest.java`

- [ ] **Step 1: Implement the service (create + reply only — resolve / reopen / assign land in Task 9)**

```java
package com.slparcelauctions.backend.support;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.slparcelauctions.backend.notification.NotificationPublisher;
import com.slparcelauctions.backend.support.dto.*;
import com.slparcelauctions.backend.user.Role;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class SupportTicketService {

    private final SupportTicketRepository ticketRepo;
    private final SupportTicketMessageRepository messageRepo;
    private final SupportTicketAttachmentRepository attachmentRepo;
    private final SupportTicketAttachmentService attachmentService;
    private final SupportTicketRateLimiter rateLimiter;
    private final NotificationPublisher notifications;
    private final UserRepository userRepo;

    public SupportTicket createTicket(long submitterUserId, CreateSupportTicketRequest req) {
        rateLimiter.assertCanOpenNewTicket(submitterUserId);
        User submitter = userRepo.findById(submitterUserId).orElseThrow(() ->
                new SupportTicketException(SupportTicketError.UNKNOWN_TICKET, "submitter missing"));

        OffsetDateTime now = OffsetDateTime.now();
        SupportTicket ticket = SupportTicket.builder()
                .user(submitter)
                .subject(req.subject().trim())
                .category(req.category())
                .status(SupportTicketStatus.OPEN)
                .lastMessageAt(now)
                .lastMessageAuthor(SupportTicketAuthorRole.USER)
                .build();
        ticket = ticketRepo.save(ticket);

        SupportTicketMessage initial = SupportTicketMessage.builder()
                .ticket(ticket).authorUser(submitter)
                .authorRole(SupportTicketAuthorRole.USER)
                .body(req.body().trim())
                .visibleToUser(true)
                .build();
        initial = messageRepo.save(initial);

        if (req.attachmentKeys() != null && !req.attachmentKeys().isEmpty()) {
            attachmentService.promote(req.attachmentKeys(), submitter.getId(), initial, attachmentRepo);
        }

        List<Long> adminIds = userRepo.findIdsByRole(Role.ADMIN);
        notifications.supportTicketOpened(adminIds, ticket.getPublicId(),
                ticket.getSubject(),
                submitter.getDisplayName() == null ? submitter.getUsername() : submitter.getDisplayName(),
                ticket.getCategory().name());

        return ticket;
    }

    public SupportTicketMessage userReply(long callerUserId, UUID ticketPublicId,
                                            ReplySupportTicketRequest req) {
        SupportTicket ticket = ticketRepo.findByPublicId(ticketPublicId)
                .orElseThrow(() -> new SupportTicketException(SupportTicketError.UNKNOWN_TICKET));
        if (!ticket.getUser().getId().equals(callerUserId)) {
            throw new SupportTicketException(SupportTicketError.NOT_OWNER);
        }
        User caller = ticket.getUser();
        return appendMessage(ticket, caller, SupportTicketAuthorRole.USER,
                req.body(), req.attachmentKeys(), true);
    }

    public SupportTicketMessage adminReply(long adminUserId, UUID ticketPublicId,
                                             AdminReplyRequest req) {
        SupportTicket ticket = ticketRepo.findByPublicId(ticketPublicId)
                .orElseThrow(() -> new SupportTicketException(SupportTicketError.UNKNOWN_TICKET));
        User admin = userRepo.findById(adminUserId).orElseThrow(() ->
                new SupportTicketException(SupportTicketError.UNKNOWN_TICKET, "admin missing"));
        boolean internal = Boolean.TRUE.equals(req.internalNote());
        return appendMessage(ticket, admin, SupportTicketAuthorRole.ADMIN,
                req.body(), req.attachmentKeys(), !internal);
    }

    private SupportTicketMessage appendMessage(SupportTicket ticket, User author,
                                                 SupportTicketAuthorRole role,
                                                 String body, List<String> attachmentKeys,
                                                 boolean visibleToUser) {
        OffsetDateTime now = OffsetDateTime.now();
        SupportTicketMessage msg = SupportTicketMessage.builder()
                .ticket(ticket).authorUser(author)
                .authorRole(role)
                .body(body.trim())
                .visibleToUser(visibleToUser)
                .build();
        msg = messageRepo.save(msg);

        if (attachmentKeys != null && !attachmentKeys.isEmpty()) {
            attachmentService.promote(attachmentKeys, author.getId(), msg, attachmentRepo);
        }

        if (visibleToUser) {
            ticket.setLastMessageAt(now);
            ticket.setLastMessageAuthor(role);
            // Auto-reopen only on user reply to a resolved ticket.
            if (role == SupportTicketAuthorRole.USER && ticket.getStatus() == SupportTicketStatus.RESOLVED) {
                ticket.setStatus(SupportTicketStatus.OPEN);
                ticket.setResolvedAt(null);
            }

            // Notifications for visible messages only.
            if (role == SupportTicketAuthorRole.ADMIN) {
                User submitter = ticket.getUser();
                notifications.supportTicketAdminReplied(
                        submitter.getId(), ticket.getPublicId(), ticket.getSubject(),
                        author.getDisplayName() == null ? author.getUsername() : author.getDisplayName());
            } else {
                List<Long> adminIds = userRepo.findIdsByRole(Role.ADMIN);
                notifications.supportTicketUserReplied(
                        adminIds, ticket.getPublicId(), ticket.getSubject(),
                        author.getDisplayName() == null ? author.getUsername() : author.getDisplayName());
            }
        }
        // Internal notes are silent and do not bump lastMessageAt.
        return msg;
    }
}
```

If `UserRepository.findIdsByRole(Role)` doesn't exist, add it as a derived query. Mirror the existing `findByRole` query if present; otherwise:

```java
@Query("SELECT u.id FROM User u WHERE u.role = :role")
List<Long> findIdsByRole(@Param("role") Role role);
```

- [ ] **Step 2: Write `SupportTicketServiceCreateReplyTest`**

`@SpringBootTest` + `@ActiveProfiles("dev")` + scheduler-mute. Tests:

```java
@Test
void createTicket_inserts_row_and_initial_message_and_notifies_admins() { ... }

@Test
void createTicket_propagates_rate_limit() {
    // create 5 tickets; 6th throws RATE_LIMITED
}

@Test
void createTicket_persists_attachments_when_provided() { ... }

@Test
void userReply_throws_NOT_OWNER_when_caller_is_not_submitter() { ... }

@Test
void userReply_visible_message_updates_lastMessageAt_and_lastMessageAuthor() { ... }

@Test
void userReply_on_resolved_ticket_auto_reopens() { ... }

@Test
void userReply_fires_supportTicketUserReplied_to_all_admins() {
    // mock NotificationPublisher; assert called once with the admin id list
}

@Test
void adminReply_visible_fires_supportTicketAdminReplied_to_submitter() { ... }

@Test
void adminReply_internalNote_does_not_update_lastMessageAt_or_fire_notification() { ... }
```

- [ ] **Step 3: Run tests**

```bash
cd backend && ./mvnw test -Dtest=SupportTicketServiceCreateReplyTest
```
Expected: 9/9 green.

- [ ] **Step 4: Commit + push**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/support/SupportTicketService.java \
        backend/src/main/java/com/slparcelauctions/backend/user/UserRepository.java \
        backend/src/test/java/com/slparcelauctions/backend/support/SupportTicketServiceCreateReplyTest.java
git commit -m "feat(support): create + reply flow with auto-reopen + notification dispatch"
git push
```

---

### Task 9: SupportTicketService admin actions (resolve / reopen / assign / patch / queue-stats)

**Files:**
- Modify: `backend/src/main/java/com/slparcelauctions/backend/support/SupportTicketService.java`
- Test: `backend/src/test/java/com/slparcelauctions/backend/support/SupportTicketServiceAdminActionsTest.java`

- [ ] **Step 1: Add methods to `SupportTicketService`**

```java
public SupportTicket resolve(long adminUserId, UUID ticketPublicId) {
    SupportTicket ticket = ticketRepo.findByPublicId(ticketPublicId)
            .orElseThrow(() -> new SupportTicketException(SupportTicketError.UNKNOWN_TICKET));
    if (ticket.getStatus() == SupportTicketStatus.RESOLVED) return ticket;  // idempotent

    OffsetDateTime now = OffsetDateTime.now();
    User admin = userRepo.findById(adminUserId).orElseThrow(() ->
            new SupportTicketException(SupportTicketError.UNKNOWN_TICKET, "admin missing"));
    ticket.setStatus(SupportTicketStatus.RESOLVED);
    ticket.setResolvedAt(now);

    SupportTicketMessage system = SupportTicketMessage.builder()
            .ticket(ticket).authorUser(admin)
            .authorRole(SupportTicketAuthorRole.ADMIN)
            .body("Marked resolved by admin")
            .visibleToUser(true)
            .build();
    messageRepo.save(system);
    ticket.setLastMessageAt(now);
    ticket.setLastMessageAuthor(SupportTicketAuthorRole.ADMIN);

    User submitter = ticket.getUser();
    notifications.supportTicketResolved(submitter.getId(), ticket.getPublicId(), ticket.getSubject());
    return ticket;
}

public SupportTicket reopen(long adminUserId, UUID ticketPublicId) {
    SupportTicket ticket = ticketRepo.findByPublicId(ticketPublicId)
            .orElseThrow(() -> new SupportTicketException(SupportTicketError.UNKNOWN_TICKET));
    if (ticket.getStatus() == SupportTicketStatus.OPEN) return ticket;

    OffsetDateTime now = OffsetDateTime.now();
    User admin = userRepo.findById(adminUserId).orElseThrow(() ->
            new SupportTicketException(SupportTicketError.UNKNOWN_TICKET, "admin missing"));
    ticket.setStatus(SupportTicketStatus.OPEN);
    ticket.setResolvedAt(null);

    SupportTicketMessage system = SupportTicketMessage.builder()
            .ticket(ticket).authorUser(admin)
            .authorRole(SupportTicketAuthorRole.ADMIN)
            .body("Reopened by admin")
            .visibleToUser(true)
            .build();
    messageRepo.save(system);
    ticket.setLastMessageAt(now);
    ticket.setLastMessageAuthor(SupportTicketAuthorRole.ADMIN);
    return ticket;
}

public SupportTicket assign(UUID ticketPublicId, UUID adminPublicId) {
    SupportTicket ticket = ticketRepo.findByPublicId(ticketPublicId)
            .orElseThrow(() -> new SupportTicketException(SupportTicketError.UNKNOWN_TICKET));
    if (adminPublicId == null) {
        ticket.setAssignedAdminId(null);
        return ticket;
    }
    User admin = userRepo.findByPublicId(adminPublicId).orElseThrow(() ->
            new SupportTicketException(SupportTicketError.UNKNOWN_TICKET, "admin missing"));
    if (admin.getRole() != Role.ADMIN) {
        throw new SupportTicketException(SupportTicketError.UNKNOWN_TICKET, "user is not an admin");
    }
    ticket.setAssignedAdminId(admin.getId());
    return ticket;
}

public SupportTicket patchCategory(UUID ticketPublicId, SupportTicketCategory category) {
    if (category == null) {
        throw new SupportTicketException(SupportTicketError.INVALID_CATEGORY, "category required");
    }
    SupportTicket ticket = ticketRepo.findByPublicId(ticketPublicId)
            .orElseThrow(() -> new SupportTicketException(SupportTicketError.UNKNOWN_TICKET));
    ticket.setCategory(category);
    return ticket;
}

@Transactional(readOnly = true)
public SupportTicketQueueStatsDto queueStats() {
    long openNeedingAdminReply = ticketRepo.count((root, cq, cb) -> cb.and(
            cb.equal(root.get("status"), SupportTicketStatus.OPEN),
            cb.equal(root.get("lastMessageAuthor"), SupportTicketAuthorRole.USER)));
    long openTotal = ticketRepo.count((root, cq, cb) ->
            cb.equal(root.get("status"), SupportTicketStatus.OPEN));
    return new SupportTicketQueueStatsDto(openNeedingAdminReply, openTotal);
}
```

`UserRepository.findByPublicId(UUID)` already exists (used by realty groups etc.).

- [ ] **Step 2: Write tests**

```java
@Test
void resolve_idempotent_when_already_resolved() { ... }

@Test
void resolve_writes_system_message_and_fires_notification() { ... }

@Test
void reopen_idempotent_when_already_open() { ... }

@Test
void reopen_writes_system_message_and_no_notification() { ... }

@Test
void assign_with_valid_admin_publicId_sets_assignedAdminId() { ... }

@Test
void assign_with_null_unassigns() { ... }

@Test
void assign_rejects_non_admin_user() { ... }

@Test
void patchCategory_updates_value() { ... }

@Test
void queueStats_counts_open_total_and_needing_admin_reply() {
    // seed: 2 OPEN with last=USER, 1 OPEN with last=ADMIN, 1 RESOLVED
    // assert openTotal=3, openNeedingAdminReply=2
}
```

- [ ] **Step 3: Run tests**

```bash
cd backend && ./mvnw test -Dtest=SupportTicketServiceAdminActionsTest
```
Expected: 9/9 green.

- [ ] **Step 4: Commit + push**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/support/SupportTicketService.java \
        backend/src/test/java/com/slparcelauctions/backend/support/SupportTicketServiceAdminActionsTest.java
git commit -m "feat(support): admin actions - resolve + reopen + assign + patch + queue-stats"
git push
```

---

### Task 10: SupportTicketService search / list (admin queue spec + user paginated list)

**Files:**
- Modify: `backend/src/main/java/com/slparcelauctions/backend/support/SupportTicketService.java`
- Test: `backend/src/test/java/com/slparcelauctions/backend/support/SupportTicketServiceListTest.java`

- [ ] **Step 1: Add list methods**

```java
@Transactional(readOnly = true)
public Page<SupportTicket> listForUser(long userId, SupportTicketStatus status,
                                         String q, Pageable pageable) {
    Specification<SupportTicket> spec = (root, cq, cb) -> cb.equal(root.get("user").get("id"), userId);
    if (status != null) {
        Specification<SupportTicket> finalSpec = spec;
        spec = (root, cq, cb) -> cb.and(
                finalSpec.toPredicate(root, cq, cb),
                cb.equal(root.get("status"), status));
    }
    if (q != null && !q.isBlank()) {
        Specification<SupportTicket> finalSpec = spec;
        spec = (root, cq, cb) -> cb.and(
                finalSpec.toPredicate(root, cq, cb),
                cb.like(cb.lower(root.get("subject")), "%" + q.toLowerCase() + "%"));
    }
    return ticketRepo.findAll(spec, pageable);
}

@Transactional(readOnly = true)
public Page<SupportTicket> listAdmin(SupportTicketStatus status, SupportTicketCategory category,
                                       String assignee, SupportTicketAuthorRole lastAuthor,
                                       String q, long callerAdminId, Pageable pageable) {
    Specification<SupportTicket> spec = (root, cq, cb) -> cb.conjunction();
    if (status != null) {
        Specification<SupportTicket> p = spec;
        spec = (root, cq, cb) -> cb.and(p.toPredicate(root, cq, cb), cb.equal(root.get("status"), status));
    }
    if (category != null) {
        Specification<SupportTicket> p = spec;
        spec = (root, cq, cb) -> cb.and(p.toPredicate(root, cq, cb), cb.equal(root.get("category"), category));
    }
    if (lastAuthor != null) {
        Specification<SupportTicket> p = spec;
        spec = (root, cq, cb) -> cb.and(p.toPredicate(root, cq, cb), cb.equal(root.get("lastMessageAuthor"), lastAuthor));
    }
    if (assignee != null && !assignee.isBlank()) {
        Specification<SupportTicket> p = spec;
        if ("mine".equalsIgnoreCase(assignee)) {
            spec = (root, cq, cb) -> cb.and(p.toPredicate(root, cq, cb),
                    cb.equal(root.get("assignedAdminId"), callerAdminId));
        } else if ("unassigned".equalsIgnoreCase(assignee)) {
            spec = (root, cq, cb) -> cb.and(p.toPredicate(root, cq, cb),
                    cb.isNull(root.get("assignedAdminId")));
        } else {
            // treat as a user publicId on the submitter
            UUID submitterPid;
            try { submitterPid = UUID.fromString(assignee); }
            catch (IllegalArgumentException e) { submitterPid = null; }
            if (submitterPid != null) {
                final UUID pid = submitterPid;
                spec = (root, cq, cb) -> cb.and(p.toPredicate(root, cq, cb),
                        cb.equal(root.get("user").get("publicId"), pid));
            }
        }
    }
    if (q != null && !q.isBlank()) {
        Specification<SupportTicket> p = spec;
        spec = (root, cq, cb) -> cb.and(p.toPredicate(root, cq, cb),
                cb.like(cb.lower(root.get("subject")), "%" + q.toLowerCase() + "%"));
    }
    return ticketRepo.findAll(spec, pageable);
}
```

Imports: `org.springframework.data.domain.Page`, `Pageable`, `org.springframework.data.jpa.domain.Specification`, `java.util.UUID`.

- [ ] **Step 2: Write tests covering each filter axis independently + combined**

Verify each filter axis (status, category, assignee=mine/unassigned/userPublicId, lastAuthor, q) returns the expected rows in isolation, plus a combined-filters test.

- [ ] **Step 3: Run tests**

```bash
cd backend && ./mvnw test -Dtest=SupportTicketServiceListTest
```
Expected: green.

- [ ] **Step 4: Commit + push**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/support/SupportTicketService.java \
        backend/src/test/java/com/slparcelauctions/backend/support/SupportTicketServiceListTest.java
git commit -m "feat(support): paginated list specs for user-side + admin queue"
git push
```

---

### Task 11: MeSupportTicketController

**Files:**
- Create: `backend/src/main/java/com/slparcelauctions/backend/support/MeSupportTicketController.java`
- Test: `backend/src/test/java/com/slparcelauctions/backend/support/MeSupportTicketControllerIntegrationTest.java`

- [ ] **Step 1: Implement the controller**

```java
package com.slparcelauctions.backend.support;

import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import com.slparcelauctions.backend.auth.AuthPrincipal;
import com.slparcelauctions.backend.common.PagedResponse;
import com.slparcelauctions.backend.support.dto.*;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/me/support-tickets")
@RequiredArgsConstructor
public class MeSupportTicketController {

    private final SupportTicketService service;
    private final SupportTicketMapper mapper;
    private final UserRepository userRepo;

    @GetMapping
    @Transactional(readOnly = true)
    public PagedResponse<SupportTicketSummaryDto> list(
            @AuthenticationPrincipal AuthPrincipal principal,
            @RequestParam(required = false) SupportTicketStatus status,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<SupportTicket> p = service.listForUser(principal.userId(), status, q,
                PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "lastMessageAt")));
        return PagedResponse.from(p.map(mapper::toSummaryDto));
    }

    @GetMapping("/{publicId}")
    @Transactional(readOnly = true)
    public SupportTicketDto detail(@AuthenticationPrincipal AuthPrincipal principal,
                                     @PathVariable UUID publicId) {
        SupportTicket t = service.findByPublicIdEnsureOwner(principal.userId(), publicId);
        User assignedAdmin = t.getAssignedAdminId() == null ? null :
                userRepo.findById(t.getAssignedAdminId()).orElse(null);
        return mapper.toUserDto(t, assignedAdmin);
    }

    @PostMapping
    @Transactional
    public SupportTicketDto create(@AuthenticationPrincipal AuthPrincipal principal,
                                     @Valid @RequestBody CreateSupportTicketRequest req) {
        SupportTicket t = service.createTicket(principal.userId(), req);
        SupportTicket reloaded = service.findByPublicIdEnsureOwner(principal.userId(), t.getPublicId());
        return mapper.toUserDto(reloaded, null);
    }

    @PostMapping("/{publicId}/messages")
    @Transactional
    public SupportTicketMessageDto reply(@AuthenticationPrincipal AuthPrincipal principal,
                                           @PathVariable UUID publicId,
                                           @Valid @RequestBody ReplySupportTicketRequest req) {
        SupportTicketMessage msg = service.userReply(principal.userId(), publicId, req);
        return mapper.toMessageDto(msg);
    }
}
```

Add a small helper on `SupportTicketService`:

```java
@Transactional(readOnly = true)
public SupportTicket findByPublicIdEnsureOwner(long userId, UUID publicId) {
    SupportTicket t = ticketRepo.findByPublicId(publicId)
            .orElseThrow(() -> new SupportTicketException(SupportTicketError.UNKNOWN_TICKET));
    if (!t.getUser().getId().equals(userId)) {
        throw new SupportTicketException(SupportTicketError.UNKNOWN_TICKET);  // 404, not 403 - don't leak existence
    }
    return t;
}
```

- [ ] **Step 2: Write the integration test (with class-level `@Transactional`)**

Cover GET list filters, GET detail, GET detail rejects 404 when not owner, POST create happy path, POST create propagates rate limit (429), POST reply happy path, POST reply rejects 10001-char body, POST reply on RESOLVED ticket auto-reopens (assert status flips).

- [ ] **Step 3: Write the NON-transactional LazyInit regression test**

File: `backend/src/test/java/com/slparcelauctions/backend/support/MeSupportTicketLazyInitRegressionTest.java`. Mirrors `AdminCouponLazyInitRegressionTest`. Tests:
- GET `/me/support-tickets/{id}` outside an enclosing tx → 200, messages array populated, attachments rendered.

- [ ] **Step 4: Run tests**

```bash
cd backend && ./mvnw test -Dtest='MeSupportTicketControllerIntegrationTest,MeSupportTicketLazyInitRegressionTest'
```
Expected: all green.

- [ ] **Step 5: Commit + push**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/support/MeSupportTicketController.java \
        backend/src/main/java/com/slparcelauctions/backend/support/SupportTicketService.java \
        backend/src/test/java/com/slparcelauctions/backend/support/MeSupportTicketControllerIntegrationTest.java \
        backend/src/test/java/com/slparcelauctions/backend/support/MeSupportTicketLazyInitRegressionTest.java
git commit -m "feat(support): MeSupportTicketController + LazyInit regression coverage"
git push
```

---

### Task 12: AdminSupportTicketController

**Files:**
- Create: `backend/src/main/java/com/slparcelauctions/backend/support/AdminSupportTicketController.java`
- Test: `backend/src/test/java/com/slparcelauctions/backend/support/AdminSupportTicketControllerIntegrationTest.java`
- Test: `backend/src/test/java/com/slparcelauctions/backend/support/AdminSupportTicketLazyInitRegressionTest.java`

- [ ] **Step 1: Implement the controller**

```java
package com.slparcelauctions.backend.support;

import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import com.slparcelauctions.backend.auth.AuthPrincipal;
import com.slparcelauctions.backend.common.PagedResponse;
import com.slparcelauctions.backend.support.dto.*;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/admin/support-tickets")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminSupportTicketController {

    private final SupportTicketService service;
    private final SupportTicketMapper mapper;
    private final UserRepository userRepo;

    @GetMapping
    @Transactional(readOnly = true)
    public PagedResponse<AdminSupportTicketQueueRow> queue(
            @AuthenticationPrincipal AuthPrincipal principal,
            @RequestParam(required = false) SupportTicketStatus status,
            @RequestParam(required = false) SupportTicketCategory category,
            @RequestParam(required = false) String assignee,
            @RequestParam(name = "last_author", required = false) SupportTicketAuthorRole lastAuthor,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<SupportTicket> p = service.listAdmin(status, category, assignee, lastAuthor, q,
                principal.userId(),
                PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "lastMessageAt")));
        return PagedResponse.from(p.map(t -> {
            User admin = t.getAssignedAdminId() == null ? null :
                    userRepo.findById(t.getAssignedAdminId()).orElse(null);
            return mapper.toAdminRow(t, admin);
        }));
    }

    @GetMapping("/queue-stats")
    @Transactional(readOnly = true)
    public SupportTicketQueueStatsDto stats() {
        return service.queueStats();
    }

    @GetMapping("/{publicId}")
    @Transactional(readOnly = true)
    public SupportTicketDto detail(@PathVariable UUID publicId) {
        SupportTicket t = service.findByPublicId(publicId);
        User admin = t.getAssignedAdminId() == null ? null :
                userRepo.findById(t.getAssignedAdminId()).orElse(null);
        return mapper.toAdminDto(t, admin);
    }

    @PostMapping("/{publicId}/messages")
    @Transactional
    public SupportTicketMessageDto reply(@AuthenticationPrincipal AuthPrincipal principal,
                                           @PathVariable UUID publicId,
                                           @Valid @RequestBody AdminReplyRequest req) {
        SupportTicketMessage msg = service.adminReply(principal.userId(), publicId, req);
        return mapper.toMessageDto(msg);
    }

    @PostMapping("/{publicId}/resolve")
    @Transactional
    public SupportTicketDto resolve(@AuthenticationPrincipal AuthPrincipal principal,
                                      @PathVariable UUID publicId) {
        SupportTicket t = service.resolve(principal.userId(), publicId);
        User admin = t.getAssignedAdminId() == null ? null :
                userRepo.findById(t.getAssignedAdminId()).orElse(null);
        return mapper.toAdminDto(t, admin);
    }

    @PostMapping("/{publicId}/reopen")
    @Transactional
    public SupportTicketDto reopen(@AuthenticationPrincipal AuthPrincipal principal,
                                     @PathVariable UUID publicId) {
        SupportTicket t = service.reopen(principal.userId(), publicId);
        User admin = t.getAssignedAdminId() == null ? null :
                userRepo.findById(t.getAssignedAdminId()).orElse(null);
        return mapper.toAdminDto(t, admin);
    }

    @PostMapping("/{publicId}/assign")
    @Transactional
    public SupportTicketDto assign(@PathVariable UUID publicId,
                                     @Valid @RequestBody AssignTicketRequest req) {
        SupportTicket t = service.assign(publicId, req.adminPublicId());
        User admin = t.getAssignedAdminId() == null ? null :
                userRepo.findById(t.getAssignedAdminId()).orElse(null);
        return mapper.toAdminDto(t, admin);
    }

    @PatchMapping("/{publicId}")
    @Transactional
    public SupportTicketDto patch(@PathVariable UUID publicId,
                                    @Valid @RequestBody PatchTicketRequest req) {
        SupportTicket t = service.patchCategory(publicId, req.category());
        User admin = t.getAssignedAdminId() == null ? null :
                userRepo.findById(t.getAssignedAdminId()).orElse(null);
        return mapper.toAdminDto(t, admin);
    }
}
```

Add `findByPublicId(UUID)` helper to the service (delegates to repo + throws UNKNOWN_TICKET if missing).

- [ ] **Step 2: Write integration tests + LazyInit regression**

Mirror the pattern from `AdminCouponControllerIntegrationTest` (transactional) + `AdminCouponLazyInitRegressionTest` (non-transactional).

- [ ] **Step 3: Run tests**

```bash
cd backend && ./mvnw test -Dtest='AdminSupportTicketControllerIntegrationTest,AdminSupportTicketLazyInitRegressionTest'
```
Expected: all green.

- [ ] **Step 4: Commit + push**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/support/AdminSupportTicketController.java \
        backend/src/main/java/com/slparcelauctions/backend/support/SupportTicketService.java \
        backend/src/test/java/com/slparcelauctions/backend/support/AdminSupportTicketControllerIntegrationTest.java \
        backend/src/test/java/com/slparcelauctions/backend/support/AdminSupportTicketLazyInitRegressionTest.java
git commit -m "feat(support): AdminSupportTicketController + LazyInit regression coverage"
git push
```

---

### Task 13: SupportTicketAttachmentController + config + S3 lifecycle docs

**Files:**
- Create: `backend/src/main/java/com/slparcelauctions/backend/support/SupportTicketAttachmentController.java`
- Modify: `backend/src/main/resources/application.yml`
- Modify: `docs/superpowers/specs/2026-05-21-customer-support-contact-design.md` (append a deploy-notes section if not already present)
- Test: `backend/src/test/java/com/slparcelauctions/backend/support/SupportTicketAttachmentControllerIntegrationTest.java`

- [ ] **Step 1: Add config keys to `application.yml`**

Append under `slpa:`:

```yaml
  support:
    rate-limit:
      tickets-per-hour: 5
    attachments:
      max-per-message: 3
      max-file-bytes: 5242880
      allowed-mime-types: "image/png,image/jpeg,image/webp,image/gif"
      pending-ttl-seconds: 3600
```

- [ ] **Step 2: Implement the controller**

```java
package com.slparcelauctions.backend.support;

import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import com.slparcelauctions.backend.auth.AuthPrincipal;
import com.slparcelauctions.backend.user.Role;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;
import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
public class SupportTicketAttachmentController {

    private final SupportTicketAttachmentService attachmentService;
    private final SupportTicketAttachmentRepository attachmentRepo;
    private final SupportTicketRepository ticketRepo;
    private final UserRepository userRepo;

    @PostMapping(value = "/api/v1/me/support-tickets/attachments",
                  consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public AttachmentKeyResponse preUpload(@AuthenticationPrincipal AuthPrincipal principal,
                                             @RequestParam("file") MultipartFile file) {
        User u = userRepo.findById(principal.userId()).orElseThrow();
        String key = attachmentService.preUpload(u, file);
        return new AttachmentKeyResponse(key);
    }

    @GetMapping("/api/v1/support-tickets/attachments/{publicId}")
    @Transactional(readOnly = true)
    public AttachmentSignedUrlResponse signedUrl(
            @AuthenticationPrincipal AuthPrincipal principal,
            @PathVariable UUID publicId) {
        SupportTicketAttachment att = attachmentRepo.findByPublicId(publicId)
                .orElseThrow(() -> new SupportTicketException(SupportTicketError.ATTACHMENT_NOT_FOUND));
        // Authorization: owner of the ticket or any admin.
        Long ticketUserId = att.getMessage().getTicket().getUser().getId();
        User caller = userRepo.findById(principal.userId()).orElseThrow();
        boolean isOwner = ticketUserId.equals(principal.userId());
        boolean isAdmin = caller.getRole() == Role.ADMIN;
        if (!isOwner && !isAdmin) {
            throw new SupportTicketException(SupportTicketError.NOT_OWNER);
        }
        return new AttachmentSignedUrlResponse(attachmentService.signedDownloadUrl(att));
    }

    public record AttachmentKeyResponse(String attachmentKey) {}
    public record AttachmentSignedUrlResponse(String url) {}
}
```

- [ ] **Step 3: Integration tests**

Cover: pre-upload happy path (POST multipart); rejects > 5 MiB (mock the validator); rejects MIME outside allowlist; GET signed URL for owner OK; GET signed URL for admin OK; GET signed URL for non-owner non-admin → 403.

- [ ] **Step 4: Run tests**

```bash
cd backend && ./mvnw test -Dtest=SupportTicketAttachmentControllerIntegrationTest
```
Expected: green.

- [ ] **Step 5: Document the S3 lifecycle rule requirement**

Edit `docs/superpowers/specs/2026-05-21-customer-support-contact-design.md` and append a "Deploy notes" section near the end (do NOT delete the decision log). Contents:

```markdown
## Deploy notes

### S3 lifecycle rule

The pending-attachment cleanup relies on an S3 bucket lifecycle rule that the backend does not create automatically. Configure once via Terraform (or AWS console for dev):

- Rule name: `support-attachments-pending-cleanup`
- Bucket: `slpa.storage.bucket`
- Prefix filter: `support-attachments/pending/`
- Action: Expire objects 1 day after creation
- Status: Enabled

Without this rule, orphaned pending uploads remain in S3 indefinitely (they are also not referenced by any DB row, so they don't appear in any application listing). The application-side Redis cache TTL expires the metadata after 1 hour, but the actual S3 objects are only cleaned by this lifecycle rule.
```

- [ ] **Step 6: Commit + push**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/support/SupportTicketAttachmentController.java \
        backend/src/main/resources/application.yml \
        docs/superpowers/specs/2026-05-21-customer-support-contact-design.md \
        backend/src/test/java/com/slparcelauctions/backend/support/SupportTicketAttachmentControllerIntegrationTest.java
git commit -m "feat(support): attachment upload + signed-url endpoints + S3 lifecycle docs"
git push
```

---

### Task 14: Frontend Textarea UI primitive

**Files:**
- Create: `frontend/src/components/ui/Textarea.tsx`
- Create: `frontend/src/components/ui/Textarea.test.tsx`

- [ ] **Step 1: Write the failing test**

```tsx
import { describe, it, expect } from "vitest";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { Textarea } from "./Textarea";

describe("Textarea", () => {
  it("renders with a label", () => {
    render(<Textarea label="Message" />);
    expect(screen.getByLabelText("Message")).toBeInTheDocument();
  });

  it("renders helperText when no error", () => {
    render(<Textarea label="Message" helperText="Max 10000 chars" />);
    expect(screen.getByText("Max 10000 chars")).toBeInTheDocument();
  });

  it("renders error text when error is set", () => {
    render(<Textarea label="Message" error="Required" />);
    expect(screen.getByText("Required")).toBeInTheDocument();
  });

  it("accepts user input via the rows prop", async () => {
    const u = userEvent.setup();
    render(<Textarea label="Message" rows={8} />);
    const ta = screen.getByLabelText("Message") as HTMLTextAreaElement;
    expect(ta.rows).toBe(8);
    await u.type(ta, "Hello");
    expect(ta.value).toBe("Hello");
  });
});
```

- [ ] **Step 2: Run, see failure (compile)**

```bash
cd frontend && npm test -- --run Textarea
```
Expected: fails because Textarea doesn't exist.

- [ ] **Step 3: Implement the primitive**

```tsx
"use client";
import { forwardRef, useId, type ReactNode, type TextareaHTMLAttributes } from "react";
import { cn } from "@/lib/cn";

type TextareaProps = {
  label?: string;
  helperText?: ReactNode;
  error?: string;
  fullWidth?: boolean;
} & TextareaHTMLAttributes<HTMLTextAreaElement>;

const baseClasses =
  "w-full rounded-lg bg-bg-subtle text-fg placeholder:text-fg-muted p-3 ring-1 ring-transparent transition-all focus:bg-surface-raised focus:outline-none focus:ring-brand resize-y";

export const Textarea = forwardRef<HTMLTextAreaElement, TextareaProps>(function Textarea(
  { label, helperText, error, fullWidth = true, className, id, rows = 6, ...rest },
  ref,
) {
  const generatedId = useId();
  const taId = id ?? generatedId;
  const showError = Boolean(error);

  return (
    <div className={cn("flex flex-col gap-1", fullWidth && "w-full")}>
      {label && (
        <label htmlFor={taId} className="text-sm text-fg">
          {label}
        </label>
      )}
      <textarea
        ref={ref}
        id={taId}
        rows={rows}
        className={cn(
          baseClasses,
          showError && "ring-danger focus:ring-danger",
          className,
        )}
        aria-invalid={showError || undefined}
        {...rest}
      />
      {(helperText || error) && (
        <p className={cn("text-xs", showError ? "text-danger" : "text-fg-muted")}>
          {error ?? helperText}
        </p>
      )}
    </div>
  );
});
```

- [ ] **Step 4: Run + verify guards**

```bash
cd frontend && npm test -- --run Textarea && npm run verify
```
Expected: 4/4 tests green; all four verify guards green.

- [ ] **Step 5: Commit + push**

```bash
git add frontend/src/components/ui/Textarea.tsx frontend/src/components/ui/Textarea.test.tsx
git commit -m "feat(ui): Textarea primitive"
git push
```

---

### Task 15: Frontend types + API client + hooks foundation

**Files:**
- Create: `frontend/src/types/support.ts`
- Create: `frontend/src/lib/api/support.ts`
- Create: `frontend/src/hooks/useMySupportTickets.ts`
- Create: `frontend/src/hooks/useMySupportTicket.ts`
- Create: `frontend/src/hooks/useCreateSupportTicket.ts`
- Create: `frontend/src/hooks/useReplySupportTicket.ts`
- Create: `frontend/src/hooks/useUploadSupportAttachment.ts`
- Create: `frontend/src/hooks/useSignedAttachmentUrl.ts`

- [ ] **Step 1: Write the types**

```ts
// frontend/src/types/support.ts
export type SupportTicketStatus = "OPEN" | "RESOLVED";
export type SupportTicketCategory =
  | "ACCOUNT" | "BIDDING" | "LISTING" | "ESCROW" | "WALLET" | "OTHER";
export type SupportTicketAuthorRole = "USER" | "ADMIN";

export interface SupportTicketAttachmentDto {
  publicId: string;
  mimeType: string;
  sizeBytes: number;
  width: number | null;
  height: number | null;
}

export interface SupportTicketMessageDto {
  publicId: string;
  authorPublicId: string;
  authorDisplayName: string;
  authorRole: SupportTicketAuthorRole;
  body: string;
  visibleToUser: boolean;
  createdAt: string;
  attachments: SupportTicketAttachmentDto[];
}

export interface SupportTicketDto {
  publicId: string;
  submitterPublicId: string;
  submitterDisplayName: string;
  subject: string;
  category: SupportTicketCategory;
  status: SupportTicketStatus;
  assignedAdminPublicId: string | null;
  assignedAdminDisplayName: string | null;
  lastMessageAt: string;
  lastMessageAuthor: SupportTicketAuthorRole;
  resolvedAt: string | null;
  createdAt: string;
  updatedAt: string;
  messages: SupportTicketMessageDto[];
}

export interface SupportTicketSummaryDto {
  publicId: string;
  subject: string;
  category: SupportTicketCategory;
  status: SupportTicketStatus;
  lastMessageAuthor: SupportTicketAuthorRole;
  lastMessageAt: string;
}

export interface AdminSupportTicketQueueRow {
  publicId: string;
  subject: string;
  category: SupportTicketCategory;
  status: SupportTicketStatus;
  submitterPublicId: string;
  submitterDisplayName: string;
  assignedAdminPublicId: string | null;
  assignedAdminDisplayName: string | null;
  lastMessageAuthor: SupportTicketAuthorRole;
  lastMessageAt: string;
}

export interface SupportTicketQueueStatsDto {
  openNeedingAdminReply: number;
  openTotal: number;
}

export type SupportTicketErrorCode =
  | "UNKNOWN_TICKET"
  | "NOT_OWNER"
  | "INTERNAL_NOTE_FROM_USER"
  | "RATE_LIMITED"
  | "INVALID_ATTACHMENT"
  | "INVALID_CATEGORY"
  | "ATTACHMENT_NOT_FOUND";

export interface CreateSupportTicketRequest {
  subject: string;
  category: SupportTicketCategory;
  body: string;
  attachmentKeys?: string[];
}

export interface ReplySupportTicketRequest {
  body: string;
  attachmentKeys?: string[];
}

export interface AdminReplyRequest {
  body: string;
  attachmentKeys?: string[];
  internalNote?: boolean;
}
```

- [ ] **Step 2: Write the API client**

```ts
// frontend/src/lib/api/support.ts
import { api } from "@/lib/api";
import type { Page } from "@/types/page";
import type {
  AdminReplyRequest,
  AdminSupportTicketQueueRow,
  CreateSupportTicketRequest,
  ReplySupportTicketRequest,
  SupportTicketCategory,
  SupportTicketDto,
  SupportTicketMessageDto,
  SupportTicketQueueStatsDto,
  SupportTicketStatus,
  SupportTicketSummaryDto,
} from "@/types/support";

export interface MyListParams {
  status?: SupportTicketStatus;
  q?: string;
  page?: number;
  size?: number;
}

export function fetchMySupportTickets(params: MyListParams = {}) {
  return api.get<Page<SupportTicketSummaryDto>>("/api/v1/me/support-tickets", { params });
}

export function fetchMySupportTicket(publicId: string) {
  return api.get<SupportTicketDto>(`/api/v1/me/support-tickets/${publicId}`);
}

export function createSupportTicket(req: CreateSupportTicketRequest) {
  return api.post<SupportTicketDto>("/api/v1/me/support-tickets", req);
}

export function replySupportTicket(publicId: string, req: ReplySupportTicketRequest) {
  return api.post<SupportTicketMessageDto>(`/api/v1/me/support-tickets/${publicId}/messages`, req);
}

export function uploadSupportAttachment(file: File): Promise<{ attachmentKey: string }> {
  const fd = new FormData();
  fd.append("file", file);
  return api.postMultipart("/api/v1/me/support-tickets/attachments", fd);
}

export function fetchAttachmentSignedUrl(publicId: string) {
  return api.get<{ url: string }>(`/api/v1/support-tickets/attachments/${publicId}`);
}

// Admin
export interface AdminListParams {
  status?: SupportTicketStatus;
  category?: SupportTicketCategory;
  assignee?: string;
  last_author?: "USER" | "ADMIN";
  q?: string;
  page?: number;
  size?: number;
}

export function fetchAdminSupportTickets(params: AdminListParams = {}) {
  return api.get<Page<AdminSupportTicketQueueRow>>("/api/v1/admin/support-tickets", { params });
}

export function fetchAdminSupportTicket(publicId: string) {
  return api.get<SupportTicketDto>(`/api/v1/admin/support-tickets/${publicId}`);
}

export function fetchAdminSupportQueueStats() {
  return api.get<SupportTicketQueueStatsDto>("/api/v1/admin/support-tickets/queue-stats");
}

export function adminReplySupportTicket(publicId: string, req: AdminReplyRequest) {
  return api.post<SupportTicketMessageDto>(`/api/v1/admin/support-tickets/${publicId}/messages`, req);
}

export function adminResolveSupportTicket(publicId: string) {
  return api.post<SupportTicketDto>(`/api/v1/admin/support-tickets/${publicId}/resolve`, {});
}

export function adminReopenSupportTicket(publicId: string) {
  return api.post<SupportTicketDto>(`/api/v1/admin/support-tickets/${publicId}/reopen`, {});
}

export function adminAssignSupportTicket(publicId: string, adminPublicId: string | null) {
  return api.post<SupportTicketDto>(`/api/v1/admin/support-tickets/${publicId}/assign`, { adminPublicId });
}

export function adminPatchSupportTicket(publicId: string, category: SupportTicketCategory) {
  return api.patch<SupportTicketDto>(`/api/v1/admin/support-tickets/${publicId}`, { category });
}
```

If `api.postMultipart` doesn't exist on the API client, add it (mirroring `api.post` but skipping the JSON header — let the browser set the multipart boundary).

- [ ] **Step 3: Hooks**

Standard react-query hooks. Hook patterns mirror `useMyCoupons` / `useRedeemCoupon` etc. exactly. Each mutation invalidates the relevant query keys on success.

- [ ] **Step 4: Lint + verify**

```bash
cd frontend && npm run verify
```

- [ ] **Step 5: Commit + push**

```bash
git add frontend/src/types/support.ts frontend/src/lib/api/support.ts frontend/src/hooks/useMySupportTickets.ts frontend/src/hooks/useMySupportTicket.ts frontend/src/hooks/useCreateSupportTicket.ts frontend/src/hooks/useReplySupportTicket.ts frontend/src/hooks/useUploadSupportAttachment.ts frontend/src/hooks/useSignedAttachmentUrl.ts frontend/src/lib/api.ts
git commit -m "feat(support): frontend types + API client + react-query hooks"
git push
```

---

### Task 16: SupportTicketList page

**Files:**
- Create: `frontend/src/app/support/page.tsx`
- Create: `frontend/src/components/support/SupportTicketList.tsx`
- Create: `frontend/src/components/support/SupportTicketList.test.tsx`

- [ ] **Step 1: Server-component shell**

```tsx
// frontend/src/app/support/page.tsx
import { SupportTicketList } from "@/components/support/SupportTicketList";

export const dynamic = "force-dynamic";

export default function SupportPage() {
  return <SupportTicketList />;
}
```

- [ ] **Step 2: Client list component**

Mirror `WalletCouponsCard` / coupon list patterns. Columns per spec §7. Empty state + "New ticket" button linking to `/support/new`.

- [ ] **Step 3: Tests**

Empty state, table rendering, status pill + "admin replied" sub-label when `lastMessageAuthor=ADMIN`, pagination, status-filter URL sync.

- [ ] **Step 4: Run tests + verify**

```bash
cd frontend && npm test -- --run SupportTicketList && npm run verify
```

- [ ] **Step 5: Commit + push**

```bash
git add frontend/src/app/support/page.tsx frontend/src/components/support/SupportTicketList.tsx frontend/src/components/support/SupportTicketList.test.tsx
git commit -m "feat(support): /support list page"
git push
```

---

### Task 17: NewSupportTicketForm page

**Files:**
- Create: `frontend/src/app/support/new/page.tsx`
- Create: `frontend/src/components/support/NewSupportTicketForm.tsx`
- Create: `frontend/src/components/support/NewSupportTicketForm.test.tsx`
- Create: `frontend/src/components/support/SupportAttachmentDropzone.tsx`

- [ ] **Step 1: Implement the dropzone (reusable across user + admin reply paths)**

`SupportAttachmentDropzone` accepts `onAttachmentKeyAdded(key: string)` and `onAttachmentKeyRemoved(key: string)`. Renders up to N image thumbnails (from local object URLs of the pending uploads) plus a "drop or click to add" zone. Calls `useUploadSupportAttachment()` on file selection; surfaces upload errors inline (e.g. "file too large", "mime not allowed").

- [ ] **Step 2: Implement the form**

Subject input (max 160), Category select (six options), Message Textarea (max 10000 + char counter), Attachments (`SupportAttachmentDropzone` with `maxAttachments={3}`). Submit calls `useCreateSupportTicket()`. On success, redirect to `/support/{newPublicId}`. On rate-limit (429), show the specific message.

- [ ] **Step 3: Tests**

Subject required, Category required, Body required, body char counter increments, dropzone respects max-3, submit happy path navigates to detail, rate-limit error renders the message.

- [ ] **Step 4: Run tests + verify**

```bash
cd frontend && npm test -- --run NewSupportTicketForm SupportAttachmentDropzone && npm run verify
```

- [ ] **Step 5: Commit + push**

```bash
git add frontend/src/app/support/new/ frontend/src/components/support/NewSupportTicketForm.tsx frontend/src/components/support/NewSupportTicketForm.test.tsx frontend/src/components/support/SupportAttachmentDropzone.tsx
git commit -m "feat(support): /support/new form + attachment dropzone"
git push
```

---

### Task 18: SupportTicketThread page

**Files:**
- Create: `frontend/src/app/support/[publicId]/page.tsx`
- Create: `frontend/src/components/support/SupportTicketThread.tsx`
- Create: `frontend/src/components/support/SupportTicketThread.test.tsx`

- [ ] **Step 1: Implement the thread**

Header: subject, category badge, status pill. Message list with alternating bubbles (user right, admin left); each shows authorname + role + timestamp + body + attachment thumbnails. Attachment click → lightbox (existing primitive if available; otherwise a simple modal with the signed URL). Composer at bottom: Textarea + dropzone + Send. When `status=RESOLVED`, show helper text "Replying will reopen this ticket."

Next.js 16 async-params pattern:

```tsx
export default async function SupportTicketDetailPage({
  params,
}: {
  params: Promise<{ publicId: string }>;
}) {
  const { publicId } = await params;
  return <SupportTicketThread publicId={publicId} />;
}
```

- [ ] **Step 2: Tests**

Bubble alternation, lightbox-on-thumbnail-click, helper text appears when status=RESOLVED, internal notes never appear in user view (defense in depth — backend already filters), reply submit invalidates the detail query.

- [ ] **Step 3: Run + verify**

```bash
cd frontend && npm test -- --run SupportTicketThread && npm run verify
```

- [ ] **Step 4: Commit + push**

```bash
git add 'frontend/src/app/support/[publicId]/' frontend/src/components/support/SupportTicketThread.tsx frontend/src/components/support/SupportTicketThread.test.tsx
git commit -m "feat(support): /support/[publicId] thread view"
git push
```

---

### Task 19: User nav wiring — dropdown + mobile menu

**Files:**
- Modify: `frontend/src/components/layout/UserMenuDropdown.tsx`
- Modify: `frontend/src/components/layout/MobileMenu.tsx`

- [ ] **Step 1: Add "Support" to `UserMenuDropdown`**

Between "Wallet" and "Sign out". Link target: `/support`.

- [ ] **Step 2: Add "Support" to `MobileMenu`'s footer row**

Same row as Contact / About / Terms.

- [ ] **Step 3: Run tests + verify**

```bash
cd frontend && npm test -- --run UserMenuDropdown MobileMenu && npm run verify
```

- [ ] **Step 4: Commit + push**

```bash
git add frontend/src/components/layout/UserMenuDropdown.tsx frontend/src/components/layout/MobileMenu.tsx
git commit -m "feat(support): Help link in user dropdown + mobile menu"
git push
```

---

### Task 20: Admin queue + sidebar badge

**Files:**
- Create: `frontend/src/app/admin/support/page.tsx`
- Create: `frontend/src/components/admin/support/AdminSupportTicketQueue.tsx`
- Create: `frontend/src/components/admin/support/AdminSupportTicketQueue.test.tsx`
- Create: `frontend/src/hooks/admin/useAdminSupportTickets.ts`
- Create: `frontend/src/hooks/admin/useAdminSupportQueueStats.ts`
- Modify: `frontend/src/components/admin/AdminShell.tsx`

- [ ] **Step 1: Implement the polling stats hook**

```ts
import { useQuery } from "@tanstack/react-query";
import { fetchAdminSupportQueueStats } from "@/lib/api/support";

export const ADMIN_SUPPORT_STATS_KEY = ["admin-support-stats"] as const;

export function useAdminSupportQueueStats(enabled = true) {
  return useQuery({
    queryKey: ADMIN_SUPPORT_STATS_KEY,
    queryFn: fetchAdminSupportQueueStats,
    refetchInterval: 30_000,
    enabled,
  });
}
```

- [ ] **Step 2: Add the "Support" sidebar item with badge**

In `AdminShell.tsx`, after the "Reports" item, before "Users":

```ts
{ label: "Support", href: "/admin/support", badge: supportStats?.openNeedingAdminReply },
```

Source `supportStats` via the new hook at the top of `AdminShell`.

- [ ] **Step 3: Implement the queue component**

Mirrors `AdminCouponList` shape. Filters: status, category, assignee (Mine / Unassigned / All), last_author (Needs admin reply / Waiting on user / All), subject q. URL-synced. Table columns per spec §7.

- [ ] **Step 4: Tests**

URL-synced filters, pagination, "needs admin reply" filter narrows the table, sidebar badge counter rendering.

- [ ] **Step 5: Run + verify**

```bash
cd frontend && npm test -- --run AdminSupportTicketQueue AdminShell && npm run verify
```

- [ ] **Step 6: Commit + push**

```bash
git add frontend/src/app/admin/support/page.tsx frontend/src/components/admin/support/AdminSupportTicketQueue.tsx frontend/src/components/admin/support/AdminSupportTicketQueue.test.tsx frontend/src/hooks/admin/useAdminSupportTickets.ts frontend/src/hooks/admin/useAdminSupportQueueStats.ts frontend/src/components/admin/AdminShell.tsx
git commit -m "feat(admin-ui): support queue + sidebar badge with polling stats"
git push
```

---

### Task 21: Admin detail page (resolve / reopen / assign / internal-note)

**Files:**
- Create: `frontend/src/app/admin/support/[publicId]/page.tsx`
- Create: `frontend/src/components/admin/support/AdminSupportTicketDetail.tsx`
- Create: `frontend/src/components/admin/support/AdminSupportTicketDetail.test.tsx`
- Create: `frontend/src/hooks/admin/useAdminSupportTicket.ts`
- Create: `frontend/src/hooks/admin/useAdminSupportReply.ts`
- Create: `frontend/src/hooks/admin/useAdminSupportResolve.ts`
- Create: `frontend/src/hooks/admin/useAdminSupportReopen.ts`
- Create: `frontend/src/hooks/admin/useAdminSupportAssign.ts`
- Create: `frontend/src/hooks/admin/useAdminSupportPatchCategory.ts`

- [ ] **Step 1: Page wrapper**

```tsx
import { AdminSupportTicketDetail } from "@/components/admin/support/AdminSupportTicketDetail";

export const dynamic = "force-dynamic";

export default async function AdminSupportTicketDetailPage({
  params,
}: {
  params: Promise<{ publicId: string }>;
}) {
  const { publicId } = await params;
  return <AdminSupportTicketDetail publicId={publicId} />;
}
```

- [ ] **Step 2: Implement the detail component**

Top bar: Resolve / Reopen button (mutex on status), "Assign to me" / "Unassign" buttons, Category dropdown. Thread renders ALL messages including internal notes (styled with `border-warning bg-warning-bg/20` plus "Internal note" badge). Composer with "Send" button + "Save as internal note" checkbox; when checked, the bubble preview switches to internal-note styling so the admin sees what they're about to send.

- [ ] **Step 3: Tests**

Resolve button transitions status, reopen visible only when resolved, internal-note checkbox toggles preview style, assign-to-me happy path, category patch happy path.

- [ ] **Step 4: Run + verify**

```bash
cd frontend && npm test -- --run AdminSupportTicketDetail && npm run verify
```

- [ ] **Step 5: Commit + push**

```bash
git add 'frontend/src/app/admin/support/[publicId]/' frontend/src/components/admin/support/AdminSupportTicketDetail.tsx frontend/src/components/admin/support/AdminSupportTicketDetail.test.tsx frontend/src/hooks/admin/useAdminSupportTicket.ts frontend/src/hooks/admin/useAdminSupportReply.ts frontend/src/hooks/admin/useAdminSupportResolve.ts frontend/src/hooks/admin/useAdminSupportReopen.ts frontend/src/hooks/admin/useAdminSupportAssign.ts frontend/src/hooks/admin/useAdminSupportPatchCategory.ts
git commit -m "feat(admin-ui): support ticket detail with resolve / reopen / assign / internal-note"
git push
```

---

### Task 22: Notification feed renderer + bell deep-links for support categories

**Files:**
- Modify: `frontend/src/components/notifications/NotificationFeed.tsx` (or wherever the category-icon + deep-link mapping lives)
- Modify: `frontend/src/types/notifications.ts` (or equivalent)

- [ ] **Step 1: Add the four new categories to the typescript discriminator**

Add `"SUPPORT_TICKET_ADMIN_REPLIED" | "SUPPORT_TICKET_RESOLVED" | "SUPPORT_TICKET_OPENED" | "SUPPORT_TICKET_USER_REPLIED"` to the category-type union.

- [ ] **Step 2: Add icon + deep-link mapping**

In whichever component maps category to icon + render-body + deep-link, add the four entries:

```ts
case "SUPPORT_TICKET_ADMIN_REPLIED":
case "SUPPORT_TICKET_RESOLVED":
  return { icon: MessageSquare, href: `/support/${data.ticketPublicId}` };
case "SUPPORT_TICKET_OPENED":
case "SUPPORT_TICKET_USER_REPLIED":
  return { icon: MessageSquare, href: `/admin/support/${data.ticketPublicId}` };
```

(Confirm `MessageSquare` is exported from `@/components/ui/icons`; if not, add it.)

- [ ] **Step 3: Test the renderer**

A test that pushes each of the four notification categories through the renderer and asserts the deep-link target is correct.

- [ ] **Step 4: Run + verify**

```bash
cd frontend && npm test && npm run verify
```

- [ ] **Step 5: Commit + push**

```bash
git add frontend/src/components/notifications/ frontend/src/types/
git commit -m "feat(notify-ui): support ticket deep-links + MessageSquare icon"
git push
```

---

### Task 23: Postman + smoke + README + DEFERRED_WORK + PR

**Files:**
- Update: SLPA Postman collection (cloud)
- Modify: `README.md`
- Modify: `docs/implementation/DEFERRED_WORK.md` (only if anything was deferred — should be empty)

- [ ] **Step 1: Postman mirror**

Add a "Support tickets" folder. Requests:

1. POST `/me/support-tickets/attachments` (multipart; capture `attachmentKey`)
2. POST `/me/support-tickets` body uses `{{attachmentKey}}`; capture `supportTicketPublicId` + the initial message's `supportTicketMessagePublicId`
3. GET `/me/support-tickets?status=OPEN`
4. GET `/me/support-tickets/{{supportTicketPublicId}}`
5. POST `/me/support-tickets/{{supportTicketPublicId}}/messages`
6. GET `/admin/support-tickets?last_author=USER`
7. GET `/admin/support-tickets/queue-stats`
8. GET `/admin/support-tickets/{{supportTicketPublicId}}`
9. POST `/admin/support-tickets/{{supportTicketPublicId}}/messages` (with `internalNote: false`)
10. POST `/admin/support-tickets/{{supportTicketPublicId}}/messages` (with `internalNote: true`) — note the user-side detail call should NOT show this message
11. POST `/admin/support-tickets/{{supportTicketPublicId}}/resolve`
12. POST `/admin/support-tickets/{{supportTicketPublicId}}/reopen`
13. POST `/admin/support-tickets/{{supportTicketPublicId}}/assign` body `{ "adminPublicId": "{{adminPublicId}}" }`
14. PATCH `/admin/support-tickets/{{supportTicketPublicId}}` body `{ "category": "ESCROW" }`
15. GET `/support-tickets/attachments/{{supportTicketAttachmentPublicId}}`

Each gets a `pm.test()` script asserting the right HTTP status and (where applicable) capturing chained variables.

- [ ] **Step 2: README sweep**

Add a `### Customer support` section near the existing feature blocks (e.g. after the coupon-codes block). Bullet list of what shipped (user dropdown entry, /support list/new/thread, admin queue with badge, internal notes, attachments, four notification categories).

- [ ] **Step 3: DEFERRED_WORK check**

Confirm spec §11 (Out of scope) items don't need entries — they are strategic decisions, not tactical deferrals. Scan implementation for any TODO / FIXME markers; should be none. If any are found, add them to DEFERRED_WORK.md with a description, owning task, why deferred, and what would unblock.

- [ ] **Step 4: Run full test suites**

```bash
cd backend && ./mvnw test
cd frontend && npm test
cd frontend && npm run verify
```

All green required.

- [ ] **Step 5: Local dev smoke** (Docker compose required)

```bash
docker compose restart backend frontend
# Browser:
# 1. Log in as a regular verified user
# 2. Open user dropdown -> click Support -> see empty list
# 3. Click "New ticket" -> fill subject "Test" + category WALLET + body "test message"
# 4. Drop a small PNG, submit
# 5. Redirected to /support/{newId} -> verify message + attachment rendered
# 6. Log in as admin (different browser profile)
# 7. See "Support" sidebar badge count = 1
# 8. Open queue with default filters
# 9. Click into the ticket -> reply with internal note checked
# 10. Reply again without the checkbox
# 11. Mark resolved
# 12. Switch back to regular user -> notification feed shows reply + resolved entries
# 13. Reply on the resolved ticket -> auto-reopens; admin sees badge=1 again
```

- [ ] **Step 6: Commit + push**

```bash
git add README.md docs/implementation/DEFERRED_WORK.md
git commit -m "docs: README + DEFERRED_WORK sweep for support system"
git push
```

- [ ] **Step 7: Open PR into dev**

```bash
gh pr create --base dev --head feat/customer-support-contact \
  --title "feat(support): customer support contact (#167)" \
  --body "$(cat <<'EOF'
## Summary
- Implements customer support per docs/superpowers/specs/2026-05-21-customer-support-contact-design.md (closes #167)
- New tables support_tickets / support_ticket_messages / support_ticket_attachments (Flyway V42)
- Backend: new support package with entities, repos, service, rate limiter, two controllers (Me + Admin), attachment controller, exception handler
- Frontend: new Textarea UI primitive; /support list/new/thread; /admin/support queue + detail; user dropdown entry; admin sidebar badge with polling
- Four new notification categories with in-app + (user-side only) SL IM channels
- Image attachments up to 3 per message; pending state via Redis + S3 lifecycle rule
- Per-user rate limit: 5 new tickets/hour, replies uncapped

## LazyInit defense
Every controller method that returns a mapper-derived DTO is @Transactional; single-row repo finders use @EntityGraph; each controller surface has a non-@Transactional regression test class (lesson from PR #388).

## Test plan
- [x] backend ./mvnw test full suite green
- [x] frontend npm test full suite green
- [x] frontend npm run verify green
- [ ] Manual smoke (post-deploy): user creates ticket -> admin sees badge -> admin internal note (invisible to user) -> admin public reply -> user sees notification + SL IM
- [ ] Post-deploy: configure the S3 lifecycle rule per spec deploy notes (support-attachments/pending/ -> 1-day expire)
EOF
)"
```

---

## Self-review

**Spec coverage check:**
- §1 Goal — PR description covers
- §2 Data model — Task 1
- §3 Apply / lifecycle logic — Tasks 8 (create + reply + auto-reopen) and 9 (resolve / reopen / assign / patch)
- §4 Attachments — Task 7 (service) + Task 13 (controller)
- §5 Backend endpoints — Tasks 11 (Me) + 12 (Admin) + 13 (attachments)
- §6 Notifications — Task 5
- §7 Frontend UI — Tasks 14-21
- §8 Migration plan — Task 1
- §9 Configuration — Task 13
- §10 Testing — distributed across every backend / frontend task
- §11 Out of scope — Task 23 confirms nothing was silently deferred
- §12 Decision log — context only

**Placeholder scan:** No `TBD` / `TODO` / `implement later`. Every code step has the actual code or a tight description with file:line. Two places hand off implementation detail to "mirror the existing X" patterns (the dropzone hook + the lightbox primitive); both reference an existing file the implementer can read.

**Type consistency:**
- `SupportTicketAuthorRole` enum used identically across entities, DTOs, TS types, and notification builders.
- `attachmentKeys: string[]` shape matches between `CreateSupportTicketRequest`, `ReplySupportTicketRequest`, `AdminReplyRequest`.
- `SupportTicketDto.messages: List<SupportTicketMessageDto>` — same shape in TS types.
- `SupportTicketMapper.toUserDto` filters `visibleToUser=true`; `toAdminDto` shows all. Used correctly by `MeSupportTicketController.detail` vs `AdminSupportTicketController.detail`.
- `SupportTicketQueueStatsDto` shape matches frontend `useAdminSupportQueueStats` consumer.

**Resolved during plan-writing:**
- The migration is V42 (V41 = coupon codes is latest on disk). Plan header documents this.
- `ImageUploadValidator.validate(MultipartFile)`'s actual return type is consumed by the attachment service; implementer must adapt if shape differs from the assumed `{width, height}` record (called out in Task 7 Step 1).
- `MessageSquare` icon assumed exported from `@/components/ui/icons`; Task 22 calls out the check.
