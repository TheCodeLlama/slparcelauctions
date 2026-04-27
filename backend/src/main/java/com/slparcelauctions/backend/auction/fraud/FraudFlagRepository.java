package com.slparcelauctions.backend.auction.fraud;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface FraudFlagRepository extends
        JpaRepository<FraudFlag, Long>,
        JpaSpecificationExecutor<FraudFlag> {

    List<FraudFlag> findByAuctionId(Long auctionId);

    long countByResolved(boolean resolved);

    long countByAuctionIdAndResolvedFalseAndIdNot(Long auctionId, Long flagId);
}
