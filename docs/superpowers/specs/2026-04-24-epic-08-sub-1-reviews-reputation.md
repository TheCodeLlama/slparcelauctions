# Epic 08 Sub-Spec 1 — Reviews & Reputation

**Date:** 2026-04-24
**Epic:** 08 — Ratings & Reputation
**Sub-spec scope:** Tasks 01 + 02 + 04 from `docs/implementation/epic-08/` (review service + reputation aggregation + reviews UI). Cancellation penalties and 48h post-cancel ownership watcher are deferred to sub-spec 2.
**Branch:** `task/08-sub-1-reviews-reputation` → `dev`

---

## 1. Goal

Ship the reviews-and-reputation layer end-to-end: blind rating backend, aggregate pipeline, and every public UI surface that shows review data (escrow panel, user profile tabs, dashboard pending-reviews card, listing cards, auction detail seller card, flag / response flows, partial-star rendering).

Scope excludes: cancellation penalties, suspension enforcement, off-platform-sale fraud flags (all Epic 08 sub-spec 2).

---

## 2. Architecture

### 2.1 Backend — one new package

```
backend/src/main/java/com/slparcelauctions/backend/review/
├── Review.java                   (@Entity)
├── ReviewResponse.java           (@Entity)
├── ReviewFlag.java               (@Entity)
├── ReviewedRole.java             (enum: SELLER, BUYER)
├── ReviewFlagReason.java         (enum: SPAM, ABUSIVE, OFF_TOPIC, FALSE_INFO, OTHER)
├── ReviewRepository.java
├── ReviewResponseRepository.java
├── ReviewFlagRepository.java
├── ReviewService.java            (submit, reveal, recompute-aggregates)
├── ReviewController.java         (4 endpoints: submit, list-for-auction, respond, flag)
├── UserReviewsController.java    (2 endpoints: list-for-user, pending-for-me)
├── BlindReviewRevealTask.java    (hourly scheduler)
├── ReviewRevealedEnvelope.java   (WS broadcast — added to AuctionTopicEnvelope union)
├── dto/
│   ├── ReviewDto.java
│   ├── ReviewResponseDto.java
│   ├── ReviewSubmitRequest.java
│   ├── ReviewResponseSubmitRequest.java
│   ├── ReviewFlagRequest.java
│   ├── PendingReviewDto.java
│   └── ReviewListPage.java       (PagedResponse<ReviewDto>)
└── exception/
    ├── ReviewNotFoundException.java
    ├── ReviewIneligibleException.java   (→ 422)
    ├── ReviewAlreadySubmittedException.java  (→ 409)
    ├── ReviewWindowClosedException.java (→ 422)
    ├── ReviewResponseAlreadyExistsException.java  (→ 409)
    ├── ReviewFlagAlreadyExistsException.java  (→ 409)
    └── ReviewExceptionHandler.java
```

No changes to Flyway. New entities land via JPA `ddl-auto: update`.

### 2.2 Frontend — one new component package

```
frontend/src/components/reviews/
├── ReviewPanel.tsx               (escrow page inline panel, 5 states)
├── ReviewCard.tsx                (single review display, used everywhere)
├── ReviewList.tsx                (paginated list for profile tab)
├── RatingSummary.tsx             (partial-star + numeric + count)
├── StarSelector.tsx              (interactive 1-5 picker, used in ReviewPanel)
├── RespondModal.tsx              (reviewee responds once)
├── FlagModal.tsx                 (reason enum + optional textarea)
├── PendingReviewsSection.tsx     (dashboard card)
└── ProfileReviewTabs.tsx         (seller / buyer tab container)

frontend/src/hooks/
└── useReviews.ts                 (React Query: 6 hooks)

frontend/src/lib/api/
└── reviews.ts                    (6 API functions)

frontend/src/types/
└── review.ts                     (public DTOs mirroring backend)
```

Modified surfaces:
- `components/user/ReputationStars.tsx` — upgrade numeric-only → partial-star SVG
- `components/user/PublicProfileView.tsx` — replace "No reviews yet" empty-state with `<ProfileReviewTabs>`
- `app/auction/[id]/escrow/EscrowPageClient.tsx` — append `<ReviewPanel>` when `escrow.state === "COMPLETED"`
- `app/dashboard/(verified)/overview/page.tsx` (or equivalent) — append `<PendingReviewsSection>`
- `types/auction.ts` — extend `AuctionTopicEnvelope` union with `REVIEW_REVEALED`

Unchanged:
- `components/auction/ListingCard.tsx` — already reads `seller.averageRating` / `seller.reviewCount` from `SellerSummaryDto`; new partial-star rendering will land via updates to the shared `ReputationStars` / `RatingSummary` primitives if they are used, or via a compact inline reading if not.
- `components/user/NewSellerBadge.tsx` — already correct at `completedSales < 3`.
- `components/auction/SellerProfileCard.tsx` — already renders the seller card on auction detail.

### 2.3 Integration envelope

```
Escrow COMPLETED ──┬──→ handleEscrowPayoutSuccess (existing)
                   │      └─→ NEW: user.completedSales += 1 for seller
                   │
                   └──→ Review eligibility window opens for seller + winner
                              │
                              ↓
                   [both parties submit OR day 14 passes]
                              │
                              ↓
                   ReviewService.reveal()
                   ├─→ Review.visible = true
                   ├─→ Full recompute reviewee aggregates (AVG + COUNT queries)
                   └─→ ReviewRevealedEnvelope on /topic/auction/{id}
                              │
                              ↓
                   Clients invalidate review query → refetch → "magic moment" UI update
```

---

## 3. Data model

### 3.1 New entity — `Review`

