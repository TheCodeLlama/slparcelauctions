package com.slparcelauctions.backend.auction.saved;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.slparcelauctions.backend.auction.Auction;
import com.slparcelauctions.backend.auction.AuctionRepository;
import com.slparcelauctions.backend.auction.AuctionStatus;
import com.slparcelauctions.backend.auction.exception.AuctionNotFoundException;
import com.slparcelauctions.backend.auction.saved.exception.CannotSavePreActiveException;
import com.slparcelauctions.backend.auction.saved.exception.SavedLimitReachedException;
import com.slparcelauctions.backend.auction.search.AuctionPhotoBatchRepository;
import com.slparcelauctions.backend.auction.search.AuctionSearchPredicateBuilder;
import com.slparcelauctions.backend.auction.search.AuctionSearchQuery;
import com.slparcelauctions.backend.auction.search.AuctionSearchResultDto;
import com.slparcelauctions.backend.auction.search.AuctionSearchResultMapper;
import com.slparcelauctions.backend.auction.search.AuctionTagBatchRepository;
import com.slparcelauctions.backend.auction.search.SearchMeta;
import com.slparcelauctions.backend.auction.search.SearchPagedResponse;
import com.slparcelauctions.backend.parceltag.ParcelTag;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import lombok.RequiredArgsConstructor;

/**
 * Saved-auctions read/write surface for the bidder dashboard.
 *
 * <p><strong>Cap enforcement.</strong> Each user is capped at
 * {@value #SAVED_CAP} saved rows. Without serialization, two concurrent
 * POSTs against the cap (count = 499) could each pass the count check
 * and insert, yielding 501. We take a per-user PostgreSQL transaction-scoped
 * advisory lock ({@code pg_advisory_xact_lock(hashtext("saved:" + userId))})
 * before re-counting and inserting. The lock is released automatically on
 * commit/rollback. Different users hash to different lock keys, so this
 * doesn't serialize the global save flow.
 *
 * <p><strong>Idempotent save.</strong> A POST against an already-saved
 * (user, auction) pair returns the existing row's {@code savedAt} with
 * 200 OK rather than reinserting. Implementation reads first, locks +
 * inserts only on miss; the unique constraint
 * {@code uk_saved_auctions_user_auction} is the storage-layer backstop.
 *
 * <p><strong>Pre-active guard.</strong> Saving DRAFT / DRAFT_PAID /
 * VERIFICATION_PENDING / VERIFICATION_FAILED auctions is forbidden — these
 * are not yet visible to non-sellers, so a save would leak existence.
 * 403 with {@code CANNOT_SAVE_PRE_ACTIVE}.
 *
 * <p><strong>Paginated saved list.</strong> {@link #listPaginated} reuses
 * Task 3's {@link AuctionSearchPredicateBuilder} to honour the same
 * filter surface as {@code /auctions/search}, AND'd with a "this user has
 * saved this auction" subquery. Sorting is most-recently-saved first via
 * an {@code orderBy} expression that re-joins {@link SavedAuction} so
 * {@code saved_at DESC} can be expressed against the {@link Auction}
 * root row. The {@link AuctionSearchPredicateBuilder#build} call hard-codes
 * a {@code status = ACTIVE} predicate, which would break ENDED_ONLY/ALL
 * filtering — see {@link #savedListSpec}.
 */
@Service
@RequiredArgsConstructor
public class SavedAuctionService {

    public static final int SAVED_CAP = 500;

    /**
     * Pre-activation states. Saving these is forbidden (POST guard) and the
     * {@code ended_only} filter excludes them from the saved-list view.
     */
    private static final Set<AuctionStatus> PRE_ACTIVE = EnumSet.of(
            AuctionStatus.DRAFT,
            AuctionStatus.DRAFT_PAID,
            AuctionStatus.VERIFICATION_PENDING,
            AuctionStatus.VERIFICATION_FAILED);

    private final SavedAuctionRepository savedRepo;
    private final AuctionRepository auctionRepo;
    private final UserRepository userRepo;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    private final AuctionSearchPredicateBuilder predicateBuilder;
    private final AuctionTagBatchRepository tagBatchRepo;
    private final AuctionPhotoBatchRepository photoBatchRepo;
    private final AuctionSearchResultMapper mapper;

    @Transactional
    public SavedAuctionDto save(Long userId, Long auctionId) {
        Auction auction = auctionRepo.findById(auctionId)
                .orElseThrow(() -> new AuctionNotFoundException(auctionId));

        if (PRE_ACTIVE.contains(auction.getStatus())) {
            throw new CannotSavePreActiveException(auctionId, auction.getStatus().name());
        }

        return savedRepo.findByUserIdAndAuctionId(userId, auctionId)
                .map(existing -> new SavedAuctionDto(auctionId, existing.getSavedAt()))
                .orElseGet(() -> insert(userId, auction));
    }

    private SavedAuctionDto insert(Long userId, Auction auction) {
        // Per-user advisory lock serializes concurrent inserts so the cap
        // can't race past 500. queryForList lets us discard the void return
        // value of pg_advisory_xact_lock without an Object.class NPE.
        jdbc.queryForList(
                "SELECT pg_advisory_xact_lock(hashtext(?))",
                "saved:" + userId);

        long count = savedRepo.countByUserId(userId);
        if (count >= SAVED_CAP) {
            throw new SavedLimitReachedException();
        }

        User userRef = userRepo.getReferenceById(userId);
        OffsetDateTime now = OffsetDateTime.now(clock);
        SavedAuction row = SavedAuction.builder()
                .user(userRef)
                .auction(auction)
                .savedAt(now)
                .build();
        savedRepo.save(row);
        return new SavedAuctionDto(auction.getId(), now);
    }

