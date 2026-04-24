package com.slparcelauctions.backend.auction.saved;

import java.time.OffsetDateTime;

import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import com.slparcelauctions.backend.auction.Auction;
import com.slparcelauctions.backend.user.User;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A user's saved/bookmarked auction. Many-to-many between users and auctions
 * realised as an explicit join entity so we can carry a {@code savedAt}
 * timestamp for "most-recently-saved" ordering.
 *
 * <p>The {@code uk_saved_auctions_user_auction} unique constraint enforces
 * the "save once" invariant at the storage layer; {@code SavedAuctionService}
 * treats a duplicate POST as idempotent and returns the existing row.
 *
 * <p>The {@code ix_saved_auctions_user_saved_at} composite index is the
 * read path's hot index — every saved-list query is "rows for user X
 * ordered by savedAt DESC". The trailing {@code DESC} matches the dominant
 * sort direction.
 *
 * <p>{@code @OnDelete(CASCADE)} on the auction FK means deleting an auction
 * removes its saves; the user-side cascade is left to FK defaults
 * (typically blocked by RESTRICT) so we don't silently drop user history.
 */
@Entity
@Table(name = "saved_auctions",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_saved_auctions_user_auction",
                columnNames = {"user_id", "auction_id"}),
        indexes = @Index(name = "ix_saved_auctions_user_saved_at",
                columnList = "user_id, saved_at DESC"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SavedAuction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "auction_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Auction auction;

    @Column(name = "saved_at", nullable = false)
    private OffsetDateTime savedAt;
}
