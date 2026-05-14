# Groups Namespace Migration — Part 1: Backend foundation

> Index: [`2026-05-13-groups-nav-and-browse-plan.md`](2026-05-13-groups-nav-and-browse-plan.md). Spec: [`2026-05-13-groups-nav-and-browse-design.md`](2026-05-13-groups-nav-and-browse-design.md).

**Tasks 1–7.** New browse endpoint + DTO + repo query + reserved-slug validator + notification `linkUrl`.

**Order rule:** Tasks 1 → 2 → 3 → 4 chain (DTO → repo → service → controller). Tasks 5, 6 (and 7 if needed) are parallel-safe with the chain and with each other.

---

## Task 1: `RealtyGroupCardDto` record + `GroupsSortKey` enum

**Files:**
- Create: `backend/src/main/java/com/slparcelauctions/backend/realty/browse/dto/RealtyGroupCardDto.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/realty/browse/GroupsSortKey.java`
- Create: `backend/src/test/java/com/slparcelauctions/backend/realty/browse/dto/RealtyGroupCardDtoTest.java`

- [ ] **Step 1: Write failing test that asserts DTO + enum exist with the right shape**

```java
package com.slparcelauctions.backend.realty.browse.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.slparcelauctions.backend.realty.browse.GroupsSortKey;
import com.slparcelauctions.backend.realty.rating.dto.GroupRatingDto;

class RealtyGroupCardDtoTest {

    @Test
    void carriesEveryFieldFromTheSpec() {
        var rating = new GroupRatingDto(new BigDecimal("4.5"), 12);
        var dto = new RealtyGroupCardDto(
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                "Sunset Realty",
                "sunset-realty",
                "Mainland parcels across Heterocera.",
                "/api/v1/realty-groups/.../logo",
                null,
                OffsetDateTime.parse("2026-01-15T00:00:00Z"),
                4,
                8,
                3,
                17,
                rating);

        assertThat(dto.name()).isEqualTo("Sunset Realty");
        assertThat(dto.slug()).isEqualTo("sunset-realty");
        assertThat(dto.tagline()).isEqualTo("Mainland parcels across Heterocera.");
        assertThat(dto.memberCount()).isEqualTo(4);
        assertThat(dto.activeListingsCount()).isEqualTo(3);
        assertThat(dto.completedSalesCount()).isEqualTo(17);
        assertThat(dto.rating().reviewCount()).isEqualTo(12);
    }

    @Test
    void sortKeyEnumHasFourValues() {
        assertThat(GroupsSortKey.values())
            .containsExactlyInAnyOrder(
                GroupsSortKey.RATING,
                GroupsSortKey.NEWEST,
                GroupsSortKey.MOST_ACTIVE_LISTINGS,
                GroupsSortKey.MOST_SALES);
    }
}
```

- [ ] **Step 2: Run the test to confirm it fails**

```bash
./mvnw test -Dtest=RealtyGroupCardDtoTest
```

Expected: FAIL with `cannot find symbol` for the new types.

- [ ] **Step 3: Create `GroupsSortKey`**

```java
package com.slparcelauctions.backend.realty.browse;

/**
 * Sort key for the public {@code GET /api/v1/realty-groups} browse endpoint.
 * Spec section 6.1. Direction is always DESC for every key; tie-break is
 * {@code name ASC} for stable pagination across pages.
 */
public enum GroupsSortKey {
    RATING,
    NEWEST,
    MOST_ACTIVE_LISTINGS,
    MOST_SALES;
}
```

- [ ] **Step 4: Create `RealtyGroupCardDto`**

```java
package com.slparcelauctions.backend.realty.browse.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.slparcelauctions.backend.realty.rating.dto.GroupRatingDto;

/**
 * Wire shape for one card in the public groups directory. Spec section 6.1.
 *
 * <p>{@code tagline} is the description server-truncated to 120 chars + ellipsis.
 * {@code logoUrl} and {@code coverUrl} are relative paths; the frontend wraps
 * them with {@code apiUrl()}. {@code hasVerifiedSlGroup} is deliberately
 * absent — the listing-level filter excludes unverified groups, so the
 * field would always be true on the wire.
 */
public record RealtyGroupCardDto(
        UUID publicId,
        String name,
        String slug,
        String tagline,
        String logoUrl,
        String coverUrl,
        OffsetDateTime foundedAt,
        int memberCount,
        int memberSeatLimit,
        int activeListingsCount,
        int completedSalesCount,
        GroupRatingDto rating) {
}
```