    @Transactional
    public void unsave(Long userId, Long auctionId) {
        savedRepo.deleteByUserIdAndAuctionId(userId, auctionId);
    }

    @Transactional(readOnly = true)
    public SavedAuctionIdsResponse listIds(Long userId) {
        List<Long> ids = savedRepo.findAuctionIdsByUserId(userId);
        return new SavedAuctionIdsResponse(ids);
    }

    /**
     * Paginated saved list with the same filter surface as
     * {@code /auctions/search} except {@code near_region}, {@code distance},
     * and {@code seller_id} (those don't make sense in the saved context).
     *
     * <p>Default sort is {@code saved_at DESC}; the orderBy is expressed via a
     * scalar correlated subquery against {@link SavedAuction}. The base
     * predicate-builder spec hard-codes {@code status = ACTIVE}, which is
     * correct for the public search surface but wrong for {@code ALL}/
     * {@code ENDED_ONLY} here — so we replicate the predicate-builder's filter
     * predicates without the ACTIVE clamp via a custom spec composition.
     */
    @Transactional(readOnly = true)
    public SearchPagedResponse<AuctionSearchResultDto> listPaginated(
            Long userId,
            AuctionSearchQuery query,
            SavedStatusFilter statusFilter) {

        Specification<Auction> spec = savedListSpec(userId, query, statusFilter);

        Pageable pageable = PageRequest.of(query.page(), query.size(), Sort.unsorted());
        Page<Auction> page = auctionRepo.findAll(spec, pageable);

        List<Long> ids = page.stream().map(Auction::getId).toList();
        Map<Long, Set<ParcelTag>> tags = tagBatchRepo.findTagsGrouped(ids);
        Map<Long, String> photos = photoBatchRepo.findPrimaryPhotoUrls(ids);

        List<AuctionSearchResultDto> dtos = mapper.toDtos(page.getContent(), tags, photos, null);
        Page<AuctionSearchResultDto> dtoPage = new PageImpl<>(dtos, pageable, page.getTotalElements());

        return SearchPagedResponse.from(dtoPage, new SearchMeta("saved_at", null));
    }

    /**
     * Build the saved-list spec: the standard search filter predicates AND
     * a "this user saved this auction" EXISTS, with a status predicate per
     * {@code statusFilter}. The base {@link AuctionSearchPredicateBuilder#build}
     * hard-codes {@code status = ACTIVE}, so we apply it only when the
     * filter is {@code ACTIVE_ONLY}; for {@code ALL} / {@code ENDED_ONLY} the
     * base spec is unsuitable and we rebuild the filter chain inline.
     *
     * <p>Result ordering is most-recently-saved first; the orderBy uses a
     * correlated scalar subquery against {@link SavedAuction#getSavedAt()}.
     */
    private Specification<Auction> savedListSpec(
            Long userId, AuctionSearchQuery query, SavedStatusFilter statusFilter) {

        Specification<Auction> filterSpec = switch (statusFilter) {
            // ACTIVE_ONLY uses the canonical predicate builder — its baked-in
            // status = ACTIVE matches the filter intent.
            case ACTIVE_ONLY -> predicateBuilder.build(query);
            // ENDED_ONLY: filters minus ACTIVE clamp + explicit "not pre-active and
            // not ACTIVE" predicate. Spec §5.4: any future terminal state added
            // to AuctionStatus automatically falls into "ended".
            case ENDED_ONLY -> {
                Set<AuctionStatus> excluded = EnumSet.copyOf(PRE_ACTIVE);
                excluded.add(AuctionStatus.ACTIVE);
                yield predicateBuilder.buildWithoutStatusClamp(query)
                        .and((root, q, cb) -> cb.not(root.get("status").in(excluded)));
            }
            // ALL: filters minus ACTIVE clamp; no status restriction at all.
            case ALL -> predicateBuilder.buildWithoutStatusClamp(query);
        };

        // EXISTS (SELECT 1 FROM SavedAuction s WHERE s.user_id = :userId AND s.auction_id = root.id)
        Specification<Auction> savedExists = (root, q, cb) -> {
            Subquery<Long> sub = q.subquery(Long.class);
            Root<SavedAuction> s = sub.from(SavedAuction.class);
            sub.select(s.get("id"));
            sub.where(cb.and(
                    cb.equal(s.get("user").get("id"), userId),
                    cb.equal(s.get("auction").get("id"), root.get("id"))));
            return cb.exists(sub);
        };

        // saved_at DESC ordering via correlated scalar subquery; tiebreaker
        // on auction id DESC for deterministic pagination.
        Specification<Auction> withOrder = (root, q, cb) -> {
            Subquery<OffsetDateTime> sub = q.subquery(OffsetDateTime.class);
            Root<SavedAuction> s = sub.from(SavedAuction.class);
            sub.select(s.get("savedAt"));
            sub.where(cb.and(
                    cb.equal(s.get("user").get("id"), userId),
                    cb.equal(s.get("auction").get("id"), root.get("id"))));
            // Only set ordering on the outer query (not on count subqueries).
            if (q.getResultType() != Long.class && q.getResultType() != long.class) {
                q.orderBy(cb.desc(sub), cb.desc(root.get("id")));
            }
            return cb.conjunction();
        };

        return filterSpec.and(savedExists).and(withOrder);
    }
}