```java
@Entity
@Table(name = "reviews",
       uniqueConstraints = @UniqueConstraint(columnNames = {"auction_id", "reviewer_id"}),
       indexes = {
         @Index(name = "idx_reviews_reviewee_visible",
                columnList = "reviewee_id, reviewed_role, visible"),
         @Index(name = "idx_reviews_auction", columnList = "auction_id")
       })
public class Review {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) Long id;

    @ManyToOne(fetch = LAZY, optional = false)
    @JoinColumn(name = "auction_id", nullable = false) Auction auction;

    @ManyToOne(fetch = LAZY, optional = false)
    @JoinColumn(name = "reviewer_id", nullable = false) User reviewer;

    @ManyToOne(fetch = LAZY, optional = false)
    @JoinColumn(name = "reviewee_id", nullable = false) User reviewee;

    @Enumerated(EnumType.STRING)
    @Column(name = "reviewed_role", nullable = false, length = 10)
    ReviewedRole reviewedRole;   // SELLER = review is about reviewee's seller-side behavior; BUYER = buyer-side

    @Column(nullable = false) Integer rating;   // 1..5 (bean validator enforced at DTO level, not entity)

    @Column(length = 500) String text;          // nullable — text is optional

    @Builder.Default @Column(nullable = false) Boolean visible = false;

    @CreationTimestamp @Column(name = "submitted_at", nullable = false, updatable = false)
    OffsetDateTime submittedAt;

    @Column(name = "revealed_at") OffsetDateTime revealedAt;  // null until visible flips true

    @Builder.Default @Column(name = "flag_count", nullable = false) Integer flagCount = 0;
}
```

`reviewedRole` is persisted (not derived) so aggregate queries use an index-only scan. For a seller→buyer review, `reviewedRole=BUYER`; on reveal, the reviewee's `avgBuyerRating` + `totalBuyerReviews` recompute.

### 3.2 New entity — `ReviewResponse`

```java
@Entity
@Table(name = "review_responses")
public class ReviewResponse {
    @Id @GeneratedValue Long id;

    @OneToOne(fetch = LAZY, optional = false)
    @JoinColumn(name = "review_id", nullable = false, unique = true)
    Review review;                  // unique FK = one response per review

    @Column(nullable = false, length = 500) String text;     // non-empty, max 500

    @CreationTimestamp @Column(name = "created_at", nullable = false, updatable = false)
    OffsetDateTime createdAt;
}
```

### 3.3 New entity — `ReviewFlag`

```java
@Entity
@Table(name = "review_flags",
       uniqueConstraints = @UniqueConstraint(columnNames = {"review_id", "flagger_id"}))
public class ReviewFlag {
    @Id @GeneratedValue Long id;

    @ManyToOne(fetch = LAZY, optional = false)
    @JoinColumn(name = "review_id", nullable = false) Review review;

    @ManyToOne(fetch = LAZY, optional = false)
    @JoinColumn(name = "flagger_id", nullable = false) User flagger;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20) ReviewFlagReason reason;

    @Column(length = 500) String elaboration;   // required when reason=OTHER (validator)

    @CreationTimestamp @Column(name = "created_at", nullable = false, updatable = false)
    OffsetDateTime createdAt;
}
```

### 3.4 Modified entity — `User`

**One new column:**

```java
@Builder.Default
@Column(name = "escrow_expired_unfulfilled", nullable = false)
private Integer escrowExpiredUnfulfilled = 0;
```

**Existing columns — new or changed write paths:**

