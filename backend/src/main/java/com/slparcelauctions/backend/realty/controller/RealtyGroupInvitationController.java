package com.slparcelauctions.backend.realty.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.slparcelauctions.backend.auth.AuthPrincipal;
import com.slparcelauctions.backend.realty.RealtyGroupInvitation;
import com.slparcelauctions.backend.realty.dto.CreateInvitationRequest;
import com.slparcelauctions.backend.realty.dto.InvitationDto;
import com.slparcelauctions.backend.realty.dto.RealtyGroupDtoMapper;
import com.slparcelauctions.backend.realty.service.RealtyGroupInvitationService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * Group-scoped invitation surface. All three endpoints are
 * {@code INVITE_AGENTS}-gated (the leader passes implicitly via the authorizer); the
 * service layer enforces.
 *
 * <p>{@code GET} lives here rather than on {@link RealtyGroupPublicController} because
 * pending invitations are not anonymous-safe: the invitee's identity, the inviter's
 * identity, and the proposed permission set are all leader/delegate-internal.
 */
@RestController
@RequestMapping("/api/v1/realty-groups/{publicId}/invitations")
@RequiredArgsConstructor
public class RealtyGroupInvitationController {

    private final RealtyGroupInvitationService invitationService;
    private final RealtyGroupDtoMapper mapper;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public InvitationDto invite(
            @PathVariable UUID publicId,
            @AuthenticationPrincipal AuthPrincipal principal,
            @Valid @RequestBody CreateInvitationRequest req) {
        RealtyGroupInvitation inv = invitationService.invite(publicId, req, principal.userId());
        return mapper.toInvitationDto(inv);
    }

    @GetMapping
    @Transactional(readOnly = true)
    public List<InvitationDto> list(
            @PathVariable UUID publicId,
            @AuthenticationPrincipal AuthPrincipal principal) {
        List<RealtyGroupInvitation> rows = invitationService.listForGroup(publicId, principal.userId());
        return mapper.toInvitationDtos(rows);
    }

    @DeleteMapping("/{invitationPublicId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public ResponseEntity<Void> revoke(
            @PathVariable UUID publicId,
            @PathVariable UUID invitationPublicId,
            @AuthenticationPrincipal AuthPrincipal principal) {
        invitationService.revoke(publicId, invitationPublicId, principal.userId());
        return ResponseEntity.noContent().build();
    }
}
