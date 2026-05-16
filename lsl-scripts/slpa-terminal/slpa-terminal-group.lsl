// SLParcels Terminal — "Pay to group" sister script.
//
// Designed to be dropped into the SAME prim as `slpa-terminal.lsl`. The
// wallet script owns touch/dialog/personal-deposit/withdraw/HTTP-in;
// this script owns ONLY the "Pay to group" flow. The split exists
// because the combined feature set tripped Stack-Heap Collision on real
// terminals -- each LSL script gets its own 64KB heap, so two scripts in
// one prim doubles the budget.
//
// Coordination protocol (llMessageLinked, fixed integer codes):
//   PING (10)    wallet -> group : at wallet's state_entry
//   PONG (11)    group  -> wallet: response to PING
//   START (12)   wallet -> group : user X tapped "Pay to group"
//   CLAIM (13)   group  -> wallet: user X has a pending slot; route
//                                  their next money() to me
//   RELEASE (14) group  -> wallet: drop the CLAIM on user X
//
// money() fires in BOTH scripts when an avatar pays. The CLAIM/RELEASE
// protocol lets the wallet skip its personal-deposit POST whenever this
// script intends to handle the L$ — so a single payment results in a
// single ledger credit.
//
// Configuration is shared with the wallet script via the same `config`
// notecard in the prim's contents. This script reads only the keys it
// needs (GROUP_DEPOSIT_URL, SHARED_SECRET, TERMINAL_ID, REGION_NAME,
// DEBUG_MODE) and silently ignores the rest.

// === Config ===
string  GROUP_DEPOSIT_URL = "";
string  SHARED_SECRET     = "";
string  TERMINAL_ID       = "";
string  REGION_NAME       = "";
integer DEBUG_MODE        = TRUE;

// === Notecard reading state ===
key     notecardLineRequest = NULL_KEY;
integer notecardLineNum     = 0;

// === Permissions ===
integer debitGranted = FALSE;

// === Listen channel for the typed group-name text-box ===
integer mainChan         = 0;
integer mainListenHandle = -1;

// === Link-message protocol codes (must match wallet script) ===
integer LM_PING    = 10;
integer LM_PONG    = 11;
integer LM_START   = 12;
integer LM_CLAIM   = 13;
integer LM_RELEASE = 14;

// === Pending text-box input (single-slot, last-write-wins) ===
key     pendingNameAvatar    = NULL_KEY;
integer pendingNameExpiresAt = 0;
integer NAME_INPUT_TTL       = 60;
integer GROUP_NAME_MAX_CHARS = 64;

// === Pending group-deposit slot (single-slot per terminal) ===
//   When the avatar has typed a name and the slot is set, the next
//   money() event from that avatar within DEPOSIT_SLOT_TTL seconds
//   POSTs to /sl/wallet/group-deposit. We keep it single-slot to
//   match wallet's CLAIMED_CAP=4 in spirit -- a second concurrent
//   "Pay to group" overwrites; rare in practice.
key     pendingDepositAvatar    = NULL_KEY;
string  pendingDepositGroupName = "";
integer pendingDepositExpiresAt = 0;
integer DEPOSIT_SLOT_TTL        = 60;

// === In-flight /group-deposit request tracking ===
key     depositReqId        = NULL_KEY;
key     depositPayer        = NULL_KEY;
integer depositAmount       = 0;
string  depositTxKey        = "";
string  depositGroupName    = "";
integer depositRetryCount   = 0;
integer depositNextRetryAt  = 0;

// === Timer phase ===
integer TIMER_NONE        = 0;
integer TIMER_SWEEP       = 1;
integer TIMER_DEPOSIT_RETRY = 2;
integer timerPhase        = 0;

// ---------------------------------------------------------------------------
// Helpers
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
    if      (k == "GROUP_DEPOSIT_URL") GROUP_DEPOSIT_URL = v;
    else if (k == "SHARED_SECRET")     SHARED_SECRET     = v;
    else if (k == "TERMINAL_ID")       TERMINAL_ID       = v;
    else if (k == "REGION_NAME")       REGION_NAME       = v;
    else if (k == "DEBUG_MODE")
        DEBUG_MODE = (v == "true" || v == "TRUE" || v == "1");
}

string escapeJson(string s) {
    s = llReplaceSubString(s, "\\", "\\\\", 0);
    s = llReplaceSubString(s, "\"", "\\\"", 0);
    return s;
}

integer retryDelay(integer attempt) {
    list schedule = [10, 30, 90, 300, 900];
    integer idx = attempt - 1;
    if (idx < 0) idx = 0;
    if (idx > 4) idx = 4;
    return llList2Integer(schedule, idx);
}

clearDepositSlot() {
    pendingDepositAvatar    = NULL_KEY;
    pendingDepositGroupName = "";
    pendingDepositExpiresAt = 0;
}

clearNameInputSlot() {
    pendingNameAvatar    = NULL_KEY;
    pendingNameExpiresAt = 0;
}

