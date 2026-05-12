# Realty Groups: G — Part 4: Pulled-in items + Postman + Docs + Final PR

> Index: [`2026-05-12-realty-groups-final-cleanup.md`](2026-05-12-realty-groups-final-cleanup.md).
> Spec: [`docs/superpowers/specs/2026-05-12-realty-groups-final-cleanup-design.md`](../specs/2026-05-12-realty-groups-final-cleanup-design.md).

**Tasks 28–34.** Pulled-in items (report-threshold notification fan-out, dedicated group reviews page, SL group UUID reverse-search hard block, `AdminActionType` label map), Postman collection additions, documentation sweep (`DEFERRED_WORK.md`, `README.md`, `FOOTGUNS.md`), and the final PR into `dev`.

**Concurrency:** Tasks 28–31 are pairwise file-disjoint and parallel-safe with each other (the report-threshold work, reviews page, reverse-search gate, and label map touch different packages on backend and different routes on frontend). Task 32 (Postman) depends on every new endpoint landing first. Task 33 (docs sweep) depends on all prior tasks. Task 34 (final PR) is the terminus.

---

## Task 28: Group report-threshold notification fan-out [parallel-safe with 29-31]

Per spec §12. Three new pieces of plumbing:

1. A new `NotificationCategory.GROUP_REPORT_THRESHOLD_REACHED` enum value.
2. A new `@Min(1)` validated property `slpa.reports.group-alert-threshold = 3` on the moderation properties bean.
3. A persistent per-group flag `realty_groups.reports_threshold_notified` (already added to the schema by V29 in Part 1 Task 1) wired into the entity as a Lombok-managed field.

The threshold-notification path lives entirely inside `RealtyGroupReportService` — `submit` fires it on the row that just transitioned the group across the threshold, and `resolve` / `dismiss` re-arm it when the last open report on the group is closed.

**Files:**
- Modify: `backend/src/main/java/com/slparcelauctions/backend/notification/NotificationCategory.java`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/realty/moderation/RealtyGroupModerationProperties.java`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/realty/RealtyGroup.java`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/realty/reports/RealtyGroupReportService.java`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/realty/reports/RealtyGroupReportRepository.java` (new `countByRealtyGroupIdAndStatus` finder if not already present)
- Modify: `backend/src/main/java/com/slparcelauctions/backend/notification/NotificationPublisher.java`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/notification/NotificationPublisherImpl.java`
- Modify: `backend/src/test/java/com/slparcelauctions/backend/realty/reports/RealtyGroupReportServiceTest.java`
- Create: `backend/src/test/java/com/slparcelauctions/backend/realty/reports/ReportThresholdFanOutIntegrationTest.java`

- [ ] **Step 1: Add the new `NotificationCategory` value (failing-test-first not required for an enum value — covered indirectly by the fan-out integration test in Step 6)**

Edit `NotificationCategory.java` — append after `REALTY_GROUP_SL_GROUP_DRIFT_DETECTED`:

```java
    ,

    // Realty groups — admin-side notification when a group's open report count
    // crosses the configured threshold (sub-project G §12). Fan-out target is
    // the set of active admin users (Role.ADMIN), not group members. One-shot
    // per cycle: re-armed once openReportCount returns to 0.
    GROUP_REPORT_THRESHOLD_REACHED(NotificationGroup.ADMIN_OPS)
```

(Adjust the trailing semicolon on the previous line — convert from `;` to `,` — and place the new semicolon after `GROUP_REPORT_THRESHOLD_REACHED(...)` so the enum list stays syntactically valid.)

- [ ] **Step 2: Add the configuration property**

Edit `RealtyGroupModerationProperties.java`. The class is bound to `slpa.realty.*`, but the spec calls for the property to live at `slpa.reports.group-alert-threshold` so it surfaces alongside the listing-side report machinery, not under the realty-group-specific block.

Two acceptable shapes:

- **(a) Add a separate `@ConfigurationProperties("slpa.reports")` bean.** Create a new class `ReportsProperties` in `backend/src/main/java/com/slparcelauctions/backend/realty/reports/ReportsProperties.java`:

```java
package com.slparcelauctions.backend.realty.reports;

import org.springframework.boot.context.properties.ConfigurationProperties;
import jakarta.validation.constraints.Min;
import lombok.Getter;
import lombok.Setter;

/**
 * Configuration for the user-submitted reports subsystem. Bound to {@code slpa.reports.*}.
 *
 * <p>{@code groupAlertThreshold} is the number of open user-submitted reports a realty
 * group can accumulate before the admin fan-out notification fires
 * ({@link com.slparcelauctions.backend.notification.NotificationCategory#GROUP_REPORT_THRESHOLD_REACHED}).
 * One-shot per cycle — see spec §12.3. Default {@code 3}; minimum {@code 1}.
 */
@ConfigurationProperties(prefix = "slpa.reports")
@Getter
@Setter
public class ReportsProperties {

    @Min(1)
    private int groupAlertThreshold = 3;
}
```

Register the bean in `RealtyConfig.java`:

```java
@EnableConfigurationProperties({
    RealtyGroupModerationProperties.class,
    ReportsProperties.class,
})
```

- **(b) Add a nested `Reports` block under the existing realty bean.** Discouraged — the `slpa.reports.*` prefix is not realty-scoped and we'd be paying for indirection at every read site.

Choose **(a)**. Add `slpa.reports.group-alert-threshold: 3` to `backend/src/main/resources/application.yml` (and any profile-overlay yml that sets `slpa.*` values).

- [ ] **Step 3: Wire the entity field**

Edit `RealtyGroup.java` — add the column-backed field (V29 from Part 1 Task 1 already added the column):

```java
/**
 * One-shot-per-cycle flag for {@link com.slparcelauctions.backend.notification
 * .NotificationCategory#GROUP_REPORT_THRESHOLD_REACHED}. Set when
 * {@code openReportCount} crosses the configured threshold; cleared by
 * {@code RealtyGroupReportService.resolve}/{@code dismiss} once
 * {@code openReportCount} returns to zero (re-arms next cycle). See spec §12.3.
 */
@Column(name = "reports_threshold_notified", nullable = false)
private boolean reportsThresholdNotified = false;
```

Lombok `@Getter`/`@Setter` on the class handles the accessor pair (`isReportsThresholdNotified` / `setReportsThresholdNotified`).

- [ ] **Step 4: Add the publisher entrypoint declaration + implementation**

Edit `NotificationPublisher.java` — append to the realty-group block (around line 156, after `realtyGroupUnsuspended`):

```java
/**
 * Sub-project G §12 — fan-out to every active admin when a realty group's
 * open report count crosses {@code slpa.reports.group-alert-threshold} for
 * the first time in the current cycle. One-shot per cycle: the caller
 * ({@link com.slparcelauctions.backend.realty.reports.RealtyGroupReportService})
 * gates by checking {@code group.isReportsThresholdNotified()} and sets the
 * flag before invoking this method.
 */
void groupReportThresholdReached(RealtyGroup group, int threshold);
```

Edit `NotificationPublisherImpl.java` — implement, modeling on the existing `realtyGroupSuspended` method. Use `UserRepository.findByRole(Role.ADMIN)` to resolve the fan-out target. Inject `UserRepository` if it's not already on the impl (check first — the field may already exist):

```java
@Override
public void groupReportThresholdReached(RealtyGroup group, int threshold) {
    if (group == null) return;
    String title = "Realty group \"" + group.getName()
        + "\" reached " + threshold + " open reports";
    String body = "Review the queue at /admin/realty-groups/"
        + group.getPublicId() + "/reports";
    Map<String, Object> data = NotificationDataBuilder.realtyGroupBase(
        group.getPublicId(), group.getName(), group.getSlug());
    data.put("threshold", threshold);
    for (User admin : userRepository.findByRole(Role.ADMIN)) {
        publishOne(admin.getId(),
            NotificationCategory.GROUP_REPORT_THRESHOLD_REACHED,
            title, body, data);
    }
}
```

(If active-only admin filtering is desired, mirror the project pattern — the spec calls out "currently-active admin users via the existing admin-resolver F's report queue uses". Verify that helper exists; if F's admin queue resolves admins differently (e.g., by a flag), reuse that. Worst-case fall back to `findByRole(Role.ADMIN)` plus a `where deletedAt is null` filter at the query layer.)

- [ ] **Step 5: Add a failing test for the cycle: submit-3 fires once; submit-4 does not; resolve-all then submit-3 fires again**

Create `backend/src/test/java/com/slparcelauctions/backend/realty/reports/ReportThresholdFanOutIntegrationTest.java`:

```java
package com.slparcelauctions.backend.realty.reports;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import com.slparcelauctions.backend.notification.NotificationPublisher;
import com.slparcelauctions.backend.realty.RealtyGroup;

@SpringBootTest
@Transactional
@TestPropertySource(properties = "slpa.reports.group-alert-threshold=3")
class ReportThresholdFanOutIntegrationTest {

    @Autowired private RealtyGroupReportService reportService;
    @Autowired private RealtyGroupTestFixtures fixtures;          // see existing test helpers
    @SpyBean private NotificationPublisher notificationPublisher;

