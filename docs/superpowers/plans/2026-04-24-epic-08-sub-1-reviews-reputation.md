# Epic 08 Sub-Spec 1 — Reviews & Reputation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship blind-reveal reviews backend + reputation aggregation + full reviews UI end-to-end.

**Architecture:** New `review` backend package (entities + service + 6 endpoints + hourly scheduler) coupled with new `components/reviews/` frontend package. Aggregate pipeline hooks into existing `TerminalCommandService` and `EscrowService`. Completion rate derives at read time from three `User` counters (no stored `completion_rate` column). WebSocket `REVIEW_REVEALED` envelope piggy-backs on existing `/topic/auction/{id}` channel.

**Tech Stack:** Spring Boot 4 / Java 26 / JPA with `ddl-auto: update` (no Flyway migrations). Next.js 16 / React 19 / TanStack Query v5 / Vitest 4 / MSW 2 / Headless UI.

**Spec:** `docs/superpowers/specs/2026-04-24-epic-08-sub-1-reviews-reputation.md`

---

## Task 1 — Backend: Review domain + submit endpoint + counters wiring

**Goal:** Land the Review entity, eligibility checks, submit endpoint (with simultaneous-reveal path), plus the `User` counter wire-ups in escrow paths so completion rate starts tracking correctly from sub-spec 1 forward.

**Files:**

- Create: `backend/src/main/java/com/slparcelauctions/backend/review/Review.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/review/ReviewResponse.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/review/ReviewFlag.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/review/ReviewedRole.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/review/ReviewFlagReason.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/review/ReviewRepository.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/review/ReviewResponseRepository.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/review/ReviewFlagRepository.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/review/Aggregate.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/review/ReviewService.java` (partial — submit path only)
- Create: `backend/src/main/java/com/slparcelauctions/backend/review/ReviewController.java` (submit endpoint only in this task)
- Create: `backend/src/main/java/com/slparcelauctions/backend/review/dto/*.java` (submit request/response DTOs, ReviewDto)
- Create: `backend/src/main/java/com/slparcelauctions/backend/review/exception/*.java` (exception classes + handler)
- Modify: `backend/src/main/java/com/slparcelauctions/backend/user/User.java` — add `escrowExpiredUnfulfilled` column
- Modify: `backend/src/main/java/com/slparcelauctions/backend/user/SellerCompletionRateMapper.java` — 3-arg signature
- Modify: `backend/src/main/java/com/slparcelauctions/backend/auction/AuctionDtoMapper.java` — pass 3rd arg
- Modify: `backend/src/main/java/com/slparcelauctions/backend/user/dto/UserProfileResponse.java` — add `completionRate`, `isNewSeller`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/user/UserService.java` (or wherever profile response is built) — populate new fields
- Modify: `backend/src/main/java/com/slparcelauctions/backend/escrow/EscrowService.java` — `expireTransfer` increments seller's `escrowExpiredUnfulfilled`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/escrow/command/TerminalCommandService.java` — `handleEscrowPayoutSuccess` increments seller's `completedSales`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/auction/search/SellerSummaryDto.java` — add `completedSales`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/auction/search/AuctionSearchResultMapper.java` — populate it
- Modify: `backend/src/main/java/com/slparcelauctions/backend/config/SecurityConfig.java` — permit GET + require JWT on POST review paths
- Tests: mirror paths under `backend/src/test/java/...`

### Step-by-step

- [ ] **Step 1: Write the failing test — User.escrowExpiredUnfulfilled column exists and defaults to 0**

File: `backend/src/test/java/com/slparcelauctions/backend/user/UserEntityTest.java` (create if needed)

```java
@Test
void escrowExpiredUnfulfilledDefaultsToZero() {
    User u = User.builder()
            .email("a@b.com")
            .passwordHash("x")
            .build();
    assertThat(u.getEscrowExpiredUnfulfilled()).isEqualTo(0);
}
```

- [ ] **Step 2: Add the column to `User`**

Edit `User.java`, add above `createdAt`:

```java
@Builder.Default
@Column(name = "escrow_expired_unfulfilled", nullable = false,
        columnDefinition = "integer not null default 0")
private Integer escrowExpiredUnfulfilled = 0;
```

Run: `./mvnw test -Dtest=UserEntityTest` — passes.

- [ ] **Step 3: Update `SellerCompletionRateMapper` signature (3 args)**

Existing tests for the mapper will fail after signature change. Write the new test first:

File: `backend/src/test/java/com/slparcelauctions/backend/user/SellerCompletionRateMapperTest.java`

```java
@Test
void threeWayDenominator() {
    // 10 completed / (10 + 2 + 3) = 0.67
    assertThat(SellerCompletionRateMapper.compute(10, 2, 3))
            .isEqualByComparingTo(new BigDecimal("0.67"));
}

@Test
void nullWhenAllCountersZero() {
    assertThat(SellerCompletionRateMapper.compute(0, 0, 0)).isNull();
}

@Test
void expiredUnfulfilledDragsTheRate() {
    // 5 completed / (5 + 0 + 5) = 0.50
    assertThat(SellerCompletionRateMapper.compute(5, 0, 5))
            .isEqualByComparingTo(new BigDecimal("0.50"));
}
```

Run: compile fails (2-arg → 3-arg).

Edit `SellerCompletionRateMapper.java`:

```java
public static BigDecimal compute(
        int completedSales,
        int cancelledWithBids,
        int escrowExpiredUnfulfilled) {
    int denom = completedSales + cancelledWithBids + escrowExpiredUnfulfilled;
    if (denom <= 0) return null;
    return BigDecimal.valueOf(completedSales)
            .divide(BigDecimal.valueOf(denom), 2, RoundingMode.HALF_UP);
}
```

Update `AuctionDtoMapper.java:208` to pass `s.getEscrowExpiredUnfulfilled()` as the third arg. Grep for any other callers and update.

Run: `./mvnw test` — relevant tests pass.

- [ ] **Step 4: Write the failing test — `expireTransfer` increments seller.escrowExpiredUnfulfilled**

File: `backend/src/test/java/com/slparcelauctions/backend/escrow/EscrowServiceExpireTransferTest.java`

```java
@Test
void expireTransferIncrementsSellerEscrowExpiredUnfulfilled() {
    User seller = userRepo.save(User.builder().email("s@x.com").passwordHash("p").build());
    User buyer = userRepo.save(User.builder().email("b@x.com").passwordHash("p").build());
    Auction a = persistAuctionFor(seller);
    Escrow escrow = escrowService.open(a, buyer, L(1000));
    // Force to TRANSFER_PENDING; details depend on existing helpers
    advanceToTransferPending(escrow);
    OffsetDateTime now = OffsetDateTime.now();

    txTemplate.executeWithoutResult(s ->
            escrowService.expireTransfer(escrowRepo.findByIdForUpdate(escrow.getId()).orElseThrow(), now));

    assertThat(userRepo.findById(seller.getId()).orElseThrow()
            .getEscrowExpiredUnfulfilled()).isEqualTo(1);
}

@Test
void expirePaymentDoesNotIncrementEscrowExpiredUnfulfilled() {
    // mirror but call expirePayment on a fresh ESCROW_PENDING escrow
    // assert counter remains 0 (buyer-fault, not seller's)
}
```

Run: both fail (counter not incremented).

- [ ] **Step 5: Implement the increment in `EscrowService.expireTransfer`**

Find line 678-700 range. Inside the `expireTransfer` method, after `escrow = escrowRepo.save(escrow);`, add:

```java
// Seller failed to transfer the parcel in time. Count this against the
// seller's completion rate (formula B denominator).
User seller = escrow.getAuction().getSeller();
seller.setEscrowExpiredUnfulfilled(seller.getEscrowExpiredUnfulfilled() + 1);
userRepo.save(seller);
```

Inject `UserRepository` via `@RequiredArgsConstructor` if not already. Do NOT touch `expirePayment` — buyer-fault, different attribution.

Run: `./mvnw test -Dtest=EscrowServiceExpireTransferTest` — passes.

- [ ] **Step 6: Write failing test — `handleEscrowPayoutSuccess` increments seller.completedSales**

File: `backend/src/test/java/com/slparcelauctions/backend/escrow/command/TerminalCommandPayoutSuccessTest.java`

```java
@Test
void payoutSuccessIncrementsSellerCompletedSales() {
    User seller = createUser("s@x.com");
    int before = seller.getCompletedSales();
    // Set up escrow in TRANSFER_PENDING with a PAYOUT command in flight
    Escrow escrow = setupEscrowWithPayoutCommand(seller);
    String slTxn = "tx-" + UUID.randomUUID();

    // Simulate the callback that flips escrow to COMPLETED
    terminalCommandService.applyCallback(commandId, slTxn);

    assertThat(userRepo.findById(seller.getId()).orElseThrow().getCompletedSales())
            .isEqualTo(before + 1);
    assertThat(escrowRepo.findById(escrow.getId()).orElseThrow().getState())
            .isEqualTo(EscrowState.COMPLETED);
}
```

Run: fails (counter not incremented today).

- [ ] **Step 7: Implement in `TerminalCommandService.handleEscrowPayoutSuccess`**

At line 211-ish, after `escrow = escrowRepo.save(escrow);`, add:

