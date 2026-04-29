package com.slparcelauctions.backend.admin.users;

import java.util.List;

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

    @GetMapping
    public PagedResponse<AdminUserSummaryDto> search(
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return userService.search(search, PageRequest.of(page, Math.min(size, MAX_PAGE_SIZE)));
    }

    @GetMapping("/{id}")
    public AdminUserDetailDto detail(@PathVariable Long id) {
        return userService.detail(id);
    }

    @GetMapping("/{id}/listings")
    public PagedResponse<AdminUserListingRowDto> listings(
            @PathVariable Long id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return userService.listings(id, PageRequest.of(page, Math.min(size, MAX_PAGE_SIZE)));
    }

    @GetMapping("/{id}/bids")
    public PagedResponse<AdminUserBidRowDto> bids(
            @PathVariable Long id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return userService.bids(id, PageRequest.of(page, Math.min(size, MAX_PAGE_SIZE)));
    }

    @GetMapping("/{id}/cancellations")
    public PagedResponse<AdminUserCancellationRowDto> cancellations(
            @PathVariable Long id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return userService.cancellations(id, PageRequest.of(page, Math.min(size, MAX_PAGE_SIZE)));
    }

    @GetMapping("/{id}/reports")
    public PagedResponse<AdminUserReportRowDto> reports(
            @PathVariable Long id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return userService.reports(id, PageRequest.of(page, Math.min(size, MAX_PAGE_SIZE)));
    }

    @GetMapping("/{id}/fraud-flags")
    public PagedResponse<AdminUserFraudFlagRowDto> fraudFlags(
            @PathVariable Long id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return userService.fraudFlags(id, PageRequest.of(page, Math.min(size, MAX_PAGE_SIZE)));
    }

    @GetMapping("/{id}/moderation")
    public PagedResponse<AdminUserModerationRowDto> moderation(
            @PathVariable Long id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return userService.moderation(id, PageRequest.of(page, Math.min(size, MAX_PAGE_SIZE)));
    }

    @GetMapping("/{id}/ips")
    public List<UserIpProjection> ips(@PathVariable Long id) {
        return userService.ips(id);
    }

    @PostMapping("/{id}/promote")
    public void promote(
            @PathVariable Long id,
            @Valid @RequestBody AdminUserActionRequest body,
            @AuthenticationPrincipal AuthPrincipal admin) {
        roleService.promote(id, admin.userId(), body.notes());
    }

    @PostMapping("/{id}/demote")
    public void demote(
            @PathVariable Long id,
            @Valid @RequestBody AdminUserActionRequest body,
            @AuthenticationPrincipal AuthPrincipal admin) {
        roleService.demote(id, admin.userId(), body.notes());
    }

    @PostMapping("/{id}/reset-frivolous-counter")
    public void resetCounter(
            @PathVariable Long id,
            @Valid @RequestBody AdminUserActionRequest body,
            @AuthenticationPrincipal AuthPrincipal admin) {
        roleService.resetFrivolousCounter(id, admin.userId(), body.notes());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteUser(
            @PathVariable Long id,
            @Valid @RequestBody AdminUserDeletionRequest body,
            @AuthenticationPrincipal AuthPrincipal admin) {
        userDeletionService.deleteByAdmin(id, admin.userId(), body.adminNote());
    }
}
