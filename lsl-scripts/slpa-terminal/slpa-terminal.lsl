// SLPA Terminal (unified payments)
//
// Single-object payment kiosk for SLPA. Handles four touch-menu options:
//   1. Escrow Payment  — winner pays escrow on a won auction
//   2. Listing Fee     — seller pays listing fee on a DRAFT auction
//   3. Pay Penalty     — anyone clears a cancellation-penalty balance
//   4. Get Parcel Verifier — llGiveInventory of the SLPA Parcel Verifier object
//
// Also accepts backend-initiated PAYOUT/REFUND/WITHDRAW commands via HTTP-in.
//
// Configuration is loaded from a notecard named "config" in the same prim.
// See README.md for deployment and operations details.
//
// Grid guard note:
//   llGetEnv("sim_channel") == "Second Life Server"   (in-world env value)
//   X-SecondLife-Shard: "Production"                  (HTTP header value, checked backend-side)
// These are DIFFERENT strings. Both are checked: this script checks sim_channel
// at startup; SlHeaderValidator checks X-SecondLife-Shard on every inbound request.

// === Configuration loaded from notecard ===
string  REGISTER_URL        = "";
string  ESCROW_PAYMENT_URL  = "";
string  LISTING_FEE_URL     = "";
string  PENALTY_LOOKUP_URL  = "";
string  PENALTY_PAYMENT_URL = "";
string  PAYOUT_RESULT_URL   = "";
string  SHARED_SECRET       = "";
string  TERMINAL_ID         = "";
string  REGION_NAME         = "";
integer DEBUG_MODE          = TRUE;

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

// === Touch session lock ===
key     lockHolder     = NULL_KEY;
string  lockHolderName = "";
integer lockExpiresAt  = 0;
integer listenHandle   = -1;

// === Touch-state constants ===
integer STATE_IDLE               = 0;
integer STATE_MENU_OPEN          = 1;
integer STATE_AWAITING_AUCTION_ID = 2;
integer STATE_LOOKUP_INFLIGHT    = 3;
integer STATE_AWAITING_PAYMENT   = 4;
integer touchState               = 0;

// === Payment-kind constants ===
integer SELECTED_NONE        = 0;
integer SELECTED_ESCROW      = 1;
integer SELECTED_LISTING_FEE = 2;
integer SELECTED_PENALTY     = 3;

// === Touch payment selection ===
integer selectedKind         = 0;
integer selectedAuctionId    = 0;
integer expectedPenaltyAmount = 0;

// === Async request tracking ===
key     lookupReqId  = NULL_KEY;

// === Channels (set to random negatives in state_entry) ===
integer menuChan      = 0;
integer auctionIdChan = 0;

// === Background payment retry (independent of lock) ===
key     paymentReqId       = NULL_KEY;
key     paymentPayer       = NULL_KEY;
integer paymentAmount      = 0;
string  paymentTxKey       = "";    // synthesized once via llGenerateKey(); same across retries
integer paymentKind        = 0;     // SELECTED_*
integer paymentAuctionId   = 0;
integer paymentRetryCount  = 0;
integer paymentNextRetryAt = 0;

// === HTTP-in inflight command tracking (parallel to touch; lock-independent) ===
list    inflightCmdTxKeys         = [];
list    inflightCmdIdempotencyKeys = [];
list    inflightCmdRecipients     = [];
list    inflightCmdAmounts        = [];
integer MAX_INFLIGHT_CMDS         = 16;

// === Payout-result tracking ===
key     payoutResultReqId = NULL_KEY;

// === Timer phase ===
integer TIMER_NONE           = 0;
integer TIMER_LOCK_TTL       = 1;
integer TIMER_PAYMENT_RETRY  = 2;
integer TIMER_REGISTER_RETRY = 3;
integer timerPhase           = 0;

// ---------------------------------------------------------------------------
// Helper functions
// ---------------------------------------------------------------------------

readNotecardLine(integer n) {
    notecardLineNum     = n;
    notecardLineRequest = llGetNotecardLine("config", n);
}