| Column | Current state | Sub-spec 1 write path |
|---|---|---|
| `completedSales` | Declared, never incremented | **NEW**: incremented in `TerminalCommandService.handleEscrowPayoutSuccess` inside the same transaction that flips escrow → COMPLETED. Incremented for the **seller** only (spec §4 confirms it's tracked per Epic). |
| `escrowExpiredUnfulfilled` | Does not exist today | **NEW**: incremented only in `EscrowService.expireTransfer` (seller failed to transfer). Not touched by `expirePayment` (buyer failure). |
| `avgSellerRating` | Declared, never written | **NEW**: full recompute on reveal transition for seller-role reviewees. |
| `avgBuyerRating` | Declared, never written | **NEW**: full recompute on reveal transition for buyer-role reviewees. |
| `totalSellerReviews` | Declared, never written | **NEW**: full recompute on reveal. Counts only visible reviews. |
| `totalBuyerReviews` | Declared, never written | **NEW**: full recompute on reveal. Counts only visible reviews. |

### 3.5 `SellerCompletionRateMapper` — extended signature

```java
// OLD: compute(completedSales, cancelledWithBids)
// NEW:
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

All callers updated. Known call sites (from grep):
- `AuctionDtoMapper:208`
- Any DTO that embeds `SellerSummaryDto` and populates a completion rate (search response path flows through `AuctionSearchResultMapper`).

---

## 4. API surface

All endpoints versioned under `/api/v1`. Auth column: `P` = public/no auth, `J` = JWT required.

| Method | Path | Auth | Purpose |
|---|---|---|---|
| `POST` | `/auctions/{id}/reviews` | J | Submit a review for an auction |
| `GET` | `/auctions/{id}/reviews` | P* | List visible reviews for an auction (+ own pending if party) |
| `POST` | `/reviews/{id}/respond` | J | One-time response from reviewee |
| `POST` | `/reviews/{id}/flag` | J | Flag a review (non-author, once per user per review) |
| `GET` | `/users/{id}/reviews` | P | Paginated list for profile tab (`?role=SELLER|BUYER&page&size`) |
| `GET` | `/users/me/pending-reviews` | J | Reviews the viewer still needs to submit |

`P*` = endpoint is public, but if the caller is authenticated **and** is the seller or winner of the auction, the response also includes their own submitted-but-not-yet-visible review with `visible=false, pending=true`. The counterparty sees no hint of submission (Q9 blind-on-fact decision).

### 4.1 `POST /auctions/{id}/reviews`

Request body:
```json
{ "rating": 5, "text": "Smooth transfer, recommended." }
```

Validations:
- `rating` ∈ [1, 5] (JSR-303 `@Min(1) @Max(5)`)
- `text` ≤ 500 chars, optional
- Caller is authenticated
- Caller is seller or winner of auction (derived from `auction.sellerId` and `escrow.winnerId`)
- Auction has an escrow with `state=COMPLETED`
- `escrow.completedAt + 14 days > now` (hard cutoff — returns 422 `ReviewWindowClosedException` otherwise)
- No existing `Review` with (auctionId, callerId) — returns 409 `ReviewAlreadySubmittedException` otherwise

Server derives:
- `reviewerId` = caller
- `revieweeId` = the other party (if caller is seller → reviewee is winner; if caller is winner → reviewee is seller)
- `reviewedRole` = the reviewee's role in this auction (if reviewee is seller → `SELLER`; if reviewee is winner → `BUYER`)

Response (201):
```json
{
  "id": 42,
  "auctionId": 1234,
  "rating": 5,
  "text": "Smooth transfer, recommended.",
  "reviewedRole": "SELLER",
  "visible": false,
  "submittedAt": "2026-04-24T10:15:00Z",
  "revealedAt": null,
  "pending": true
}
```

**Simultaneous-reveal path (inside submit transaction):** after inserting the new row, the service queries for the counterparty's review on the same auction. If present and `visible=false`, flip both to `visible=true`, stamp both `revealedAt=now`, recompute both reviewees' aggregates in the same transaction, and enqueue a `ReviewRevealedEnvelope` on `afterCommit` for WS broadcast. If not present, leave both rows in pending state.

### 4.2 `GET /auctions/{id}/reviews`

Response shape:
```json
{
  "reviews": [ /* ReviewDto — only visible=true */ ],
  "myPendingReview": { /* ReviewDto or null — viewer's own submitted-but-hidden review */ },
  "canReview": true,        // viewer is party, escrow COMPLETED, within window, no existing review
  "windowClosesAt": "2026-05-08T10:15:00Z"  // null if viewer is not a party or escrow not COMPLETED
}
```

Anonymous callers: `reviews` only, `myPendingReview=null`, `canReview=false`, `windowClosesAt=null`.

### 4.3 `POST /reviews/{id}/respond`

```json
{ "text": "Thanks for the kind words!" }
```

Validations:
- Caller is `review.revieweeId` (403 if not)
- No existing `ReviewResponse` for this review (409 `ReviewResponseAlreadyExistsException` otherwise)
- `text` non-empty, ≤ 500

Response (201): `ReviewResponseDto { id, text, createdAt }`.

### 4.4 `POST /reviews/{id}/flag`

```json
{ "reason": "SPAM", "elaboration": null }
```

Validations:
- Caller ≠ `review.reviewerId` (403 — can't flag own review)
- `reason` ∈ enum
- `elaboration` required when `reason=OTHER` (custom cross-field validator)
- No existing `ReviewFlag` with (reviewId, callerId) — 409 `ReviewFlagAlreadyExistsException`

Side effect: `review.flagCount += 1` (increment + save). No auto-hide.

Response (204 No Content).

### 4.5 `GET /users/{id}/reviews?role=SELLER|BUYER&page=0&size=10`

Paginated list of **visible** reviews where `revieweeId=id` and `reviewedRole=role`. Sorted `revealedAt DESC`.

Returns `PagedResponse<ReviewDto>`.

### 4.6 `GET /users/me/pending-reviews`

Scans completed escrows where the viewer is seller or winner, the 14-day window is still open, and the viewer has not yet submitted a review. Sorted by window-remaining ascending (most urgent first).

Response (200):
```json
{
  "items": [
    {
      "auctionId": 1234,
      "title": "Lakefront Parcel in Aurora",
      "primaryPhotoUrl": "...",
      "counterpartyId": 78,
      "counterpartyDisplayName": "Alice",
      "counterpartyAvatarUrl": "...",
      "escrowCompletedAt": "2026-04-20T10:15:00Z",
      "windowClosesAt": "2026-05-04T10:15:00Z",
      "hoursRemaining": 312,
      "viewerRole": "SELLER"
    }
  ]
}
```

### 4.7 `ReviewDto` shape

```java
public record ReviewDto(
    Long id,
    Long auctionId,
    String auctionTitle,                 // live-joined from Auction
    String auctionPrimaryPhotoUrl,       // nullable, for card preview
    Long reviewerId,
    String reviewerDisplayName,          // live-joined from User
    String reviewerAvatarUrl,            // live-joined; nullable
    Long revieweeId,
    ReviewedRole reviewedRole,
    Integer rating,
    String text,                         // nullable
    Boolean visible,
    Boolean pending,                     // true when the viewer is the reviewer AND visible=false
    OffsetDateTime submittedAt,          // only populated when pending=true OR visible=true
    OffsetDateTime revealedAt,           // only populated when visible=true
    ReviewResponseDto response           // nullable; the reviewee's one response if any
) { }
```

`flagCount` is NOT exposed publicly — admin-only (Epic 10 adds the admin DTO).

---

## 5. Blind-reveal state machine

### 5.1 State transitions

```
[no review]
    │
    │ party A submits
    ↓
[A submitted, pending]
    │
    ├── party B submits before day 14 ───→ REVEAL BOTH (synchronous, inside submit tx)
    │
    ├── day 14 passes, B never submits ──→ REVEAL A ONLY (scheduler)
    │                                       ∧ submission endpoint returns 422 for late attempts
    │
    └── both submit simultaneously ──────→ REVEAL BOTH (the second commit wins the lock; the first just inserted)
```

### 5.2 Scheduler — `BlindReviewRevealTask`

- Spring `@Scheduled(cron = "0 0 * * * *")` — top of every hour.
- Query:
  ```sql
  SELECT r.id FROM reviews r
  JOIN auctions a ON a.id = r.auction_id
  JOIN escrows e ON e.auction_id = a.id
  WHERE r.visible = false
    AND e.state = 'COMPLETED'
    AND e.completed_at + INTERVAL '14 days' < :now
  ORDER BY r.id
  LIMIT 500
  ```
- For each id: call `ReviewService.reveal(reviewId)` in its own transaction.
- Idempotent: `reveal()` is a no-op if `visible=true` already. Safe to re-run after a partial failure.
- Per-auction lock through `AuctionRepository.findByIdForUpdate(auctionId)` inside `reveal()` to serialise against a simultaneous submit-and-reveal race.
- Budget check: scans for reviews > 14d old + escrow COMPLETED + not visible. Expected volume: single-digit per hour in MVP. Hard ceiling `LIMIT 500` to prevent runaway batches; logged if hit.

### 5.3 `ReviewService.reveal(reviewId)`

```java
@Transactional
public void reveal(Long reviewId) {
    Review r = reviewRepo.findByIdForUpdate(reviewId).orElseThrow();
    if (Boolean.TRUE.equals(r.getVisible())) return;  // idempotent

    r.setVisible(true);
    r.setRevealedAt(clock.instant().atOffset(UTC));
    reviewRepo.save(r);

    recomputeAggregates(r.getReviewee(), r.getReviewedRole());

    final ReviewRevealedEnvelope env = ReviewRevealedEnvelope.of(r);
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
            @Override public void afterCommit() { broadcastPublisher.publishReviewRevealed(env); }
        });
}
```

### 5.4 `ReviewService.recomputeAggregates(User reviewee, ReviewedRole role)`

```java
private void recomputeAggregates(User reviewee, ReviewedRole role) {
    if (role == ReviewedRole.SELLER) {
        var agg = reviewRepo.computeSellerAggregate(reviewee.getId());
        reviewee.setAvgSellerRating(agg.avg());           // null if count=0
        reviewee.setTotalSellerReviews(agg.count());
    } else {
        var agg = reviewRepo.computeBuyerAggregate(reviewee.getId());
        reviewee.setAvgBuyerRating(agg.avg());
        reviewee.setTotalBuyerReviews(agg.count());
    }
    userRepo.save(reviewee);
}
```

`ReviewRepository` custom JPQL:
```java
@Query("select new com.slparcelauctions.backend.review.Aggregate(" +
       "  avg(r.rating), count(r)) " +
       "from Review r " +
       "where r.reviewee.id = :revieweeId " +
       "  and r.reviewedRole = 'SELLER' " +
       "  and r.visible = true")
