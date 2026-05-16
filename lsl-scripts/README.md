# LSL Scripts

In-world Linden Scripting Language (LSL) code that consumes SLParcels's backend APIs.
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
  kiosk. Players touch and enter their 6-digit SLParcels code; the script POSTs
  avatar metadata to link the SL account to a website account. Header-trust
  only; widely deployed via Marketplace + allied venues.
- [`parcel-verifier/`](parcel-verifier/) — Single-use rezzable. Sellers rez it
  on the parcel they want to list; the script reads parcel metadata, prompts
  for the 6-digit PARCEL code, POSTs to the backend, then `llDie()`s.
  Distributed via Marketplace + given out by the SLParcels Parcel Verifier Giver.
- [`slpa-verifier-giver/`](slpa-verifier-giver/) — Touch-to-receive prim that
  hands out a copy of the SLParcels Parcel Verifier inventory item. Free, no L$,
  no shared secret. Per-avatar 60s rate-limit. Deployed at SLParcels HQ + auction
  venues + Marketplace.
- [`slpa-terminal/`](slpa-terminal/) — Wallet-model payment terminal. Right-
  click → Pay credits the user's wallet via `/sl/wallet/deposit` (lockless,
  fully concurrent). Touch menu offers Deposit-instructions and Withdraw
  (plus "Pay to group" when the sister `slpa-terminal-group/` script is
  loaded in the same prim). Withdraw uses per-flow slots dispatched by
  avatar key on a single shared listen — no terminal-wide lock. Also
  receives HTTP-in commands from the backend for PAYOUT / WITHDRAW
  execution (REFUND defensive — refunds are wallet credits, never
  dispatched). SLParcels-team-deployed; holds shared secret +
  PERMISSION_DEBIT.
- [`slpa-terminal-group/`](slpa-terminal-group/) — Sister script for
  `slpa-terminal/`. Owns the "Pay to group" flow: typed-name text-box,
  per-avatar deposit slot, `/sl/wallet/group-deposit` POST + retry +
  refund. Lives in the SAME prim as `slpa-terminal/` and coordinates with
  it via `llMessageLinked` (PING/PONG/START/CLAIM/RELEASE). Each LSL
  script has its own 64KB heap, so the split exists purely to keep the
  combined feature set off a single script's memory budget.
- [`sl-im-dispatcher/`](sl-im-dispatcher/) — Polls SLParcels backend for pending
  SL IM notifications and delivers them via `llInstantMessage`. SLParcels-team-deployed
  (one instance per environment); not user-deployed.