clearDepositRetryState() {
    depositReqId       = NULL_KEY;
    depositPayer       = NULL_KEY;
    depositAmount      = 0;
    depositTxKey       = "";
    depositGroupName   = "";
    depositRetryCount  = 0;
    depositNextRetryAt = 0;
}

fireDepositPost() {
    string body = "{"
        + "\"payerUuid\":\"" + (string)depositPayer + "\","
        + "\"groupName\":\"" + escapeJson(depositGroupName) + "\","
        + "\"amount\":" + (string)depositAmount + ","
        + "\"slTransactionKey\":\"" + depositTxKey + "\","
        + "\"terminalId\":\"" + escapeJson(TERMINAL_ID) + "\","
        + "\"sharedSecret\":\"" + escapeJson(SHARED_SECRET) + "\""
        + "}";
    depositReqId = llHTTPRequest(GROUP_DEPOSIT_URL,
        [HTTP_METHOD, "POST",
         HTTP_MIMETYPE, "application/json",
         HTTP_BODY_MAXLENGTH, 16384],
        body);
}

scheduleDepositRetry() {
    if (depositRetryCount >= 5) {
        // Refund discipline: L$ in hand, backend unreachable. Bounce.
        llTransferLindenDollars(depositPayer, depositAmount);
        llOwnerSay("CRITICAL: SLParcels Group Pay: deposit from "
            + (string)depositPayer
            + " L$" + (string)depositAmount
            + " to '" + depositGroupName
            + "' not acknowledged after 5 retries; refunded payer");
        clearDepositRetryState();
        if (timerPhase == TIMER_DEPOSIT_RETRY) {
            timerPhase = TIMER_SWEEP;
            llSetTimerEvent(10.0);
        }
        return;
    }
    integer delay = retryDelay(depositRetryCount);
    depositNextRetryAt = llGetUnixTime() + delay;
    timerPhase = TIMER_DEPOSIT_RETRY;
    llSetTimerEvent((float)delay);
}

// ---------------------------------------------------------------------------