- [ ] **Step 5: Run the test to confirm it passes**

```bash
./mvnw test -Dtest=RealtyGroupCardDtoTest
```

Expected: 2 tests, 0 failures.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/realty/browse/ \
        backend/src/test/java/com/slparcelauctions/backend/realty/browse/
git commit -m "feat(groups): RealtyGroupCardDto + GroupsSortKey for browse endpoint"
```

---

## Task 2: Native-SQL projection + `browseCards` repo method

**Files:**
- Create: `backend/src/main/java/com/slparcelauctions/backend/realty/browse/RealtyGroupCardProjection.java`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/realty/RealtyGroupRepository.java`
- Create: `backend/src/test/java/com/slparcelauctions/backend/realty/browse/RealtyGroupBrowseQueryTest.java`

- [ ] **Step 1: Write failing repository test**

```java
package com.slparcelauctions.backend.realty.browse;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import com.slparcelauctions.backend.realty.RealtyGroupRepository;

@SpringBootTest
@ActiveProfiles("dev")
@Transactional
@TestPropertySource(properties = {
    "auth.cleanup.enabled=false",
    "slpa.auction-end.enabled=false",
    "slpa.ownership-monitor.enabled=false",
    "slpa.notifications.cleanup.enabled=false",
    "slpa.notifications.sl-im.cleanup.enabled=false",
    "slpa.realty.invitation-expiry.enabled=false"
})
class RealtyGroupBrowseQueryTest {

    @Autowired RealtyGroupRepository repo;

    @Test
    void returnsEmptyPageWhenNoVerifiedGroupsExist() {
        Page<RealtyGroupCardProjection> page = repo.browseCards(
            null,
            PageRequest.of(0, 20, Sort.unsorted()));

        assertThat(page.getContent()).isEmpty();
        assertThat(page.getTotalElements()).isZero();
    }
}
```

- [ ] **Step 2: Run the test to confirm it fails**

```bash
./mvnw test -Dtest=RealtyGroupBrowseQueryTest
```

Expected: FAIL with `cannot find symbol` for `RealtyGroupCardProjection` and `browseCards`.

- [ ] **Step 3: Create the projection interface**

```java
package com.slparcelauctions.backend.realty.browse;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Native-query projection populated by the browse SQL in
 * {@code RealtyGroupRepository.browseCards}. Field names match the SQL column
 * aliases exactly so Spring Data's interface-based projection binds them by
 * setter convention.
 */
public interface RealtyGroupCardProjection {
    UUID getPublicId();
    String getName();
    String getSlug();
    String getDescription();
    String getLogoUrl();
    String getCoverUrl();
    OffsetDateTime getCreatedAt();
    int getMemberCount();
    int getMemberSeatLimit();
    long getActiveListings();
    long getCompletedSales();
    BigDecimal getAverageRating();
    long getReviewCount();
}
```

- [ ] **Step 4: Add the repo method**

Edit `RealtyGroupRepository.java` — add after the existing methods:

