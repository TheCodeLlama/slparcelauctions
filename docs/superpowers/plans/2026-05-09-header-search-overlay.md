# Header Search Overlay Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the disabled header search icon with a live-typeahead overlay that searches listing titles, parcel names, and region names; route Enter to `/browse?q=...` so the existing filter sidebar still applies.

**Architecture:** Backend gains `GET /api/v1/search/suggest?q=` (5 listings + 3 regions, grouped) plus a `q=` filter on the existing `/api/v1/auctions/search`. Both paths are public, hit `pg_trgm` GIN trigram indexes via native SQL on three columns (`auctions.title`, `auction_parcel_snapshots.parcel_name`, `regions.name`), and rank by `similarity()`. Frontend uses Headless UI `Combobox` inside a responsive wrapper (anchored panel ≥md, full-screen `Dialog` <md), debounced 250ms via TanStack Query.

**Tech Stack:** Spring Boot 4 / Java 26 / Postgres + pg_trgm / Flyway · Next.js 16 / React 19 / Headless UI 2 / TanStack Query / Vitest + MSW

**Spec:** `docs/superpowers/specs/2026-05-09-header-search-overlay-design.md` (committed at `79b281a5`).

---

## Task 1: Migration `V22__pg_trgm_search_indexes.sql` + index existence test

**Files:**
- Create: `backend/src/main/resources/db/migration/V22__pg_trgm_search_indexes.sql`
- Modify: `backend/src/test/java/com/slparcelauctions/backend/auction/search/PgIndexExistenceTest.java`

- [ ] **Step 1: Write the failing index existence test**

In `PgIndexExistenceTest.java`, extend the `required` set inside `every_promised_index_exists()`:

```java
Set<String> required = Set.of(
        "ix_auctions_status_ends_at",
        "ix_auctions_status_starts_at",
        // ... existing entries ...
        // NEW for pg_trgm search:
        "idx_auctions_title_trgm",
        "idx_parcel_snapshots_parcel_name_trgm",
        "idx_regions_name_trgm");
```

Add these three names alongside the existing required indexes (preserve all existing entries — read the current file first and append).

- [ ] **Step 2: Run the test to verify it fails**

Run from `backend/`:
```
./mvnw test -Dtest=PgIndexExistenceTest#every_promised_index_exists
```

Expected: FAIL because the three trigram indexes are missing.

- [ ] **Step 3: Create the migration**

Create `backend/src/main/resources/db/migration/V22__pg_trgm_search_indexes.sql`:

```sql
-- pg_trgm trigram indexes for the header search overlay (spec
-- 2026-05-09-header-search-overlay-design §5.5). Powers ILIKE
-- substring matches and similarity() ranking on listing titles,
-- parcel names, and region names. pg_trgm is bundled with Postgres
-- and is one of the default extensions enabled on AWS RDS PG.
CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE INDEX IF NOT EXISTS idx_auctions_title_trgm
    ON public.auctions USING gin (title gin_trgm_ops);

CREATE INDEX IF NOT EXISTS idx_parcel_snapshots_parcel_name_trgm
    ON public.auction_parcel_snapshots USING gin (parcel_name gin_trgm_ops);

CREATE INDEX IF NOT EXISTS idx_regions_name_trgm
    ON public.regions USING gin (name gin_trgm_ops);
```

- [ ] **Step 4: Run the test to verify it passes**

Run from `backend/`:
```
./mvnw test -Dtest=PgIndexExistenceTest#every_promised_index_exists
```

Expected: PASS — the migration applies on test container startup; all three indexes resolve.

- [ ] **Step 5: Commit**

```
git add backend/src/main/resources/db/migration/V22__pg_trgm_search_indexes.sql backend/src/test/java/com/slparcelauctions/backend/auction/search/PgIndexExistenceTest.java
git commit -m "feat(search): pg_trgm indexes for typeahead suggest path"
```

---

## Task 2: Backend Suggest DTOs

**Files:**
- Create: `backend/src/main/java/com/slparcelauctions/backend/auction/search/suggest/SuggestListingDto.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/auction/search/suggest/SuggestRegionDto.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/auction/search/suggest/SuggestResponse.java`

- [ ] **Step 1: Create `SuggestListingDto`**

```java
package com.slparcelauctions.backend.auction.search.suggest;

import java.util.UUID;

/**
 * One row in the suggest popover's "Listings" group. The {@code
 * primaryPhotoUrl} is the relative path the frontend wraps in
 * {@code apiUrl()}; null when the listing has no photo.
 */
public record SuggestListingDto(
        UUID publicId,
        String title,
        String regionName,
        String parcelName,
        String primaryPhotoUrl,
        long currentBid) {
}
```

- [ ] **Step 2: Create `SuggestRegionDto`**

```java
package com.slparcelauctions.backend.auction.search.suggest;

/**
 * One row in the suggest popover's "Regions" group. Click navigates
 * to {@code /browse?region=<name>} on the frontend.
 */
public record SuggestRegionDto(String name, int activeAuctionCount) {
}
```

- [ ] **Step 3: Create `SuggestResponse`**

```java
package com.slparcelauctions.backend.auction.search.suggest;

import java.util.List;

/**
 * Envelope returned by {@code GET /api/v1/search/suggest}. {@code
 * totalListings} powers the popover's "See all N results" footer; the
 * footer is suppressed when it equals {@code listings.size()} because
 * everything is already on screen.
 */
public record SuggestResponse(
        List<SuggestListingDto> listings,
        List<SuggestRegionDto> regions,
        int totalListings) {

    public static SuggestResponse empty() {
        return new SuggestResponse(List.of(), List.of(), 0);
    }
}
```

- [ ] **Step 4: Verify compile**

```
./mvnw -DskipTests compile
```

Expected: BUILD SUCCESS.

- [ ] **Step 5: Commit**

```
git add backend/src/main/java/com/slparcelauctions/backend/auction/search/suggest/
git commit -m "feat(search): suggest endpoint DTOs"
```

---

## Task 3: SearchSuggestRepository (native SQL)

**Files:**
- Create: `backend/src/main/java/com/slparcelauctions/backend/auction/search/suggest/SearchSuggestRepository.java`
- Create: `backend/src/test/java/com/slparcelauctions/backend/auction/search/suggest/SearchSuggestRepositoryTest.java`

- [ ] **Step 1: Write the failing repository test**

```java
package com.slparcelauctions.backend.auction.search.suggest;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import com.slparcelauctions.backend.auction.Auction;
import com.slparcelauctions.backend.auction.AuctionParcelSnapshot;
import com.slparcelauctions.backend.auction.AuctionRepository;
import com.slparcelauctions.backend.auction.AuctionStatus;
import com.slparcelauctions.backend.auction.VerificationTier;
import com.slparcelauctions.backend.region.Region;
import com.slparcelauctions.backend.region.RegionRepository;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

@SpringBootTest
@ActiveProfiles("dev")
@TestPropertySource(properties = {
        "auth.cleanup.enabled=false",
        "slpa.notifications.cleanup.enabled=false",
        "slpa.notifications.sl-im.cleanup.enabled=false"
})
@Transactional
class SearchSuggestRepositoryTest {

    @Autowired SearchSuggestRepository repo;
    @Autowired UserRepository userRepo;
    @Autowired RegionRepository regionRepo;
    @Autowired AuctionRepository auctionRepo;

    private User seller;

    @BeforeEach
    void setUp() {
        seller = userRepo.save(User.builder()
                .username("u-" + UUID.randomUUID().toString().substring(0, 8))
                .email("seller-" + UUID.randomUUID() + "@ex.com")
                .passwordHash("x")
                .slAvatarUuid(UUID.randomUUID())
                .displayName("Seller")
                .verified(true).build());
    }

    @Test
    void matchesByTitle() {
        seedActive("Tula", "Beachfront retreat", "Premium Waterfront");
        seedActive("Luna", "Skybox parking", "Industrial Lot");
        List<SuggestListingDto> hits = repo.findListings("waterfront", 5);
        assertThat(hits).extracting(SuggestListingDto::title)
                .containsExactly("Premium Waterfront");
    }

    @Test
    void matchesByParcelName() {
        seedActive("Tula", "Beachfront retreat", "Generic Title");
        List<SuggestListingDto> hits = repo.findListings("beachfront", 5);
        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).parcelName()).isEqualTo("Beachfront retreat");
    }

    @Test
    void matchesByRegionName() {
        seedActive("Tula", "x", "Generic Title");
        seedActive("Luna", "y", "Other Title");
        List<SuggestListingDto> hits = repo.findListings("tula", 5);
        assertThat(hits).extracting(SuggestListingDto::regionName)
                .containsExactly("Tula");
    }

    @Test
    void cappedAtLimit() {
        for (int i = 0; i < 8; i++) {
            seedActive("Region" + i, "x", "Waterfront " + i);
        }
        List<SuggestListingDto> hits = repo.findListings("waterfront", 5);
        assertThat(hits).hasSize(5);
    }

    @Test
    void excludesNonActive() {
        Auction draft = seedActive("Tula", "x", "Waterfront Draft");
        draft.setStatus(AuctionStatus.DRAFT);
        auctionRepo.save(draft);
        List<SuggestListingDto> hits = repo.findListings("waterfront", 5);
        assertThat(hits).isEmpty();
    }

    @Test
    void regionsExcludeRegionsWithoutActiveAuctions() {
        regionRepo.save(Region.builder()
                .slUuid(UUID.randomUUID()).name("EmptyRegion")
                .gridX(0.0).gridY(0.0).maturityRating("GENERAL").build());
        seedActive("Tula", "x", "Listed");
        List<SuggestRegionDto> hits = repo.findRegions("region", 3);
        assertThat(hits).extracting(SuggestRegionDto::name)
                .doesNotContain("EmptyRegion");
    }

    @Test
    void regionsCount_reflectsActiveAuctions() {
        seedActive("Tula", "x", "L1");
        seedActive("Tula", "y", "L2");
        List<SuggestRegionDto> hits = repo.findRegions("tula", 3);
        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).activeAuctionCount()).isEqualTo(2);
    }

    @Test
    void totalListings_countsAllMatches_notCappedAtLimit() {
        for (int i = 0; i < 8; i++) {
            seedActive("Region" + i, "x", "Waterfront " + i);
        }
        int total = repo.countListings("waterfront");
        assertThat(total).isEqualTo(8);
    }

    private Auction seedActive(String regionName, String parcelName, String title) {
        Region region = regionRepo.findByNameIgnoreCase(regionName)
                .orElseGet(() -> regionRepo.save(Region.builder()
                        .slUuid(UUID.randomUUID()).name(regionName)
                        .gridX(0.0).gridY(0.0)
                        .maturityRating("GENERAL").build()));
        UUID parcelUuid = UUID.randomUUID();
        Auction a = auctionRepo.save(Auction.builder()
                .slParcelUuid(parcelUuid)
                .seller(seller).title(title)
                .status(AuctionStatus.ACTIVE)
                .startingBid(1000L).currentBid(1000L)
                .endsAt(OffsetDateTime.now().plusDays(7))
                .durationHours(168)
                .snipeProtect(false)
                .verificationTier(VerificationTier.BOT)
                .build());
        a.setParcelSnapshot(AuctionParcelSnapshot.builder()
                .slParcelUuid(parcelUuid)
                .region(region)
                .regionName(regionName)
                .regionMaturityRating("GENERAL")
                .areaSqm(1024)
                .ownerType("agent")
                .ownerName("Owner")
                .parcelName(parcelName)
                .build());
        return auctionRepo.save(a);
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

```
./mvnw test -Dtest=SearchSuggestRepositoryTest
```

Expected: FAIL with "SearchSuggestRepository does not exist" / compilation error.

- [ ] **Step 3: Implement `SearchSuggestRepository`**

```java
package com.slparcelauctions.backend.auction.search.suggest;

