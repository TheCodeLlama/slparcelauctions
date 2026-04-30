// SLPA Verification Terminal
//
// Touch-driven account-linking kiosk. Players touch this terminal, type their
// 6-digit SLPA verification code, and the script POSTs avatar metadata to the
// backend to link the SL account to a website account.
//
// Configuration is loaded from a notecard named "config" in the same prim.
// See README.md for deployment and operations details.
//
// Grid guard note:
//   llGetEnv("sim_channel") == "Second Life Server"   (in-world env value)
//   X-SecondLife-Shard: "Production"                  (HTTP header value, checked backend-side)
// These are DIFFERENT strings. Both are checked: this script at startup,
// SlHeaderValidator on every inbound request.

// === Configuration loaded from notecard ===
string  VERIFY_URL    = "";
integer DEBUG_MODE = TRUE;

// === Notecard reading state ===
key     notecardLineRequest = NULL_KEY;
integer notecardLineNum     = 0;

// === Lock state ===
key     lockHolder     = NULL_KEY;
string  lockHolderName = "";
integer lockExpiresAt  = 0;

// === Avatar data buffers ===
string  storedCode        = "";
key     storedAvatarUuid  = NULL_KEY;
string  storedAvatarName  = "";
string  storedDisplayName = "";
string  storedUsername    = "";
string  storedBornDate    = "";
integer storedPayInfo     = 0;
integer bornArrived       = FALSE;
integer payArrived        = FALSE;

// === Async request tracking ===
key     bornReqKey = NULL_KEY;
key     payReqKey  = NULL_KEY;
key     httpReqId  = NULL_KEY;

// === Listen state ===
integer listenHandle = -1;
integer menuChan     = 0;   // set to random negative in state_entry

// === Timer phase ===
// Distinguishes data-fetch timeout from lock-TTL timeout.
integer TIMER_DATA = 1;
integer TIMER_LOCK = 2;
integer timerPhase = 0;

// ---------------------------------------------------------------------------
// Helper functions
// ---------------------------------------------------------------------------

readNotecardLine(integer n) {
    notecardLineNum = n;
    notecardLineRequest = llGetNotecardLine("config", n);
}

parseConfigLine(string line) {
    if (llStringLength(line) == 0) return;
    if (llSubStringIndex(line, "#") == 0) return;  // comment line

    integer eq = llSubStringIndex(line, "=");
    if (eq < 1) return;

    string cfgKey = llStringTrim(llGetSubString(line, 0, eq - 1), STRING_TRIM);
    string val = llStringTrim(llGetSubString(line, eq + 1, -1), STRING_TRIM);

    if (cfgKey == "VERIFY_URL")     VERIFY_URL = val;
    else if (cfgKey == "DEBUG_MODE")
        DEBUG_MODE = (val == "true" || val == "TRUE" || val == "1");
}

setBusyChrome() {
    llSetText("SLPA Verification Terminal\n<In Use>", <1.0, 0.2, 0.2>, 1.0);
    llSetObjectName("SLPA Verification Terminal <In Use>");
}

setIdleChrome() {
    llSetText("SLPA Verification Terminal\nTouch to link your account", <1.0, 1.0, 1.0>, 1.0);
    llSetObjectName("SLPA Verification Terminal");
}

releaseLock() {
    if (listenHandle != -1) {
        llListenRemove(listenHandle);
        listenHandle = -1;
    }
    lockHolder     = NULL_KEY;
    lockHolderName = "";
    lockExpiresAt  = 0;
    bornArrived    = FALSE;
    payArrived     = FALSE;
    bornReqKey     = NULL_KEY;
    payReqKey      = NULL_KEY;
    timerPhase     = 0;
    llSetTimerEvent(0);
    setIdleChrome();
}

integer isSixDigitCode(string s) {
    if (llStringLength(s) != 6) return FALSE;
    integer i;
    for (i = 0; i < 6; ++i) {
        if (llSubStringIndex("0123456789", llGetSubString(s, i, i)) < 0)
            return FALSE;
    }
    return TRUE;
}

string escapeJson(string s) {
    s = llReplaceSubString(s, "\\", "\\\\", 0);
    s = llReplaceSubString(s, "\"", "\\\"", 0);
    return s;
}

