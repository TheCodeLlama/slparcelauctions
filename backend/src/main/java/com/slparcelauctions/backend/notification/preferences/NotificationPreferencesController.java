package com.slparcelauctions.backend.notification.preferences;

import com.slparcelauctions.backend.auth.AuthPrincipal;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/users/me/notification-preferences")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class NotificationPreferencesController {

    private final UserRepository userRepo;

    /**
     * The closed set of group keys the UI can edit. SYSTEM is delivered
     * regardless; REALTY_GROUP and MARKETING have no shipping categories yet.
     * Server is the source of truth for what's user-mutable.
     */
    private static final Set<String> ALLOWED_GROUP_KEYS = Set.of(
        "bidding", "auction_result", "escrow", "listing_status", "reviews");

    @GetMapping
    public PreferencesDto get(@AuthenticationPrincipal AuthPrincipal caller) {
        User user = userRepo.findById(caller.userId()).orElseThrow();
        Map<String, Object> filtered = ALLOWED_GROUP_KEYS.stream()
            .collect(Collectors.toMap(
                k -> k,
                k -> {
                    if (user.getNotifySlIm() == null) return (Object) false;
                    Object val = user.getNotifySlIm().getOrDefault(k, false);
                    return Boolean.TRUE.equals(val);
                }
            ));
        return new PreferencesDto(Boolean.TRUE.equals(user.getNotifySlImMuted()), filtered);
    }

    @PutMapping
    @Transactional
    public PreferencesDto put(
        @AuthenticationPrincipal AuthPrincipal caller,
        @RequestBody PreferencesDto body
    ) {
        if (body.slIm() == null || !body.slIm().keySet().equals(ALLOWED_GROUP_KEYS)) {
            throw new IllegalArgumentException(
                "slIm must contain exactly these keys: " + ALLOWED_GROUP_KEYS);
        }
        // Reject non-boolean values; Jackson deserializes JSON objects into Object,
        // so string "true" becomes a String, not a Boolean.
        boolean allBooleans = body.slIm().values().stream().allMatch(v -> v instanceof Boolean);
        if (!allBooleans) {
            throw new IllegalArgumentException(
                "slIm values must all be JSON booleans (true/false), not strings or other types.");
        }

        User user = userRepo.findById(caller.userId()).orElseThrow();
        user.setNotifySlImMuted(body.slImMuted());

        // Merge into existing map: preserve keys we don't expose (system,
        // realty_group, marketing) at their existing/default values.
        Map<String, Object> merged = user.getNotifySlIm() == null
            ? new HashMap<>()
            : new HashMap<>(user.getNotifySlIm());
        merged.putAll(body.slIm());
        user.setNotifySlIm(merged);
        userRepo.save(user);

        return get(caller);
    }
}
