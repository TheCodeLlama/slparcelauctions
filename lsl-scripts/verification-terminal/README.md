# SLParcels Verification Terminal

In-world verification kiosk handling two flows:

1. **Self verify** — players touch this terminal, type their 6-digit
   SLParcels code, and the script POSTs avatar metadata to `/sl/verify`
   to link the SL account to a website account (Method C).
2. **SL Group verify** — realty-group founders type the
   `SLPA-XXXXXXXXXXXX` code their group leader handed them, and the
   script POSTs to `/sl/sl-group/verify` with the toucher's avatar UUID
   so the backend can cross-check it against the SL group's founder.
   Sub-project E spec section 7.3.

If `SL_GROUP_VERIFY_URL` is set in the notecard, touch opens a `llDialog`
menu so the toucher picks which flow to run. When it's blank, touch goes
straight to the self-verify text box exactly as before.

## Architecture summary

- **Trust:** SL-injected `X-SecondLife-Shard` + `X-SecondLife-Owner-Key` headers.
  No shared secret. The terminal must be owned by an SLParcels service avatar listed
  in `slpa.sl.trusted-owner-keys`. Both endpoints (`/sl/verify` and
  `/sl/sl-group/verify`) accept SL-header trust at `/api/v1/sl/**`, and
  the verification codes are single-use server-side.
- **Self-verify state machine:** IDLE → (touch) lock + busy chrome →
  (llTextBox) code entry → (dataserver) DATA_BORN + DATA_PAYINFO →
  (HTTP) POST `/sl/verify` → (http_response) speak result → release
  lock → IDLE.
- **SL-group-verify state machine:** IDLE → (touch) lock + busy chrome
  → (llDialog) pick "SL Group" → (llTextBox) `SLPA-...` code entry →
  (HTTP) POST `/sl/sl-group/verify` with `{ verificationCode,
  founderAvatarUuid }` → (http_response) speak result → release lock →
  IDLE. No avatar-data fetch needed — the body is just the code + the
  toucher's UUID.
- **Lock:** single-user, 60s TTL. Subsequent touches see "Terminal busy"
  message. Multiple physical terminals at busy locations handle concurrent load.

## Deployment

User-facing kiosk distributed via Marketplace + SLParcels HQ + allied venues.

1. Rez a generic prim, give it terminal-style geometry / texture (visual; not
   script concern).
2. Drop `verification-terminal.lsl` into the prim.
3. Drop a copy of `config.notecard.example` renamed to **`config`** (no
   extension). Edit `VERIFY_URL` to match the target environment.
4. Set the prim's owner to the SLParcels service avatar (so `X-SecondLife-Owner-Key`
   matches `slpa.sl.trusted-owner-keys`).
5. Reset the script (right-click → Edit → Reset Scripts in Selection).
6. Confirm idle floating text appears: "SLParcels Verification Terminal\nTouch to
   link your account".

## Configuration

`config` notecard format: `key=value` pairs, one per line. Lines starting with
`#` are comments. Whitespace trimmed.

| Key | Description |
| --- | --- |
| `VERIFY_URL` | Full URL of the backend's `/api/v1/sl/verify` endpoint. Required. |
| `SL_GROUP_VERIFY_URL` | Full URL of `/api/v1/sl/sl-group/verify`. Optional — when blank, the SL-group menu item is hidden and touch goes straight to self-verify. New deployments should set it so realty-group founders can complete verification from this terminal. |
| `DEBUG_MODE` | `true`/`false`, default `true`. Recommended `true` in prod. |

Editing the notecard auto-resets the script via `CHANGED_INVENTORY`.

## Operations

In steady state:

- `SLParcels Verification Terminal: ready (verify=... sl-group=...)` — startup ping. `sl-group=<disabled>` when `SL_GROUP_VERIFY_URL` is blank.
- `SLParcels Verification Terminal: touch from <name>` — when a user touches.
- `SLParcels Verification Terminal: verify ok: userId=<n>` — successful self-verify link.
- `SLParcels Verification Terminal: verify denied: <reason>` — backend rejected the self-verify request.
- `SLParcels Verification Terminal: SL Group Verify OK for <uuid>` — successful founder-terminal verification. The realty group leader can now list parcels owned by that SL group.
- `SLParcels Verification Terminal: SL Group Verify failed for <uuid> status=... code=... title=... detail=...` — founder-terminal verification rejected. Mapped statuses: 410 (`SL_GROUP_VERIFICATION_EXPIRED`), 422 (`SL_GROUP_FOUNDER_MISMATCH`), 404 (code not recognised). Other statuses fall through to a generic "try again" message to the toucher.

If silent for >5 minutes during expected traffic, check land permissions for
outbound HTTP and confirm the prim has the script + notecard.

