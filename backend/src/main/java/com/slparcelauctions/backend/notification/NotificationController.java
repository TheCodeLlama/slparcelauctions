package com.slparcelauctions.backend.notification;

import com.slparcelauctions.backend.auth.AuthPrincipal;
import com.slparcelauctions.backend.common.PagedResponse;
import com.slparcelauctions.backend.notification.dto.NotificationDto;
import com.slparcelauctions.backend.notification.dto.UnreadCountResponse;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.HashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/notifications")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
@Validated
public class NotificationController {

    private final NotificationService notificationService;
    private final NotificationRepository notificationRepository;

    @GetMapping
    public PagedResponse<NotificationDto> list(
        @AuthenticationPrincipal AuthPrincipal caller,
        @RequestParam(required = false) NotificationGroup group,
        @RequestParam(defaultValue = "false") boolean unreadOnly,
        @RequestParam(defaultValue = "0") @Min(0) int page,
        @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size
    ) {
        Page<NotificationDto> result = notificationService.listFor(
            caller.userId(), group, unreadOnly, PageRequest.of(page, size));
        return PagedResponse.from(result);
    }

    @GetMapping("/unread-count")
    public UnreadCountResponse unreadCount(
        @AuthenticationPrincipal AuthPrincipal caller,
        @RequestParam(required = false) String breakdown
    ) {
        long total = notificationService.unreadCount(caller.userId());
        if (!"group".equals(breakdown)) {
            return UnreadCountResponse.of(total);
        }
        Map<NotificationCategory, Long> byCategory =
            notificationService.unreadCountByCategory(caller.userId());
        Map<String, Long> byGroup = new HashMap<>();
        for (NotificationGroup g : NotificationGroup.values()) {
            byGroup.put(g.name().toLowerCase(), 0L);
        }
        byCategory.forEach((cat, count) ->
            byGroup.merge(cat.getGroup().name().toLowerCase(), count, Long::sum));
        return UnreadCountResponse.withBreakdown(total, byGroup);
    }

    @PutMapping("/{publicId}/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void markRead(
            @AuthenticationPrincipal AuthPrincipal caller,
            @PathVariable UUID publicId) {
        Notification notification = notificationRepository.findByPublicId(publicId)
                .orElseThrow(() -> new NoSuchElementException("notification not found"));
        notificationService.markRead(caller.userId(), notification.getId());
    }

    @PutMapping("/read-all")
    public Map<String, Integer> markAllRead(
        @AuthenticationPrincipal AuthPrincipal caller,
        @RequestParam(required = false) NotificationGroup group
    ) {
        int markedRead = notificationService.markAllRead(caller.userId(), group);
        return Map.of("markedRead", markedRead);
    }
}
