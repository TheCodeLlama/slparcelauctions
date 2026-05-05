package com.slparcelauctions.backend.sl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.slparcelauctions.backend.admin.ban.BanCheckService;
import com.slparcelauctions.backend.sl.dto.SlVerifyRequest;
import com.slparcelauctions.backend.sl.dto.SlVerifyResponse;
import com.slparcelauctions.backend.sl.exception.AvatarAlreadyLinkedException;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;
import com.slparcelauctions.backend.verification.VerificationCodeService;
import com.slparcelauctions.backend.verification.VerificationCodeType;
import com.slparcelauctions.backend.verification.exception.AlreadyVerifiedException;

class SlVerificationServiceTest {

    private static final OffsetDateTime NOW = OffsetDateTime.of(
            2026, 4, 13, 20, 0, 0, 0, ZoneOffset.UTC);
    private static final Clock FIXED = Clock.fixed(NOW.toInstant(), ZoneOffset.UTC);
    private static final UUID TRUSTED = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID AVATAR = UUID.fromString("a1b2c3d4-a1b2-c3d4-e5f6-000000000123");

    private VerificationCodeService codeService;
    private UserRepository userRepository;
    private SlHeaderValidator headerValidator;
    private SlVerificationService service;

    @BeforeEach
    void setup() {
        codeService = mock(VerificationCodeService.class);
        userRepository = mock(UserRepository.class);
        headerValidator = new SlHeaderValidator(
                new SlConfigProperties("Production", Set.of(TRUSTED)));
        service = new SlVerificationService(codeService, userRepository, headerValidator, mock(BanCheckService.class), FIXED);
    }

    private SlVerifyRequest body() {
        return new SlVerifyRequest(
                "123456", AVATAR, "Test Resident", "Test",
                "test.resident", LocalDate.of(2012, 1, 1), 3);
    }

    @Test
    void happyPath_linksAvatarAndMarksVerified() {
        when(userRepository.findBySlAvatarUuid(AVATAR)).thenReturn(Optional.empty());
        when(codeService.consume("123456", VerificationCodeType.PLAYER)).thenReturn(7L);
        User user = User.builder().id(7L).email("a@b.c").username("a").passwordHash("x").verified(false).build();
        when(userRepository.findById(7L)).thenReturn(Optional.of(user));
        when(userRepository.save(any(User.class))).thenAnswer(i -> i.getArgument(0));

        SlVerifyResponse resp = service.verify("Production", TRUSTED.toString(), body());

        assertThat(resp.verified()).isTrue();
        assertThat(resp.userId()).isEqualTo(7L);
        assertThat(user.getSlAvatarUuid()).isEqualTo(AVATAR);
        assertThat(user.getSlAvatarName()).isEqualTo("Test Resident");
        assertThat(user.getSlDisplayName()).isEqualTo("Test");
        assertThat(user.getSlUsername()).isEqualTo("test.resident");
        assertThat(user.getSlBornDate()).isEqualTo(LocalDate.of(2012, 1, 1));
        assertThat(user.getSlPayinfo()).isEqualTo(3);
        assertThat(user.getVerified()).isTrue();
        assertThat(user.getVerifiedAt()).isEqualTo(NOW);
    }

    @Test
    void avatarAlreadyLinked_throwsBeforeConsumingCode() {
        User other = User.builder().username("u-" + java.util.UUID.randomUUID().toString().substring(0, 8)).id(99L).slAvatarUuid(AVATAR).build();
        when(userRepository.findBySlAvatarUuid(AVATAR)).thenReturn(Optional.of(other));

        assertThatThrownBy(() -> service.verify("Production", TRUSTED.toString(), body()))
                .isInstanceOf(AvatarAlreadyLinkedException.class);
        verify(codeService, never()).consume(any(), any());
    }

    @Test
    void userAlreadyVerified_throws() {
        when(userRepository.findBySlAvatarUuid(AVATAR)).thenReturn(Optional.empty());
        when(codeService.consume("123456", VerificationCodeType.PLAYER)).thenReturn(7L);
        User verified = User.builder().id(7L).verified(true).email("a@b.c").username("a").passwordHash("x").build();
        when(userRepository.findById(7L)).thenReturn(Optional.of(verified));

        assertThatThrownBy(() -> service.verify("Production", TRUSTED.toString(), body()))
                .isInstanceOf(AlreadyVerifiedException.class);
    }

    @Test
    void headerValidationFails_shortCircuitsBeforeAvatarCheck() {
        assertThatThrownBy(() -> service.verify("Beta", TRUSTED.toString(), body()))
                .hasMessageContaining("grid");
        verify(userRepository, never()).findBySlAvatarUuid(any());
        verify(codeService, never()).consume(any(), any());
    }
}