## SL Group Verify (sub-project E spec section 7.3, section 13.3)

Founder-of-an-SL-group verification for realty groups listing parcels under
case-3 (SL group owns the parcel). The leader of a realty group starts the
registration in the SLParcels web UI; the SL group's founder finishes it by
typing the short `SLPA-XXXXXXXXXXXX` code into this terminal.

- **Menu:** touch the terminal → pick **SL Group**.
- **Input:** the 12-character `SLPA-XXXXXXXXXXXX` code shown on the realty
  group's SL Groups page after the leader initiates registration. Trimmed
  of whitespace before send.
- **Effect:** POSTs `{ verificationCode, founderAvatarUuid }` to
  `/api/v1/sl/sl-group/verify`. Backend cross-checks the toucher's avatar
  UUID against the SL group's founder via the World API and, on match,
  flips the registration row to `verified=true,
  verified_via=FOUNDER_TERMINAL`.
- **Toucher-facing outcomes** (`llDialog` `[OK]` — private to the toucher
  per the terminal-output-genericisation policy in commit 5a5276a):
  - 200 OK → "SL group verified. The realty group can now list parcels
    owned by this SL group."
  - 410 (`SL_GROUP_VERIFICATION_EXPIRED`) → "Verification code expired.
    The realty group leader needs to start a new registration."
  - 422 (`SL_GROUP_FOUNDER_MISMATCH`) → "You are not the founder of the SL
    group registered with this code."
  - 404 → "Verification code not recognised. Check the code and try again."
  - 401 / other 4xx → "There was a problem with SL Group Verify. Check the
    code and try again."
- **Concurrency:** this terminal is single-user-locked (60s TTL); the same
  lock that gates self-verify gates SL-group-verify. A founder hitting a
  busy terminal sees the standard "Terminal already in use" message and
  should try another nearby terminal.
- **No new env vars or permissions required.** Backend trust comes from
  the existing `X-SecondLife-Owner-Key` SL header (validated by
  `SlHeaderValidator` on `/api/v1/sl/**`). No `sharedSecret` is sent on
  this path because the controller doesn't read one; the wire shape
  exactly mirrors `SlGroupVerifyRequest`.
- **Backward compatibility.** `SL_GROUP_VERIFY_URL` is optional in the
  notecard. When blank, touch goes straight to the self-verify text box
  exactly as in pre-port deployments; the SL-group menu item is hidden.

## Troubleshooting

| Symptom | Likely cause |
| --- | --- |
| Startup says `incomplete config` | Notecard missing `VERIFY_URL`. |
| Startup says `notecard 'config' missing or unreadable` | Notecard not in prim, or named something other than exactly `config`. |
| Periodic `5xx` responses | Backend issue. Check server logs. |
| `403` responses | `X-SecondLife-Owner-Key` not in trusted set, or wrong shard. Confirm owner is an SLParcels service avatar. |
| `✓` never appears after correct code | Possibly the dataserver event for DATA_BORN / DATA_PAYINFO was lost. The 30s data-timeout will fire and speak an error. User can re-touch. |
| Touch goes straight to text box, no SL Group option | `SL_GROUP_VERIFY_URL` is blank in the notecard. Add the URL, save the notecard, the script auto-resets. |
| SL Group Verify owner-says `status=401` | Backend `/api/v1/sl/sl-group/verify` rejected at the SL-header gate. Confirm the terminal's owner UUID is in `slpa.sl.trusted-owner-keys`. |
| SL Group Verify owner-says `status=415` | Backend returned `application/problem+json` for a 4xx. Confirm `SlProblemDetailContentTypeAdvice` covers `/api/v1/sl/sl-group/**` (the path prefix in advice is `/api/v1/sl/**`, so this should be automatic). |

## Limits

- LSL listen cap is 65 active listens per script. The terminal opens exactly one
  listen per touch session and removes it on every exit path.
- LSL `dataserver` event is asynchronous and not guaranteed to fire if the
  avatar logs out mid-touch. The 30s timeout covers this.
- 60s lock means low-volume kiosks hit lock contention rarely. For high-volume
  locations, deploy multiple kiosks rather than queueing.

## Security

- The terminal must be owned by an SLParcels service avatar listed in
  `slpa.sl.trusted-owner-keys`. Backend rejects `X-SecondLife-Owner-Key` not
  in that set.
- No shared secret in the notecard — header trust is sufficient because the
  endpoint is idempotent and verification codes are single-use server-side.
- Mainland-only: the script's `sim_channel` guard ("Second Life Server") refuses
  to run on Beta Grid (Aditi) — backend's `slpa.sl.expected-shard` enforces this
  at the HTTP layer too.