```java
@Query(value = """
    SELECT
      g.public_id AS publicId,
      g.name AS name,
      g.slug AS slug,
      g.description AS description,
      g.logo_url AS logoUrl,
      g.cover_url AS coverUrl,
      g.created_at AS createdAt,
      g.member_count AS memberCount,
      g.member_seat_limit AS memberSeatLimit,
      (SELECT count(*) FROM auctions a
         WHERE a.realty_group_id = g.id
           AND a.status IN ('SCHEDULED','LIVE')) AS activeListings,
      (SELECT count(*) FROM auctions a
         WHERE a.realty_group_id = g.id
           AND a.status = 'COMPLETED') AS completedSales,
      (SELECT avg(r.rating) FROM reviews r
         JOIN auctions a ON r.auction_id = a.id
         WHERE a.realty_group_id = g.id) AS averageRating,
      (SELECT count(*) FROM reviews r
         JOIN auctions a ON r.auction_id = a.id
         WHERE a.realty_group_id = g.id) AS reviewCount
    FROM realty_groups g
    WHERE g.dissolved_at IS NULL
      AND EXISTS (SELECT 1 FROM realty_group_sl_groups s
                   WHERE s.realty_group_id = g.id AND s.verified = TRUE)
      AND NOT EXISTS (SELECT 1 FROM realty_group_suspensions sus
                       WHERE sus.realty_group_id = g.id
                         AND sus.lifted_at IS NULL)
      AND (:q IS NULL OR LOWER(g.name) LIKE CONCAT('%', LOWER(CAST(:q AS text)), '%')
                      OR LOWER(g.description) LIKE CONCAT('%', LOWER(CAST(:q AS text)), '%'))
    """,
    countQuery = """
    SELECT count(*)
    FROM realty_groups g
    WHERE g.dissolved_at IS NULL
      AND EXISTS (SELECT 1 FROM realty_group_sl_groups s
                   WHERE s.realty_group_id = g.id AND s.verified = TRUE)
      AND NOT EXISTS (SELECT 1 FROM realty_group_suspensions sus
                       WHERE sus.realty_group_id = g.id
                         AND sus.lifted_at IS NULL)
      AND (:q IS NULL OR LOWER(g.name) LIKE CONCAT('%', LOWER(CAST(:q AS text)), '%')
                      OR LOWER(g.description) LIKE CONCAT('%', LOWER(CAST(:q AS text)), '%'))
    """,
    nativeQuery = true)
org.springframework.data.domain.Page<RealtyGroupCardProjection> browseCards(
    @org.springframework.data.repository.query.Param("q") String q,
    org.springframework.data.domain.Pageable pageable);
```

- [ ] **Step 5: Run the test to confirm it passes**

```bash
./mvnw test -Dtest=RealtyGroupBrowseQueryTest
```

Expected: 1 test, 0 failures.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/realty/browse/RealtyGroupCardProjection.java \
        backend/src/main/java/com/slparcelauctions/backend/realty/RealtyGroupRepository.java \
        backend/src/test/java/com/slparcelauctions/backend/realty/browse/RealtyGroupBrowseQueryTest.java
git commit -m "feat(groups): browseCards repo query with verified/non-suspended/search filters"
```

---

## Task 3: `RealtyGroupBrowseService` — projection → DTO + tagline truncation

**Files:**
- Create: `backend/src/main/java/com/slparcelauctions/backend/realty/browse/RealtyGroupBrowseService.java`
- Create: `backend/src/test/java/com/slparcelauctions/backend/realty/browse/RealtyGroupBrowseServiceTest.java`

- [ ] **Step 1: Write failing service test**

```java
package com.slparcelauctions.backend.realty.browse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import com.slparcelauctions.backend.realty.RealtyGroupRepository;

class RealtyGroupBrowseServiceTest {

    private final RealtyGroupRepository repo = mock(RealtyGroupRepository.class);
    private final RealtyGroupBrowseService service = new RealtyGroupBrowseService(repo);

    @Test
    void taglineTruncatesAt120CharsWithEllipsis() {
        String longDescription = "a".repeat(200);
        RealtyGroupCardProjection p = projection("g", longDescription);
        when(repo.browseCards(eq(null), any()))
            .thenReturn(new PageImpl<>(List.of(p)));

        var result = service.browse(null, GroupsSortKey.RATING, PageRequest.of(0, 20));

        assertThat(result.getContent().get(0).tagline())
            .hasSize(121)
            .endsWith("...");
    }

    @Test
    void taglineLeavesShortDescriptionsUnchanged() {
        RealtyGroupCardProjection p = projection("g", "short");
        when(repo.browseCards(eq(null), any()))
            .thenReturn(new PageImpl<>(List.of(p)));

        var result = service.browse(null, GroupsSortKey.RATING, PageRequest.of(0, 20));

        assertThat(result.getContent().get(0).tagline()).isEqualTo("short");
    }

    @Test
    void mapsSortKeyToRepoSortOrder() {
        when(repo.browseCards(eq(null), any()))
            .thenReturn(Page.empty());

        service.browse(null, GroupsSortKey.MOST_ACTIVE_LISTINGS, PageRequest.of(0, 20));

        org.mockito.ArgumentCaptor<org.springframework.data.domain.Pageable> captor =
            org.mockito.ArgumentCaptor.forClass(org.springframework.data.domain.Pageable.class);
        org.mockito.Mockito.verify(repo).browseCards(eq(null), captor.capture());
        Sort sort = captor.getValue().getSort();
        assertThat(sort.getOrderFor("activeListings")).isNotNull();
        assertThat(sort.getOrderFor("activeListings").getDirection())
            .isEqualTo(Sort.Direction.DESC);
    }