Aggregate computeSellerAggregate(@Param("revieweeId") Long revieweeId);
```

(Analogous `computeBuyerAggregate`.)

`Aggregate` is a tiny Java record `(BigDecimal avg, int count)`; the JPQL returns `Double`/`Long` which we normalise.

### 5.5 Submit-path simultaneous reveal

Inside `ReviewService.submit`:

```java
@Transactional
public ReviewDto submit(Long auctionId, User caller, ReviewSubmitRequest req) {
    // ... eligibility checks + persist new review (visible=false) ...
    Review mine = reviewRepo.save(Review.builder()...build());

    // Check for counterparty's prior submission. If present, reveal both atomically.
    Optional<Review> theirs = reviewRepo.findByAuctionIdAndReviewerId(
            auctionId, counterpartyId);
    if (theirs.isPresent() && !theirs.get().getVisible()) {
        reveal(mine.getId());          // reveals mine + recomputes my reviewee's aggregates
        reveal(theirs.get().getId());  // reveals theirs + recomputes their reviewee's aggregates
    }
    return ReviewDto.pendingFor(caller, mine);
}
```

`reveal()` calls are nested into the same transaction; the `afterCommit` WS envelope fires once per revealed review.

---

## 6. Completion-rate pipeline

### 6.1 The three counters

| Counter | Incremented where | Notes |
|---|---|---|
| `completedSales` | `TerminalCommandService.handleEscrowPayoutSuccess` — inside the same tx that flips `escrow.state=COMPLETED` | Incremented on the **seller** only |
| `cancelledWithBids` | `CancellationService.cancel` — when `from == ACTIVE && hadBids` (already exists) | Sub-spec 1 does **not** change this path |
| `escrowExpiredUnfulfilled` | `EscrowService.expireTransfer` — inside the same tx that flips `escrow.state=EXPIRED` after a TRANSFER_PENDING timeout | Incremented on the **seller** only. `expirePayment` (buyer-fault) does NOT touch this counter. |

All three are atomic integer increments on the `User` row, guarded by the same `@Transactional` boundary that owns the triggering state transition.

### 6.2 Derivation at read time

`SellerCompletionRateMapper.compute()` is the sole derivation point. It takes three integers and returns `BigDecimal | null`. No column is stored on `User` for `completion_rate`. The three counters are the source of truth; the mapper is the lens.

### 6.3 Where completion rate surfaces

- `UserProfileResponse` (via `UserController.getPublicProfile`): add `completionRate` field, populate via mapper.
- `PublicAuctionResponse.sellerStats` (auction detail): already present, already wired through the mapper — update the mapper call to add the third argument.
- `SellerSummaryDto` (search/listing cards): NOT present today (only `averageRating` + `reviewCount`). We add an optional `completionRate` field to `SellerSummaryDto` but do NOT render it on listing cards for MVP (cards already have enough density); the summary card on the auction detail page and the profile are the surfaces that show it.

### 6.4 "New Seller" threshold

- Derived at read time as `completedSales < 3` — not stored as a flag.
- `UserProfileResponse.isNewSeller` (new field) exposes it.
- Listing cards already read `seller.completedSales` and render `<NewSellerBadge>` client-side, but `SellerSummaryDto` does NOT currently carry `completedSales`. We add it.

---

## 7. WebSocket integration

### 7.1 New envelope

`ReviewRevealedEnvelope` joins the `AuctionEnvelope | EscrowEnvelope` union (broadcast on `/topic/auction/{id}`):

```java
public record ReviewRevealedEnvelope(
    String type,          // always "REVIEW_REVEALED"
    Long auctionId,
    Long reviewId,
    Long reviewerId,
    Long revieweeId,
    ReviewedRole reviewedRole,
    OffsetDateTime revealedAt
) implements AuctionTopicEnvelope {
    public static ReviewRevealedEnvelope of(Review r) { ... }
}
```

Frontend type addition to `types/auction.ts`:

```typescript
type ReviewRevealedEnvelope = AuctionEnvelopeBase & {
  type: "REVIEW_REVEALED";
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

### 7.2 Client handling

`EscrowPageClient` subscribes to `/topic/auction/{id}` already. Extend the envelope handler:

```ts
if (env.type === "REVIEW_REVEALED") {
    queryClient.invalidateQueries({ queryKey: ["reviews", "auction", auctionId] });
}
```

No new topic, no new subscription. Cheapest possible integration.

### 7.3 No broadcast on submit (pending)

A bare submit (no counterparty review yet, not revealed) does NOT broadcast anything. Per Q9, we don't leak "the other party has submitted." The reviewer's own client refreshes its own review locally via the mutation's `onSuccess` — no WS needed.

---

## 8. Frontend surfaces

### 8.1 `ReviewPanel` on escrow page

Rendered in `EscrowPageClient` below `<EscrowStepCard>` when `escrow.state === "COMPLETED"`. One component, five internal states derived from props:

| State key | When | UI |
|---|---|---|
| `submit` | `canReview=true` and no `myPendingReview` | `<StarSelector>` + textarea (500-char counter) + "Submit review" button |
| `pending` | `myPendingReview` exists, `visible=false` | "Submitted — you'll see [counterparty]'s review when they submit theirs or on [windowClosesAt date]." Plus a read-only display of the viewer's own text + rating. |
| `revealed-both` | `reviews` array contains both seller-side and buyer-side reviews | Two `<ReviewCard>`s side-by-side (desktop) / stacked (mobile) with a "Respond" button on the viewer's own received review (if no response yet) |
| `revealed-one` | `reviews` array has one entry, `canReview=false`, `windowClosesAt` in the past | One `<ReviewCard>`; if viewer's side never submitted, show subtle "Your review window closed." |
| `window-closed-none` | `canReview=false`, `reviews` empty, `windowClosesAt` in the past | "The review window for this auction has closed." |

The panel uses `useAuctionReviews(auctionId)` (React Query). On `REVIEW_REVEALED` WS event, the query invalidates and refetches; the panel re-renders into the new state.

### 8.2 `ProfileReviewTabs` on profile page

Replaces the existing "No reviews yet" `<EmptyState>` in `PublicProfileView.tsx`. Tabs: "As Seller" (default active) / "As Buyer". Each tab has its own `ReviewList` with independent pagination state. URL-synced tab via a `?tab=seller|buyer` query param so deep-links work.

Inside each tab:
- `<RatingSummary>` at top (partial-star + "4.7" + "· 23 reviews")
- `<ReviewList>` — paginated (10/page), newest first, empty-state "No reviews as [seller|buyer] yet" when count = 0

`<ReviewCard>` shape:
- Reviewer avatar + display name + date (relative, "3 weeks ago", absolute in title attr)
- `<RatingStars>` — 5 stars, filled to the review's rating (no partial; it's an integer 1-5)
- Text (if present) — pre-wrapped (`white-space: pre-wrap`) so newlines render, everything else escaped
- Link to auction ("Aurora Parcel · L$ 12,345") using `auctionTitle` + numeric bid display, `href="/auction/{auctionId}"`
- Reviewee's response (if present) — visually nested / indented, labeled "Seller response" or "Buyer response" based on reviewedRole
- Small "Flag" button → opens `<FlagModal>`
- On the viewer's own-profile reviews (`session.user.id === revieweeId`), a "Respond" button when no response exists → opens `<RespondModal>`