parseConfigLine(string line) {
    if (llStringLength(line) == 0) return;
    if (llSubStringIndex(line, "#") == 0) return;  // comment line

    integer eq = llSubStringIndex(line, "=");
    if (eq < 1) return;

    string k = llStringTrim(llGetSubString(line, 0, eq - 1), STRING_TRIM);
    string v = llStringTrim(llGetSubString(line, eq + 1, -1), STRING_TRIM);

    if      (k == "REGISTER_URL")        REGISTER_URL        = v;
    else if (k == "ESCROW_PAYMENT_URL")  ESCROW_PAYMENT_URL  = v;
    else if (k == "LISTING_FEE_URL")     LISTING_FEE_URL     = v;
    else if (k == "PENALTY_LOOKUP_URL")  PENALTY_LOOKUP_URL  = v;
    else if (k == "PENALTY_PAYMENT_URL") PENALTY_PAYMENT_URL = v;
    else if (k == "PAYOUT_RESULT_URL")   PAYOUT_RESULT_URL   = v;
    else if (k == "SHARED_SECRET")       SHARED_SECRET       = v;
    else if (k == "TERMINAL_ID")         TERMINAL_ID         = v;
    else if (k == "REGION_NAME")         REGION_NAME         = v;
    else if (k == "DEBUG_MODE")
        DEBUG_MODE = (v == "true" || v == "TRUE" || v == "1");
}

string escapeJson(string s) {
    s = llReplaceSubString(s, "\\", "\\\\", 0);
    s = llReplaceSubString(s, "\"", "\\\"", 0);
    return s;
}

// debugSayUser: when DEBUG_MODE is on, surface the parsed ProblemDetail
// fields (status, title, detail, code) from a backend error response to
// both the toucher (`who`, may be NULL_KEY for HTTP-in commands) and
// owner-chat. The user-visible production message stays generic; this
// adds a labelled diagnostic line so an operator standing at the
// terminal can see exactly why a 4xx/5xx came back without grepping
// CloudWatch.
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
    // If the response wasn't a Spring ProblemDetail (no title/detail/code),
    // surface the raw body excerpt so we can see HTML error pages, plain
    // text, or empty bodies returned by intermediate proxies / framework
    // defaults.
    if (!haveAny) {
        integer blen = llStringLength(body);
        if (blen == 0) {
            msg += " body=<empty>";
        } else {
            integer cap = blen;
            if (cap > 200) cap = 200;
            msg += " body[" + (string)blen + "]=" + llGetSubString(body, 0, cap - 1);
        }
    }
    if (who != NULL_KEY) llRegionSayTo(who, 0, msg);
    llOwnerSay("SLPA Terminal: " + msg);
}

setBusyChrome() {
    llSetText("SLPA Terminal\n<In Use>", <1.0, 0.2, 0.2>, 1.0);
    llSetObjectName("SLPA Terminal <In Use>");
}

setIdleChrome() {
    llSetText("SLPA Terminal\nTouch for options", <1.0, 1.0, 1.0>, 1.0);
    llSetObjectName("SLPA Terminal");
}

releaseLock() {
    if (listenHandle != -1) {
        llListenRemove(listenHandle);
        listenHandle = -1;
    }
    lockHolder            = NULL_KEY;
    lockHolderName        = "";
    lockExpiresAt         = 0;
    selectedKind          = SELECTED_NONE;
    selectedAuctionId     = 0;
    expectedPenaltyAmount = 0;
    touchState            = STATE_IDLE;
    // Restore IDLE pay price — no payment accepted
    llSetPayPrice(PAY_HIDE, [PAY_HIDE, PAY_HIDE, PAY_HIDE, PAY_HIDE]);
    // Cancel lock-TTL timer (payment-retry timer lives independently)
    if (timerPhase == TIMER_LOCK_TTL) {
        timerPhase = TIMER_NONE;
        llSetTimerEvent(0);
    }
    setIdleChrome();
}

extendLock(integer seconds) {
    lockExpiresAt = llGetUnixTime() + seconds;
    timerPhase    = TIMER_LOCK_TTL;
    llSetTimerEvent((float)seconds);
}