import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import lombok.RequiredArgsConstructor;

/**
 * Native-SQL queries powering {@link SearchSuggestController}. Uses
 * {@code JdbcTemplate} directly rather than Spring Data Specifications
 * because the suggest path benefits from explicit {@code similarity()}
 * ordering — Criteria API doesn't express it cleanly. All three queries
 * hit the {@code pg_trgm} GIN indexes added in V22.
 */
@Repository
@RequiredArgsConstructor
public class SearchSuggestRepository {

    private final JdbcTemplate jdbc;

    /**
     * Top-N ACTIVE listings whose title, parcel name, or region name
     * substring-matches {@code raw}. Ranked by trigram similarity; ties
     * fall back to insertion order.
     */
    public List<SuggestListingDto> findListings(String raw, int limit) {
        String pattern = "%" + raw.toLowerCase() + "%";
        String sql = """
                SELECT a.public_id, a.title, ps.region_name,
                       ps.parcel_name, ph.public_id AS photo_public_id,
                       a.current_bid
                FROM auctions a
                JOIN auction_parcel_snapshots ps ON ps.auction_id = a.id
                LEFT JOIN auction_photos ph
                       ON ph.auction_id = a.id AND ph.sort_order = 0
                WHERE a.status = 'ACTIVE'
                  AND (LOWER(a.title) LIKE ?
                       OR LOWER(ps.parcel_name) LIKE ?
                       OR LOWER(ps.region_name) LIKE ?)
                ORDER BY GREATEST(
                    similarity(a.title, ?),
                    similarity(COALESCE(ps.parcel_name, ''), ?),
                    similarity(ps.region_name, ?)
                ) DESC
                LIMIT ?
                """;
        return jdbc.query(sql,
                (rs, i) -> new SuggestListingDto(
                        UUID.fromString(rs.getString("public_id")),
                        rs.getString("title"),
                        rs.getString("region_name"),
                        rs.getString("parcel_name"),
                        photoUrl(rs.getString("photo_public_id")),
                        rs.getLong("current_bid")),
                pattern, pattern, pattern, raw, raw, raw, limit);
    }

    /**
     * Top-N region names with at least one ACTIVE auction whose name
     * substring-matches {@code raw}. The {@code activeAuctionCount}
     * surfaces in the popover as "{n} active".
     */
    public List<SuggestRegionDto> findRegions(String raw, int limit) {
        String pattern = "%" + raw.toLowerCase() + "%";
        String sql = """
                SELECT r.name AS name, COUNT(a.id) AS active_count
                FROM regions r
                JOIN auction_parcel_snapshots ps ON ps.region_id = r.id
                JOIN auctions a ON a.id = ps.auction_id AND a.status = 'ACTIVE'
                WHERE LOWER(r.name) LIKE ?
                GROUP BY r.name
                ORDER BY similarity(r.name, ?) DESC
                LIMIT ?
                """;
        return jdbc.query(sql,
                (rs, i) -> new SuggestRegionDto(
                        rs.getString("name"),
                        rs.getInt("active_count")),
                pattern, raw, limit);
    }

    /**
     * Total count of ACTIVE auctions matching the same predicate the
     * listings query uses. Powers the popover's "See all N results"
     * footer; the footer is hidden when this equals the number of rows
     * already returned by {@link #findListings}.
     */
    public int countListings(String raw) {
        String pattern = "%" + raw.toLowerCase() + "%";
        String sql = """
                SELECT COUNT(*)
                FROM auctions a
                JOIN auction_parcel_snapshots ps ON ps.auction_id = a.id
                WHERE a.status = 'ACTIVE'
                  AND (LOWER(a.title) LIKE ?
                       OR LOWER(ps.parcel_name) LIKE ?
                       OR LOWER(ps.region_name) LIKE ?)
                """;
        Integer count = jdbc.queryForObject(sql, Integer.class,
                pattern, pattern, pattern);
        return count == null ? 0 : count;
    }