default {
    state_entry() {
        GROUP_DEPOSIT_URL = "";
        SHARED_SECRET     = "";
        TERMINAL_ID       = "";
        REGION_NAME       = "";
        DEBUG_MODE        = TRUE;
        notecardLineRequest = NULL_KEY;
        notecardLineNum     = 0;
        debitGranted        = FALSE;
        clearDepositSlot();
        clearNameInputSlot();
        clearDepositRetryState();
        timerPhase = TIMER_NONE;

        // Mainland-only grid guard.
        if (llGetEnv("sim_channel") != "Second Life Server") {
            llOwnerSay("CRITICAL: SLParcels Group Pay: not on Second Life Server grid; halting.");
            return;
        }
        if (TERMINAL_ID == "") TERMINAL_ID = (string)llGetKey();
        if (REGION_NAME == "") REGION_NAME = llGetRegionName();

        // Random negative channel for our own llTextBox responses.
        mainChan = -200000 - (integer)(llFrand(50000.0));
        mainListenHandle = llListen(mainChan, "", NULL_KEY, "");

        readNotecardLine(0);
        llRequestPermissions(llGetOwner(), PERMISSION_DEBIT);

        // 10s sweeper.
        timerPhase = TIMER_SWEEP;
        llSetTimerEvent(10.0);
    }

    on_rez(integer n) { llResetScript(); }

    changed(integer change) {
        if (change & CHANGED_INVENTORY) llResetScript();
    }

    run_time_permissions(integer perm) {
        if (perm & PERMISSION_DEBIT) {
            debitGranted = TRUE;
        } else {
            llOwnerSay("CRITICAL: SLParcels Group Pay: PERMISSION_DEBIT denied. "
                + "Refunds-on-error won't work. Owner must re-grant.");
        }
    }

    dataserver(key requested, string data) {
        if (requested != notecardLineRequest) return;
        if (data == EOF) {
            if (GROUP_DEPOSIT_URL == "" || SHARED_SECRET == "" || TERMINAL_ID == "") {
                llOwnerSay("CRITICAL: SLParcels Group Pay: incomplete config notecard.");
                return;
            }
            if (DEBUG_MODE) llOwnerSay("SLParcels Group Pay: config loaded.");
            return;
        }
        parseConfigLine(data);
        readNotecardLine(notecardLineNum + 1);
    }

    link_message(integer sender, integer num, string str, key id) {
        if (num == LM_PING) {
            llMessageLinked(LINK_THIS, LM_PONG, "", NULL_KEY);
            return;
        }
        if (num == LM_START) {
            // Wallet says: avatar `id` picked "Pay to group". Open our
            // text-box and remember which avatar we're waiting on.
            if (GROUP_DEPOSIT_URL == "") {
                llRegionSayTo(id, 0, "SLParcels Group Pay isn't configured. Try again later.");
                return;
            }
            pendingNameAvatar    = id;
            pendingNameExpiresAt = llGetUnixTime() + NAME_INPUT_TTL;
            llTextBox(id,
                "Type the realty group's name (the name as shown on the "
                + "group's profile page). You then have 60 seconds to "
                + "right-click -> Pay to deposit. Cancel = no deposit.",
                mainChan);
            return;
        }
    }

    listen(integer chan, string name, key id, string msg) {
        if (chan != mainChan) return;
        if (pendingNameAvatar != id) return;
        clearNameInputSlot();
        string typed = llStringTrim(msg, STRING_TRIM);
        if (typed == "") {
            llRegionSayTo(id, 0, "SLParcels Group Pay: cancelled (no name typed).");
            return;
        }
        if (llStringLength(typed) > GROUP_NAME_MAX_CHARS) {
            typed = llGetSubString(typed, 0, GROUP_NAME_MAX_CHARS - 1);
        }
        pendingDepositAvatar    = id;
        pendingDepositGroupName = typed;
        pendingDepositExpiresAt = llGetUnixTime() + DEPOSIT_SLOT_TTL;
        // Tell the wallet script to skip its personal-deposit POST when
        // this avatar's next money() event fires.
        llMessageLinked(LINK_THIS, LM_CLAIM, "", id);
        llRegionSayTo(id, 0,
            "You have 60 seconds to right-click -> Pay -> enter L$ amount "
            + "to deposit into '" + typed + "'. The L$ is refunded if the "
            + "group name doesn't match.");
    }

    money(key payer, integer amount) {
        // Only handle money() if we have a fresh slot for this payer.
        // Otherwise the wallet script handles it for personal deposit.
        if (pendingDepositAvatar != payer) return;
        if (pendingDepositExpiresAt < llGetUnixTime()) {
            // Stale slot. Drop it and let wallet handle the L$ -- BUT
            // wallet already skipped (it saw the CLAIM). Send RELEASE so
            // wallet picks this money() up... too late, money() already
            // fired in wallet and was skipped. The L$ is in our hands.
            // Refund.
            llTransferLindenDollars(payer, amount);
            llOwnerSay("SLParcels Group Pay: slot expired before pay, refunded L$"
                + (string)amount + " to " + (string)payer);
            clearDepositSlot();
            llMessageLinked(LINK_THIS, LM_RELEASE, "", payer);
            return;
        }
        if (GROUP_DEPOSIT_URL == "" || SHARED_SECRET == "" || TERMINAL_ID == "") {
            llTransferLindenDollars(payer, amount);
            llOwnerSay("CRITICAL: SLParcels Group Pay: config incomplete, refunded L$"
                + (string)amount);
            clearDepositSlot();
            llMessageLinked(LINK_THIS, LM_RELEASE, "", payer);
            return;
        }
        string groupName = pendingDepositGroupName;
        clearDepositSlot();
        llMessageLinked(LINK_THIS, LM_RELEASE, "", payer);
        depositPayer      = payer;
        depositAmount     = amount;
        depositTxKey      = (string)llGenerateKey();
        depositGroupName  = groupName;
        depositRetryCount = 0;
        fireDepositPost();
    }

    http_response(key req, integer status, list meta, string body) {
        if (req != depositReqId) return;
        depositReqId = NULL_KEY;
        if (status >= 200 && status < 300) {
            string s = llJsonGetValue(body, ["status"]);
            if (s == "OK") {
                if (DEBUG_MODE)
                    llOwnerSay("SLParcels Group Pay: ok L$"
                        + (string)depositAmount + " to '" + depositGroupName + "'");
            } else if (s == "REFUND") {
                string reason = llJsonGetValue(body, ["reason"]);
                llTransferLindenDollars(depositPayer, depositAmount);
                if (DEBUG_MODE)
                    llOwnerSay("SLParcels Group Pay: refunded (" + reason
                        + ") L$" + (string)depositAmount + " to " + (string)depositPayer);
            } else if (s == "ERROR") {
                string reason = llJsonGetValue(body, ["reason"]);
                llTransferLindenDollars(depositPayer, depositAmount);
                if (DEBUG_MODE)
                    llOwnerSay("SLParcels Group Pay: refunded on ERROR (" + reason
                        + ") L$" + (string)depositAmount + " to " + (string)depositPayer);
            }
            clearDepositRetryState();
            return;
        }
        // Transient: retry on the same slTransactionKey.
        ++depositRetryCount;
        if (DEBUG_MODE)
            llOwnerSay("SLParcels Group Pay: retry " + (string)depositRetryCount
                + "/5: status=" + (string)status);
        scheduleDepositRetry();
    }

    timer() {
        integer now = llGetUnixTime();
        if (timerPhase == TIMER_DEPOSIT_RETRY && now >= depositNextRetryAt) {
            fireDepositPost();
            timerPhase = TIMER_SWEEP;
            llSetTimerEvent(10.0);
            return;
        }
        // Sweep: expire pending name-input + pending deposit slots.
        if (pendingNameAvatar != NULL_KEY && pendingNameExpiresAt < now) {
            clearNameInputSlot();
        }
        if (pendingDepositAvatar != NULL_KEY && pendingDepositExpiresAt < now) {
            key staleAvatar = pendingDepositAvatar;
            clearDepositSlot();
            llMessageLinked(LINK_THIS, LM_RELEASE, "", staleAvatar);
        }
        timerPhase = TIMER_SWEEP;
        llSetTimerEvent(10.0);
    }
}
