package com.slparcelauctions.backend.user.deletion;

import com.slparcelauctions.backend.admin.audit.AdminActionService;
import com.slparcelauctions.backend.auction.AuctionRepository;
import com.slparcelauctions.backend.auction.AuctionStatus;
import com.slparcelauctions.backend.auction.ProxyBidRepository;
import com.slparcelauctions.backend.escrow.EscrowRepository;
import com.slparcelauctions.backend.escrow.EscrowState;
import com.slparcelauctions.backend.user.Role;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;
import com.slparcelauctions.backend.user.deletion.exception.ActiveAuctionsException;
import com.slparcelauctions.backend.user.deletion.exception.ActiveHighBidsException;
import com.slparcelauctions.backend.user.deletion.exception.ActiveProxyBidsException;
import com.slparcelauctions.backend.user.deletion.exception.InvalidPasswordException;
import com.slparcelauctions.backend.user.deletion.exception.OpenEscrowsException;
import com.slparcelauctions.backend.user.deletion.exception.UserAlreadyDeletedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure service-layer unit tests for {@link UserDeletionService}.
 * All dependencies are mocked so no Spring context is required.
 *
 * <p>Covers all 10 scenarios in the precondition matrix:
 * <ol>
 *   <li>Happy path (self-service) — scrubs PII, sets deletedAt, bumps tokenVersion.</li>
 *   <li>Self-service retains slAvatarUuid.</li>
 *   <li>Self-service bumps tokenVersion.</li>
 *   <li>Wrong password → {@link InvalidPasswordException}.</li>
 *   <li>Already deleted → {@link UserAlreadyDeletedException}.</li>
 *   <li>Admin path — no password check, records audit action.</li>
 *   <li>Active auctions as seller → {@link ActiveAuctionsException}.</li>
 *   <li>Open escrows → {@link OpenEscrowsException}.</li>
 *   <li>Current high bidder on active auction → {@link ActiveHighBidsException}.</li>
 *   <li>Active proxy bids → {@link ActiveProxyBidsException}.</li>
 * </ol>
 */
class UserDeletionServiceTest {

    private static final OffsetDateTime NOW = OffsetDateTime.of(2026, 4, 27, 12, 0, 0, 0, ZoneOffset.UTC);
    private static final Clock FIXED_CLOCK = Clock.fixed(NOW.toInstant(), ZoneOffset.UTC);

    private static final Long USER_ID = 42L;
    private static final Long ADMIN_ID = 99L;
    private static final String CORRECT_PASSWORD = "correct-password";
    private static final String ENCODED_PASSWORD = "$2a$10$encoded";

    private UserRepository userRepo;
    private AuctionRepository auctionRepo;
    private EscrowRepository escrowRepo;
    private ProxyBidRepository proxyBidRepo;
    private PasswordEncoder passwordEncoder;
    private AdminActionService adminActionService;
    private UserDeletionService service;

    @BeforeEach
    void setup() {
        userRepo = mock(UserRepository.class);
        auctionRepo = mock(AuctionRepository.class);
        escrowRepo = mock(EscrowRepository.class);
        proxyBidRepo = mock(ProxyBidRepository.class);
        passwordEncoder = mock(PasswordEncoder.class);
        adminActionService = mock(AdminActionService.class);

        service = new UserDeletionService(
                userRepo, auctionRepo, escrowRepo, proxyBidRepo,
                passwordEncoder, adminActionService, FIXED_CLOCK);
    }

    // ------------------------------------------------------------------ //
    //  Helpers                                                            //
    // ------------------------------------------------------------------ //

    /** Builds a live (not deleted) user with ID {@value #USER_ID}. */
    private User activeUser() {
        return User.builder()
                .id(USER_ID)
                .email("user@example.com").username("user")
                .passwordHash(ENCODED_PASSWORD)
                .displayName("Test User")
                .bio("Some bio")
                .role(Role.USER)
                .verified(true)
                .tokenVersion(5L)
                .build();
    }

    /** Configures all precondition repo calls to return empty (no blockers). */
    @SuppressWarnings("unchecked")
    private void stubNoPreconditionBlockers(User user) {
        when(auctionRepo.findIdsBySellerAndStatusIn(eq(user), any(Collection.class)))
                .thenReturn(List.of());
        when(escrowRepo.findIdsByUserInvolvedAndStateIn(eq(USER_ID), any(Collection.class)))
                .thenReturn(List.of());
        when(auctionRepo.findIdsByCurrentBidderIdAndActive(USER_ID))
                .thenReturn(List.of());
        when(proxyBidRepo.findActiveIdsByBidder(user))
                .thenReturn(List.of());
    }

    // ------------------------------------------------------------------ //
    //  Scenario 1 – happy-path self-service scrub                        //
    // ------------------------------------------------------------------ //

