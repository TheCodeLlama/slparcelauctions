# SLParcels Parcel Verifier (rezzable)

Single-use rezzable object. Sellers rez it on the parcel they want to list,
the script reads parcel metadata via `llGetParcelDetails`, prompts for a
6-digit PARCEL code, POSTs to backend, then `llDie()`s.

## Architecture summary

- **Trust:** SL-injected `X-SecondLife-Shard` + `X-SecondLife-Owner-Key` headers.
  `X-SecondLife-Owner-Key` here is the *seller* who rezzed the object. No
  shared secret.
- **Lifecycle:** state_entry → read notecard → read parcel data → auto-prompt
  for code via llTextBox → POST /sl/parcel/verify → llDie() (success or
  failure or timeout — always llDie).
- **Group-owned land:** if parcel owner UUID is a group (not the rezzer's
  avatar), skip the client-side owner-match short-circuit and let the backend
  authoritatively decide.

## Deployment

Distributed two ways:

1. **Marketplace listing** — free, transfer YES, copy YES, modify NO. The
   modify-NO setting prevents accidental edits that would void SLParcels service
   account ownership.
2. **SLParcels Terminal "Get Parcel Verifier" menu** — `llGiveInventory` from a
   deployed SLParcels Terminal. Sellers who don't have a Marketplace copy can
   pick one up from any kiosk.

To set up the Marketplace listing:

1. Rez a generic prim. Give it a small visual marker (a flag or beacon).
2. Drop `parcel-verifier.lsl` into the prim.
3. Drop the `config` notecard with `PARCEL_VERIFY_URL` set.
4. Set permissions: transfer YES, copy YES, modify NO. Owner: SLParcels service
   avatar.
5. Take the object back into inventory and list on Marketplace as a free item.

## Configuration

| Key | Description |
| --- | --- |
| `PARCEL_VERIFY_URL` | Full URL of `/api/v1/sl/parcel/verify`. |
| `DEBUG_MODE` | `true`/`false`, default `true`. |

## Operations

The script speaks via `llOwnerSay` (visible only to the rezzer). Expected
chat after rez:

- `✓ Parcel verified — your listing is live on slparcels.com.` (success)
- `✗ This parcel isn't yours. Please rez on land you own.` (owner mismatch)
- `✗ Code must be 6 digits.` (bad input)
- `✗ <title>: <detail>` (backend rejection — code expired, parcel mismatch, etc.)
- `✗ Backend unreachable. Please rez again in a moment.` (5xx / network)
- `✗ Timed out waiting for code.` (90s with no code entered)
- `✗ Timed out reaching SLParcels.` (30s with no HTTP response)

## Troubleshooting

| Symptom | Likely cause |
| --- | --- |
| Object self-destructs immediately on rez with "wrong grid" | Rezzed on Beta Grid (Aditi). Script is mainland-only. |
| `notecard 'config' missing or unreadable` | Notecard not in inventory. Take object back, add notecard, re-list. |
| `incomplete config` | `PARCEL_VERIFY_URL` empty or missing in notecard. |
| 4xx response — code expired | Generate a new PARCEL code on the auction's draft page. |
| 4xx response — parcel UUID mismatch | The PARCEL code was generated for a different parcel. Verify the code matches the auction. |
| 5xx repeatedly | Backend issue. Try again in a few minutes. |

## Limits

- One verification per rez. The object self-destructs in all paths. To re-try,
  rez a fresh copy from inventory.
- `llGetParcelDetails` only reads the parcel the object is physically on.
  This is intentional — proves the seller can rez on that parcel.
- 90s code-entry timeout. If the seller walks away mid-rez, the object
  self-destructs.

## Security

- Object owner must be an SLParcels service avatar listed in
  `slpa.sl.trusted-owner-keys`. The backend rejects `X-SecondLife-Owner-Key`
  not in that set.
- The `X-SecondLife-Owner-Key` header on the outbound request identifies the
  *rezzer* (the seller), not the parcel owner. Backend cross-checks against
  the verification code's bound user.
- Modify NO permission prevents tampering with the script after distribution.