    private static RealtyGroupCardProjection projection(String name, String description) {
        return new RealtyGroupCardProjection() {
            public UUID getPublicId() {
                return UUID.fromString("00000000-0000-0000-0000-000000000001");
            }
            public String getName() { return name; }
            public String getSlug() { return name; }
            public String getDescription() { return description; }
            public String getLogoUrl() { return null; }
            public String getCoverUrl() { return null; }
            public OffsetDateTime getCreatedAt() {
                return OffsetDateTime.parse("2026-01-01T00:00:00Z");
            }
            public int getMemberCount() { return 1; }
            public int getMemberSeatLimit() { return 8; }
            public long getActiveListings() { return 0L; }
            public long getCompletedSales() { return 0L; }
            public BigDecimal getAverageRating() { return null; }
            public long getReviewCount() { return 0L; }
        };
    }
}
```

- [ ] **Step 2: Run the test to confirm it fails**

```bash
./mvnw test -Dtest=RealtyGroupBrowseServiceTest
```

Expected: FAIL with `cannot find symbol RealtyGroupBrowseService`.

- [ ] **Step 3: Create the service**

```java
package com.slparcelauctions.backend.realty.browse;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.slparcelauctions.backend.realty.RealtyGroupRepository;
import com.slparcelauctions.backend.realty.browse.dto.RealtyGroupCardDto;
import com.slparcelauctions.backend.realty.rating.dto.GroupRatingDto;

import lombok.RequiredArgsConstructor;

/**
 * Service backing the public {@code GET /api/v1/realty-groups} browse
 * endpoint. Translates the {@link GroupsSortKey} into a {@link Sort} order
 * the native query understands, calls
 * {@link RealtyGroupRepository#browseCards}, and maps each row from
 * {@link RealtyGroupCardProjection} to {@link RealtyGroupCardDto} with the
 * description truncated to {@value #TAGLINE_MAX_CHARS} chars + ellipsis.
 */
@Service
@RequiredArgsConstructor
public class RealtyGroupBrowseService {

    static final int TAGLINE_MAX_CHARS = 120;

    private final RealtyGroupRepository repo;

    @Transactional(readOnly = true)
    public Page<RealtyGroupCardDto> browse(String q, GroupsSortKey sort, Pageable pageable) {
        Pageable sorted = applySort(pageable, sort);
        return repo.browseCards(q, sorted).map(this::toDto);
    }

    private Pageable applySort(Pageable original, GroupsSortKey sort) {
        Sort.Order primary = switch (sort) {
            case RATING -> new Sort.Order(Sort.Direction.DESC, "averageRating", Sort.NullHandling.NULLS_LAST);
            case NEWEST -> Sort.Order.desc("createdAt");
            case MOST_ACTIVE_LISTINGS -> Sort.Order.desc("activeListings");
            case MOST_SALES -> Sort.Order.desc("completedSales");
        };
        Sort.Order tiebreak = Sort.Order.asc("name");
        return PageRequest.of(
            original.getPageNumber(),
            original.getPageSize(),
            Sort.by(primary, tiebreak));
    }

    private RealtyGroupCardDto toDto(RealtyGroupCardProjection p) {
        return new RealtyGroupCardDto(
            p.getPublicId(),
            p.getName(),
            p.getSlug(),
            tagline(p.getDescription()),
            p.getLogoUrl(),
            p.getCoverUrl(),
            p.getCreatedAt(),
            p.getMemberCount(),
            p.getMemberSeatLimit(),
            (int) p.getActiveListings(),
            (int) p.getCompletedSales(),
            new GroupRatingDto(p.getAverageRating(), (int) p.getReviewCount()));
    }

    private static String tagline(String description) {
        if (description == null) return "";
        if (description.length() <= TAGLINE_MAX_CHARS) return description;
        return description.substring(0, TAGLINE_MAX_CHARS) + "...";
    }
}
```

- [ ] **Step 4: Run the test to confirm it passes**

```bash
./mvnw test -Dtest=RealtyGroupBrowseServiceTest
```

Expected: 3 tests, 0 failures.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/realty/browse/RealtyGroupBrowseService.java \
        backend/src/test/java/com/slparcelauctions/backend/realty/browse/RealtyGroupBrowseServiceTest.java
git commit -m "feat(groups): RealtyGroupBrowseService -- projection mapping + tagline truncation + sort"
```

