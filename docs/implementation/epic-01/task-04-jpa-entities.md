# Task 01-04: JPA Entities and Repositories

## Goal

Create JPA entity classes and Spring Data repositories for all database tables so the application has a complete, working data access layer.

## Context

All database tables are defined by Flyway migrations (Tasks 01-02 and 01-03). Entities should map exactly to those tables. See DESIGN.md Section 7 for schema details.

## What Needs to Happen

- Create JPA entity classes for every table: User, Parcel, Auction, Bid, ProxyBid, EscrowTransaction, BotAccount, BotTask, Review, CancellationLog, ListingReport, Ban, FraudFlag, RealtyGroup, RealtyGroupMember, RealtyGroupInvitation, RealtyGroupSlGroup, VerificationCode, AuctionTag
- Map all relationships between entities (ManyToOne, OneToMany, etc.)
- Handle the parcel_tag PostgreSQL enum - map it to a Java enum
- Handle JSONB columns (notification preferences) with appropriate type mapping
- Create Spring Data JPA repository interfaces for each entity
- Add commonly needed query methods: find user by email, find user by SL UUID, find active auctions, find bids by auction (ordered by amount desc), find pending bot tasks, etc.
- Use Lombok for boilerplate reduction

## Acceptance Criteria

- Application starts without any JPA mapping errors or Hibernate validation failures
- Every database table has a corresponding entity and repository
- A simple integration test can persist and retrieve at least one User, one Parcel, and one Auction
- Relationships work correctly (e.g., loading an Auction eager/lazy loads its Seller, Parcel)
- JSONB notification preference columns serialize/deserialize correctly
- The parcel_tag enum maps to the PostgreSQL enum type

## Notes

- Primary keys are BIGSERIAL → use Long in Java, not UUID.
- UUID columns (sl_avatar_uuid, parcel_uuid, etc.) are data fields, map them as java.util.UUID.
- For the auction status, use a Java enum with all values from DESIGN.md (DRAFT, DRAFT_PAID, VERIFICATION_PENDING, VERIFICATION_FAILED, ACTIVE, ENDED, ESCROW_PENDING, ESCROW_FUNDED, TRANSFER_PENDING, COMPLETED, CANCELLED, EXPIRED, DISPUTED). Store as VARCHAR in DB.
- Don't overcomplicate relationships - use lazy loading by default.