```java
// New in Epic 08 sub-spec 1: track completed sales for seller
// completion-rate. Incremented inside the same tx that flips escrow →
// COMPLETED so the counter can't drift.
User seller = escrow.getAuction().getSeller();
seller.setCompletedSales(seller.getCompletedSales() + 1);
userRepo.save(seller);
```

Inject `UserRepository` if not already present.

Run: passes.

- [ ] **Step 8: Commit Task 1 partial — counters**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/user/User.java \
        backend/src/main/java/com/slparcelauctions/backend/user/SellerCompletionRateMapper.java \
        backend/src/main/java/com/slparcelauctions/backend/auction/AuctionDtoMapper.java \
        backend/src/main/java/com/slparcelauctions/backend/escrow/EscrowService.java \
        backend/src/main/java/com/slparcelauctions/backend/escrow/command/TerminalCommandService.java \
        backend/src/test/java/com/slparcelauctions/backend/user/SellerCompletionRateMapperTest.java \
        backend/src/test/java/com/slparcelauctions/backend/user/UserEntityTest.java \
        backend/src/test/java/com/slparcelauctions/backend/escrow/EscrowServiceExpireTransferTest.java \
        backend/src/test/java/com/slparcelauctions/backend/escrow/command/TerminalCommandPayoutSuccessTest.java
git commit -m "feat(user): User.escrowExpiredUnfulfilled counter + 3-arg completion rate + live escrow increments"
```

- [ ] **Step 9: Write failing tests — `Review` entity persists, unique constraint works**

File: `backend/src/test/java/com/slparcelauctions/backend/review/ReviewEntityTest.java`

```java
@Test
void reviewPersistsWithDefaults() { ... }

@Test
void uniqueConstraintOnAuctionReviewer() {
    // attempt to insert two reviews with same auction_id + reviewer_id
    // expect DataIntegrityViolationException on second save
}

@Test
void visibleDefaultsFalse() { ... }
```

Run: fails (entity doesn't exist).

- [ ] **Step 10: Create `ReviewedRole`, `ReviewFlagReason` enums**

```java
// ReviewedRole.java
public enum ReviewedRole { SELLER, BUYER }

// ReviewFlagReason.java
public enum ReviewFlagReason { SPAM, ABUSIVE, OFF_TOPIC, FALSE_INFO, OTHER }
```

- [ ] **Step 11: Create `Review.java`**

Full entity per spec §3.1. Use `@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder`. FetchType.LAZY on ManyToOne. `@CreationTimestamp` on `submittedAt`. Unique constraint: `@UniqueConstraint(columnNames = {"auction_id", "reviewer_id"}, name = "uq_reviews_auction_reviewer")`. Indexes: `idx_reviews_reviewee_visible` on `(reviewee_id, reviewed_role, visible)`, `idx_reviews_auction` on `auction_id`.

- [ ] **Step 12: Create `ReviewResponse.java` and `ReviewFlag.java`**

Per spec §3.2, §3.3.

- [ ] **Step 13: Create the three repositories**

```java
public interface ReviewRepository extends JpaRepository<Review, Long> {
    Optional<Review> findByAuctionIdAndReviewerId(Long auctionId, Long reviewerId);

    @Query("select r from Review r where r.id = :id")
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Review> findByIdForUpdate(@Param("id") Long id);

    @Query("select new com.slparcelauctions.backend.review.Aggregate(" +
           "  avg(r.rating), count(r)) " +
           "from Review r " +
           "where r.reviewee.id = :revieweeId " +
           "  and r.reviewedRole = com.slparcelauctions.backend.review.ReviewedRole.SELLER " +
           "  and r.visible = true")
    Aggregate computeSellerAggregate(@Param("revieweeId") Long revieweeId);

    @Query("select new com.slparcelauctions.backend.review.Aggregate(" +
           "  avg(r.rating), count(r)) " +
           "from Review r " +
           "where r.reviewee.id = :revieweeId " +
           "  and r.reviewedRole = com.slparcelauctions.backend.review.ReviewedRole.BUYER " +
           "  and r.visible = true")
    Aggregate computeBuyerAggregate(@Param("revieweeId") Long revieweeId);

    @Query("""
           select r from Review r
           where r.visible = false
             and r.auction.id in (
               select e.auction.id from Escrow e
               where e.state = 'COMPLETED'
                 and e.completedAt < :threshold
             )
           order by r.id
           """)
    List<Review> findRevealable(@Param("threshold") OffsetDateTime threshold, Pageable page);
}

public interface ReviewResponseRepository extends JpaRepository<ReviewResponse, Long> {
    Optional<ReviewResponse> findByReviewId(Long reviewId);
    boolean existsByReviewId(Long reviewId);
}

public interface ReviewFlagRepository extends JpaRepository<ReviewFlag, Long> {
    boolean existsByReviewIdAndFlaggerId(Long reviewId, Long flaggerId);
}
```

- [ ] **Step 14: Create `Aggregate.java`**

```java
package com.slparcelauctions.backend.review;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * JPQL projection record for AVG+COUNT over visible reviews. The JPQL
 * returns Double and Long; we normalize avg to a BigDecimal with scale=2,
 * RoundingMode.HALF_UP, to match the User column precision(3, scale=2).
 */
public record Aggregate(BigDecimal avg, int count) {
    public Aggregate(Double avgRaw, Long countRaw) {
        this(
            avgRaw == null
                ? null
                : BigDecimal.valueOf(avgRaw).setScale(2, RoundingMode.HALF_UP),
            countRaw == null ? 0 : countRaw.intValue()
        );
    }
}
```

- [ ] **Step 15: Run entity tests — pass**

Run: `./mvnw test -Dtest=ReviewEntityTest` — passes.

- [ ] **Step 16: Write failing test — `ReviewService.submit` eligibility**

File: `backend/src/test/java/com/slparcelauctions/backend/review/ReviewServiceSubmitTest.java`

Tests to write (use Mockito for dependencies):

```java
@Test
void submitRejectsWhenEscrowNotCompleted() {
    // escrow state = ESCROW_PENDING
    // expect ReviewIneligibleException
}

@Test
void submitRejectsWhenCallerNotPartyToAuction() {
    // caller.id = 999 (not seller, not winner)
    // expect ReviewIneligibleException (or 403 at controller layer)
}

@Test
void submitRejectsAfterWindowCloses() {
    // escrow.completedAt = 15 days ago
    // expect ReviewWindowClosedException
}

@Test
void submitRejectsDuplicateFromSameReviewer() {
    // existing Review with (auctionId, callerId)
    // expect ReviewAlreadySubmittedException
}

@Test
void submitSucceedsForSellerSubmittingAboutBuyer() {
    // caller = seller → reviewedRole=BUYER, reviewee=winner
    // assert Review persisted with visible=false, reviewedRole=BUYER, reviewee=winner
}

@Test
void submitSucceedsForWinnerSubmittingAboutSeller() {
    // caller = winner → reviewedRole=SELLER, reviewee=seller
}

@Test
void simultaneousSubmitRevealsBoth() {
    // step 1: winner submits (visible=false)
    // step 2: seller submits
    // after step 2: both rows visible=true, revealedAt stamped, aggregates recomputed
}
```

Run: fails (service + exceptions don't exist).

- [ ] **Step 17: Create exception classes**

```java
// ReviewIneligibleException — 422
public class ReviewIneligibleException extends RuntimeException {
    public ReviewIneligibleException(String msg) { super(msg); }
}

// ReviewAlreadySubmittedException — 409
public class ReviewAlreadySubmittedException extends RuntimeException { ... }

// ReviewWindowClosedException — 422
public class ReviewWindowClosedException extends RuntimeException { ... }

// ReviewNotFoundException — 404
public class ReviewNotFoundException extends RuntimeException { ... }
```

- [ ] **Step 18: Create `ReviewSubmitRequest.java`**

```java
public record ReviewSubmitRequest(
    @Min(1) @Max(5) Integer rating,
    @Size(max = 500) String text  // nullable
) {}
```

- [ ] **Step 19: Create `ReviewDto.java`**

```java
public record ReviewDto(
    Long id,
    Long auctionId,
    String auctionTitle,
    String auctionPrimaryPhotoUrl,
    Long reviewerId,
    String reviewerDisplayName,
    String reviewerAvatarUrl,
    Long revieweeId,
    ReviewedRole reviewedRole,
    Integer rating,
    String text,
    Boolean visible,
    Boolean pending,
    OffsetDateTime submittedAt,
    OffsetDateTime revealedAt,
    ReviewResponseDto response
) {
    /**
     * Build a ReviewDto from a persisted Review. Resolves the reviewer's
     * live display name + avatar so renamed users immediately show as
     * renamed. If `viewerId` equals `reviewer.id`, flags pending=true and
     * includes submittedAt for private-view use.
     */
    public static ReviewDto of(Review r, Long viewerId, Optional<ReviewResponse> resp,
                                String primaryPhotoUrl) {
        boolean viewerIsReviewer = r.getReviewer().getId().equals(viewerId);
        boolean pending = !Boolean.TRUE.equals(r.getVisible()) && viewerIsReviewer;
        boolean exposeText = Boolean.TRUE.equals(r.getVisible()) || viewerIsReviewer;
        return new ReviewDto(
            r.getId(),
            r.getAuction().getId(),
            r.getAuction().getTitle(),
            primaryPhotoUrl,
            r.getReviewer().getId(),
            r.getReviewer().getDisplayName(),
            r.getReviewer().getProfilePicUrl(),
            r.getReviewee().getId(),
            r.getReviewedRole(),
            exposeText ? r.getRating() : null,
            exposeText ? r.getText() : null,
            r.getVisible(),
            pending,
            exposeText ? r.getSubmittedAt() : null,
            r.getRevealedAt(),
            resp.map(ReviewResponseDto::of).orElse(null)
        );
    }
}
```

- [ ] **Step 20: Create `ReviewResponseDto.java`**

```java
public record ReviewResponseDto(
    Long id,
    String text,
    OffsetDateTime createdAt
) {
    public static ReviewResponseDto of(ReviewResponse r) {
        return new ReviewResponseDto(r.getId(), r.getText(), r.getCreatedAt());
    }
}
```

- [ ] **Step 21: Create `ReviewService.java` (submit path only for Task 1)**

```java
@Service
@RequiredArgsConstructor
@Slf4j
public class ReviewService {
    private static final Duration REVIEW_WINDOW = Duration.ofDays(14);

