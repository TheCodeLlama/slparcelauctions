// SLParcels Terminal (wallet model)
//
// Single-object payment kiosk for SLParcels. Two touch-menu options:
//   1. Deposit  — instructs user to right-click and pay any amount
//   2. Withdraw — touch-confirmed withdrawal from the user's SLParcels wallet
//
// SL Group Verify (the founder-of-an-SL-group verification flow for realty
// groups, sub-project E spec section 7.3) lives on the SLParcels Verification
// Terminal — the kiosk whose name actually advertises "verification". This
// terminal handles L$ flows only.
//
// The terminal accepts deposits via the natural SL pay flow:
//   * Right-click → Pay → enter amount → money() event fires
//   * Lockless, fully concurrent (multiple users can deposit simultaneously)
//   * Backend POST to /sl/wallet/deposit credits the user's wallet
//
// Touch-initiated withdrawals use per-flow slots dispatched by avatar key
// on a single shared listen — no terminal-wide lock, no per-flow listen
// handles to leak. Up to 4 concurrent withdraw sessions; per-avatar dedup
// (a second touch from the same avatar resets their existing slot).
//
// Also accepts backend-initiated PAYOUT/WITHDRAW commands via HTTP-in
// (REFUND defensive — should never arrive in the wallet model since refunds
// are wallet credits, but logged + failed loudly if it does).
//
// Configuration is loaded from a notecard named "config" in the same prim.
// See README.md for deployment and operations details.
//
// Grid guard note:
//   llGetEnv("sim_channel") == "Second Life Server"   (in-world env value)
//   X-SecondLife-Shard: "Production"                  (HTTP header value, checked backend-side)

// === Configuration loaded from notecard ===
string  REGISTER_URL          = "";
string  DEPOSIT_URL           = "";
string  WITHDRAW_REQUEST_URL  = "";
string  PAYOUT_RESULT_URL     = "";
string  HEARTBEAT_URL         = "";
// Optional URLs for the "Pay to group" flow (sub-project H). Both must be
// non-empty for the menu button to appear; absence keeps the terminal on
// the legacy Deposit/Withdraw-only behaviour (backwards compatibility for
// not-yet-updated terminals).
string  AVATAR_GROUPS_URL     = "";
string  GROUP_DEPOSIT_URL     = "";
string  SHARED_SECRET         = "";
string  TERMINAL_ID           = "";
string  REGION_NAME           = "";
integer DEBUG_MODE            = TRUE;

// === Notecard reading state ===
key     notecardLineRequest = NULL_KEY;
integer notecardLineNum     = 0;

// === HTTP-in URL ===
string  httpInUrl    = "";
key     urlRequestId = NULL_KEY;

// === Permissions / startup ===
integer debitGranted      = FALSE;
integer registered        = FALSE;
key     registerReqId     = NULL_KEY;
integer registerAttempt   = 0;
integer registerNextRetryAt = 0;

// === Single shared listen (opened at startup, never closed) ===
integer mainChan       = 0;
integer mainListenHandle = -1;

// === Per-flow withdraw slots ===
// Strided 3-wide list: [avatarKey, amountOrMinusOne, expiresAt, ...]
//   amountOrMinusOne: -1 = awaiting amount; >0 = awaiting confirm
list    withdrawSessions   = [];
integer MAX_WITHDRAW_SESSIONS = 4;
integer SESSION_TTL_SECONDS = 60;
integer SLOT_STRIDE = 3;

// === "Pay to group" pending-deposit slots ===
// Strided 4-wide list: [avatarKey, groupPublicIdStr, groupNameStr, expiresAt, ...]
//   Set when an avatar picks a group on the "Pay to group" dialog.
//   Consumed in money() if not expired -> POST /sl/wallet/group-deposit.
//   Falls through to the personal-wallet deposit flow when missing or
//   expired (preserves the existing right-click -> Pay semantics).
//
//   groupNameStr is retained for owner-say log messages only; not sent on
//   the wire to the backend.
list    pendingGroupDeposits   = [];
integer MAX_GROUP_DEPOSIT_SLOTS = 4;
integer GROUP_DEPOSIT_SLOT_TTL  = 60;
integer GROUP_DEPOSIT_SLOT_STRIDE = 4;

// === "Pay to group" pending dialog (between /avatar-groups POST and the
// avatar clicking a group name in llDialog) ===
// Single-slot scalar state. Only one avatar can be in the "Pay to group"
// picker at a time -- a second avatar's request clobbers the first
// silently (the first's dialog falls through to a no-op when they tap).
// We previously stored a strided list of concurrent pickers, but the
// runtime memory pressure tripped Stack-Heap Collision on real terminals.
//   pendingDialogLabels    -- JSON array of truncated group display names
//   pendingDialogPublicIds -- JSON array of group publicIds (same order)
//   pendingDialogNextAfter -- "" when the backend reported hasMore=false,
//                             else the cursor to re-POST on "More..."
key     pendingDialogAvatar    = NULL_KEY;
string  pendingDialogLabels    = "";
string  pendingDialogPublicIds = "";
string  pendingDialogNextAfter = "";
integer pendingDialogExpiresAt = 0;
integer GROUP_DIALOG_TTL       = 60;
// Max chars for an LSL dialog button label. SL caps at 24; we leave one
// char of headroom for the truncation ellipsis.
integer GROUP_LABEL_MAX_CHARS  = 24;

// === In-flight /avatar-groups + /group-deposit request tracking ===
// /avatar-groups request -> remembers which avatar to dialog when the
// response arrives. One slot at a time (per-avatar overwrite).
key     avatarGroupsReqId      = NULL_KEY;
key     avatarGroupsReqAvatar  = NULL_KEY;

// /group-deposit request -> mirrors paymentReqId/paymentPayer/... for the
// existing personal deposit. Reuses the same retry schedule. Only one
// concurrent group-deposit per terminal (mirrors the existing single-
// concurrent personal-deposit retry slot — the rest are awaiting on the
// 10s/30s/90s/5m/15m chain in the timer).
key     groupDepositReqId      = NULL_KEY;
key     groupDepositPayer      = NULL_KEY;
integer groupDepositAmount     = 0;
string  groupDepositTxKey      = "";
string  groupDepositGroupId    = "";
string  groupDepositGroupName  = "";
integer groupDepositRetryCount = 0;
integer groupDepositNextRetryAt = 0;

// === Background deposit retry (per money() event) ===
key     paymentReqId       = NULL_KEY;
key     paymentPayer       = NULL_KEY;
integer paymentAmount      = 0;
string  paymentTxKey       = "";
integer paymentRetryCount  = 0;
integer paymentNextRetryAt = 0;

// === Background withdraw-request retry (per touch-confirm) ===
key     withdrawReqId       = NULL_KEY;
key     withdrawPayer       = NULL_KEY;
integer withdrawAmount      = 0;
string  withdrawTxKey       = "";
integer withdrawRetryCount  = 0;
integer withdrawNextRetryAt = 0;

// === HTTP-in inflight command tracking ===
list    inflightCmdTxKeys         = [];
list    inflightCmdIdempotencyKeys = [];
list    inflightCmdRecipients     = [];
list    inflightCmdAmounts         = [];
integer MAX_INFLIGHT_CMDS         = 16;

// === Payout-result tracking ===
key     payoutResultReqId = NULL_KEY;

