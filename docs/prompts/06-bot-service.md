# Phase 6: Bot Service (LibreMetaverse)

_Reference: DESIGN.md Sections 5.4, 4.2 (Method C)_

---

## Goal

Build a C#/.NET service using LibreMetaverse that manages a pool of headless SL bots. Bots teleport to regions, read parcel data via the viewer protocol, and report back to the Java backend. This enables Sale-to-Bot verification and ongoing auction/escrow monitoring.

---

## What Needs to Happen

### Bot Pool Manager

- Manage multiple worker bot accounts (login, maintain session, heartbeat)
- Primary escrow account (SLPAEscrow Resident) is config only - never logs in
- Worker bots are headless (no rendering, no textures, no 3D - protocol layer only)
- Track bot status: online, offline, maintenance
- Track current region per bot
- Distribute tasks across available workers
- Handle bot failures gracefully (reassign tasks to other workers)

### Task Queue

- Accept tasks from the Java backend via REST API or message queue
- Task types: VERIFY (one-time parcel check), MONITOR_AUCTION (recurring), MONITOR_ESCROW (recurring)
- Schedule recurring tasks based on configured intervals (30 min for auction monitoring, 15 min for escrow)
- Return results to Java backend via callback

### Parcel Verification (Method C)

- Bot teleports to the target region
- Reads `ParcelProperties` for the parcel at given coordinates
- Checks: `AuthBuyerID` matches primary escrow account UUID, `SalePrice` is L$999,999,999, ownership matches expected seller
- Reports result (pass/fail with parcel data) to backend

### Auction Monitoring

- Every 30 minutes for active bot-verified auctions
- Checks: land still set for sale to primary escrow account, ownership unchanged
- Reports any changes to backend (which handles the auction pause/cancel logic)

### Escrow Monitoring

- Every 15 minutes during escrow period
- Checks: ownership changed to winner (transfer complete), or ownership changed to unknown (fraud)
- Can detect if seller set land for sale to winner correctly (AuthBuyerID == winner UUID)

### Scaling

- Respect SL's hard limit: 6 teleports per minute per bot
- Optimize routing: group nearby parcels to minimize teleports (multiple parcels in same region = 1 teleport)
- Config-driven bot count (start with 2-3 workers, scale as needed)

---

## Acceptance Criteria

- Bot service starts, logs in worker accounts, maintains sessions
- Can receive a verification task, teleport to region, read ParcelProperties, and return results
- Correctly reads AuthBuyerID, SalePrice, OwnerID from ParcelProperties
- Recurring monitoring tasks execute on schedule
- Task reassignment works when a bot goes offline
- Teleport rate limiting is respected (never exceeds 6/min per bot)
- Results reported to Java backend reliably
- Bot service runs independently from the Java backend (separate process/container)

---

## Notes

- This is a C#/.NET project, separate from the Java backend. It communicates with the backend via HTTP.
- LibreMetaverse is the library for SL viewer protocol. It handles login, teleport, and message parsing.
- Bots should be marked as Scripted Agents in their SL profiles per LL's bot policy.
- Access restrictions (~40% of parcels) will cause some teleports to fail. Handle gracefully and report back as "access denied."
- The ParcelOverlay message may also be useful for the parcel layout map feature (future), but for now focus on ParcelProperties.
