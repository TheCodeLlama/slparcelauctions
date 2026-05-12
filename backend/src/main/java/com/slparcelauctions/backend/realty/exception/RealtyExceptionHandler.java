package com.slparcelauctions.backend.realty.exception;

import java.net.URI;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.slparcelauctions.backend.realty.moderation.exception.RealtyGroupSuspendedException;
import com.slparcelauctions.backend.realty.slgroup.exception.ParcelNotOwnedByRegisteredSlGroupException;
import com.slparcelauctions.backend.realty.slgroup.exception.RegisteredSlGroupHasListingsException;
import com.slparcelauctions.backend.realty.slgroup.exception.SlGroupAlreadyRegisteredException;
import com.slparcelauctions.backend.realty.slgroup.exception.SlGroupFounderMismatchException;
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
}