// === Heartbeat ===
// The terminal periodically POSTs to /sl/terminal/heartbeat so the backend
// dispatcher's `lastSeenAt` window stays fresh. Without this, an idle but
// healthy terminal eventually drops out of dispatch rotation between
// rezzes / inventory changes (the only other paths that re-register).
//
// HEARTBEAT_URL is optional in the notecard for backward compatibility:
// pre-heartbeat deployments will simply skip heartbeats and rely on the
// authenticated-call refresh of lastSeenAt added in the backend at the
// same time as this LSL change. New deployments should set it.
key     heartbeatReqId             = NULL_KEY;
integer nextHeartbeatAt            = 0;
integer HEARTBEAT_INTERVAL_SECONDS = 300;

// === Timer phase ===
integer TIMER_NONE              = 0;
integer TIMER_SESSION_SWEEP     = 1;
integer TIMER_PAYMENT_RETRY     = 2;
integer TIMER_REGISTER_RETRY    = 3;
integer TIMER_WITHDRAW_RETRY    = 4;
integer TIMER_GROUP_DEPOSIT_RETRY = 5;
integer timerPhase              = 0;

// ---------------------------------------------------------------------------
// Helper functions
// ---------------------------------------------------------------------------

readNotecardLine(integer n) {
    notecardLineNum     = n;
    notecardLineRequest = llGetNotecardLine("config", n);
}

parseConfigLine(string line) {
    if (llStringLength(line) == 0) return;
    if (llSubStringIndex(line, "#") == 0) return;

    integer eq = llSubStringIndex(line, "=");
    if (eq < 1) return;

    string k = llStringTrim(llGetSubString(line, 0, eq - 1), STRING_TRIM);
    string v = llStringTrim(llGetSubString(line, eq + 1, -1), STRING_TRIM);

    if      (k == "REGISTER_URL")          REGISTER_URL          = v;
    else if (k == "DEPOSIT_URL")           DEPOSIT_URL           = v;
    else if (k == "WITHDRAW_REQUEST_URL")  WITHDRAW_REQUEST_URL  = v;
    else if (k == "PAYOUT_RESULT_URL")     PAYOUT_RESULT_URL     = v;
    else if (k == "HEARTBEAT_URL")         HEARTBEAT_URL         = v;
    else if (k == "AVATAR_GROUPS_URL")     AVATAR_GROUPS_URL     = v;
    else if (k == "GROUP_DEPOSIT_URL")     GROUP_DEPOSIT_URL     = v;
    else if (k == "SHARED_SECRET")         SHARED_SECRET         = v;
    else if (k == "TERMINAL_ID")           TERMINAL_ID           = v;
    else if (k == "REGION_NAME")           REGION_NAME           = v;
    else if (k == "DEBUG_MODE")
        DEBUG_MODE = (v == "true" || v == "TRUE" || v == "1");
}

string escapeJson(string s) {
    s = llReplaceSubString(s, "\\", "\\\\", 0);
    s = llReplaceSubString(s, "\"", "\\\"", 0);
    return s;
}

debugSayUser(key who, string label, integer status, string body) {
    if (!DEBUG_MODE) return;
    string title  = llJsonGetValue(body, ["title"]);
    string detail = llJsonGetValue(body, ["detail"]);
    string code   = llJsonGetValue(body, ["code"]);
    string msg = "DEBUG " + label + ": status=" + (string)status;
    integer haveAny = FALSE;
    if (title  != JSON_INVALID && title  != "") { msg += " title=" + title;   haveAny = TRUE; }
    if (detail != JSON_INVALID && detail != "") { msg += " detail=" + detail; haveAny = TRUE; }
    if (code   != JSON_INVALID && code   != "") { msg += " code=" + code;     haveAny = TRUE; }
    if (!haveAny) {
        integer blen = llStringLength(body);
        if (blen == 0) msg += " body=<empty>";
        else {
            integer cap = blen;
            if (cap > 200) cap = 200;
            msg += " body[" + (string)blen + "]=" + llGetSubString(body, 0, cap - 1);
        }
    }
    llOwnerSay("SLParcels Terminal: " + msg);
}

setIdleChrome() {
    llSetText("SLParcels Terminal\nRight-click → Pay to deposit\nTouch for menu",
        <1.0, 1.0, 1.0>, 1.0);
    llSetObjectName("SLParcels Terminal");
    llSetPayPrice(PAY_DEFAULT, [100, 500, 1000, 5000]);
}

// retrySchedule: 1-indexed
integer retryDelay(integer attempt) {
    list schedule = [10, 30, 90, 300, 900];
    integer idx = attempt - 1;
    if (idx < 0)  idx = 0;
    if (idx > 4)  idx = 4;
    return llList2Integer(schedule, idx);
}

postRegister() {
    string body = "{"
        + "\"terminalId\":\"" + escapeJson(TERMINAL_ID) + "\","
        + "\"httpInUrl\":\"" + escapeJson(httpInUrl) + "\","
        + "\"regionName\":\"" + escapeJson(REGION_NAME) + "\","
        + "\"sharedSecret\":\"" + escapeJson(SHARED_SECRET) + "\""
        + "}";
    registerReqId = llHTTPRequest(REGISTER_URL,
        [HTTP_METHOD, "POST",
         HTTP_MIMETYPE, "application/json",
         HTTP_BODY_MAXLENGTH, 16384],
        body);
}

scheduleRegisterRetry() {
    integer delay = retryDelay(registerAttempt);
    registerNextRetryAt = llGetUnixTime() + delay;
    timerPhase = TIMER_REGISTER_RETRY;
    llSetTimerEvent((float)delay);
}

postHeartbeat() {
    // Reconciliation isn't wired yet — `accountBalance` is sent as 0
    // until the backend reconciliation job lands and we add proper
    // tracking via money() / transaction_result. The dispatcher only
    // uses the heartbeat for `lastSeenAt`, so 0 is harmless today.
    string body = "{"
        + "\"terminalKey\":\"" + escapeJson(TERMINAL_ID) + "\","
        + "\"accountBalance\":0"
        + "}";
    heartbeatReqId = llHTTPRequest(HEARTBEAT_URL,
        [HTTP_METHOD, "POST",
         HTTP_MIMETYPE, "application/json",
         HTTP_BODY_MAXLENGTH, 16384],
        body);
}

// ---------------- Deposit (money() handler) ----------------

firePayment() {
    string body = "{"
        + "\"payerUuid\":\"" + (string)paymentPayer + "\","
        + "\"amount\":" + (string)paymentAmount + ","
        + "\"slTransactionKey\":\"" + paymentTxKey + "\","
        + "\"terminalId\":\"" + escapeJson(TERMINAL_ID) + "\","
        + "\"sharedSecret\":\"" + escapeJson(SHARED_SECRET) + "\""
        + "}";
    paymentReqId = llHTTPRequest(DEPOSIT_URL,
        [HTTP_METHOD, "POST",
         HTTP_MIMETYPE, "application/json",
         HTTP_BODY_MAXLENGTH, 16384],
        body);
}

schedulePaymentRetry() {
    if (paymentRetryCount >= 5) {
        // Final-failure refund discipline (CLAUDE.md "always refund on
        // deposit error"): L$ is still in the script's hands and the
        // backend hasn't acked after ~22 minutes of retries. Bounce to
        // the payer rather than stranding the funds, then log CRITICAL
        // for ops reconciliation. Mirrors scheduleGroupDepositRetry.
        llTransferLindenDollars(paymentPayer, paymentAmount);
        llOwnerSay("CRITICAL: SLParcels Terminal: deposit from "
            + (string)paymentPayer
            + " L$" + (string)paymentAmount
            + " key " + paymentTxKey
            + " not acknowledged after 5 retries; refunded payer");
        paymentReqId      = NULL_KEY;
        paymentPayer      = NULL_KEY;
        paymentAmount     = 0;
        paymentTxKey      = "";
        paymentRetryCount = 0;
        if (timerPhase == TIMER_PAYMENT_RETRY) {
            timerPhase = TIMER_NONE;
            // Don't blindly cancel — session sweep also uses the timer.
            llSetTimerEvent(10.0);
            timerPhase = TIMER_SESSION_SWEEP;
        }
        return;
    }
    integer delay = retryDelay(paymentRetryCount);
    paymentNextRetryAt = llGetUnixTime() + delay;
    timerPhase = TIMER_PAYMENT_RETRY;
    llSetTimerEvent((float)delay);
}

