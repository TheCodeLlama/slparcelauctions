package com.slparcelauctions.backend.verification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserNotFoundException;
import com.slparcelauctions.backend.user.UserRepository;
import com.slparcelauctions.backend.verification.dto.ActiveCodeResponse;
import com.slparcelauctions.backend.verification.dto.GenerateCodeResponse;
import com.slparcelauctions.backend.verification.exception.AlreadyVerifiedException;
import com.slparcelauctions.backend.verification.exception.CodeCollisionException;
import com.slparcelauctions.backend.verification.exception.CodeNotFoundException;

class VerificationCodeServiceTest {

    private static final OffsetDateTime NOW = OffsetDateTime.of(
            2026, 4, 13, 20, 0, 0, 0, ZoneOffset.UTC);
    private static final Clock FIXED_CLOCK = Clock.fixed(NOW.toInstant(), ZoneOffset.UTC);

    private VerificationCodeRepository repository;
    private UserRepository userRepository;
    private VerificationCodeService service;

    @BeforeEach
    void setup() {
        repository = mock(VerificationCodeRepository.class);
        userRepository = mock(UserRepository.class);
        service = new VerificationCodeService(repository, userRepository, FIXED_CLOCK, 15);
    }

    @Test
    void generate_happyPath_insertsRowAndReturnsCode() {
        User user = User.builder().id(1L).email("a@b.c").passwordHash("x").verified(false).build();
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(repository.findByUserIdAndTypeAndUsedFalse(1L, VerificationCodeType.PLAYER))
                .thenReturn(List.of());
        ArgumentCaptor<VerificationCode> saved = ArgumentCaptor.forClass(VerificationCode.class);
        when(repository.save(saved.capture())).thenAnswer(i -> i.getArgument(0));

        GenerateCodeResponse resp = service.generate(1L, VerificationCodeType.PLAYER);

        assertThat(resp.code()).matches("^[0-9]{6}$");
        assertThat(resp.expiresAt()).isEqualTo(NOW.plusMinutes(15));
        VerificationCode row = saved.getValue();
        assertThat(row.getUserId()).isEqualTo(1L);
        assertThat(row.getType()).isEqualTo(VerificationCodeType.PLAYER);
        assertThat(row.isUsed()).isFalse();
    }

    @Test
    void generate_voidsPriorActiveCode() {
        User user = User.builder().id(1L).verified(false).email("a@b.c").passwordHash("x").build();
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        VerificationCode prior = VerificationCode.builder()
                .id(99L).userId(1L).code("111111").type(VerificationCodeType.PLAYER)
                .expiresAt(NOW.plusMinutes(5)).used(false).build();
        when(repository.findByUserIdAndTypeAndUsedFalse(1L, VerificationCodeType.PLAYER))
                .thenReturn(List.of(prior));
        when(repository.save(any(VerificationCode.class))).thenAnswer(i -> i.getArgument(0));

        service.generate(1L, VerificationCodeType.PLAYER);

        assertThat(prior.isUsed()).isTrue();
        verify(repository).saveAll(List.of(prior));
    }

    @Test
    void generate_rejectsAlreadyVerifiedUser() {
        User user = User.builder().id(1L).verified(true).email("a@b.c").passwordHash("x").build();
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> service.generate(1L, VerificationCodeType.PLAYER))
                .isInstanceOf(AlreadyVerifiedException.class);
        verify(repository, never()).save(any());
    }

    @Test
    void generate_rejectsNonExistentUser() {
        when(userRepository.findById(42L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.generate(42L, VerificationCodeType.PLAYER))
                .isInstanceOf(UserNotFoundException.class);
    }

    @Test
    void findActive_returnsEmptyWhenNoLiveCode() {
        when(repository.findFirstByUserIdAndTypeAndUsedFalseAndExpiresAtAfterOrderByCreatedAtDesc(
                1L, VerificationCodeType.PLAYER, NOW)).thenReturn(Optional.empty());

        assertThat(service.findActive(1L, VerificationCodeType.PLAYER)).isEmpty();
    }

    @Test
    void findActive_returnsCodeWhenLive() {
        VerificationCode row = VerificationCode.builder()
                .code("123456").expiresAt(NOW.plusMinutes(10)).build();
        when(repository.findFirstByUserIdAndTypeAndUsedFalseAndExpiresAtAfterOrderByCreatedAtDesc(
                1L, VerificationCodeType.PLAYER, NOW)).thenReturn(Optional.of(row));

        ActiveCodeResponse resp = service.findActive(1L, VerificationCodeType.PLAYER).orElseThrow();
        assertThat(resp.code()).isEqualTo("123456");
    }

    @Test
    void consume_happyPath_marksUsedAndReturnsUserId() {
        VerificationCode row = VerificationCode.builder()
                .id(10L).userId(7L).code("654321").type(VerificationCodeType.PLAYER)
                .expiresAt(NOW.plusMinutes(5)).used(false).build();
        when(repository.findByCodeAndTypeAndUsedFalseAndExpiresAtAfter(
                "654321", VerificationCodeType.PLAYER, NOW)).thenReturn(List.of(row));
        when(repository.save(any(VerificationCode.class))).thenAnswer(i -> i.getArgument(0));

        Long userId = service.consume("654321", VerificationCodeType.PLAYER);

        assertThat(userId).isEqualTo(7L);
        assertThat(row.isUsed()).isTrue();
    }

    @Test
    void consume_nothingMatches_throwsCodeNotFound() {
        when(repository.findByCodeAndTypeAndUsedFalseAndExpiresAtAfter(
                "000000", VerificationCodeType.PLAYER, NOW)).thenReturn(List.of());

        assertThatThrownBy(() -> service.consume("000000", VerificationCodeType.PLAYER))
                .isInstanceOf(CodeNotFoundException.class);
    }

    @Test
    void consume_twoMatches_voidsBothAndThrowsCollision() {
        VerificationCode a = VerificationCode.builder()
                .id(10L).userId(1L).code("111111").type(VerificationCodeType.PLAYER)
                .expiresAt(NOW.plusMinutes(5)).used(false).build();
        VerificationCode b = VerificationCode.builder()
                .id(11L).userId(2L).code("111111").type(VerificationCodeType.PLAYER)
                .expiresAt(NOW.plusMinutes(5)).used(false).build();
        when(repository.findByCodeAndTypeAndUsedFalseAndExpiresAtAfter(
                "111111", VerificationCodeType.PLAYER, NOW)).thenReturn(List.of(a, b));

        assertThatThrownBy(() -> service.consume("111111", VerificationCodeType.PLAYER))
                .isInstanceOf(CodeCollisionException.class)
                .hasMessageContaining("2 users");
        assertThat(a.isUsed()).isTrue();
        assertThat(b.isUsed()).isTrue();
        verify(repository).saveAll(List.of(a, b));
    }
}
