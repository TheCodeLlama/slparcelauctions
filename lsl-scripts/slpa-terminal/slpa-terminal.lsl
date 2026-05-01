// SLPA Terminal (wallet model)
//
// Single-object payment kiosk for SLPA. Two touch-menu options:
//   1. Deposit  — instructs user to right-click and pay any amount
//   2. Withdraw — touch-confirmed withdrawal from the user's SLPA wallet
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

// === Timer phase ===
integer TIMER_NONE              = 0;
integer TIMER_SESSION_SWEEP     = 1;
integer TIMER_PAYMENT_RETRY     = 2;
integer TIMER_REGISTER_RETRY    = 3;
integer TIMER_WITHDRAW_RETRY    = 4;
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
    if (who != NULL_KEY) llRegionSayTo(who, 0, msg);
    llOwnerSay("SLPA Terminal: " + msg);
}

setIdleChrome() {
    llSetText("SLPA Terminal\nRight-click \xe2\x86\x92 Pay to deposit\nTouch for menu",
        <1.0, 1.0, 1.0>, 1.0);
    llSetObjectName("SLPA Terminal");
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
        llOwnerSay("CRITICAL: SLPA Terminal: deposit from "
            + (string)paymentPayer
            + " L$" + (string)paymentAmount
            + " key " + paymentTxKey
            + " not acknowledged after 5 retries");
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
        llOwnerSay("CRITICAL: SLPA Terminal: withdraw-request from "
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

// ---------------- HTTP-in inflight ----------------

addInflightCommand(key txKey, string idempotencyKey, key recipient, integer amount) {
    if (llGetListLength(inflightCmdTxKeys) >= MAX_INFLIGHT_CMDS) {
        llOwnerSay("SLPA Terminal: inflight command cap (" + (string)MAX_INFLIGHT_CMDS
            + ") hit — refusing command. Backend retry will cover.");
        return;
    }
    inflightCmdTxKeys          = inflightCmdTxKeys          + [txKey];
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
            llOwnerSay("CRITICAL: PERMISSION_DEBIT denied — script halted. Owner must re-grant.");
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
                if (DEBUG_MODE) llOwnerSay("SLPA Terminal: config loaded.");
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
                if (DEBUG_MODE) llOwnerSay("SLPA Terminal: HTTP-in URL granted: " + httpInUrl);
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
            llOwnerSay("CRITICAL: unexpected REFUND HTTP-in command — refunds are now wallet credits. idempotencyKey=" + ikey);
            llHTTPResponse(id, 200,
                "{\"status\":\"FAILED\",\"reason\":\"REFUND_NOT_SUPPORTED\"}");
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
            llOwnerSay("SLPA Terminal: HTTP-in " + action + " to "
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
            llOwnerSay("SLPA Terminal: transfer to " + recip + " L$"
                + (string)amount + " success=" + successStr);
        }
    }

    money(key payer, integer amount) {
        // Defensive: if config is missing or DEPOSIT_URL is empty, refund
        // immediately and shout. Without this guard, llHTTPRequest("", ...)
        // would silently fail and the L$ would be stranded in the prim.
        if (DEPOSIT_URL == "" || SHARED_SECRET == "" || TERMINAL_ID == "") {
            llOwnerSay("CRITICAL: deposit received but config incomplete — "
                + "refunding L$" + (string)amount + " to " + (string)payer
                + ". Check the 'config' notecard for DEPOSIT_URL, "
                + "SHARED_SECRET, TERMINAL_ID.");
            llTransferLindenDollars(payer, amount);
            return;
        }
        // Lockless: every money() event is a deposit.
        paymentPayer      = payer;
        paymentAmount     = amount;
        paymentTxKey      = (string)llGenerateKey();
        paymentRetryCount = 0;
        firePayment();
    }

    touch_start(integer num) {
        key toucher = llDetectedKey(0);
        // Per-toucher dialog filtered by avatar key in the listen handler.
        llDialog(toucher,
            "What would you like to do?\n\n"
            + "Deposit: right-click & pay (any amount)\n"
            + "Withdraw: pull L$ from your wallet to your avatar",
            ["Deposit", "Withdraw"], mainChan);
    }

    listen(integer chan, string name, key id, string msg) {
        if (chan != mainChan) return;

        // Top-level menu responses (Deposit / Withdraw)
        if (msg == "Deposit") {
            llRegionSayTo(id, 0,
                "To deposit: right-click this terminal \xe2\x86\x92 Pay \xe2\x86\x92 enter any L$ amount. "
                + "Funds will be credited to your SLPA wallet.");
            return;
        }
        if (msg == "Withdraw") {
            integer slot = acquireOrResetSlot(id);
            if (slot < 0) {
                llRegionSayTo(id, 0, "Terminal busy — try another nearby.");
                return;
            }
            llTextBox(id, "Enter L$ amount to withdraw:", mainChan);
            return;
        }

        // Dispatch by avatar key against per-flow withdraw slots
        integer slotIdx = findSlot(id);
        if (slotIdx < 0) return;
        integer amt = slotAmount(slotIdx);
        if (amt == -1) {
            // Awaiting amount
            if (!isPositiveInteger(msg)) {
                llRegionSayTo(id, 0, "Amount must be a positive integer.");
                releaseSlot(id);
                return;
            }
            integer reqAmt = (integer)msg;
            setSlotAmount(slotIdx, reqAmt);
            extendSlot(slotIdx);
            llDialog(id, "Withdraw L$" + msg + " from your SLPA wallet?",
                ["Yes", "No"], mainChan);
            return;
        }
        // Awaiting confirm
        if (msg == "Yes") {
            if (WITHDRAW_REQUEST_URL == "" || SHARED_SECRET == "" || TERMINAL_ID == "") {
                llRegionSayTo(id, 0,
                    "Withdraw unavailable: terminal config incomplete. Contact ops.");
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
            llRegionSayTo(id, 0, "Withdrawal queued — L$" + (string)amt
                + " will arrive shortly.");
        } else {
            llRegionSayTo(id, 0, "Withdrawal cancelled.");
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
                    llOwnerSay("SLPA Terminal: registered (terminal_id="
                        + TERMINAL_ID + ", url=" + httpInUrl + ")");
                return;
            }
            ++registerAttempt;
            if (registerAttempt > 5) {
                llOwnerSay("CRITICAL: registration failed after 5 attempts.");
                return;
            }
            if (DEBUG_MODE)
                llOwnerSay("SLPA Terminal: register retry "
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
                        llOwnerSay("SLPA Terminal: deposit ok L$"
                            + (string)paymentAmount + " from " + (string)paymentPayer);
                } else if (s == "REFUND") {
                    string reason = llJsonGetValue(body, ["reason"]);
                    llTransferLindenDollars(paymentPayer, paymentAmount);
                    if (DEBUG_MODE)
                        llOwnerSay("SLPA Terminal: deposit refunded ("
                            + reason + ") L$" + (string)paymentAmount
                            + " to " + (string)paymentPayer);
                } else if (s == "ERROR") {
                    // ERROR — do NOT refund (could be an attack probe).
                    string reason = llJsonGetValue(body, ["reason"]);
                    llOwnerSay("SLPA Terminal: deposit ERROR (" + reason
                        + ") L$" + (string)paymentAmount + " from " + (string)paymentPayer
                        + " — NOT refunded.");
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
                llOwnerSay("SLPA Terminal: deposit retry "
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
                        llOwnerSay("SLPA Terminal: withdraw queued L$"
                            + (string)withdrawAmount + " for " + (string)withdrawPayer);
                } else if (s == "REFUND_BLOCKED") {
                    string reason = llJsonGetValue(body, ["reason"]);
                    string message = llJsonGetValue(body, ["message"]);
                    string msg = "Withdrawal declined: " + reason;
                    if (message != JSON_INVALID && message != "") {
                        msg += " (" + message + ")";
                    }
                    llRegionSayTo(withdrawPayer, 0, msg);
                } else if (s == "ERROR") {
                    string reason = llJsonGetValue(body, ["reason"]);
                    llOwnerSay("SLPA Terminal: withdraw-request ERROR ("
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
                llOwnerSay("SLPA Terminal: withdraw retry "
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
                    llOwnerSay("SLPA Terminal: payout-result acknowledged.");
            } else {
                llOwnerSay("CRITICAL: /payout-result POST failed status="
                    + (string)status);
            }
            return;
        }
    }

    timer() {
        // Multi-purpose 10-second timer: sweeps expired withdraw slots,
        // checks for due register/deposit/withdraw retries, then continues.
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

        sweepExpiredSlots();

        // Always reschedule for the next sweep.
        timerPhase = TIMER_SESSION_SWEEP;
        llSetTimerEvent(10.0);
    }
}