    private final ReviewRepository reviewRepo;
    private final ReviewResponseRepository responseRepo;
    private final AuctionRepository auctionRepo;
    private final EscrowRepository escrowRepo;
    private final UserRepository userRepo;
    private final Clock clock;
    private final EscrowBroadcastPublisher broadcastPublisher;  // reused; publishReviewRevealed added in Task 2

    @Transactional
    public ReviewDto submit(Long auctionId, User caller, ReviewSubmitRequest req) {
        Auction auction = auctionRepo.findById(auctionId)
                .orElseThrow(() -> new AuctionNotFoundException(auctionId));
        Escrow escrow = escrowRepo.findByAuctionId(auctionId)
                .orElseThrow(() -> new ReviewIneligibleException(
                        "Auction has no escrow"));

        if (escrow.getState() != EscrowState.COMPLETED) {
            throw new ReviewIneligibleException(
                    "Can only review auctions whose escrow has completed.");
        }

        OffsetDateTime now = OffsetDateTime.now(clock);
        OffsetDateTime windowCloses = escrow.getCompletedAt().plus(REVIEW_WINDOW);
        if (now.isAfter(windowCloses)) {
            throw new ReviewWindowClosedException(
                    "The 14-day review window for this auction has closed.");
        }

        Long sellerId = auction.getSeller().getId();
        Long winnerId = escrow.getWinner().getId();
        Long callerId = caller.getId();

        if (!callerId.equals(sellerId) && !callerId.equals(winnerId)) {
            throw new ReviewIneligibleException(
                    "Only the seller or winner can review this auction.");
        }

        if (reviewRepo.findByAuctionIdAndReviewerId(auctionId, callerId).isPresent()) {
            throw new ReviewAlreadySubmittedException(
                    "You have already submitted a review for this auction.");
        }

        // Derive reviewee + reviewedRole. Caller's role determines whose
        // behavior is being rated. Caller = seller → reviewing the winner's
        // BUYER behavior. Caller = winner → reviewing the seller's SELLER
        // behavior.
        User reviewee;
        ReviewedRole reviewedRole;
        if (callerId.equals(sellerId)) {
            reviewee = escrow.getWinner();
            reviewedRole = ReviewedRole.BUYER;
        } else {
            reviewee = auction.getSeller();
            reviewedRole = ReviewedRole.SELLER;
        }

        Review mine = reviewRepo.save(Review.builder()
                .auction(auction)
                .reviewer(caller)
                .reviewee(reviewee)
                .reviewedRole(reviewedRole)
                .rating(req.rating())
                .text(req.text())
                .visible(false)
                .build());

        // Simultaneous-reveal branch — if the counterparty already
        // submitted, flip both visible atomically and recompute both
        // reviewees' aggregates inside this transaction.
        Long counterpartyId = callerId.equals(sellerId) ? winnerId : sellerId;
        Optional<Review> theirs = reviewRepo.findByAuctionIdAndReviewerId(auctionId, counterpartyId);
        if (theirs.isPresent() && !Boolean.TRUE.equals(theirs.get().getVisible())) {
            reveal(mine.getId());
            reveal(theirs.get().getId());
        }

        // For the pending-view DTO, include submittedAt/text so the caller
        // sees their own review immediately.
        return ReviewDto.of(mine, callerId,
                responseRepo.findByReviewId(mine.getId()),
                resolvePrimaryPhotoUrl(mine.getAuction()));
    }

    // reveal() + recomputeAggregates() added in Task 2; Task 1 only uses
    // submit(). For Task 1 commit stability, include reveal() as a stub
    // that throws UnsupportedOperationException; Task 2 will replace.
    //
    // NO — better: move the simultaneous-reveal-path test to Task 2 so
    // we don't have a stub. For Task 1, the service does NOT attempt
    // simultaneous reveal; it just persists the row and returns.
    // Adjust Step 16's "simultaneousSubmitRevealsBoth" test to land in
    // Task 2 instead.

    private String resolvePrimaryPhotoUrl(Auction auction) {
        // Match the pattern used in AuctionDtoMapper: first AuctionPhoto by
        // sort_order, else parcel snapshotUrl.
        return auction.getPhotos().stream()
                .sorted(Comparator.comparing(AuctionPhoto::getSortOrder))
                .findFirst()
                .map(AuctionPhoto::getUrl)
                .orElseGet(() -> auction.getParcel().getSnapshotUrl());
    }
}
```

**Important revision:** The simultaneous-reveal logic and `reveal()` live in Task 2. Task 1's submit only persists the row with `visible=false`. Adjust Step 16's test list accordingly: remove `simultaneousSubmitRevealsBoth`, keep the eligibility tests only.

- [ ] **Step 22: Create `ReviewController.java` (submit endpoint only)**

```java
@RestController
@RequestMapping("/api/v1/auctions/{id}/reviews")
@RequiredArgsConstructor
public class ReviewController {
    private final ReviewService reviewService;
    private final UserService userService;  // for @AuthenticationPrincipal → User resolution

    @PostMapping
    public ResponseEntity<ReviewDto> submit(
            @PathVariable("id") Long auctionId,
            @AuthenticationPrincipal UserDetails principal,
            @Valid @RequestBody ReviewSubmitRequest request) {
        User caller = userService.requireByEmail(principal.getUsername());
        ReviewDto dto = reviewService.submit(auctionId, caller, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(dto);
    }
}
```

- [ ] **Step 23: Create `ReviewExceptionHandler.java`**

```java
@RestControllerAdvice(basePackageClasses = ReviewController.class)
@Slf4j
public class ReviewExceptionHandler {

