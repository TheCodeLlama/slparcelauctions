package com.slparcelauctions.backend.auction.fraud;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FraudFlagRepository extends
        JpaRepository<FraudFlag, Long>,
        JpaSpecificationExecutor<FraudFlag> {

    List<FraudFlag> findByAuctionId(Long auctionId);

    long countByResolved(boolean resolved);

    long countByAuctionIdAndResolvedFalseAndIdNot(Long auctionId, Long flagId);

    @Query("""
        SELECT f FROM FraudFlag f
        WHERE f.auction.seller.id = :sellerId
        ORDER BY f.detectedAt DESC
        """)
    Page<FraudFlag> findByAuctionSellerId(@Param("sellerId") Long sellerId, Pageable pageable);
}
