# SLPA Verification Terminal

In-world account-linking kiosk. Players touch this terminal, type their 6-digit
SLPA code, and the script POSTs their avatar metadata to the backend to link
the SL account to a website account.

## Architecture summary

- **Trust:** SL-injected `X-SecondLife-Shard` + `X-SecondLife-Owner-Key` headers.
  No shared secret. The terminal must be owned by an SLPA service avatar listed
  in `slpa.sl.trusted-owner-keys`.
- **State machine:** IDLE → (touch) lock + busy chrome → (llTextBox) code entry →
  (dataserver) DATA_BORN + DATA_PAYINFO → (HTTP) POST /sl/verify → (http_response)
  speak result → release lock → IDLE.
- **Lock:** single-user, 60s TTL. Subsequent touches see "Terminal busy"
  message. Multiple physical terminals at busy locations handle concurrent load.

## Deployment

User-facing kiosk distributed via Marketplace + SLPA HQ + allied venues.

1. Rez a generic prim, give it terminal-style geometry / texture (visual; not
   script concern).
2. Drop `verification-terminal.lsl` into the prim.
3. Drop a copy of `config.notecard.example` renamed to **`config`** (no
   extension). Edit `VERIFY_URL` to match the target environment.
4. Set the prim's owner to the SLPA service avatar (so `X-SecondLife-Owner-Key`
   matches `slpa.sl.trusted-owner-keys`).
5. Reset the script (right-click → Edit → Reset Scripts in Selection).
6. Confirm idle floating text appears: "SLPA Verification Terminal\nTouch to
   link your account".

## Configuration

`config` notecard format: `key=value` pairs, one per line. Lines starting with
`#` are comments. Whitespace trimmed.

| Key | Description |
| --- | --- |
| `VERIFY_URL` | Full URL of the backend's `/api/v1/sl/verify` endpoint. |
| `DEBUG_MODE` | `true`/`false`, default `true`. Recommended `true` in prod. |

Editing the notecard auto-resets the script via `CHANGED_INVENTORY`.

## Operations

In steady state:

- `SLPA Verification Terminal: ready (verify=...)` — startup ping.
- `SLPA Verification Terminal: touch from <name>` — when a user touches.
- `SLPA Verification Terminal: verify ok: userId=<n>` — successful link.
- `SLPA Verification Terminal: verify denied: <reason>` — backend rejected.

If silent for >5 minutes during expected traffic, check land permissions for
outbound HTTP and confirm the prim has the script + notecard.

## Troubleshooting

| Symptom | Likely cause |
| --- | --- |
| Startup says `incomplete config` | Notecard missing `VERIFY_URL`. |
| Startup says `notecard 'config' missing or unreadable` | Notecard not in prim, or named something other than exactly `config`. |
| Periodic `5xx` responses | Backend issue. Check server logs. |
| `403` responses | `X-SecondLife-Owner-Key` not in trusted set, or wrong shard. Confirm owner is an SLPA service avatar. |
| `✓` never appears after correct code | Possibly the dataserver event for DATA_BORN / DATA_PAYINFO was lost. The 30s data-timeout will fire and speak an error. User can re-touch. |

## Limits

- LSL listen cap is 65 active listens per script. The terminal opens exactly one
  listen per touch session and removes it on every exit path.
- LSL `dataserver` event is asynchronous and not guaranteed to fire if the
  avatar logs out mid-touch. The 30s timeout covers this.
- 60s lock means low-volume kiosks hit lock contention rarely. For high-volume
  locations, deploy multiple kiosks rather than queueing.

## Security

- The terminal must be owned by an SLPA service avatar listed in
  `slpa.sl.trusted-owner-keys`. Backend rejects `X-SecondLife-Owner-Key` not
  in that set.
- No shared secret in the notecard — header trust is sufficient because the
  endpoint is idempotent and verification codes are single-use server-side.
- Mainland-only: the script's `sim_channel` guard ("Second Life Server") refuses
  to run on Beta Grid (Aditi) — backend's `slpa.sl.expected-shard` enforces this
  at the HTTP layer too.