    @Test
    void crossingThresholdFiresOnce_thenReArmsAfterAllResolved() {
        RealtyGroup group = fixtures.aGroup();
        Long reporter1 = fixtures.aUser().getId();
        Long reporter2 = fixtures.aUser().getId();
        Long reporter3 = fixtures.aUser().getId();
        Long reporter4 = fixtures.aUser().getId();
        Long admin = fixtures.anAdmin().getId();

        // 3 submissions across the threshold — exactly one fan-out fires.
        UUID r1 = reportService.submit(group.getPublicId(), reporter1,
            RealtyGroupReportReason.SCAM, "first").getPublicId();
        UUID r2 = reportService.submit(group.getPublicId(), reporter2,
            RealtyGroupReportReason.SCAM, "second").getPublicId();
        UUID r3 = reportService.submit(group.getPublicId(), reporter3,
            RealtyGroupReportReason.SCAM, "third").getPublicId();
        verify(notificationPublisher, times(1)).groupReportThresholdReached(any(), anyInt());

        // 4th submission, still above threshold, no additional fan-out.
        reportService.submit(group.getPublicId(), reporter4,
            RealtyGroupReportReason.SCAM, "fourth");
        verify(notificationPublisher, times(1)).groupReportThresholdReached(any(), anyInt());

        // Resolve all four — flag re-arms when openReportCount returns to 0.
        reportService.resolve(r1, admin, "ok");
        reportService.resolve(r2, admin, "ok");
        reportService.resolve(r3, admin, "ok");
        // r4 resolved via dismiss to exercise both reset branches.
        UUID r4PublicId = reportService.findAll(RealtyGroupReportStatus.OPEN,
            org.springframework.data.domain.PageRequest.of(0, 1))
            .getContent().get(0).getPublicId();
        reportService.dismiss(r4PublicId, admin, "frivolous");

        // 3 new submissions — second cycle fires.
        reportService.submit(group.getPublicId(), reporter1,
            RealtyGroupReportReason.SCAM, "second cycle 1");
        reportService.submit(group.getPublicId(), reporter2,
            RealtyGroupReportReason.SCAM, "second cycle 2");
        reportService.submit(group.getPublicId(), reporter3,
            RealtyGroupReportReason.SCAM, "second cycle 3");
        verify(notificationPublisher, times(2)).groupReportThresholdReached(any(), anyInt());

        verifyNoMoreInteractions(notificationPublisher);
    }
}
```

If `RealtyGroupTestFixtures` does not exist, inline the equivalent saves via `RealtyGroupRepository.save(...)` / `UserRepository.save(...)`. Match the existing test idiom in `RealtyGroupReportServiceTest`.

- [ ] **Step 6: Run the test to confirm it fails (no `tryFireThresholdNotification` yet)**

```bash
./mvnw test -Dtest=ReportThresholdFanOutIntegrationTest
```

Expected: failure at the first `verify(times(1), ...)` — `groupReportThresholdReached` was never invoked.

- [ ] **Step 7: Implement `tryFireThresholdNotification` + the reset in `resolve` / `dismiss`**

Edit `RealtyGroupReportService.java`. Add the `ReportsProperties` dependency:

```java
private final ReportsProperties reportsProps;
private final NotificationPublisher notificationPublisher;
```

(Both go in the constructor via Lombok's `@RequiredArgsConstructor`.)

Inside `submit`, after the `saveAndFlush` returns and *before* the `log.info`, call:

```java
tryFireThresholdNotification(group);
```

Add the private method:

```java
/**
 * Sub-project G §12.3 — one-shot fan-out when the group's open-report count
 * crosses the configured threshold. The flag stays set until {@link #resolve}
 * or {@link #dismiss} returns {@code openReportCount} to zero, at which point
 * the flag clears and the next cycle can fire again.
 */
private void tryFireThresholdNotification(RealtyGroup group) {
    if (group.isReportsThresholdNotified()) {
        return;
    }
    long openReports = reportRepo
        .countByRealtyGroupIdAndStatus(group.getId(), RealtyGroupReportStatus.OPEN);
    int threshold = reportsProps.getGroupAlertThreshold();
    if (openReports < threshold) {
        return;
    }
    group.setReportsThresholdNotified(true);
    groupRepo.save(group);
    notificationPublisher.groupReportThresholdReached(group, threshold);
}
```

Inside `resolve`, after the `report = reportRepo.save(report)` line, add the reset:

```java
long openReports = reportRepo
    .countByRealtyGroupIdAndStatus(report.getRealtyGroup().getId(),
        RealtyGroupReportStatus.OPEN);
if (openReports == 0 && report.getRealtyGroup().isReportsThresholdNotified()) {
    report.getRealtyGroup().setReportsThresholdNotified(false);
    groupRepo.save(report.getRealtyGroup());
}
```

Same shape inside `dismiss`.

Add the finder to `RealtyGroupReportRepository.java` if it does not exist:

```java
long countByRealtyGroupIdAndStatus(Long realtyGroupId, RealtyGroupReportStatus status);
```

(Verify the existing repository — F's queue likely already has a similar count finder. Reuse rather than duplicate if so.)

Inject `RealtyGroupRepository` into the service if it isn't already on the constructor (verify — F's service already has `groupRepo`).

- [ ] **Step 8: Run the test to confirm it passes**

```bash
./mvnw test -Dtest=ReportThresholdFanOutIntegrationTest
```

Expected: green.

- [ ] **Step 9: Run the full reports test class to confirm no regression**

```bash
./mvnw test -Dtest=RealtyGroupReportServiceTest,ReportThresholdFanOutIntegrationTest
```

Expected: green.

- [ ] **Step 10: Commit**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/notification/NotificationCategory.java \
        backend/src/main/java/com/slparcelauctions/backend/notification/NotificationPublisher.java \
        backend/src/main/java/com/slparcelauctions/backend/notification/NotificationPublisherImpl.java \
        backend/src/main/java/com/slparcelauctions/backend/realty/RealtyGroup.java \
        backend/src/main/java/com/slparcelauctions/backend/realty/RealtyConfig.java \
        backend/src/main/java/com/slparcelauctions/backend/realty/reports/ReportsProperties.java \
        backend/src/main/java/com/slparcelauctions/backend/realty/reports/RealtyGroupReportService.java \
        backend/src/main/java/com/slparcelauctions/backend/realty/reports/RealtyGroupReportRepository.java \
        backend/src/main/resources/application.yml \
        backend/src/test/java/com/slparcelauctions/backend/realty/reports/ReportThresholdFanOutIntegrationTest.java
git commit -m "feat(realty-g): group report-threshold admin fan-out (one-shot per cycle)"
```

---

## Task 29: Dedicated group reviews page [parallel-safe with 28, 30, 31]

Per spec §13. New backend endpoint + DTO + service method + frontend page + `GroupRatingBadge` link update. Anonymous-accessible (mirrors the existing user-side public reviews endpoint).

**Files:**
- Create: `backend/src/main/java/com/slparcelauctions/backend/realty/rating/RealtyGroupReviewsController.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/realty/rating/dto/GroupReviewRowDto.java`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/realty/rating/GroupRatingService.java`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/config/SecurityConfig.java` (verify `/api/v1/realty/groups/*/reviews` is on the `permitAll` list — mirror existing public-realty patterns)
- Create: `frontend/src/app/realty/groups/[publicId]/reviews/page.tsx`
- Create: `frontend/src/app/realty/groups/[publicId]/reviews/page.test.tsx`
- Modify: `frontend/src/components/realty/GroupRatingBadge.tsx`
- Modify: `frontend/src/components/realty/GroupRatingBadge.test.tsx`
- Create: `backend/src/test/java/com/slparcelauctions/backend/realty/rating/RealtyGroupReviewsControllerTest.java`

- [ ] **Step 1: Write a failing test for the backend endpoint**

Create `RealtyGroupReviewsControllerTest.java`:

```java
package com.slparcelauctions.backend.realty.rating;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class RealtyGroupReviewsControllerTest {

    @Autowired private MockMvc mvc;
    @Autowired private RealtyGroupRatingTestFixtures fixtures;

    @Test
    void returnsPagedReviewsForGroupAuctions_anonymousAccessOk() throws Exception {
        UUID groupPublicId = fixtures.groupWithReviewedAuction(
            /* rating= */ 4, /* comment= */ "Great communication").getPublicId();

        mvc.perform(get("/api/v1/realty/groups/{publicId}/reviews", groupPublicId)
                .param("page", "0").param("size", "20"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content[0].rating").value(4))
            .andExpect(jsonPath("$.content[0].comment").value("Great communication"))
            .andExpect(jsonPath("$.content[0].auctionPublicId").exists())
            .andExpect(jsonPath("$.content[0].reviewerPublicId").exists());
    }

    @Test
    void emptyGroupReturnsEmptyPage() throws Exception {
        UUID groupPublicId = fixtures.groupWithoutReviews().getPublicId();
        mvc.perform(get("/api/v1/realty/groups/{publicId}/reviews", groupPublicId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content").isEmpty())
            .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void doesNotIncludeReviewsFromUserSideAuctionsWhereLeaderWasBuyerOrSeller()
            throws Exception {
        UUID groupPublicId = fixtures.groupWhoseLeaderHasUnrelatedUserReviews()
            .getPublicId();
        mvc.perform(get("/api/v1/realty/groups/{publicId}/reviews", groupPublicId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content").isEmpty());
    }
}
```

(`RealtyGroupRatingTestFixtures` may need to be created; if so add it alongside the test file. Mirror the pattern in any existing F-era rating test.)

- [ ] **Step 2: Run the test to confirm it fails (404 — controller does not exist yet)**

```bash
./mvnw test -Dtest=RealtyGroupReviewsControllerTest
```

- [ ] **Step 3: Create the DTO**

Create `backend/src/main/java/com/slparcelauctions/backend/realty/rating/dto/GroupReviewRowDto.java`:

```java
package com.slparcelauctions.backend.realty.rating.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Single row of the dedicated group-reviews page (sub-project G §13). All
 * fields are public-safe — {@code publicId}s only. The {@code auctionTitle}
 * carries the parcel name displayed in the auction header at the time the
 * review was left.
 */
public record GroupReviewRowDto(
        UUID reviewerPublicId,
        String reviewerDisplayName,
        int rating,
        String comment,
        UUID auctionPublicId,
        String auctionTitle,
        Instant createdAt) {
}
```

- [ ] **Step 4: Add the service method**

