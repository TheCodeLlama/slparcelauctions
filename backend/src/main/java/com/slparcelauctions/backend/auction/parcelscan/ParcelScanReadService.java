package com.slparcelauctions.backend.auction.parcelscan;

import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

import com.slparcelauctions.backend.auction.Auction;
import com.slparcelauctions.backend.auction.AuctionRepository;
import com.slparcelauctions.backend.auction.parcelscan.dto.ParcelScanResponse;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

/**
 * Read-only assembler for {@link ParcelScanResponse}. Returns
 * {@code Optional.empty()} if the auction is unknown OR either raster
 * row is absent; the controller maps that to a 404.
 *
 * <p>Rasters are immutable per the parcel-scanner spec ("the auction's
 * permanent record"), so callers can cache responses aggressively.
 *
 * <p>{@code landUseCellsBase64} is null for any auction whose scan predates
 * the Land Use feature (i.e. no sibling {@code auction_parcel_land_use} row).
 */
@Service
@RequiredArgsConstructor
public class ParcelScanReadService {

    private final AuctionRepository auctionRepository;
    private final AuctionParcelLayoutRepository layoutRepository;
    private final AuctionParcelHeightMapRepository heightRepository;
    private final AuctionParcelLandUseRepository landUseRepository;

    @Transactional(readOnly = true)
    public Optional<ParcelScanResponse> findForAuction(UUID publicId) {
        Optional<Auction> auctionOpt = auctionRepository.findByPublicId(publicId);
        if (auctionOpt.isEmpty()) {
            return Optional.empty();
        }
        Long auctionId = auctionOpt.get().getId();

        Optional<AuctionParcelLayout> layoutOpt = layoutRepository.findByAuctionId(auctionId);
        Optional<AuctionParcelHeightMap> heightOpt = heightRepository.findByAuctionId(auctionId);
        if (layoutOpt.isEmpty() || heightOpt.isEmpty()) {
            return Optional.empty();
        }

        AuctionParcelLayout layout = layoutOpt.get();
        AuctionParcelHeightMap height = heightOpt.get();
        // landUse may be absent for pre-feature scans; null-through to the DTO.
        Optional<AuctionParcelLandUse> landUseOpt = landUseRepository.findByAuctionId(auctionId);

        Base64.Encoder b64 = Base64.getEncoder();
        return Optional.of(new ParcelScanResponse(
                layout.getGridSize(),
                layout.getCellSizeMeters(),
                b64.encodeToString(layout.getCells()),
                b64.encodeToString(height.getCells()),
                height.getBaseMeters(),
                height.getStepMeters(),
                height.getScannedAt(),
                landUseOpt.map(lu -> b64.encodeToString(lu.getCells())).orElse(null)
        ));
    }
}
