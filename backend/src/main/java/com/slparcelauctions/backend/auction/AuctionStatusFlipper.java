package com.slparcelauctions.backend.auction;

import com.slparcelauctions.backend.escrow.Escrow;
import org.springframework.stereotype.Service;
import lombok.RequiredArgsConstructor;

/**
 * Flips auction.status in lockstep with an escrow.state transition. Extracted
 * from {@code EscrowService} so both it and {@code TerminalCommandService}
 * (which owns the escrow to COMPLETED transition for the payout-success path)
 * can call it without creating a bidirectional dependency.
 *
 * <p>Caller is responsible for ensuring the new auction status reflects the
 * escrow's new state. Runs inside the caller's transaction; the auction is
 * already a managed entity loaded via the escrow's @ManyToOne, so the explicit
 * save flushes the change with the rest of the caller's writes.
 */
@Service
@RequiredArgsConstructor
public class AuctionStatusFlipper {

    private final AuctionRepository auctionRepo;

    public void flip(Escrow escrow, AuctionStatus newStatus) {
        Auction auction = escrow.getAuction();
        auction.setStatus(newStatus);
        auctionRepo.save(auction);
    }
}