Edit `GroupRatingService.java` — add `listReviews`. Use JPQL on `Review` (the existing rating query uses native SQL for the AVG; the reviews list is plain Spring Data JPA territory):

```java
/**
 * Sub-project G §13.1 — paginated list of every review on auctions attributed
 * to the realty group (case-1 direct, case-3 via {@code realty_group_sl_groups}).
 * Anonymous-accessible; returns an empty page when no reviews exist.
 *
 * <p>Ordering: newest first ({@code r.createdAt DESC}). Caller is responsible
 * for translating the {@link Pageable} into {@code page=N&size=M} query params.
 */
@Transactional(readOnly = true)
public Page<GroupReviewRowDto> listReviews(Long groupId, Pageable pageable) {
    // Native query keeps parity with computeRating's auction-group attribution
    // (case-1 OR case-3) without forcing a second JPQL path that drifts.
    String selectSql = """
        SELECT u.public_id           AS reviewer_public_id,
               u.display_name        AS reviewer_display_name,
               r.rating              AS rating,
               r.comment             AS comment,
               a.public_id           AS auction_public_id,
               a.title               AS auction_title,
               r.created_at          AS created_at
          FROM reviews r
          JOIN auctions a       ON a.id = r.auction_id
          JOIN users    u       ON u.id = r.reviewer_id
         WHERE a.realty_group_id = :groupId
            OR EXISTS (SELECT 1 FROM realty_group_sl_groups rsg
                        WHERE rsg.id = a.realty_group_sl_group_id
                          AND rsg.realty_group_id = :groupId)
         ORDER BY r.created_at DESC
         LIMIT :limit OFFSET :offset
        """;
    String countSql = """
        SELECT COUNT(*)
          FROM reviews r
          JOIN auctions a ON a.id = r.auction_id
         WHERE a.realty_group_id = :groupId
            OR EXISTS (SELECT 1 FROM realty_group_sl_groups rsg
                        WHERE rsg.id = a.realty_group_sl_group_id
                          AND rsg.realty_group_id = :groupId)
        """;

    @SuppressWarnings("unchecked")
    List<Tuple> rows = em.createNativeQuery(selectSql, Tuple.class)
        .setParameter("groupId", groupId)
        .setParameter("limit", pageable.getPageSize())
        .setParameter("offset", pageable.getOffset())
        .getResultList();

    long total = ((Number) em.createNativeQuery(countSql)
        .setParameter("groupId", groupId)
        .getSingleResult()).longValue();

    List<GroupReviewRowDto> content = rows.stream().map(t -> new GroupReviewRowDto(
            (UUID) t.get("reviewer_public_id"),
            (String) t.get("reviewer_display_name"),
            ((Number) t.get("rating")).intValue(),
            (String) t.get("comment"),
            (UUID) t.get("auction_public_id"),
            (String) t.get("auction_title"),
            ((java.sql.Timestamp) t.get("created_at")).toInstant()))
        .toList();

    return new PageImpl<>(content, pageable, total);
}
```

Pull in the imports the new method needs (`Page`, `Pageable`, `PageImpl`, `Tuple`, `Review`-package types, the DTO). The existing rating service already injects `EntityManager`; no constructor change required.

If preferred, write it as JPQL instead — both shapes pass the spec acceptance test. Match whichever is closer to the existing project idiom (the rating service itself uses native; many other services use JPQL).

- [ ] **Step 5: Create the controller**

Create `RealtyGroupReviewsController.java`:

```java
package com.slparcelauctions.backend.realty.rating;

import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.slparcelauctions.backend.common.PagedResponse;
import com.slparcelauctions.backend.realty.RealtyGroup;
import com.slparcelauctions.backend.realty.RealtyGroupRepository;
import com.slparcelauctions.backend.realty.exception.RealtyGroupNotFoundException;
import com.slparcelauctions.backend.realty.rating.dto.GroupReviewRowDto;

import lombok.RequiredArgsConstructor;

/**
 * Sub-project G §13 — paginated public reviews list for a realty group.
 * Anonymous-accessible; matches the auth posture of the user-side public
 * reviews endpoint. Returns an empty page when the group has no reviews.
 */
@RestController
@RequestMapping("/api/v1/realty/groups/{publicId}/reviews")
@RequiredArgsConstructor
public class RealtyGroupReviewsController {

    private final GroupRatingService ratingService;
    private final RealtyGroupRepository groupRepo;

    @GetMapping
    public ResponseEntity<PagedResponse<GroupReviewRowDto>> list(
            @PathVariable("publicId") UUID groupPublicId,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "20") int size) {

        RealtyGroup group = groupRepo.findByPublicIdAndDissolvedAtIsNull(groupPublicId)
            .orElseThrow(() -> new RealtyGroupNotFoundException(groupPublicId));

        Page<GroupReviewRowDto> p = ratingService.listReviews(
            group.getId(), PageRequest.of(page, size));
        return ResponseEntity.ok(PagedResponse.from(p));
    }
}
```

Confirm `PagedResponse.from(Page<T>)` exists — it's the project-wide envelope. If the helper name differs, match it.

- [ ] **Step 6: Verify Spring Security permits the path**

The `permitAll` rule for public realty endpoints is typically a pattern like `/api/v1/realty/groups/**`. Spot-check `SecurityConfig` — if `/api/v1/realty/groups/{publicId}/reviews` is already covered by an existing wildcard rule, no edit needed; otherwise add it next to the existing `permitAll` for the public group profile.

- [ ] **Step 7: Run the test to confirm it passes**

```bash
./mvnw test -Dtest=RealtyGroupReviewsControllerTest
```

Expected: green.

- [ ] **Step 8: Write a failing Vitest for the frontend page (page renders header + paginated rows + empty state)**

Create `frontend/src/app/realty/groups/[publicId]/reviews/page.test.tsx`:

```tsx
import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { http, HttpResponse } from "msw";
import { setupServer } from "msw/node";

import Page from "./page";

const server = setupServer();
// ... existing project MSW boilerplate

describe("GroupReviewsPage", () => {
  it("renders the group name + rating badge + review rows", async () => {
    // MSW stub for GET /api/v1/realty/groups/{publicId}/reviews
    // returning a page with two reviews
    server.use(
      http.get("*/api/v1/realty/groups/abc-123/reviews", () =>
        HttpResponse.json({
          content: [
            {
              reviewerPublicId: "u-1",
              reviewerDisplayName: "Alice",
              rating: 5,
              comment: "Lovely parcel",
              auctionPublicId: "a-1",
              auctionTitle: "Sunset Cove 256m²",
              createdAt: "2026-05-10T12:00:00Z",
            },
          ],
          totalElements: 1,
          totalPages: 1,
          number: 0,
          size: 20,
        }),
      ),
    );

    const rendered = await Page({ params: Promise.resolve({ publicId: "abc-123" }) });
    render(rendered);

    expect(await screen.findByText("Sunset Cove 256m²")).toBeInTheDocument();
    expect(screen.getByText("Lovely parcel")).toBeInTheDocument();
  });

  it("renders the empty state when there are no reviews", async () => {
    server.use(
      http.get("*/api/v1/realty/groups/abc-456/reviews", () =>
        HttpResponse.json({
          content: [], totalElements: 0, totalPages: 0, number: 0, size: 20,
        }),
      ),
    );
    const rendered = await Page({ params: Promise.resolve({ publicId: "abc-456" }) });
    render(rendered);
    expect(await screen.findByText(/no reviews yet/i)).toBeInTheDocument();
  });
});
```

