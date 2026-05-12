package com.slparcelauctions.backend.admin.audit;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

import lombok.RequiredArgsConstructor;

/**
 * Resolves the SLPA "system" {@link User} row used as the actor / approver
 * stamp when a scheduled job mutates state that the data model requires be
 * attributed to a user. Sub-project F currently uses this in two places:
 *
 * <ul>
 *   <li>{@code GroupSuspensionExpiryTask} stamps {@code lifted_by_admin_id}
 *       on timed suspensions whose expiry has passed.</li>
 *   <li>The bulk-suspended-listing auto-cancel sweep (Task 13) will use it
 *       to attribute the cancellation that the data model normally pins to
 *       a human admin.</li>
 *   <li>The SL-group reverify failure cascade (Task 27) will use it for the
 *       same reason.</li>
 * </ul>
 *
 * <p>The system-user id is sourced from {@code slpa.system.user-id} (default
 * {@code 1}). In dev / prod the {@code UserBootstrap} bean seeds the first
 * user row at id=1 via the {@code IDENTITY} sequence on the {@code users}
 * table, so the default resolves to the bootstrap admin until a future
 * migration introduces a dedicated synthetic system user.
 *
 * <p>Throws {@link IllegalStateException} on lookup miss — that is a startup
 * misconfiguration (config points at a row that does not exist), not a
 * runtime condition that a caller is expected to handle.
 */
@Component
@RequiredArgsConstructor
public class SystemUserResolver {

    @Value("${slpa.system.user-id:1}")
    private Long systemUserId;

    private final UserRepository userRepo;

    public User getSystemUser() {
        return userRepo.findById(systemUserId)
                .orElseThrow(() -> new IllegalStateException(
                        "System user not seeded (id=" + systemUserId + ")"));
    }
}