postVerifyRequest() {
    string body = "{"
        + "\"verificationCode\":\"" + storedCode + "\","
        + "\"avatarUuid\":\"" + (string)storedAvatarUuid + "\","
        + "\"avatarName\":\"" + escapeJson(storedAvatarName) + "\","
        + "\"displayName\":\"" + escapeJson(storedDisplayName) + "\","
        + "\"username\":\"" + escapeJson(storedUsername) + "\","
        + "\"bornDate\":\"" + storedBornDate + "\","
        + "\"payInfo\":" + (string)storedPayInfo
        + "}";

    httpReqId = llHTTPRequest(VERIFY_URL,
        [HTTP_METHOD, "POST",
         HTTP_MIMETYPE, "application/json",
         HTTP_BODY_MAXLENGTH, 16384],
        body);
}

// ---------------------------------------------------------------------------
// Default state
// ---------------------------------------------------------------------------

default {
    state_entry() {
        // Initialize all globals to sentinel values.
        VERIFY_URL       = "";
        DEBUG_MODE  = TRUE;
        lockHolder       = NULL_KEY;
        lockHolderName   = "";
        lockExpiresAt    = 0;
        storedCode       = "";
        storedAvatarUuid = NULL_KEY;
        storedAvatarName = "";
        storedDisplayName = "";
        storedUsername   = "";
        storedBornDate   = "";
        storedPayInfo    = 0;
        bornArrived      = FALSE;
        payArrived       = FALSE;
        bornReqKey       = NULL_KEY;
        payReqKey        = NULL_KEY;
        httpReqId        = NULL_KEY;
        listenHandle     = -1;
        timerPhase       = 0;
        menuChan         = (integer)(llFrand(-2000000000.0) - 1000000.0);

        // Mainland guard — must fire before anything else.
        // llGetEnv("sim_channel") == "Second Life Server" on main grid.
        // This is distinct from X-SecondLife-Shard ("Production") checked backend-side.
        if (llGetEnv("sim_channel") != "Second Life Server") {
            llOwnerSay("SLPA Verification Terminal: wrong grid — this script is mainland-only.");
            return;  // halt; do not read notecard, do not set chrome
        }

        // No L$ accepted — hide pay dialog entirely.
        llSetPayPrice(PAY_HIDE, [PAY_HIDE, PAY_HIDE, PAY_HIDE, PAY_HIDE]);

        setIdleChrome();
        readNotecardLine(0);
    }

    dataserver(key requested, string data) {
        // --- Notecard read ---
        if (requested == notecardLineRequest) {
            if (data == NAK) {
                llOwnerSay("SLPA Verification Terminal: notecard 'config' missing or unreadable");
                return;
            }
            if (data == EOF) {
                if (VERIFY_URL == "") {
                    llOwnerSay("SLPA Verification Terminal: incomplete config — VERIFY_URL required");
                    return;
                }
                if (DEBUG_MODE)
                    llOwnerSay("SLPA Verification Terminal: ready (verify=" + VERIFY_URL + ")");
                return;
            }
            parseConfigLine(data);
            readNotecardLine(notecardLineNum + 1);
            return;
        }

        // --- Avatar data reads ---
        if (requested == bornReqKey) {
            storedBornDate = data;
            bornArrived    = TRUE;
        }
        if (requested == payReqKey) {
            storedPayInfo = (integer)data;
            payArrived    = TRUE;
        }
        if (bornArrived && payArrived) {
            // Both avatar-data fields have arrived; cancel data timeout and POST.
            timerPhase = 0;
            llSetTimerEvent(0);
            postVerifyRequest();
        }
    }

    touch_start(integer num_detected) {
        key    toucher     = llDetectedKey(0);
        string toucherName = llDetectedName(0);

        // If lock is held and not yet expired, bounce the toucher.
        if (lockHolder != NULL_KEY && lockExpiresAt > llGetUnixTime()) {
            llRegionSayTo(toucher, 0,
                "Terminal busy — currently verifying " + lockHolderName + ".");
            return;
        }

        // Acquire lock.
        lockHolder     = toucher;
        lockHolderName = toucherName;
        lockExpiresAt  = llGetUnixTime() + 60;

        // Stash all avatar data we can get synchronously.
        storedAvatarUuid  = toucher;
        storedAvatarName  = toucherName;
        storedDisplayName = llGetDisplayName(toucher);
        storedUsername    = llGetUsername(toucher);

        setBusyChrome();

        if (DEBUG_MODE)
            llOwnerSay("SLPA Verification Terminal: touch from " + toucherName);

        listenHandle = llListen(menuChan, "", lockHolder, "");
        llTextBox(lockHolder, "Enter your 6-digit SLPA code:", menuChan);

        // Start lock-TTL timer (60s). Replaced by 30s data timer after code entry.
        timerPhase = TIMER_LOCK;
        llSetTimerEvent(60.0);
    }

    listen(integer channel, string name, key id, string message) {
        // Only handle our channel and our lock holder.
        if (channel != menuChan || id != lockHolder) return;

        // Remove the listen immediately — one shot.
        llListenRemove(listenHandle);
        listenHandle = -1;

        if (!isSixDigitCode(message)) {
            llRegionSayTo(lockHolder, 0, "✗ Code must be 6 digits.");
            releaseLock();
            return;
        }

        storedCode = message;
        bornArrived = FALSE;
        payArrived  = FALSE;

        // Request avatar data asynchronously; results come in dataserver().
        bornReqKey = llRequestAgentData(lockHolder, DATA_BORN);
        payReqKey  = llRequestAgentData(lockHolder, DATA_PAYINFO);

        // Switch to 30s data-fetch timeout.
        timerPhase = TIMER_DATA;
        llSetTimerEvent(30.0);
    }

    http_response(key req, integer status, list meta, string body) {
        if (req != httpReqId) return;
        httpReqId = NULL_KEY;

        if (status == 200) {
            string verified    = llJsonGetValue(body, ["verified"]);
            string userId      = llJsonGetValue(body, ["userId"]);
            string slAvatarName = llJsonGetValue(body, ["slAvatarName"]);

            if (verified == "true") {
                llRegionSayTo(lockHolder, 0,
                    "✓ Linked SLPA #" + userId + " to " + slAvatarName + ".");
                if (DEBUG_MODE)
                    llOwnerSay("SLPA Verification Terminal: verify ok: userId=" + userId);
            } else {
                llRegionSayTo(lockHolder, 0,
                    "✗ Verification failed. Code may be expired — generate a new one on slparcels.com.");
                if (DEBUG_MODE)
                    llOwnerSay("SLPA Verification Terminal: verify denied: not verified");
            }
        } else if (status >= 400 && status < 500) {
            string title  = llJsonGetValue(body, ["title"]);
            string detail = llJsonGetValue(body, ["detail"]);
            if (title == JSON_INVALID || title == "") title = "Error";
            if (detail == JSON_INVALID || detail == "") detail = "status " + (string)status;
            llRegionSayTo(lockHolder, 0, "✗ " + title + ": " + detail);
            if (DEBUG_MODE)
                llOwnerSay("SLPA Verification Terminal: verify denied: " + title);
        } else {
            // 5xx or 0 (HTTP timeout / network failure)
            llRegionSayTo(lockHolder, 0,
                "✗ Backend unreachable. Try again in a moment.");
            if (DEBUG_MODE)
                llOwnerSay("SLPA Verification Terminal: http error: status=" + (string)status);
        }

        releaseLock();
    }

    timer() {
        if (timerPhase == TIMER_DATA) {
            // Avatar data (DATA_BORN / DATA_PAYINFO) didn't arrive in time.
            llRegionSayTo(lockHolder, 0,
                "✗ Couldn't read your avatar data — please try again.");
            releaseLock();
        } else {
            // TIMER_LOCK — 60s passed with no progress (user walked away, etc.).
            releaseLock();
        }
        llSetTimerEvent(0);
        timerPhase = 0;
    }

    changed(integer change) {
        if (change & CHANGED_INVENTORY) {
            llResetScript();
        }
    }

    on_rez(integer start_param) {
        llResetScript();
    }
}
