# LSL Scripts

In-world Linden Scripting Language (LSL) code that consumes SLPA's backend APIs.
Each script is the in-world half of an end-to-end integration; the matching
backend code lives under `backend/src/main/java/com/slparcelauctions/backend/`.

## Contributor rules

**Each new script gets its own subdirectory with its own README.** Updates to
any script's behavior, deployment, or configuration must be reflected in that
script's README in the same commit. The top-level index below updates only on
add / remove / rename.

A script's README must cover:

- **Purpose** — what the script does and what backend system it integrates with.
- **Architecture summary** — high-level flow (events, timers, HTTP calls).
- **Deployment** — step-by-step in-world deployment, prim setup, permissions.
- **Configuration** — notecard format, where to obtain secrets, rotation procedure.
- **Operations** — what owner-say / chat output to expect; how to verify health.
- **Troubleshooting** — common failure modes and their signatures.
- **Limits** — SL platform constraints the script depends on (HTTP throttles,
  IM byte limits, etc.) and any known operational caveats.
- **Security** — secret-handling expectations and access-control notes.

## Scripts

- [`verification-terminal/`](verification-terminal/) — In-world account-linking
  kiosk. Players touch and enter their 6-digit SLPA code; the script POSTs
  avatar metadata to link the SL account to a website account. Header-trust
  only; widely deployed via Marketplace + allied venues.
- [`parcel-verifier/`](parcel-verifier/) — Single-use rezzable. Sellers rez it
  on the parcel they want to list; the script reads parcel metadata, prompts
  for the 6-digit PARCEL code, POSTs to the backend, then `llDie()`s.
  Distributed via Marketplace + given out by the SLPA Terminal.
- [`slpa-terminal/`](slpa-terminal/) — Unified in-world payment terminal.
  Touch menu offers Escrow Payment, Listing Fee, Pay Penalty, and Get Parcel
  Verifier. Also receives HTTP-in commands from the backend for PAYOUT /
  REFUND / WITHDRAW execution. SLPA-team-deployed; holds shared secret +
  PERMISSION_DEBIT.
- [`sl-im-dispatcher/`](sl-im-dispatcher/) — Polls SLPA backend for pending
  SL IM notifications and delivers them via `llInstantMessage`. SLPA-team-deployed
  (one instance per environment); not user-deployed.