acquireLock(key holder, string name) {
    lockHolder     = holder;
    lockHolderName = name;
    setBusyChrome();
    extendLock(60);
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

// retrySchedule is 1-indexed: attempt 1 = first retry after initial failure.
// schedule indices: [10, 30, 90, 300, 900]
integer retryDelay(integer attempt) {
    list schedule = [10, 30, 90, 300, 900];
    integer idx = attempt - 1;
    if (idx < 0)  idx = 0;
    if (idx > 4)  idx = 4;
    return llList2Integer(schedule, idx);
}

scheduleRegisterRetry() {
    integer delay = retryDelay(registerAttempt);
    registerNextRetryAt = llGetUnixTime() + delay;
    timerPhase = TIMER_REGISTER_RETRY;
    llSetTimerEvent((float)delay);
}

firePayment() {
    string url;
    string body;

    if (paymentKind == SELECTED_ESCROW) {
        url = ESCROW_PAYMENT_URL;
        body = "{"
            + "\"auctionId\":" + (string)paymentAuctionId + ","
            + "\"payerUuid\":\"" + (string)paymentPayer + "\","
            + "\"amount\":" + (string)paymentAmount + ","
            + "\"slTransactionKey\":\"" + paymentTxKey + "\","
            + "\"terminalId\":\"" + escapeJson(TERMINAL_ID) + "\","
            + "\"sharedSecret\":\"" + escapeJson(SHARED_SECRET) + "\""
            + "}";
    } else if (paymentKind == SELECTED_LISTING_FEE) {
        url = LISTING_FEE_URL;
        body = "{"
            + "\"auctionId\":" + (string)paymentAuctionId + ","
            + "\"payerUuid\":\"" + (string)paymentPayer + "\","
            + "\"amount\":" + (string)paymentAmount + ","
            + "\"slTransactionKey\":\"" + paymentTxKey + "\","
            + "\"terminalId\":\"" + escapeJson(TERMINAL_ID) + "\","
            + "\"sharedSecret\":\"" + escapeJson(SHARED_SECRET) + "\""
            + "}";
    } else {
        // SELECTED_PENALTY
        url = PENALTY_PAYMENT_URL;
        body = "{"
            + "\"slAvatarUuid\":\"" + (string)paymentPayer + "\","
            + "\"slTransactionId\":\"" + paymentTxKey + "\","
            + "\"amount\":" + (string)paymentAmount + ","
            + "\"terminalId\":\"" + escapeJson(TERMINAL_ID) + "\""
            + "}";
    }

    paymentReqId = llHTTPRequest(url,
        [HTTP_METHOD, "POST",
         HTTP_MIMETYPE, "application/json",
         HTTP_BODY_MAXLENGTH, 16384],
        body);
}

schedulePaymentRetry() {
    if (paymentRetryCount >= 5) {
        llOwnerSay("CRITICAL: SLPA Terminal: payment from "
            + (string)paymentPayer
            + " L$" + (string)paymentAmount
            + " key " + paymentTxKey
            + " not acknowledged after 5 retries");
        // Clear payment state — stop retrying
        paymentReqId      = NULL_KEY;
        paymentPayer      = NULL_KEY;
        paymentAmount     = 0;
        paymentTxKey      = "";
        paymentKind       = SELECTED_NONE;
        paymentAuctionId  = 0;
        paymentRetryCount = 0;
        // Only clear timer if not being used for lock TTL
        if (timerPhase == TIMER_PAYMENT_RETRY) {
            timerPhase = TIMER_NONE;
            llSetTimerEvent(0);
        }
        return;
    }
    integer delay = retryDelay(paymentRetryCount);
    paymentNextRetryAt = llGetUnixTime() + delay;
    timerPhase = TIMER_PAYMENT_RETRY;
    llSetTimerEvent((float)delay);
}

// isPositiveInteger: checks that s is a non-empty string of digits (max 9 digits)
// representing a value > 0. Used to validate user-typed auction ID.
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

addInflightCommand(key txKey, string idempotencyKey, key recipient, integer amount) {
    if (llGetListLength(inflightCmdTxKeys) >= MAX_INFLIGHT_CMDS) {
        llOwnerSay("SLPA Terminal: inflight command cap (" + (string)MAX_INFLIGHT_CMDS
            + ") hit — refusing PAYOUT command. Backend retry budget will cover.");
        return;
    }
    inflightCmdTxKeys          = inflightCmdTxKeys          + [txKey];
    inflightCmdIdempotencyKeys = inflightCmdIdempotencyKeys + [idempotencyKey];
    inflightCmdRecipients      = inflightCmdRecipients      + [(string)recipient];
    inflightCmdAmounts         = inflightCmdAmounts         + [amount];
}

// Finds and removes entry by txKey. Returns [idempotencyKey, recipientUuid, amount]
// or [] if not found.
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
// Default state
// ---------------------------------------------------------------------------

default {

    state_entry() {
        // Reset all globals to sentinel values.
        REGISTER_URL        = "";
        ESCROW_PAYMENT_URL  = "";
        LISTING_FEE_URL     = "";
        PENALTY_LOOKUP_URL  = "";
        PENALTY_PAYMENT_URL = "";
        PAYOUT_RESULT_URL   = "";
        SHARED_SECRET       = "";
        TERMINAL_ID         = "";
        REGION_NAME         = "";
        DEBUG_MODE          = TRUE;

        notecardLineRequest = NULL_KEY;
        notecardLineNum     = 0;

        httpInUrl           = "";
        urlRequestId        = NULL_KEY;

        debitGranted        = FALSE;
        registered          = FALSE;
        registerReqId       = NULL_KEY;
        registerAttempt     = 0;
        registerNextRetryAt = 0;

        lockHolder          = NULL_KEY;
        lockHolderName      = "";
        lockExpiresAt       = 0;
        listenHandle        = -1;
        touchState          = STATE_IDLE;

        selectedKind          = SELECTED_NONE;
        selectedAuctionId     = 0;
        expectedPenaltyAmount = 0;
        lookupReqId           = NULL_KEY;

        paymentReqId       = NULL_KEY;
        paymentPayer       = NULL_KEY;
        paymentAmount      = 0;
        paymentTxKey       = "";
        paymentKind        = SELECTED_NONE;
        paymentAuctionId   = 0;
        paymentRetryCount  = 0;
        paymentNextRetryAt = 0;

        inflightCmdTxKeys          = [];
        inflightCmdIdempotencyKeys = [];
        inflightCmdRecipients      = [];
        inflightCmdAmounts         = [];

        payoutResultReqId = NULL_KEY;
        timerPhase        = TIMER_NONE;

        // Two distinct channels — use non-adjacent negatives to reduce collision risk.
        menuChan      = -100000 - (integer)(llFrand(50000.0));
        auctionIdChan = menuChan - 1;

        // Mainland-only guard.
        // llGetEnv("sim_channel") == "Second Life Server" on main grid.
        // This is DISTINCT from X-SecondLife-Shard ("Production") which SlHeaderValidator checks.
        if (llGetEnv("sim_channel") != "Second Life Server") {
            llOwnerSay("CRITICAL: SLPA Terminal must run on the main grid (sim_channel != \"Second Life Server\").");
            return;
        }

        // Idle state: no payment accepted until explicitly set.
        llSetPayPrice(PAY_HIDE, [PAY_HIDE, PAY_HIDE, PAY_HIDE, PAY_HIDE]);
        setIdleChrome();

        // Start reading the config notecard.
        readNotecardLine(0);
    }

    dataserver(key requested, string data) {
        if (requested != notecardLineRequest) return;

        if (data == NAK) {
            llOwnerSay("SLPA Terminal: notecard 'config' missing or unreadable");
            return;
        }

        if (data == EOF) {
            // Validate all required keys.
            if (REGISTER_URL == "" || ESCROW_PAYMENT_URL == "" || LISTING_FEE_URL == ""
                || PENALTY_LOOKUP_URL == "" || PENALTY_PAYMENT_URL == ""
                || PAYOUT_RESULT_URL == "" || SHARED_SECRET == "") {
                llOwnerSay("SLPA Terminal: incomplete config — REGISTER_URL / ESCROW_PAYMENT_URL / LISTING_FEE_URL / PENALTY_LOOKUP_URL / PENALTY_PAYMENT_URL / PAYOUT_RESULT_URL / SHARED_SECRET required");
                return;
            }
            // Apply defaults for optional keys.
            if (TERMINAL_ID == "") TERMINAL_ID = (string)llGetKey();
            if (REGION_NAME == "")  REGION_NAME = llGetRegionName();

            // Request PERMISSION_DEBIT from owner before doing anything else.
            llRequestPermissions(llGetOwner(), PERMISSION_DEBIT);
            return;
        }

        // Normal notecard line.
        parseConfigLine(data);
        readNotecardLine(notecardLineNum + 1);
    }

    run_time_permissions(integer perm) {
        if (perm & PERMISSION_DEBIT) {
            debitGranted = TRUE;
            // Request an HTTP-in URL; result arrives in http_request as URL_REQUEST_GRANTED.
            urlRequestId = llRequestURL();
        } else {
            llOwnerSay("CRITICAL: PERMISSION_DEBIT denied — script halted. Owner must re-grant.");
            // No further action; script sits idle until reset.
        }
    }

    http_request(key reqId, string method, string body) {
        // --- URL lifecycle events ---
        if (method == URL_REQUEST_GRANTED) {
            httpInUrl = body;
            postRegister();
            return;
        }
        if (method == URL_REQUEST_DENIED) {
            llOwnerSay("CRITICAL: HTTP-in URL request denied — region may not allow scripts to request URLs. Halting.");
            return;
        }

        // --- Incoming HTTP-in command from backend (POST) ---
        if (method == "POST") {
            // Parse TerminalCommandBody fields.
            string inSecret      = llJsonGetValue(body, ["sharedSecret"]);
            string action        = llJsonGetValue(body, ["action"]);
            string recipientUuid = llJsonGetValue(body, ["recipientUuid"]);
            integer amount       = (integer)llJsonGetValue(body, ["amount"]);
            string idempotencyKey = llJsonGetValue(body, ["idempotencyKey"]);

            // Shared-secret validation. LSL == is not constant-time, but HTTPS
            // provides transport security; spec accepts this trade-off.
            if (inSecret != SHARED_SECRET) {
                llHTTPResponse(reqId, 403, "{\"error\":\"secret mismatch\"}");
                return;
            }

            // Inflight cap check.
            if (llGetListLength(inflightCmdTxKeys) >= MAX_INFLIGHT_CMDS) {
                llHTTPResponse(reqId, 503, "{\"error\":\"terminal busy\"}");
                return;
            }

            // Ack receipt immediately; result follows via /payout-result.
            llHTTPResponse(reqId, 200, "{\"ack\":true}");

            // Execute the transfer regardless of action enum value
            // (PAYOUT, REFUND, WITHDRAW all map to llTransferLindenDollars).
            key txKey = llTransferLindenDollars((key)recipientUuid, amount);
            addInflightCommand(txKey, idempotencyKey, (key)recipientUuid, amount);

            if (DEBUG_MODE) {
                llOwnerSay("SLPA Terminal: HTTP-in command action=" + action
                    + " recipient=" + recipientUuid
                    + " amount=" + (string)amount
                    + " idempotencyKey=" + idempotencyKey);
            }
        }
    }

    transaction_result(key id, integer success, string data) {
        list found = removeInflightByTxKey(id);
        if (llGetListLength(found) == 0) {
            if (DEBUG_MODE)
                llOwnerSay("SLPA Terminal: transaction_result for unknown txKey=" + (string)id);
            return;
        }

        string ikey  = llList2String(found, 0);
        string recip = llList2String(found, 1);
        integer amt  = llList2Integer(found, 2);

        // Build payout-result body. success is an integer (0/1) in LSL;
        // JSON requires unquoted true/false. slTransactionKey/errorMessage may be null.
        string successStr;
        string txKeyField;
        string errorField;

        if (success) {
            successStr  = "true";
            txKeyField  = "\"" + escapeJson(data) + "\"";
            errorField  = "null";
        } else {
            successStr = "false";
            txKeyField = "null";
            if (data != "") {
                errorField = "\"" + escapeJson(data) + "\"";
            } else {
                errorField = "null";
            }
        }

        string pbody = "{"
            + "\"idempotencyKey\":\"" + escapeJson(ikey) + "\","
            + "\"success\":" + successStr + ","
            + "\"slTransactionKey\":" + txKeyField + ","
            + "\"errorMessage\":" + errorField + ","
            + "\"terminalId\":\"" + escapeJson(TERMINAL_ID) + "\","
            + "\"sharedSecret\":\"" + escapeJson(SHARED_SECRET) + "\""
            + "}";

        payoutResultReqId = llHTTPRequest(PAYOUT_RESULT_URL,
            [HTTP_METHOD, "POST",
             HTTP_MIMETYPE, "application/json",
             HTTP_BODY_MAXLENGTH, 16384],
            pbody);

        if (DEBUG_MODE) {
            llOwnerSay("SLPA Terminal: payout-result posted: success=" + successStr
                + " recipient=" + recip + " amount=" + (string)amt);
        }
    }

    http_response(key req, integer status, list meta, string body) {

        // --- Registration response ---
        if (req == registerReqId) {
            registerReqId = NULL_KEY;
            if (status == 200) {
                registered    = TRUE;
                registerAttempt = 0;
                if (DEBUG_MODE)
                    llOwnerSay("SLPA Terminal: registered (terminal_id=" + TERMINAL_ID
                        + ", url=" + httpInUrl + ")");
            } else {
                registerAttempt++;
                if (registerAttempt > 5) {
                    llOwnerSay("CRITICAL: SLPA Terminal: registration failed after 5 attempts. Status="
                        + (string)status);
                } else {
                    if (DEBUG_MODE)
                        llOwnerSay("SLPA Terminal: register retry " + (string)registerAttempt + "/5: status=" + (string)status);
                    scheduleRegisterRetry();
                }
            }
            return;
        }

        // --- Penalty-lookup response ---
        if (req == lookupReqId) {
            lookupReqId = NULL_KEY;
            if (status == 200) {
                // Backend always returns 200 for /penalty-lookup. Branch on
                // penaltyBalanceOwed: 0 means "no debt" (unknown avatar AND
                // known-but-zero are byte-identical for privacy); positive
                // means show the pay prompt. The endpoint used to 404 the
                // no-debt cases, but the SL HTTP outbound layer rewrites
                // 4xx responses whose Content-Type is not in HTTP_ACCEPT
                // into a synthetic 415, so the script never saw the real
                // 404. See PenaltyTerminalService.lookup javadoc.
                integer owed = (integer)llJsonGetValue(body, ["penaltyBalanceOwed"]);
                if (owed <= 0) {
                    llRegionSayTo(lockHolder, 0, "No penalty on file for your account.");
                    releaseLock();
                } else {
                    selectedKind          = SELECTED_PENALTY;
                    expectedPenaltyAmount = owed;
                    llRegionSayTo(lockHolder, 0,
                        "Penalty owed: L$" + (string)owed
                        + ". Pay below — full or partial OK.");
                    // PENALTY pay-price matrix: first arg = default text-field value = owed,
                    // quick buttons: full / half / quarter / hidden.
                    llSetPayPrice(owed, [owed, owed / 2, owed / 4, PAY_HIDE]);
                    touchState = STATE_AWAITING_PAYMENT;
                    extendLock(60);
                }
            } else {
                // 5xx, 0 (network), or unexpected status (e.g. 400 validation,
                // 403 SL_INVALID_HEADERS). The user message stays generic;
                // DEBUG_MODE adds the parsed ProblemDetail so an operator at
                // the terminal can diagnose without grepping CloudWatch.
                llRegionSayTo(lockHolder, 0, "Lookup failed — try again.");
                debugSayUser(lockHolder, "penalty lookup", status, body);
                releaseLock();
            }
            return;
        }

        // --- Payment response (escrow / listing-fee / penalty) ---
        if (req == paymentReqId) {
            paymentReqId = NULL_KEY;
            if (status == 200) {
                if (paymentKind == SELECTED_ESCROW || paymentKind == SELECTED_LISTING_FEE) {
                    string pstatus  = llJsonGetValue(body, ["status"]);
                    string pmessage = llJsonGetValue(body, ["message"]);
                    if (pstatus == "OK") {
                        llRegionSayTo(paymentPayer, 0,
                            "Payment of L$" + (string)paymentAmount + " accepted.");
                    } else if (pstatus == "REFUND") {
                        llRegionSayTo(paymentPayer, 0,
                            "Payment refused: " + pmessage + ". Refund will be issued.");
                    } else {
                        // ERROR
                        llRegionSayTo(paymentPayer, 0,
                            "Payment error: " + pmessage);
                    }
                } else if (paymentKind == SELECTED_PENALTY) {
                    integer remaining = (integer)llJsonGetValue(body, ["remainingBalance"]);
                    if (remaining == 0) {
                        llRegionSayTo(paymentPayer, 0, "Penalty cleared.");
                    } else {
                        llRegionSayTo(paymentPayer, 0,
                            "L$" + (string)paymentAmount + " applied. L$"
                            + (string)remaining + " still owed.");
                    }
                }
                // Clear payment state.
                paymentPayer      = NULL_KEY;
                paymentAmount     = 0;
                paymentTxKey      = "";
                paymentKind       = SELECTED_NONE;
                paymentAuctionId  = 0;
                paymentRetryCount = 0;
                if (timerPhase == TIMER_PAYMENT_RETRY) {
                    timerPhase = TIMER_NONE;
                    llSetTimerEvent(0);
                }
            } else if (status >= 400 && status < 500) {
                string title  = llJsonGetValue(body, ["title"]);
                string detail = llJsonGetValue(body, ["detail"]);
                if (title == JSON_INVALID || title == "") title = "Error";
                llOwnerSay("CRITICAL: SLPA Terminal: payment 4xx from "
                    + (string)paymentPayer
                    + " L$" + (string)paymentAmount
                    + " key " + paymentTxKey
                    + ": " + title + " — " + detail);
                llRegionSayTo(paymentPayer, 0,
                    "Payment error (" + (string)status + "): " + title + ". Contact SLPA support.");
                debugSayUser(paymentPayer, "payment", status, body);
                // Clear payment state — 4xx is non-retriable.
                paymentPayer      = NULL_KEY;
                paymentAmount     = 0;
                paymentTxKey      = "";
                paymentKind       = SELECTED_NONE;
                paymentAuctionId  = 0;
                paymentRetryCount = 0;
                if (timerPhase == TIMER_PAYMENT_RETRY) {
                    timerPhase = TIMER_NONE;
                    llSetTimerEvent(0);
                }
            } else {
                // 5xx or 0 — schedule retry.
                paymentRetryCount++;
                if (DEBUG_MODE)
                    llOwnerSay("SLPA Terminal: payment retry " + (string)paymentRetryCount
                        + "/5: status=" + (string)status);
                debugSayUser(paymentPayer,
                    "payment retry " + (string)paymentRetryCount + "/5",
                    status, body);
                schedulePaymentRetry();
            }
            return;
        }

        // --- Payout-result response ---
        if (req == payoutResultReqId) {
            payoutResultReqId = NULL_KEY;
            if (status == 200) {
                if (DEBUG_MODE)
                    llOwnerSay("SLPA Terminal: payout-result acknowledged by backend.");
            } else {
                llOwnerSay("CRITICAL: /payout-result POST failed status=" + (string)status
                    + " — backend will retry from terminal_commands ledger.");
            }
            return;
        }
    }

    touch_start(integer num_detected) {
        key    toucher     = llDetectedKey(0);
        string toucherName = llDetectedName(0);

        // If lock is held and not expired, bounce the toucher.
        if (lockHolder != NULL_KEY && lockExpiresAt > llGetUnixTime()) {
            llRegionSayTo(toucher, 0,
                "Terminal busy with " + lockHolderName + ". Try again in 60s.");
            return;
        }

        acquireLock(toucher, toucherName);
        listenHandle = llListen(menuChan, "", toucher, "");
        llDialog(toucher, "What do you need?",
            ["Escrow Payment", "Listing Fee", "Pay Penalty", "Get Parcel Verifier"],
            menuChan);
        touchState = STATE_MENU_OPEN;
    }

    listen(integer channel, string name, key id, string message) {

        // --- Main menu ---
        if (channel == menuChan && id == lockHolder) {
            llListenRemove(listenHandle);
            listenHandle = -1;

            if (message == "Escrow Payment") {
                selectedKind = SELECTED_ESCROW;
                listenHandle = llListen(auctionIdChan, "", lockHolder, "");
                llTextBox(lockHolder, "Enter the Auction ID from your auction page:", auctionIdChan);
                touchState = STATE_AWAITING_AUCTION_ID;
                extendLock(60);

            } else if (message == "Listing Fee") {
                selectedKind = SELECTED_LISTING_FEE;
                listenHandle = llListen(auctionIdChan, "", lockHolder, "");
                llTextBox(lockHolder, "Enter the Auction ID from your draft listing:", auctionIdChan);
                touchState = STATE_AWAITING_AUCTION_ID;
                extendLock(60);

            } else if (message == "Pay Penalty") {
                // Fire penalty lookup; lock stays held during inflight.
                // Manual JSON construction (matches postRegister + firePayment in
                // this script) instead of llList2Json — the grid's auto-typing in
                // llList2Json has historically produced bodies that the SL HTTP
                // layer flags as a non-JSON content shape, surfacing as 415 from
                // the backend even though HTTP_MIMETYPE is set to application/json.
                string lbody = "{"
                    + "\"slAvatarUuid\":\"" + (string)lockHolder + "\","
                    + "\"terminalId\":\""   + escapeJson(TERMINAL_ID) + "\""
                    + "}";
                lookupReqId = llHTTPRequest(PENALTY_LOOKUP_URL,
                    [HTTP_METHOD, "POST",
                     HTTP_MIMETYPE, "application/json",
                     HTTP_BODY_MAXLENGTH, 4096],
                    lbody);
                touchState = STATE_LOOKUP_INFLIGHT;
                extendLock(30);

            } else if (message == "Get Parcel Verifier") {
                llGiveInventory(lockHolder, "SLPA Parcel Verifier");
                llRegionSayTo(lockHolder, 0,
                    "Sent! Rez it on your parcel and enter your 6-digit PARCEL code.");
                releaseLock();
            }
            // If message is anything else (dialog dismissed without selection),
            // let the 60s lock TTL fire releaseLock() naturally.
            return;
        }

        // --- Auction-ID text box ---
        if (channel == auctionIdChan && id == lockHolder) {
            llListenRemove(listenHandle);
            listenHandle = -1;

            if (!isPositiveInteger(message)) {
                llRegionSayTo(lockHolder, 0,
                    "Invalid auction ID — must be a positive number.");
                releaseLock();
                return;
            }

            selectedAuctionId = (integer)message;

            if (selectedKind == SELECTED_ESCROW) {
                llRegionSayTo(lockHolder, 0,
                    "Pay the L$ escrow amount shown on auction #" + message + ".");
            } else {
                llRegionSayTo(lockHolder, 0,
                    "Pay the L$ listing fee shown on auction #" + message + ".");
            }

            // ESCROW / LISTING_FEE pay-price matrix:
            // first arg PAY_DEFAULT = empty text field so user types custom amount;
            // all four quick buttons hidden.
            llSetPayPrice(PAY_DEFAULT, [PAY_HIDE, PAY_HIDE, PAY_HIDE, PAY_HIDE]);
            touchState = STATE_AWAITING_PAYMENT;
            extendLock(60);
            return;
        }
    }

    money(key payer, integer amount) {
        // Defensive: only process payments when we are actively expecting them.
        // llSetPayPrice(PAY_HIDE,...) in IDLE/other states prevents accidental payments
        // in normal flow, but record unexpected ones for support.
        if (touchState != STATE_AWAITING_PAYMENT) {
            llOwnerSay("SLPA Terminal: unexpected payment from "
                + (string)payer + " L$" + (string)amount
                + " — touchState=" + (string)touchState + ". Forwarding to backend.");
        }

        if (payer != lockHolder && DEBUG_MODE) {
            llOwnerSay("SLPA Terminal: payment from " + (string)payer
                + " is not the menu user " + (string)lockHolder);
        }

        // Capture payment context. paymentTxKey is synthesized ONCE here and
        // reused across retries for idempotency.
        paymentPayer      = payer;
        paymentAmount     = amount;
        paymentKind       = selectedKind;
        paymentAuctionId  = selectedAuctionId;
        paymentTxKey      = (string)llGenerateKey();
        paymentRetryCount = 0;

        // Fire the first POST attempt.
        firePayment();

        // Release lock IMMEDIATELY so a second user can touch while retries run.
        releaseLock();

        // Ensure pay price returns to IDLE (releaseLock does this, but be explicit).
        llSetPayPrice(PAY_HIDE, [PAY_HIDE, PAY_HIDE, PAY_HIDE, PAY_HIDE]);
    }

    timer() {
        llSetTimerEvent(0);

        if (timerPhase == TIMER_LOCK_TTL) {
            timerPhase = TIMER_NONE;
            // Lock TTL expired — release lock in all states.
            // For STATE_LOOKUP_INFLIGHT: the HTTP response may still arrive after lock
            // is cleared; the lookupReqId guard in http_response will match, but
            // lockHolder will be NULL_KEY, so llRegionSayTo(NULL_KEY,...) is harmless
            // and releaseLock() will be a safe no-op (already cleared).
            releaseLock();

        } else if (timerPhase == TIMER_PAYMENT_RETRY) {
            timerPhase = TIMER_NONE;
            if (DEBUG_MODE)
                llOwnerSay("SLPA Terminal: payment retry " + (string)paymentRetryCount + "/5");
            firePayment();

        } else if (timerPhase == TIMER_REGISTER_RETRY) {
            timerPhase = TIMER_NONE;
            if (DEBUG_MODE)
                llOwnerSay("SLPA Terminal: register retry " + (string)registerAttempt + "/5");
            postRegister();
        }
    }

    changed(integer change) {
        // Notecard edit or parcel-verifier inventory update — full reset.
        if (change & CHANGED_INVENTORY) {
            llResetScript();
        }
        // Region restart: HTTP-in URL is invalidated; re-request and re-register.
        // Touch state is NOT preserved across region restarts — releaseLock not called
        // (lockHolder is cleared on reset anyway). The script remains in default state.
        if (change & CHANGED_REGION_START) {
            httpInUrl  = "";
            registered = FALSE;
            urlRequestId = llRequestURL();
        }
    }

    on_rez(integer start_param) {
        llResetScript();
    }

}
