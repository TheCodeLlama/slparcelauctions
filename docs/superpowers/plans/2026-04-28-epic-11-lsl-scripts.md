# Epic 11 — LSL Scripts Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship 3 new in-world LSL scripts (Verification Terminal, Parcel Verifier, unified SLPA Terminal) on top of the already-shipped `sl-im-dispatcher`, completing Epic 11 / Phase 1 in-world surface.

**Architecture:** Each script lives under `lsl-scripts/<name>/` with a `.lsl` source, a `config.notecard.example` template, and a structured README. All four (including `sl-im-dispatcher`) follow the shared pattern: notecard config, mainland-only `sim_channel` guard, `CHANGED_INVENTORY` auto-reset, listen-handle hygiene, header trust + (where needed) shared-secret. The unified SLPA Terminal handles 4 touch-menu options (Escrow Payment, Listing Fee, Pay Penalty, Get Parcel Verifier) plus an HTTP-in command handler for backend-initiated PAYOUT/REFUND/WITHDRAW.

**Tech Stack:** Linden Scripting Language (LSL); HTTP via `llHTTPRequest`; HTTP-in via `llRequestURL` (registered with backend); JSON via `llJsonGetValue` / `llJson2List`; floating text via `llSetText`; pay handling via `llSetPayPrice` + `money()` event + `llTransferLindenDollars` / `transaction_result`.

**Validation model:** LSL has no native unit-test framework. Every task's gate is (a) the script must visibly correspond to the spec's state machine and pay-price matrix, and (b) listen handles, retry budgets, and lock TTLs match the spec exactly. After all tasks, final acceptance is: paste each `.lsl` into the SL viewer's script editor → zero compile errors.

**Spec reference:** `docs/superpowers/specs/2026-04-28-epic-11-lsl-scripts.md` (commit `1572a72`). Implementer subagents MUST read the relevant spec section before writing code — the plan defines task scope and file layout; the spec is the authoritative source for state-machine pseudocode, endpoint shapes, and pay-price matrix.

---

## File structure

```
lsl-scripts/
├── README.md                                 # MODIFIED — add 3 new entries to index
├── sl-im-dispatcher/                         # already shipped, untouched
├── verification-terminal/                    # NEW
│   ├── verification-terminal.lsl             # NEW
│   ├── config.notecard.example               # NEW
│   └── README.md                             # NEW
├── parcel-verifier/                          # NEW
│   ├── parcel-verifier.lsl                   # NEW
│   ├── config.notecard.example               # NEW
│   └── README.md                             # NEW
└── slpa-terminal/                            # NEW
    ├── slpa-terminal.lsl                     # NEW
    ├── config.notecard.example               # NEW
    └── README.md                             # NEW
```

---

## Task 1: Verification Terminal — script + notecard + README

**Files:**
- Create: `lsl-scripts/verification-terminal/verification-terminal.lsl`
- Create: `lsl-scripts/verification-terminal/config.notecard.example`
- Create: `lsl-scripts/verification-terminal/README.md`

**Spec reference:** §3 (shared pattern), §4 (Verification Terminal full state machine + notecard + deployment).

- [ ] **Step 1: Read the spec sections**

