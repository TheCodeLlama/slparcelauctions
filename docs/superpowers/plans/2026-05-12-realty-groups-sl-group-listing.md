# Realty Groups — Sub-project E — SL-Group-Owned Listings Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the realty-group case-3 listing flow end-to-end. Leader registers an SL group via about-text or founder-via-terminal; members with `CREATE_LISTING` list parcels deeded to that SL group; realty group is seller-of-record with payout to the group wallet; listing agent earns a per-listing commission rate from their member row; brokers with `MANAGE_ALL_LISTINGS` can cancel any case-3 listing without penalizing the original lister.

**Architecture:** New `realty.slgroup` sub-package owns the SL-group registration + verification flow. C-era case-1 plumbing stays as deprecated legacy; new wizard logic + backend validation route every new listing to either Individual (personal land) or case-3 (SL-group-owned). `AgentCommissionDistributor` ships beside D's existing `AgentFeeDistributor`; case discrimination is by `auction.realty_group_sl_group_id IS NOT NULL`. Broker-cancel is a sibling to seller-cancel that skips the penalty ladder.

**Tech Stack:** Spring Boot 4 / Java 26 / Lombok / JPA / Flyway / PostgreSQL / JUnit 5 / Mockito / AssertJ. Frontend: Next.js 16 / React 19 / TypeScript 5 / Tailwind CSS 4 / Vitest / MSW / TanStack Query. LSL on the `slpa-terminal` script.

**Branch:** `feat/realty-groups-sl-group-listing` (already branched from `dev` and pushed; spec committed at `0e6c7e0f`).

---

## Spec Reference

Spec at `docs/superpowers/specs/2026-05-12-realty-groups-sl-group-listing-design.md`. Each task below names the spec sections it implements.

## Conventions Refresh (read before starting)

- **`BaseEntity` / `BaseMutableEntity`:** every entity extends one. `Long id` internal; `UUID publicId` public. Use `@SuperBuilder`, never `@Builder`. Don't redeclare `id`, `publicId`, `createdAt`, `updatedAt`, `version`. Don't override `equals`/`hashCode`.
- **Migrations:** Flyway is the schema manager. Next migration is `V27`.
- **Lock ordering:** wallet writes go through `findByIdForUpdate(...)` (D pattern); auction cancellation already uses pessimistic locking.
- **No emojis** anywhere. No AI/tool attribution in commits.
- **Test-first:** every code task starts with the failing test, runs it red, then implements.
- **Commit cadence:** one commit per task, conventional-commit format (`feat(realty-slgroup): ...`).
- **Push** after every commit so the branch on GitHub stays current.

---

## File Structure

### Backend — new files

```
backend/src/main/resources/db/migration/
  V27__realty_group_sl_groups.sql

backend/src/main/java/com/slparcelauctions/backend/realty/slgroup/
  RealtyGroupSlGroup.java                    # entity, extends BaseMutableEntity
  RealtyGroupSlGroupRepository.java
  RealtyGroupSlGroupService.java             # register, verify, list, unregister, recheck
  RealtyGroupSlGroupController.java          # GET/POST/DELETE under /realty/groups/{publicId}/sl-groups
  SlGroupVerifyController.java               # LSL callback POST /sl/sl-group/verify
  SlGroupVerifyMethod.java                   # enum ABOUT_TEXT, FOUNDER_TERMINAL
  SlGroupVerificationCodeGenerator.java      # 12-char base32 (no 0/O/1/l)
  SlGroupAboutTextPollTask.java              # @Scheduled, 5-min cadence
  SlGroupRegistrationExpiryTask.java         # @Scheduled, hourly cleanup
  dto/
    RealtyGroupSlGroupDto.java
    RegisterSlGroupRequest.java
    SlGroupVerifyRequest.java
    SlGroupPendingDto.java
    RealtyGroupSlGroupDtoMapper.java
  exception/
    SlGroupAlreadyRegisteredException.java
    SlGroupNotVerifiedException.java
    SlGroupVerificationExpiredException.java
    SlGroupFounderMismatchException.java
    ParcelNotOwnedByRegisteredSlGroupException.java
    RegisteredSlGroupHasListingsException.java
    SlGroupRegisteredBlocksDissolveException.java

backend/src/main/java/com/slparcelauctions/backend/auction/agentfee/
  AgentCommissionDistributor.java            # case-3 sibling to AgentFeeDistributor

backend/src/main/java/com/slparcelauctions/backend/auction/exception/
  BrokerCancelNotApplicableException.java

backend/src/main/java/com/slparcelauctions/backend/sl/dto/
  GroupPageData.java                          # typed wrapper for world.secondlife.com/group/{uuid}
```

