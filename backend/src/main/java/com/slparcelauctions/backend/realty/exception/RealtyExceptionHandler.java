package com.slparcelauctions.backend.realty.exception;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.slparcelauctions.backend.realty.moderation.exception.RealtyGroupSuspendedException;
import com.slparcelauctions.backend.realty.moderation.exception.SuspensionAlreadyActiveException;
import com.slparcelauctions.backend.realty.moderation.exception.SuspensionNotFoundException;
import com.slparcelauctions.backend.realty.reports.exception.AlreadyReportedException;
import com.slparcelauctions.backend.realty.reports.exception.CannotReportOwnGroupException;
import com.slparcelauctions.backend.realty.reports.exception.ReportNotFoundException;
import com.slparcelauctions.backend.realty.reports.exception.ReportRateLimitedException;
import com.slparcelauctions.backend.realty.slgroup.exception.NoDriftDetectedException;
import com.slparcelauctions.backend.realty.slgroup.exception.ParcelNotOwnedByRegisteredSlGroupException;
import com.slparcelauctions.backend.realty.slgroup.exception.RegisteredSlGroupHasListingsException;
import com.slparcelauctions.backend.realty.slgroup.exception.SlGroupAlreadyRegisteredException;
import com.slparcelauctions.backend.realty.slgroup.exception.SlGroupFounderMismatchException;
import com.slparcelauctions.backend.realty.slgroup.exception.SlGroupNotFoundException;
import com.slparcelauctions.backend.realty.slgroup.exception.SlGroupNotVerifiedException;
import com.slparcelauctions.backend.realty.slgroup.exception.SlGroupVerificationExpiredException;
import com.slparcelauctions.backend.realty.wallet.exception.GroupHasInFlightEscrowsException;
import com.slparcelauctions.backend.realty.wallet.exception.GroupHasNonzeroBalanceException;
import com.slparcelauctions.backend.realty.wallet.exception.InsufficientGroupBalanceException;
import com.slparcelauctions.backend.realty.wallet.exception.LeaderFrozenException;
import com.slparcelauctions.backend.realty.wallet.exception.LeaderTermsNotAcceptedException;
import com.slparcelauctions.backend.sl.exception.InvalidSlHeadersException;
import com.slparcelauctions.backend.user.exception.UnsupportedImageFormatException;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;

/**
 * Realty-group-scoped @RestControllerAdvice. Higher precedence than the global
 * handler so the package-specific mappings take effect first.
 */
@RestControllerAdvice(basePackages = "com.slparcelauctions.backend.realty")
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
@Slf4j
public class RealtyExceptionHandler {

