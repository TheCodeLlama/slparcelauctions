package com.slparcelauctions.backend.promotion;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.slparcelauctions.backend.auction.Auction;
import com.slparcelauctions.backend.auction.AuctionRepository;
import com.slparcelauctions.backend.auction.AuctionStatus;
import com.slparcelauctions.backend.auction.exception.AuctionNotFoundException;
import com.slparcelauctions.backend.promotion.exception.NotAuctionSellerException;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;
import com.slparcelauctions.backend.wallet.WalletService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Orchestrates the buy-PROMO-01 flow: caller-owns-auction check, atomic
 * wallet debit + slot assignment + is_featured flip. One transaction; any
 * failure rolls back the wallet and the slot row together.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PromotionService {

    public static final String PROMO_01_CODE = "PROMO-01";

    private final AuctionRepository auctionRepo;
    private final UserRepository userRepo;
    private final WalletService walletService;
    private final FeaturedBoardSlotService slotService;
    private final PromotionConfigProperties promotionConfig;

    public record PurchaseResult(FeaturedBoardSlot slot, long newBalanceLindens) {}

    @Transactional
    public PurchaseResult purchaseFeatured(long callerUserId, UUID auctionPublicId) {
        Auction auction = auctionRepo.findByPublicId(auctionPublicId)
                .orElseThrow(() -> new AuctionNotFoundException(auctionPublicId));
        if (auction.getSeller() == null
                || !auction.getSeller().getId().equals(callerUserId)) {
            throw new NotAuctionSellerException(auctionPublicId);
        }
        if (auction.getStatus() != AuctionStatus.ACTIVE) {
            throw new IllegalStateException(
                    "PROMO-01 can only be bought on ACTIVE auctions; got " + auction.getStatus());
        }

        User caller = userRepo.findById(callerUserId)
                .orElseThrow(() -> new IllegalStateException(
                        "Authenticated user not found: " + callerUserId));

        long price = promotionConfig.featuredPriceLindens();
        walletService.debitPromotionFee(caller, price, auction.getId(), PROMO_01_CODE);
        FeaturedBoardSlot slot = slotService.assign(auction);
        auction.setFeatured(true);
        auction.setFeaturedUntil(auction.getEndsAt());

        log.info("PROMO-01 purchased: callerUserId={} auctionId={} price={} slotId={}",
                callerUserId, auction.getId(), price, slot.getId());
        return new PurchaseResult(slot, caller.getBalanceLindens());
    }
}
