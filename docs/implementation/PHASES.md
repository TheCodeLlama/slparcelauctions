# SLPA - Implementation Phases

_Reference: docs/initial-design/DESIGN.md for full specifications_

---

## Phase Overview

| Phase | Name | Description | Dependencies |
|-------|------|-------------|--------------|
| 1 | Project Foundation | Spring Boot + Next.js scaffolding, DB schema, auth | None |
| 2 | Player Verification | SL identity linking via verification codes | Phase 1 |
| 3 | Parcel Management | Parcel verification, World API integration, listing creation | Phase 2 |
| 4 | Auction Engine | Bidding, proxy bids, snipe protection, countdown timers | Phase 3 |
| 5 | Escrow System | L$ payment tracking, ownership monitoring, payout flow | Phase 4 |
| 6 | Bot Service | LibreMetaverse bot pool for Sale-to-Bot verification and monitoring | Phase 3 |
| 7 | Browse & Search | Public listing discovery, filtering, distance search, map | Phase 4 |
| 8 | Ratings & Reputation | Post-auction reviews, completion tracking, cancellation penalties | Phase 5 |
| 9 | Notifications | Email + SL IM notification system with user preferences | Phase 2 |
| 10 | Admin & Moderation | Reports, fraud flags, bans, admin dashboard | Phase 8 |
| 11 | LSL Scripts | In-world verification terminal, escrow terminal, parcel verifier | Phase 2, Phase 5 |

**Notes:**
- Phases 6 and 11 are parallel tracks (C#/.NET and LSL respectively) that can be worked independently once their dependencies are met.
- Phase 9 (Notifications) can start as soon as Phase 2 is done and be expanded as later phases add new notification events.
- Each phase has its own prompt document in this directory.