// ---------------- Withdraw-request retry ----------------

fireWithdrawRequest() {
    string body = "{"
        + "\"payerUuid\":\"" + (string)withdrawPayer + "\","
        + "\"amount\":" + (string)withdrawAmount + ","
        + "\"slTransactionKey\":\"" + withdrawTxKey + "\","
        + "\"terminalId\":\"" + escapeJson(TERMINAL_ID) + "\","
        + "\"sharedSecret\":\"" + escapeJson(SHARED_SECRET) + "\""
        + "}";
    withdrawReqId = llHTTPRequest(WITHDRAW_REQUEST_URL,
        [HTTP_METHOD, "POST",
         HTTP_MIMETYPE, "application/json",
         HTTP_BODY_MAXLENGTH, 16384],
        body);
}

scheduleWithdrawRetry() {
    if (withdrawRetryCount >= 5) {
        llOwnerSay("CRITICAL: SLParcels Terminal: withdraw-request from "
            + (string)withdrawPayer
            + " L$" + (string)withdrawAmount
            + " key " + withdrawTxKey
            + " not acknowledged after 5 retries");
        withdrawReqId      = NULL_KEY;
        withdrawPayer      = NULL_KEY;
        withdrawAmount     = 0;
        withdrawTxKey      = "";
        withdrawRetryCount = 0;
        if (timerPhase == TIMER_WITHDRAW_RETRY) {
            timerPhase = TIMER_SESSION_SWEEP;
            llSetTimerEvent(10.0);
        }
        return;
    }
    integer delay = retryDelay(withdrawRetryCount);
    withdrawNextRetryAt = llGetUnixTime() + delay;
    timerPhase = TIMER_WITHDRAW_RETRY;
    llSetTimerEvent((float)delay);
}

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

// ---------------- Withdraw slot helpers ----------------

integer findSlot(key avatar) {
    integer i;
    integer count = llGetListLength(withdrawSessions) / SLOT_STRIDE;
    for (i = 0; i < count; ++i) {
        key k = llList2Key(withdrawSessions, i * SLOT_STRIDE);
        if (k == avatar) return i;
    }
    return -1;
}

integer slotAmount(integer slotIdx) {
    return llList2Integer(withdrawSessions, slotIdx * SLOT_STRIDE + 1);
}

setSlotAmount(integer slotIdx, integer amount) {
    integer offset = slotIdx * SLOT_STRIDE + 1;
    withdrawSessions = llListReplaceList(withdrawSessions, [amount], offset, offset);
}

extendSlot(integer slotIdx) {
    integer offset = slotIdx * SLOT_STRIDE + 2;
    withdrawSessions = llListReplaceList(withdrawSessions,
        [llGetUnixTime() + SESSION_TTL_SECONDS], offset, offset);
}

// Returns slot index of new/reset slot, or -1 if at capacity (and not own).
integer acquireOrResetSlot(key avatar) {
    integer existing = findSlot(avatar);
    if (existing >= 0) {
        // Reset existing slot
        integer offset = existing * SLOT_STRIDE + 1;
        withdrawSessions = llListReplaceList(withdrawSessions,
            [-1, llGetUnixTime() + SESSION_TTL_SECONDS], offset, offset + 1);
        return existing;
    }
    integer count = llGetListLength(withdrawSessions) / SLOT_STRIDE;
    if (count >= MAX_WITHDRAW_SESSIONS) return -1;
    withdrawSessions += [avatar, -1, llGetUnixTime() + SESSION_TTL_SECONDS];
    return count;
}

releaseSlot(key avatar) {
    integer slotIdx = findSlot(avatar);
    if (slotIdx < 0) return;
    integer start = slotIdx * SLOT_STRIDE;
    withdrawSessions = llDeleteSubList(withdrawSessions, start, start + SLOT_STRIDE - 1);
}

sweepExpiredSlots() {
    integer now = llGetUnixTime();
    integer i = llGetListLength(withdrawSessions) / SLOT_STRIDE - 1;
    while (i >= 0) {
        integer expiresAt = llList2Integer(withdrawSessions, i * SLOT_STRIDE + 2);
        if (expiresAt < now) {
            withdrawSessions = llDeleteSubList(withdrawSessions,
                i * SLOT_STRIDE, i * SLOT_STRIDE + SLOT_STRIDE - 1);
        }
        --i;
    }
}

// ---------------- Group-deposit slot helpers ----------------

integer findGroupDepositSlot(key avatar) {
    integer i;
    integer count = llGetListLength(pendingGroupDeposits) / GROUP_DEPOSIT_SLOT_STRIDE;
    for (i = 0; i < count; ++i) {
        key k = llList2Key(pendingGroupDeposits, i * GROUP_DEPOSIT_SLOT_STRIDE);
        if (k == avatar) return i;
    }
    return -1;
}

releaseGroupDepositSlot(key avatar) {
    integer slotIdx = findGroupDepositSlot(avatar);
    if (slotIdx < 0) return;
    integer start = slotIdx * GROUP_DEPOSIT_SLOT_STRIDE;
    pendingGroupDeposits = llDeleteSubList(pendingGroupDeposits,
        start, start + GROUP_DEPOSIT_SLOT_STRIDE - 1);
}

// Insert a new pending group-deposit slot. Per-avatar dedup (last-write-
// wins) and a global cap of MAX_GROUP_DEPOSIT_SLOTS — when the cap is hit
// without an existing avatar slot, evict the oldest (head of the strided
// list) to make room (spec §8.3).
setGroupDepositSlot(key avatar, string groupPublicId, string groupName) {
    releaseGroupDepositSlot(avatar);
    integer count = llGetListLength(pendingGroupDeposits) / GROUP_DEPOSIT_SLOT_STRIDE;
    if (count >= MAX_GROUP_DEPOSIT_SLOTS) {
        pendingGroupDeposits = llDeleteSubList(pendingGroupDeposits,
            0, GROUP_DEPOSIT_SLOT_STRIDE - 1);
    }
    pendingGroupDeposits += [
        avatar,
        groupPublicId,
        groupName,
        llGetUnixTime() + GROUP_DEPOSIT_SLOT_TTL
    ];
}

sweepExpiredGroupDepositSlots() {
    integer now = llGetUnixTime();
    integer i = llGetListLength(pendingGroupDeposits) / GROUP_DEPOSIT_SLOT_STRIDE - 1;
    while (i >= 0) {
        integer expiresAt = llList2Integer(pendingGroupDeposits,
            i * GROUP_DEPOSIT_SLOT_STRIDE + 3);
        if (expiresAt < now) {
            pendingGroupDeposits = llDeleteSubList(pendingGroupDeposits,
                i * GROUP_DEPOSIT_SLOT_STRIDE,
                i * GROUP_DEPOSIT_SLOT_STRIDE + GROUP_DEPOSIT_SLOT_STRIDE - 1);
        }
        --i;
    }
}

// ---------------- Group-dialog slot helpers ----------------

releaseGroupDialogSlot() {
    pendingDialogAvatar    = NULL_KEY;
    pendingDialogLabels    = "";
    pendingDialogPublicIds = "";
    pendingDialogNextAfter = "";
    pendingDialogExpiresAt = 0;
}