    @ExceptionHandler(ReviewNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ErrorResponse notFound(ReviewNotFoundException ex) { ... }

    @ExceptionHandler(ReviewIneligibleException.class)
    @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
    public ErrorResponse ineligible(ReviewIneligibleException ex) { ... }

    @ExceptionHandler(ReviewWindowClosedException.class)
    @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
    public ErrorResponse windowClosed(ReviewWindowClosedException ex) { ... }

    @ExceptionHandler(ReviewAlreadySubmittedException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ErrorResponse alreadySubmitted(ReviewAlreadySubmittedException ex) { ... }
}
```

Follow existing `ErrorResponse` shape from `common/`.

- [ ] **Step 24: Write slice test — `@WebMvcTest(ReviewController.class)`**

File: `backend/src/test/java/com/slparcelauctions/backend/review/ReviewControllerTest.java`

```java
@Test void submitReturns201() { ... }
@Test void submitReturns422WhenIneligible() { ... }
@Test void submitReturns422WhenWindowClosed() { ... }
@Test void submitReturns409WhenDuplicate() { ... }
@Test void submitReturns401WhenUnauthenticated() { ... }
@Test void submitReturns400WhenRatingOutOfRange() { ... }
@Test void submitReturns400WhenTextTooLong() { ... }
```

- [ ] **Step 25: Update `SecurityConfig.java`**

Ensure POST `/api/v1/auctions/*/reviews` requires authentication. GET endpoints will be added in Task 2 — those will be public.

- [ ] **Step 26: Add `SellerSummaryDto.completedSales` field**

Modify `SellerSummaryDto.java`:

```java
public record SellerSummaryDto(
        Long id,
        String displayName,
        String avatarUrl,
        BigDecimal averageRating,
        Integer reviewCount,
        Integer completedSales
) {}
```

Update `AuctionSearchResultMapper.java` to populate `completedSales`. Update any DTO constructors in tests.

- [ ] **Step 27: Extend `UserProfileResponse.java` with `completionRate`, `isNewSeller`**

```java
public record UserProfileResponse(
        Long id,
        String displayName,
        String bio,
        String profilePicUrl,
        UUID slAvatarUuid,
        String slUsername,
        String slDisplayName,
        Boolean verified,
        BigDecimal avgSellerRating,
        BigDecimal avgBuyerRating,
        Integer totalSellerReviews,
        Integer totalBuyerReviews,
        Integer completedSales,
        BigDecimal completionRate,
        Boolean isNewSeller,
        OffsetDateTime createdAt) {

    public static UserProfileResponse from(User user) {
        BigDecimal rate = SellerCompletionRateMapper.compute(
                user.getCompletedSales(),
                user.getCancelledWithBids(),
                user.getEscrowExpiredUnfulfilled());
        boolean isNewSeller = user.getCompletedSales() < 3;
        return new UserProfileResponse(
                user.getId(),
                user.getDisplayName(),
                user.getBio(),
                user.getProfilePicUrl(),
                user.getSlAvatarUuid(),
                user.getSlUsername(),
                user.getSlDisplayName(),
                user.getVerified(),
                user.getAvgSellerRating(),
                user.getAvgBuyerRating(),
                user.getTotalSellerReviews(),
                user.getTotalBuyerReviews(),
                user.getCompletedSales(),
                rate,
                isNewSeller,
                user.getCreatedAt());
    }
}
```

- [ ] **Step 28: Run all backend tests**

Run: `./mvnw test`. All pass. If any unrelated tests break because they constructed `SellerSummaryDto` or `UserProfileResponse` with old constructors, fix those too.

- [ ] **Step 29: Commit Task 1**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/review/ \
        backend/src/test/java/com/slparcelauctions/backend/review/ \
        backend/src/main/java/com/slparcelauctions/backend/user/dto/UserProfileResponse.java \
        backend/src/main/java/com/slparcelauctions/backend/auction/search/SellerSummaryDto.java \
        backend/src/main/java/com/slparcelauctions/backend/auction/search/AuctionSearchResultMapper.java \
        backend/src/main/java/com/slparcelauctions/backend/config/SecurityConfig.java
git commit -m "feat(review): entities + submit endpoint + exception mapping"
```

---

## Task 2 — Backend: Reveal scheduler, aggregates, list endpoints, WS

**Goal:** Complete the review read path and visibility machinery. Hourly scheduler flips day-14 reviews visible. Simultaneous submit-path now triggers synchronous reveal + aggregate recompute + WS broadcast.

**Files:**

- Create: `backend/src/main/java/com/slparcelauctions/backend/review/BlindReviewRevealTask.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/review/ReviewRevealedEnvelope.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/review/UserReviewsController.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/review/dto/AuctionReviewsResponse.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/review/dto/PendingReviewDto.java`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/review/ReviewService.java` — add `reveal`, `recomputeAggregates`, `listForAuction`, `listForUser`, `listPendingForCaller`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/review/ReviewController.java` — add GET /auctions/{id}/reviews
- Modify: `backend/src/main/java/com/slparcelauctions/backend/escrow/broadcast/*` — add `publishReviewRevealed`
- Tests for each

### Steps

- [ ] **Step 1: Write failing test — `ReviewService.reveal` flips visibility and recomputes**

```java
@Test
void revealFlipsVisibilityAndStampsRevealedAt() {
    Review r = persistPendingReview();
    reviewService.reveal(r.getId());
    Review updated = reviewRepo.findById(r.getId()).orElseThrow();
    assertThat(updated.getVisible()).isTrue();
    assertThat(updated.getRevealedAt()).isNotNull();
}

@Test
void revealRecomputesAggregatesForReviewee() {
    User reviewee = createUser();
    persistThreeVisibleReviewsWithRatings(reviewee, 4, 5, 5, ReviewedRole.SELLER);
    Review fourth = persistPendingReview(reviewee, 3, ReviewedRole.SELLER);
    reviewService.reveal(fourth.getId());
    User updated = userRepo.findById(reviewee.getId()).orElseThrow();
    // AVG(4, 5, 5, 3) = 17/4 = 4.25
    assertThat(updated.getAvgSellerRating())
            .isEqualByComparingTo(new BigDecimal("4.25"));
    assertThat(updated.getTotalSellerReviews()).isEqualTo(4);
}

@Test
void revealIsIdempotent() {
    Review r = persistPendingReview();
    reviewService.reveal(r.getId());
    OffsetDateTime first = reviewRepo.findById(r.getId()).orElseThrow().getRevealedAt();
    reviewService.reveal(r.getId());  // second call
    OffsetDateTime second = reviewRepo.findById(r.getId()).orElseThrow().getRevealedAt();
    assertThat(second).isEqualTo(first);  // not overwritten
}
```

- [ ] **Step 2: Implement `ReviewService.reveal` + `recomputeAggregates`**

```java
@Transactional
public void reveal(Long reviewId) {
    Review r = reviewRepo.findByIdForUpdate(reviewId)
            .orElseThrow(() -> new ReviewNotFoundException(reviewId));
    if (Boolean.TRUE.equals(r.getVisible())) return;  // idempotent

    r.setVisible(true);
    r.setRevealedAt(OffsetDateTime.now(clock));
    reviewRepo.save(r);

    recomputeAggregates(r.getReviewee(), r.getReviewedRole());

    ReviewRevealedEnvelope envelope = ReviewRevealedEnvelope.of(r);
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
            @Override public void afterCommit() {
                broadcastPublisher.publishReviewRevealed(envelope);
            }
        });
    log.info("Review {} revealed: auction={}, reviewee={}, role={}",
            r.getId(), r.getAuction().getId(), r.getReviewee().getId(),
            r.getReviewedRole());
}

private void recomputeAggregates(User reviewee, ReviewedRole role) {
    if (role == ReviewedRole.SELLER) {
        Aggregate agg = reviewRepo.computeSellerAggregate(reviewee.getId());
        reviewee.setAvgSellerRating(agg.avg());
        reviewee.setTotalSellerReviews(agg.count());
    } else {
        Aggregate agg = reviewRepo.computeBuyerAggregate(reviewee.getId());
        reviewee.setAvgBuyerRating(agg.avg());
        reviewee.setTotalBuyerReviews(agg.count());
    }
    userRepo.save(reviewee);
}
```

- [ ] **Step 3: Add the simultaneous-reveal branch back to `submit`**

Uncomment/add the "Simultaneous-reveal branch" described in Task 1 Step 21. Write test:

```java
@Test
void simultaneousSubmitRevealsBoth() {
    Auction a = auctionWithCompletedEscrow();
    reviewService.submit(a.getId(), winnerUser, new ReviewSubmitRequest(5, "gg"));
    reviewService.submit(a.getId(), sellerUser, new ReviewSubmitRequest(4, "fast pay"));

    // Both reviews visible after the second submit
    List<Review> reviews = reviewRepo.findAll().stream()
            .filter(r -> r.getAuction().getId().equals(a.getId()))
            .toList();
    assertThat(reviews).hasSize(2)
            .allMatch(r -> Boolean.TRUE.equals(r.getVisible()))
            .allMatch(r -> r.getRevealedAt() != null);

    // Both reviewees' aggregates updated
    User sellerAfter = userRepo.findById(sellerUser.getId()).orElseThrow();
    User winnerAfter = userRepo.findById(winnerUser.getId()).orElseThrow();
    assertThat(sellerAfter.getTotalSellerReviews()).isEqualTo(1);
    assertThat(winnerAfter.getTotalBuyerReviews()).isEqualTo(1);
}
```

- [ ] **Step 4: Create `ReviewRevealedEnvelope.java`**

```java
package com.slparcelauctions.backend.review;

import com.slparcelauctions.backend.auction.AuctionTopicEnvelope;
import java.time.OffsetDateTime;

public record ReviewRevealedEnvelope(
    String type,
    Long auctionId,
    Long reviewId,
    Long reviewerId,
    Long revieweeId,
    ReviewedRole reviewedRole,
    OffsetDateTime revealedAt
) implements AuctionTopicEnvelope {
    public static ReviewRevealedEnvelope of(Review r) {
        return new ReviewRevealedEnvelope(
            "REVIEW_REVEALED",
            r.getAuction().getId(),
            r.getId(),
            r.getReviewer().getId(),
            r.getReviewee().getId(),
            r.getReviewedRole(),
            r.getRevealedAt()
        );
    }
}
```

Note: `AuctionTopicEnvelope` is a sealed interface in the auction package today. Add `ReviewRevealedEnvelope` to its `permits` clause (or add the marker interface if it uses one). Read `AuctionEnvelope`/`EscrowEnvelope` to match the pattern.

- [ ] **Step 5: Add `publishReviewRevealed` to broadcast publisher**

Find the existing `EscrowBroadcastPublisher` (or equivalent). Add:

```java
public void publishReviewRevealed(ReviewRevealedEnvelope envelope) {
    messaging.convertAndSend(
            "/topic/auction/" + envelope.auctionId(),
            envelope);
    log.debug("Broadcast REVIEW_REVEALED: reviewId={}", envelope.reviewId());
}
```

- [ ] **Step 6: Create `BlindReviewRevealTask`**

```java
@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(
    name = "slpa.review.scheduler.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class BlindReviewRevealTask {
    private static final Duration REVIEW_WINDOW = Duration.ofDays(14);
    private static final int BATCH_LIMIT = 500;

    private final ReviewRepository reviewRepo;
    private final ReviewService reviewService;
    private final Clock clock;

    /** Top of every hour. */
    @Scheduled(cron = "0 0 * * * *")
    public void run() {
        OffsetDateTime threshold = OffsetDateTime.now(clock).minus(REVIEW_WINDOW);
        List<Review> revealable = reviewRepo.findRevealable(
                threshold, PageRequest.of(0, BATCH_LIMIT));
        if (revealable.isEmpty()) return;

        log.info("BlindReviewReveal: {} reviews past day-14 window", revealable.size());
        for (Review r : revealable) {
            try {
                reviewService.reveal(r.getId());
            } catch (Exception e) {
                log.error("Failed to reveal review {}: {}", r.getId(), e.toString());
            }
        }
        if (revealable.size() == BATCH_LIMIT) {
            log.warn("BlindReviewReveal: hit batch limit {}; re-running next tick", BATCH_LIMIT);
        }
    }
}
```

- [ ] **Step 7: Write scheduler test**

File: `BlindReviewRevealTaskTest.java`

```java
@Test
void revealsReviewsOlderThan14Days() {
    // escrow.completedAt = 15 days ago, review still visible=false
    // run task
    // assert review.visible=true
}

@Test
void skipsReviewsWithin14Days() {
    // escrow.completedAt = 10 days ago
    // run task
    // assert review.visible=false
}

@Test
void idempotentOnRerun() {
    // run twice, assert revealedAt unchanged on second
}
```

- [ ] **Step 8: Create `AuctionReviewsResponse.java`**

```java
public record AuctionReviewsResponse(
    List<ReviewDto> reviews,
    ReviewDto myPendingReview,  // nullable
    Boolean canReview,
    OffsetDateTime windowClosesAt  // nullable
) {}
```

- [ ] **Step 9: Add `GET /api/v1/auctions/{id}/reviews` endpoint**

In `ReviewController.java`:

```java
@GetMapping
public AuctionReviewsResponse list(
        @PathVariable("id") Long auctionId,
        @AuthenticationPrincipal UserDetails principal) {
    User caller = principal == null
            ? null
            : userService.findByEmail(principal.getUsername()).orElse(null);
    return reviewService.listForAuction(auctionId, caller);
}
```

- [ ] **Step 10: Implement `ReviewService.listForAuction`**

```java
@Transactional(readOnly = true)
public AuctionReviewsResponse listForAuction(Long auctionId, User caller) {
    Auction auction = auctionRepo.findById(auctionId)
            .orElseThrow(() -> new AuctionNotFoundException(auctionId));
    Optional<Escrow> maybeEscrow = escrowRepo.findByAuctionId(auctionId);

    List<Review> visible = reviewRepo.findByAuctionIdAndVisibleTrue(auctionId);
    String primaryPhoto = resolvePrimaryPhotoUrl(auction);

    List<ReviewDto> visibleDtos = visible.stream()
            .map(r -> ReviewDto.of(r, caller == null ? null : caller.getId(),
                    responseRepo.findByReviewId(r.getId()), primaryPhoto))
            .toList();

    ReviewDto myPending = null;
    boolean canReview = false;
    OffsetDateTime windowCloses = null;

    if (caller != null && maybeEscrow.isPresent()
            && maybeEscrow.get().getState() == EscrowState.COMPLETED) {
        Escrow e = maybeEscrow.get();
        Long sellerId = auction.getSeller().getId();
        Long winnerId = e.getWinner().getId();
        boolean isParty = caller.getId().equals(sellerId)
                || caller.getId().equals(winnerId);
        if (isParty) {
            windowCloses = e.getCompletedAt().plus(REVIEW_WINDOW);
            OffsetDateTime now = OffsetDateTime.now(clock);
            boolean windowOpen = now.isBefore(windowCloses);
            Optional<Review> mine = reviewRepo
                    .findByAuctionIdAndReviewerId(auctionId, caller.getId());
            canReview = windowOpen && mine.isEmpty();
            myPending = mine
                    .filter(r -> !Boolean.TRUE.equals(r.getVisible()))
                    .map(r -> ReviewDto.of(r, caller.getId(),
                            responseRepo.findByReviewId(r.getId()), primaryPhoto))
                    .orElse(null);
        }
    }

    return new AuctionReviewsResponse(visibleDtos, myPending, canReview, windowCloses);
}
```

- [ ] **Step 11: Add `findByAuctionIdAndVisibleTrue` to `ReviewRepository`**

```java
@Query("select r from Review r where r.auction.id = :auctionId and r.visible = true " +
       "order by r.revealedAt desc")
List<Review> findByAuctionIdAndVisibleTrue(@Param("auctionId") Long auctionId);
```

- [ ] **Step 12: Create `UserReviewsController.java`**

```java
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class UserReviewsController {
    private final ReviewService reviewService;
    private final UserService userService;

    @GetMapping("/users/{id}/reviews")
    public PagedResponse<ReviewDto> listForUser(
            @PathVariable("id") Long userId,
            @RequestParam("role") ReviewedRole role,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "10") int size) {
        Page<ReviewDto> result = reviewService.listForUser(userId, role,
                PageRequest.of(page, Math.min(size, 50)));
        return PagedResponse.from(result);
    }

