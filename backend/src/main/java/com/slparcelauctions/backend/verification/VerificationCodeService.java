package com.slparcelauctions.backend.verification;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserNotFoundException;
import com.slparcelauctions.backend.user.UserRepository;
import com.slparcelauctions.backend.verification.dto.ActiveCodeResponse;
import com.slparcelauctions.backend.verification.dto.GenerateCodeResponse;
import com.slparcelauctions.backend.verification.exception.AlreadyVerifiedException;
import com.slparcelauctions.backend.verification.exception.CodeCollisionException;
import com.slparcelauctions.backend.verification.exception.CodeNotFoundException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class VerificationCodeService {

    public static final Duration CODE_TTL = Duration.ofMinutes(15);

    private final VerificationCodeRepository repository;
    private final UserRepository userRepository;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    /** Generate a fresh code for the given user, voiding any prior active codes. */
    @Transactional
    public GenerateCodeResponse generate(Long userId, VerificationCodeType type) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException(userId));
        if (Boolean.TRUE.equals(user.getVerified())) {
            throw new AlreadyVerifiedException(userId);
        }
        voidActive(userId, type);
        String code = String.format("%06d", random.nextInt(1_000_000));
        OffsetDateTime expiresAt = OffsetDateTime.now(clock).plus(CODE_TTL);
        VerificationCode row = repository.save(
                VerificationCode.builder()
                        .userId(userId)
                        .code(code)
                        .type(type)
                        .expiresAt(expiresAt)
                        .used(false)
                        .build());
        log.info("Generated verification code for user {} (type={}, id={})",
                userId, type, row.getId());
        return new GenerateCodeResponse(code, expiresAt);
    }

    /** Non-destructive read of the caller's currently active code, if any. */
    @Transactional(readOnly = true)
    public Optional<ActiveCodeResponse> findActive(Long userId, VerificationCodeType type) {
        return repository
                .findFirstByUserIdAndTypeAndUsedFalseAndExpiresAtAfterOrderByCreatedAtDesc(
                        userId, type, OffsetDateTime.now(clock))
                .map(c -> new ActiveCodeResponse(c.getCode(), c.getExpiresAt()));
    }

    /**
     * Generate a PARCEL-type code bound to a specific draft auction. Voids any
     * prior active PARCEL code for this auction. Unlike {@link #generate}, does
     * NOT check the user's verified flag — PARCEL codes presuppose a verified
     * user (the caller is the seller of an auction, and the auction-create path
     * already gates on {@code AuctionController.requireVerified}).
     */
    @Transactional
    public GenerateCodeResponse generateForParcel(Long userId, Long auctionId) {
        voidActiveParcelCodes(auctionId);
        String code = String.format("%06d", random.nextInt(1_000_000));
        OffsetDateTime expiresAt = OffsetDateTime.now(clock).plus(CODE_TTL);
        VerificationCode row = repository.save(
                VerificationCode.builder()
                        .userId(userId)
                        .auctionId(auctionId)
                        .code(code)
                        .type(VerificationCodeType.PARCEL)
                        .expiresAt(expiresAt)
                        .used(false)
                        .build());
        log.info("Generated PARCEL verification code for user {} auction {} (id={})",
                userId, auctionId, row.getId());
        return new GenerateCodeResponse(code, expiresAt);
    }

    /**
     * Non-destructive read of the active PARCEL code for an auction, if any.
     * Callers filter out expired rows here rather than pushing a time filter
     * into the repository so the same repository finder can feed the expiry
     * sweep job (which needs to see ALL unused rows to decide "no active code").
     */
    @Transactional(readOnly = true)
    public Optional<ActiveCodeResponse> findActiveForParcel(Long userId, Long auctionId) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        return repository
                .findByAuctionIdAndTypeAndUsedFalse(auctionId, VerificationCodeType.PARCEL)
                .stream()
                .filter(c -> userId == null || userId.equals(c.getUserId()))
                .filter(c -> c.getExpiresAt().isAfter(now))
                .findFirst()
                .map(c -> new ActiveCodeResponse(c.getCode(), c.getExpiresAt()));
    }

    /**
     * Validate a code and mark it used. Handles the Q5b collision case
     * (multiple rows match the same code) by voiding BOTH matching rows
     * before throwing {@link CodeCollisionException}.
     *
     * <p><strong>{@code noRollbackFor} is load-bearing.</strong> Without it,
     * Spring's default {@code RuntimeException} rollback would revert the
     * {@code used = true} writes on both colliding rows when the exception
     * propagates, and the next {@code consume} call with the same code would
     * hit the same collision. The spec section 7 / Q5b security guarantee
     * requires that both colliding rows become permanently unusable even
     * though an exception is thrown. A future refactor must NOT drop the
     * {@code noRollbackFor} attribute, or the collision guarantee silently
     * breaks.
     */
    @Transactional(noRollbackFor = CodeCollisionException.class)
    public Long consume(String code, VerificationCodeType type) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        List<VerificationCode> matches = repository
                .findByCodeAndTypeAndUsedFalseAndExpiresAtAfter(code, type, now);

        if (matches.isEmpty()) {
            throw new CodeNotFoundException(code);
        }
        if (matches.size() > 1) {
            List<Long> affected = matches.stream().map(VerificationCode::getUserId).toList();
            log.warn("Verification code collision: code={} users={} - voiding all matches",
                    code, affected);
            matches.forEach(c -> c.setUsed(true));
            repository.saveAll(matches);
            throw new CodeCollisionException(code, affected);
        }
        VerificationCode match = matches.get(0);
        match.setUsed(true);
        repository.save(match);
        return match.getUserId();
    }

    private void voidActive(Long userId, VerificationCodeType type) {
        List<VerificationCode> active = repository.findByUserIdAndTypeAndUsedFalse(userId, type);
        if (active.isEmpty()) return;
        active.forEach(c -> c.setUsed(true));
        repository.saveAll(active);
        log.info("Voided {} prior active code(s) for user {}", active.size(), userId);
    }

    private void voidActiveParcelCodes(Long auctionId) {
        List<VerificationCode> active = repository
                .findByAuctionIdAndTypeAndUsedFalse(auctionId, VerificationCodeType.PARCEL);
        if (active.isEmpty()) return;
        active.forEach(c -> c.setUsed(true));
        repository.saveAll(active);
        log.info("Voided {} prior active PARCEL code(s) for auction {}", active.size(), auctionId);
    }
}