setGroupDialogSlot(key avatar, string labelsJson, string publicIdsJson, string nextAfter) {
    pendingDialogAvatar    = avatar;
    pendingDialogLabels    = labelsJson;
    pendingDialogPublicIds = publicIdsJson;
    pendingDialogNextAfter = nextAfter;
    pendingDialogExpiresAt = llGetUnixTime() + GROUP_DIALOG_TTL;
}

sweepExpiredGroupDialogs() {
    if (pendingDialogAvatar != NULL_KEY
            && pendingDialogExpiresAt < llGetUnixTime()) {
        releaseGroupDialogSlot();
    }
}

// Truncate a group name to fit within an LSL dialog button. SL caps
// button labels at 24 chars; long names are truncated with a trailing
// ellipsis-character to signal the truncation. We use a single "~" rather
// than "..." so we keep 23 chars of name visible at the 24-char cap.
string truncateGroupLabel(string name) {
    if (llStringLength(name) <= GROUP_LABEL_MAX_CHARS) return name;
    return llGetSubString(name, 0, GROUP_LABEL_MAX_CHARS - 2) + "~";
}

// ---------------- /avatar-groups POST ----------------

postAvatarGroups(key avatar, string after) {
    string body = "{"
        + "\"terminalId\":\"" + escapeJson(TERMINAL_ID) + "\","
        + "\"sharedSecret\":\"" + escapeJson(SHARED_SECRET) + "\","
        + "\"avatarUuid\":\"" + (string)avatar + "\"";
    if (after != "") {
        body += ",\"after\":\"" + escapeJson(after) + "\"";
    }
    body += "}";
    avatarGroupsReqAvatar = avatar;
    avatarGroupsReqId = llHTTPRequest(AVATAR_GROUPS_URL,
        [HTTP_METHOD, "POST",
         HTTP_MIMETYPE, "application/json",
         HTTP_BODY_MAXLENGTH, 16384],
        body);
}

// ---------------- /group-deposit POST + retry chain ----------------

fireGroupDeposit() {
    string body = "{"
        + "\"payerUuid\":\"" + (string)groupDepositPayer + "\","
        + "\"groupPublicId\":\"" + escapeJson(groupDepositGroupId) + "\","
        + "\"amount\":" + (string)groupDepositAmount + ","
        + "\"slTransactionKey\":\"" + groupDepositTxKey + "\","
        + "\"terminalId\":\"" + escapeJson(TERMINAL_ID) + "\","
        + "\"sharedSecret\":\"" + escapeJson(SHARED_SECRET) + "\""
        + "}";
    groupDepositReqId = llHTTPRequest(GROUP_DEPOSIT_URL,
        [HTTP_METHOD, "POST",
         HTTP_MIMETYPE, "application/json",
         HTTP_BODY_MAXLENGTH, 16384],
        body);
}

scheduleGroupDepositRetry() {
    if (groupDepositRetryCount >= 5) {
        // Final-failure refund discipline: by this point L$ is still in
        // the script's hands and the backend hasn't acked. Bounce to the
        // payer rather than stranding the funds, then log CRITICAL for
        // ops reconciliation.
        llTransferLindenDollars(groupDepositPayer, groupDepositAmount);
        llOwnerSay("CRITICAL: SLParcels Terminal: group deposit from "
            + (string)groupDepositPayer
            + " L$" + (string)groupDepositAmount
            + " to group " + groupDepositGroupId
            + " key " + groupDepositTxKey
            + " not acknowledged after 5 retries; refunded payer");
        groupDepositReqId      = NULL_KEY;
        groupDepositPayer      = NULL_KEY;
        groupDepositAmount     = 0;
        groupDepositTxKey      = "";
        groupDepositGroupId    = "";
        groupDepositGroupName  = "";
        groupDepositRetryCount = 0;
        if (timerPhase == TIMER_GROUP_DEPOSIT_RETRY) {
            timerPhase = TIMER_SESSION_SWEEP;
            llSetTimerEvent(10.0);
        }
        return;
    }
    integer delay = retryDelay(groupDepositRetryCount);
    groupDepositNextRetryAt = llGetUnixTime() + delay;
    timerPhase = TIMER_GROUP_DEPOSIT_RETRY;
    llSetTimerEvent((float)delay);
}

// ---------------- HTTP-in inflight ----------------

addInflightCommand(key txKey, string idempotencyKey, key recipient, integer amount) {
    if (llGetListLength(inflightCmdTxKeys) >= MAX_INFLIGHT_CMDS) {
        llOwnerSay("SLParcels Terminal: inflight command cap (" + (string)MAX_INFLIGHT_CMDS
            + ") hit; refusing command. Backend retry will cover.");
        return;
    }
    // Cast to string at insertion: llListFindList in removeInflightByTxKey
    // searches with a string cast, and LSL's list type-comparison is strict
    // — a key-typed element does NOT match a string-typed search element
    // even with identical UUIDs, so a missing cast here silently drops every
    // transaction_result lookup, never POSTs /payout-result, and the
    // dispatcher's IN_FLIGHT timeout requeues the command into a permanent
    // retry loop.
    inflightCmdTxKeys          = inflightCmdTxKeys          + [(string)txKey];
    inflightCmdIdempotencyKeys = inflightCmdIdempotencyKeys + [idempotencyKey];
    inflightCmdRecipients      = inflightCmdRecipients      + [(string)recipient];
    inflightCmdAmounts         = inflightCmdAmounts         + [amount];
}

list removeInflightByTxKey(key txKey) {
    integer idx = llListFindList(inflightCmdTxKeys, [(string)txKey]);
    if (idx < 0) return [];
    string ikey = llList2String(inflightCmdIdempotencyKeys, idx);
    string recip = llList2String(inflightCmdRecipients, idx);
    integer amt  = llList2Integer(inflightCmdAmounts, idx);
    inflightCmdTxKeys          = llDeleteSubList(inflightCmdTxKeys,          idx, idx);
    inflightCmdIdempotencyKeys = llDeleteSubList(inflightCmdIdempotencyKeys, idx, idx);
    inflightCmdRecipients      = llDeleteSubList(inflightCmdRecipients,      idx, idx);
    inflightCmdAmounts         = llDeleteSubList(inflightCmdAmounts,         idx, idx);
    return [ikey, recip, amt];
}

// ---------------------------------------------------------------------------

