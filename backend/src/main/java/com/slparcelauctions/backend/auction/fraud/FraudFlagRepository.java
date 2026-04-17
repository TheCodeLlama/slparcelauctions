package com.slparcelauctions.backend.auction.fraud;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface FraudFlagRepository extends JpaRepository<FraudFlag, Long> {

    List<FraudFlag> findByAuctionId(Long auctionId);
}
