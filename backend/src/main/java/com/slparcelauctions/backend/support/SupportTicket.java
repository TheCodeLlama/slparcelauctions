package com.slparcelauctions.backend.support;

import com.slparcelauctions.backend.common.BaseMutableEntity;
import com.slparcelauctions.backend.user.User;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.LinkedHashSet;
import java.util.Set;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

/**
 * One customer support ticket: a thread between a user and admins.
 *
 * <p>The status model has only two persisted values
 * ({@link SupportTicketStatus#OPEN}, {@link SupportTicketStatus#RESOLVED}).
 * The "needs admin attention" filter is derived from
 * {@code lastMessageAuthor = USER AND status = OPEN} so we never end up
 * with the persisted state and the derived state disagreeing.
 *
 * <p>{@code assignedAdminId} is a raw {@code Long}, not a
 * {@code @ManyToOne} to {@code User}, because we do not need to load the
 * admin's full graph on every ticket read; the admin's display name is
 * pulled out separately when the queue / detail DTOs are built.
 *
 * <p>Spec: {@code docs/superpowers/specs/2026-05-21-customer-support-contact-design.md}.
 */
@Entity
@Table(name = "support_tickets")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class SupportTicket extends BaseMutableEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false, length = 160)
    private String subject;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private SupportTicketCategory category;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private SupportTicketStatus status = SupportTicketStatus.OPEN;

    @Column(name = "assigned_admin_id")
    private Long assignedAdminId;

    @Column(name = "last_message_at", nullable = false)
    private OffsetDateTime lastMessageAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "last_message_author", nullable = false, length = 16)
    private SupportTicketAuthorRole lastMessageAuthor;

    @Column(name = "resolved_at")
    private OffsetDateTime resolvedAt;

    /**
     * Messages on this ticket. Modeled as a {@code LinkedHashSet} (not a
     * {@code List}) so an {@code @EntityGraph} can fetch this collection
     * alongside {@code messages.attachments} in a single query without
     * triggering Hibernate's {@code MultipleBagFetchException}. Callers
     * that need chronological order sort by {@code createdAt} at the
     * mapper / DTO boundary; insertion order is preserved across writes
     * within a transaction but is NOT a load-order contract.
     */
    @Builder.Default
    @OneToMany(mappedBy = "ticket", cascade = CascadeType.ALL,
            orphanRemoval = true, fetch = FetchType.LAZY)
    private Set<SupportTicketMessage> messages = new LinkedHashSet<>();
}
