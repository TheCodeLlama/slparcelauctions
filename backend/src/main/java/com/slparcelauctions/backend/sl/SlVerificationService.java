package com.slparcelauctions.backend.sl;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.slparcelauctions.backend.admin.ban.BanCheckService;
import com.slparcelauctions.backend.sl.dto.SlVerifyRequest;
import com.slparcelauctions.backend.sl.dto.SlVerifyResponse;
import com.slparcelauctions.backend.sl.exception.AvatarAlreadyLinkedException;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserNotFoundException;
import com.slparcelauctions.backend.user.UserRepository;
import com.slparcelauctions.backend.verification.VerificationCodeService;
import com.slparcelauctions.backend.verification.VerificationCodeType;
import com.slparcelauctions.backend.verification.exception.AlreadyVerifiedException;
import com.slparcelauctions.backend.verification.exception.CodeCollisionException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Orchestrates {@code POST /api/v1/sl/verify}. Validates SL-injected headers,
 * pre-checks avatar uniqueness, consumes a verification code, then links the
 * avatar to the user and marks the account verified.
 *
 * <p>Does NOT catch {@code DataIntegrityViolationException} - the unique index
 * race on {@code users.sl_avatar_uuid} is handled by {@link SlExceptionHandler}.
 * Keeping the catch out of the service keeps the unit tests free of fake
 * constraint exceptions.
 *
 * <p><strong>{@code noRollbackFor = CodeCollisionException.class} is
 * load-bearing.</strong> {@link VerificationCodeService#consume} runs with
 * {@code REQUIRED} propagation so it joins this outer transaction (it does
 * NOT open a nested one). Without {@code noRollbackFor} on the outer boundary,
 * Spring's default rollback rule for {@code RuntimeException} would revert the
 * void-both-rows writes that {@code consume} performs in the collision branch
 * (see Task 2 Q5b fix in {@code VerificationCodeService}). Both annotations
 * must agree, or the collision guarantee silently breaks for any caller that
 * runs through {@code SlVerificationService.verify}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SlVerificationService {

    private final VerificationCodeService verificationCodeService;
    private final UserRepository userRepository;
    private final SlHeaderValidator headerValidator;
    private final BanCheckService banCheckService;
    private final Clock clock;

    @Transactional(noRollbackFor = CodeCollisionException.class)
    public SlVerifyResponse verify(
            String shardHeader, String ownerKeyHeader, SlVerifyRequest body) {
        headerValidator.validate(shardHeader, ownerKeyHeader);
        banCheckService.assertNotBanned(null, body.avatarUuid());

        Optional<User> existingLinked = userRepository.findBySlAvatarUuid(body.avatarUuid());
        if (existingLinked.isPresent()) {
            throw new AvatarAlreadyLinkedException(body.avatarUuid());
        }

        Long userId = verificationCodeService.consume(
                body.verificationCode(), VerificationCodeType.PLAYER);

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException(userId));
        if (Boolean.TRUE.equals(user.getVerified())) {
            throw new AlreadyVerifiedException(userId);
        }

        user.setSlAvatarUuid(body.avatarUuid());
        user.setSlAvatarName(body.avatarName());
        user.setSlDisplayName(body.displayName());
        user.setSlUsername(body.username());
        user.setSlBornDate(body.bornDate());
        user.setSlPayinfo(body.payInfo());
        user.setVerified(true);
        user.setVerifiedAt(OffsetDateTime.now(clock));
        userRepository.save(user);

        log.info("SL verification succeeded: userId={} avatarName={} payInfo={}",
                userId, body.avatarName(), body.payInfo());

        return new SlVerifyResponse(true, userId, body.avatarName());
    }
}
