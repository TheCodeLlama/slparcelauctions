package com.slparcelauctions.backend.realty.exception;

import java.net.URI;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

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
}