    @GetMapping("/users/me/pending-reviews")
    public List<PendingReviewDto> listPending(
            @AuthenticationPrincipal UserDetails principal) {
        User caller = userService.requireByEmail(principal.getUsername());
        return reviewService.listPendingForCaller(caller);
    }
}
```

- [ ] **Step 13: Implement `listForUser` and `listPendingForCaller`**

```java
@Transactional(readOnly = true)
public Page<ReviewDto> listForUser(Long userId, ReviewedRole role, Pageable page) {
    Page<Review> reviews = reviewRepo.findByRevieweeIdAndReviewedRoleAndVisibleTrue(
            userId, role, page.withSort(Sort.by(Sort.Direction.DESC, "revealedAt")));
    return reviews.map(r -> ReviewDto.of(r, null,
            responseRepo.findByReviewId(r.getId()),
            resolvePrimaryPhotoUrl(r.getAuction())));
}

@Transactional(readOnly = true)
public List<PendingReviewDto> listPendingForCaller(User caller) {
    // Completed escrows where caller is seller or winner, window open, no review yet
    OffsetDateTime now = OffsetDateTime.now(clock);
    OffsetDateTime threshold = now.minus(REVIEW_WINDOW);
    return escrowRepo.findCompletedEscrowsForUser(caller.getId(), threshold)
            .stream()
            .filter(e -> reviewRepo
                    .findByAuctionIdAndReviewerId(e.getAuction().getId(), caller.getId())
                    .isEmpty())
            .map(e -> PendingReviewDto.of(e, caller, now))
            .toList();
}
```

Add `findCompletedEscrowsForUser` to `EscrowRepository`:

```java
@Query("""
       select e from Escrow e
       where e.state = 'COMPLETED'
         and e.completedAt > :threshold
         and (e.auction.seller.id = :userId or e.winner.id = :userId)
       """)
List<Escrow> findCompletedEscrowsForUser(
        @Param("userId") Long userId,
        @Param("threshold") OffsetDateTime threshold);
```

Add `findByRevieweeIdAndReviewedRoleAndVisibleTrue` to `ReviewRepository`:

```java
Page<Review> findByRevieweeIdAndReviewedRoleAndVisibleTrue(
        Long revieweeId, ReviewedRole role, Pageable page);
```

- [ ] **Step 14: Create `PendingReviewDto.java`**

```java
public record PendingReviewDto(
    Long auctionId,
    String title,
    String primaryPhotoUrl,
    Long counterpartyId,
    String counterpartyDisplayName,
    String counterpartyAvatarUrl,
    OffsetDateTime escrowCompletedAt,
    OffsetDateTime windowClosesAt,
    long hoursRemaining,
    ReviewedRole viewerRole
) {
    public static PendingReviewDto of(Escrow e, User viewer, OffsetDateTime now) {
        boolean viewerIsSeller = e.getAuction().getSeller().getId().equals(viewer.getId());
        User counterparty = viewerIsSeller ? e.getWinner() : e.getAuction().getSeller();
        OffsetDateTime windowCloses = e.getCompletedAt().plus(Duration.ofDays(14));
        long hoursRemaining = Math.max(0,
                Duration.between(now, windowCloses).toHours());

        // ViewerRole here means "role of the person who needs to submit the
        // review" — if viewer is seller, they review the BUYER side. But the
        // frontend card shows their OWN role in the transaction, so use
        // SELLER/BUYER to describe the viewer.
        ReviewedRole viewerRole = viewerIsSeller ? ReviewedRole.SELLER : ReviewedRole.BUYER;

        String photo = e.getAuction().getPhotos().stream()
                .sorted(Comparator.comparing(AuctionPhoto::getSortOrder))
                .findFirst()
                .map(AuctionPhoto::getUrl)
                .orElseGet(() -> e.getAuction().getParcel().getSnapshotUrl());

        return new PendingReviewDto(
            e.getAuction().getId(),
            e.getAuction().getTitle(),
            photo,
            counterparty.getId(),
            counterparty.getDisplayName(),
            counterparty.getProfilePicUrl(),
            e.getCompletedAt(),
            windowCloses,
            hoursRemaining,
            viewerRole
        );
    }
}
```

- [ ] **Step 15: Update `SecurityConfig.java`**

Permit GET on review paths; JWT on write paths:

```java
.requestMatchers(HttpMethod.GET, "/api/v1/auctions/*/reviews").permitAll()
.requestMatchers(HttpMethod.GET, "/api/v1/users/*/reviews").permitAll()
.requestMatchers(HttpMethod.GET, "/api/v1/users/me/pending-reviews").authenticated()
.requestMatchers(HttpMethod.POST, "/api/v1/auctions/*/reviews").authenticated()
```

- [ ] **Step 16: Update frontend envelope type (deferred to Task 5)**

Note: leaves `AuctionTopicEnvelope` TS union at Task 5.

- [ ] **Step 17: Run all backend tests**

Run: `./mvnw test`. All pass.

- [ ] **Step 18: Commit Task 2**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/review/ \
        backend/src/test/java/com/slparcelauctions/backend/review/ \
        backend/src/main/java/com/slparcelauctions/backend/escrow/broadcast/ \
        backend/src/main/java/com/slparcelauctions/backend/escrow/EscrowRepository.java \
        backend/src/main/java/com/slparcelauctions/backend/config/SecurityConfig.java \
        backend/src/main/java/com/slparcelauctions/backend/auction/AuctionTopicEnvelope.java  # if sealed
git commit -m "feat(review): reveal scheduler + aggregates + list endpoints + WS"
```

---

## Task 3 — Backend: Response + flag endpoints

**Goal:** Ship the two secondary review actions. Small task — mostly repository + service + controller + exception wiring.

**Files:**

- Create: `backend/.../review/dto/ReviewResponseSubmitRequest.java`
- Create: `backend/.../review/dto/ReviewFlagRequest.java`
- Create: `backend/.../review/exception/ReviewResponseAlreadyExistsException.java`
- Create: `backend/.../review/exception/ReviewFlagAlreadyExistsException.java`
- Create: `backend/.../review/exception/ElaborationRequiredWhenOther.java` (annotation)
- Create: `backend/.../review/exception/ElaborationRequiredWhenOtherValidator.java`
- Modify: `ReviewService.java` — add `respondTo`, `flag` methods
- Modify: `ReviewController.java` — add POST /reviews/{id}/respond, POST /reviews/{id}/flag
- Modify: `ReviewExceptionHandler.java` — handle new exception types
- Tests for each path

### Steps

- [ ] **Step 1: Write failing test — respond**

```java
@Test
void respondReturns201FirstTime() { ... }
@Test
void respondReturns403WhenNotReviewee() { ... }
@Test
void respondReturns409OnDuplicate() { ... }
@Test
void respondReturns400OnEmptyText() { ... }
@Test
void respondReturns400OnTextTooLong() { ... }
```

- [ ] **Step 2: Create `ReviewResponseSubmitRequest`**

```java
public record ReviewResponseSubmitRequest(
    @NotBlank @Size(max = 500) String text
) {}
```

- [ ] **Step 3: Create `ReviewResponseAlreadyExistsException`** (409)

- [ ] **Step 4: Implement `ReviewService.respondTo`**

```java
@Transactional
public ReviewResponseDto respondTo(Long reviewId, User caller,
                                    ReviewResponseSubmitRequest req) {
    Review r = reviewRepo.findById(reviewId)
            .orElseThrow(() -> new ReviewNotFoundException(reviewId));
    if (!r.getReviewee().getId().equals(caller.getId())) {
        throw new AccessDeniedException("Only the reviewee can respond.");
    }
    if (responseRepo.existsByReviewId(reviewId)) {
        throw new ReviewResponseAlreadyExistsException(reviewId);
    }
    ReviewResponse resp = responseRepo.save(ReviewResponse.builder()
            .review(r)
            .text(req.text())
            .build());
    return ReviewResponseDto.of(resp);
}
```

- [ ] **Step 5: Add endpoint in `ReviewController`**

```java
@RestController
@RequestMapping("/api/v1/reviews")
@RequiredArgsConstructor
public class ReviewActionController {  // separate controller to avoid path collision

    private final ReviewService reviewService;
    private final UserService userService;

    @PostMapping("/{id}/respond")
    @ResponseStatus(HttpStatus.CREATED)
    public ReviewResponseDto respond(
            @PathVariable("id") Long reviewId,
            @AuthenticationPrincipal UserDetails principal,
            @Valid @RequestBody ReviewResponseSubmitRequest request) {
        User caller = userService.requireByEmail(principal.getUsername());
        return reviewService.respondTo(reviewId, caller, request);
    }
}
```

Two controller classes (one for `/auctions/{id}/reviews`, one for `/reviews/{id}/*`) keeps path conflicts trivial.

- [ ] **Step 6: Tests for flag**

```java
@Test
void flagReturns204OnFirstFlag() { ... }
@Test
void flagReturns403WhenCallerIsReviewer() { ... }
@Test
void flagReturns409OnDuplicate() { ... }
@Test
void flagReturns400WhenOtherWithoutElaboration() { ... }
@Test
void flagIncrementsReviewFlagCount() { ... }
```

- [ ] **Step 7: Create `ElaborationRequiredWhenOther` validator**

```java
@Documented
@Constraint(validatedBy = ElaborationRequiredWhenOtherValidator.class)
@Target(TYPE)
@Retention(RUNTIME)
public @interface ElaborationRequiredWhenOther {
    String message() default "Elaboration is required when reason is OTHER.";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
}

public class ElaborationRequiredWhenOtherValidator
        implements ConstraintValidator<ElaborationRequiredWhenOther, ReviewFlagRequest> {
    @Override
    public boolean isValid(ReviewFlagRequest r, ConstraintValidatorContext ctx) {
        if (r.reason() != ReviewFlagReason.OTHER) return true;
        return r.elaboration() != null && !r.elaboration().isBlank();
    }
}
```

- [ ] **Step 8: Create `ReviewFlagRequest`**

```java
@ElaborationRequiredWhenOther
public record ReviewFlagRequest(
    @NotNull ReviewFlagReason reason,
    @Size(max = 500) String elaboration
) {}
```

- [ ] **Step 9: Implement `ReviewService.flag`**

```java
@Transactional
public void flag(Long reviewId, User caller, ReviewFlagRequest req) {
    Review r = reviewRepo.findByIdForUpdate(reviewId)
            .orElseThrow(() -> new ReviewNotFoundException(reviewId));
    if (r.getReviewer().getId().equals(caller.getId())) {
        throw new AccessDeniedException("Cannot flag your own review.");
    }
    if (flagRepo.existsByReviewIdAndFlaggerId(reviewId, caller.getId())) {
        throw new ReviewFlagAlreadyExistsException(reviewId);
    }
    flagRepo.save(ReviewFlag.builder()
            .review(r)
            .flagger(caller)
            .reason(req.reason())
            .elaboration(req.elaboration())
            .build());
    r.setFlagCount(r.getFlagCount() + 1);
    reviewRepo.save(r);
    log.info("Review {} flagged by user {} (reason={})",
            reviewId, caller.getId(), req.reason());
}
```

- [ ] **Step 10: Endpoint for flag**

```java
@PostMapping("/{id}/flag")
@ResponseStatus(HttpStatus.NO_CONTENT)
public void flag(
        @PathVariable("id") Long reviewId,
        @AuthenticationPrincipal UserDetails principal,
        @Valid @RequestBody ReviewFlagRequest request) {
    User caller = userService.requireByEmail(principal.getUsername());
    reviewService.flag(reviewId, caller, request);
}
```

- [ ] **Step 11: Extend exception handler**

Add `@ExceptionHandler(ReviewResponseAlreadyExistsException.class)` and `@ExceptionHandler(ReviewFlagAlreadyExistsException.class)` — both map to 409.

- [ ] **Step 12: Update `SecurityConfig.java`**

```java
.requestMatchers(HttpMethod.POST, "/api/v1/reviews/*/respond").authenticated()
.requestMatchers(HttpMethod.POST, "/api/v1/reviews/*/flag").authenticated()
```

- [ ] **Step 13: Run all tests + commit**

```bash
./mvnw test
git add backend/src/main/java/com/slparcelauctions/backend/review/ \
        backend/src/test/java/com/slparcelauctions/backend/review/ \
        backend/src/main/java/com/slparcelauctions/backend/config/SecurityConfig.java
git commit -m "feat(review): respond + flag endpoints with one-per-user guards"
```

---

## Task 4 — Frontend: Primitives + hooks + API client

**Goal:** Build all review-related primitives, hooks, and API client so Task 5 and Task 6 can focus on page integration.

**Files:**

- Create: `frontend/src/types/review.ts`
- Create: `frontend/src/lib/api/reviews.ts`
- Create: `frontend/src/hooks/useReviews.ts`
- Create: `frontend/src/components/reviews/RatingSummary.tsx`
- Create: `frontend/src/components/reviews/StarSelector.tsx`
- Create: `frontend/src/components/reviews/ReviewCard.tsx`
- Create: `frontend/src/components/reviews/FlagModal.tsx`
- Create: `frontend/src/components/reviews/RespondModal.tsx`
- Modify: `frontend/src/components/user/ReputationStars.tsx` — thin wrapper around RatingSummary
- Tests for each

### Steps (abbreviated — TDD pattern repeats)

- [ ] **Step 1: `types/review.ts`**

```typescript
export type ReviewedRole = "SELLER" | "BUYER";

export type ReviewFlagReason =
  | "SPAM"
  | "ABUSIVE"
  | "OFF_TOPIC"
  | "FALSE_INFO"
  | "OTHER";

export interface ReviewResponseDto {
  id: number;
  text: string;
  createdAt: string;
}

export interface ReviewDto {
  id: number;
  auctionId: number;
  auctionTitle: string;
  auctionPrimaryPhotoUrl: string | null;
  reviewerId: number;
  reviewerDisplayName: string;
  reviewerAvatarUrl: string | null;
  revieweeId: number;
  reviewedRole: ReviewedRole;
  rating: number | null;
  text: string | null;
  visible: boolean;
  pending: boolean;
  submittedAt: string | null;
  revealedAt: string | null;
  response: ReviewResponseDto | null;
}

export interface AuctionReviewsResponse {
  reviews: ReviewDto[];
  myPendingReview: ReviewDto | null;
  canReview: boolean;
  windowClosesAt: string | null;
}

export interface PendingReviewDto {
  auctionId: number;
  title: string;
  primaryPhotoUrl: string | null;
  counterpartyId: number;
  counterpartyDisplayName: string;
  counterpartyAvatarUrl: string | null;
  escrowCompletedAt: string;
  windowClosesAt: string;
  hoursRemaining: number;
  viewerRole: ReviewedRole;
}

export interface ReviewSubmitRequest {
  rating: number;
  text?: string;
}

export interface ReviewResponseSubmitRequest {
  text: string;
}

export interface ReviewFlagRequest {
  reason: ReviewFlagReason;
  elaboration?: string;
}
```

- [ ] **Step 2: `lib/api/reviews.ts`**

```typescript
import { apiFetch } from "@/lib/api";
import type {
  AuctionReviewsResponse,
  PendingReviewDto,
  ReviewDto,
  ReviewFlagRequest,
  ReviewResponseDto,
  ReviewResponseSubmitRequest,
  ReviewSubmitRequest,
} from "@/types/review";
import type { PagedResponse } from "@/types/page";

export const getAuctionReviews = (auctionId: number) =>
  apiFetch<AuctionReviewsResponse>(`/api/v1/auctions/${auctionId}/reviews`);

export const submitReview = (auctionId: number, body: ReviewSubmitRequest) =>
  apiFetch<ReviewDto>(`/api/v1/auctions/${auctionId}/reviews`, {
    method: "POST",
    body: JSON.stringify(body),
  });

export const getUserReviews = (
  userId: number,
  role: "SELLER" | "BUYER",
  page = 0,
  size = 10,
) =>
  apiFetch<PagedResponse<ReviewDto>>(
    `/api/v1/users/${userId}/reviews?role=${role}&page=${page}&size=${size}`,
  );

export const getPendingReviews = () =>
  apiFetch<PendingReviewDto[]>("/api/v1/users/me/pending-reviews");

export const respondToReview = (
  reviewId: number,
  body: ReviewResponseSubmitRequest,
) =>
  apiFetch<ReviewResponseDto>(`/api/v1/reviews/${reviewId}/respond`, {
    method: "POST",
    body: JSON.stringify(body),
  });

export const flagReview = (reviewId: number, body: ReviewFlagRequest) =>
  apiFetch<void>(`/api/v1/reviews/${reviewId}/flag`, {
    method: "POST",
    body: JSON.stringify(body),
  });
```

- [ ] **Step 3: `hooks/useReviews.ts`**

```typescript
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { toast } from "@/components/ui/Toast";
import {
  flagReview,
  getAuctionReviews,
  getPendingReviews,
  getUserReviews,
  respondToReview,
  submitReview,
} from "@/lib/api/reviews";
import type {
  ReviewSubmitRequest,
  ReviewFlagRequest,
  ReviewResponseSubmitRequest,
} from "@/types/review";

export const useAuctionReviews = (auctionId: number) =>
  useQuery({
    queryKey: ["reviews", "auction", auctionId],
    queryFn: () => getAuctionReviews(auctionId),
    enabled: Number.isFinite(auctionId) && auctionId > 0,
  });

export const useUserReviews = (
  userId: number,
  role: "SELLER" | "BUYER",
  page: number,
) =>
  useQuery({
    queryKey: ["reviews", "user", userId, role, page],
    queryFn: () => getUserReviews(userId, role, page),
  });

export const usePendingReviews = () =>
  useQuery({
    queryKey: ["reviews", "pending"],
    queryFn: getPendingReviews,
  });

export const useSubmitReview = (auctionId: number) => {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: ReviewSubmitRequest) => submitReview(auctionId, body),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["reviews", "auction", auctionId] });
      qc.invalidateQueries({ queryKey: ["reviews", "pending"] });
      toast.success({
        title: "Review submitted",
        description:
          "Your review will appear when the other party submits theirs or when the 14-day window closes.",
      });
    },
    onError: () =>
      toast.error({
        title: "Could not submit review",
        description: "Please try again.",
      }),
  });
};

export const useRespondToReview = (reviewId: number) => {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: ReviewResponseSubmitRequest) => respondToReview(reviewId, body),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["reviews"] });
      toast.success({ title: "Response posted" });
    },
    onError: () =>
      toast.error({ title: "Could not post response", description: "Please try again." }),
  });
};

export const useFlagReview = (reviewId: number) => {
  return useMutation({
    mutationFn: (body: ReviewFlagRequest) => flagReview(reviewId, body),
    onSuccess: () =>
      toast.success({
        title: "Review flagged",
        description: "Thanks — our team will review it shortly.",
      }),
    onError: (err) => {
      const already = /already flagged|409/i.test(String(err));
      toast.error({
        title: already ? "Already flagged" : "Could not flag review",
        description: already
          ? "You've already flagged this review."
          : "Please try again.",
      });
    },
  });
};
```

- [ ] **Step 4: `RatingSummary.tsx` with partial-star SVG**

```tsx
type Size = "sm" | "md" | "lg";

const SIZE_MAP: Record<Size, { star: string; text: string; count: string }> = {
  sm: { star: "size-3.5", text: "text-label-md", count: "text-label-sm" },
  md: { star: "size-4", text: "text-title-md font-bold", count: "text-body-sm" },
  lg: { star: "size-5", text: "text-title-lg font-bold", count: "text-body-md" },
};

interface Props {
  rating: number | null;
  reviewCount: number;
  size?: Size;
  hideCountText?: boolean;
}

function PartialStar({ fillRatio, className }: { fillRatio: number; className: string }) {
  const pct = Math.round(fillRatio * 100);
  const id = `star-${pct}-${Math.random().toString(36).slice(2, 8)}`;
  return (
    <svg viewBox="0 0 20 20" aria-hidden="true" className={className}>
      <defs>
        <linearGradient id={id}>
          <stop offset={`${pct}%`} className="[stop-color:var(--color-primary)]" />
          <stop offset={`${pct}%`} className="[stop-color:var(--color-surface-variant)]" />
        </linearGradient>
      </defs>
      <path
        d="M10 1l2.78 5.64 6.22.9-4.5 4.39 1.06 6.2L10 15.27l-5.56 2.86 1.06-6.2L1 7.54l6.22-.9L10 1z"
        fill={`url(#${id})`}
        stroke="currentColor"
        strokeWidth="0.5"
        className="text-primary"
      />
    </svg>
  );
}

