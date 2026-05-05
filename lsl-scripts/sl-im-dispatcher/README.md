# SL IM Dispatcher

In-world component of the SLParcels SL IM notification channel. Polls the SLParcels
backend for pending IMs and delivers them via `llInstantMessage`.

## Architecture summary

- **Polling cadence:** 60 seconds when idle.
- **Per-IM cadence:** 2 seconds (matches SL's `llInstantMessage` floor).
- **Batch size:** up to 10 messages per poll, sized to fit comfortably within
  SL's 20-second HTTP request window.
- **Confirmation:** for each delivered IM, the script POSTs
  `/api/v1/internal/sl-im/{id}/delivered` to mark the row DELIVERED.
- **Authentication:** shared-secret bearer token, loaded from the `config`
  notecard at script start.

## Deployment

This is a **SLParcels-team-deployed object**, one per environment. Not user-deployed.

1. Rez a generic prim somewhere with reliable land permissions (group land or
   owner-controlled). Outbound HTTP must be permitted on that parcel.
2. Drop `dispatcher.lsl` into the prim.
3. Drop a copy of `config.notecard.example` renamed to **`config`** (no extension).
   Edit the values to match the target environment.
4. Reset the script (right-click → Edit → Reset Scripts in Selection).
5. Watch local chat for the startup message: `SL IM dispatcher: ready (poll=...)`.

Object ownership and modify permissions should be tightly scoped to SLParcels-team
avatars only. The shared secret is in the notecard, visible to anyone with
edit-rights on the object.

## Configuration

The `config` notecard format is `key=value` pairs, one per line. Lines starting
with `#` are comments. Whitespace is trimmed. Required keys:

| Key | Description |
| --- | --- |
| `POLL_URL` | Full URL of the backend's pending-messages endpoint. |
| `CONFIRM_URL_BASE` | URL prefix for delivery-confirmation posts. The script appends `{id}/delivered` to this base. |
| `SHARED_SECRET` | The bearer-token secret. Obtain from the server's `slpa.notifications.sl-im.dispatcher.shared-secret` configuration property. |
| `DEBUG_MODE` | `true` to enable per-poll owner chat (default); `false` to silence. Recommended `true` in prod for observability. |

### Rotating the shared secret

1. Update the deployment's secrets store with the new value.
2. Restart the SLParcels backend so it picks up the new secret.
3. Edit the `config` notecard with the new `SHARED_SECRET` value.
4. Reset the script (the `changed(CHANGED_INVENTORY)` handler also auto-resets,
   but a manual reset is faster).

In-flight pending rows are unaffected; the next poll uses the new secret.

## Operations

In steady state, with `DEBUG_MODE=true`, you'll see one of these every
60 seconds:

- `SL IM poll: 0 messages` — nothing pending; healthy.
- `SL IM batch=N` — N messages found; per-IM cadence kicks in.
- `SL IM confirm failed: status=N` — a confirmation HTTP call failed; the IM
  was still sent, the row in the backend may end up EXPIRED via the cleanup
  job. Investigate if these are frequent.
- `SL IM poll failed: N` — the poll HTTP call failed. Common causes: 401
  (secret mismatch), 500 (backend issue), 0 (network / permissions).

If you go silent for >5 minutes (no startup ping, no poll output), something is
wrong. Most common: notecard missing, notecard malformed, or the prim landed
on a parcel without outbound HTTP permission.

## Troubleshooting

| Symptom | Likely cause |
| --- | --- |
| Startup says `incomplete config` | Notecard missing one of `POLL_URL`, `CONFIRM_URL_BASE`, `SHARED_SECRET`. |
| Startup says `notecard 'config' missing or unreadable` | Notecard not in the prim, or named something other than exactly `config`. |
| Periodic `SL IM poll failed: 401` | Shared secret in notecard doesn't match the backend's configured value. |
| Periodic `SL IM poll failed: 500` | Backend issue. Check server logs. |
| `SL IM poll failed: 0` (no HTTP response) | Land permissions blocking outbound HTTP, or no-script land. Move the object. |
| Long silence in owner chat | Script may have wedged. Right-click → Edit → Reset Scripts. |
| Backend shows growing PENDING count | Dispatcher is dark. Check that the object is rezzed, not on no-script land, has the right script + notecard. |

## Limits

Three load-bearing notes:

- **Hard byte limit on IM text.** `llInstantMessage` truncates at 1024 BYTES
  (not characters). The backend's `SlImMessageBuilder` enforces this; the
  script trusts the messages it receives. **Operators should never modify
  `messageText` in the script.**
- **No `/failed` caller in this script.** `llInstantMessage` is fire-and-forget
  — no return value, no error, no way to distinguish "delivered" from "UUID
  doesn't exist." The script unconditionally calls `/{id}/delivered` after every
  IM. So FAILED rows in the database only ever come from manual operator
  intervention or a future revision of this script that adds UUID
  pre-validation (e.g., `llRequestAgentData` against `DATA_ONLINE` before
  sending). If `SELECT status, count(*) FROM sl_im_message GROUP BY status`
  shows zero FAILED rows, that's correct behavior, not a missing pipeline.
- **Batch size 10 with 2-second spacing fits the 20-second SL HTTP window.**
  Increasing batch size beyond 10 risks the script's confirmation requests
  racing the next poll cycle's request limits. Don't increase without verifying
  the math.

## Security

The shared secret is a deployment artifact; treat it like a database password.
Notecard contents are visible to anyone with object-edit rights, so the
dispatcher prim's ownership and modify permissions should be SLParcels-team-only.
A leaked shared secret means an attacker can pull the entire pending IM queue
and confirm-mark messages as DELIVERED, blocking real delivery — rotate
immediately if compromise is suspected.
