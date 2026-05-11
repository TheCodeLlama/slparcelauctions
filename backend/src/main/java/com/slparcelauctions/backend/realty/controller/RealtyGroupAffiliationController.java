package com.slparcelauctions.backend.realty.controller;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.slparcelauctions.backend.realty.RealtyGroup;
import com.slparcelauctions.backend.realty.RealtyGroupRepository;
import com.slparcelauctions.backend.realty.dto.RealtyGroupDtoMapper;
import com.slparcelauctions.backend.realty.dto.UserRealtyGroupAffiliationDto;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserNotFoundException;
import com.slparcelauctions.backend.user.UserRepository;

import lombok.RequiredArgsConstructor;

/**
 * Anonymous-safe endpoint that feeds the "Groups" section on a user's public profile
 * page. Lives in the realty slice (rather than {@code UserController}) to keep the
 * realty surface in its own package and to avoid a cross-slice import on a UI-shape DTO.
 *
 * <p>Unknown {@code publicId} → 404 (mapped via the existing
 * {@link UserNotFoundException} handler in the user slice's controller advice). Unlike
 * {@code /users/{id}/auctions} (which deliberately returns an empty page for unknown
 * users to avoid id enumeration), the affiliations endpoint distinguishes the two so the
 * frontend can show a clear "not found" rather than an empty section that misleads
 * readers about the user's group membership.
 *
 * <p>Cached for 60 s mirroring the public group detail endpoint's posture.
 */
@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class RealtyGroupAffiliationController {

    private final UserRepository users;
    private final RealtyGroupRepository groups;
    private final RealtyGroupDtoMapper mapper;

    @GetMapping("/{publicId}/realty-groups")
    @Transactional(readOnly = true)
    public ResponseEntity<List<UserRealtyGroupAffiliationDto>> listForUser(@PathVariable UUID publicId) {
        User user = users.findByPublicId(publicId)
            .orElseThrow(() -> new UserNotFoundException(publicId));
        List<RealtyGroup> rows = groups.findActiveByMemberUserId(user.getId());
        List<UserRealtyGroupAffiliationDto> body = rows.stream()
            .map(g -> mapper.toAffiliationDto(g, user.getId()))
            .toList();
        return ResponseEntity.ok()
            .cacheControl(CacheControl.maxAge(60, TimeUnit.SECONDS).cachePublic())
            .body(body);
    }
}