    @Test
    void deleteSelf_happyPath_scrubsPiiAndSetsDeletedAt() {
        User user = activeUser();
        when(userRepo.findById(USER_ID)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches(CORRECT_PASSWORD, ENCODED_PASSWORD)).thenReturn(true);
        stubNoPreconditionBlockers(user);
        when(userRepo.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        service.deleteSelf(USER_ID, CORRECT_PASSWORD);

        assertThat(user.getDeletedAt()).isEqualTo(NOW);
        assertThat(user.getEmail()).isNull();
        assertThat(user.getBio()).isNull();
        assertThat(user.getDisplayName()).isEqualTo("Deleted user #" + USER_ID);
        verify(userRepo).save(user);
    }

    // ------------------------------------------------------------------ //
    //  Scenario 2 – slAvatarUuid retained                                //
    // ------------------------------------------------------------------ //

    @Test
    void deleteSelf_retainsSlAvatarUuid() {
        User user = activeUser();
        // slAvatarUuid is null on this fixture — confirm it stays null (not accidentally set).
        when(userRepo.findById(USER_ID)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches(CORRECT_PASSWORD, ENCODED_PASSWORD)).thenReturn(true);
        stubNoPreconditionBlockers(user);
        when(userRepo.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        service.deleteSelf(USER_ID, CORRECT_PASSWORD);

        assertThat(user.getSlAvatarUuid()).isNull(); // retained (null here == not overwritten)
    }

    // ------------------------------------------------------------------ //
    //  Scenario 3 – tokenVersion bumped                                  //
    // ------------------------------------------------------------------ //

    @Test
    void deleteSelf_bumpsTokenVersion() {
        User user = activeUser();
        long originalTv = user.getTokenVersion(); // 5L from builder
        when(userRepo.findById(USER_ID)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches(CORRECT_PASSWORD, ENCODED_PASSWORD)).thenReturn(true);
        stubNoPreconditionBlockers(user);
        when(userRepo.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        service.deleteSelf(USER_ID, CORRECT_PASSWORD);

        assertThat(user.getTokenVersion()).isEqualTo(originalTv + 1);
    }

    // ------------------------------------------------------------------ //
    //  Scenario 4 – wrong password                                       //
    // ------------------------------------------------------------------ //

    @Test
    void deleteSelf_wrongPassword_throwsInvalidPasswordException() {
        User user = activeUser();
        when(userRepo.findById(USER_ID)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("wrong", ENCODED_PASSWORD)).thenReturn(false);

        assertThatThrownBy(() -> service.deleteSelf(USER_ID, "wrong"))
                .isInstanceOf(InvalidPasswordException.class);

        verify(userRepo, never()).save(any());
    }

    // ------------------------------------------------------------------ //
    //  Scenario 5 – already deleted                                      //
    // ------------------------------------------------------------------ //

    @Test
    void deleteSelf_alreadyDeleted_throwsUserAlreadyDeletedException() {
        User user = activeUser();
        user.setDeletedAt(NOW.minusDays(1));
        when(userRepo.findById(USER_ID)).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> service.deleteSelf(USER_ID, CORRECT_PASSWORD))
                .isInstanceOf(UserAlreadyDeletedException.class)
                .hasMessageContaining(String.valueOf(USER_ID));

        verify(userRepo, never()).save(any());
    }

    // ------------------------------------------------------------------ //
    //  Scenario 6 – admin path (no password check, audit recorded)       //
    // ------------------------------------------------------------------ //

    @Test
    void deleteByAdmin_noPasswordCheck_recordsAuditAction() {
        User user = activeUser();
        when(userRepo.findById(USER_ID)).thenReturn(Optional.of(user));
        stubNoPreconditionBlockers(user);
        when(userRepo.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        service.deleteByAdmin(USER_ID, ADMIN_ID, "Spam account");

        assertThat(user.getDeletedAt()).isEqualTo(NOW);
        // Password encoder must NOT be called for admin path.
        verify(passwordEncoder, never()).matches(any(), any());
        verify(adminActionService).record(
                eq(ADMIN_ID),
                eq(com.slparcelauctions.backend.admin.audit.AdminActionType.USER_DELETED_BY_ADMIN),
                eq(com.slparcelauctions.backend.admin.audit.AdminActionTargetType.USER),
                eq(USER_ID),
                eq("Spam account"),
                any());
    }

    // ------------------------------------------------------------------ //
    //  Scenario 7 – active auctions as seller                            //
    // ------------------------------------------------------------------ //

    @Test
    @SuppressWarnings("unchecked")
    void deleteSelf_withActiveAuctions_throwsActiveAuctionsException() {
        User user = activeUser();
        when(userRepo.findById(USER_ID)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches(CORRECT_PASSWORD, ENCODED_PASSWORD)).thenReturn(true);
        when(auctionRepo.findIdsBySellerAndStatusIn(eq(user), any(Collection.class)))
                .thenReturn(List.of(101L, 102L));

        assertThatThrownBy(() -> service.deleteSelf(USER_ID, CORRECT_PASSWORD))
                .isInstanceOf(ActiveAuctionsException.class)
                .satisfies(ex -> {
                    var e = (ActiveAuctionsException) ex;
                    assertThat(e.getCode()).isEqualTo("ACTIVE_AUCTIONS");
                    assertThat(e.getBlockingIds()).containsExactly(101L, 102L);
                });

        verify(userRepo, never()).save(any());
    }

    // ------------------------------------------------------------------ //
    //  Scenario 8 – open escrows                                         //
    // ------------------------------------------------------------------ //

    @Test
    @SuppressWarnings("unchecked")
    void deleteSelf_withOpenEscrows_throwsOpenEscrowsException() {
        User user = activeUser();
        when(userRepo.findById(USER_ID)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches(CORRECT_PASSWORD, ENCODED_PASSWORD)).thenReturn(true);
        when(auctionRepo.findIdsBySellerAndStatusIn(eq(user), any(Collection.class)))
                .thenReturn(List.of());
        when(escrowRepo.findIdsByUserInvolvedAndStateIn(eq(USER_ID), any(Collection.class)))
                .thenReturn(List.of(201L));

        assertThatThrownBy(() -> service.deleteSelf(USER_ID, CORRECT_PASSWORD))
                .isInstanceOf(OpenEscrowsException.class)
                .satisfies(ex -> {
                    var e = (OpenEscrowsException) ex;
                    assertThat(e.getCode()).isEqualTo("OPEN_ESCROWS");
                    assertThat(e.getBlockingIds()).containsExactly(201L);
                });

        verify(userRepo, never()).save(any());
    }

    // ------------------------------------------------------------------ //
    //  Scenario 9 – current high bidder                                  //
    // ------------------------------------------------------------------ //

    @Test
    @SuppressWarnings("unchecked")
    void deleteSelf_asCurrentHighBidder_throwsActiveHighBidsException() {
        User user = activeUser();
        when(userRepo.findById(USER_ID)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches(CORRECT_PASSWORD, ENCODED_PASSWORD)).thenReturn(true);
        when(auctionRepo.findIdsBySellerAndStatusIn(eq(user), any(Collection.class)))
                .thenReturn(List.of());
        when(escrowRepo.findIdsByUserInvolvedAndStateIn(eq(USER_ID), any(Collection.class)))
                .thenReturn(List.of());
        when(auctionRepo.findIdsByCurrentBidderIdAndActive(USER_ID))
                .thenReturn(List.of(301L));

        assertThatThrownBy(() -> service.deleteSelf(USER_ID, CORRECT_PASSWORD))
                .isInstanceOf(ActiveHighBidsException.class)
                .satisfies(ex -> {
                    var e = (ActiveHighBidsException) ex;
                    assertThat(e.getCode()).isEqualTo("ACTIVE_HIGH_BIDS");
                    assertThat(e.getBlockingIds()).containsExactly(301L);
                });

        verify(userRepo, never()).save(any());
    }

    // ------------------------------------------------------------------ //
    //  Scenario 10 – active proxy bids                                   //
    // ------------------------------------------------------------------ //

    @Test
    @SuppressWarnings("unchecked")
    void deleteSelf_withActiveProxyBids_throwsActiveProxyBidsException() {
        User user = activeUser();
        when(userRepo.findById(USER_ID)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches(CORRECT_PASSWORD, ENCODED_PASSWORD)).thenReturn(true);
        when(auctionRepo.findIdsBySellerAndStatusIn(eq(user), any(Collection.class)))
                .thenReturn(List.of());
        when(escrowRepo.findIdsByUserInvolvedAndStateIn(eq(USER_ID), any(Collection.class)))
                .thenReturn(List.of());
        when(auctionRepo.findIdsByCurrentBidderIdAndActive(USER_ID))
                .thenReturn(List.of());
        when(proxyBidRepo.findActiveIdsByBidder(user))
                .thenReturn(List.of(401L, 402L));

        assertThatThrownBy(() -> service.deleteSelf(USER_ID, CORRECT_PASSWORD))
                .isInstanceOf(ActiveProxyBidsException.class)
                .satisfies(ex -> {
                    var e = (ActiveProxyBidsException) ex;
                    assertThat(e.getCode()).isEqualTo("ACTIVE_PROXY_BIDS");
                    assertThat(e.getBlockingIds()).containsExactly(401L, 402L);
                });

        verify(userRepo, never()).save(any());
    }
}