(Match the project's Vitest patterns — check `frontend/AGENTS.md` for the Next.js 16 page-param shape. `params` may be a `Promise` in Next 16.)

- [ ] **Step 9: Run the Vitest to confirm it fails**

```bash
cd frontend && npm test -- page.test.tsx
```

- [ ] **Step 10: Create the page**

Create `frontend/src/app/realty/groups/[publicId]/reviews/page.tsx`:

```tsx
import Link from "next/link";
import { notFound } from "next/navigation";

import { GroupRatingBadge } from "@/components/realty/GroupRatingBadge";
import { apiUrl } from "@/lib/api/url";
import { fetchGroupPublic } from "@/lib/realty/api";   // existing F-era client
import { fetchGroupReviews, fetchGroupRating } from "@/lib/realty/reviews";

export const dynamic = "force-dynamic";

export default async function GroupReviewsPage({
  params,
  searchParams,
}: {
  params: Promise<{ publicId: string }>;
  searchParams: Promise<{ page?: string }>;
}) {
  const { publicId } = await params;
  const { page = "0" } = await searchParams;

  const [group, rating, reviews] = await Promise.all([
    fetchGroupPublic(publicId).catch(() => null),
    fetchGroupRating(publicId).catch(() => null),
    fetchGroupReviews(publicId, Number(page)).catch(() => null),
  ]);

  if (!group || !reviews) {
    notFound();
  }

  return (
    <main className="mx-auto max-w-3xl px-4 py-8">
      <header className="mb-6 flex items-center gap-3">
        <h1 className="text-xl font-semibold">{group.name}</h1>
        <GroupRatingBadge rating={rating} />
      </header>

      {reviews.content.length === 0 ? (
        <p className="text-fg-muted">This group has no reviews yet.</p>
      ) : (
        <ul className="divide-y divide-border">
          {reviews.content.map(r => (
            <li key={`${r.reviewerPublicId}-${r.auctionPublicId}-${r.createdAt}`}
                className="py-4">
              <div className="flex items-center justify-between">
                <Link href={`/users/${r.reviewerPublicId}`}
                      className="font-medium hover:underline">
                  {r.reviewerDisplayName}
                </Link>
                <time className="text-xs text-fg-muted">{r.createdAt}</time>
              </div>
              <div className="mt-1 text-sm">★ {r.rating} / 5</div>
              {r.comment && <p className="mt-1 text-sm">{r.comment}</p>}
              <Link href={`/auction/${r.auctionPublicId}`}
                    className="mt-1 inline-block text-xs text-fg-muted hover:underline">
                {r.auctionTitle}
              </Link>
            </li>
          ))}
        </ul>
      )}

      {/* paginator: match the project's existing PagedResponse pager component */}
    </main>
  );
}
```

Add the API helpers to `frontend/src/lib/realty/reviews.ts` (or wherever realty fetchers live — verify the existing file structure first):

```ts
import { apiUrl } from "@/lib/api/url";

export async function fetchGroupReviews(publicId: string, page = 0, size = 20) {
  const res = await fetch(
    apiUrl(`/api/v1/realty/groups/${publicId}/reviews?page=${page}&size=${size}`),
    { cache: "no-store" },
  );
  if (!res.ok) throw new Error(`group reviews ${res.status}`);
  return res.json() as Promise<{
    content: GroupReviewRow[];
    totalElements: number;
    totalPages: number;
    number: number;
    size: number;
  }>;
}

export interface GroupReviewRow {
  reviewerPublicId: string;
  reviewerDisplayName: string;
  rating: number;
  comment: string | null;
  auctionPublicId: string;
  auctionTitle: string;
  createdAt: string;
}
```

(If `fetchGroupRating` already exists for the badge — check; it should from F's work — reuse the existing helper.)

- [ ] **Step 11: Update `GroupRatingBadge` to link to the dedicated page**

Edit `frontend/src/components/realty/GroupRatingBadge.tsx` — replace the `leaderPublicId` prop and the `/users/{leaderSlug}/reviews` link with a `groupPublicId` prop and a `/realty/groups/{groupPublicId}/reviews` link:

```tsx
export interface GroupRatingBadgeProps {
  rating: GroupRating | null;
  /**
   * Public id of the realty group. When provided, the badge renders as a link
   * to the dedicated group-reviews page (sub-project G §13). Omit to render a
   * non-link span (e.g. inside the reviews page header itself).
   */
  groupPublicId?: string;
  className?: string;
}
```

…and change the populated render path:

```tsx
if (groupPublicId) {
  return (
    <Link
      href={`/realty/groups/${encodeURIComponent(groupPublicId)}/reviews`}
      className={cn(baseClasses, "hover:underline", className)}
      data-testid="group-rating-badge"
      data-variant="populated"
      aria-label={ariaLabel}
    >
      {inner}
    </Link>
  );
}
```

Drop the stopgap Javadoc paragraph about deferred work.

Search every consumer site:

```bash
grep -rn "GroupRatingBadge" frontend/src/
```

Each currently passes `leaderPublicId=...`. Rewrite each call site to pass `groupPublicId={group.publicId}` (or the equivalent prop on whatever shape the caller has).

Update `GroupRatingBadge.test.tsx` to assert the new link target.

- [ ] **Step 12: Run Vitest + lint**

```bash
cd frontend && npm test -- page.test.tsx GroupRatingBadge.test.tsx
cd frontend && npm run lint
```

Expected: green.

- [ ] **Step 13: Commit**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/realty/rating/RealtyGroupReviewsController.java \
        backend/src/main/java/com/slparcelauctions/backend/realty/rating/GroupRatingService.java \
        backend/src/main/java/com/slparcelauctions/backend/realty/rating/dto/GroupReviewRowDto.java \
        backend/src/test/java/com/slparcelauctions/backend/realty/rating/RealtyGroupReviewsControllerTest.java \
        frontend/src/app/realty/groups/ frontend/src/components/realty/GroupRatingBadge.tsx \
        frontend/src/components/realty/GroupRatingBadge.test.tsx \
        frontend/src/lib/realty/reviews.ts
git commit -m "feat(realty-g): dedicated group reviews page + badge link update"
```

---

## Task 30: SL group UUID reverse-search hard block at registration [parallel-safe with 28, 29, 31]

Per spec §14. Three pieces: repository query, service-layer precondition, exception + handler + frontend error surfacing.

**Files:**
- Modify: `backend/src/main/java/com/slparcelauctions/backend/realty/slgroup/RealtyGroupSlGroupRepository.java`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/realty/slgroup/RealtyGroupSlGroupService.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/realty/slgroup/exception/SlGroupRegisteredToSuspendedGroupException.java`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/realty/exception/RealtyExceptionHandler.java`
- Modify: `frontend/src/app/realty/groups/[publicId]/sl-groups/register/page.tsx` (or wherever the SL group registration form lives — verify)
- Create: `backend/src/test/java/com/slparcelauctions/backend/realty/slgroup/SlGroupRegistrationReverseSearchTest.java`

- [ ] **Step 1: Write a failing test for the precondition**

Create `backend/src/test/java/com/slparcelauctions/backend/realty/slgroup/SlGroupRegistrationReverseSearchTest.java`:

```java
package com.slparcelauctions.backend.realty.slgroup;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import com.slparcelauctions.backend.realty.RealtyGroup;
import com.slparcelauctions.backend.realty.RealtyGroupTestFixtures;
import com.slparcelauctions.backend.realty.moderation.RealtyGroupSuspensionService;
import com.slparcelauctions.backend.realty.slgroup.exception.SlGroupRegisteredToSuspendedGroupException;

@SpringBootTest
@Transactional
class SlGroupRegistrationReverseSearchTest {

    @Autowired private RealtyGroupSlGroupService slGroupService;
    @Autowired private RealtyGroupSuspensionService suspensionService;
    @Autowired private RealtyGroupTestFixtures fixtures;

    @Test
    void registeringSlGroupAlreadyOnSuspendedRealtyGroupReturns409() {
        UUID slGroupUuid = UUID.randomUUID();

        // First group registers the SL group, then gets suspended.
        RealtyGroup first = fixtures.aGroup();
        Long firstLeader = first.getLeaderUserId();
        slGroupService.register(firstLeader, first.getPublicId(), slGroupUuid);
        Long admin = fixtures.anAdmin().getId();
        suspensionService.suspend(admin, first.getPublicId(), "fraud", /* expiresAt= */ null);

        // Second, fresh group tries to register the same SL group UUID. Hard block.
        RealtyGroup second = fixtures.aGroup();
        Long secondLeader = second.getLeaderUserId();

        assertThatThrownBy(() ->
            slGroupService.register(secondLeader, second.getPublicId(), slGroupUuid))
            .isInstanceOf(SlGroupRegisteredToSuspendedGroupException.class);
    }

    @Test
    void unsuspendingFirstGroupDoesNotAutoAllowSecond_butNextAttemptSucceeds() {
        UUID slGroupUuid = UUID.randomUUID();
        RealtyGroup first = fixtures.aGroup();
        slGroupService.register(first.getLeaderUserId(), first.getPublicId(), slGroupUuid);
        Long admin = fixtures.anAdmin().getId();
        Long suspensionId = suspensionService.suspend(admin, first.getPublicId(),
            "fraud", /* expiresAt= */ null).getId();
        suspensionService.lift(admin, first.getPublicId(), suspensionId, "appeal granted");

        // Second registration on the now-unsuspended group's UUID should succeed
        // (no active suspension row remains for the first group). But first must
        // unregister from the first group, since the SL group UUID has a unique
        // constraint regardless of suspension state.
        slGroupService.unregister(first.getLeaderUserId(), first.getPublicId(),
            /* slGroupPublicId= */ /* ... */);
        RealtyGroup second = fixtures.aGroup();
        // Should not throw.
        slGroupService.register(second.getLeaderUserId(), second.getPublicId(), slGroupUuid);
    }
}
```

(Second test exercises the unsuspend → unregister-from-first → re-register-to-second flow; if the existing `SlGroupAlreadyRegisteredException` uniqueness gate fires before this test's new exception, that's a pre-existing flow — the spec only requires that the *suspended-realty-group* case raises the new exception. Keep the second test only if it adds coverage that isn't already in the existing SL-group suite.)

- [ ] **Step 2: Run the test to confirm it fails**

```bash
./mvnw test -Dtest=SlGroupRegistrationReverseSearchTest
```

Expected: failure — `SlGroupAlreadyRegisteredException` (the existing UUID-unique check) fires before the new precondition, **OR** the registration succeeds without any exception. Either failure tells us the new gate is missing.

- [ ] **Step 3: Create the exception class**

Create `backend/src/main/java/com/slparcelauctions/backend/realty/slgroup/exception/SlGroupRegisteredToSuspendedGroupException.java`:

```java
package com.slparcelauctions.backend.realty.slgroup.exception;

import java.util.UUID;

import lombok.Getter;

/**
 * Sub-project G §14 — reverse-search ban-evasion gate. Thrown when a caller
 * attempts to register an SL group UUID that already has a registration on
 * a suspended realty group (active row in {@code realty_group_suspensions}
 * with {@code lifted_at IS NULL}). Mapped to 409 Conflict with code
 * {@code SL_GROUP_REGISTERED_TO_SUSPENDED_GROUP}.
 */
@Getter
public class SlGroupRegisteredToSuspendedGroupException extends RuntimeException {

    public static final String CODE = "SL_GROUP_REGISTERED_TO_SUSPENDED_GROUP";

    private final UUID slGroupUuid;

    public SlGroupRegisteredToSuspendedGroupException(UUID slGroupUuid) {
        super("This SL group is registered to a suspended SLPA realty group. Contact support.");
        this.slGroupUuid = slGroupUuid;
    }
}
```

- [ ] **Step 4: Add the repository method**

Edit `RealtyGroupSlGroupRepository.java` — append the query:

```java
/**
 * Sub-project G §14 — reverse-search ban-evasion gate. Returns {@code true} when
 * the supplied SL group UUID is currently registered to any realty group that
 * has an active (unlifted) suspension row. {@code realty_groups.suspended}
 * does NOT exist as a column — "suspended" is determined by an unlifted row
 * in {@code realty_group_suspensions}.
 */
@Query("""
    SELECT CASE WHEN COUNT(s) > 0 THEN TRUE ELSE FALSE END
      FROM RealtyGroupSlGroup s
     WHERE s.slGroupUuid = :slGroupUuid
       AND EXISTS (
           SELECT 1 FROM RealtyGroupSuspension sus
            WHERE sus.realtyGroupId = s.realtyGroupId
              AND sus.liftedAt IS NULL
       )
    """)
boolean existsForSuspendedRealtyGroup(@Param("slGroupUuid") UUID slGroupUuid);
```

(Verify the join column on `RealtyGroupSuspension` — F's model may name the field `realtyGroup` (entity) or `realtyGroupId` (long). Match whichever the existing F code uses. If it's an entity-mapped field, use `sus.realtyGroup.id = s.realtyGroupId` instead.)

- [ ] **Step 5: Add the precondition in `RealtyGroupSlGroupService.register`**

Edit `RealtyGroupSlGroupService.java` — in `register(...)`, insert the gate **after** the `realtyGroupGuard.requireGroupCanOperate(group.getId())` line and **before** the existing `repo.findBySlGroupUuid(...)` uniqueness check. The order matters: the suspension gate is a strictly stronger constraint than the uniqueness one, so checking it first yields the more informative exception.

```java
if (repo.existsForSuspendedRealtyGroup(slGroupUuid)) {
    throw new SlGroupRegisteredToSuspendedGroupException(slGroupUuid);
}
```

Add the import for the new exception class.

- [ ] **Step 6: Map the exception in `RealtyExceptionHandler`**

Edit `RealtyExceptionHandler.java` — append a new handler next to the existing SL-group exception handlers:

```java
@ExceptionHandler(SlGroupRegisteredToSuspendedGroupException.class)
public ProblemDetail handleSlGroupRegisteredToSuspendedGroup(
        SlGroupRegisteredToSuspendedGroupException e, HttpServletRequest req) {
    ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    pd.setTitle("SL Group Registered To Suspended Group");
    pd.setInstance(URI.create(req.getRequestURI()));
    pd.setProperty("code", SlGroupRegisteredToSuspendedGroupException.CODE);
    if (e.getSlGroupUuid() != null) {
        pd.setProperty("slGroupUuid", e.getSlGroupUuid().toString());
    }
    return pd;
}
```

Add the import.

- [ ] **Step 7: Run the test to confirm it passes**

```bash
./mvnw test -Dtest=SlGroupRegistrationReverseSearchTest
```

Expected: green.

- [ ] **Step 8: Frontend — surface the error inline with a support link**

Locate the SL group registration form (grep for the existing 409 / `SL_GROUP_ALREADY_REGISTERED` handling — same form):

```bash
grep -rn "SL_GROUP_ALREADY_REGISTERED" frontend/src/
```

Add a branch for the new code. Pattern:

```tsx
if (problem?.code === "SL_GROUP_REGISTERED_TO_SUSPENDED_GROUP") {
  return (
    <FormError>
      This SL group is registered to a suspended SLPA realty group.{" "}
      <a href={`mailto:${process.env.NEXT_PUBLIC_SUPPORT_EMAIL ?? "support@slparcels.com"}`}
         className="underline">
        Contact support
      </a>
      {" "}for help.
    </FormError>
  );
}
```

(Mirror the existing FormError component style and the existing `process.env.NEXT_PUBLIC_SUPPORT_EMAIL` shape — verify against the existing form's error handling.)

Update the form's Vitest to cover the new branch with an MSW handler that returns `409` with `code: "SL_GROUP_REGISTERED_TO_SUSPENDED_GROUP"`.

- [ ] **Step 9: Run frontend tests + lint**

```bash
cd frontend && npm test -- sl-group
cd frontend && npm run lint
```

- [ ] **Step 10: Commit**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/realty/slgroup/RealtyGroupSlGroupRepository.java \
        backend/src/main/java/com/slparcelauctions/backend/realty/slgroup/RealtyGroupSlGroupService.java \
        backend/src/main/java/com/slparcelauctions/backend/realty/slgroup/exception/SlGroupRegisteredToSuspendedGroupException.java \
        backend/src/main/java/com/slparcelauctions/backend/realty/exception/RealtyExceptionHandler.java \
        backend/src/test/java/com/slparcelauctions/backend/realty/slgroup/SlGroupRegistrationReverseSearchTest.java \
        frontend/src/
git commit -m "feat(realty-g): reverse-search hard block on SL group UUID registration"
```

---

## Task 31: Frontend `AdminActionType` label map [parallel-safe with 28, 29, 30]

Per spec §15. Frontend-only. One new file plus an audit-log row renderer hook-up.

**Files:**
- Create: `frontend/src/lib/admin/action-type-labels.ts`
- Create: `frontend/src/lib/admin/action-type-labels.test.ts`
- Modify: the audit-log row renderer (grep `row.actionType` in `frontend/src/components/admin/` to locate)
- Modify: that renderer's existing test file

- [ ] **Step 1: Enumerate every current `AdminActionType` value**

Read the backend enum to mirror it on the frontend:

```bash
cat backend/src/main/java/com/slparcelauctions/backend/admin/audit/AdminActionType.java
```

Post-Task 2 the enum carries:

```
DISMISS_REPORT, WARN_SELLER_FROM_REPORT, SUSPEND_LISTING_FROM_REPORT,
CANCEL_LISTING_FROM_REPORT, CREATE_BAN, LIFT_BAN, PROMOTE_USER, DEMOTE_USER,
RESET_FRIVOLOUS_COUNTER, REINSTATE_LISTING, DISPUTE_RESOLVED,
LISTING_CANCELLED_VIA_DISPUTE, WITHDRAWAL_REQUESTED,
OWNERSHIP_RECHECK_INVOKED, TERMINAL_SECRET_ROTATED, USER_DELETED_BY_ADMIN,
WALLET_ADJUST, WALLET_FREEZE, WALLET_UNFREEZE, WALLET_FORGIVE_PENALTY,
WALLET_RESET_DORMANCY, WALLET_CLEAR_TERMS, WITHDRAWAL_FORCE_COMPLETE,
WITHDRAWAL_FORCE_FAIL, PARCEL_TAG_CREATED, PARCEL_TAG_UPDATED,
PARCEL_TAG_TOGGLED_ACTIVE, PARCEL_TAG_CATEGORY_CREATED,
PARCEL_TAG_CATEGORY_UPDATED, PARCEL_TAG_CATEGORY_TOGGLED_ACTIVE,
FEATURE_LISTING, UNFEATURE_LISTING, REALTY_GROUP_EDIT,
REALTY_GROUP_DISSOLVE, REALTY_GROUP_MEMBER_REMOVE, REALTY_GROUP_SUSPEND,
REALTY_GROUP_UNSUSPEND, REALTY_GROUP_BAN, REALTY_GROUP_UNBAN,
REALTY_GROUP_FRAUD_FLAG, REALTY_GROUP_REPORT_RESOLVE,
REALTY_GROUP_REPORT_DISMISS, REALTY_GROUP_BULK_SUSPEND,
REALTY_GROUP_BULK_REINSTATE, REALTY_GROUP_BULK_SUSPEND_EXPIRY_RUN,
REALTY_GROUP_SL_GROUP_FORCE_UNREGISTER, REALTY_GROUP_SL_GROUP_DRIFT_ACK,
REALTY_GROUP_SL_GROUP_RECHECK, REALTY_GROUP_WALLET_ADMIN_ADJUSTMENT
```

(48 values. Verify by re-reading the file at task time; if Task 2's diff added more, include those.)

- [ ] **Step 2: Write a failing Vitest for the label map**

Create `frontend/src/lib/admin/action-type-labels.test.ts`:

```ts
import { describe, expect, it } from "vitest";

import { ACTION_TYPE_LABELS, labelFor } from "./action-type-labels";

describe("ACTION_TYPE_LABELS", () => {
  it("provides a human label for every documented action type", () => {
    expect(ACTION_TYPE_LABELS.DISMISS_REPORT).toBe("Dismiss report");
    expect(ACTION_TYPE_LABELS.REALTY_GROUP_WALLET_ADMIN_ADJUSTMENT)
      .toBe("Realty group wallet adjustment");
    expect(ACTION_TYPE_LABELS.REALTY_GROUP_SL_GROUP_FORCE_UNREGISTER)
      .toBe("Force-unregister SL group");
  });
});

describe("labelFor", () => {
  it("returns the configured label for known values", () => {
    expect(labelFor("CREATE_BAN")).toBe("Create ban");
  });
  it("falls back to title-cased enum name for unknown values", () => {
    // simulates a future backend enum addition the frontend hasn't caught up to
    expect(labelFor("SOMETHING_NEW" as never)).toBe("Something New");
  });
});
```

- [ ] **Step 3: Run the test to confirm it fails**

```bash
cd frontend && npm test -- action-type-labels.test.ts
```

- [ ] **Step 4: Create the label map module**

Create `frontend/src/lib/admin/action-type-labels.ts`:

```ts
import type { AdminActionType } from "@/types/admin";

/**
 * Sub-project G §15 — human-readable label for every {@link AdminActionType}
 * value the audit log can carry. Single source of truth for the admin UI's
 * row-rendering layer. The fallback in {@link labelFor} handles any future
 * enum value that ships before this map updates.
 */
export const ACTION_TYPE_LABELS: Record<AdminActionType, string> = {
  DISMISS_REPORT: "Dismiss report",
  WARN_SELLER_FROM_REPORT: "Warn seller (from report)",
  SUSPEND_LISTING_FROM_REPORT: "Suspend listing (from report)",
  CANCEL_LISTING_FROM_REPORT: "Cancel listing (from report)",
  CREATE_BAN: "Create ban",
  LIFT_BAN: "Lift ban",
  PROMOTE_USER: "Promote user",
  DEMOTE_USER: "Demote user",
  RESET_FRIVOLOUS_COUNTER: "Reset frivolous-reporter counter",
  REINSTATE_LISTING: "Reinstate listing",
  DISPUTE_RESOLVED: "Resolve dispute",
  LISTING_CANCELLED_VIA_DISPUTE: "Cancel listing via dispute",
  WITHDRAWAL_REQUESTED: "Withdrawal requested",
  OWNERSHIP_RECHECK_INVOKED: "Ownership recheck",
  TERMINAL_SECRET_ROTATED: "Rotate terminal secret",
  USER_DELETED_BY_ADMIN: "Delete user",
  WALLET_ADJUST: "Wallet adjustment",
  WALLET_FREEZE: "Freeze wallet",
  WALLET_UNFREEZE: "Unfreeze wallet",
  WALLET_FORGIVE_PENALTY: "Forgive wallet penalty",
  WALLET_RESET_DORMANCY: "Reset wallet dormancy",
  WALLET_CLEAR_TERMS: "Clear wallet terms acceptance",
  WITHDRAWAL_FORCE_COMPLETE: "Force-complete withdrawal",
  WITHDRAWAL_FORCE_FAIL: "Force-fail withdrawal",
  PARCEL_TAG_CREATED: "Create parcel tag",
  PARCEL_TAG_UPDATED: "Update parcel tag",
  PARCEL_TAG_TOGGLED_ACTIVE: "Toggle parcel tag active",
  PARCEL_TAG_CATEGORY_CREATED: "Create parcel tag category",
  PARCEL_TAG_CATEGORY_UPDATED: "Update parcel tag category",
  PARCEL_TAG_CATEGORY_TOGGLED_ACTIVE: "Toggle parcel tag category active",
  FEATURE_LISTING: "Feature listing",
  UNFEATURE_LISTING: "Unfeature listing",
  REALTY_GROUP_EDIT: "Edit realty group",
  REALTY_GROUP_DISSOLVE: "Dissolve realty group",
  REALTY_GROUP_MEMBER_REMOVE: "Remove realty group member",
  REALTY_GROUP_SUSPEND: "Suspend realty group",
  REALTY_GROUP_UNSUSPEND: "Unsuspend realty group",
  REALTY_GROUP_BAN: "Ban realty group",
  REALTY_GROUP_UNBAN: "Unban realty group",
  REALTY_GROUP_FRAUD_FLAG: "Flag realty group for fraud",
  REALTY_GROUP_REPORT_RESOLVE: "Resolve realty group report",
  REALTY_GROUP_REPORT_DISMISS: "Dismiss realty group report",
  REALTY_GROUP_BULK_SUSPEND: "Bulk-suspend group listings",
  REALTY_GROUP_BULK_REINSTATE: "Bulk-reinstate group listings",
  REALTY_GROUP_BULK_SUSPEND_EXPIRY_RUN: "Bulk-suspend expiry sweep",
  REALTY_GROUP_SL_GROUP_FORCE_UNREGISTER: "Force-unregister SL group",
  REALTY_GROUP_SL_GROUP_DRIFT_ACK: "Acknowledge SL group drift",
  REALTY_GROUP_SL_GROUP_RECHECK: "Recheck SL group",
  REALTY_GROUP_WALLET_ADMIN_ADJUSTMENT: "Realty group wallet adjustment",
};

/**
 * Map an {@link AdminActionType} value to its human label, falling back to
 * a title-cased version of the raw enum name when an entry is missing
 * (e.g. a backend-only addition the frontend hasn't caught up to).
 */
export function labelFor(action: AdminActionType): string {
  return ACTION_TYPE_LABELS[action] ?? toTitleCase(action);
}

function toTitleCase(raw: string): string {
  return raw
    .toLowerCase()
    .split("_")
    .map(w => (w.length === 0 ? w : w.charAt(0).toUpperCase() + w.slice(1)))
    .join(" ");
}
```

- [ ] **Step 5: Verify the `AdminActionType` TS type covers every value**

```bash
grep -n "AdminActionType" frontend/src/types/admin.ts
```

If the union is currently a string-or-narrow shape, widen it to cover every value above. Otherwise `Record<AdminActionType, string>` will compile-error against the missing keys — that's the safety net.

- [ ] **Step 6: Wire the renderer**

Locate the audit-log row renderer:

```bash
grep -rn "row.actionType\|\.actionType" frontend/src/components/admin/
```

Replace `{row.actionType}` (or whatever the raw render is) with `{labelFor(row.actionType)}`. Import `labelFor` from the new module.

Update the renderer's existing Vitest:

```tsx
// before:
expect(screen.getByText("DISMISS_REPORT")).toBeInTheDocument();
// after:
expect(screen.getByText("Dismiss report")).toBeInTheDocument();
```

- [ ] **Step 7: Run Vitest + lint**

```bash
cd frontend && npm test -- action-type-labels
cd frontend && npm run lint
```

Expected: green.

- [ ] **Step 8: Commit**

```bash
git add frontend/src/lib/admin/action-type-labels.ts \
        frontend/src/lib/admin/action-type-labels.test.ts \
        frontend/src/components/admin/ \
        frontend/src/types/admin.ts
git commit -m "feat(realty-g): AdminActionType frontend label map with title-case fallback"
```

---

## Task 32: Postman collection additions [depends on every new endpoint]

Per spec §10. Three folder additions and one header fix. Run after Tasks 11–31 have landed so every endpoint exists to be tested.

The collection lives in Postman cloud (`8070328-cf3e1190-cb71-4af3-8e2e-70c82dfee288` in the `SLPA` workspace). The implementer may use the `mcp__postman__*` MCP tools to add the requests directly. If the MCP server is unavailable in the current session, document the additions in the PR body checklist instead so a human can reconcile them.

**Files:** none modified in the repo. The PR body's checklist (Task 34) carries the spec-of-what-to-add.

- [ ] **Step 1: Audit `SlGroupVerifyController` to confirm the actual header names**

Read `backend/src/main/java/com/slparcelauctions/backend/realty/slgroup/SlGroupVerifyController.java`. The controller validates SL headers via `SlHeaderValidator.validate(shard, ownerKey)`. Expected headers (as of E ship):

```
X-SecondLife-Shard
X-SecondLife-Owner-Key
```

(The deferred-work note's guess of `X-Slpa-Terminal-Auth` was wrong. There is no shared-secret pre-auth header on this path — trust is via the SL-injected shard + owner-key pair, which Linden injects and validates as belonging to the trusted-owners list. The body field `verificationCode` carries the per-registration secret.)

- [ ] **Step 2: Define the three new folders**

If using MCP, call the appropriate `mcp__postman__*` tools (`createCollectionFolder`, `createCollectionRequest`) with the spec below. Otherwise capture the spec in the PR body and note "manual Postman additions deferred to operator reconciliation".

**Folder: `Realty Groups → List as Group`**

- `POST {{baseUrl}}/realty/groups/{{realtyGroupId}}/listings`
  - Headers: `Authorization: Bearer {{accessToken}}`
  - Body: case-3 listing request; chain `{{realtyGroupSlGroupId}}` from the SL groups folder.
  - Test script: capture `auctionId` and `auctionPublicId` into the env.
- `GET {{baseUrl}}/realty/me/listing-eligible-groups?parcelId={{parcelId}}`
  - Headers: `Authorization: Bearer {{accessToken}}`
  - Test script: assert response contains the group whose member the test user is.

**Folder: `Realty Groups → Dissolve gate`**

- `GET {{baseUrl}}/realty/groups/{{realtyGroupId}}/can-dissolve`
  - Surfaces an object enumerating blockers (active listings, registered SL groups, non-zero wallet balance).
  - Test script: capture `canDissolve` boolean.
- `DELETE {{baseUrl}}/realty/groups/{{realtyGroupId}}`
  - Multiple variants are useful here — clone the request three times in the folder with descriptive names: "(should 200 — clean)", "(should 409 — has active listings)", "(should 409 — has registered SL group)".

**Folder: `Realty Groups → Wallet`**

- `GET {{baseUrl}}/realty/groups/{{realtyGroupId}}/wallet`
- `GET {{baseUrl}}/realty/groups/{{realtyGroupId}}/wallet/ledger?page=0&size=20`
- `POST {{baseUrl}}/realty/groups/{{realtyGroupId}}/wallet/withdraw` — avatar variant. Body `{ "amount": 100, "idempotencyKey": "{{$guid}}", "recipient": "AVATAR" }`.
- `POST {{baseUrl}}/realty/groups/{{realtyGroupId}}/wallet/withdraw` — SL_GROUP variant. Body `{ "amount": 100, "idempotencyKey": "{{$guid}}", "recipient": "SL_GROUP" }`. Same URL as the avatar variant; the request lives in the folder as a separately-named clone.
- `POST {{baseUrl}}/admin/realty-groups/{{realtyGroupId}}/wallet/adjust` — credit variant. Headers include `Authorization: Bearer {{adminAccessToken}}`. Body `{ "amount": 2500, "reason": "Manual credit per ticket SLPA-1234" }`.
- `POST {{baseUrl}}/admin/realty-groups/{{realtyGroupId}}/wallet/adjust` — debit variant. Body `{ "amount": -2500, "reason": "Reverted accidental escrow double-payout per ticket SLPA-1234" }`.
- `POST {{baseUrl}}/realty/groups/{{realtyGroupId}}/listings/{{auctionId}}/pay-listing-fee`

**`SL → SL Group → Founder Terminal Verify` request fix**

The existing request in the `SL` folder was added during E with a guessed header. Replace its headers with:

```
X-SecondLife-Shard: Production
X-SecondLife-Owner-Key: {{trustedOwnerKey}}
Content-Type: application/json
```

Body:

```json
{
  "verificationCode": "{{slGroupVerificationCode}}",
  "founderAvatarUuid": "{{founderAvatarUuid}}"
}
```

URL stays `{{baseUrl}}/sl/sl-group/verify`.

- [ ] **Step 3: Run each new request end-to-end against a freshly seeded dev backend**

Boot the stack via `docker compose up --build`, then run the new requests via the Postman runner (Collection Runner — pick the new folders one at a time). Confirm chained variables resolve correctly. Capture screenshots of green runs if helpful for the PR body.

- [ ] **Step 4: (Optional) Export the collection to a git-tracked path**

If the project policy is to commit the collection JSON, export and commit. Check whether the collection has ever been committed before:

```bash
find . -name "*.postman_collection.json"
```

If hits — replace with the updated export and commit. If nothing exists — the collection lives in cloud only; document the new requests in the PR body's manual-test checklist.

- [ ] **Step 5: Commit (if anything is git-tracked)**

```bash
git add postman/  # whatever path the JSON lives at
git commit -m "docs(realty-g): Postman additions for List as Group, Dissolve gate, Wallet folders"
```

If nothing is git-tracked, skip this step — the PR body checklist (Task 34) carries the additions.

---

## Task 33: Documentation sweep [depends on Tasks 28–32]

Per spec §17 (migration concerns retained in the README), §18 (out-of-scope items kept deferred), and the cross-cutting README staleness sweep that every task closes with per `feedback_update_readme_each_task.md`.

**Files:**
- Modify: `docs/implementation/DEFERRED_WORK.md`
- Modify: `README.md`
- Modify: `docs/implementation/FOOTGUNS.md`

- [ ] **Step 1: Prune `DEFERRED_WORK.md`**

Open `docs/implementation/DEFERRED_WORK.md`. Lines ~300-337 currently contain four sub-project sections that this PR closes:

- `### Realty Groups: Listing Integration (Sub-project C, 2026-05-11)` (line ~300)
- `### Realty Groups: Group Wallet (Sub-project D, 2026-05-11)` (line ~305)
- `### Realty Groups: SL-group-owned listings (Sub-project E, 2026-05-12)` (line ~314)
- `### Realty Groups: Admin Moderation (Sub-project F, 2026-05-12)` (line ~324)

Delete all four sections in full. Replace with a single new section just after the rebrand follow-ups (before `### Pre-existing test flake`):

```markdown
### Realty Groups: Final cleanup (Sub-project G, 2026-05-12)

Five items deliberately deferred per spec §18:

- **User-side `WalletDormancyJob`** — user-wallet concern, not realty-group; lives under the wallet track.
- **Realtime ban broadcast / forced-logout WebSocket (groups)** — same posture as the user-side deferred broadcast (FOOTGUNS §F.106). When the user-side ban-broadcast ships, the group analog lands alongside so the WS topic/payload shape stays unified.
- **Per-listing admin-suspend timer** — existing indefinite per-listing admin-suspend stays untouched by design; could be unified later with the bulk-suspend 48 h timer.
- **Phase 8 reputation-driven bid-eligibility gating** — applies to all bidders, not realty-group-specific. Stays under Phase 8.
- **Empty-due audit row** — `BulkSuspendedListingExpiryTask` / `GroupSuspensionExpiryTask` deliberately skip writing an `_EXPIRY_RUN` audit row when no work was done. Revisit only if compliance reporting requires it.

Spec: `docs/superpowers/specs/2026-05-12-realty-groups-final-cleanup-design.md` §18. Sub-projects C, D, E, F sections were pruned when G shipped — every item they listed either landed in G or was renormalized into one of the five above.
```

Use Edit (not Write) — the file is long and most of it is unchanged.

- [ ] **Step 2: Sweep `README.md`**

Read the realty-groups sections (around line 170-245). Two changes:

(a) Update the sub-project E and F bullets — anywhere they reference a now-deleted DEFERRED_WORK section (e.g. "tracked in [Realty Groups: G (#246)]" — that issue is now closed by this PR), reword to point at G's spec or simply drop the reference.

Specifically:

- README line ~223 (sub-project E section): the bullet about "Deeper code/column cleanup tracked in [Realty Groups: G (#246)]" — rewrite to past tense: "Deeper code/column cleanup (drop `auctions.agent_fee_rate / agent_fee_split / agent_fee_amt`, drop `realty_groups.agent_fee_rate / agent_fee_split`, delete `AgentFeeDistributor`) was completed by sub-project G."

- Anywhere else `[Realty Groups: G (#246)]` appears, repoint to the spec file (`docs/superpowers/specs/2026-05-12-realty-groups-final-cleanup-design.md`).

(b) Append a new sub-project G section after sub-project F (around line 245):

```markdown
### Realty Groups: Final cleanup (Sub-project G)

Closes every realty-groups deferred item in one PR.

- **C-era removal.** `AgentFeeDistributor`, the case-1 snapshot path in `RealtyGroupListingService`, `RealtyGroupMembershipService.reassignListingAgentForCase1`, the `agentFeeRate` / `agentFeeSplit` fields on `RealtyGroup` + DTOs + frontend types are all deleted. V29 drops `auctions.agent_fee_rate / split / amt` and `realty_groups.agent_fee_rate / split`.
- **N+1 fixes.** `AuctionDtoMapper.toBatchDto` pre-loads groups, primary photos, and winner public ids via three batch queries — three queries regardless of list size.
- **Admin wallet ops.** `POST /admin/realty-groups/{publicId}/wallet/adjust` writes a signed `ADMIN_ADJUSTMENT` ledger row (direction in `amount`, freeform `reason`), updates `realty_groups.balance`, fires an audit row (`REALTY_GROUP_WALLET_ADMIN_ADJUSTMENT`), and broadcasts the new balance over the group's WS topic.
- **SL-group destination withdraws.** `GroupWithdrawRequest.recipient` (`AVATAR` | `SL_GROUP`) routes through `TerminalCommandAction.WITHDRAW_GROUP` to the bot's new `WithdrawGroupHandler` (LibreMetaverse `GiveGroupMoney`). Withdrawals to a suspended-registration SL group return 422; drift-flagged-but-not-suspended registrations still allow.
- **Case-3 zero-payout.** `TerminalCommandService.queuePayout` skips the terminal round-trip for `payoutAmt = 0` escrows and runs `runZeroPayoutSuccessInline` instead. The seller's escrow-payout notification body no longer says "L$0 payout received" — case-3 copy summarizes commission and group-wallet credit. The LSL terminal script defensively short-circuits any stale `amount = 0` PAYOUT it receives.
- **Reports threshold fan-out.** `RealtyGroupReportService.submit` fires `NotificationCategory.GROUP_REPORT_THRESHOLD_REACHED` to every active admin once the group crosses `slpa.reports.group-alert-threshold` (default 3). One-shot per cycle — `resolve`/`dismiss` re-arm when open-report count returns to 0.
- **Dedicated group reviews page.** `GET /api/v1/realty/groups/{publicId}/reviews` returns paginated `GroupReviewRowDto` rows. `/realty/groups/[publicId]/reviews` is the new public page. `GroupRatingBadge` now links to this page instead of the leader's user-side reviews.
- **Reverse-search ban-evasion hard block.** Registering an SL group UUID that's already on a suspended realty group returns 409 `SL_GROUP_REGISTERED_TO_SUSPENDED_GROUP`.
- **Frontend `AdminActionType` label map.** Audit-log rows render "Realty group wallet adjustment" instead of `REALTY_GROUP_WALLET_ADMIN_ADJUSTMENT`. Unknown values title-case fall back gracefully.
- **Polish.** `SystemUserResolver` field removal on `BulkSuspendedListingExpiryTask`, `slpa.realty.sl-group.reverify-batch-size` property, `@Transactional` boundary moved from F's admin moderation controllers down to the services, `SPEND_FROM_GROUP_WALLET` permission and any persisted member-row references removed.

Spec at `docs/superpowers/specs/2026-05-12-realty-groups-final-cleanup-design.md`. Issue [#246](https://github.com/TheCodeLlama/slparcelauctions/issues/246).
```

Adjust prose to match the README's existing tone in the realty sections — short paragraphs, no emoji, no em-dashes (per `feedback_no_emojis.md` and the language-cleanup spec).

- [ ] **Step 3: Add G-specific entries to `FOOTGUNS.md`**

Open `docs/implementation/FOOTGUNS.md`. Add three new entries at the end of a new §G section. The file is currently organized by category-prefix (§1, §2, …, §B, §F); add a §G block for realty-groups-specific gotchas:

```markdown
## §G Realty groups

### G.1 `runZeroPayoutSuccessInline` is the entire post-payout work for case-3 escrows

**Why:** Sub-project G §8.1 split the escrow payout flow. For case-1 the existing terminal-round-trip path runs; for case-3 (`payoutAmt = 0`) `TerminalCommandService.queuePayout` short-circuits and calls `runZeroPayoutSuccessInline` directly, which mirrors the post-payout work (ledger write, escrow → COMPLETED, seller notification, `AgentCommissionDistributor` invocation) without the bot/LSL round-trip.

**How to apply:** If you add a new post-payout side effect (analytics ping, reconciliation row, dispute-window timer) put it in BOTH `handleEscrowPayoutSuccess` (the terminal-callback path) AND `runZeroPayoutSuccessInline` (the zero-payout path) — or refactor the shared bits into a private method both call. Forgetting the inline path silently breaks the case-3 codepath.

**Idempotency:** `runZeroPayoutSuccessInline` is gated on `escrow.getState() == COMPLETED` for the no-op check — the normal callback path uses a `TerminalCommand`-row check, which doesn't apply when no command was enqueued.

### G.2 `GroupWithdrawRecipient.SL_GROUP` rejects suspended registrations, allows drift-flagged ones

**Why:** Sub-project G §7.3. The withdraw path checks two distinct registration states:

- An SL group registration with `liftedAt IS NULL` row in `realty_group_suspensions` for its realty group → 422 `SL_GROUP_REGISTRATION_SUSPENDED`. No L$ moves.
- An SL group registration whose `drift_reason` is set but the realty group itself is not suspended → the withdraw is allowed. The drift is informational; SL group ownership has changed in-world but the group still operates from SLPA's POV.

**How to apply:** The two states are independent — a registration can be both suspended AND drift-flagged. The suspended-check fires first. Don't conflate them in test fixtures.

### G.3 `realty_groups.suspended` does NOT exist as a column

**Why:** F shipped suspension state as a separate `realty_group_suspensions` table with one row per suspension event. "Is suspended" is determined by `EXISTS (SELECT 1 FROM realty_group_suspensions WHERE realty_group_id = ? AND lifted_at IS NULL)`, not by a boolean column on the parent. The reverse-search ban-evasion gate in G §14 specifically joins on this pattern.

**How to apply:** When writing any "is this group suspended right now?" query, never `SELECT g.suspended FROM realty_groups g` — the column will not exist and your query will throw. Use the active-suspension-row EXISTS pattern instead. `RealtyGroupGuard.requireGroupCanOperate` is the canonical Java-side check.
```

(If §G already exists from an earlier sub-project, append the three new entries; if not, create the section and add them.)

- [ ] **Step 4: Commit**

```bash
git add docs/implementation/DEFERRED_WORK.md \
        docs/implementation/FOOTGUNS.md \
        README.md
git commit -m "docs(realty-g): prune C/D/E/F DEFERRED sections, add G README + FOOTGUNS"
```

---

## Task 34: Final PR into `dev`

Per spec §16 and §19. Compose the PR body, push the branch (final time), open the PR. User has authorized merge into `dev` only; do NOT open or merge any PR into `main` as part of this task.

**Files:** none modified — push + PR open only.

- [ ] **Step 1: Verify everything green locally**

```bash
./mvnw test
cd frontend && npm run verify
cd ../bot && dotnet test
```

All three must pass. Frontend `verify` runs lint + guards + coverage + tests.

- [ ] **Step 2: Verify the grep sweep finds no residue**

```bash
grep -rEn '(AgentFeeDistributor|agent_fee_(rate|split|amt)|SPEND_FROM_GROUP_WALLET|reassignListingAgentForCase1)' \
    backend/src/main/java/ frontend/src/ bot/src/ \
    || echo "OK: no residue"
```

Expected: "OK: no residue".

- [ ] **Step 3: Push the branch**

```bash
git push
```

(All Part 1–4 commits should already be on the remote from intermediate pushes; this is the final sync before opening the PR.)

- [ ] **Step 4: Open the PR**

Use `gh pr create` with the body below. Target: `dev` (NOT `main`).

```bash
gh pr create --base dev --title "feat(realty-g): realty groups final cleanup — close C/D/E/F deferred items + Section G OOS pulls" --body "$(cat <<'EOF'
## Summary

Closes every realty-groups deferred item across sub-projects C, D, E, F in a single PR into `dev`. Spec at `docs/superpowers/specs/2026-05-12-realty-groups-final-cleanup-design.md`; issue [#246](https://github.com/TheCodeLlama/slparcelauctions/issues/246).

### What lands

- **Section A — C-era removal.** Deletes `AgentFeeDistributor`, the case-1 snapshot path in `RealtyGroupListingService`, `reassignListingAgentForCase1`, `agentFeeRate`/`agentFeeSplit` on `RealtyGroup` + DTOs + frontend types. V29 drops the five C-era columns + widens the ledger `entry_type` CHECK + adds `realty_groups.reports_threshold_notified` + scrubs persisted `SPEND_FROM_GROUP_WALLET` permission entries.
- **Section B — frontend cleanup.** `AgentFeePreview` consumers swapped to `AgentCommissionPreview`. Group profile page's C-era `agent_fee_rate` block removed.
- **Section C — DTO / N+1 fixes.** `AuctionDtoMapper.toBatchDto` issues exactly three SELECTs regardless of list size. `ListingEligibleGroupDto.agentCommissionRate` carries the requesting user's per-member rate.
- **Section D — admin wallet ops, SL-group withdraw, leader-terms banner.** `POST /admin/realty-groups/{publicId}/wallet/adjust` with signed amount + reason. `GroupWithdrawRequest.recipient` selects AVATAR vs. SL_GROUP. Bot `WithdrawGroupHandler` calls `Self.GiveGroupMoney`. `GroupWalletDto.leaderTermsAcceptedAt` populated; banner condition flips correctly.
- **Section E — escrow / notification / LSL (case-3 zero-payout).** `TerminalCommandService.queuePayout` skips the terminal round-trip for `payoutAmt = 0`; `runZeroPayoutSuccessInline` runs the post-payout work directly. Seller notification body no longer says "L$0 payout received". LSL terminal defensively short-circuits any stale `amount = 0` PAYOUT. Spec §9.6 of the E design doc synced.
- **Section F — polish.** Unused `SystemUserResolver` field dropped from `BulkSuspendedListingExpiryTask`. `slpa.realty.sl-group.reverify-batch-size` property added (default `Integer.MAX_VALUE`). `@Transactional` moved from F's admin moderation controllers down to the services; CONVENTIONS.md gains the rule.
- **Pulled-in OOS items.**
  - Group report-threshold notification fan-out (`NotificationCategory.GROUP_REPORT_THRESHOLD_REACHED`, one-shot per cycle, re-arms when openReportCount returns to 0).
  - Dedicated `/realty/groups/{publicId}/reviews` page.
  - SL group UUID reverse-search hard block at registration (`SL_GROUP_REGISTERED_TO_SUSPENDED_GROUP`).
  - Frontend `AdminActionType` label map with title-case fallback.
  - `RealtyGroupPermission.SPEND_FROM_GROUP_WALLET` deleted + persisted member-row references scrubbed by V29.

## Manual ops checklist (post-merge)

- [ ] **Deploy the updated LSL terminal script.** `lsl-scripts/slpa-terminal/slpa-terminal.lsl` gained a graceful `amount <= 0` short-circuit in the PAYOUT handler. In-world deployment is a manual ops step — copy the updated script into the in-world `SLParcels Terminal` object on each grid presence (dev + prod). The behavior change is fail-safe: if the script is NOT redeployed and the backend somehow sent a zero-amount PAYOUT (post-G it does not), the existing script would log + skip without transferring L\$.
- [ ] **Postman collection additions.** The three new folders (`Realty Groups → List as Group`, `Realty Groups → Dissolve gate`, `Realty Groups → Wallet`) and the `SL → SL Group → Founder Terminal Verify` header fix were authored against the cloud collection (`8070328-cf3e1190-cb71-4af3-8e2e-70c82dfee288`, workspace `SLPA`). If the MCP-driven additions did not land cleanly, reconcile manually using the spec at §10 of the design doc.
- [ ] **Sanity-gate awareness.** `slpa.realty.admin-wallet-adjust-max-l = 10_000_000` is a sanity gate, not a policy lever — operators should tune it via config if a legitimate large adjustment is blocked, not interpret a refusal as a hard policy line.

## Schema migration

V29 (`backend/src/main/resources/db/migration/V29__drop_c_era_agent_fee_columns_and_widen_admin_adjust.sql`) is committed in this PR and will run automatically on backend redeploy. Dev DB has been wiped + re-migrated locally; prod has no real case-1 rows so the column drops are safe.

## Acceptance criteria (spec §19)

- [x] `./mvnw test` passes (backend + integration).
- [x] `cd frontend && npm run verify` passes (lint, guards, coverage).
- [x] `cd bot && dotnet test` passes.
- [x] V29 migration applies cleanly against a freshly seeded dev DB.
- [x] Per-section acceptance lists in spec §§4.3, 5.3, 6.4, 7.6, 8.5, 9.4, 10.5, 11, 12.6, 13.4, 14.4, 15 all pass.
- [x] `git grep -E '(AgentFeeDistributor|agent_fee_(rate|split|amt)|SPEND_FROM_GROUP_WALLET|reassignListingAgentForCase1)'` returns zero hits in `backend/src/main/java`, `frontend/src/`, `bot/src/`.
- [x] `README.md` and `DEFERRED_WORK.md` reflect the new shape — C/D/E/F sub-project entries pruned, single G section added.

## Test plan

- [ ] Boot the stack via `docker compose up --build`.
- [ ] Run the Postman `Realty Groups → List as Group` folder end-to-end against the dev backend.
- [ ] Run the Postman `Realty Groups → Wallet` folder including both withdraw variants (AVATAR + SL_GROUP) and both admin-adjust variants (credit + debit).
- [ ] Visit `/realty/groups/{publicId}/reviews` on a group with auctions reviewed — confirm rows render; visit on an unreviewed group — confirm empty state.
- [ ] Click the rating badge on the public group profile — confirm it lands on the reviews page (not the user-side leader page).
- [ ] Submit three test reports against a single group via the user-side report form — confirm the admin notification fires exactly once.
- [ ] Try registering an SL group UUID already registered to a suspended group — confirm inline 409 error with support link.

EOF
)"
```

- [ ] **Step 5: Capture the PR URL in the task output**

The PR URL is the deliverable. Print it and report the task complete. Do NOT merge or attempt to merge into `main`; the user reviews + merges that PR themselves per `feedback_no_merge_to_main.md`. Merging into `dev` is authorized once review approves.

---

## Part 4 done

Tasks 28–34 complete. After Task 34 the PR is open against `dev` and the realty-groups track has no outstanding deferred items beyond the five items listed in the new DEFERRED_WORK G section (each of which has its own natural owner and is renormalized out of the realty-groups column).