### 8.3 Partial-star rendering (`<RatingSummary>`)

Current `ReputationStars` renders one filled star + numeric. Upgrade to five SVG stars, filled proportionally to the rating value. Ratings ≤ 0 / null render "No ratings yet". Ratings > 0 render the partial-star row.

Implementation sketch:

```tsx
function Star({ fillRatio }: { fillRatio: number }) {
  // fillRatio in [0, 1]; uses a <linearGradient> with two stops
  // at fillRatio * 100% to produce left-to-right partial fills.
}

function RatingSummary({ rating, reviewCount, size = "md" }: ...) {
  if (rating === null || reviewCount === 0) {
    return <span className="text-body-sm text-on-surface-variant">No ratings yet</span>;
  }
  return (
    <div className="flex items-center gap-2">
      <div className="flex">
        {[0, 1, 2, 3, 4].map(i =>
          <Star key={i} fillRatio={Math.max(0, Math.min(1, rating - i))} />)}
      </div>
      <span className="text-title-md font-bold">{rating.toFixed(1)}</span>
      <span className="text-body-sm text-on-surface-variant">
        ({reviewCount} review{reviewCount === 1 ? "" : "s"})
      </span>
    </div>
  );
}
```

`ReputationStars` is retained for backwards compatibility during migration but becomes a thin wrapper around `RatingSummary` with identical props.

### 8.4 `PendingReviewsSection` on dashboard

Lives in the verified-dashboard overview. Renders only if `pendingReviews.length > 0`. Shape:

