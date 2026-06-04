# Featured board

In-world component of the SLParcels HQ Featured-board wall. One script
per board prim. The prim renders the per-board MOAP page from
`https://slparcels.com/in-world/board/{BOARD_INDEX}`, and on touch fires
a dialog with [Teleport / View listing / Cancel].

This is an **outbound-only** script -- no HTTP-in URL is registered, no
shared secret is required.

## Deployment

1. Rez a square prim at HQ. Set its face 0 to a neutral material (the
   MOAP layer paints over it).
2. Drop `featured-board.lsl` into the prim.
3. Drop `config.notecard.example` renamed to `config`. Edit it:
   - `BASE_URL=https://slparcels.com`
   - `BOARD_INDEX=1` (each board prim gets a unique 1..N).
   - `DEBUG_MODE=false` (true while you're setting up).
4. Right-click -> Edit -> Reset Scripts in Selection.
5. Watch local chat for `[featured-board] ready: BASE_URL=... BOARD_INDEX=1`.

Repeat for each board, incrementing `BOARD_INDEX`. Boards beyond the
backend's configured `slpa.auction.featured-slot-count` will receive
404 from `/in-world/featured-board/{N}` -- point those prims at
`/in-world/board/placeholder` instead by setting `BOARD_INDEX=0` and
adjusting the script's URL builder, or simply leave them dormant.

## Configuration

| Key | Description |
| --- | --- |
| `BASE_URL` | Frontend origin, no trailing slash. |
| `BOARD_INDEX` | 1..13. Must match a configured slot on the backend. |
| `DEBUG_MODE` | `true` enables owner-say debug logs. |

## Touch flow

1. Visitor touches the prim.
2. Script GETs `/api/v1/in-world/featured-board/{BOARD_INDEX}/touch` to
   learn which listing the MOAP cycle is currently showing.
3. Script `llDialog`s the toucher with a small menu.
4. `Teleport` and `View listing` both use `llLoadURL` -- SL pops the
   browser-redirect dialog and the user clicks through.

## Limits and gotchas

- MOAP requires the viewer to have media enabled. Boards are blank to
  viewers with media off -- see spec section 3.3.
- First-time visitors see a one-time "Allow Always slparcels.com" prompt.
- Touch latency is bounded by the HTTP roundtrip (500ms typically).
- If the backend is down, touch returns the "couldn't load" message --
  the prim's MOAP layer continues showing whatever was last rendered.
- The script resets on inventory change, so dropping a new `config`
  notecard rehydrates automatically.