export function RatingSummary({ rating, reviewCount, size = "md", hideCountText }: Props) {
  const sz = SIZE_MAP[size];
  if (rating === null || reviewCount === 0) {
    return (
      <span className={`${sz.count} text-on-surface-variant`}>No ratings yet</span>
    );
  }
  const numeric = Number(rating);
  return (
    <div className="flex items-center gap-2" role="img"
         aria-label={`${numeric.toFixed(1)} out of 5 stars, ${reviewCount} review${reviewCount === 1 ? "" : "s"}`}>
      <div className="flex gap-0.5">
        {[0, 1, 2, 3, 4].map((i) => (
          <PartialStar
            key={i}
            fillRatio={Math.max(0, Math.min(1, numeric - i))}
            className={sz.star}
          />
        ))}
      </div>
      <span className={sz.text}>{numeric.toFixed(1)}</span>
      {!hideCountText && (
        <span className={`${sz.count} text-on-surface-variant`}>
          ({reviewCount} review{reviewCount === 1 ? "" : "s"})
        </span>
      )}
    </div>
  );
}
```

- [ ] **Step 5: Update `ReputationStars.tsx` to thin wrapper**

```tsx
import { RatingSummary } from "@/components/reviews/RatingSummary";

interface Props {
  rating: number | null;
  reviewCount: number;
  label?: string;
}

