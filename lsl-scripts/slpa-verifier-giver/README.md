# SLParcels Parcel Verifier Giver

Single-purpose in-world prim that hands out a copy of the SLParcels Parcel
Verifier inventory item on touch. Free, no L$, no backend interaction.

## Architecture summary

- **Trust:** none. Touch-driven, header-trust-equivalent — anyone in-world
  can touch and receive a verifier.
- **Touch flow:** IDLE → (touch) per-avatar rate-limit check → if OK,
  `llGiveInventory(toucher, "SLParcels Parcel Verifier")` → owner-say → IDLE.
- **Rate limit:** 60 seconds per-avatar. A second touch from the same
  avatar within the window is refused with `Just gave you one — wait a
  minute before requesting another.`

## Why a separate prim?

Previously the SLParcels Terminal had a "Get Parcel Verifier" menu option that
called `llGiveInventory` from the terminal's own contents. Updating
`parcel-verifier.lsl` required dragging the new object into every deployed
SLParcels Terminal's inventory — a two-place rule that was easy to forget.

Splitting the verifier-give role into a dedicated prim concentrates that
update step to just the verifier-giver instances. SLParcels Terminal no longer
carries an SLParcels Parcel Verifier in its inventory.

## Deployment

1. Rez a generic prim at SLParcels HQ, an auction venue, or Marketplace.
2. Drop `slpa-verifier-giver.lsl` into the prim.
3. Drop a `SLParcels Parcel Verifier` object copy into the prim's contents.
4. Drop a copy of `config.notecard.example` renamed to **`config`** (no
   extension). Edit if you want to change the verifier item name or
   rate-limit duration.
5. Reset the script. Confirm the floating text "SLParcels Parcel Verifier — Free
   / Touch to receive" appears.
6. Smoke-test: touch the prim from a second avatar; confirm the verifier
   inventory offer arrives.

## Configuration

| Key | Description |
| --- | --- |
| `VERIFIER_NAME` | Name of the inventory item to give. Default `SLParcels Parcel Verifier`. |
| `RATE_LIMIT_SECONDS` | Per-avatar rate-limit window. Default 60. |
| `DEBUG_MODE` | Optional. `true`/`false`, default `true`. Per-event owner-chat. |

## Updating

When `parcel-verifier.lsl` is updated:

1. **Marketplace listing**: republish a new revision with the updated `.lsl`.
2. **Every deployed verifier-giver prim's inventory**: drag-drop the new
   `SLParcels Parcel Verifier` object into the prim's contents, replacing the
   old copy. `CHANGED_INVENTORY` auto-resets the giver script.

This is now the only place to update verifiers — SLParcels Terminals no longer
carry a copy.

Updating the giver script itself: drag-drop the new `slpa-verifier-giver.lsl`
into the prim's contents → `CHANGED_INVENTORY` auto-resets.

## Operations

In steady state with `DEBUG_MODE=true`:

- `SLParcels Verifier Giver: config loaded (rate_limit=60s).` — startup.
- `SLParcels Verifier Giver: gave verifier to <name>` — successful touch.
- `CRITICAL: 'SLParcels Parcel Verifier' missing from giver prim inventory.` —
  the verifier object isn't in the prim's contents. Drag-drop it back in.

## Limits

- LSL listen cap: this script opens zero listens. No leak risk.
- `llGiveInventory` cap: 16 pending offers per agent. The 60-second
  rate-limit makes hitting this implausible at SLParcels's traffic level.

## Security

- The verifier itself is free; there's nothing to steal.
- The script holds no secrets. Anyone with edit-rights on the prim sees
  only the inventory-item name and rate-limit settings.
- A determined griefer could touch repeatedly with multiple alts to
  consume verifier copies — but verifier copies are free and unlimited
  for the prim owner, so the worst-case impact is minor avatar-list
  noise. Rate-limiting per-avatar handles single-avatar griefing.