### Backend — modified files

```
backend/src/main/java/com/slparcelauctions/backend/realty/permission/RealtyGroupPermission.java
  -- MANAGE_OWN_LISTING (drop)
  ++ REGISTER_SL_GROUP

backend/src/main/java/com/slparcelauctions/backend/realty/RealtyGroupMember.java
  ++ agent_commission_rate (DECIMAL(5,4) NOT NULL DEFAULT 0)

backend/src/main/java/com/slparcelauctions/backend/realty/RealtyGroupMemberRepository.java
  ++ findByGroupIdAndUserIdReturningRate (for snapshot)

backend/src/main/java/com/slparcelauctions/backend/auction/Auction.java
  ++ realty_group_sl_group_id (BIGINT FK)
  ++ agent_commission_rate (DECIMAL(5,4))

backend/src/main/java/com/slparcelauctions/backend/auction/AuctionRepository.java
  ++ reassignSellerToLeaderForCase3, reassignListingAgentForCase1 (split)
  ++ existsCase3ForSlGroup (for unregister gate)

backend/src/main/java/com/slparcelauctions/backend/realty/listing/RealtyGroupListingService.java
  ++ case-3 validation (parcel SL-group-owned, registration verified, snapshot rate)

backend/src/main/java/com/slparcelauctions/backend/realty/listing/RealtyGroupListingController.java
  ++ slParcelUuid query param + parcel-aware filtering

backend/src/main/java/com/slparcelauctions/backend/realty/service/RealtyGroupMembershipService.java
  ++ split reassignment between case 1 (listing_agent) and case 3 (seller_id)

backend/src/main/java/com/slparcelauctions/backend/realty/service/RealtyGroupService.java
  ++ extend dissolve() gate with SL-group registrations

backend/src/main/java/com/slparcelauctions/backend/realty/service/RealtyGroupInvitationService.java
  ++ accept agentCommissionRate at invite time, persist on accept

backend/src/main/java/com/slparcelauctions/backend/realty/dto/CreateInvitationRequest.java
  ++ agentCommissionRate field

backend/src/main/java/com/slparcelauctions/backend/realty/dto/UpdatePermissionsRequest.java
  ++ agentCommissionRate field

backend/src/main/java/com/slparcelauctions/backend/realty/dto/RealtyGroupDtoMapper.java
  ++ surface agentCommissionRate on member DTOs

backend/src/main/java/com/slparcelauctions/backend/realty/exception/RealtyExceptionHandler.java
  ++ ProblemDetail mappings for E exceptions

backend/src/main/java/com/slparcelauctions/backend/sl/SlWorldApiClient.java
  ++ fetchGroupPage(UUID)

backend/src/main/java/com/slparcelauctions/backend/auction/CancellationService.java
  ++ brokerCancel(brokerUserId, auctionId, reason, ip)

backend/src/main/java/com/slparcelauctions/backend/auction/CancellationOffenseKind.java
  ++ BROKER_CANCEL

backend/src/main/java/com/slparcelauctions/backend/auction/CancellationLog.java
  ++ actor_user_id, realty_group_id fields (broker context)

backend/src/main/java/com/slparcelauctions/backend/auction/CancellationLogRepository.java
  ++ countPriorOffensesWithBids excludes BROKER_CANCEL

backend/src/main/java/com/slparcelauctions/backend/auction/AuctionController.java
  ++ POST /auctions/{publicId}/broker-cancel

backend/src/main/java/com/slparcelauctions/backend/escrow/EscrowService.java
  ++ case-3 payout routing in createForEndedAuction

backend/src/main/java/com/slparcelauctions/backend/escrow/command/TerminalCommandService.java
  ++ branch agent distributor invocation (case 3 vs case 1)

backend/src/main/java/com/slparcelauctions/backend/bot/BotTaskService.java
  ++ case-3 ownership check inside complete()

backend/src/main/java/com/slparcelauctions/backend/auction/monitoring/OwnershipCheckTask.java
  ++ case-3 expected-owner from realty_group_sl_groups
```