```
┌──────────────────────────────────────────────┐
│ Pending reviews                              │
│ ─────────────────────────────────────────── │
│ [photo] Aurora Parcel · Seller              │
│         Leave a review for Alice             │
│         Closes in 3 days                     │
│         [Leave a review →]  ← /auction/1234/escrow#review-panel
│                                              │
│ [photo] Lakeview Shore · Buyer              │
│         Leave a review for Bob               │
│         Closes in 12 days                    │
│         [Leave a review →]                   │
└──────────────────────────────────────────────┘
```

CTA links jump to the escrow page with `#review-panel` hash; `ReviewPanel` is given an `id="review-panel"` so the browser's native scroll-into-view handles the jump. Dashboard query: `useQuery(["pending-reviews"], fetchPendingReviews)`. Invalidated when any review is submitted.

### 8.5 `FlagModal`

Opens from any `<ReviewCard>`'s flag button. Headless UI Dialog. Form:
- Radio group with 5 options: Spam, Abusive, Off-topic, False information, Other
- Textarea (optional when reason ≠ OTHER, required when reason = OTHER, max 500 chars with live counter)
- Submit button → `POST /api/v1/reviews/{id}/flag`
- Success state: "Thanks — this review has been flagged for admin review." → auto-close after 2s
- Error state: duplicate-flag 409 → "You've already flagged this review."

### 8.6 `RespondModal`

Opens from any `<ReviewCard>` where `session.user.id === review.revieweeId` and no response exists. Headless UI Dialog. Form:
- Textarea (required, non-empty, max 500 chars, live counter)
- Submit button → `POST /api/v1/reviews/{id}/respond`
- Success state: modal closes; `<ReviewCard>` re-renders with the response nested below
- Error state: 409 (already responded) → refresh the card and close

### 8.7 `StarSelector`

Interactive 1-5 star picker used inside `ReviewPanel` submit state.
- Five star buttons, arrow-key and number-key (1-5) accessible, `role="radiogroup"` ARIA
- Hover preview fills stars left-to-right up to the hovered index
- Click sets the rating
- Keyboard: Home → 1, End → 5, ArrowLeft/Right decrement/increment with wraparound
- Clear affordance: selected rating stays visible after mouseleave

### 8.8 Listing-card / auction-detail seller card

No direct component changes. The existing `ListingCard` already reads `seller.averageRating` and `seller.reviewCount`. The only wire-up is the backend: ensure `SellerSummaryDto` (from Epic 07 search path) is populated using the freshly-maintained `User.avgSellerRating` / `User.totalSellerReviews` — which is automatic because the aggregate columns are the read source and sub-spec 1 is where those columns finally start being written.

---

## 9. Eligibility & edge cases

| Scenario | Behavior |
|---|---|
| Escrow state is not COMPLETED (PENDING / DISPUTED / EXPIRED / FROZEN) | `POST /auctions/{id}/reviews` → 422 `ReviewIneligibleException`. Panel shows nothing. |
| Escrow COMPLETED but viewer is anonymous | Panel shows read-only reviews (if any) but no form. |
| Escrow COMPLETED and viewer is seller/winner, already submitted | Panel shows pending state with their own text. No resubmit, no edit. |
| Escrow COMPLETED and viewer is seller/winner, window closed | Panel shows "review window has closed" + any revealed reviews. No form. |
| Escrow COMPLETED and viewer is a bidder who did NOT win | Panel shows revealed reviews only (they are not a party). No form. |
| Auction has no escrow row (e.g., ended-no-bids / reserve-not-met) | Panel shows nothing; auction never qualifies for review. |
| Both parties submit simultaneously (race) | The `findByIdForUpdate` lock on the escrow row inside submit serialises them. The second commit sees the first's row and triggers the simultaneous-reveal branch; the first's insert simply enters the pending state. |
| Seller reviews themselves (e.g., bought their own parcel via sock puppet) | Prevented by eligibility check — they'd have to be both seller and winner, but `revieweeId = other party ≠ self` is enforced. If sellerId == winnerId (shouldn't happen, Epic 04 guards against self-bid), submit is rejected. |
| Review flag by the author | 403. |
| Duplicate flag by same user | 409 `ReviewFlagAlreadyExistsException`. |
| Response submitted by non-reviewee | 403. |
| Duplicate response | 409 `ReviewResponseAlreadyExistsException`. |

---

## 10. Review text rendering

- **Storage:** UTF-8 string, ≤ 500 chars. No HTML parsing or sanitisation on write (defense in depth is at render).
- **Rendering:** plain text with `white-space: pre-wrap` so newlines render. No Markdown. No auto-linking. Rendered into a `<p>` or `<blockquote>`; React auto-escapes string children, so we cannot accidentally inject HTML.
- **Newline cap:** no hard cap, but the 500-char cap makes this a non-issue in practice.
- **Empty text:** text is optional on reviews (just the rating is enough); required on responses (no empty responses).

---

## 11. Security & authz

- All write endpoints require JWT. Read endpoints are public except `GET /users/me/pending-reviews`.
- Ownership validation is inside the service layer (not a filter), so it's visible in tests:
  - `submit`: caller ∈ {sellerId, winnerId}
  - `respond`: caller == review.revieweeId
  - `flag`: caller ≠ review.reviewerId
  - `pending-reviews`: scoped to caller via `@AuthenticationPrincipal`
- Rate-limit pre-existing config on bid-placement also covers review endpoints (Redis-backed). Sub-spec 1 reuses without new rules.
- Reviewee and reviewer identities exposed in DTOs — this is deliberate: reviews are a public trust signal, not pseudonymous.
- `flagCount` is NOT in `ReviewDto`. Sub-spec 1 adds a field but does not expose it via public endpoints; Epic 10's admin DTO will read it directly.

---

## 12. Testing strategy

### 12.1 Backend

