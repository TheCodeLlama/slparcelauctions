package com.slparcelauctions.backend.admin.users;

import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.slparcelauctions.backend.admin.users.dto.AdminUserActionRequest;
import com.slparcelauctions.backend.admin.users.dto.AdminUserBidRowDto;
import com.slparcelauctions.backend.admin.users.dto.AdminUserCancellationRowDto;
import com.slparcelauctions.backend.admin.users.dto.AdminUserDetailDto;
import com.slparcelauctions.backend.admin.users.dto.AdminUserFraudFlagRowDto;
import com.slparcelauctions.backend.admin.users.dto.AdminUserListingRowDto;
import com.slparcelauctions.backend.admin.users.dto.AdminUserModerationRowDto;
import com.slparcelauctions.backend.admin.users.dto.AdminUserReportRowDto;
import com.slparcelauctions.backend.admin.users.dto.AdminUserSummaryDto;
import com.slparcelauctions.backend.admin.users.dto.UserIpProjection;
import com.slparcelauctions.backend.auth.AuthPrincipal;
import com.slparcelauctions.backend.common.PagedResponse;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserNotFoundException;
import com.slparcelauctions.backend.user.UserRepository;
import com.slparcelauctions.backend.user.deletion.AdminUserDeletionRequest;
import com.slparcelauctions.backend.user.deletion.UserDeletionService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/admin/users")
@RequiredArgsConstructor
public class AdminUserController {

    private static final int MAX_PAGE_SIZE = 100;

    private final AdminUserService userService;
    private final AdminRoleService roleService;
    private final UserDeletionService userDeletionService;
    private final UserRepository userRepository;

    @GetMapping
    public PagedResponse<AdminUserSummaryDto> search(
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return userService.search(search, PageRequest.of(page, Math.min(size, MAX_PAGE_SIZE)));
    }

    @GetMapping("/{publicId}")
    public AdminUserDetailDto detail(@PathVariable UUID publicId) {
        return userService.detail(resolveUserId(publicId));
    }

    @GetMapping("/{publicId}/listings")
    public PagedResponse<AdminUserListingRowDto> listings(
            @PathVariable UUID publicId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return userService.listings(resolveUserId(publicId), PageRequest.of(page, Math.min(size, MAX_PAGE_SIZE)));
    }

    @GetMapping("/{publicId}/bids")
    public PagedResponse<AdminUserBidRowDto> bids(
            @PathVariable UUID publicId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return userService.bids(resolveUserId(publicId), PageRequest.of(page, Math.min(size, MAX_PAGE_SIZE)));
    }

    @GetMapping("/{publicId}/cancellations")
    public PagedResponse<AdminUserCancellationRowDto> cancellations(
            @PathVariable UUID publicId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return userService.cancellations(resolveUserId(publicId), PageRequest.of(page, Math.min(size, MAX_PAGE_SIZE)));
    }

    @GetMapping("/{publicId}/reports")
    public PagedResponse<AdminUserReportRowDto> reports(
            @PathVariable UUID publicId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return userService.reports(resolveUserId(publicId), PageRequest.of(page, Math.min(size, MAX_PAGE_SIZE)));
    }

    @GetMapping("/{publicId}/fraud-flags")
    public PagedResponse<AdminUserFraudFlagRowDto> fraudFlags(
            @PathVariable UUID publicId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return userService.fraudFlags(resolveUserId(publicId), PageRequest.of(page, Math.min(size, MAX_PAGE_SIZE)));
    }

    @GetMapping("/{publicId}/moderation")
    public PagedResponse<AdminUserModerationRowDto> moderation(
            @PathVariable UUID publicId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return userService.moderation(resolveUserId(publicId), PageRequest.of(page, Math.min(size, MAX_PAGE_SIZE)));
    }

    @GetMapping("/{publicId}/ips")
    public List<UserIpProjection> ips(@PathVariable UUID publicId) {
        return userService.ips(resolveUserId(publicId));
    }

    @PostMapping("/{publicId}/promote")
    public void promote(
            @PathVariable UUID publicId,
            @Valid @RequestBody AdminUserActionRequest body,
            @AuthenticationPrincipal AuthPrincipal admin) {
        roleService.promote(resolveUserId(publicId), admin.userId(), body.notes());
    }

    @PostMapping("/{publicId}/demote")
    public void demote(
            @PathVariable UUID publicId,
            @Valid @RequestBody AdminUserActionRequest body,
            @AuthenticationPrincipal AuthPrincipal admin) {
        roleService.demote(resolveUserId(publicId), admin.userId(), body.notes());
    }

    @PostMapping("/{publicId}/reset-frivolous-counter")
    public void resetCounter(
            @PathVariable UUID publicId,
            @Valid @RequestBody AdminUserActionRequest body,
            @AuthenticationPrincipal AuthPrincipal admin) {
        roleService.resetFrivolousCounter(resolveUserId(publicId), admin.userId(), body.notes());
    }

    @DeleteMapping("/{publicId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteUser(
            @PathVariable UUID publicId,
            @Valid @RequestBody AdminUserDeletionRequest body,
            @AuthenticationPrincipal AuthPrincipal admin) {
        userDeletionService.deleteByAdmin(resolveUserId(publicId), admin.userId(), body.adminNote());
    }

    private Long resolveUserId(UUID publicId) {
        return userRepository.findByPublicId(publicId)
                .map(User::getId)
                .orElseThrow(() -> new UserNotFoundException(publicId));
    }
}