The implementer must read `docs/superpowers/specs/2026-04-28-epic-11-lsl-scripts.md` §3 (shared pattern) and §4 (Verification Terminal) before writing code. The spec contains the authoritative state-machine pseudocode and the pay-price matrix (none for this script — verification doesn't accept payment).

- [ ] **Step 2: Create directory and write `config.notecard.example`**

Path: `lsl-scripts/verification-terminal/config.notecard.example`

```
# config notecard for SLPA Verification Terminal
# Place this file (renamed to "config", no extension) in the prim's contents.
# After editing, the script auto-resets via CHANGED_INVENTORY.

VERIFY_URL=https://api.slparcelauctions.com/api/v1/sl/verify

# Optional: set to "false" in prod to silence per-touch chat.
DEBUG_OWNER_SAY=true
```

- [ ] **Step 3: Write `verification-terminal.lsl`**

Path: `lsl-scripts/verification-terminal/verification-terminal.lsl`

This script translates spec §4.3's state machine into compiling LSL. Reference the existing `lsl-scripts/sl-im-dispatcher/dispatcher.lsl` for the notecard parser pattern. The script must:

1. **State globals** at the top: `VERIFY_URL`, `DEBUG_OWNER_SAY`, `notecardLineRequest`, `notecardLineNum`, lock state (`lockHolder`, `lockHolderName`, `lockExpiresAt`), avatar data buffers (`storedCode`, `storedAvatarUuid`, `storedAvatarName`, `storedDisplayName`, `storedUsername`, `storedBornDate`, `storedPayInfo`, `bornArrived`, `payArrived`), `bornReqKey`, `payReqKey`, `httpReqId`, `listenHandle`, `menuChan` (random negative integer).

2. **Helper functions:**
   - `parseConfigLine(line)` — same pattern as `sl-im-dispatcher`'s parser: strip `#` comments, find `=`, set globals by key.
   - `readNotecardLine(n)` — wraps `llGetNotecardLine("config", n)`.
   - `setBusyChrome()` — `llSetText("SLPA Verification Terminal\n<In Use>", <1.0,0.2,0.2>, 1.0)` + `llSetObjectName("SLPA Verification Terminal <In Use>")`.
   - `setIdleChrome()` — `llSetText("SLPA Verification Terminal\nTouch to link your account", <1.0,1.0,1.0>, 1.0)` + `llSetObjectName("SLPA Verification Terminal")`.
   - `releaseLock()` — clears lock state, `llListenRemove(listenHandle)` if not -1, sets idle chrome, cancels timer.
   - `isSixDigitCode(s)` — LSL has no regex; check `llStringLength(s) == 6 && llSubStringIndex("0123456789", llGetSubString(s, 0, 0)) >= 0` for each char (or use a loop).
   - `postVerifyRequest()` — builds JSON body and calls `llHTTPRequest(VERIFY_URL, [HTTP_METHOD,"POST", HTTP_MIMETYPE,"application/json", HTTP_BODY_MAXLENGTH,16384], jsonBody)`.

3. **Events:**
   - `state_entry` — initialize globals, `llRequestPermissions` not needed (no debit), set idle chrome, `llSetPayPrice(PAY_HIDE, [PAY_HIDE,PAY_HIDE,PAY_HIDE,PAY_HIDE])`, start notecard read at line 0.
   - `dataserver(requested, data)` — handles BOTH notecard reads (if `requested == notecardLineRequest`) AND avatar-data reads (if `requested == bornReqKey` or `payReqKey`). Notecard EOF: validate `VERIFY_URL != ""`; if missing, owner-say + halt. Avatar data: store + when both arrived, fire HTTP. Note: `DATA_BORN` returns `"YYYY-MM-DD"`, `DATA_PAYINFO` returns integer-as-string.
   - `touch_start(num_detected)` — capture `llDetectedKey(0)` + `llDetectedName(0)`. Check lock TTL. If locked: `llRegionSayTo(toucher, 0, "Terminal busy — currently verifying " + lockHolderName + ".")` and return. Else acquire lock with `lockExpiresAt = llGetUnixTime() + 60`, set busy chrome, also stash `storedAvatarUuid`, `storedAvatarName`, `storedDisplayName = llGetDisplayName(toucher)`, `storedUsername = llGetUsername(toucher)`, open `listenHandle = llListen(menuChan, "", toucher, "")`, `llTextBox(toucher, "Enter your 6-digit SLPA code:", menuChan)`, start `llSetTimerEvent(60.0)` for lock timeout.
   - `listen(channel, name, id, message)` — only handle when `channel == menuChan && id == lockHolder`. `llListenRemove(listenHandle); listenHandle = -1`. If `!isSixDigitCode(message)`: `llRegionSayTo(lockHolder, 0, "✗ Code must be 6 digits.")` + `releaseLock()`. Else: `storedCode = message`, fire `bornReqKey = llRequestAgentData(lockHolder, DATA_BORN); payReqKey = llRequestAgentData(lockHolder, DATA_PAYINFO); bornArrived = FALSE; payArrived = FALSE`. Reset timer to 30s for data timeout.
   - `http_response(req, status, meta, body)` — only handle if `req == httpReqId`. On 200: parse `verified`, `userId`, `slAvatarName` from JSON. If `verified == "true"` (LSL JSON bools are strings): `llRegionSayTo(lockHolder, 0, "✓ Linked SLPA #" + userId + " to " + slAvatarName + ".")`. Else: `llRegionSayTo(lockHolder, 0, "✗ Verification failed. Code may be expired — generate a new one on slparcelauctions.com.")`. On 4xx: parse problem-detail `title` + `detail`, speak. On 5xx / 0: speak "✗ Backend unreachable. Try again in a moment." Always: `releaseLock()`.
   - `timer()` — distinguishes data timeout vs lock timeout via state. If avatar-data was requested but not both arrived: speak "✗ Couldn't read your avatar data — please try again." + `releaseLock()`. Else (general lock TTL hit): `releaseLock()`. Set timer to 0 after firing.
   - `changed(change)` — `if (change & CHANGED_INVENTORY) llResetScript();`.
   - `on_rez(start_param)` — `llResetScript();`.

4. **Sentinel values:** `listenHandle = -1`, `httpReqId = NULL_KEY`, `bornReqKey = NULL_KEY`, `payReqKey = NULL_KEY`, `lockHolder = NULL_KEY`, `lockExpiresAt = 0`, `bornArrived = FALSE`, `payArrived = FALSE`.

5. **JSON construction**: LSL has `llJsonSetValue` to build JSON; or template strings. The implementer should use `llList2Json(JSON_OBJECT, ["verificationCode", storedCode, "avatarUuid", (string)storedAvatarUuid, ...])` to build the body cleanly.

6. **Critical**: every exit path (success, failure, timeout) calls `releaseLock()` which is responsible for `llListenRemove`. No handle leaks.

The full script will be ~150 LSL lines. Match the pseudocode in spec §4.3 exactly; differences must be justified by LSL syntax requirements only.

- [ ] **Step 4: Write `README.md`**

Path: `lsl-scripts/verification-terminal/README.md`

Structure mirrors `lsl-scripts/sl-im-dispatcher/README.md`:

```markdown
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
| `DEBUG_OWNER_SAY` | `true`/`false`, default `true`. Recommended `true` in prod. |

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
```

- [ ] **Step 5: Smoke-validate the script's syntactic correctness**

There's no LSL compiler outside the SL viewer. The implementer should:
1. Re-read the script end-to-end and trace every code path against spec §4.3.
2. Verify every `llListen` is matched by an `llListenRemove` on every exit path.
3. Verify event signatures are LSL-spec-correct (e.g., `dataserver(key requested, string data)` not `(integer, string)`).
4. Verify all sentinel values are initialized (`-1`, `NULL_KEY`, `0`, `FALSE`).

If anything looks off, fix inline.

- [ ] **Step 6: Stage files for commit**

```bash
git add lsl-scripts/verification-terminal/
```

Note: The repository's gitignore may match `lsl-scripts/` indirectly via a `**/superpowers/` rule check — verify with `git status` first; if files don't appear, use `git add -f`. The implementer subagent MUST stage but NOT commit (parent commits to avoid pre-commit hook contention with stale Task #1).

---

## Task 2: Parcel Verifier — script + notecard + README

**Files:**
- Create: `lsl-scripts/parcel-verifier/parcel-verifier.lsl`
- Create: `lsl-scripts/parcel-verifier/config.notecard.example`
- Create: `lsl-scripts/parcel-verifier/README.md`

**Spec reference:** §3 (shared pattern), §5 (Parcel Verifier full state machine + auto-prompt-on-rez pattern + group-owned land handling).

- [ ] **Step 1: Read the spec sections**

The implementer must read `docs/superpowers/specs/2026-04-28-epic-11-lsl-scripts.md` §5 carefully — the auto-prompt-on-rez flow and the group-owned-land short-circuit semantics are easy to misimplement. Also re-read §3 for the shared pattern.

- [ ] **Step 2: Write `config.notecard.example`**

Path: `lsl-scripts/parcel-verifier/config.notecard.example`

```
# config notecard for SLPA Parcel Verifier (rezzable)
# Place this file (renamed to "config", no extension) in the prim's contents
# BEFORE you rez it on a parcel — once rezzed it self-destructs after one
# verification attempt.

PARCEL_VERIFY_URL=https://api.slparcelauctions.com/api/v1/sl/parcel/verify

# Optional: set to "false" to silence chat.
DEBUG_OWNER_SAY=true
```

- [ ] **Step 3: Write `parcel-verifier.lsl`**

Path: `lsl-scripts/parcel-verifier/parcel-verifier.lsl`

This script translates spec §5.4 to compiling LSL. It's the simplest of the three (~100 LSL lines). The script must:

1. **State globals:** `PARCEL_VERIFY_URL`, `DEBUG_OWNER_SAY`, notecard read state, parcel data fields (`parcelUuid`, `ownerUuid`, `groupUuid`, `parcelName`, `description`, `areaSqm`, `primCapacity`, `pos`, `rezzer`, `verificationCode`), `listenHandle`, `httpReqId`, `codeChan`, phase enum (`PHASE_AWAITING_CODE`, `PHASE_HTTP_INFLIGHT`).

2. **Helpers:** `parseConfigLine`, `readNotecardLine`, `isSixDigitCode` (same as Task 1), `dieWithMessage(msg)` — `llOwnerSay(msg)` then `llDie()`.

3. **Events:**
   - `state_entry` — initialize globals, set `listenHandle = -1`, start notecard read at line 0.
   - `dataserver` — only handles notecard reads here (no avatar data needed). On EOF: validate `PARCEL_VERIFY_URL != ""`; if missing, `llOwnerSay("✗ Parcel Verifier: incomplete config — PARCEL_VERIFY_URL required") + llDie()`. Else: kick off the verification flow:
     - Guard: `if (llGetEnv("sim_channel") != "Second Life Server") dieWithMessage("✗ Wrong grid.")`.
     - Read parcel data: `list parcelData = llGetParcelDetails(llGetPos(), [PARCEL_DETAILS_ID, PARCEL_DETAILS_OWNER, PARCEL_DETAILS_GROUP, PARCEL_DETAILS_NAME, PARCEL_DETAILS_DESC, PARCEL_DETAILS_AREA, PARCEL_DETAILS_PRIM_CAPACITY]);` then index out each field with `(key)llList2String(parcelData, 0)` etc.
     - Capture `pos = llGetPos(); rezzer = llGetOwner();`.
     - Owner short-circuit: `if (ownerUuid != rezzer && groupUuid == NULL_KEY) dieWithMessage("✗ This parcel isn't yours. Please rez on land you own.")`.
     - Open listen + auto-prompt: `listenHandle = llListen(codeChan, "", rezzer, ""); llTextBox(rezzer, "Enter your 6-digit PARCEL code:", codeChan);` then `llSetTimerEvent(90.0)` for code-entry timeout.
     - `phase = PHASE_AWAITING_CODE`.
   - `listen(channel, name, id, message)` — only handle when `channel == codeChan && id == rezzer`. `llListenRemove(listenHandle); listenHandle = -1`. If `!isSixDigitCode(message)`: `dieWithMessage("✗ Code must be 6 digits.")`. Else: `verificationCode = message`. Build JSON body via `llList2Json(JSON_OBJECT, ["verificationCode", verificationCode, "parcelUuid", (string)parcelUuid, "ownerUuid", (string)ownerUuid, "parcelName", parcelName, "areaSqm", (string)areaSqm, "description", description, "primCapacity", (string)primCapacity, "regionPosX", (string)pos.x, "regionPosY", (string)pos.y, "regionPosZ", (string)pos.z])` — note that LSL `llList2Json` does NOT auto-quote integers, so for numeric fields use `(string)` to convert and rely on the backend's Jackson converter being lenient OR use `JSON_NUMBER` style. Easier path: build the body string manually using `"\""+key+"\":"+value+...` — see the dispatcher.lsl JSON-write idioms for reference. Then `httpReqId = llHTTPRequest(PARCEL_VERIFY_URL, [HTTP_METHOD,"POST", HTTP_MIMETYPE,"application/json", HTTP_BODY_MAXLENGTH,16384], body);` and `phase = PHASE_HTTP_INFLIGHT`. Reset timer to 30s for HTTP timeout.
   - `http_response(req, status, meta, body)` — only if `req == httpReqId`. 204: `dieWithMessage("✓ Parcel verified — your listing is live on slparcelauctions.com.")`. 4xx: parse `title` + `detail`, `dieWithMessage("✗ " + title + ": " + detail)`. 5xx / 0: `dieWithMessage("✗ Backend unreachable. Please rez again in a moment.")`.
   - `timer()` — fires on either code-entry timeout (90s) or HTTP timeout (30s). Distinguish via `phase`. PHASE_AWAITING_CODE: `llListenRemove(listenHandle); dieWithMessage("✗ Timed out waiting for code.")`. PHASE_HTTP_INFLIGHT: `dieWithMessage("✗ Timed out reaching SLPA.")`.
   - `changed(change)` — `if (change & CHANGED_INVENTORY) llResetScript();`. Note: `on_rez` is INTENTIONALLY not handled here — the script's full lifecycle starts at `state_entry`, and `on_rez` would re-trigger the whole flow on a re-rez (which doesn't happen because it dies). The state_entry path covers initial deployment.

4. **Important LSL detail:** `llGetParcelDetails` returns a list of strings; indices match the order of the requested keys. Cast `(integer)` for area / primCapacity, `(key)` for UUIDs, leave name/desc as strings.

5. **JSON quoting:** numeric fields in `SlParcelVerifyRequest` are typed (`Integer areaSqm`, `Double regionPosX`, etc.). The body must NOT quote them. Build manually:
   ```lsl
   string body = "{"
       + "\"verificationCode\":\"" + verificationCode + "\","
       + "\"parcelUuid\":\"" + (string)parcelUuid + "\","
       + "\"ownerUuid\":\"" + (string)ownerUuid + "\","
       + "\"parcelName\":\"" + escapeJson(parcelName) + "\","
       + "\"areaSqm\":" + (string)areaSqm + ","
       + "\"description\":\"" + escapeJson(description) + "\","
       + "\"primCapacity\":" + (string)primCapacity + ","
       + "\"regionPosX\":" + (string)pos.x + ","
       + "\"regionPosY\":" + (string)pos.y + ","
       + "\"regionPosZ\":" + (string)pos.z
       + "}";
   ```
   Where `escapeJson(s)` is a helper that replaces `"` with `\"` and `\` with `\\`. The implementer must include that helper.

The full script will be ~120 LSL lines.

- [ ] **Step 4: Write `README.md`**

Path: `lsl-scripts/parcel-verifier/README.md`

Same structure as Task 1's README. Key differences:

```markdown
# SLPA Parcel Verifier (rezzable)

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
   modify-NO setting prevents accidental edits that would void SLPA service
   account ownership.
2. **SLPA Terminal "Get Parcel Verifier" menu** — `llGiveInventory` from a
   deployed SLPA Terminal. Sellers who don't have a Marketplace copy can
   pick one up from any kiosk.

To set up the Marketplace listing:

1. Rez a generic prim. Give it a small visual marker (a flag or beacon).
2. Drop `parcel-verifier.lsl` into the prim.
3. Drop the `config` notecard with `PARCEL_VERIFY_URL` set.
4. Set permissions: transfer YES, copy YES, modify NO. Owner: SLPA service
   avatar.
5. Take the object back into inventory and list on Marketplace as a free item.

## Configuration

| Key | Description |
| --- | --- |
| `PARCEL_VERIFY_URL` | Full URL of `/api/v1/sl/parcel/verify`. |
| `DEBUG_OWNER_SAY` | `true`/`false`, default `true`. |

## Operations

The script speaks via `llOwnerSay` (visible only to the rezzer). Expected
chat after rez:

- `✓ Parcel verified — your listing is live on slparcelauctions.com.` (success)
- `✗ This parcel isn't yours. Please rez on land you own.` (owner mismatch)
- `✗ Code must be 6 digits.` (bad input)
- `✗ <title>: <detail>` (backend rejection — code expired, parcel mismatch, etc.)
- `✗ Backend unreachable. Please rez again in a moment.` (5xx / network)
- `✗ Timed out waiting for code.` (90s with no code entered)
- `✗ Timed out reaching SLPA.` (30s with no HTTP response)

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

- Object owner must be an SLPA service avatar listed in
  `slpa.sl.trusted-owner-keys`. The backend rejects `X-SecondLife-Owner-Key`
  not in that set.
- The `X-SecondLife-Owner-Key` header on the outbound request identifies the
  *rezzer* (the seller), not the parcel owner. Backend cross-checks against
  the verification code's bound user.
- Modify NO permission prevents tampering with the script after distribution.
```

- [ ] **Step 5: Re-read the script vs spec**

Same syntactic-correctness pass as Task 1, Step 5. Special attention to:
- Group-owned land short-circuit logic (spec §5.5).
- JSON numeric quoting (must NOT quote integers/doubles for `areaSqm`, `primCapacity`, `regionPosX/Y/Z`).
- `llDie()` is called on every exit path including timeouts.

- [ ] **Step 6: Stage files for commit**

```bash
git add lsl-scripts/parcel-verifier/
```

(Use `-f` if gitignore matches.)

---

## Task 3: SLPA Terminal — script

**Files:**
- Create: `lsl-scripts/slpa-terminal/slpa-terminal.lsl`

**Spec reference:** §3 (shared pattern), §6 (full state machine), §6.4 (pay-price matrix — easy to misread!), §6.5 (lock semantics), §6.6 (HTTP-in command handler).

- [ ] **Step 1: Read the spec sections thoroughly**

This is the largest LSL deliverable (~400 lines). The implementer must read §6 in full, paying special attention to:
- Pay-price matrix in §6.4 (the IDLE-vs-AWAITING_PAYMENT first-arg distinction is the spec's most error-prone moment).
- The new `AWAITING_AUCTION_ID` state added to handle `EscrowPaymentRequest.auctionId @NotNull`.
- Lock-release-on-first-POST + background-retry pattern in §6.3 (PAYMENT_INFLIGHT).
- HTTP-in handler is independent of touch-state lock (§6.6).
- Region-restart re-registration via `changed(CHANGED_REGION_START)`.

- [ ] **Step 2: Write `slpa-terminal.lsl`**

Path: `lsl-scripts/slpa-terminal/slpa-terminal.lsl`

The script combines the touch-driven payment menu, the HTTP-in command receiver, and the registration lifecycle. Translate spec §6.3's full state machine into LSL.

**State globals (top of script):**
```
// Notecard config
string REGISTER_URL = "";
string ESCROW_PAYMENT_URL = "";
string LISTING_FEE_URL = "";
string PENALTY_LOOKUP_URL = "";
string PENALTY_PAYMENT_URL = "";
string PAYOUT_RESULT_URL = "";
string SHARED_SECRET = "";
string TERMINAL_ID = "";
string REGION_NAME = "";
integer DEBUG_OWNER_SAY = TRUE;

// Notecard reading state
key notecardLineRequest = NULL_KEY;
integer notecardLineNum = 0;

// HTTP-in URL (received from llRequestURL)
string httpInUrl = "";
key urlRequestId = NULL_KEY;

// Permissions / startup
integer debitGranted = FALSE;
integer registered = FALSE;
key registerReqId = NULL_KEY;
integer registerAttempt = 0;          // for bounded retry of /terminal/register
integer registerNextRetryAt = 0;       // unix time

// Touch session lock
key lockHolder = NULL_KEY;
string lockHolderName = "";
integer lockExpiresAt = 0;
integer listenHandle = -1;

// Touch state machine
integer SELECTED_NONE = 0;
integer SELECTED_ESCROW = 1;
integer SELECTED_LISTING_FEE = 2;
integer SELECTED_PENALTY = 3;
integer selectedKind = 0;              // SELECTED_*
integer selectedAuctionId = 0;
integer expectedPenaltyAmount = 0;     // for PENALTY menu

// Touch state
integer STATE_IDLE = 0;
integer STATE_MENU_OPEN = 1;
integer STATE_AWAITING_AUCTION_ID = 2;
integer STATE_LOOKUP_INFLIGHT = 3;
integer STATE_AWAITING_PAYMENT = 4;
integer touchState = 0;
key lookupReqId = NULL_KEY;

// Channels (set in state_entry to random negative integers)
integer menuChan = 0;
integer auctionIdChan = 0;

// Background payment retry (NOT held against lock)
key paymentReqId = NULL_KEY;
key paymentPayer = NULL_KEY;
integer paymentAmount = 0;
string paymentTxKey = "";              // synthesized via llGenerateKey
integer paymentKind = 0;               // SELECTED_*
integer paymentAuctionId = 0;
integer paymentRetryCount = 0;
integer paymentNextRetryAt = 0;

// HTTP-in command tracking (parallel to touch state)
// Stores at most N inflight commands keyed by transaction id
list inflightCmdTxKeys = [];           // keys
list inflightCmdIdempotencyKeys = [];
list inflightCmdRecipients = [];
list inflightCmdAmounts = [];
integer MAX_INFLIGHT_CMDS = 16;

// HTTP-in pending response (for ack on /payout-result)
key payoutResultReqId = NULL_KEY;

// Timer phase
integer TIMER_NONE = 0;
integer TIMER_LOCK_TTL = 1;
integer TIMER_PAYMENT_RETRY = 2;
integer TIMER_REGISTER_RETRY = 3;
integer timerPhase = 0;
```

**Helper functions:**
- `parseConfigLine(string line)` — same pattern as `dispatcher.lsl`. Sets each global by key.
- `readNotecardLine(integer n)` — `notecardLineRequest = llGetNotecardLine("config", n);`.
- `isSixDigitCode(string s)` — for menu auction-ID isn't 6-digit; instead use `isPositiveInteger(string s)` here:
  ```lsl
  integer isPositiveInteger(string s) {
      integer len = llStringLength(s);
      if (len == 0 || len > 9) return FALSE;
      integer i;
      for (i = 0; i < len; ++i) {
          string c = llGetSubString(s, i, i);
          if (llSubStringIndex("0123456789", c) < 0) return FALSE;
      }
      return ((integer)s) > 0;
  }
  ```
- `escapeJson(string s)` — replace `"` with `\"`, `\` with `\\`.
- `setBusyChrome()` — `llSetText("SLPA Terminal\n<In Use>", <1.0,0.2,0.2>, 1.0); llSetObjectName("SLPA Terminal <In Use>");`.
- `setIdleChrome()` — `llSetText("SLPA Terminal\nTouch for options", <1.0,1.0,1.0>, 1.0); llSetObjectName("SLPA Terminal");`.
- `releaseLock()` — clears `lockHolder`, `lockHolderName`, `lockExpiresAt`, `selectedKind`, `selectedAuctionId`, `expectedPenaltyAmount`, `touchState = STATE_IDLE`. `llListenRemove(listenHandle)` if `!= -1`. Calls `setIdleChrome()`. Restores IDLE pay price: `llSetPayPrice(PAY_HIDE, [PAY_HIDE,PAY_HIDE,PAY_HIDE,PAY_HIDE]);`. Cancels lock-TTL timer.
- `extendLock(integer seconds)` — sets `lockExpiresAt = llGetUnixTime() + seconds`, `llSetTimerEvent((float)seconds)`, `timerPhase = TIMER_LOCK_TTL`.
- `acquireLock(key holder, string name)` — sets `lockHolder = holder`, `lockHolderName = name`, sets busy chrome, calls `extendLock(60)`.
- `postRegister()` — builds JSON `{"terminalId":"...","httpInUrl":"...","regionName":"...","sharedSecret":"..."}`, fires `registerReqId = llHTTPRequest(REGISTER_URL, ..., body)`.
- `scheduleRegisterRetry(integer attempt)` — picks next backoff (10/30/90/300/900s) by attempt index; sets `registerNextRetryAt`, `timerPhase = TIMER_REGISTER_RETRY`, `llSetTimerEvent(...)`.
- `firePayment()` — uses `paymentKind` to pick endpoint and build body. Synthesizes `paymentTxKey = (string)llGenerateKey()` ONCE per payment (not per retry — same key for idempotency). Fires `paymentReqId = llHTTPRequest(...)`.
- `schedulePaymentRetry()` — backoff schedule 10/30/90/300/900s by `paymentRetryCount`; sets `paymentNextRetryAt`, `timerPhase = TIMER_PAYMENT_RETRY`. After 5 attempts, owner-say CRITICAL + clear payment state.
- `addInflightCommand(key txKey, string idempotencyKey, key recipient, integer amount)` — appends to inflight lists; if length > `MAX_INFLIGHT_CMDS`, owner-say "queueing payment retry" and drop oldest (or refuse — implementer's choice; spec is not strict here). Recommend: refuse new commands if at cap; backend retry budget covers.
- `removeInflightByTxKey(key txKey)` — finds index, returns `(idempotencyKey, recipient, amount)` triple, removes from all four lists.

**Events:**

1. `state_entry()`:
   - Reset all globals to defaults.
   - Set channels: `menuChan = -100000 - (integer)(llFrand(50000.0)); auctionIdChan = menuChan - 1;`.
   - Mainland guard: `if (llGetEnv("sim_channel") != "Second Life Server") { llOwnerSay("CRITICAL: SLPA Terminal must run on the main grid (sim_channel != \"Second Life Server\")."); return; }`.
   - Start notecard read at line 0.

2. `dataserver(key requested, string data)`:
   - Only handles notecard reads. On `requested == notecardLineRequest`:
     - On NAK: `llOwnerSay("SLPA Terminal: notecard 'config' missing or unreadable"); return;`.
     - On EOF: validate all required keys non-empty (the six URLs + SHARED_SECRET). On any missing: `llOwnerSay("SLPA Terminal: incomplete config — REGISTER_URL / ESCROW_PAYMENT_URL / LISTING_FEE_URL / PENALTY_LOOKUP_URL / PENALTY_PAYMENT_URL / PAYOUT_RESULT_URL / SHARED_SECRET required");`. Apply defaults for `TERMINAL_ID = (string)llGetKey()` and `REGION_NAME = llGetRegionName()` if empty. Then request DEBIT permission: `llRequestPermissions(llGetOwner(), PERMISSION_DEBIT);`.
     - Else: `parseConfigLine(data); readNotecardLine(notecardLineNum + 1);`.

3. `run_time_permissions(integer perm)`:
   - If `perm & PERMISSION_DEBIT`: `debitGranted = TRUE;` then request URL: `urlRequestId = llRequestURL();`.
   - Else: `llOwnerSay("CRITICAL: PERMISSION_DEBIT denied — script halted. Owner must re-grant.");`. Halt (no further state advances).

4. `http_request(key reqId, string method, string body)`:
   - This event handles BOTH the URL grant and incoming HTTP-in commands.
   - If `method == URL_REQUEST_GRANTED`:
     - `httpInUrl = body;` then `postRegister();`.
   - Else if `method == URL_REQUEST_DENIED`:
     - `llOwnerSay("CRITICAL: HTTP-in URL request denied — region may not allow scripts to request URLs. Halting."); return;`.
   - Else if `method == "POST"`:
     - Parse `TerminalCommandBody` JSON: `action`, `purpose`, `recipientUuid`, `amount`, `escrowId`, `listingFeeRefundId`, `idempotencyKey`, `sharedSecret`.
     - Constant-time secret compare: `if (sharedSecret != SHARED_SECRET) { llHTTPResponse(reqId, 403, "{\"error\":\"secret mismatch\"}"); return; }`. (Note: LSL string equality isn't constant-time; the secret-leak threat is mitigated by HTTPS not LSL constant-time; spec accepts this.)
     - If at inflight cap: `llHTTPResponse(reqId, 503, "{\"error\":\"terminal busy\"}"); return;`.
     - `llHTTPResponse(reqId, 200, "{\"ack\":true}");`.
     - Action switch (PAYOUT, REFUND, WITHDRAW all handled identically — just `llTransferLindenDollars`):
       ```lsl
       key txKey = llTransferLindenDollars((key)recipientUuid, amount);
       addInflightCommand(txKey, idempotencyKey, (key)recipientUuid, amount);
       ```

5. `transaction_result(key id, integer success, string data)`:
   - Look up `id` in inflight via `removeInflightByTxKey(id)`. If not found: log + return.
   - Build payout-result body: `{"idempotencyKey":..., "success": <true/false>, "slTransactionKey": data, "errorMessage": <data on fail else null>, "terminalId":..., "sharedSecret":...}`.
   - `payoutResultReqId = llHTTPRequest(PAYOUT_RESULT_URL, ..., body);`.

6. `http_response(key req, integer status, list meta, string body)`:
   - Distinguish by request id:
     - `req == registerReqId`: 200 → `registered = TRUE; registerReqId = NULL_KEY; if (DEBUG_OWNER_SAY) llOwnerSay("SLPA Terminal: registered (terminal_id=" + TERMINAL_ID + ", url=" + httpInUrl + ")");`. 4xx/5xx/0 → `scheduleRegisterRetry(++registerAttempt); if (registerAttempt > 5) llOwnerSay("CRITICAL: registration failed after 5 attempts.");`.
     - `req == lookupReqId`: handle penalty lookup response per spec §6.3 LOOKUP_INFLIGHT state. 404 or `owed=0` → speak "✓ No penalty on file." + `releaseLock()`. 200 with `owed > 0` → `selectedKind = SELECTED_PENALTY; expectedPenaltyAmount = owed`, speak balance, `llSetPayPrice(owed, [owed, owed/2, owed/4, PAY_HIDE]); extendLock(60); touchState = STATE_AWAITING_PAYMENT;`. 5xx/0 → speak "✗ Lookup failed — try again." + `releaseLock()`.
     - `req == paymentReqId`: handle payment response per spec §6.3 PAYMENT_INFLIGHT state. 200 → parse status field, speak appropriate confirmation, clear payment state. 4xx → speak detail + CRITICAL log, clear payment state. 5xx/0 → `paymentRetryCount++; schedulePaymentRetry();` (the lock has already released; retries run via timer).
     - `req == payoutResultReqId`: 200 → log success at debug. 4xx/5xx/0 → `llOwnerSay("CRITICAL: /payout-result POST failed status=" + (string)status + " — backend will retry from terminal_commands ledger.");`. (Backend's retry budget is the safety net.)

7. `touch_start(integer N)`:
   - Capture `key toucher = llDetectedKey(0); string toucherName = llDetectedName(0);`.
   - Lock check: `if (lockHolder != NULL_KEY && lockExpiresAt > llGetUnixTime()) { llRegionSayTo(toucher, 0, "Terminal busy with " + lockHolderName + ". Try again in 60s."); return; }`.
   - `acquireLock(toucher, toucherName); listenHandle = llListen(menuChan, "", toucher, ""); llDialog(toucher, "What do you need?", ["Escrow Payment", "Listing Fee", "Pay Penalty", "Get Parcel Verifier"], menuChan); touchState = STATE_MENU_OPEN;`.

8. `listen(integer channel, string name, key id, string message)`:
   - If `channel == menuChan && id == lockHolder`:
     - `llListenRemove(listenHandle); listenHandle = -1;`.
     - Switch on `message`:
       - `"Escrow Payment"`: `selectedKind = SELECTED_ESCROW; listenHandle = llListen(auctionIdChan, "", lockHolder, ""); llTextBox(lockHolder, "Enter the Auction ID from your auction page:", auctionIdChan); extendLock(60); touchState = STATE_AWAITING_AUCTION_ID;`.
       - `"Listing Fee"`: same as Escrow but `selectedKind = SELECTED_LISTING_FEE` and prompt text is `"Enter the Auction ID from your draft listing:"`.
       - `"Pay Penalty"`: build lookup body `{"slAvatarUuid":"...","terminalId":"..."}`, fire `lookupReqId = llHTTPRequest(PENALTY_LOOKUP_URL, ..., body); touchState = STATE_LOOKUP_INFLIGHT; extendLock(30);` (shorter — lookup is fast).
       - `"Get Parcel Verifier"`: `llGiveInventory(lockHolder, "SLPA Parcel Verifier"); llRegionSayTo(lockHolder, 0, "Sent! Rez it on your parcel and enter your 6-digit PARCEL code."); releaseLock();`.
   - Else if `channel == auctionIdChan && id == lockHolder`:
     - `llListenRemove(listenHandle); listenHandle = -1;`.
     - If `!isPositiveInteger(message)`: speak `"✗ Invalid auction ID — must be a positive number."` + `releaseLock();` return.
     - `selectedAuctionId = (integer)message;`.
     - Speak appropriate "Pay the L$ ... shown on auction #N" message based on `selectedKind`.
     - `llSetPayPrice(PAY_DEFAULT, [PAY_HIDE, PAY_HIDE, PAY_HIDE, PAY_HIDE]); extendLock(60); touchState = STATE_AWAITING_PAYMENT;`.

9. `money(key payer, integer amount)`:
   - Only honor if `touchState == STATE_AWAITING_PAYMENT`. (Otherwise the lock is gone — the L$ should not have been accepted; LSL doesn't let you reject after the fact, but `llSetPayPrice(PAY_HIDE,...)` in IDLE prevents this from happening in normal flow. Defensive: log and accept anyway, backend disambiguates.)
   - If `payer != lockHolder && DEBUG_OWNER_SAY`: `llOwnerSay("Note: payment from " + (string)payer + " is not the menu user " + (string)lockHolder);`.
   - Capture `paymentPayer = payer; paymentAmount = amount; paymentKind = selectedKind; paymentAuctionId = selectedAuctionId; paymentTxKey = (string)llGenerateKey(); paymentRetryCount = 0;`.
   - `firePayment();`.
   - `releaseLock();` — release IMMEDIATELY so a second user can touch while retries run in background.
   - `llSetPayPrice(PAY_HIDE, [PAY_HIDE,PAY_HIDE,PAY_HIDE,PAY_HIDE]);` — return to no-pay until next touch.

10. `timer()`:
    - Switch on `timerPhase`:
      - `TIMER_LOCK_TTL`: lock TTL hit (60s of inactivity). Per `touchState`:
        - STATE_MENU_OPEN, STATE_AWAITING_AUCTION_ID, STATE_AWAITING_PAYMENT: `releaseLock();`.
        - STATE_LOOKUP_INFLIGHT: ignore (HTTP response will fire releaseLock).
      - `TIMER_PAYMENT_RETRY`: `firePayment();` and `if (DEBUG_OWNER_SAY) llOwnerSay("SLPA Terminal: payment retry " + (string)paymentRetryCount + "/5");`.
      - `TIMER_REGISTER_RETRY`: `postRegister();` and `if (DEBUG_OWNER_SAY) llOwnerSay("SLPA Terminal: register retry " + (string)registerAttempt + "/5");`.
    - Set `llSetTimerEvent(0);` after firing — the next state transition sets a new timer if needed.

11. `changed(integer change)`:
    - `if (change & CHANGED_INVENTORY) { llResetScript(); }` — covers notecard edits AND parcel-verifier inventory updates.
    - `if (change & CHANGED_REGION_START) { httpInUrl = ""; registered = FALSE; urlRequestId = llRequestURL(); }` — region restart drops the URL; re-request and re-register.

12. `on_rez(integer start_param)`:
    - `llResetScript();`.

**Pay-price matrix verification (read carefully):**
- IDLE: `llSetPayPrice(PAY_HIDE, [PAY_HIDE,PAY_HIDE,PAY_HIDE,PAY_HIDE]);` — first arg PAY_HIDE = no text input field, all buttons hidden = no payment dialog.
- AWAITING_PAYMENT after Escrow/Listing-Fee selection: `llSetPayPrice(PAY_DEFAULT, [PAY_HIDE, PAY_HIDE, PAY_HIDE, PAY_HIDE]);` — first arg PAY_DEFAULT = empty text field shows, no quick buttons.
- AWAITING_PAYMENT after Penalty selection: `llSetPayPrice(owed, [owed, owed/2, owed/4, PAY_HIDE]);` — first arg = default text-field value, three quick buttons sized to balance.

The full script will be ~400 LSL lines. Match spec §6.3's pseudocode exactly; differences must be justified by LSL syntax requirements only.

- [ ] **Step 3: Self-review the script vs spec**

The implementer must trace the following paths end-to-end and verify correctness:
1. Cold-start: state_entry → notecard → permissions → URL request → register → IDLE.
2. Region restart: changed(CHANGED_REGION_START) → URL re-request → re-register → state remains usable mid-flight.
3. Touch → menu → Escrow → auction id → pay → success → release lock.
4. Touch → menu → Listing Fee → auction id → pay → 5xx → bounded retry → eventual success.
5. Touch → menu → Pay Penalty → lookup returns owed=0 → speak "no penalty" → release lock.
6. Touch → menu → Pay Penalty → lookup returns owed=N → pay full → speak "cleared" → release lock.
7. Touch → menu → Get Parcel Verifier → give inventory → release lock immediately.
8. HTTP-in PAYOUT command from backend → debit → transaction_result → POST /payout-result.
9. HTTP-in command with wrong shared secret → 403.
10. Touch by user A → lock held → touch by user B → "busy" message → user A walks away → 60s timer → lock auto-clears → user B can touch.

Verify listen-handle hygiene: every `llListen` exit path calls `llListenRemove`.

- [ ] **Step 4: Stage file for commit**

```bash
git add lsl-scripts/slpa-terminal/slpa-terminal.lsl
```

(Use `-f` if gitignore matches.)

---

## Task 4: SLPA Terminal — notecard + README (with two-place update rule)

**Files:**
- Create: `lsl-scripts/slpa-terminal/config.notecard.example`
- Create: `lsl-scripts/slpa-terminal/README.md`

**Spec reference:** §6.7 (notecard), §6.8 (deployment), §6.9 (Updating two-place rule), §7.1 (rotation).

- [ ] **Step 1: Write `config.notecard.example`**

Path: `lsl-scripts/slpa-terminal/config.notecard.example`

```
# config notecard for SLPA Terminal (unified payments)
# Place this file (renamed to "config", no extension) in the prim's contents.
# After editing, the script auto-resets via CHANGED_INVENTORY and re-registers.

# Backend endpoint URLs — all six required.
REGISTER_URL=https://api.slparcelauctions.com/api/v1/sl/terminal/register
ESCROW_PAYMENT_URL=https://api.slparcelauctions.com/api/v1/sl/escrow/payment
LISTING_FEE_URL=https://api.slparcelauctions.com/api/v1/sl/listing-fee/payment
PENALTY_LOOKUP_URL=https://api.slparcelauctions.com/api/v1/sl/penalty-lookup
PENALTY_PAYMENT_URL=https://api.slparcelauctions.com/api/v1/sl/penalty-payment
PAYOUT_RESULT_URL=https://api.slparcelauctions.com/api/v1/sl/escrow/payout-result

# Shared secret for terminal authentication. REQUIRED.
# Obtain from the backend's slpa.escrow.terminal-shared-secret config.
SHARED_SECRET=replace-with-real-secret-from-deployment-secrets-store

# Optional: defaults to (string)llGetKey() if empty.
TERMINAL_ID=

# Optional: defaults to llGetRegionName() if empty.
REGION_NAME=

# Optional: false to silence per-event chat. Recommended true in prod.
DEBUG_OWNER_SAY=true
```

- [ ] **Step 2: Write `README.md`**

Path: `lsl-scripts/slpa-terminal/README.md`

```markdown
# SLPA Terminal (unified payments)

In-world unified payment kiosk for SLPA. Handles three payment types via a
4-option touch menu and accepts backend-initiated payouts/refunds/withdrawals
via HTTP-in.

## Architecture summary

- **Trust:** SL-injected `X-SecondLife-Shard` + `X-SecondLife-Owner-Key` headers
  on outbound, **plus** a `sharedSecret` field in body for register / payment /
  payout-result requests. Inbound HTTP-in: shared-secret check on every
  command.
- **Touch flow:** IDLE → (touch) lock + menu → (selection) AUCTION_ID prompt
  (Escrow/Listing-Fee) or LOOKUP (Penalty) or GIVE (Verifier) → (after
  selection) AWAITING_PAYMENT with appropriate `llSetPayPrice` → (money())
  POST to selected endpoint → release lock; retries run in background.
- **HTTP-in flow:** parallel to touch; backend POSTs `TerminalCommandBody`
  with action PAYOUT/REFUND/WITHDRAW; script validates shared secret, fires
  `llTransferLindenDollars`, reports `transaction_result` to `/payout-result`.
- **Lock:** single-user, 60s TTL. Released eagerly: as soon as the first
  payment POST fires, the lock releases so a second user can touch while
  retries run. "Get Parcel Verifier" releases immediately after `llGiveInventory`.
- **Region restart:** `changed(CHANGED_REGION_START)` triggers `llRequestURL()`
  + re-register. Backend's `terminals.http_in_url` is updated.

## Deployment

**SLPA-team-deployed only.** This script holds a shared secret and PERMISSION_DEBIT.
Never publish on Marketplace.

1. Rez a generic prim at SLPA HQ or an auction venue. Land must permit
   outbound HTTP and `llRequestURL`.
2. Drop `slpa-terminal.lsl` into the prim.
3. Drop a `SLPA Parcel Verifier` object copy into the prim's contents (so
   the "Get Parcel Verifier" menu option works).
4. Drop a copy of `config.notecard.example` renamed to **`config`** (no
   extension). Edit all six URLs and `SHARED_SECRET`.
5. Set the prim's owner to the SLPA service avatar (so
   `X-SecondLife-Owner-Key` matches `slpa.sl.trusted-owner-keys`).
6. Reset the script. The script will request `PERMISSION_DEBIT` from the
   owner — accept the dialog. Confirm:
   - `SLPA Terminal: registered (terminal_id=..., url=...)` startup ping.
   - Floating text "SLPA Terminal\nTouch for options" appears.
7. Smoke-test each menu option once with small amounts.

≥1 active SLPA Terminal must be live for the auction-completion path
(PAYOUT). Multi-instance is fine; backend dispatcher picks any active
terminal for any command.

## Configuration

| Key | Description |
| --- | --- |
| `REGISTER_URL` | Full URL of `/api/v1/sl/terminal/register`. Required. |
| `ESCROW_PAYMENT_URL` | Full URL of `/api/v1/sl/escrow/payment`. Required. |
| `LISTING_FEE_URL` | Full URL of `/api/v1/sl/listing-fee/payment`. Required. |
| `PENALTY_LOOKUP_URL` | Full URL of `/api/v1/sl/penalty-lookup`. Required. |
| `PENALTY_PAYMENT_URL` | Full URL of `/api/v1/sl/penalty-payment`. Required. |
| `PAYOUT_RESULT_URL` | Full URL of `/api/v1/sl/escrow/payout-result`. Required. |
| `SHARED_SECRET` | The shared secret. **Required.** Obtain from `slpa.escrow.terminal-shared-secret`. |
| `TERMINAL_ID` | Optional. Defaults to `(string)llGetKey()`. Use a stable name if you want admin tooling to identify this terminal across restarts. |
| `REGION_NAME` | Optional. Defaults to `llGetRegionName()`. |
| `DEBUG_OWNER_SAY` | Optional. `true`/`false`, default `true`. |

### Rotating the shared secret

1. Update `slpa.escrow.terminal-shared-secret` in the deployment's secret store.
2. Restart the SLPA backend so it picks up the new secret.
3. On every deployed SLPA Terminal: edit the `config` notecard with the new
   `SHARED_SECRET` value. `CHANGED_INVENTORY` auto-resets the script and
   re-registers with the new secret.
4. In-flight `terminal_commands` rows dispatched on the old secret will be
   rejected (terminal returns 403); the dispatcher's existing retry budget
   (4 attempts with 1m/5m/15m backoff) covers the brief rotation window.

## Updating

**Two-place rule for the parcel verifier.** The "Get Parcel Verifier" menu
option `llGiveInventory`s a copy of the parcel verifier from the prim's
contents. When you update `parcel-verifier.lsl`:

1. **Marketplace listing**: republish a new revision with the updated `.lsl`.
2. **Every deployed SLPA Terminal's inventory**: drag-drop the new
   `SLPA Parcel Verifier` object into the prim's contents, replacing the old
   copy. `CHANGED_INVENTORY` auto-resets the SLPA Terminal script
   (which re-registers — the inventory swap doesn't break anything else).

Forgetting place 2 leaves users with a stale verifier from the give-on-touch
menu while Marketplace customers get the new version. Track this in the ops
runbook.

Updating the SLPA Terminal script itself: drag-drop the new `slpa-terminal.lsl`
into the prim's contents → `CHANGED_INVENTORY` auto-resets → re-register.
Updating just the notecard: edit values → `CHANGED_INVENTORY` auto-resets →
re-register.

## Operations

In steady state, with `DEBUG_OWNER_SAY=true`:

- `SLPA Terminal: registered (terminal_id=..., url=...)` — startup confirmation.
- `SLPA Terminal: touch from <name>` — user touched the terminal.
- `SLPA Terminal: menu choice <option> by <name>` — menu selection.
- `SLPA Terminal: payment ok <kind> L$<amount> from <payer>` — successful payment POST.
- `SLPA Terminal: payment retry N/5: <status>` — transient failure, retrying.
- `SLPA Terminal: PAYOUT to <recipient> L$<amount> ok` — successful debit.
- `CRITICAL: payment from <payer> L$<amount> key <tx> not acknowledged after 5 retries` — payment recovery failed; manual reconciliation required.
- `CRITICAL: PERMISSION_DEBIT denied — script halted. Owner must re-grant.` — permissions issue.
- `CRITICAL: registration failed after 5 attempts.` — backend unreachable; investigate.

## Troubleshooting

| Symptom | Likely cause |
| --- | --- |
| Startup says `incomplete config` | One of the required notecard keys is empty. |
| Startup says `notecard 'config' missing or unreadable` | Notecard not in inventory or named something other than exactly `config`. |
| `PERMISSION_DEBIT denied` | Owner declined the permission dialog. Reset the script and accept. |
| `URL_REQUEST_DENIED` | Land doesn't allow scripts to request URLs. Move the prim to a region with permissive land settings. |
| Periodic `register retry N/5` | Backend unreachable or rejecting registration. Check `slpa.sl.trusted-owner-keys` includes this terminal's owner. |
| `payment retry N/5` repeatedly | Backend transient or network issue. Self-recovers in most cases. |
| `CRITICAL: payment from ... not acknowledged` | Backend POST never succeeded after 5 retries. Manual reconciliation required — operator must check `escrow_transactions` ledger and refund or recognize manually. |
| `Get Parcel Verifier` does nothing | The SLPA Parcel Verifier object is missing from the terminal's inventory. Drag-drop it back in. |
| Backend command dispatcher logs 403 | Shared secret mismatch. Update notecard, reset. |
| Terminal stuck `<In Use>` | Lock TTL didn't fire. Reset the script. |

## Limits

- LSL listen cap is 65; the script opens at most 2 listens per touch session
  (menu + auction-id-input or code-entry) and removes them on every exit path.
- HTTP-in URLs change on region restart; re-registration is automatic via
  `changed(CHANGED_REGION_START)`.
- `llTransferLindenDollars` rate limit: 30 payments per 30 seconds per owner per
  region. Phase 1 traffic is well under this; alert if approached.
- 60s touch lock means low-volume kiosks rarely hit contention. For high-volume
  venues, deploy multiple SLPA Terminals — backend's dispatcher picks any
  active one for commands.
- Inflight HTTP-in commands cap at 16 concurrent. New commands beyond that
  return 503; backend retry budget handles.
- Bounded payment retry: 10s / 30s / 90s / 5m / 15m, total ~22 minutes of
  trying. After exhaustion the script logs CRITICAL and stops; daily
  reconciliation job (deferred — see `DEFERRED_WORK.md`) catches missed POSTs.

## Security

- The terminal must be owned by an SLPA service avatar listed in
  `slpa.sl.trusted-owner-keys`. Backend rejects header mismatch.
- The shared secret in the notecard is visible to anyone with edit-rights on
  the prim — keep ownership and modify permissions SLPA-team-only.
- A leaked shared secret means an attacker can call `/payout-result` with
  forged "success" outcomes (debiting the escrow ledger without actually
  paying anyone) — rotate immediately if compromise is suspected.
- HMAC-SHA256 per-request auth is on the deferred list (`DEFERRED_WORK.md`)
  for Phase 2 hardening once the LSL terminal is dogfooded.
- Penalty endpoints (`/penalty-lookup`, `/penalty-payment`) are
  header-trust-only on the backend — no shared secret in body. The script
  still has its shared secret loaded for the other endpoints; it just
  doesn't include it in penalty bodies.
```

- [ ] **Step 3: Stage files for commit**

```bash
git add lsl-scripts/slpa-terminal/config.notecard.example lsl-scripts/slpa-terminal/README.md
```

---

## Task 5: Top-level `lsl-scripts/README.md` index update

**Files:**
- Modify: `lsl-scripts/README.md`

**Spec reference:** §3.2 (directory layout — top-level index updates only on add/remove/rename).

- [ ] **Step 1: Read existing index**

Confirm the current state of `lsl-scripts/README.md` — it lists only `sl-im-dispatcher`. We're adding 3 more entries.

- [ ] **Step 2: Update the `## Scripts` section**

Replace the existing `## Scripts` section content with:

```markdown
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
```

- [ ] **Step 3: Stage file for commit**

```bash
git add lsl-scripts/README.md
```

---

## Task 6: Final commit + push + open PR

**Files:** none new.

- [ ] **Step 1: Verify all files staged**

Run `git status`. Expected: all of the following modified or new:
- `lsl-scripts/README.md` (modified)
- `lsl-scripts/verification-terminal/{config.notecard.example,README.md,verification-terminal.lsl}` (new)
- `lsl-scripts/parcel-verifier/{config.notecard.example,README.md,parcel-verifier.lsl}` (new)
- `lsl-scripts/slpa-terminal/{config.notecard.example,README.md,slpa-terminal.lsl}` (new)

If any file is missing, `git add` it (use `-f` if the gitignore matches).

- [ ] **Step 2: Commit (parent commits — subagent stages only)**

The implementer subagent only stages files. The parent (controller) commits using:

```bash
git commit -m "$(cat <<'EOF'
feat(epic-11): LSL scripts — verification, parcel verifier, unified SLPA Terminal

Closes Epic 11. Three new in-world LSL deliverables on top of the
already-shipped sl-im-dispatcher:

- Verification Terminal: touch + 6-digit code → POST /sl/verify; widely
  distributed (Marketplace + allied venues); header trust only.
- Parcel Verifier: rezzable single-use object; on_rez reads parcel data,
  auto-prompts for 6-digit PARCEL code, POSTs, llDies in all paths.
- SLPA Terminal: unified payment surface. 4-option touch menu (Escrow Payment
  / Listing Fee / Pay Penalty / Get Parcel Verifier) plus HTTP-in command
  handler for backend-initiated PAYOUT/REFUND/WITHDRAW. SLPA-team-deployed
  only — holds shared secret + PERMISSION_DEBIT. Pay-price matrix is
  state-dependent: IDLE refuses payment; ESCROW/LISTING_FEE shows custom
  amount field; PENALTY shows balance + half/quarter quick buttons.

All three scripts share the notecard + sim_channel guard + CHANGED_INVENTORY
auto-reset + listen-handle hygiene pattern established by sl-im-dispatcher.
Bounded HTTP retry (10s/30s/90s/5m/15m) on payment POSTs only. Top-level
lsl-scripts/README.md index updated.

Spec: docs/superpowers/specs/2026-04-28-epic-11-lsl-scripts.md
Plan: docs/superpowers/plans/2026-04-28-epic-11-lsl-scripts.md

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

- [ ] **Step 3: Push branch**

```bash
git push -u origin task/11-lsl-scripts
```

- [ ] **Step 4: Open PR against `dev`**

```bash
gh pr create --base dev --title "feat(epic-11): LSL scripts — verification, parcel verifier, unified SLPA Terminal" --body "$(cat <<'EOF'
## Summary

Closes Epic 11. Three new in-world LSL deliverables on top of the already-shipped `sl-im-dispatcher`:

- **Verification Terminal** (`lsl-scripts/verification-terminal/`) — touch + 6-digit code → POST `/sl/verify`; widely distributed; header trust only.
- **Parcel Verifier** (`lsl-scripts/parcel-verifier/`) — rezzable single-use; on_rez reads parcel data, auto-prompts for code, POSTs, `llDie`s.
- **SLPA Terminal** (`lsl-scripts/slpa-terminal/`) — unified payment surface with 4-option menu + HTTP-in command handler. SLPA-team-deployed only.

The unified SLPA Terminal subsumes the originally-planned escrow / listing-fee / penalty terminals into one object — Q&A during brainstorm landed on this architecture for operational simplicity.

After this merges, every backend SL endpoint that was waiting for an in-world client has one. End-to-end loops (verify → list → bid → pay → confirm → review → admin) all run against real SL traffic.

## Test plan

- [ ] Paste each `.lsl` into the SL viewer's script editor — zero compile errors.
- [ ] On Beta Grid (Aditi) with a backend pointed at a Beta deployment:
  - [ ] Rez Verification Terminal, touch, enter code → success message.
  - [ ] Rez Parcel Verifier on a parcel I own, type code → "Parcel verified" + llDie.
  - [ ] Rez SLPA Terminal, accept DEBIT permission, confirm "registered" startup ping.
  - [ ] Touch SLPA Terminal → menu → Escrow Payment → enter Auction ID → pay L$ → success.
  - [ ] Touch → Listing Fee → similar path.
  - [ ] Touch → Pay Penalty (with seeded penalty) → lookup → pay → "cleared".
  - [ ] Touch → Get Parcel Verifier → object delivered to inventory.
  - [ ] Backend-initiated PAYOUT command via dispatcher → terminal debits, posts /payout-result.
  - [ ] Region restart: terminal re-registers; new HTTP-in URL appears in `terminals` table.
- [ ] Two avatars touching the Verification Terminal: second sees "busy" message; lock auto-clears after 60s.
- [ ] Edit notecard mid-deploy → `CHANGED_INVENTORY` auto-resets, terminal re-registers.

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

- [ ] **Step 5: Confirm PR is open + paste URL**

`gh pr view --json url,number -q '.url + " (#" + (.number|tostring) + ")"'` should print the PR URL. Done.

---

## Self-review

**1. Spec coverage**

| Spec section | Task |
|---|---|
| §1 Goal | Implicit — addressed by all tasks delivering the three scripts |
| §2 Scope (3 deliverables) | Tasks 1, 2, 3+4 |
| §3 Architecture (shared pattern, directory layout) | All scripts follow shared pattern; Task 5 updates index |
| §4 Verification Terminal | Task 1 |
| §5 Parcel Verifier | Task 2 |
| §6 SLPA Terminal full spec | Tasks 3 + 4 |
| §7 Trust model + secret rotation | Task 4 README "Rotating the shared secret" |
| §8 Failure-handling matrix | Embedded in each script's state-machine implementation |
| §9 Testing strategy | Task 6 PR test plan |
| §10 Rollout plan | Task 6 PR (operator-facing; not script changes) |
| §11 Acceptance criteria | All scripts must compile + smoke-test (Task 6 PR test plan checklist) |

**No gaps.**

**2. Placeholder scan**

The plan contains no "TBD", "TODO", "implement later", or "fill in details". Every script section enumerates the exact globals, helpers, events, and edge cases. Code snippets are concrete LSL or `name=value` examples. The implementer's discretion is bounded to LSL syntax translation.

**3. Type consistency**

- `selectedKind` is `integer` throughout (SELECTED_NONE / SELECTED_ESCROW / SELECTED_LISTING_FEE / SELECTED_PENALTY) — used in §6.3 of the script same way across `firePayment` / `money()` / `listen` / `releaseLock`.
- `lockHolder` is `key`, never confused with `lockHolderName: string`.
- `paymentTxKey` is `string` (synthesized once via `(string)llGenerateKey()` in `money()` event), reused across retries — consistent.
- `httpReqId` / `paymentReqId` / `lookupReqId` / `registerReqId` / `payoutResultReqId` / `urlRequestId` all `key`. Each event handler dispatches on the matching id.
- Channels (`menuChan`, `auctionIdChan`, `codeChan`) all `integer`, set once in `state_entry`.
- `IS_SIX_DIGIT_CODE` (Verification Terminal, Parcel Verifier) and `IS_POSITIVE_INTEGER` (SLPA Terminal Auction-ID) are different functions for different validations — naming is distinct on purpose.

No type drift detected.

---

## Execution handoff

Plan complete and saved to `docs/superpowers/plans/2026-04-28-epic-11-lsl-scripts.md`. The user has pre-selected **Subagent-Driven autonomous execution** (lean — sonnet implementers, skip formal reviewers, subagent stages files + parent commits). Proceeding to invoke `superpowers:subagent-driven-development` next.