| Layer | Coverage |
|---|---|
| Unit (service) | `ReviewService.submit` across all eligibility paths; simultaneous-reveal path; `reveal()` idempotency; aggregate recompute correctness; `BlindReviewRevealTask` scanning; `SellerCompletionRateMapper` three-arg boundary cases |
| Slice (`@WebMvcTest`) | All 6 endpoints: happy path, auth failures (401/403), validation failures (422), duplicates (409), idempotent reveal |
| Integration (`@SpringBootTest` + Testcontainers) | End-to-end: create auction → escrow COMPLETED → both submit → aggregates updated → WS envelope fires → day-14 scheduler idempotent |

Key test — **race**: two concurrent `submit` calls on the same auction (different reviewers). Assert exactly one reveal happens, both aggregates land, WS envelope fires twice (once per revealed review).

Key test — **formula B** coverage: build fixture users with representative counter mixes (`completed=10, cancelled=1, expired=0` → 91%; `completed=0, cancelled=0, expired=0` → null; etc.) and assert `SellerCompletionRateMapper.compute` output.

### 12.2 Frontend

| File | Coverage |
|---|---|
| `ReviewPanel.test.tsx` | All 5 states render correctly. State transitions on `canReview`, `myPendingReview`, `reviews` changes. WS-invalidation path: simulate envelope → assert query invalidated. |
| `ReviewCard.test.tsx` | Reviewer display, partial-star, response nesting, flag button gating (hidden for authors), respond button gating (shown only to reviewee on own profile). Dark-mode assertions. |
| `RatingSummary.test.tsx` | Partial-star fill ratios across rating values (0, 0.5, 3.7, 5.0). "No ratings yet" empty state. Dark-mode. |
| `StarSelector.test.tsx` | Click, hover, keyboard (arrows, Home/End, 1-5). ARIA radiogroup. |
| `FlagModal.test.tsx` | Reason radio group, elaboration required on OTHER, submit/cancel, 409 handling. |
| `RespondModal.test.tsx` | Non-empty validation, submit, 409 handling. |
| `PendingReviewsSection.test.tsx` | Renders only when items > 0. Item rendering. Link-hash behavior. |
| `ProfileReviewTabs.test.tsx` | Tab switching, independent pagination, URL-sync, default-active "As Seller". |
| `useReviews.test.tsx` | All React Query hooks: auction reviews, user reviews, pending reviews, submit mutation, respond mutation, flag mutation. Cache invalidation on mutation success. |

MSW 2 with `onUnhandledRequest: "error"` for all tests.

---

## 13. Task breakdown

Six tasks, executed sequentially via `superpowers:subagent-driven-development`.

**Task 1 — Backend: Review domain + submit endpoint**
- Entity trio (`Review`, `ReviewResponse`, `ReviewFlag`) + enums
- Repositories with custom aggregate JPQL
- `ReviewService.submit` with eligibility + simultaneous-reveal
- `POST /auctions/{id}/reviews` endpoint + exception handler + 201/422/409 mapping
- `User.escrowExpiredUnfulfilled` column added
- `SellerCompletionRateMapper` extended to 3-arg + all callers updated
- `TerminalCommandService.handleEscrowPayoutSuccess` — add `seller.completedSales++`
- `EscrowService.expireTransfer` — add `seller.escrowExpiredUnfulfilled++`
- Unit + slice tests for submit, eligibility, simultaneous-reveal, expireTransfer counter increment

**Task 2 — Backend: Reveal scheduler, aggregates, list endpoints**
- `BlindReviewRevealTask` hourly cron
- `ReviewService.reveal` + `recomputeAggregates`
- `ReviewRevealedEnvelope` + broadcast publisher wiring
- `GET /auctions/{id}/reviews` (public + own-pending for parties)
- `GET /users/{id}/reviews` (paginated)
- `GET /users/me/pending-reviews`
- Unit + slice + integration tests

**Task 3 — Backend: Response + flag endpoints**
- `ReviewResponse` + `ReviewResponseRepository` (already scaffolded in Task 1 entity; wire service path here)
- `POST /reviews/{id}/respond`
- `POST /reviews/{id}/flag` + `ReviewFlagReason` cross-field validator
- `review.flagCount++` on flag
- Exception handler additions
- Tests

**Task 4 — Frontend: Primitives + hooks + API client**
- `RatingSummary` (partial-star SVG) + `ReputationStars` becomes thin wrapper
- `StarSelector`
- `ReviewCard` (with reviewer avatar/name/date/stars/text/response/flag/auction-link)
- `RespondModal` + `FlagModal`
- `lib/api/reviews.ts` (6 functions)
- `hooks/useReviews.ts` (6 hooks with optimistic updates on flag/respond)
- `types/review.ts` (mirror backend DTOs)
- Component + hook tests with MSW 2

**Task 5 — Frontend: ReviewPanel + escrow page wiring**
- `ReviewPanel` with all 5 states
- Wire into `EscrowPageClient`: render when `escrow.state === "COMPLETED"`
- Extend `AuctionTopicEnvelope` union with `REVIEW_REVEALED`
- Escrow page client: handle `REVIEW_REVEALED` by invalidating `["reviews", "auction", auctionId]`
- Tests (state matrix, WS invalidation, hash-scroll anchor)

**Task 6 — Frontend: Profile tabs + dashboard pending section**
- `ProfileReviewTabs` replaces empty-state in `PublicProfileView`
- `ReviewList` with pagination + URL-sync tab param
- `PendingReviewsSection` + dashboard wiring
- Resolve DEFERRED_WORK: "Partial-star rendering for ReputationStars" + "Recent reviews section on public profile"
- Tests

Cross-cutting (rolled into whichever task naturally touches each):
- `types/auction.ts` envelope union extension (Task 5)
- `SellerSummaryDto.completedSales` field addition (Task 1 — minor)

---

## 14. DEFERRED_WORK ledger changes

**Resolve** (delete from ledger on sub-spec completion):
- "Partial-star rendering for ReputationStars"
- "Recent reviews section on public profile"