export function ReputationStars({ rating, reviewCount, label }: Props) {
  // BigDecimal comes as string in JSON — normalize at the edge. If called
  // with the raw number, passes through.
  const normalized =
    typeof rating === "string" ? Number.parseFloat(rating) : rating;
  return (
    <div className="flex flex-col gap-1">
      {label && (
        <span className="text-label-sm font-bold uppercase tracking-widest text-on-surface-variant">
          {label}
        </span>
      )}
      <RatingSummary rating={normalized} reviewCount={reviewCount} size="md" />
    </div>
  );
}
```

- [ ] **Step 6: `StarSelector.tsx` (interactive 1-5)**

Per spec §8.7. Keyboard + ARIA. Test hover preview, click, keyboard nav.

- [ ] **Step 7: `ReviewCard.tsx`**

Per spec §8.2. Renders reviewer info, stars (fixed integer-only rating, so use `RatingSummary` with count hidden), text with `white-space: pre-wrap`, response (nested), flag + respond buttons conditionally, auction link.

- [ ] **Step 8: `FlagModal.tsx`**

Headless UI Dialog. Reason radio group, elaboration textarea (required on OTHER), submit, 409 handling.

- [ ] **Step 9: `RespondModal.tsx`**

Textarea, 500-char counter, submit, close-on-success.

- [ ] **Step 10: Write tests for each primitive**

All components: dark + light mode assertions (per project hard rule), keyboard a11y, RTL happy-path interactions.

- [ ] **Step 11: Run all frontend tests + commit**

```bash
cd frontend && npm test -- --run
git add frontend/src/types/review.ts \
        frontend/src/lib/api/reviews.ts \
        frontend/src/hooks/useReviews.ts \
        frontend/src/components/reviews/ \
        frontend/src/components/user/ReputationStars.tsx