default {
    state_entry() {
        REGISTER_URL          = "";
        DEPOSIT_URL           = "";
        WITHDRAW_REQUEST_URL  = "";
        PAYOUT_RESULT_URL     = "";
        HEARTBEAT_URL         = "";
        AVATAR_GROUPS_URL     = "";
        GROUP_DEPOSIT_URL     = "";
        SHARED_SECRET         = "";
        TERMINAL_ID           = "";
        REGION_NAME           = "";
        DEBUG_MODE            = TRUE;

        notecardLineRequest = NULL_KEY;
        notecardLineNum     = 0;
        httpInUrl           = "";
        urlRequestId        = NULL_KEY;
        debitGranted        = FALSE;
        registered          = FALSE;
        registerReqId       = NULL_KEY;
        registerAttempt     = 0;
        registerNextRetryAt = 0;
        withdrawSessions    = [];
        pendingGroupDeposits   = [];
        pendingDialogAvatar    = NULL_KEY;
        pendingDialogLabels    = "";
        pendingDialogPublicIds = "";
        pendingDialogNextAfter = "";
        pendingDialogExpiresAt = 0;
        avatarGroupsReqId      = NULL_KEY;
        avatarGroupsReqAvatar  = NULL_KEY;
        groupDepositReqId      = NULL_KEY;
        groupDepositPayer      = NULL_KEY;
        groupDepositAmount     = 0;
        groupDepositTxKey      = "";
        groupDepositGroupId    = "";
        groupDepositGroupName  = "";
        groupDepositRetryCount = 0;
        groupDepositNextRetryAt = 0;
        paymentReqId        = NULL_KEY;
        paymentPayer        = NULL_KEY;
        paymentAmount       = 0;
        paymentTxKey        = "";
        paymentRetryCount   = 0;
        withdrawReqId       = NULL_KEY;
        withdrawPayer       = NULL_KEY;
        withdrawAmount      = 0;
        withdrawTxKey       = "";
        withdrawRetryCount  = 0;
        inflightCmdTxKeys          = [];
        inflightCmdIdempotencyKeys = [];
        inflightCmdRecipients      = [];
        inflightCmdAmounts         = [];
        payoutResultReqId   = NULL_KEY;
        heartbeatReqId      = NULL_KEY;
        // First heartbeat fires HEARTBEAT_INTERVAL_SECONDS after rez —
        // by then registration has either succeeded or is retrying, so
        // the heartbeat refreshes lastSeenAt on a known-good terminal.
        nextHeartbeatAt     = llGetUnixTime() + HEARTBEAT_INTERVAL_SECONDS;
        timerPhase          = TIMER_NONE;

        // Mainland-only grid guard
        if (llGetEnv("sim_channel") != "Second Life Server") {
            llOwnerSay("CRITICAL: not on Second Life Server grid; halting.");
            return;
        }

        if (TERMINAL_ID == "") TERMINAL_ID = (string)llGetKey();
        if (REGION_NAME == "") REGION_NAME = llGetRegionName();

        // Single shared listen on a random negative channel.
        mainChan = -100000 - (integer)(llFrand(50000.0));
        mainListenHandle = llListen(mainChan, "", NULL_KEY, "");

        setIdleChrome();
        readNotecardLine(0);

        // Request DEBIT permission from owner so HTTP-in PAYOUT/WITHDRAW
        // can fire llTransferLindenDollars.
        llRequestPermissions(llGetOwner(), PERMISSION_DEBIT);

        // 10-second timer for session sweep + retry checks.
        timerPhase = TIMER_SESSION_SWEEP;
        llSetTimerEvent(10.0);
    }

    on_rez(integer n) {
        llResetScript();
    }

    changed(integer change) {
        if (change & CHANGED_INVENTORY) {
            llResetScript();
        }
        if (change & CHANGED_REGION_START) {
            // Re-request the HTTP-in URL and re-register with backend.
            urlRequestId = llRequestURL();
        }
    }

    run_time_permissions(integer perm) {
        if (perm & PERMISSION_DEBIT) {
            debitGranted = TRUE;
            // Now request HTTP-in URL.
            urlRequestId = llRequestURL();
        } else {
            llOwnerSay("CRITICAL: PERMISSION_DEBIT denied. Script halted. Owner must re-grant.");
        }
    }

    dataserver(key requested, string data) {
        if (requested == notecardLineRequest) {
            if (data == EOF) {
                // Check that all required keys parsed
                if (REGISTER_URL == "" || DEPOSIT_URL == "" || WITHDRAW_REQUEST_URL == ""
                    || PAYOUT_RESULT_URL == "" || SHARED_SECRET == "" || TERMINAL_ID == "") {
                    llOwnerSay("CRITICAL: incomplete config notecard.");
                    return;
                }
                if (DEBUG_MODE) llOwnerSay("SLParcels Terminal: config loaded.");
                return;
            }
            parseConfigLine(data);
            readNotecardLine(notecardLineNum + 1);
        }
    }

    http_request(key id, string method, string body) {
        if (id == urlRequestId) {
            if (method == URL_REQUEST_GRANTED) {
                httpInUrl = body;
                if (DEBUG_MODE) llOwnerSay("SLParcels Terminal: HTTP-in URL granted: " + httpInUrl);
                if (REGISTER_URL != "") {
                    registerAttempt = 1;
                    postRegister();
                }
            } else if (method == URL_REQUEST_DENIED) {
                llOwnerSay("CRITICAL: URL_REQUEST_DENIED. Land must allow scripts to request URLs.");
            }
            return;
        }

        // Backend-initiated HTTP-in command (PAYOUT / WITHDRAW)
        if (method != "POST") {
            llHTTPResponse(id, 405, "{\"error\":\"method not allowed\"}");
            return;
        }
        // Validate shared secret in body
        string secret = llJsonGetValue(body, ["sharedSecret"]);
        if (secret == JSON_INVALID || secret != SHARED_SECRET) {
            llHTTPResponse(id, 403, "{\"error\":\"shared secret mismatch\"}");
            return;
        }
        string action      = llJsonGetValue(body, ["action"]);
        string ikey        = llJsonGetValue(body, ["idempotencyKey"]);
        string recipient   = llJsonGetValue(body, ["recipientUuid"]);
        integer amount     = (integer)llJsonGetValue(body, ["amount"]);

        if (action == "REFUND") {
            // Defensive: refunds are wallet credits in the new model and
            // shouldn't be dispatched to terminals. Log loud and fail.
            llOwnerSay("CRITICAL: unexpected REFUND HTTP-in command; refunds are now wallet credits. idempotencyKey=" + ikey);
            llHTTPResponse(id, 200,
                "{\"status\":\"FAILED\",\"reason\":\"REFUND_NOT_SUPPORTED\"}");
            return;
        }

        // Sub-project G §8.2 -- graceful skip for stale $0 PAYOUT commands. After
        // the backend's runZeroPayoutSuccessInline short-circuit ships, backend
        // never emits amount=0; but a command queued before the deploy can still
        // arrive. Ack as success so the backend clears the command on the
        // callback path rather than letting it stall.
        if (action == "PAYOUT" && amount <= 0) {
            llOwnerSay("SLParcels Terminal: ignoring 0-L$ PAYOUT for ikey=" + ikey);
            // Ack receipt to the dispatcher first so it doesn't retry the POST.
            llHTTPResponse(id, 200, "{\"status\":\"ACCEPTED\"}");
            // Post the synthetic success callback. slTransactionKey "0" signals
            // "no SL transaction happened" -- backend's ledger code treats it as
            // an opaque string and stores it on the AUCTION_ESCROW_PAYOUT row.
            string skipBody = "{"
                + "\"idempotencyKey\":\"" + escapeJson(ikey) + "\","
                + "\"success\":true,"
                + "\"slTransactionKey\":\"0\","
                + "\"memo\":\"skipped-zero-amount\","
                + "\"terminalId\":\"" + escapeJson(TERMINAL_ID) + "\","
                + "\"sharedSecret\":\"" + escapeJson(SHARED_SECRET) + "\""
                + "}";
            payoutResultReqId = llHTTPRequest(PAYOUT_RESULT_URL,
                [HTTP_METHOD, "POST",
                 HTTP_MIMETYPE, "application/json",
                 HTTP_BODY_MAXLENGTH, 16384],
                skipBody);
            return;
        }

        if (recipient == JSON_INVALID || amount <= 0) {
            llHTTPResponse(id, 400, "{\"error\":\"missing recipient or amount\"}");
            return;
        }
        if (!debitGranted) {
            llHTTPResponse(id, 503, "{\"error\":\"DEBIT not granted\"}");
            return;
        }

        // Ack receipt; transaction_result will follow async.
        llHTTPResponse(id, 200, "{\"status\":\"ACCEPTED\"}");

        key txKey = llTransferLindenDollars((key)recipient, amount);
        addInflightCommand(txKey, ikey, (key)recipient, amount);
        if (DEBUG_MODE) {
            llOwnerSay("SLParcels Terminal: HTTP-in " + action + " to "
                + recipient + " L$" + (string)amount + " ikey=" + ikey);
        }
    }

    transaction_result(key id, integer success, string data) {
        list inflight = removeInflightByTxKey(id);
        if (llGetListLength(inflight) == 0) return;
        string ikey = llList2String(inflight, 0);
        string recip = llList2String(inflight, 1);
        integer amount = llList2Integer(inflight, 2);

        // Build payout-result body. success is integer 0/1; JSON requires unquoted true/false.
        string successStr = "false";
        if (success) successStr = "true";

        string body = "{"
            + "\"idempotencyKey\":\"" + escapeJson(ikey) + "\","
            + "\"success\":" + successStr + ","
            + "\"slTransactionKey\":\"" + (string)id + "\","
            + "\"terminalId\":\"" + escapeJson(TERMINAL_ID) + "\","
            + "\"sharedSecret\":\"" + escapeJson(SHARED_SECRET) + "\""
            + "}";
        if (!success) {
            body = "{"
                + "\"idempotencyKey\":\"" + escapeJson(ikey) + "\","
                + "\"success\":false,"
                + "\"slTransactionKey\":\"" + (string)id + "\","
                + "\"errorMessage\":\"" + escapeJson(data) + "\","
                + "\"terminalId\":\"" + escapeJson(TERMINAL_ID) + "\","
                + "\"sharedSecret\":\"" + escapeJson(SHARED_SECRET) + "\""
                + "}";
        }

        payoutResultReqId = llHTTPRequest(PAYOUT_RESULT_URL,
            [HTTP_METHOD, "POST",
             HTTP_MIMETYPE, "application/json",
             HTTP_BODY_MAXLENGTH, 16384],
            body);

        if (DEBUG_MODE) {
            llOwnerSay("SLParcels Terminal: transfer to " + recip + " L$"
                + (string)amount + " success=" + successStr);
        }
    }

    money(key payer, integer amount) {
        // Defensive: if config is missing or DEPOSIT_URL is empty, refund
        // immediately and shout. Without this guard, llHTTPRequest("", ...)
        // would silently fail and the L$ would be stranded in the prim.
        if (DEPOSIT_URL == "" || SHARED_SECRET == "" || TERMINAL_ID == "") {
            llOwnerSay("CRITICAL: deposit received but config incomplete: "
                + "refunding L$" + (string)amount + " to " + (string)payer
                + ". Check the 'config' notecard for DEPOSIT_URL, "
                + "SHARED_SECRET, TERMINAL_ID.");
            llTransferLindenDollars(payer, amount);
            return;
        }

        // "Pay to group" routing: if the payer picked a group within the
        // last GROUP_DEPOSIT_SLOT_TTL seconds, route this money() event to
        // /sl/wallet/group-deposit instead of the personal /sl/wallet/deposit
        // flow. The sweeper evicts expired slots out-of-band, but we still
        // double-check expiresAt here in case money() fires between the slot
        // expiring and the next sweeper tick.
        integer groupSlotIdx = findGroupDepositSlot(payer);
        if (groupSlotIdx >= 0) {
            integer expiresAt = llList2Integer(pendingGroupDeposits,
                groupSlotIdx * GROUP_DEPOSIT_SLOT_STRIDE + 3);
            if (expiresAt >= llGetUnixTime()
                    && GROUP_DEPOSIT_URL != ""
                    && groupDepositReqId == NULL_KEY) {
                string groupId   = llList2String(pendingGroupDeposits,
                    groupSlotIdx * GROUP_DEPOSIT_SLOT_STRIDE + 1);
                string groupName = llList2String(pendingGroupDeposits,
                    groupSlotIdx * GROUP_DEPOSIT_SLOT_STRIDE + 2);
                releaseGroupDepositSlot(payer);
                groupDepositPayer      = payer;
                groupDepositAmount     = amount;
                groupDepositTxKey      = (string)llGenerateKey();
                groupDepositGroupId    = groupId;
                groupDepositGroupName  = groupName;
                groupDepositRetryCount = 0;
                fireGroupDeposit();
                return;
            }
            // Expired (or another group deposit already in flight on this
            // terminal). Clear stale slot and fall through to personal.
            releaseGroupDepositSlot(payer);
        }

        // Lockless: every money() event is a personal-wallet deposit.
        paymentPayer      = payer;
        paymentAmount     = amount;
        paymentTxKey      = (string)llGenerateKey();
        paymentRetryCount = 0;
        firePayment();
    }

    touch_start(integer num) {
        key toucher = llDetectedKey(0);
        // Per-toucher dialog filtered by avatar key in the listen handler.
        // "Pay to group" appears only when both group-flow URLs are
        // configured -- pre-deploy terminals (notecard without the two
        // new keys) keep the legacy 2-button menu.
        list buttons = ["Deposit"];
        string prompt = "What would you like to do?\n\n"
            + "Deposit: right-click & pay (any amount)\n";
        if (AVATAR_GROUPS_URL != "" && GROUP_DEPOSIT_URL != "") {
            buttons += ["Pay to group"];
            prompt += "Pay to group: deposit L$ into a realty group wallet\n";
        }
        buttons += ["Withdraw"];
        prompt += "Withdraw: pull L$ from your wallet to your avatar";
        llDialog(toucher, prompt, buttons, mainChan);
    }

    listen(integer chan, string name, key id, string msg) {
        if (chan != mainChan) return;

        // Top-level menu responses (Deposit / Pay to group / Withdraw)
        if (msg == "Deposit") {
            llDialog(id,
                "To deposit: right-click this terminal → Pay → enter any L$ amount. "
                + "Funds will be credited to your SLParcels wallet.",
                ["OK"], mainChan);
            return;
        }
        if (msg == "Pay to group") {
            if (AVATAR_GROUPS_URL == "" || GROUP_DEPOSIT_URL == "") {
                // Defensive: button shouldn't have been shown. Fall through
                // silently rather than POST to an empty URL.
                return;
            }
            postAvatarGroups(id, "");
            return;
        }
        if (msg == "Withdraw") {
            integer slot = acquireOrResetSlot(id);
            if (slot < 0) {
                llDialog(id, "Terminal already in use, please use another terminal or try again later.", ["OK"], mainChan);
                return;
            }
            llTextBox(id, "Enter L$ amount to withdraw:", mainChan);
            return;
        }

        // ---- "Pay to group" dialog responses (More.../Cancel/group label) ----
        // Single-slot scalar state -- only the avatar who most recently
        // opened the picker can resolve a dialog here. A delayed click
        // from a previous avatar falls through silently.
        if (pendingDialogAvatar == id) {
            if (msg == "Cancel") {
                releaseGroupDialogSlot();
                return;
            }
            if (msg == "More...") {
                // Re-POST /avatar-groups with the saved cursor. The slot
                // stays open; the http_response handler will overwrite it
                // when the new page arrives.
                if (pendingDialogNextAfter != "") {
                    postAvatarGroups(id, pendingDialogNextAfter);
                }
                return;
            }
            // Group-label button: look up its publicId in the parallel
            // array. Walk the parsed list to find the index of the label
            // match.
            list labels    = llJson2List(pendingDialogLabels);
            list publicIds = llJson2List(pendingDialogPublicIds);
            integer labelCount = llGetListLength(labels);
            integer i;
            integer matchIdx = -1;
            for (i = 0; i < labelCount; ++i) {
                if (llList2String(labels, i) == msg) {
                    matchIdx = i;
                    i = labelCount; // break
                }
            }
            if (matchIdx < 0) {
                // Unknown button on an open group dialog. Most likely the
                // avatar opened a stale dialog after a /avatar-groups
                // pagination overwrote the slot, or we received a delayed
                // message. Ignore silently.
                return;
            }
            string chosenPublicId = llList2String(publicIds, matchIdx);
            string chosenName     = llList2String(labels, matchIdx);
            setGroupDepositSlot(id, chosenPublicId, chosenName);
            releaseGroupDialogSlot();
            llRegionSayTo(id, 0,
                "You have 60 seconds to right-click -> Pay -> enter L$ amount "
                + "to deposit into " + chosenName + ".");
            return;
        }

        // Dispatch by avatar key against per-flow withdraw slots
        integer slotIdx = findSlot(id);
        if (slotIdx < 0) {
            // Not a withdraw flow and not a known menu choice — ignore.
            return;
        }
        integer amt = slotAmount(slotIdx);
        if (amt == -1) {
            // Awaiting amount
            if (!isPositiveInteger(msg)) {
                llDialog(id, "Amount must be a positive integer.", ["OK"], mainChan);
                releaseSlot(id);
                return;
            }
            integer reqAmt = (integer)msg;
            setSlotAmount(slotIdx, reqAmt);
            extendSlot(slotIdx);
            llDialog(id, "Withdraw L$" + msg + " from your SLParcels wallet?",
                ["Yes", "No"], mainChan);
            return;
        }
        // Awaiting confirm
        if (msg == "Yes") {
            if (WITHDRAW_REQUEST_URL == "" || SHARED_SECRET == "" || TERMINAL_ID == "") {
                llDialog(id,
                    "Withdraw unavailable: terminal config incomplete. Contact Heath Onyx.",
                    ["OK"], mainChan);
                llOwnerSay("CRITICAL: withdraw attempt but WITHDRAW_REQUEST_URL "
                    + "or SHARED_SECRET or TERMINAL_ID is empty. Check 'config' notecard.");
                releaseSlot(id);
                return;
            }
            withdrawPayer      = id;
            withdrawAmount     = amt;
            withdrawTxKey      = (string)llGenerateKey();
            withdrawRetryCount = 0;
            fireWithdrawRequest();
            llDialog(id, "Withdrawal queued: L$" + (string)amt
                + " will arrive shortly.",
                ["OK"], mainChan);
        } else {
            llDialog(id, "Withdrawal cancelled.", ["OK"], mainChan);
        }
        releaseSlot(id);
    }

    http_response(key req, integer status, list meta, string body) {
        // ----- Register response -----
        if (req == registerReqId) {
            registerReqId = NULL_KEY;
            if (status >= 200 && status < 300) {
                registered = TRUE;
                registerAttempt = 0;
                if (DEBUG_MODE)
                    llOwnerSay("SLParcels Terminal: registered (terminal_id="
                        + TERMINAL_ID + ", url=" + httpInUrl + ")");
                return;
            }
            ++registerAttempt;
            if (registerAttempt > 5) {
                llOwnerSay("CRITICAL: registration failed after 5 attempts.");
                return;
            }
            if (DEBUG_MODE)
                llOwnerSay("SLParcels Terminal: register retry "
                    + (string)registerAttempt + "/5: status=" + (string)status);
            scheduleRegisterRetry();
            return;
        }

        // ----- Deposit response -----
        if (req == paymentReqId) {
            paymentReqId = NULL_KEY;
            if (status >= 200 && status < 300) {
                string s = llJsonGetValue(body, ["status"]);
                if (s == "OK") {
                    if (DEBUG_MODE)
                        llOwnerSay("SLParcels Terminal: deposit ok L$"
                            + (string)paymentAmount + " from " + (string)paymentPayer);
                } else if (s == "REFUND") {
                    string reason = llJsonGetValue(body, ["reason"]);
                    llTransferLindenDollars(paymentPayer, paymentAmount);
                    if (DEBUG_MODE)
                        llOwnerSay("SLParcels Terminal: deposit refunded ("
                            + reason + ") L$" + (string)paymentAmount
                            + " to " + (string)paymentPayer);
                } else if (s == "ERROR") {
                    // Deposit ERROR — refund anyway. The payer is real and
                    // their L$ is in our hands; whatever the backend tripped
                    // on (unknown terminal id, unparseable payer uuid, etc.)
                    // is our problem, not theirs. The earlier "could be an
                    // attack probe" rationale was unfounded — pre-flight
                    // shared-secret + SL header validation already throw
                    // before any L$-bearing path, so reaching this branch
                    // implies a legitimate but unhandled failure.
                    string reason = llJsonGetValue(body, ["reason"]);
                    llTransferLindenDollars(paymentPayer, paymentAmount);
                    if (DEBUG_MODE)
                        llOwnerSay("SLParcels Terminal: deposit refunded on ERROR ("
                            + reason + ") L$" + (string)paymentAmount
                            + " to " + (string)paymentPayer);
                }
                paymentPayer = NULL_KEY;
                paymentAmount = 0;
                paymentTxKey = "";
                paymentRetryCount = 0;
                return;
            }
            // Transient: schedule retry
            ++paymentRetryCount;
            if (DEBUG_MODE)
                llOwnerSay("SLParcels Terminal: deposit retry "
                    + (string)paymentRetryCount + "/5: status=" + (string)status);
            debugSayUser(paymentPayer, "deposit", status, body);
            schedulePaymentRetry();
            return;
        }

        // ----- Withdraw-request response -----
        if (req == withdrawReqId) {
            withdrawReqId = NULL_KEY;
            if (status >= 200 && status < 300) {
                string s = llJsonGetValue(body, ["status"]);
                if (s == "OK") {
                    if (DEBUG_MODE)
                        llOwnerSay("SLParcels Terminal: withdraw queued L$"
                            + (string)withdrawAmount + " for " + (string)withdrawPayer);
                } else if (s == "REFUND_BLOCKED") {
                    string reason = llJsonGetValue(body, ["reason"]);
                    string message = llJsonGetValue(body, ["message"]);
                    string msg = "Withdrawal declined: " + reason;
                    if (message != JSON_INVALID && message != "") {
                        msg += " (" + message + ")";
                    }
                    llDialog(withdrawPayer, msg, ["OK"], mainChan);
                } else if (s == "ERROR") {
                    string reason = llJsonGetValue(body, ["reason"]);
                    llOwnerSay("SLParcels Terminal: withdraw-request ERROR ("
                        + reason + ") for " + (string)withdrawPayer);
                }
                withdrawPayer = NULL_KEY;
                withdrawAmount = 0;
                withdrawTxKey = "";
                withdrawRetryCount = 0;
                return;
            }
            ++withdrawRetryCount;
            if (DEBUG_MODE)
                llOwnerSay("SLParcels Terminal: withdraw retry "
                    + (string)withdrawRetryCount + "/5: status=" + (string)status);
            debugSayUser(withdrawPayer, "withdraw-request", status, body);
            scheduleWithdrawRetry();
            return;
        }

        // ----- Payout-result ack -----
        if (req == payoutResultReqId) {
            payoutResultReqId = NULL_KEY;
            if (status >= 200 && status < 300) {
                if (DEBUG_MODE)
                    llOwnerSay("SLParcels Terminal: payout-result acknowledged.");
            } else {
                llOwnerSay("CRITICAL: /payout-result POST failed status="
                    + (string)status);
            }
            return;
        }

        // ----- /avatar-groups response -----
        if (req == avatarGroupsReqId) {
            avatarGroupsReqId = NULL_KEY;
            key targetAvatar = avatarGroupsReqAvatar;
            avatarGroupsReqAvatar = NULL_KEY;
            if (status < 200 || status >= 300) {
                llRegionSayTo(targetAvatar, 0,
                    "Unable to fetch groups right now, please try again in a moment.");
                debugSayUser(targetAvatar, "avatar-groups", status, body);
                releaseGroupDialogSlot();
                return;
            }
            // Body shape: { "groups":[{"publicId":"...","name":"..."},...],
            //               "hasMore":bool, "nextAfter":"..." }
            string groupsJson = llJsonGetValue(body, ["groups"]);
            if (groupsJson == JSON_INVALID) {
                llRegionSayTo(targetAvatar, 0,
                    "Unable to read groups response. Contact Heath Onyx if this persists.");
                releaseGroupDialogSlot();
                return;
            }
            list groupList = llJson2List(groupsJson);
            integer groupCount = llGetListLength(groupList);
            if (groupCount == 0) {
                llRegionSayTo(targetAvatar, 0,
                    "You are not a member of any group with deposit permission.");
                releaseGroupDialogSlot();
                return;
            }

            // Build parallel arrays of labels (truncated names) and publicIds.
            // We dialog with up to 10 group buttons + "More..." + "Cancel"
            // (LSL dialog cap is 12). Backend already pages at 12 per spec,
            // so we keep the cap at 10 groups here to leave room for both
            // the cursor and Cancel buttons.
            list labels    = [];
            list publicIds = [];
            integer i;
            integer dialogCap = 10;
            integer toShow = groupCount;
            if (toShow > dialogCap) toShow = dialogCap;
            for (i = 0; i < toShow; ++i) {
                string entryJson = llList2String(groupList, i);
                string pid  = llJsonGetValue(entryJson, ["publicId"]);
                string nm   = llJsonGetValue(entryJson, ["name"]);
                if (pid == JSON_INVALID || nm == JSON_INVALID) {
                    // Skip malformed entry; the backend contract guarantees
                    // both fields, so this is purely defensive.
                } else {
                    labels    += [truncateGroupLabel(nm)];
                    publicIds += [pid];
                }
            }

            string hasMore   = llJsonGetValue(body, ["hasMore"]);
            string nextAfter = llJsonGetValue(body, ["nextAfter"]);
            // hasMore is JSON true|false. nextAfter is JSON null when no
            // more pages — surfaces in LSL as JSON_NULL or the literal
            // "null" string depending on the SL build.
            string nextAfterStored = "";
            if (hasMore == "true" && nextAfter != JSON_INVALID
                    && nextAfter != "null" && nextAfter != JSON_NULL
                    && nextAfter != "") {
                nextAfterStored = nextAfter;
            }

            list buttons = labels;
            if (nextAfterStored != "") buttons += ["More..."];
            buttons += ["Cancel"];

            // Persist label->publicId mapping until the avatar picks one.
            string labelsJson    = llList2Json(JSON_ARRAY, labels);
            string publicIdsJson = llList2Json(JSON_ARRAY, publicIds);
            setGroupDialogSlot(targetAvatar, labelsJson, publicIdsJson, nextAfterStored);

            llDialog(targetAvatar, "Pick a group to deposit into:",
                buttons, mainChan);
            return;
        }

        // ----- /group-deposit response -----
        if (req == groupDepositReqId) {
            groupDepositReqId = NULL_KEY;
            if (status >= 200 && status < 300) {
                string s = llJsonGetValue(body, ["status"]);
                if (s == "OK") {
                    if (DEBUG_MODE)
                        llOwnerSay("SLParcels Terminal: group deposit ok L$"
                            + (string)groupDepositAmount
                            + " to " + groupDepositGroupName);
                } else if (s == "REFUND") {
                    string reason = llJsonGetValue(body, ["reason"]);
                    llTransferLindenDollars(groupDepositPayer, groupDepositAmount);
                    if (DEBUG_MODE)
                        llOwnerSay("SLParcels Terminal: group deposit refunded ("
                            + reason + ") L$" + (string)groupDepositAmount
                            + " to " + (string)groupDepositPayer);
                } else if (s == "ERROR") {
                    // Mirrors the personal-deposit ERROR branch: bounce L$
                    // defensively. By the time this endpoint runs, L$ is in
                    // the script's hands and the payer is a real avatar
                    // (pre-flight shared-secret / SL headers already
                    // validated). See CLAUDE.md "always refund on deposit
                    // error".
                    string reason = llJsonGetValue(body, ["reason"]);
                    llTransferLindenDollars(groupDepositPayer, groupDepositAmount);
                    if (DEBUG_MODE)
                        llOwnerSay("SLParcels Terminal: group deposit refunded on ERROR ("
                            + reason + ") L$" + (string)groupDepositAmount
                            + " to " + (string)groupDepositPayer);
                }
                groupDepositPayer      = NULL_KEY;
                groupDepositAmount     = 0;
                groupDepositTxKey      = "";
                groupDepositGroupId    = "";
                groupDepositGroupName  = "";
                groupDepositRetryCount = 0;
                return;
            }
            // Transient: schedule retry. Idempotent by groupDepositTxKey
            // server-side.
            ++groupDepositRetryCount;
            if (DEBUG_MODE)
                llOwnerSay("SLParcels Terminal: group deposit retry "
                    + (string)groupDepositRetryCount + "/5: status=" + (string)status);
            debugSayUser(groupDepositPayer, "group-deposit", status, body);
            scheduleGroupDepositRetry();
            return;
        }

        // ----- Heartbeat ack -----
        if (req == heartbeatReqId) {
            heartbeatReqId = NULL_KEY;
            if (status >= 200 && status < 300) {
                if (DEBUG_MODE)
                    llOwnerSay("SLParcels Terminal: heartbeat ok.");
            } else {
                // Heartbeats are best-effort: log and move on. Next interval
                // will retry naturally.
                if (DEBUG_MODE)
                    llOwnerSay("SLParcels Terminal: heartbeat failed status="
                        + (string)status + " (will retry on next interval)");
            }
            return;
        }
    }

    timer() {
        // Multi-purpose 10-second timer: sweeps expired withdraw +
        // group-deposit + group-dialog slots, checks for due register /
        // deposit / withdraw / group-deposit retries, then continues.
        integer now = llGetUnixTime();

        if (timerPhase == TIMER_REGISTER_RETRY && now >= registerNextRetryAt) {
            postRegister();
        }
        if (timerPhase == TIMER_PAYMENT_RETRY && now >= paymentNextRetryAt) {
            firePayment();
        }
        if (timerPhase == TIMER_WITHDRAW_RETRY && now >= withdrawNextRetryAt) {
            fireWithdrawRequest();
        }
        if (timerPhase == TIMER_GROUP_DEPOSIT_RETRY
                && now >= groupDepositNextRetryAt) {
            fireGroupDeposit();
        }

        // Heartbeat tick (independent of timerPhase — runs on its own
        // schedule, gated only on URL configured + no inflight request).
        if (HEARTBEAT_URL != "" && TERMINAL_ID != ""
                && heartbeatReqId == NULL_KEY
                && now >= nextHeartbeatAt) {
            postHeartbeat();
            nextHeartbeatAt = now + HEARTBEAT_INTERVAL_SECONDS;
        }

        sweepExpiredSlots();
        sweepExpiredGroupDepositSlots();
        sweepExpiredGroupDialogs();

        // Always reschedule for the next sweep.
        timerPhase = TIMER_SESSION_SWEEP;
        llSetTimerEvent(10.0);
    }
}