    @ExceptionHandler(RealtyGroupNotFoundException.class)
    public ProblemDetail handleNotFound(RealtyGroupNotFoundException e, HttpServletRequest req) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
        pd.setTitle("Realty Group Not Found");
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("code", "REALTY_GROUP_NOT_FOUND");
        if (e.getPublicId() != null) pd.setProperty("publicId", e.getPublicId().toString());
        return pd;
    }

    @ExceptionHandler(RealtyGroupNameTakenException.class)
    public ProblemDetail handleNameTaken(RealtyGroupNameTakenException e, HttpServletRequest req) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
        pd.setTitle("Group Name Taken");
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("code", "GROUP_NAME_TAKEN");
        pd.setProperty("name", e.getName());
        return pd;
    }

    @ExceptionHandler(RealtyGroupRenameCooldownException.class)
    public ProblemDetail handleCooldown(RealtyGroupRenameCooldownException e, HttpServletRequest req) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
        pd.setTitle("Rename On Cooldown");
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("code", "GROUP_RENAME_COOLDOWN");
        pd.setProperty("cooldownEndsAt", e.getCooldownEndsAt().toString());
        return pd;
    }

    @ExceptionHandler(MemberSeatLimitReachedException.class)
    public ProblemDetail handleSeatLimit(MemberSeatLimitReachedException e, HttpServletRequest req) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
        pd.setTitle("Seat Limit Reached");
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("code", "SEAT_LIMIT_REACHED");
        pd.setProperty("seatLimit", e.getSeatLimit());
        return pd;
    }

    @ExceptionHandler(InvitationAlreadyPendingException.class)
    public ProblemDetail handleAlreadyPending(InvitationAlreadyPendingException e, HttpServletRequest req) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
        pd.setTitle("Invitation Already Pending");
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("code", "INVITATION_ALREADY_PENDING");
        return pd;
    }

    @ExceptionHandler(InvitationExpiredException.class)
    public ProblemDetail handleExpired(InvitationExpiredException e, HttpServletRequest req) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.GONE, e.getMessage());
        pd.setTitle("Invitation Expired");
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("code", "INVITATION_EXPIRED");
        return pd;
    }

    @ExceptionHandler(InvitationNotFoundException.class)
    public ProblemDetail handleInviteNotFound(InvitationNotFoundException e, HttpServletRequest req) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
        pd.setTitle("Invitation Not Found");
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("code", "INVITATION_NOT_FOUND");
        return pd;
    }

    @ExceptionHandler(LeaderCannotLeaveException.class)
    public ProblemDetail handleLeaderLeave(LeaderCannotLeaveException e, HttpServletRequest req) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
        pd.setTitle("Leader Cannot Leave");
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("code", "LEADER_CANNOT_LEAVE");
        return pd;
    }

    @ExceptionHandler(CannotRemoveLeaderException.class)
    public ProblemDetail handleRemoveLeader(CannotRemoveLeaderException e, HttpServletRequest req) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
        pd.setTitle("Cannot Remove Leader");
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("code", "CANNOT_REMOVE_LEADER");
        return pd;
    }

    @ExceptionHandler(LeaderTransferTargetNotMemberException.class)
    public ProblemDetail handleTransferTarget(LeaderTransferTargetNotMemberException e, HttpServletRequest req) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
        pd.setTitle("Transfer Target Not A Member");
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("code", "TRANSFER_TARGET_NOT_MEMBER");
        return pd;
    }

    /**
     * Surfaces {@link MemberNotInGroupException} as 400 Bad Request. Thrown by the
     * leader-side bulk commission edit path (Task 29, spec §6.7) when a batch entry
     * references a member-public-id that does not exist within the addressed group.
     * Mapped to 400 (rather than 404) because the offending value is part of the request
     * body, not the URL path — the caller fixes the bad payload row and resubmits.
     */
    @ExceptionHandler(MemberNotInGroupException.class)
    public ProblemDetail handleMemberNotInGroup(MemberNotInGroupException e, HttpServletRequest req) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
        pd.setTitle("Member Not In Group");
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("code", "MEMBER_NOT_IN_GROUP");
        if (e.getMemberPublicId() != null) {
            pd.setProperty("memberPublicId", e.getMemberPublicId().toString());
        }
        return pd;
    }

    @ExceptionHandler(RealtyGroupPermissionDeniedException.class)
    public ProblemDetail handlePermissionDenied(RealtyGroupPermissionDeniedException e, HttpServletRequest req) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, e.getMessage());
        pd.setTitle("Realty Group Permission Denied");
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("code", "REALTY_GROUP_PERMISSION_DENIED");
        if (e.getMissingPermission() != null) {
            pd.setProperty("missingPermission", e.getMissingPermission().name());
        }
        return pd;
    }

    @ExceptionHandler(GroupDissolvedException.class)
    public ProblemDetail handleDissolved(GroupDissolvedException e, HttpServletRequest req) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.GONE, e.getMessage());
        pd.setTitle("Group Dissolved");
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("code", "GROUP_DISSOLVED");
        return pd;
    }

    @ExceptionHandler(ActiveListingsBlockDissolveException.class)
    public ProblemDetail handleActiveListingsBlock(
            ActiveListingsBlockDissolveException e, HttpServletRequest req) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
        pd.setTitle("Group Has Active Listings");
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("code", "GROUP_HAS_ACTIVE_LISTINGS");
        return pd;
    }

    @ExceptionHandler(InvalidWebsiteUrlException.class)
    public ProblemDetail handleBadUrl(InvalidWebsiteUrlException e, HttpServletRequest req) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
        pd.setTitle("Invalid Website URL");
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("code", "INVALID_WEBSITE_URL");
        return pd;
    }

    @ExceptionHandler(AlreadyMemberException.class)
    public ProblemDetail handleAlreadyMember(AlreadyMemberException e, HttpServletRequest req) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
        pd.setTitle("Already A Member");
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("code", "ALREADY_MEMBER");
        return pd;
    }

    /**
     * Mirrors {@code UserExceptionHandler}'s handling of the same exception but lifts the
     * status to {@code 415} per spec §5.7 (the realty logo/cover upload endpoints surface
     * format rejection at the wire as {@code UNSUPPORTED_IMAGE_FORMAT}). The package-scoped
     * {@code @RestControllerAdvice} on this class wins over the user-package handler for
     * any exception thrown from a realty controller.
     */
    @ExceptionHandler(UnsupportedImageFormatException.class)
    public ProblemDetail handleUnsupportedImageFormat(
            UnsupportedImageFormatException e, HttpServletRequest req) {
        log.warn("Realty image upload rejected: {}", e.getMessage());
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                "Upload must be a JPEG, PNG, or WebP image.");
        pd.setTitle("Unsupported Image Format");
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("code", "UNSUPPORTED_IMAGE_FORMAT");
        return pd;
    }

    @ExceptionHandler(RealtyGroupImageNotFoundException.class)
    public ProblemDetail handleImageNotFound(
            RealtyGroupImageNotFoundException e, HttpServletRequest req) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
        pd.setTitle("Realty Group Image Not Found");
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("code", "REALTY_GROUP_IMAGE_NOT_FOUND");
        pd.setProperty("groupPublicId", e.getGroupPublicId().toString());
        pd.setProperty("kind", e.getKind().name());
        return pd;
    }

    // -------------------------------------------------------------------------
    // Sub-project D — group wallet exceptions (spec §5.5, §9.1)
    // -------------------------------------------------------------------------

    @ExceptionHandler(InsufficientGroupBalanceException.class)
    public ProblemDetail handleInsufficientGroupBalance(
            InsufficientGroupBalanceException e, HttpServletRequest req) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
        pd.setTitle("Insufficient Group Balance");
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("code", "INSUFFICIENT_GROUP_BALANCE");
        pd.setProperty("available", e.getAvailable());
        pd.setProperty("requested", e.getRequested());
        return pd;
    }

    @ExceptionHandler(LeaderTermsNotAcceptedException.class)
    public ProblemDetail handleLeaderTermsNotAccepted(
            LeaderTermsNotAcceptedException e, HttpServletRequest req) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
        pd.setTitle("Leader Terms Not Accepted");
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("code", "LEADER_TERMS_NOT_ACCEPTED");
        pd.setProperty("leaderPublicId", e.getLeaderPublicId().toString());
        return pd;
    }

    @ExceptionHandler(LeaderFrozenException.class)
    public ProblemDetail handleLeaderFrozen(LeaderFrozenException e, HttpServletRequest req) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
        pd.setTitle("Leader Frozen");
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("code", "LEADER_FROZEN");
        return pd;
    }

    @ExceptionHandler(GroupHasNonzeroBalanceException.class)
    public ProblemDetail handleGroupHasNonzeroBalance(
            GroupHasNonzeroBalanceException e, HttpServletRequest req) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
        pd.setTitle("Group Has Nonzero Balance");
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("code", "GROUP_HAS_NONZERO_BALANCE");
        return pd;
    }

    @ExceptionHandler(GroupHasInFlightEscrowsException.class)
    public ProblemDetail handleGroupHasInFlightEscrows(
            GroupHasInFlightEscrowsException e, HttpServletRequest req) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
        pd.setTitle("Group Has In-Flight Escrows");
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("code", "GROUP_HAS_INFLIGHT_ESCROWS");
        return pd;
    }

    // -------------------------------------------------------------------------
    // Sub-project E — SL group registration exceptions (spec §5.1, §5.5, §7)
    // -------------------------------------------------------------------------

    /**
     * Mirrors {@code SlExceptionHandler.handleInvalidHeaders} for the realty
     * package. {@code SlGroupVerifyController} (in
     * {@code com.slparcelauctions.backend.realty.slgroup}) calls
     * {@link com.slparcelauctions.backend.sl.SlHeaderValidator#validate} per-handler
     * because there is no global in-world filter; the throw must therefore be
     * mapped here so the LSL caller sees {@code 403 SL_INVALID_HEADERS} (same
     * wire shape as the other {@code /api/v1/sl/**} endpoints) instead of
     * falling through to the 500 catch-all.
     */
    @ExceptionHandler(InvalidSlHeadersException.class)
    public ProblemDetail handleInvalidSlHeaders(InvalidSlHeadersException e, HttpServletRequest req) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, e.getMessage());
        pd.setType(URI.create("https://slpa.example/problems/sl/invalid-headers"));
        pd.setTitle("Invalid SL headers");
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("code", "SL_INVALID_HEADERS");
        return pd;
    }

    @ExceptionHandler(SlGroupAlreadyRegisteredException.class)
    public ProblemDetail handleSlGroupAlreadyRegistered(
            SlGroupAlreadyRegisteredException e, HttpServletRequest req) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
        pd.setTitle("SL Group Already Registered");
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("code", SlGroupAlreadyRegisteredException.CODE);
        if (e.getSlGroupUuid() != null) {
            pd.setProperty("slGroupUuid", e.getSlGroupUuid().toString());
        }
        return pd;
    }

    @ExceptionHandler(SlGroupNotVerifiedException.class)
    public ProblemDetail handleSlGroupNotVerified(
            SlGroupNotVerifiedException e, HttpServletRequest req) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
        pd.setTitle("SL Group Not Verified");
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("code", SlGroupNotVerifiedException.CODE);
        if (e.getPublicId() != null) {
            pd.setProperty("publicId", e.getPublicId().toString());
        }
        return pd;
    }

    @ExceptionHandler(SlGroupVerificationExpiredException.class)
    public ProblemDetail handleSlGroupVerificationExpired(
            SlGroupVerificationExpiredException e, HttpServletRequest req) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.GONE, e.getMessage());
        pd.setTitle("SL Group Verification Expired");
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("code", SlGroupVerificationExpiredException.CODE);
        if (e.getPublicId() != null) {
            pd.setProperty("publicId", e.getPublicId().toString());
        }
        return pd;
    }

    @ExceptionHandler(SlGroupFounderMismatchException.class)
    public ProblemDetail handleSlGroupFounderMismatch(
            SlGroupFounderMismatchException e, HttpServletRequest req) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
        pd.setTitle("SL Group Founder Mismatch");
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("code", SlGroupFounderMismatchException.CODE);
        if (e.getReportedAvatarUuid() != null) {
            pd.setProperty("reportedAvatarUuid", e.getReportedAvatarUuid().toString());
        }
        if (e.getExpectedFounderUuid() != null) {
            pd.setProperty("expectedFounderUuid", e.getExpectedFounderUuid().toString());
        }
        return pd;
    }

    @ExceptionHandler(ParcelNotOwnedByRegisteredSlGroupException.class)
    public ProblemDetail handleParcelNotOwnedByRegisteredSlGroup(
            ParcelNotOwnedByRegisteredSlGroupException e, HttpServletRequest req) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
        pd.setTitle("Parcel Not Owned By Registered SL Group");
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("code", ParcelNotOwnedByRegisteredSlGroupException.CODE);
        if (e.getParcelUuid() != null) {
            pd.setProperty("parcelUuid", e.getParcelUuid().toString());
        }
        if (e.getRealtyGroupPublicId() != null) {
            pd.setProperty("realtyGroupPublicId", e.getRealtyGroupPublicId().toString());
        }
        return pd;
    }

    @ExceptionHandler(RegisteredSlGroupHasListingsException.class)
    public ProblemDetail handleRegisteredSlGroupHasListings(
            RegisteredSlGroupHasListingsException e, HttpServletRequest req) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
        pd.setTitle("Registered SL Group Has Listings");
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("code", RegisteredSlGroupHasListingsException.CODE);
        if (e.getSlGroupPublicId() != null) {
            pd.setProperty("slGroupPublicId", e.getSlGroupPublicId().toString());
        }
        return pd;
    }

    @ExceptionHandler(SlGroupRegisteredBlocksDissolveException.class)
    public ProblemDetail handleSlGroupRegisteredBlocksDissolve(
            SlGroupRegisteredBlocksDissolveException e, HttpServletRequest req) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
        pd.setTitle("SL Groups Block Dissolve");
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("code", SlGroupRegisteredBlocksDissolveException.CODE);
        if (e.getRealtyGroupPublicId() != null) {
            pd.setProperty("realtyGroupPublicId", e.getRealtyGroupPublicId().toString());
        }
        pd.setProperty("count", e.getCount());
        return pd;
    }

    /**
     * Surfaces {@link SlGroupNotFoundException} as 404 Not Found. Thrown by the
     * admin SL group moderation paths (recheck / ack-drift / force-unregister)
     * when a public id resolves to nothing, and by the cross-tenant guard when
     * an SL group registration is owned by a different realty group than the
     * one named in the URL.
     */
    @ExceptionHandler(SlGroupNotFoundException.class)
    public ProblemDetail handleSlGroupNotFound(
            SlGroupNotFoundException e, HttpServletRequest req) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
        pd.setTitle("SL Group Not Found");
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("code", SlGroupNotFoundException.CODE);
        if (e.getPublicId() != null) {
            pd.setProperty("publicId", e.getPublicId().toString());
        }
        return pd;
    }

    /**
     * Surfaces {@link NoDriftDetectedException} as 409 Conflict. Thrown by the
     * admin drift-ack path when the row has no drift to acknowledge (idempotent
     * second ack, or admin clicked ack on a row that was never flagged). The
     * mapping is 409 rather than 422 because the request is well-formed; what's
     * in conflict is the row's current state.
     */
    @ExceptionHandler(NoDriftDetectedException.class)
    public ProblemDetail handleNoDriftDetected(
            NoDriftDetectedException e, HttpServletRequest req) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
        pd.setTitle("No Drift Detected");
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("code", NoDriftDetectedException.CODE);
        if (e.getSlGroupPublicId() != null) {
            pd.setProperty("slGroupPublicId", e.getSlGroupPublicId().toString());
        }
        return pd;
    }

    // -------------------------------------------------------------------------
    // Sub-project F — admin moderation: suspension/ban guard rejection (spec §5.2)
    // -------------------------------------------------------------------------

    /**
     * Surfaces {@link RealtyGroupGuard}-rejected operations as 409 Conflict with
     * a {@code ProblemDetail} body that names the suspension status (SUSPENDED or
     * BANNED), the expiry timestamp (null for permanent ban), and the reason.
     * Frontend uses the {@code groupStatus} field to render a guard banner.
     */
    @ExceptionHandler(RealtyGroupSuspendedException.class)
    public ProblemDetail handleRealtyGroupSuspended(
            RealtyGroupSuspendedException e, HttpServletRequest req) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
        pd.setType(URI.create("https://slpa.example/problems/realty/group-suspended"));
        pd.setTitle("Realty group is suspended or banned");
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("code", "REALTY_GROUP_SUSPENDED");
        pd.setProperty("groupStatus", e.getStatus().name());
        pd.setProperty("expiresAt", e.getExpiresAt() == null ? null : e.getExpiresAt().toString());
        pd.setProperty("reason", e.getReason());
        return pd;
    }

    /**
     * Surfaces {@link SuspensionAlreadyActiveException} as 409 Conflict. Thrown when
     * an admin tries to issue a new suspension against a group that already has an
     * active (unlifted, unexpired) suspension row.
     */
    @ExceptionHandler(SuspensionAlreadyActiveException.class)
    public ProblemDetail handleSuspensionAlreadyActive(
            SuspensionAlreadyActiveException e, HttpServletRequest req) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
        pd.setTitle("Suspension Already Active");
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("code", "SUSPENSION_ALREADY_ACTIVE");
        if (e.getGroupPublicId() != null) {
            pd.setProperty("groupPublicId", e.getGroupPublicId().toString());
        }
        return pd;
    }

    /**
     * Surfaces {@link SuspensionNotFoundException} as 404 Not Found. Thrown when a
     * lift operation references a suspension that does not exist, has already been
     * lifted, or does not belong to the addressed realty group.
     */
    @ExceptionHandler(SuspensionNotFoundException.class)
    public ProblemDetail handleSuspensionNotFound(
            SuspensionNotFoundException e, HttpServletRequest req) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
        pd.setTitle("Suspension Not Found");
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("code", "SUSPENSION_NOT_FOUND");
        if (e.getSuspensionPublicId() != null) {
            pd.setProperty("suspensionPublicId", e.getSuspensionPublicId().toString());
        }
        return pd;
    }

    /**
     * Surfaces {@link ReportNotFoundException} as 404 Not Found. Thrown by
     * {@code RealtyGroupReportService.find/resolve/dismiss} when the supplied
     * report public id resolves to nothing or the row is already triaged
     * (resolve/dismiss only act on OPEN rows; non-OPEN rows surface as not-found
     * so the admin UI shows a consistent stale-row state).
     */
    @ExceptionHandler(ReportNotFoundException.class)
    public ProblemDetail handleReportNotFound(
            ReportNotFoundException e, HttpServletRequest req) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
        pd.setType(URI.create("https://slpa.example/problems/realty/report-not-found"));
        pd.setTitle("Report Not Found");
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("code", "REPORT_NOT_FOUND");
        if (e.getReportPublicId() != null) {
            pd.setProperty("reportPublicId", e.getReportPublicId().toString());
        }
        return pd;
    }

    // -------------------------------------------------------------------------
    // Sub-project F — public report submission exceptions (spec §6.1, §12.1)
    // -------------------------------------------------------------------------

    /**
     * Surfaces {@link AlreadyReportedException} as 409 Conflict. Thrown when a
     * reporter races a second submission past the in-service pre-check and trips
     * the {@code uq_rg_reports_one_open_per_reporter} partial-unique index, or
     * when the pre-check itself caught the existing OPEN row (post-Task 16, the
     * service exclusively translates the {@code DataIntegrityViolationException}
     * path — the pre-check raises this exception too if it gets added).
     */
    @ExceptionHandler(AlreadyReportedException.class)
    public ProblemDetail handleAlreadyReported(
            AlreadyReportedException e, HttpServletRequest req) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
        pd.setType(URI.create("https://slpa.example/problems/realty/already-reported"));
        pd.setTitle("Already Reported");
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("code", "ALREADY_REPORTED");
        if (e.getGroupPublicId() != null) {
            pd.setProperty("groupPublicId", e.getGroupPublicId().toString());
        }
        return pd;
    }

    /**
     * Surfaces {@link CannotReportOwnGroupException} as 409 Conflict. Members
     * (including the leader) of a realty group cannot file user-reports against
     * their own group — they have richer recourse paths (fraud flags, leadership
     * transfer, dispute the listing) and shouldn't funnel internal disputes
     * through the public report queue. Mapped here instead of 422 because the
     * reporter and group are both perfectly valid; what's in conflict is the
     * implied relationship (membership) between them.
     */
    @ExceptionHandler(CannotReportOwnGroupException.class)
    public ProblemDetail handleCannotReportOwnGroup(
            CannotReportOwnGroupException e, HttpServletRequest req) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
        pd.setType(URI.create("https://slpa.example/problems/realty/cannot-report-own-group"));
        pd.setTitle("Cannot Report Own Group");
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("code", "CANNOT_REPORT_OWN_GROUP");
        if (e.getGroupPublicId() != null) {
            pd.setProperty("groupPublicId", e.getGroupPublicId().toString());
        }
        return pd;
    }

    /**
     * Surfaces {@link ReportRateLimitedException} as 429 Too Many Requests. Sets
     * a {@code Retry-After} header (delta-seconds form) pointing to the next
     * UTC-midnight bucket reset — that's when {@link com.slparcelauctions.backend
     * .realty.reports.RealtyGroupReportRateLimiter}'s {@code yyyy-mm-dd}-keyed
     * counter rolls over. The body's {@code retryAfterSeconds} mirrors the
     * header so callers that parse the JSON envelope (instead of the header) get
     * the same value.
     *
     * <p>Returns {@link ResponseEntity} (not bare {@link ProblemDetail}) so the
     * header can ride alongside the body; explicit {@code Content-Type:
     * application/problem+json} preserves the wire convention the rest of the
     * handler shares via the default {@code ProblemDetail} return type.
     */
    @ExceptionHandler(ReportRateLimitedException.class)
    public ResponseEntity<ProblemDetail> handleReportRateLimited(
            ReportRateLimitedException e, HttpServletRequest req) {
        // Uses Clock.systemUTC() inline rather than an injected Clock so this handler
        // remains constructible without DI — keeps @WebMvcTest slice tests that
        // @Import this advice from blowing up on context start. Production behavior
        // is unchanged; the 429 path itself is exercised via service-layer mocks in
        // RealtyGroupReportControllerSliceTest, so test-determinism is preserved.
        Clock clock = Clock.systemUTC();
        OffsetDateTime now = OffsetDateTime.now(clock);
        OffsetDateTime nextResetUtc = LocalDate.now(clock)
            .plusDays(1)
            .atStartOfDay()
            .atOffset(ZoneOffset.UTC);
        long retryAfterSeconds = Math.max(1L, Duration.between(now, nextResetUtc).getSeconds());

        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS, e.getMessage());
        pd.setType(URI.create("https://slpa.example/problems/realty/report-rate-limited"));
        pd.setTitle("Report Rate Limited");
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("code", "REPORT_RATE_LIMITED");
        pd.setProperty("dailyLimit", e.getDailyLimit());
        pd.setProperty("retryAfterSeconds", retryAfterSeconds);

        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
            .header(HttpHeaders.RETRY_AFTER, Long.toString(retryAfterSeconds))
            .contentType(MediaType.APPLICATION_PROBLEM_JSON)
            .body(pd);
    }
}