---

## Task 4: `GET /api/v1/realty-groups` controller endpoint

**Files:**
- Create: `backend/src/main/java/com/slparcelauctions/backend/realty/browse/RealtyGroupBrowseController.java`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/config/SecurityConfig.java` (allow anonymous on `GET /api/v1/realty-groups`)
- Create: `backend/src/test/java/com/slparcelauctions/backend/realty/browse/RealtyGroupBrowseControllerTest.java`

- [ ] **Step 1: Write failing controller test**

```java
package com.slparcelauctions.backend.realty.browse;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@TestPropertySource(properties = {
    "auth.cleanup.enabled=false",
    "slpa.auction-end.enabled=false",
    "slpa.notifications.cleanup.enabled=false",
    "slpa.notifications.sl-im.cleanup.enabled=false",
    "slpa.realty.invitation-expiry.enabled=false"
})
class RealtyGroupBrowseControllerTest {

    @Autowired MockMvc mockMvc;

    @Test
    void anonymousCallerGets200WithEmptyPage() throws Exception {
        mockMvc.perform(get("/api/v1/realty-groups"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content").isArray())
            .andExpect(jsonPath("$.totalElements").exists());
    }

    @Test
    void unknownSortReturns400() throws Exception {
        mockMvc.perform(get("/api/v1/realty-groups").param("sort", "BOGUS"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void sizeAboveMaxIsClampedTo60() throws Exception {
        mockMvc.perform(get("/api/v1/realty-groups").param("size", "999"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.size").value(60));
    }
}
```

- [ ] **Step 2: Run the test to confirm it fails**

```bash
./mvnw test -Dtest=RealtyGroupBrowseControllerTest
```

Expected: FAIL with `cannot find symbol RealtyGroupBrowseController` (or 404 on the route).

- [ ] **Step 3: Create the controller**

```java
package com.slparcelauctions.backend.realty.browse;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.slparcelauctions.backend.realty.browse.dto.RealtyGroupCardDto;

import lombok.RequiredArgsConstructor;

/**
 * Public browse endpoint for the {@code /groups} directory page. Spec
 * section 6.1. Anonymous-accessible; SecurityConfig permits {@code GET
 * /api/v1/realty-groups}. Verified-only + non-suspended filters live in the
 * underlying query so they're impossible to disable from the wire.
 */
@RestController
@RequestMapping("/api/v1/realty-groups")
@RequiredArgsConstructor
public class RealtyGroupBrowseController {

    static final int MAX_PAGE_SIZE = 60;
    static final int DEFAULT_PAGE_SIZE = 20;

    private final RealtyGroupBrowseService service;

    @GetMapping
    public Page<RealtyGroupCardDto> list(
            @RequestParam(name = "q", required = false) String q,
            @RequestParam(name = "page", required = false, defaultValue = "0") int page,
            @RequestParam(name = "size", required = false, defaultValue = "20") int size,
            @RequestParam(name = "sort", required = false, defaultValue = "RATING") GroupsSortKey sort) {
        int effectivePage = Math.max(0, page);
        int effectiveSize = Math.min(Math.max(1, size), MAX_PAGE_SIZE);
        return service.browse(
            (q != null && !q.isBlank()) ? q.trim() : null,
            sort,
            PageRequest.of(effectivePage, effectiveSize));
    }
}
```

- [ ] **Step 4: Whitelist the path in SecurityConfig**

Edit `backend/src/main/java/com/slparcelauctions/backend/config/SecurityConfig.java` — find the existing `permitAll` chain (where `/api/v1/realty-groups/*/reviews` or `/api/v1/realty-groups/{publicId}` is whitelisted) and add a matcher for the bare list path:

```java
.requestMatchers(HttpMethod.GET, "/api/v1/realty-groups").permitAll()
```

The line goes before the catch-all `authenticated()` matcher. Order matters — specific matchers first.

- [ ] **Step 5: Run the test to confirm it passes**

```bash
./mvnw test -Dtest=RealtyGroupBrowseControllerTest
```

Expected: 3 tests, 0 failures.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/realty/browse/RealtyGroupBrowseController.java \
        backend/src/main/java/com/slparcelauctions/backend/config/SecurityConfig.java \
        backend/src/test/java/com/slparcelauctions/backend/realty/browse/RealtyGroupBrowseControllerTest.java
git commit -m "feat(groups): GET /api/v1/realty-groups browse endpoint (anonymous, paged, sorted)"
```

---

## Task 5: Reserved-slug validator [parallel-safe]

**Files:**
- Create: `backend/src/main/java/com/slparcelauctions/backend/realty/exception/ReservedSlugException.java`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/realty/slug/RealtyGroupSlugFactory.java` (verify path)
- Modify: `backend/src/main/java/com/slparcelauctions/backend/realty/exception/RealtyExceptionHandler.java`
- Modify: `backend/src/test/java/com/slparcelauctions/backend/realty/service/RealtyGroupServiceCreateTest.java` (verify path; add test cases)

- [ ] **Step 1: Write failing test asserting `new`, `me`, `invitations` are rejected**

In the existing create-service test class (or a new `RealtyGroupCreateReservedSlugTest`):

```java
@Test
void rejectsReservedSlugNew() {
    var req = new CreateRealtyGroupRequest("Whatever", "new", "desc", null);
    org.assertj.core.api.Assertions.assertThatThrownBy(() ->
        service.createGroup(req, callerUserId))
        .isInstanceOf(ReservedSlugException.class)
        .extracting("slug").isEqualTo("new");
}

@Test
void rejectsReservedSlugMe() {
    var req = new CreateRealtyGroupRequest("Whatever", "me", "desc", null);
    org.assertj.core.api.Assertions.assertThatThrownBy(() ->
        service.createGroup(req, callerUserId))
        .isInstanceOf(ReservedSlugException.class);
}

@Test
void rejectsReservedSlugInvitations() {
    var req = new CreateRealtyGroupRequest("Whatever", "invitations", "desc", null);
    org.assertj.core.api.Assertions.assertThatThrownBy(() ->
        service.createGroup(req, callerUserId))
        .isInstanceOf(ReservedSlugException.class);
}
```

- [ ] **Step 2: Run the tests to confirm they fail**

```bash
./mvnw test -Dtest=RealtyGroupServiceCreateTest
```

Expected: 3 new test methods fail (either `cannot find symbol ReservedSlugException` or wrong exception type).

- [ ] **Step 3: Create the exception class**

```java
package com.slparcelauctions.backend.realty.exception;

import lombok.Getter;

@Getter
public class ReservedSlugException extends RuntimeException {

    private final String slug;

    public ReservedSlugException(String slug) {
        super("Slug '" + slug + "' is reserved");
        this.slug = slug;
    }
}
```

- [ ] **Step 4: Add the reserved-slug check to the slug factory / create service**

Grep first to find where the slug-validation chain lives:

```bash
grep -rn "slug\b.*validate\|RealtyGroupSlugFactory\|reservedSlug" backend/src/main/java/com/slparcelauctions/backend/realty/
```

Then add to the validation site (most likely `RealtyGroupSlugFactory.validate()` or the entry of `RealtyGroupService.createGroup()`):

```java
private static final java.util.Set<String> RESERVED_SLUGS =
    java.util.Set.of("new", "me", "invitations");

// In the validation method, before the existing charset/length checks:
if (RESERVED_SLUGS.contains(slug)) {
    throw new ReservedSlugException(slug);
}
```

- [ ] **Step 5: Map the exception to 422 in `RealtyExceptionHandler`**

```java
@ExceptionHandler(ReservedSlugException.class)
public ResponseEntity<ProblemDetail> handleReservedSlug(ReservedSlugException e) {
    ProblemDetail pd = ProblemDetail.forStatusAndDetail(
        HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
    pd.setProperty("code", "RESERVED_SLUG");
    pd.setProperty("slug", e.getSlug());
    return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(pd);
}
```

- [ ] **Step 6: Run the tests to confirm they pass**

```bash
./mvnw test -Dtest=RealtyGroupServiceCreateTest
```

Expected: all tests in the class (existing + the 3 new) pass.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/realty/exception/ReservedSlugException.java \
        backend/src/main/java/com/slparcelauctions/backend/realty/exception/RealtyExceptionHandler.java \
        backend/src/main/java/com/slparcelauctions/backend/realty/slug/RealtyGroupSlugFactory.java \
        backend/src/test/java/com/slparcelauctions/backend/realty/service/RealtyGroupServiceCreateTest.java
git commit -m "feat(groups): reserved-slug validator rejects new/me/invitations"
```

---

## Task 6: Invitation notification `linkUrl` population [parallel-safe]

**Files:**
- Modify: `backend/src/main/java/com/slparcelauctions/backend/notification/NotificationPublisherImpl.java` (verify path; find the invitation publish method)
- Modify: associated test

- [ ] **Step 1: Locate the invitation notification publish call**

```bash
grep -rn "GROUP_INVITATION\|INVITATION_RECEIVED\|invitationReceived\|invitation.*publish" backend/src/main/java/com/slparcelauctions/backend/
```

Expected: at least one publisher method like `NotificationPublisher.invitationReceived(...)` or category constant `GROUP_INVITATION_RECEIVED` in `NotificationCategory`. Identify (a) the publish method, (b) the existing notification row's `linkUrl` field, (c) any existing test that asserts on the publish call.

- [ ] **Step 2: Write a failing test asserting the `linkUrl` is set**

In the publisher's existing test (add a new test case):

```java
@Test
void invitationReceived_setsLinkUrlToRecipientPage() {
    publisher.invitationReceived(invitee, group, inviter);

    org.mockito.ArgumentCaptor<Notification> captor =
        org.mockito.ArgumentCaptor.forClass(Notification.class);
    org.mockito.Mockito.verify(notificationRepo).save(captor.capture());
    assertThat(captor.getValue().getLinkUrl())
        .isEqualTo("/groups/invitations/me");
}
```

- [ ] **Step 3: Run the test to confirm it fails**

```bash
./mvnw test -Dtest=NotificationPublisherImplTest
```

Expected: FAIL (the existing `linkUrl` is null or empty).

- [ ] **Step 4: Set the `linkUrl` in the publisher**

Edit `NotificationPublisherImpl.invitationReceived(...)`. Find the `Notification.builder()` block; add:

```java
.linkUrl("/groups/invitations/me")
```

- [ ] **Step 5: Run the test to confirm it passes**

```bash
./mvnw test -Dtest=NotificationPublisherImplTest
```

Expected: green.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/notification/NotificationPublisherImpl.java \
        backend/src/test/java/com/slparcelauctions/backend/notification/NotificationPublisherImplTest.java
git commit -m "feat(groups): invitation-received notification linkUrl -> /groups/invitations/me"
```

---

## Task 7: V31 migration if `realty_groups.member_count` denorm is missing [parallel-safe, conditional]

**Files (conditional — only if the column doesn't exist):**
- Create: `backend/src/main/resources/db/migration/V31__realty_groups_member_count.sql`

- [ ] **Step 1: Verify whether the column exists**

```bash
grep -n "member_count\|memberCount" backend/src/main/java/com/slparcelauctions/backend/realty/RealtyGroup.java
```

If the entity has a `memberCount` field with `@Column(name = "member_count")` AND the existing `RealtyGroupPublicDto` carries `memberCount`, the column exists — skip this task entirely; mark it `[NO-OP]` in commits.

If the field is missing, continue with Step 2.

- [ ] **Step 2: Write the migration**

```sql
-- V31: denorm member_count on realty_groups for the browse-cards projection.
ALTER TABLE realty_groups
  ADD COLUMN IF NOT EXISTS member_count INTEGER NOT NULL DEFAULT 0;

-- Backfill from current membership rows.
UPDATE realty_groups g
   SET member_count = (
     SELECT count(*) FROM realty_group_members m
      WHERE m.realty_group_id = g.id
   );
```

- [ ] **Step 3: Wire the denorm in `RealtyGroupMembershipService`**

Wherever the service adds/removes a member row, increment/decrement `realty_groups.member_count` in the same transaction. (Touch the entity's `memberCount` field if added to the JPA mapping.)

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/resources/db/migration/V31__realty_groups_member_count.sql \
        backend/src/main/java/com/slparcelauctions/backend/realty/RealtyGroup.java \
        backend/src/main/java/com/slparcelauctions/backend/realty/service/RealtyGroupMembershipService.java
git commit -m "feat(groups): denorm realty_groups.member_count for browse projection"
```

If Step 1 confirmed the column exists, skip the entire task and continue to Part 2.

---

## Push Part 1 commits

```bash
git push
```

Backend foundation complete; Part 2 begins.
