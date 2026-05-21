package com.slparcelauctions.backend.support;

import com.fasterxml.jackson.annotation.JsonIgnore;
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
import java.util.LinkedHashSet;
import java.util.Set;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

/**
 * One message inside a {@link SupportTicket}. Authored by either the
 * ticket owner ({@link SupportTicketAuthorRole#USER}) or an admin
 * ({@link SupportTicketAuthorRole#ADMIN}).
 *
 * <p>{@code visibleToUser = false} marks an admin internal note - those
 * are filtered out of every user-facing query and never fire a
 * notification on any channel.
 *
 * <p>{@code authorRole} is snapshotted at write time so later role
 * changes do not rewrite history. Body length (1..10000) is enforced at
 * the service / validation layer rather than the DB because TEXT has no
 * server-side length constraint primitive.
 */
@Entity
@Table(name = "support_ticket_messages")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class SupportTicketMessage extends BaseMutableEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "ticket_id", nullable = false)
    @JsonIgnore
    private SupportTicket ticket;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "author_user_id", nullable = false)
    @JsonIgnore
    private User authorUser;

    @Enumerated(EnumType.STRING)
    @Column(name = "author_role", nullable = false, length = 16)
    private SupportTicketAuthorRole authorRole;

    @Column(nullable = false, columnDefinition = "text")
    private String body;

    @Builder.Default
    @Column(name = "visible_to_user", nullable = false)
    private Boolean visibleToUser = true;

    /**
     * Attachments on this message. Modeled as a {@code LinkedHashSet} to
     * preserve insertion order while avoiding Hibernate's
     * {@code MultipleBagFetchException} when an {@code @EntityGraph}
     * fetches both this collection and the parent ticket's
     * {@code messages} list in a single query (Hibernate cannot
     * simultaneously fetch multiple {@code List}-backed bags).
     */
    @Builder.Default
    @OneToMany(mappedBy = "message", cascade = CascadeType.ALL,
            orphanRemoval = true, fetch = FetchType.LAZY)
    private Set<SupportTicketAttachment> attachments = new LinkedHashSet<>();
}
