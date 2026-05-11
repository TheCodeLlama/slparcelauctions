package com.slparcelauctions.backend.realty.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.slparcelauctions.backend.auth.AuthPrincipal;
import com.slparcelauctions.backend.realty.InvitationStatus;
import com.slparcelauctions.backend.realty.RealtyGroup;
import com.slparcelauctions.backend.realty.RealtyGroupInvitation;
import com.slparcelauctions.backend.realty.RealtyGroupInvitationRepository;
import com.slparcelauctions.backend.realty.RealtyGroupMember;
import com.slparcelauctions.backend.realty.RealtyGroupRepository;
import com.slparcelauctions.backend.realty.dto.InvitationDto;
import com.slparcelauctions.backend.realty.dto.RealtyGroupDtoMapper;
import com.slparcelauctions.backend.realty.dto.RealtyGroupSummaryDto;
import com.slparcelauctions.backend.realty.service.RealtyGroupInvitationService;

import lombok.RequiredArgsConstructor;

/**
 * Caller-self surface for invitations + "my groups". Mounted under {@code /api/v1/me}.
 *
 * <ul>
 *   <li>{@code GET /invitations} — caller's PENDING invitations only (spec §5.4).</li>
 *   <li>{@code POST /invitations/{id}/accept} — flips the invitation to ACCEPTED and
 *       returns the freshly-joined group's summary card.</li>
 *   <li>{@code POST /invitations/{id}/decline} — flips the invitation to DECLINED;
 *       returns 204.</li>
 *   <li>{@code GET /realty-groups} — every active group the caller belongs to (leader or
 *       agent), newest first. Dashboard convenience per spec §5.5.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/me")
@RequiredArgsConstructor
public class MeRealtyGroupController {

    private final RealtyGroupInvitationService invitationService;
    private final RealtyGroupInvitationRepository invitations;
    private final RealtyGroupRepository groups;
    private final RealtyGroupDtoMapper mapper;

    @GetMapping("/invitations")
    @Transactional(readOnly = true)
    public List<InvitationDto> myInvitations(@AuthenticationPrincipal AuthPrincipal principal) {
        List<RealtyGroupInvitation> rows = invitations
            .findByInvitedUserIdAndStatusOrderByCreatedAtDesc(principal.userId(), InvitationStatus.PENDING);
        return mapper.toInvitationDtos(rows);
    }

    @PostMapping("/invitations/{invitationPublicId}/accept")
    @Transactional
    public RealtyGroupSummaryDto acceptInvitation(
            @PathVariable UUID invitationPublicId,
            @AuthenticationPrincipal AuthPrincipal principal) {
        RealtyGroupMember member = invitationService.accept(invitationPublicId, principal.userId());
        RealtyGroup group = groups.findById(member.getGroupId()).orElseThrow();
        return mapper.toSummaryDto(group);
    }

    @PostMapping("/invitations/{invitationPublicId}/decline")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void declineInvitation(
            @PathVariable UUID invitationPublicId,
            @AuthenticationPrincipal AuthPrincipal principal) {
        invitationService.decline(invitationPublicId, principal.userId());
    }

    @GetMapping("/realty-groups")
    @Transactional(readOnly = true)
    public List<RealtyGroupSummaryDto> myGroups(@AuthenticationPrincipal AuthPrincipal principal) {
        List<RealtyGroup> rows = groups.findActiveByMemberUserId(principal.userId());
        return rows.stream().map(mapper::toSummaryDto).toList();
    }
}
