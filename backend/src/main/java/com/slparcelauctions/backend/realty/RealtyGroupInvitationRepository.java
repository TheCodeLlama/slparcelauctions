package com.slparcelauctions.backend.realty;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RealtyGroupInvitationRepository extends JpaRepository<RealtyGroupInvitation, Long> {

    Optional<RealtyGroupInvitation> findByPublicId(UUID publicId);

    Optional<RealtyGroupInvitation> findByGroupIdAndInvitedUserIdAndStatus(
        Long groupId, Long invitedUserId, InvitationStatus status);

    /** Invitations addressed to the given user, in the given status, newest first. */
    List<RealtyGroupInvitation> findByInvitedUserIdAndStatusOrderByCreatedAtDesc(
        Long invitedUserId, InvitationStatus status);

    List<RealtyGroupInvitation> findByGroupIdOrderByCreatedAtDesc(Long groupId);

    /** Returns invitations whose {@code expires_at} is in the past and which are still
     *  {@code PENDING}. The expiry job iterates these to flip status and fire notifications. */
    @Query("SELECT i FROM RealtyGroupInvitation i WHERE i.status = com.slparcelauctions.backend.realty.InvitationStatus.PENDING AND i.expiresAt < CURRENT_TIMESTAMP")
    List<RealtyGroupInvitation> findOverdueInvitations();

    /** Bulk-update path retained for the unlikely case the expiry job needs to flip without
     *  individual notification fires (not used by default, since the job needs each row to
     *  fan out the notification). */
    @Modifying
    @Query("""
        UPDATE RealtyGroupInvitation i
           SET i.status = com.slparcelauctions.backend.realty.InvitationStatus.EXPIRED,
               i.respondedAt = CURRENT_TIMESTAMP
         WHERE i.status = com.slparcelauctions.backend.realty.InvitationStatus.PENDING
           AND i.expiresAt < CURRENT_TIMESTAMP
        """)
    int markOverdueExpired();
}
