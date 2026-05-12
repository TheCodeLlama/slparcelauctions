package com.slparcelauctions.backend.realty.wallet.dto;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.slparcelauctions.backend.auction.Auction;
import com.slparcelauctions.backend.auction.AuctionRepository;
import com.slparcelauctions.backend.realty.wallet.RealtyGroupLedgerEntry;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

import lombok.RequiredArgsConstructor;

/**
 * Maps {@link RealtyGroupLedgerEntry} entities to their DTO forms.
 * Resolves {@code refPublicId} for AUCTION refs and {@code actor}
 * for entries with an {@code actorUserId}. Spec §5.1, §5.2, §5.6.
 */
@Component
@RequiredArgsConstructor
public class GroupWalletDtoMapper {

    private final UserRepository userRepository;
    private final AuctionRepository auctionRepository;

    public GroupLedgerEntryDto toDto(RealtyGroupLedgerEntry e) {
        UUID refPublicId = resolveRefPublicId(e);
        LedgerActorDto actor = resolveActor(e);
        return new GroupLedgerEntryDto(
                e.getPublicId(),
                e.getEntryType().name(),
                e.getAmount(),
                e.getBalanceAfter(),
                e.getReservedAfter(),
                e.getRefType(),
                refPublicId,
                actor,
                e.getCreatedAt());
    }

    public List<GroupLedgerEntryDto> toDtos(List<RealtyGroupLedgerEntry> entries) {
        return entries.stream().map(this::toDto).toList();
    }

    /**
     * Only AUCTION refs are surfaced publicly as a UUID. All other ref types
     * (TERMINAL_COMMAND, LISTING_FEE_REFUND, REALTY_GROUP_LEDGER_ENTRY) remain
     * internal and resolve to null in the public DTO.
     */
    private UUID resolveRefPublicId(RealtyGroupLedgerEntry e) {
        if (!"AUCTION".equals(e.getRefType()) || e.getRefId() == null) {
            return null;
        }
        return auctionRepository.findById(e.getRefId())
                .map(Auction::getPublicId)
                .orElse(null);
    }

    /**
     * Resolves the actor user to a {@link LedgerActorDto}.
     * Returns {@code null} for system-driven entries (actorUserId is null).
     * Display name falls back to username if displayName is not set.
     */
    private LedgerActorDto resolveActor(RealtyGroupLedgerEntry e) {
        if (e.getActorUserId() == null) {
            return null;
        }
        return userRepository.findById(e.getActorUserId())
                .map(u -> new LedgerActorDto(u.getPublicId(), displayNameOf(u)))
                .orElse(null);
    }

    private static String displayNameOf(User u) {
        return u.getDisplayName() != null ? u.getDisplayName() : u.getUsername();
    }
}