git commit -m "feat(reviews): primitives + hooks + API client + partial-star rendering"
```

---

## Task 5 — Frontend: ReviewPanel + escrow page wiring

**Goal:** Integrate the review surface on the escrow page. Client subscribes to `REVIEW_REVEALED` envelopes.

**Files:**

- Create: `frontend/src/components/reviews/ReviewPanel.tsx` + test
- Modify: `frontend/src/types/auction.ts` — extend `AuctionTopicEnvelope` union
- Modify: `frontend/src/app/auction/[id]/escrow/EscrowPageClient.tsx`
- Test: `frontend/src/app/auction/[id]/escrow/EscrowPageClient.test.tsx`

### Steps

- [ ] **Step 1: Extend `AuctionTopicEnvelope` union**

```typescript
// types/auction.ts
export type ReviewRevealedEnvelope = {
  type: "REVIEW_REVEALED";
  auctionId: number;
  reviewId: number;
  reviewerId: number;
  revieweeId: number;
  reviewedRole: "SELLER" | "BUYER";
  revealedAt: string;
};

export type AuctionTopicEnvelope =
  | AuctionEnvelope
  | EscrowEnvelope
  | ReviewRevealedEnvelope;
```

- [ ] **Step 2: Create `ReviewPanel.tsx` with 5 states per spec §8.1**

Key responsibilities:
- Fetch via `useAuctionReviews(auctionId)`
- Derive state from `{ canReview, myPendingReview, reviews, windowClosesAt }`
- Render matching branch (submit-form, pending, revealed-both, revealed-one, window-closed-none)
- Submit form uses `useSubmitReview(auctionId)` mutation
- Host `<RespondModal>` / `<FlagModal>` via portal
- Has `id="review-panel"` for dashboard hash-scroll

State derivation helper:

```tsx
function deriveState(
  auctionReviews: AuctionReviewsResponse | undefined,
  isPartyAuthed: boolean,
  now: Date,
): PanelState {
  if (!auctionReviews) return "loading";
  const { canReview, myPendingReview, reviews, windowClosesAt } = auctionReviews;
  const windowClosed =
    windowClosesAt !== null && new Date(windowClosesAt) < now;
  if (canReview) return "submit";
  if (myPendingReview) return "pending";
  if (reviews.length >= 2) return "revealed-both";
  if (reviews.length === 1) return "revealed-one";
  if (isPartyAuthed && windowClosed) return "window-closed-none";
  return "read-only"; // anonymous viewer, no reviews yet
}
```

- [ ] **Step 3: Wire `ReviewPanel` into `EscrowPageClient`**

```tsx
{escrow && escrow.state === "COMPLETED" && (
  <ReviewPanel auctionId={auctionId} role={role} />
)}
```

- [ ] **Step 4: Extend WS handler**

```tsx
(env) => {
  if (env.type.startsWith("ESCROW_")) {
    queryClient.invalidateQueries({ queryKey: escrowKey(auctionId) });
  } else if (env.type === "REVIEW_REVEALED") {
    queryClient.invalidateQueries({
      queryKey: ["reviews", "auction", auctionId],
    });
  }
}
```

- [ ] **Step 5: Write tests**

```tsx
it("renders submit state when canReview=true, no pending review", ...);
it("renders pending state when myPendingReview is set", ...);
it("renders revealed-both when 2 visible reviews", ...);
it("renders revealed-one when 1 visible review", ...);
it("renders window-closed-none when party and past windowClosesAt", ...);
it("invalidates reviews query on REVIEW_REVEALED envelope", ...);
it("scrolls into view when URL hash #review-panel is set", ...);
```

- [ ] **Step 6: Commit**

```bash
git add frontend/src/components/reviews/ReviewPanel.tsx \
        frontend/src/components/reviews/ReviewPanel.test.tsx \
        frontend/src/types/auction.ts \
        frontend/src/app/auction/[id]/escrow/EscrowPageClient.tsx \
        frontend/src/app/auction/[id]/escrow/EscrowPageClient.test.tsx
git commit -m "feat(reviews): ReviewPanel on escrow page + REVIEW_REVEALED WS invalidation"
```

---

## Task 6 — Frontend: Profile tabs + dashboard pending section + deferred ledger cleanup

**Goal:** Two remaining public surfaces. Tabbed profile reviews section, dashboard pending-reviews card.

**Files:**

- Create: `frontend/src/components/reviews/ProfileReviewTabs.tsx` + test
- Create: `frontend/src/components/reviews/ReviewList.tsx` + test
- Create: `frontend/src/components/reviews/PendingReviewsSection.tsx` + test
- Modify: `frontend/src/components/user/PublicProfileView.tsx` — replace empty-state
- Modify: `frontend/src/app/dashboard/(verified)/overview/page.tsx` — append pending section
- Modify: `docs/implementation/DEFERRED_WORK.md` — remove resolved entries

### Steps

- [ ] **Step 1: `ReviewList.tsx`**

Paginated list. Takes `userId`, `role`, `page`. Renders a list of `<ReviewCard>`s. Pagination control below. Empty state per role.

- [ ] **Step 2: `ProfileReviewTabs.tsx`**

Tabs via Headless UI or existing Tab primitive. Default active = "As Seller". Tab state synced to URL `?tab=seller|buyer`. Each tab hosts its own `<ReviewList>` with independent page state.

- [ ] **Step 3: Replace empty-state in `PublicProfileView.tsx`**

Remove the "Placeholder: Reviews" `<Card>` with its `<EmptyState>` and replace:

```tsx
<Card>
  <Card.Header>
    <h2 className="text-title-md font-bold">Reviews</h2>
  </Card.Header>
  <Card.Body>
    <ProfileReviewTabs userId={profile.id} />
  </Card.Body>
</Card>
```

- [ ] **Step 4: `PendingReviewsSection.tsx`**

Per spec §8.4. Uses `usePendingReviews()`. Renders null when `items.length === 0`. Each row: photo, title, counterparty name, "Closes in X days", CTA link to `/auction/{id}/escrow#review-panel`.

- [ ] **Step 5: Wire into dashboard overview**

```tsx
// frontend/src/app/dashboard/(verified)/overview/page.tsx
<PendingReviewsSection />
{/* existing My Listings / My Bids sections */}
```

Place above (or below) existing sections — placement per design system heuristics.

- [ ] **Step 6: Remove deferred entries**

Edit `docs/implementation/DEFERRED_WORK.md` — delete the two entries:
- "Partial-star rendering for ReputationStars"
- "Recent reviews section on public profile"

- [ ] **Step 7: Write tests**

- `ReviewList.test.tsx` — pagination, empty state, newest-first sort assumed from API
- `ProfileReviewTabs.test.tsx` — tab switching, URL sync, independent pagination
- `PendingReviewsSection.test.tsx` — renders nothing when empty, hash link correct
- `PublicProfileView.test.tsx` — updated to not expect the old empty-state

- [ ] **Step 8: Run all tests + commit**

```bash
cd frontend && npm test -- --run
cd ..
git add frontend/src/components/reviews/ \
        frontend/src/components/user/PublicProfileView.tsx \
        frontend/src/components/user/PublicProfileView.test.tsx \
        frontend/src/app/dashboard/\(verified\)/overview/ \
        docs/implementation/DEFERRED_WORK.md
git commit -m "feat(reviews): profile tabs + dashboard pending + deferred ledger cleanup"
```

---

## Final steps — PR

- [ ] **All tests pass** — backend `./mvnw test`, frontend `npm test -- --run`, frontend `npm run build`, frontend `npm run lint`.
- [ ] **Cross-branch code review** via final code-reviewer subagent.
- [ ] **PR to `dev`** — title: "Epic 08 sub-spec 1 — Reviews & Reputation", body includes list of commits per task, resolved deferred items, test plan.

---

## Self-review (plan)

1. **Spec coverage:** Every spec section (§2-§14) has a task that implements it. Six tasks map cleanly to spec §13.
2. **Placeholder scan:** No TBD/TODO — all code snippets are concrete.
3. **Type consistency:**
   - `ReviewedRole` enum used consistently both sides (Java enum + TS union type `"SELLER" | "BUYER"`)
   - `completedSales` field added to `SellerSummaryDto` in Task 1, consumed on listing cards (no code change there — card already reads `seller.completedSales` via `NewSellerBadge`, which is at `frontend/src/components/user/NewSellerBadge.tsx` and takes `completedSales: number`).
   - `ReviewDto.rating` is `number | null` in TS (matches Java `Integer` which becomes `null` when not exposed per the privacy rule in `ReviewDto.of`).
4. **Idempotency pins:** `reveal()` checks `visible === true` and returns early. `BlindReviewRevealTask` is safe to re-run. `flag` and `respond` have unique-constraint backed 409s.
5. **Race pins:** simultaneous-submit test (Task 2 Step 3) asserts both-visible outcome. `findByIdForUpdate` used on the review row inside `reveal()` to serialise against reveal-task-vs-submit race.