**Add** (if any surface isn't fully shipped):
- None expected. Sub-spec 1 covers the full reviews surface.

**Noted out-of-scope for sub-spec 1** (either pre-existing deferred entries or sub-spec 2 scope):
- Cancellation penalty ladder → Epic 08 sub-spec 2
- 48h post-cancel ownership watcher → Epic 08 sub-spec 2
- Admin fraud-flag review queue → Epic 10
- Notifications on review reveal / receive / respond → Epic 09
- `flagCount` public exposure (admin DTO) → Epic 10

---

## 15. Out of scope (explicit)

- Email or SL-IM notifications on any review event — Epic 09 owns.
- Distribution bar charts (5★/4★/3★/2★/1★ breakdown) — polish, not MVP.
- Rating decay / time-weighting — simple arithmetic mean for MVP.
- Reviewer-identity snapshotting (preserving display name at time of review) — live-join from current `User` row.
- Response editing or deletion — one-shot, no revisions.
- Review editing — one-shot, no revisions (per Epic spec).
- Self-review prevention beyond the implicit check (seller ≠ winner) — edge case not worth dedicated guarding.
- Moderation auto-hide on flag threshold — Epic 10.
- Admin review-management UI — Epic 10.

---

## 16. Open questions

None. All design questions resolved in brainstorm Q1–Q9.

---

## 17. File inventory

### Backend new (≈ 28 files)

```
review/
  Review.java
  ReviewResponse.java
  ReviewFlag.java
  ReviewedRole.java
  ReviewFlagReason.java
  ReviewRepository.java
  ReviewResponseRepository.java
  ReviewFlagRepository.java
  ReviewService.java
  ReviewController.java
  UserReviewsController.java
  BlindReviewRevealTask.java
  ReviewRevealedEnvelope.java
  Aggregate.java              (record for JPQL projection)
  dto/ReviewDto.java
  dto/ReviewResponseDto.java
  dto/ReviewSubmitRequest.java
  dto/ReviewResponseSubmitRequest.java
  dto/ReviewFlagRequest.java
  dto/ReviewListPage.java     (typed alias of PagedResponse)
  dto/PendingReviewDto.java
  dto/AuctionReviewsResponse.java  (for GET /auctions/{id}/reviews)
  exception/ReviewNotFoundException.java
  exception/ReviewIneligibleException.java
  exception/ReviewAlreadySubmittedException.java
  exception/ReviewWindowClosedException.java
  exception/ReviewResponseAlreadyExistsException.java
  exception/ReviewFlagAlreadyExistsException.java
  exception/ReviewExceptionHandler.java
  exception/ElaborationRequiredWhenOtherValidator.java  (JSR-303 custom)
  exception/ElaborationRequiredWhenOther.java           (annotation)
```

### Backend modified

- `user/User.java` — add `escrowExpiredUnfulfilled` column
- `user/SellerCompletionRateMapper.java` — 3-arg signature
- `user/dto/UserProfileResponse.java` — add `completionRate`, `isNewSeller`
- `auction/AuctionDtoMapper.java` — pass 3rd arg to mapper
- `auction/dto/PublicAuctionResponse.java` — (javadoc only)
- `auction/search/SellerSummaryDto.java` — add `completedSales` field
- `auction/search/AuctionSearchResultMapper.java` — populate new field
- `escrow/EscrowService.java` — `expireTransfer` increments seller's `escrowExpiredUnfulfilled`
- `escrow/command/TerminalCommandService.java` — `handleEscrowPayoutSuccess` increments seller's `completedSales`
- `escrow/broadcast/*` — add `publishReviewRevealed` method
- `config/SecurityConfig.java` — permit GET `/users/{id}/reviews`, `/auctions/{id}/reviews`; require auth on POST reviews/flag/respond

### Frontend new (≈ 20 files)

```
components/reviews/
  ReviewPanel.tsx
  ReviewPanel.test.tsx
  ReviewCard.tsx
  ReviewCard.test.tsx
  ReviewList.tsx
  ReviewList.test.tsx
  RatingSummary.tsx
  RatingSummary.test.tsx
  StarSelector.tsx
  StarSelector.test.tsx
  RespondModal.tsx
  RespondModal.test.tsx
  FlagModal.tsx
  FlagModal.test.tsx
  PendingReviewsSection.tsx
  PendingReviewsSection.test.tsx
  ProfileReviewTabs.tsx
  ProfileReviewTabs.test.tsx
hooks/
  useReviews.ts
  useReviews.test.tsx
lib/api/
  reviews.ts
  reviews.test.ts
types/
  review.ts
```

### Frontend modified

- `components/user/ReputationStars.tsx` — thin wrapper around `RatingSummary`
- `components/user/PublicProfileView.tsx` — replace empty-state with `<ProfileReviewTabs>`
- `app/auction/[id]/escrow/EscrowPageClient.tsx` — append `<ReviewPanel>` on COMPLETED + `REVIEW_REVEALED` invalidation
- `app/dashboard/(verified)/overview/page.tsx` — append `<PendingReviewsSection>`
- `types/auction.ts` — extend `AuctionTopicEnvelope` union

### Docs

- `docs/implementation/DEFERRED_WORK.md` — remove two entries (see §14)
- This spec and its companion plan

---

## 18. Timeline estimate

Six tasks, roughly symmetric in size. Prior sub-spec benchmark (Epic 07 sub-2) shipped 6 tasks + 1 post-review fix in about 17 commits.

**Expected shape:**
- Tasks 1-3 (backend): entity + service + 6 endpoints + scheduler + integration. ~8-10 commits.
- Tasks 4-6 (frontend): primitives + panel + tabs + dashboard. ~6-8 commits.
- Plus per-task spec-review + code-quality review fixup commits.

Total: ~16-20 commits, single PR to `dev`.
