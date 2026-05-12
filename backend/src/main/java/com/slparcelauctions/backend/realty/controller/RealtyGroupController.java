package com.slparcelauctions.backend.realty.controller;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.slparcelauctions.backend.auth.AuthPrincipal;
import com.slparcelauctions.backend.realty.RealtyGroup;
import com.slparcelauctions.backend.realty.RealtyGroupMember;
import com.slparcelauctions.backend.realty.dto.AgentCardDto;
import com.slparcelauctions.backend.realty.dto.CreateRealtyGroupRequest;
import com.slparcelauctions.backend.realty.dto.RealtyGroupDtoMapper;
import com.slparcelauctions.backend.realty.dto.RealtyGroupPublicDto;
import com.slparcelauctions.backend.realty.dto.TransferLeadershipRequest;
import com.slparcelauctions.backend.realty.dto.UpdatePermissionsRequest;
import com.slparcelauctions.backend.realty.dto.UpdateRealtyGroupRequest;
import com.slparcelauctions.backend.realty.service.RealtyGroupMembershipService;
import com.slparcelauctions.backend.realty.service.RealtyGroupService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * Realty-group mutation surface. Public reads live on
 * {@link RealtyGroupPublicController}; member-self / "my groups" reads on
 * {@link MeRealtyGroupController}; group-scoped invitations on
 * {@link RealtyGroupInvitationController}; admin endpoints in the {@code admin} package.
 *
 * <p>Authorization gates (leader / EDIT_GROUP_PROFILE / REMOVE_AGENTS / leader-only
 * permission edits) are enforced in the service layer via {@code RealtyGroupAuthorizer}; the
 * controller's job is wire-binding + DTO assembly.
 */
@RestController
@RequestMapping("/api/v1/realty-groups")
@RequiredArgsConstructor
public class RealtyGroupController {

    private final RealtyGroupService groupService;
    private final RealtyGroupMembershipService membershipService;
    private final RealtyGroupDtoMapper mapper;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public RealtyGroupPublicDto create(
            @AuthenticationPrincipal AuthPrincipal principal,
            @Valid @RequestBody CreateRealtyGroupRequest req) {
        RealtyGroup created = groupService.createGroup(req, principal.userId());
        // Creator sees the full payload (they're the leader); admin flag is moot here but pass
        // through for symmetry with the public endpoint.
        return mapper.toPublicDto(created, principal.userId(), principal.role() == com.slparcelauctions.backend.user.Role.ADMIN);
    }

    @PatchMapping("/{publicId}")
    @Transactional
    public RealtyGroupPublicDto update(
            @PathVariable UUID publicId,
            @AuthenticationPrincipal AuthPrincipal principal,
            @Valid @RequestBody UpdateRealtyGroupRequest req) {
        RealtyGroup updated = groupService.updateGroup(publicId, req, principal.userId());
        return mapper.toPublicDto(updated, principal.userId(),
            principal.role() == com.slparcelauctions.backend.user.Role.ADMIN);
    }

    @DeleteMapping("/{publicId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void dissolve(
            @PathVariable UUID publicId,
            @AuthenticationPrincipal AuthPrincipal principal) {
        groupService.dissolveGroup(publicId, principal.userId());
    }

    @PostMapping("/{publicId}/leave")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void leave(
            @PathVariable UUID publicId,
            @AuthenticationPrincipal AuthPrincipal principal) {
        membershipService.leave(publicId, principal.userId());
    }

    @PostMapping("/{publicId}/transfer-leadership")
    @Transactional
    public RealtyGroupPublicDto transferLeadership(
            @PathVariable UUID publicId,
            @AuthenticationPrincipal AuthPrincipal principal,
            @Valid @RequestBody TransferLeadershipRequest req) {
        membershipService.transferLeadership(publicId, req, principal.userId());
        // Re-read so the response carries the new leader_id; the service mutates the group
        // in place but does not return the entity directly.
        RealtyGroup updated = groupService.loadActiveByPublicId(publicId);
        return mapper.toPublicDto(updated, principal.userId(),
            principal.role() == com.slparcelauctions.backend.user.Role.ADMIN);
    }

    @DeleteMapping("/{publicId}/members/{memberPublicId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void removeMember(
            @PathVariable UUID publicId,
            @PathVariable UUID memberPublicId,
            @AuthenticationPrincipal AuthPrincipal principal) {
        membershipService.removeMember(publicId, memberPublicId, principal.userId());
    }

    @PatchMapping("/{publicId}/members/{memberPublicId}/permissions")
    @Transactional
    public ResponseEntity<AgentCardDto> updateMemberPermissions(
            @PathVariable UUID publicId,
            @PathVariable UUID memberPublicId,
            @AuthenticationPrincipal AuthPrincipal principal,
            @Valid @RequestBody UpdatePermissionsRequest req) {
        RealtyGroupMember updated = groupService.updateMemberPermissions(
            publicId, memberPublicId, req.permissions(), req.agentCommissionRate(),
            principal.userId());
        RealtyGroup group = groupService.loadActiveByPublicId(publicId);
        return ResponseEntity.ok(mapper.toAgentCard(group, updated));
    }
}