    private static String photoUrl(String publicId) {
        return publicId == null ? null : "/api/v1/photos/" + publicId;
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

```
./mvnw test -Dtest=SearchSuggestRepositoryTest
```

Expected: PASS — 8 tests green.

- [ ] **Step 5: Commit**

```
git add backend/src/main/java/com/slparcelauctions/backend/auction/search/suggest/SearchSuggestRepository.java backend/src/test/java/com/slparcelauctions/backend/auction/search/suggest/SearchSuggestRepositoryTest.java
git commit -m "feat(search): SearchSuggestRepository native trigram queries"
```

---

## Task 4: SearchSuggestService

**Files:**
- Create: `backend/src/main/java/com/slparcelauctions/backend/auction/search/suggest/SearchSuggestService.java`
- Create: `backend/src/test/java/com/slparcelauctions/backend/auction/search/suggest/SearchSuggestServiceTest.java`

- [ ] **Step 1: Write the failing service test**

```java
package com.slparcelauctions.backend.auction.search.suggest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SearchSuggestServiceTest {

    @Mock SearchSuggestRepository repo;
    @InjectMocks SearchSuggestService service;

    @Test
    void capsListingsAt5_andRegionsAt3() {
        when(repo.findListings("foo", 5)).thenReturn(List.of(
                listing("a"), listing("b"), listing("c"), listing("d"), listing("e")));
        when(repo.findRegions("foo", 3)).thenReturn(List.of(
                new SuggestRegionDto("Tula", 4),
                new SuggestRegionDto("Luna", 2),
                new SuggestRegionDto("Terra", 1)));
        when(repo.countListings("foo")).thenReturn(12);

        SuggestResponse r = service.suggest("foo");
        assertThat(r.listings()).hasSize(5);
        assertThat(r.regions()).hasSize(3);
        assertThat(r.totalListings()).isEqualTo(12);
    }

    @Test
    void totalListings_isRepositoryCount_notListSize() {
        when(repo.findListings("foo", 5)).thenReturn(List.of(listing("a")));
        when(repo.findRegions("foo", 3)).thenReturn(List.of());
        when(repo.countListings("foo")).thenReturn(42);

        SuggestResponse r = service.suggest("foo");
        assertThat(r.listings()).hasSize(1);
        assertThat(r.totalListings()).isEqualTo(42);
    }

    private static SuggestListingDto listing(String title) {
        return new SuggestListingDto(
                UUID.randomUUID(), title, "Tula",
                "Parcel", null, 1000L);
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

```
./mvnw test -Dtest=SearchSuggestServiceTest
```

Expected: FAIL — `SearchSuggestService` doesn't exist.

- [ ] **Step 3: Implement `SearchSuggestService`**

```java
package com.slparcelauctions.backend.auction.search.suggest;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

/**
 * Orchestrates the three native-SQL queries behind the suggest
 * endpoint. The hard caps (5 listings, 3 regions) are spec-fixed
 * (§5.2); only {@code totalListings} is unbounded so the popover footer
 * can advertise the full match count.
 */
@Service
@RequiredArgsConstructor
public class SearchSuggestService {

    private static final int LISTINGS_LIMIT = 5;
    private static final int REGIONS_LIMIT = 3;

    private final SearchSuggestRepository repo;

    public SuggestResponse suggest(String trimmed) {
        return new SuggestResponse(
                repo.findListings(trimmed, LISTINGS_LIMIT),
                repo.findRegions(trimmed, REGIONS_LIMIT),
                repo.countListings(trimmed));
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

```
./mvnw test -Dtest=SearchSuggestServiceTest
```

Expected: PASS — 2 tests green.

- [ ] **Step 5: Commit**

```
git add backend/src/main/java/com/slparcelauctions/backend/auction/search/suggest/SearchSuggestService.java backend/src/test/java/com/slparcelauctions/backend/auction/search/suggest/SearchSuggestServiceTest.java
git commit -m "feat(search): SearchSuggestService"
```

---

## Task 5: SearchSuggestController + permitAll + integration test

**Files:**
- Create: `backend/src/main/java/com/slparcelauctions/backend/auction/search/suggest/SearchSuggestController.java`
- Create: `backend/src/test/java/com/slparcelauctions/backend/auction/search/suggest/SearchSuggestControllerIntegrationTest.java`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/config/SecurityConfig.java`

- [ ] **Step 1: Write the failing integration test**

```java
package com.slparcelauctions.backend.auction.search.suggest;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.slparcelauctions.backend.auction.Auction;
import com.slparcelauctions.backend.auction.AuctionParcelSnapshot;
import com.slparcelauctions.backend.auction.AuctionRepository;
import com.slparcelauctions.backend.auction.AuctionStatus;
import com.slparcelauctions.backend.auction.VerificationTier;
import com.slparcelauctions.backend.region.Region;
import com.slparcelauctions.backend.region.RegionRepository;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@TestPropertySource(properties = {
        "auth.cleanup.enabled=false",
        "slpa.notifications.cleanup.enabled=false",
        "slpa.notifications.sl-im.cleanup.enabled=false"
})
@Transactional
class SearchSuggestControllerIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired UserRepository userRepo;
    @Autowired RegionRepository regionRepo;
    @Autowired AuctionRepository auctionRepo;

    private User seller;

    @BeforeEach
    void seed() {
        seller = userRepo.save(User.builder()
                .username("u-" + UUID.randomUUID().toString().substring(0, 8))
                .email("seller-" + UUID.randomUUID() + "@ex.com")
                .passwordHash("x")
                .slAvatarUuid(UUID.randomUUID())
                .displayName("Seller")
                .verified(true).build());
        seedActive("Tula", "Beachfront retreat", "Premium Waterfront");
        seedActive("Luna", "Skybox plot", "Modern Skybox");
    }

    @Test
    void publicEndpoint_noAuthRequired() throws Exception {
        mockMvc.perform(get("/api/v1/search/suggest").param("q", "tula"))
                .andExpect(status().isOk());
    }

    @Test
    void cacheControlHeader_15s() throws Exception {
        mockMvc.perform(get("/api/v1/search/suggest").param("q", "tula"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control",
                        org.hamcrest.Matchers.containsString("max-age=15")));
    }

    @Test
    void emptyQ_returnsEmptyEnvelope_with200() throws Exception {
        mockMvc.perform(get("/api/v1/search/suggest").param("q", ""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.listings").isEmpty())
                .andExpect(jsonPath("$.regions").isEmpty())
                .andExpect(jsonPath("$.totalListings").value(0));
    }

    @Test
    void singleCharQ_returnsEmptyEnvelope() throws Exception {
        mockMvc.perform(get("/api/v1/search/suggest").param("q", "t"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalListings").value(0));
    }

    @Test
    void matches_returnsListingsAndRegions() throws Exception {
        mockMvc.perform(get("/api/v1/search/suggest").param("q", "waterfront"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.listings[0].title").value("Premium Waterfront"));
    }

    private void seedActive(String regionName, String parcelName, String title) {
        Region region = regionRepo.findByNameIgnoreCase(regionName)
                .orElseGet(() -> regionRepo.save(Region.builder()
                        .slUuid(UUID.randomUUID()).name(regionName)
                        .gridX(0.0).gridY(0.0)
                        .maturityRating("GENERAL").build()));
        UUID parcelUuid = UUID.randomUUID();
        Auction a = auctionRepo.save(Auction.builder()
                .slParcelUuid(parcelUuid)
                .seller(seller).title(title)
                .status(AuctionStatus.ACTIVE)
                .startingBid(1000L).currentBid(1000L)
                .endsAt(OffsetDateTime.now().plusDays(7))
                .durationHours(168)
                .snipeProtect(false)
                .verificationTier(VerificationTier.BOT)
                .build());
        a.setParcelSnapshot(AuctionParcelSnapshot.builder()
                .slParcelUuid(parcelUuid).region(region)
                .regionName(regionName).regionMaturityRating("GENERAL")
                .areaSqm(1024).ownerType("agent").ownerName("Owner")
                .parcelName(parcelName)
                .build());
        auctionRepo.save(a);
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

```
./mvnw test -Dtest=SearchSuggestControllerIntegrationTest
```

Expected: FAIL — controller doesn't exist (or 401/404).

- [ ] **Step 3: Implement `SearchSuggestController`**

```java
package com.slparcelauctions.backend.auction.search.suggest;

import java.time.Duration;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

/**
 * Public typeahead endpoint for the header search overlay (spec
 * 2026-05-09-header-search-overlay-design §5.1). Returns at most 5
 * listings + 3 regions + a total-listings count, designed for the
 * popover row layout. Different latency / cardinality budget than
 * {@code /api/v1/auctions/search} — kept on its own path with its own
 * rate-limit bucket.
 */
@RestController
@RequestMapping("/api/v1/search/suggest")
@RequiredArgsConstructor
public class SearchSuggestController {

    private final SearchSuggestService service;

    @GetMapping
    public ResponseEntity<SuggestResponse> suggest(@RequestParam String q) {
        // Empty / short queries return an empty envelope without hitting
        // the DB. The frontend hook also gates on length>=2, but we
        // re-check because the contract is public.
        String trimmed = q == null ? "" : q.trim();
        if (trimmed.length() < 2) {
            return ResponseEntity.ok(SuggestResponse.empty());
        }
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofSeconds(15)).cachePublic())
                .body(service.suggest(trimmed));
    }
}
```

- [ ] **Step 4: Add `permitAll` for the suggest path**

In `backend/src/main/java/com/slparcelauctions/backend/config/SecurityConfig.java`, find the existing block that permits `/api/v1/auctions/search/**` (or similar public search endpoints) and add the suggest path beside it:

```java
.requestMatchers("/api/v1/auctions/search/**").permitAll()
.requestMatchers("/api/v1/search/suggest/**").permitAll()
```

(Read the actual file first; the exact builder chain may differ — preserve all surrounding rules.)

- [ ] **Step 5: Run the test to verify it passes**

```
./mvnw test -Dtest=SearchSuggestControllerIntegrationTest
```

Expected: PASS — 5 tests green.

- [ ] **Step 6: Commit**

```
git add backend/src/main/java/com/slparcelauctions/backend/auction/search/suggest/SearchSuggestController.java backend/src/main/java/com/slparcelauctions/backend/config/SecurityConfig.java backend/src/test/java/com/slparcelauctions/backend/auction/search/suggest/SearchSuggestControllerIntegrationTest.java
git commit -m "feat(search): SearchSuggestController + public permitAll"
```

---

## Task 6: Suggest rate limit (300 rpm/IP)

**Files:**
- Create: `backend/src/main/java/com/slparcelauctions/backend/config/ratelimit/SuggestRateLimitInterceptor.java`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/config/ratelimit/SearchRateLimitConfig.java`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/config/ratelimit/WebMvcRateLimitConfig.java`
- Modify: `backend/src/test/java/com/slparcelauctions/backend/auction/search/suggest/SearchSuggestControllerIntegrationTest.java`

- [ ] **Step 1: Write the failing rate-limit test**

Append to `SearchSuggestControllerIntegrationTest`:

```java
@Test
void exceedsBucket_returns429WithRetryAfter() throws Exception {
    // Bucket capacity is 300/min — exhaust it then expect 429.
    for (int i = 0; i < 300; i++) {
        mockMvc.perform(get("/api/v1/search/suggest").param("q", "tula"))
                .andExpect(status().isOk());
    }
    mockMvc.perform(get("/api/v1/search/suggest").param("q", "tula"))
            .andExpect(status().isTooManyRequests())
            .andExpect(header().exists("Retry-After"));
}
```

- [ ] **Step 2: Run the test — should fail with 200 on the 301st request**

```
./mvnw test -Dtest=SearchSuggestControllerIntegrationTest#exceedsBucket_returns429WithRetryAfter
```

Expected: FAIL (no rate limit applied yet).

- [ ] **Step 3: Add suggest bucket bean to `SearchRateLimitConfig`**

Read the existing `SearchRateLimitConfig.java`. Append a second `BucketConfiguration` bean alongside the existing `searchBucketConfiguration()`:

```java
@Bean
public BucketConfiguration suggestBucketConfiguration() {
    // 300 rpm/IP — typeahead amplifies request count ~10x compared to
    // the structured /search path, so its bucket is sized higher. 5
    // requests/sec sustained handles fast typing without throttling
    // a real user.
    return BucketConfiguration.builder()
            .addLimit(Bandwidth.builder()
                    .capacity(300)
                    .refillGreedy(300, Duration.ofMinutes(1))
                    .build())
            .build();
}
```

- [ ] **Step 4: Implement `SuggestRateLimitInterceptor`**

Read the existing `SearchRateLimitInterceptor` to copy the pattern, then create:

```java
package com.slparcelauctions.backend.config.ratelimit;

import java.nio.charset.StandardCharsets;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

/**
 * Per-IP rate limiter for {@code GET /api/v1/search/suggest}, sized at
 * 300 rpm to fit the typeahead burst pattern (vs. 60 rpm for the
 * structured search). Reuses the same Lettuce {@link ProxyManager} as
 * the search bucket; only the {@link BucketConfiguration} differs.
 */
@Component
@RequiredArgsConstructor
public class SuggestRateLimitInterceptor implements HandlerInterceptor {

    private static final String KEY_PREFIX = "slpa:suggest:rl:";

    private final ProxyManager<String> searchRateLimitProxyManager;
    @Qualifier("suggestBucketConfiguration")
    private final BucketConfiguration suggestBucketConfiguration;

    @Override
    public boolean preHandle(HttpServletRequest request,
                             HttpServletResponse response, Object handler) {
        String key = KEY_PREFIX + clientIp(request);
        ConsumptionProbe probe = searchRateLimitProxyManager
                .builder()
                .build(key, () -> suggestBucketConfiguration)
                .tryConsumeAndReturnRemaining(1);
        if (probe.isConsumed()) {
            response.addHeader("X-RateLimit-Remaining",
                    String.valueOf(probe.getRemainingTokens()));
            return true;
        }
        long retryAfterSeconds =
                Math.max(1, probe.getNanosToWaitForRefill() / 1_000_000_000L);
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.addHeader("Retry-After", String.valueOf(retryAfterSeconds));
        try {
            response.getWriter().write("""
                    {"status":429,"code":"TOO_MANY_REQUESTS","title":"Too Many Requests"}
                    """.getBytes(StandardCharsets.UTF_8).toString());
        } catch (Exception ignored) {
            // best-effort body; status + header already on the wire
        }
        return false;
    }

    private static String clientIp(HttpServletRequest req) {
        String xff = req.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            int comma = xff.indexOf(',');
            return (comma >= 0 ? xff.substring(0, comma) : xff).trim();
        }
        return req.getRemoteAddr();
    }
}
```

(Cross-check the existing `SearchRateLimitInterceptor` for any project-specific patterns — e.g. Lombok `@Qualifier` injection style, key prefix conventions — and align with them.)

- [ ] **Step 5: Wire the interceptor in `WebMvcRateLimitConfig`**

Read the existing file, then add the suggest interceptor alongside the search one. Pattern (adjust imports + names to match the existing builder chain):

```java
@Override
public void addInterceptors(InterceptorRegistry registry) {
    registry.addInterceptor(searchRateLimitInterceptor)
            .addPathPatterns("/api/v1/auctions/search/**");
    registry.addInterceptor(suggestRateLimitInterceptor)
            .addPathPatterns("/api/v1/search/suggest/**");
}
```

- [ ] **Step 6: Run the test to verify it passes**

```
./mvnw test -Dtest=SearchSuggestControllerIntegrationTest#exceedsBucket_returns429WithRetryAfter
```

Expected: PASS. (This test takes ~10s because of 300 sequential MockMvc calls; that's acceptable.)

- [ ] **Step 7: Run the full integration test class to confirm nothing regressed**

```
./mvnw test -Dtest=SearchSuggestControllerIntegrationTest
```

Expected: PASS — all 6 tests green.

- [ ] **Step 8: Commit**

```
git add backend/src/main/java/com/slparcelauctions/backend/config/ratelimit/SuggestRateLimitInterceptor.java backend/src/main/java/com/slparcelauctions/backend/config/ratelimit/SearchRateLimitConfig.java backend/src/main/java/com/slparcelauctions/backend/config/ratelimit/WebMvcRateLimitConfig.java backend/src/test/java/com/slparcelauctions/backend/auction/search/suggest/SearchSuggestControllerIntegrationTest.java
git commit -m "feat(search): 300-rpm rate-limit bucket for suggest path"
```

---

## Task 7: Extend `/api/v1/auctions/search` with `q=` filter

**Files:**
- Modify: `backend/src/main/java/com/slparcelauctions/backend/auction/search/AuctionSearchQuery.java`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/auction/search/AuctionSearchController.java`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/auction/search/AuctionSearchPredicateBuilder.java`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/auction/search/SearchCacheKey.java`
- Modify: `backend/src/test/java/com/slparcelauctions/backend/auction/search/AuctionSearchPredicateBuilderTest.java`
- Modify: `backend/src/test/java/com/slparcelauctions/backend/auction/search/AuctionSearchControllerIntegrationTest.java`
- Modify: `backend/src/test/java/com/slparcelauctions/backend/auction/search/SearchCacheKeyTest.java`
- Modify (touch only): `backend/src/test/java/com/slparcelauctions/backend/auction/search/AuctionSearchQueryValidatorTest.java` (constructor calls add `null` for new q field)

- [ ] **Step 1: Write the failing predicate-builder tests**

Read `AuctionSearchPredicateBuilderTest.java`, find the existing seeding helpers, and append four new test methods:

```java
@Test
void qFilter_matchesByTitle() {
    seedActive("Tula", "Generic Plot", "Premium Waterfront");
    seedActive("Luna", "Generic Plot", "Skybox Modern");
    var spec = builder.build(query().q("waterfront").build());
    var hits = repo.findAll(spec);
    assertThat(hits).hasSize(1);
    assertThat(hits.get(0).getTitle()).isEqualTo("Premium Waterfront");
}

@Test
void qFilter_matchesByParcelName() {
    seedActive("Tula", "Beachfront retreat", "Generic");
    seedActive("Luna", "Industrial lot", "Other");
    var spec = builder.build(query().q("beachfront").build());
    var hits = repo.findAll(spec);
    assertThat(hits).extracting(a -> a.getParcelSnapshot().getParcelName())
            .containsExactly("Beachfront retreat");
}

@Test
void qFilter_matchesByRegionName() {
    seedActive("Tula", "x", "Generic");
    seedActive("Luna", "y", "Other");
    var spec = builder.build(query().q("tula").build());
    var hits = repo.findAll(spec);
    assertThat(hits).extracting(a -> a.getParcelSnapshot().getRegionName())
            .containsExactly("Tula");
}

@Test
void qFilter_blankIsNoOp() {
    seedActive("Tula", "x", "Generic");
    var spec = builder.build(query().q("").build());
    var hits = repo.findAll(spec);
    assertThat(hits).hasSize(1);
}
```

(Use whatever builder/seeding pattern already exists in the file — the snippets above assume one.)

- [ ] **Step 2: Run the tests — should fail because q field doesn't exist yet**

```
./mvnw test -Dtest=AuctionSearchPredicateBuilderTest#qFilter_matchesByTitle
```

Expected: FAIL with compilation error (no `q()` on the query record).

- [ ] **Step 3: Add `q` to `AuctionSearchQuery`**

```java
public record AuctionSearchQuery(
        String q,                              // NEW: nullable free-text query
        String region,
        Integer minArea, Integer maxArea,
        Long minPrice, Long maxPrice,
        Set<String> maturity,
        Set<ParcelTag> tags,
        TagsMode tagsMode,
        ReserveStatusFilter reserveStatus,
        SnipeProtectionFilter snipeProtection,
        Set<VerificationTier> verificationTier,
        Integer endingWithinHours,
        String nearRegion,
        Integer distance,
        Long sellerId,
        AuctionSearchSort sort,
        int page,
        int size) {
    // existing constants unchanged
}
```

- [ ] **Step 4: Update every constructor call in tests + production code**

Search for `new AuctionSearchQuery(` and add `null,` (or the test's q value) as the **first** argument. Most call sites are in:
- `AuctionSearchController.java` — controller plumbs `@RequestParam(required = false) String q` into the new field.
- `AuctionSearchQueryValidator.java` — pass-through into the rebuilt record at the end of `validate()`.
- Existing tests (`AuctionSearchQueryValidatorTest`, `AuctionSearchPredicateBuilderTest`, `SearchCacheKeyTest`, etc.).

`AuctionSearchController.java` change:

```java
@GetMapping
public ResponseEntity<SearchPagedResponse<AuctionSearchResultDto>> search(
        @RequestParam(required = false) String q,             // NEW
        @RequestParam(required = false) String region,
        // ... existing params ...
) {
    AuctionSearchQuery query = new AuctionSearchQuery(
            q,                                                // NEW
            region, minArea, maxArea, minPrice, maxPrice,
            // ... rest unchanged
            page, size);
    // unchanged below
}
```

- [ ] **Step 5: Add the `q` predicate to `AuctionSearchPredicateBuilder`**

In `addFilterPredicates`, after the existing `region` block, add:

```java
if (q.q() != null && !q.q().isBlank()) {
    String pattern = "%" + q.q().trim().toLowerCase() + "%";
    Predicate titleMatch  = cb.like(cb.lower(root.get("title")), pattern);
    Predicate parcelMatch = cb.like(cb.lower(parcel.<String>get("parcelName")), pattern);
    Predicate regionMatch = cb.like(cb.lower(region.<String>get("name")), pattern);
    predicates.add(cb.or(titleMatch, parcelMatch, regionMatch));
}
```

The `parcel` and `region` joins are already declared at the top of the method.

- [ ] **Step 6: Update `SearchCacheKey` to include `q`**

Read `SearchCacheKey.java`. Find the `keyFor` method that hashes the query; add `q.q()` to the hash input (before the existing `region` field for stable ordering). Pattern:

```java
public static String keyFor(AuctionSearchQuery q) {
    return digest(
            q.q(),                  // NEW — first input so existing keys diverge
            q.region(),
            q.minArea(), q.maxArea(),
            // ... existing
    );
}
```

- [ ] **Step 7: Add a SearchCacheKey test for `q`**

In `SearchCacheKeyTest.java`, add:

```java
@Test
void differentQ_producesDifferentKey() {
    AuctionSearchQuery a = baseQuery().q("foo").build();
    AuctionSearchQuery b = baseQuery().q("bar").build();
    assertThat(SearchCacheKey.keyFor(a)).isNotEqualTo(SearchCacheKey.keyFor(b));
}
```

(Use whatever `baseQuery()` builder already exists in the file.)

- [ ] **Step 8: Run the controller integration test for q**

Add to `AuctionSearchControllerIntegrationTest.java`:

```java
@Test
void qFilter_returns_matchingRows() throws Exception {
    // seed() already creates rows in regions Tula / Luna / Terra
    mockMvc.perform(get("/api/v1/auctions/search").param("q", "tula"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content[*].parcel.region").value(
                    org.hamcrest.Matchers.everyItem(
                            org.hamcrest.Matchers.equalToIgnoringCase("Tula"))));
}
```

- [ ] **Step 9: Run all the search tests**

```
./mvnw test -Dtest='AuctionSearch*Test,Search*Test'
```

Expected: PASS — every search-package test green, including the four new predicate-builder tests, the SearchCacheKey test, and the new controller q test.

- [ ] **Step 10: Commit**

```
git add backend/src/main/java/com/slparcelauctions/backend/auction/search/ backend/src/test/java/com/slparcelauctions/backend/auction/search/
git commit -m "feat(search): q= filter on /auctions/search"
```

---

## Task 8: Frontend — `useSearchSuggest` hook + API client + MSW handler

**Files:**
- Create: `frontend/src/lib/api/search-suggest.ts`
- Create: `frontend/src/hooks/useDebouncedValue.ts` (only if not already present — `grep` first)
- Create: `frontend/src/hooks/useSearchSuggest.ts`
- Create: `frontend/src/hooks/useSearchSuggest.test.tsx`
- Modify: `frontend/src/test/msw/handlers.ts`
- Modify: `frontend/src/test/msw/fixtures.ts`

- [ ] **Step 1: Verify whether `useDebouncedValue` already exists**

```
grep -r "useDebouncedValue" frontend/src
```

If it exists, skip Step 3 and import the existing one. If not, follow Step 3.

- [ ] **Step 2: Implement the API client**

`frontend/src/lib/api/search-suggest.ts`:

```ts
import { api } from "@/lib/api";

export type SuggestListing = {
  publicId: string;
  title: string;
  regionName: string;
  parcelName: string | null;
  primaryPhotoUrl: string | null;   // relative; wrap in apiUrl() at render
  currentBid: number;
};

export type SuggestRegion = {
  name: string;
  activeAuctionCount: number;
};

export type SuggestResponse = {
  listings: SuggestListing[];
  regions: SuggestRegion[];
  totalListings: number;
};

export const searchSuggestApi = {
  suggest: (q: string) =>
    api.get<SuggestResponse>(
      `/api/v1/search/suggest?q=${encodeURIComponent(q)}`,
    ),
};
```

- [ ] **Step 3: Implement `useDebouncedValue` (if needed)**

`frontend/src/hooks/useDebouncedValue.ts`:

```ts
"use client";

import { useEffect, useState } from "react";

/**
 * Returns {@code value} after it has stayed unchanged for {@code
 * delayMs}. Used by the search overlay so React Query keys stabilize
 * per "settled" input rather than firing per keystroke.
 */
export function useDebouncedValue<T>(value: T, delayMs: number): T {
  const [debounced, setDebounced] = useState(value);
  useEffect(() => {
    const id = setTimeout(() => setDebounced(value), delayMs);
    return () => clearTimeout(id);
  }, [value, delayMs]);
  return debounced;
}
```

- [ ] **Step 4: Write the failing hook test**

`frontend/src/hooks/useSearchSuggest.test.tsx`:

```tsx
import { describe, it, expect, beforeEach, afterEach, vi } from "vitest";
import { http, HttpResponse } from "msw";
import { renderHook, waitFor, act } from "@testing-library/react";
import { server } from "@/test/msw/server";
import { makeWrapper } from "@/test/render";
import { useSearchSuggest } from "./useSearchSuggest";
import { mockSuggestResponse } from "@/test/msw/fixtures";

describe("useSearchSuggest", () => {
  beforeEach(() => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
  });
  afterEach(() => {
    vi.useRealTimers();
  });

  it("does not fetch when query is < 2 chars", async () => {
    let calls = 0;
    server.use(
      http.get("*/api/v1/search/suggest", () => {
        calls += 1;
        return HttpResponse.json(mockSuggestResponse());
      }),
    );
    const { result } = renderHook(() => useSearchSuggest("a"), {
      wrapper: makeWrapper(),
    });
    await act(async () => {
      vi.advanceTimersByTime(300);
    });
    expect(result.current.isFetching).toBe(false);
    expect(calls).toBe(0);
  });

  it("debounces rapid keystrokes into a single fetch", async () => {
    let calls = 0;
    server.use(
      http.get("*/api/v1/search/suggest", ({ request }) => {
        calls += 1;
        const url = new URL(request.url);
        return HttpResponse.json(mockSuggestResponse({ q: url.searchParams.get("q") }));
      }),
    );
    const wrapper = makeWrapper();
    const { rerender, result } = renderHook((q: string) => useSearchSuggest(q), {
      wrapper,
      initialProps: "tu",
    });
    rerender("tul");
    rerender("tula");
    await act(async () => {
      vi.advanceTimersByTime(300);
    });
    await waitFor(() => expect(calls).toBe(1));
    expect(result.current.data).toBeDefined();
  });
});
```

- [ ] **Step 5: Add MSW fixtures + handler**

In `frontend/src/test/msw/fixtures.ts`, append:

```ts
import type { SuggestResponse } from "@/lib/api/search-suggest";

export function mockSuggestResponse(
  overrides: Partial<SuggestResponse> & { q?: string | null } = {},
): SuggestResponse {
  return {
    listings: [
      {
        publicId: "00000000-0000-0000-0000-000000000099",
        title: "Premium Waterfront",
        regionName: "Tula",
        parcelName: "Beachfront retreat",
        primaryPhotoUrl: "/api/v1/photos/aaaa",
        currentBid: 12000,
      },
    ],
    regions: [{ name: "Tula", activeAuctionCount: 4 }],
    totalListings: 1,
    ...overrides,
  };
}
```

In `frontend/src/test/msw/handlers.ts`, add a default-pass-through suggest handler beside the existing search handler:

```ts
http.get("*/api/v1/search/suggest", ({ request }) => {
  const url = new URL(request.url);
  const q = url.searchParams.get("q") ?? "";
  if (q.trim().length < 2) {
    return HttpResponse.json({ listings: [], regions: [], totalListings: 0 });
  }
  return HttpResponse.json(mockSuggestResponse());
}),
```

(Import `mockSuggestResponse` from `./fixtures` at the top.)

- [ ] **Step 6: Run the test to verify it fails**

```
cd frontend && npx vitest run src/hooks/useSearchSuggest.test.tsx
```

Expected: FAIL — `useSearchSuggest` doesn't exist.

- [ ] **Step 7: Implement `useSearchSuggest`**

`frontend/src/hooks/useSearchSuggest.ts`:

```ts
"use client";

import { useQuery } from "@tanstack/react-query";
import { searchSuggestApi } from "@/lib/api/search-suggest";
import { useDebouncedValue } from "./useDebouncedValue";

export const SEARCH_SUGGEST_KEY = ["search-suggest"] as const;

/**
 * Typeahead query for the header search overlay. Debounces 250ms so
 * the React Query key stabilizes per "settled" input — prevents a
 * fetch per keystroke. Gates on length >= 2 to skip the trivial
 * "user just opened the box" case (the backend re-checks).
 */
export function useSearchSuggest(rawQuery: string) {
  const debounced = useDebouncedValue(rawQuery, 250);
  const trimmed = debounced.trim();
  return useQuery({
    queryKey: [...SEARCH_SUGGEST_KEY, trimmed],
    queryFn: () => searchSuggestApi.suggest(trimmed),
    enabled: trimmed.length >= 2,
    staleTime: 30_000,
    gcTime: 5 * 60_000,
    retry: false,
  });
}
```

- [ ] **Step 8: Run the test to verify it passes**

```
npx vitest run src/hooks/useSearchSuggest.test.tsx
```

Expected: PASS — 2 tests green.

- [ ] **Step 9: Commit**

```
git add frontend/src/lib/api/search-suggest.ts frontend/src/hooks/useDebouncedValue.ts frontend/src/hooks/useSearchSuggest.ts frontend/src/hooks/useSearchSuggest.test.tsx frontend/src/test/msw/fixtures.ts frontend/src/test/msw/handlers.ts
git commit -m "feat(search): useSearchSuggest hook + MSW handlers"
```

---

## Task 9: Frontend — `SearchResultRow` + `SearchResultsList` + `SearchInput`

**Files:**
- Create: `frontend/src/components/search/SearchResultRow.tsx`
- Create: `frontend/src/components/search/SearchResultsList.tsx`
- Create: `frontend/src/components/search/SearchInput.tsx`

These primitives have no test files of their own — they're rendered (and tested) through `SearchOverlay` in Task 10. Keeping them as pure presentation children of the overlay matches the existing `components/listing/PhotoUploader` + `PhotoTile` split in the codebase.

- [ ] **Step 1: Implement `SearchResultRow`**

```tsx
"use client";

import Image from "next/image";
import { ParcelPin } from "@/components/ui/icons";
import { apiUrl } from "@/lib/api/url";
import { cn } from "@/lib/cn";
import type { SuggestListing, SuggestRegion } from "@/lib/api/search-suggest";

function ListingRow({ listing }: { listing: SuggestListing }) {
  const src = apiUrl(listing.primaryPhotoUrl) ?? null;
  return (
    <div className="flex items-center gap-3 px-3 py-2">
      <div className="relative h-10 w-10 shrink-0 overflow-hidden rounded-md bg-bg-muted">
        {src ? (
          // eslint-disable-next-line @next/next/no-img-element
          <img src={src} alt="" className="h-full w-full object-cover" />
        ) : (
          <ParcelPin className="m-2 size-6 text-fg-muted" />
        )}
      </div>
      <div className="min-w-0 flex-1">
        <div className="truncate text-sm font-semibold text-fg">{listing.title}</div>
        <div className="truncate font-mono text-[10.5px] text-fg-subtle">
          {listing.regionName} · L$ {listing.currentBid.toLocaleString()}
        </div>
      </div>
    </div>
  );
}

function RegionRow({ region }: { region: SuggestRegion }) {
  return (
    <div className="flex items-center gap-3 px-3 py-2">
      <div className="grid h-10 w-10 shrink-0 place-items-center rounded-md bg-bg-muted">
        <ParcelPin className="size-5 text-fg-muted" />
      </div>
      <div className="min-w-0 flex-1">
        <div className="truncate text-sm font-semibold text-fg">{region.name}</div>
        <div className="font-mono text-[10.5px] text-fg-subtle">
          {region.activeAuctionCount} active
        </div>
      </div>
    </div>
  );
}

export const SearchResultRow = {
  Listing: ListingRow,
  Region: RegionRow,
};
```

(If `ParcelPin` doesn't exist in `components/ui/icons.ts`, swap for whichever map-pin / land-related icon does — `MapPin` from lucide-react is the typical fallback. Verify via the icons.ts barrel before finalizing the import.)

- [ ] **Step 2: Implement `SearchInput`**

```tsx
"use client";

import { ComboboxInput } from "@headlessui/react";
import { Search } from "@/components/ui/icons";

export interface SearchInputProps {
  value: string;
  onChange: (next: string) => void;
  /** Called on Enter when no row is highlighted (Headless UI default
   *  Enter handles row selection). The overlay routes to /browse?q=. */
  onBareEnter: () => void;
  autoFocus?: boolean;
}

export function SearchInput({
  value,
  onChange,
  onBareEnter,
  autoFocus,
}: SearchInputProps) {
  return (
    <div className="flex items-center gap-2 px-3 h-12 border-b border-border bg-surface-raised">
      <Search className="size-4 text-fg-muted" aria-hidden />
      <ComboboxInput
        autoFocus={autoFocus}
        value={value}
        onChange={(e) => onChange(e.target.value)}
        onKeyDown={(e) => {
          // Headless UI handles Enter for active option selection. If
          // the listbox has no active option (i.e. user typed but
          // didn't arrow into a row), Enter routes to /browse?q=.
          if (e.key === "Enter") {
            const target = e.currentTarget;
            // Headless UI exposes aria-activedescendant when a row is
            // highlighted; check for its absence as the "bare" case.
            if (!target.getAttribute("aria-activedescendant")) {
              e.preventDefault();
              onBareEnter();
            }
          }
        }}
        placeholder="Search parcels, regions…"
        className="flex-1 bg-transparent text-sm text-fg placeholder:text-fg-muted focus:outline-none"
        autoComplete="off"
        spellCheck={false}
      />
    </div>
  );
}
```

- [ ] **Step 3: Implement `SearchResultsList`**

```tsx
"use client";

import { ComboboxOption, ComboboxOptions } from "@headlessui/react";
import type { UseQueryResult } from "@tanstack/react-query";
import { cn } from "@/lib/cn";
import type { SuggestResponse } from "@/lib/api/search-suggest";
import { SearchResultRow } from "./SearchResultRow";

export type SearchSelection =
  | { kind: "listing"; id: string }
  | { kind: "region"; name: string }
  | { kind: "browse"; q: string };

export interface SearchResultsListProps {
  state: Pick<UseQueryResult<SuggestResponse>, "data" | "isLoading" | "isError" | "isFetching">;
  trimmed: string;
}

function GroupHeader({ label }: { label: string }) {
  return (
    <div className="px-3 pt-3 pb-1 text-xs font-semibold uppercase tracking-wider text-fg-muted">
      {label}
    </div>
  );
}

function Skeleton() {
  return (
    <div className="px-3 py-2">
      <div className="h-10 w-full animate-pulse rounded-md bg-bg-muted" />
    </div>
  );
}

export function SearchResultsList({ state, trimmed }: SearchResultsListProps) {
  const { data, isLoading, isError } = state;

  if (trimmed.length < 2) {
    return <ComboboxOptions static className="py-2" />;
  }
  if (isError) {
    return (
      <ComboboxOptions static className="py-6 text-center text-sm text-fg-muted">
        Search is unavailable right now.
      </ComboboxOptions>
    );
  }
  if (isLoading || !data) {
    return (
      <ComboboxOptions static className="py-2">
        <Skeleton /><Skeleton /><Skeleton /><Skeleton />
      </ComboboxOptions>
    );
  }
  const empty = data.listings.length === 0 && data.regions.length === 0;
  if (empty) {
    return (
      <ComboboxOptions static className="py-6 text-center text-sm text-fg-muted">
        No matches for &ldquo;{trimmed}&rdquo;.
      </ComboboxOptions>
    );
  }
  return (
    <ComboboxOptions static className="py-2 max-h-[440px] overflow-y-auto">
      {data.listings.length > 0 && (
        <>
          <GroupHeader label="Listings" />
          {data.listings.map((l) => (
            <ComboboxOption
              key={l.publicId}
              value={{ kind: "listing", id: l.publicId } satisfies SearchSelection}
              className={({ focus }) =>
                cn("cursor-pointer", focus && "bg-bg-muted")
              }
            >
              <SearchResultRow.Listing listing={l} />
            </ComboboxOption>
          ))}
        </>
      )}
      {data.regions.length > 0 && (
        <>
          <GroupHeader label="Regions" />
          {data.regions.map((r) => (
            <ComboboxOption
              key={r.name}
              value={{ kind: "region", name: r.name } satisfies SearchSelection}
              className={({ focus }) =>
                cn("cursor-pointer", focus && "bg-bg-muted")
              }
            >
              <SearchResultRow.Region region={r} />
            </ComboboxOption>
          ))}
        </>
      )}
      {data.totalListings > data.listings.length && (
        <ComboboxOption
          value={{ kind: "browse", q: trimmed } satisfies SearchSelection}
          className={({ focus }) =>
            cn(
              "cursor-pointer border-t border-border px-3 py-2 text-sm text-brand",
              focus && "bg-bg-muted",
            )
          }
        >
          See all {data.totalListings} results for &ldquo;{trimmed}&rdquo; →
        </ComboboxOption>
      )}
    </ComboboxOptions>
  );
}
```

- [ ] **Step 4: Verify compile + types**

```
npx tsc --noEmit
```

Expected: no errors.

- [ ] **Step 5: Commit**

```
git add frontend/src/components/search/
git commit -m "feat(search): result row + list + input primitives"
```

---

## Task 10: Frontend — `SearchOverlay` (Combobox + mobile/desktop split + tests)

**Files:**
- Create: `frontend/src/components/search/SearchOverlay.tsx`
- Create: `frontend/src/components/search/SearchOverlay.test.tsx`
- Create: `frontend/src/hooks/useMediaQuery.ts` (only if not already present)

- [ ] **Step 1: Verify whether `useMediaQuery` already exists**

```
grep -r "useMediaQuery" frontend/src
```

If yes, skip Step 2 and import the existing one.

- [ ] **Step 2: Implement `useMediaQuery` (if needed)**

`frontend/src/hooks/useMediaQuery.ts`:

```ts
"use client";

import { useEffect, useState } from "react";

/**
 * SSR-safe media-query hook. On the server (and the first client
 * paint), returns {@code initial} so we don't mismatch hydration; the
 * effect runs after mount and updates to the real value.
 */
export function useMediaQuery(query: string, initial = false): boolean {
  const [matches, setMatches] = useState(initial);
  useEffect(() => {
    if (typeof window === "undefined") return;
    const mql = window.matchMedia(query);
    setMatches(mql.matches);
    const handler = (e: MediaQueryListEvent) => setMatches(e.matches);
    mql.addEventListener("change", handler);
    return () => mql.removeEventListener("change", handler);
  }, [query]);
  return matches;
}
```

- [ ] **Step 3: Write the failing overlay tests**

`frontend/src/components/search/SearchOverlay.test.tsx`:

```tsx
import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { http, HttpResponse } from "msw";
import { server } from "@/test/msw/server";
import {
  renderWithProviders,
  screen,
  userEvent,
  waitFor,
  act,
} from "@/test/render";
import { mockSuggestResponse } from "@/test/msw/fixtures";

const pushMock = vi.fn();

vi.mock("next/navigation", () => ({
  useRouter: () => ({ push: pushMock, replace: vi.fn(), prefetch: vi.fn() }),
  usePathname: () => "/",
  useSearchParams: () => new URLSearchParams(),
}));

import { SearchOverlay } from "./SearchOverlay";

describe("SearchOverlay", () => {
  beforeEach(() => {
    pushMock.mockReset();
    vi.useFakeTimers({ shouldAdvanceTime: true });
  });
  afterEach(() => {
    vi.useRealTimers();
  });

  function renderOpen() {
    return renderWithProviders(<SearchOverlay open onClose={vi.fn()} />);
  }

  it("renders empty state when input is < 2 chars", async () => {
    renderOpen();
    await userEvent.type(screen.getByPlaceholderText(/Search parcels/i), "a");
    expect(screen.queryByText(/No matches/i)).toBeNull();
    expect(screen.queryByText(/Listings/i)).toBeNull();
  });

  it("renders listings + regions after debounce settles", async () => {
    server.use(
      http.get("*/api/v1/search/suggest", () =>
        HttpResponse.json(mockSuggestResponse()),
      ),
    );
    renderOpen();
    await userEvent.type(screen.getByPlaceholderText(/Search parcels/i), "tula");
    await act(async () => {
      vi.advanceTimersByTime(300);
    });
    await waitFor(() =>
      expect(screen.getByText("Premium Waterfront")).toBeInTheDocument(),
    );
    expect(screen.getByText("Tula")).toBeInTheDocument();
  });

  it("clicking a listing row navigates to /auction/{publicId}", async () => {
    server.use(
      http.get("*/api/v1/search/suggest", () =>
        HttpResponse.json(mockSuggestResponse()),
      ),
    );
    renderOpen();
    await userEvent.type(screen.getByPlaceholderText(/Search parcels/i), "tula");
    await act(async () => {
      vi.advanceTimersByTime(300);
    });
    await userEvent.click(await screen.findByText("Premium Waterfront"));
    expect(pushMock).toHaveBeenCalledWith(
      "/auction/00000000-0000-0000-0000-000000000099",
    );
  });

  it("clicking a region row navigates to /browse?region={name}", async () => {
    server.use(
      http.get("*/api/v1/search/suggest", () =>
        HttpResponse.json(mockSuggestResponse()),
      ),
    );
    renderOpen();
    await userEvent.type(screen.getByPlaceholderText(/Search parcels/i), "tula");
    await act(async () => {
      vi.advanceTimersByTime(300);
    });
    await userEvent.click(await screen.findByText("Tula"));
    expect(pushMock).toHaveBeenCalledWith("/browse?region=Tula");
  });

  it("bare Enter routes to /browse?q={trimmed}", async () => {
    server.use(
      http.get("*/api/v1/search/suggest", () =>
        HttpResponse.json(mockSuggestResponse()),
      ),
    );
    renderOpen();
    const input = screen.getByPlaceholderText(/Search parcels/i);
    await userEvent.type(input, "tula{Enter}");
    expect(pushMock).toHaveBeenCalledWith("/browse?q=tula");
  });

  it("renders 'Search is unavailable' on backend 5xx", async () => {
    server.use(
      http.get("*/api/v1/search/suggest", () =>
        HttpResponse.json({ status: 500 }, { status: 500 }),
      ),
    );
    renderOpen();
    await userEvent.type(screen.getByPlaceholderText(/Search parcels/i), "tula");
    await act(async () => {
      vi.advanceTimersByTime(300);
    });
    await waitFor(() =>
      expect(screen.getByText(/Search is unavailable/i)).toBeInTheDocument(),
    );
  });

  it("renders no-matches copy when both groups are empty", async () => {
    server.use(
      http.get("*/api/v1/search/suggest", () =>
        HttpResponse.json({ listings: [], regions: [], totalListings: 0 }),
      ),
    );
    renderOpen();
    await userEvent.type(screen.getByPlaceholderText(/Search parcels/i), "xyz");
    await act(async () => {
      vi.advanceTimersByTime(300);
    });
    await waitFor(() =>
      expect(screen.getByText(/No matches for/i)).toBeInTheDocument(),
    );
  });
});
```

- [ ] **Step 4: Run the tests — should fail because SearchOverlay doesn't exist**

```
npx vitest run src/components/search/SearchOverlay.test.tsx
```

Expected: FAIL with module-not-found.

- [ ] **Step 5: Implement `SearchOverlay`**

```tsx
"use client";

import { Combobox, Dialog } from "@headlessui/react";
import { useRouter } from "next/navigation";
import { useState } from "react";
import { useDebouncedValue } from "@/hooks/useDebouncedValue";
import { useMediaQuery } from "@/hooks/useMediaQuery";
import { useSearchSuggest } from "@/hooks/useSearchSuggest";
import { X } from "@/components/ui/icons";
import { cn } from "@/lib/cn";
import { SearchInput } from "./SearchInput";
import { SearchResultsList, type SearchSelection } from "./SearchResultsList";

export interface SearchOverlayProps {
  open: boolean;
  onClose: () => void;
}

/**
 * Header search overlay. Same Combobox body in both layouts; the
 * wrapper toggles between an anchored panel (>=md) and a full-screen
 * Dialog (<md). Selection routing is one discriminated-union switch
 * — no branching per row component.
 */
export function SearchOverlay({ open, onClose }: SearchOverlayProps) {
  const router = useRouter();
  const [query, setQuery] = useState("");
  const debounced = useDebouncedValue(query, 250);
  const trimmed = debounced.trim();
  const queryState = useSearchSuggest(query);
  // useMediaQuery defaults to false on the SSR pass — first client
  // paint matches the server (mobile layout); the effect upgrades to
  // desktop after hydration. Acceptable because the overlay is only
  // rendered when the user clicks the trigger, which is post-mount.
  const isDesktop = useMediaQuery("(min-width: 768px)");

  function handleSelect(value: SearchSelection | null) {
    if (!value) return;
    if (value.kind === "listing") {
      router.push(`/auction/${value.id}`);
    } else if (value.kind === "region") {
      router.push(`/browse?region=${encodeURIComponent(value.name)}`);
    } else {
      router.push(`/browse?q=${encodeURIComponent(value.q)}`);
    }
    setQuery("");
    onClose();
  }

  function handleBareEnter() {
    if (trimmed.length < 2) return;
    router.push(`/browse?q=${encodeURIComponent(trimmed)}`);
    setQuery("");
    onClose();
  }

  if (!open) return null;

  const body = (
    <Combobox<SearchSelection | null>
      value={null}
      onChange={handleSelect}
      // immediate=true ensures clicks register without an intermediate
      // listbox highlight pass.
      immediate
    >
      <SearchInput
        value={query}
        onChange={setQuery}
        onBareEnter={handleBareEnter}
        autoFocus
      />
      <SearchResultsList state={queryState} trimmed={trimmed} />
    </Combobox>
  );

  if (isDesktop) {
    return (
      <div
        className="fixed inset-0 z-[60]"
        onClick={onClose}
      >
        <div
          className={cn(
            "absolute right-4 top-[var(--header-h)] mt-2 w-[480px]",
            "max-h-[520px] overflow-hidden",
            "rounded-lg border border-border bg-bg shadow-elevation-3",
          )}
          onClick={(e) => e.stopPropagation()}
          onKeyDown={(e) => {
            if (e.key === "Escape") onClose();
          }}
        >
          {body}
        </div>
      </div>
    );
  }

  return (
    <Dialog open onClose={onClose} className="md:hidden relative z-[60]">
      <Dialog.Panel className="fixed inset-0 flex flex-col bg-bg">
        <div className="flex items-center gap-2 px-2 h-[var(--header-h)] border-b border-border bg-surface-raised">
          <button
            type="button"
            onClick={onClose}
            aria-label="Close search"
            className="grid h-9 w-9 place-items-center rounded-md hover:bg-bg-muted"
          >
            <X className="size-5 text-fg" />
          </button>
          <div className="flex-1">{body}</div>
        </div>
      </Dialog.Panel>
    </Dialog>
  );
}
```

(Note: the snippet above shows the body being shared, but the mobile branch's header bar competes with `SearchInput`'s own border — when implementing, render `SearchInput` once at the top of the layout and put the close button inside it on mobile. The cleanest pattern is to render `<SearchInput>` once and conditionally inject a close button as a slot. If the responsive split causes layout shift, simplify: render SearchInput once, then SearchResultsList once, and let the wrapper handle the chrome.)

- [ ] **Step 6: Run the tests to verify they pass**

```
npx vitest run src/components/search/SearchOverlay.test.tsx
```

Expected: PASS — 7 tests green.

- [ ] **Step 7: Commit**

```
git add frontend/src/components/search/SearchOverlay.tsx frontend/src/components/search/SearchOverlay.test.tsx frontend/src/hooks/useMediaQuery.ts
git commit -m "feat(search): SearchOverlay (Combobox + responsive wrapper)"
```

---

## Task 11: Frontend — Header integration (replace disabled icon)

**Files:**
- Modify: `frontend/src/components/layout/Header.tsx`
- Modify: `frontend/src/components/layout/Header.test.tsx`
- Create: `frontend/src/components/search/SearchOverlayTrigger.tsx`

- [ ] **Step 1: Implement `SearchOverlayTrigger`**

```tsx
"use client";

import { useState } from "react";
import { IconButton } from "@/components/ui";
import { Search } from "@/components/ui/icons";
import { SearchOverlay } from "./SearchOverlay";

/**
 * Header-mounted button + overlay pair. Owns the open/close state so
 * the overlay can stay outside the surrounding flex layout (it portals
 * either to body via Dialog on mobile, or absolutely positions on
 * desktop).
 */
export function SearchOverlayTrigger() {
  const [open, setOpen] = useState(false);
  return (
    <>
      <IconButton
        aria-label="Search"
        variant="tertiary"
        onClick={() => setOpen(true)}
      >
        <Search className="h-[18px] w-[18px]" />
      </IconButton>
      <SearchOverlay open={open} onClose={() => setOpen(false)} />
    </>
  );
}
```

- [ ] **Step 2: Replace the disabled icon in `Header.tsx`**

Find:

```tsx
<IconButton
  aria-label="Search (coming soon)"
  variant="tertiary"
  className="hidden md:inline-flex"
  disabled
>
  <Search className="h-[18px] w-[18px]" />
</IconButton>
```

Replace with:

```tsx
<SearchOverlayTrigger />
```

Add the import at the top:

```tsx
import { SearchOverlayTrigger } from "@/components/search/SearchOverlayTrigger";
```

(Drop the now-unused `Search` import only if no other usage remains in this file — `grep` first.)

- [ ] **Step 3: Update `Header.test.tsx`**

Read the existing tests; replace any case that asserted the search icon was disabled. Add:

```tsx
it("renders an enabled Search trigger", () => {
  renderWithProviders(<Header />);
  const btn = screen.getByRole("button", { name: "Search" });
  expect(btn).toBeEnabled();
});

it("opens the search overlay when clicked", async () => {
  renderWithProviders(<Header />);
  await userEvent.click(screen.getByRole("button", { name: "Search" }));
  expect(screen.getByPlaceholderText(/Search parcels/i)).toBeInTheDocument();
});
```

- [ ] **Step 4: Run Header + SearchOverlay tests**

```
npx vitest run src/components/layout/Header.test.tsx src/components/search/
```

Expected: PASS.

- [ ] **Step 5: Commit**

```
git add frontend/src/components/search/SearchOverlayTrigger.tsx frontend/src/components/layout/Header.tsx frontend/src/components/layout/Header.test.tsx
git commit -m "feat(search): wire SearchOverlay into Header"
```

---

## Task 12: Frontend — `q=` URL codec + AuctionSearchQuery type + BrowseShell header

**Files:**
- Modify: `frontend/src/types/search.ts`
- Modify: `frontend/src/lib/search/url-codec.ts`
- Modify: `frontend/src/lib/search/url-codec.test.ts`
- Modify: `frontend/src/components/browse/BrowseShell.tsx`
- Modify: `frontend/src/components/browse/BrowseShell.test.tsx`

- [ ] **Step 1: Add `q?: string` to `AuctionSearchQuery` type**

In `frontend/src/types/search.ts`, find the `AuctionSearchQuery` type and add:

```ts
export type AuctionSearchQuery = {
  q?: string;            // NEW — free-text search
  region?: string;
  // ... rest unchanged
};
```

- [ ] **Step 2: Write failing url-codec round-trip tests**

In `frontend/src/lib/search/url-codec.test.ts`, append:

```ts
describe("q field round-trip", () => {
  it("decodes ?q=foo", () => {
    const sp = new URLSearchParams("q=foo");
    expect(queryFromSearchParams(sp).q).toBe("foo");
  });
  it("encodes q=foo", () => {
    const sp = searchParamsFromQuery({ ...defaultAuctionSearchQuery, q: "foo" });
    expect(sp.get("q")).toBe("foo");
  });
  it("drops q on encode when blank", () => {
    const sp = searchParamsFromQuery({ ...defaultAuctionSearchQuery, q: "" });
    expect(sp.get("q")).toBeNull();
  });
  it("trims whitespace on decode", () => {
    const sp = new URLSearchParams("q=%20%20foo%20%20");
    expect(queryFromSearchParams(sp).q).toBe("foo");
  });
});
```

- [ ] **Step 3: Run the tests — should fail**

```
npx vitest run src/lib/search/url-codec.test.ts
```

Expected: FAIL — `q` not handled.

- [ ] **Step 4: Implement the codec changes**

In `queryFromSearchParams`, after the existing `region` block:

```ts
const q = sp.get("q");
if (q && q.trim().length > 0) query.q = q.trim();
```

In `searchParamsFromQuery`, after the existing `region` line:

```ts
putIf(sp, "q", q.q && q.q.length > 0 ? q.q : undefined, (v) => v);
```

(Match the style of the existing `putIf(sp, "region", q.region, (v) => v);` line — preserve the helper.)

- [ ] **Step 5: Run tests — should pass**

```
npx vitest run src/lib/search/url-codec.test.ts
```

Expected: PASS.

- [ ] **Step 6: Add the "Showing results for" header in `BrowseShell`**

Read `BrowseShell.tsx`. Find where the `ResultsHeader` (or equivalent grid-header section) is rendered, and add directly above it:

```tsx
{query.q ? (
  <div className="mb-3 text-sm text-fg-muted">
    Showing results for <span className="font-semibold text-fg">&ldquo;{query.q}&rdquo;</span>
  </div>
) : null}
```

(If `query` is named differently in the surrounding scope — e.g. `currentQuery` — match.)

- [ ] **Step 7: Add a BrowseShell test for the header**

In `BrowseShell.test.tsx`, append:

```tsx
it("shows 'Showing results for' header when q is in the initial query", async () => {
  renderWithProviders(
    <BrowseShell
      initialQuery={{ ...defaultAuctionSearchQuery, q: "tula" }}
      initialData={{ content: [], totalElements: 0, /* ...other required defaults */ } as any}
      title="Browse"
    />,
  );
  expect(screen.getByText(/Showing results for/i)).toBeInTheDocument();
  expect(screen.getByText(/tula/i)).toBeInTheDocument();
});
```

(If the existing test file has helpers for `initialData`, prefer those — copy from the first existing `BrowseShell` test rather than reproducing the shape inline.)

- [ ] **Step 8: Run all the affected tests**

```
npx vitest run src/lib/search/url-codec.test.ts src/components/browse/BrowseShell.test.tsx
```

Expected: PASS — all green, including new q-field cases.

- [ ] **Step 9: Run lint + verify guards**

```
npm run verify
```

Expected: PASS — no-dark-variants, no-hex-colors, no-inline-styles, coverage all clean.

- [ ] **Step 10: Commit**

```
git add frontend/src/types/search.ts frontend/src/lib/search/url-codec.ts frontend/src/lib/search/url-codec.test.ts frontend/src/components/browse/BrowseShell.tsx frontend/src/components/browse/BrowseShell.test.tsx
git commit -m "feat(search): q= URL codec + BrowseShell results header"
```

---

## Task 13: Postman + README

**Files:**
- Postman: SLPA collection (id `8070328-cf3e1190-cb71-4af3-8e2e-70c82dfee288`)
- Modify: `README.md`

- [ ] **Step 1: Create Postman folder + request**

Use the Postman MCP tools (`mcp__postman__createCollectionFolder`, `mcp__postman__createCollectionRequest`):

1. Create folder "Search Suggest" under collection `8070328-cf3e1190-cb71-4af3-8e2e-70c82dfee288`.
2. Inside that folder, create request:
   - Name: "Get suggestions"
   - Method: GET
   - URL: `{{baseUrl}}/api/v1/search/suggest?q={{searchTerm}}`
   - No auth required (public).

3. Add `searchTerm` variable to the `SLPA Dev` environment with default value `tula`.

- [ ] **Step 2: Update `README.md`**

Find the section that lists user-facing features (or add one if absent). Append a paragraph:

```
- **Header search.** Live typeahead in the navbar searches across listing titles, parcel names, and region names. The popover surfaces up to 5 listings + 3 region rows; clicking a region jumps to the filtered browse page, and Enter opens the full results grid with the query preserved in the URL. Backed by a public `/api/v1/search/suggest` endpoint with pg_trgm trigram indexes.
```

(Place it near the existing "Browse / search" or feature-list section — read README.md first to find the natural location.)

- [ ] **Step 3: Commit**

```
git add README.md
git commit -m "docs(readme): header search overlay"
```

(Postman changes don't get a git commit — they live in the cloud collection.)

---

## Task 14: PR to dev → merge → PR to main → merge

- [ ] **Step 1: Push the branch**

```
git push origin feat/header-search-overlay
```

- [ ] **Step 2: Open PR feat/header-search-overlay → dev**

```
gh pr create --base dev --head feat/header-search-overlay \
  --title "feat: header search overlay (typeahead + /browse?q= filter)" \
  --body "$(cat <<'EOF'
## Summary
- New `GET /api/v1/search/suggest` typeahead endpoint (5 listings + 3 regions, public, 300 rpm/IP, 15s cache, pg_trgm trigram indexes via V22).
- Existing `/api/v1/auctions/search` learns a `q=` filter (OR'd across title / parcel name / region name).
- New `SearchOverlay` component in the header — Combobox-based, responsive (anchored panel ≥md, full-screen Dialog <md). Replaces the disabled "coming soon" icon.
- `/browse` URL codec gains `q=` round-trip; `BrowseShell` renders a "Showing results for" header.
- Spec: `docs/superpowers/specs/2026-05-09-header-search-overlay-design.md`. Plan: `docs/superpowers/plans/2026-05-09-header-search-overlay.md`.

## Test plan
- [x] `./mvnw test -Dtest='AuctionSearch*Test,Search*Test'` — all green
- [x] `npm run verify` — all guards clean
- [x] `npm test` — full frontend suite green
- [x] Manual: type in header → grouped results render; click listing → /auction/{id}; click region → /browse?region=; Enter → /browse?q=
- [x] Manual: deep-link /browse?q=foo renders header above the grid
EOF
)"
```

- [ ] **Step 3: Watch CI**

```
gh pr checks --watch
```

If anything fails, fix it as a follow-up commit on the same branch and re-push. Don't merge until green.

- [ ] **Step 4: Squash-merge PR into dev**

```
gh pr merge --squash --delete-branch
```

- [ ] **Step 5: Open PR dev → main**

```
git checkout main && git pull --ff-only
gh pr create --base main --head dev \
  --title "Merge dev → main: header search overlay + recent fixes" \
  --body "$(cat <<'EOF'
## Summary
Includes the header search overlay (#TODO PR number for dev — fill once known), nav dropdown z-index fix (#225), and listing wizard title re-prefill (#223), plus the prior settings/profile nav.

## Test plan
- [x] CI green on dev pre-merge
- [x] Backend deploy will trigger automatically on push to main (paths: backend/**)
- [x] Amplify will rebuild on push to main automatically
EOF
)"
```

- [ ] **Step 6: Watch dev → main CI, then merge**

```
gh pr checks --watch
gh pr merge --merge   # not squash — preserves the dev branch SHAs
```

- [ ] **Step 7: Verify deploy**

```
aws --profile slpa-prod logs tail /aws/ecs/slpa-backend --since 5m --format short
gh run list --branch main --workflow 'deploy backend' --limit 3
aws --profile slpa-prod amplify list-jobs --app-id dil6fhehya5jf --branch-name main --max-items 3
```

Expected: backend deploy succeeds; Amplify build succeeds.

- [ ] **Step 8: Smoke-test in prod**

Open `https://slparcels.com/`, click the search icon, type "tula" — confirm grouped results render. Press Enter and verify navigation to `/browse?q=tula`.

---

## Self-review notes (post-write)

- **Spec coverage:**
  - §5.1 endpoint → Tasks 5
  - §5.2 DTOs → Task 2
  - §5.3 service-layer SQL → Tasks 3, 4
  - §5.4 `/auctions/search` q= filter → Task 7
  - §5.5 V22 migration → Task 1
  - §5.6 rate limit → Task 6
  - §6 frontend layout → Tasks 8-12
  - §7 error/edge cases → covered through SearchOverlay tests in Task 10
  - §8 testing → tests inline with each task
- **Type consistency:** `SearchSelection` shape (`{kind: "listing"|"region"|"browse"}`) matches between Task 9 (definition) and Task 10 (consumer). `SuggestResponse` matches between Task 2 (backend) and Task 8 (frontend type).
- **Placeholders:** none — every code block contains the actual code.