### Frontend — new files

```
frontend/src/types/realty.ts                  # extend with RealtyGroupSlGroup, SlGroupPending, etc.
frontend/src/lib/api/realtySlGroup.ts          # register, list, delete, recheck
frontend/src/hooks/realty/useRealtyGroupSlGroups.ts
frontend/src/hooks/realty/useRegisterSlGroup.ts
frontend/src/app/realty/groups/[publicId]/sl-groups/page.tsx
frontend/src/components/realty/slgroup/SlGroupsPage.tsx
frontend/src/components/realty/slgroup/RegisterSlGroupModal.tsx
frontend/src/components/realty/slgroup/SlGroupListRow.tsx
frontend/src/components/realty/slgroup/SlGroupVerificationInstructionsCard.tsx
frontend/src/components/listing/BrokerCancelButton.tsx
frontend/src/components/listing/BrokerCancelModal.tsx
```

### Frontend — modified files

```
frontend/src/components/listing/ListingWizardForm.tsx                    # parcel-driven picker
frontend/src/components/listing/AgentFeePreview.tsx                       # commission preview (case 3 mode)
frontend/src/components/realty/group/MemberEditDrawer.tsx                  # commission rate input
frontend/src/components/realty/group/InviteMemberModal.tsx                 # commission rate input
frontend/src/components/auction/AuctionDetailHeader.tsx                    # case-3 "Sold by Realty Group" label
frontend/src/lib/api/realtyGroupListing.ts                                 # slParcelUuid param
frontend/src/test/msw/handlers.ts                                          # handlers for new endpoints
```

### LSL — modified files

```
lsl-scripts/slpa-terminal/<main script>.lsl   # SL Group Verify menu item + llTextBox + HTTP POST
lsl-scripts/slpa-terminal/README.md            # document new menu + deployment
```

### Docs

```
README.md                                      # Sub-project E section
docs/implementation/DEFERRED_WORK.md           # E section + drop superseded D items
```

---

The plan below decomposes into **30 tasks** in dependency order. Continuation files:

- **Part 1 — Tasks 1–10** (schema, entity, permissions, World API client, exceptions, SL group service core): see `2026-05-12-realty-groups-sl-group-listing-part1.md`
- **Part 2 — Tasks 11–20** (verification flows, controllers, listing case-3 flow, agent commission, escrow routing, Method C check): see `2026-05-12-realty-groups-sl-group-listing-part2.md`
- **Part 3 — Tasks 21–30** (ownership monitor, reassignment, dissolution gate, broker cancel, frontend, LSL, docs): see `2026-05-12-realty-groups-sl-group-listing-part3.md`

Read Part 1 → Part 2 → Part 3 in order. Each part lists its tasks with full TDD steps, exact file paths, and code blocks.

---

## Self-Review Note for the Implementer

After finishing all 30 tasks, before opening the PR:

1. **Spec coverage sweep:** open `docs/superpowers/specs/2026-05-12-realty-groups-sl-group-listing-design.md` and verify each section has been implemented. The §14 deferral table should match `docs/implementation/DEFERRED_WORK.md`.
2. **Backend test run:** `cd backend && ./mvnw test` — all green.
3. **Frontend test run:** `cd frontend && npm test && npm run verify` — all green.
4. **README sweep:** root `README.md` gets a new "Sub-project E — SL-group-owned listings" section mirroring D's structure.
5. **DEFERRED_WORK update:** add E-deferred items; remove any items now resolved by E.
6. **Postman:** mirror new endpoints into the SLPA Postman collection with variable-chaining test scripts where appropriate.
7. **Final commit + push.**
