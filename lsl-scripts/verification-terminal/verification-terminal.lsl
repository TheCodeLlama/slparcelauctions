// SLParcels Verification Terminal
//
// Touch-driven verification kiosk for two flows:
//
//   1. Self verify   — players touch this terminal, type their 6-digit
//                      SLParcels code, and the script POSTs avatar metadata
//                      to /sl/verify to link their SL account to a website
//                      account (Method C).
//   2. SL Group verify — realty-group founders type the SLPA-XXXX verification
//                      code their group leader handed them, and the script
//                      POSTs to /sl/sl-group/verify with the toucher's avatar
//                      UUID for the backend to cross-check against the SL
//                      group's founder (sub-project E spec section 7.3).
//
// If SL_GROUP_VERIFY_URL is empty in the notecard, the terminal behaves
// exactly as before — touch goes straight to the self-verify text box. If
// SL_GROUP_VERIFY_URL is set, touch opens a llDialog menu so the toucher
// picks which flow they want.
//
// Trust model: SL-injected X-SecondLife-Shard + X-SecondLife-Owner-Key.
// No shared secret in the notecard — both backend endpoints accept SL-header
// trust at /api/v1/sl/**, and the verification codes are single-use server-side.
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
string  VERIFY_URL          = "";
// Sub-project E spec section 7.3, section 13.3. Optional; when blank, the
// SL-group verify menu item is hidden and touch goes straight to self-verify.
string  SL_GROUP_VERIFY_URL = "";
integer DEBUG_MODE          = TRUE;

// === Flow mode (set when the user picks from the top-level menu) ===
integer MODE_NONE  = 0;
integer MODE_SELF  = 1;
integer MODE_GROUP = 2;
integer mode       = 0;

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

    if (cfgKey == "VERIFY_URL")            VERIFY_URL          = val;
    else if (cfgKey == "SL_GROUP_VERIFY_URL") SL_GROUP_VERIFY_URL = val;
    else if (cfgKey == "DEBUG_MODE")
        DEBUG_MODE = (val == "true" || val == "TRUE" || val == "1");
}

setBusyChrome() {
    llSetText("SLParcels Verification Terminal\n<In Use>", <1.0, 0.2, 0.2>, 1.0);
    llSetObjectName("SLParcels Verification Terminal <In Use>");
}

setIdleChrome() {
    llSetText("SLParcels Verification Terminal\nTouch to link your account", <1.0, 1.0, 1.0>, 1.0);
    llSetObjectName("SLParcels Verification Terminal");
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
    mode           = MODE_NONE;
    storedCode     = "";
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

// Sub-project E spec section 7.3: founder-of-an-SL-group verification.
// Body shape matches the @RequestBody DTO on SlGroupVerifyController exactly.
// No shared secret in the body -- the controller doesn't read it; trust comes
// from the SL-injected X-SecondLife-Owner-Key + X-SecondLife-Shard headers
// (checked at /api/v1/sl/** via SlHeaderValidator).
postSlGroupVerifyRequest() {
    string body = "{"
        + "\"verificationCode\":\"" + escapeJson(storedCode) + "\","
        + "\"founderAvatarUuid\":\"" + (string)storedAvatarUuid + "\""
        + "}";

    httpReqId = llHTTPRequest(SL_GROUP_VERIFY_URL,
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
        VERIFY_URL          = "";
        SL_GROUP_VERIFY_URL = "";
        DEBUG_MODE          = TRUE;
        mode                = MODE_NONE;
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
            llOwnerSay("SLParcels Verification Terminal: wrong grid. This script is mainland-only.");
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
                llOwnerSay("SLParcels Verification Terminal: notecard 'config' missing or unreadable");
                return;
            }
            if (data == EOF) {
                if (VERIFY_URL == "") {
                    llOwnerSay("SLParcels Verification Terminal: incomplete config. VERIFY_URL required.");
                    return;
                }
                if (DEBUG_MODE) {
                    string slGroupReady;
                    if (SL_GROUP_VERIFY_URL == "") slGroupReady = " sl-group=<disabled>";
                    else slGroupReady = " sl-group=" + SL_GROUP_VERIFY_URL;
                    llOwnerSay("SLParcels Verification Terminal: ready (verify=" + VERIFY_URL
                        + slGroupReady + ")");
                }
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
                "Terminal already in use, please use another terminal or try again later.");
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
            llOwnerSay("SLParcels Verification Terminal: touch from " + toucherName);

        listenHandle = llListen(menuChan, "", lockHolder, "");

        // If SL Group Verify is configured, the toucher picks which flow to
        // run. Otherwise default to self-verify exactly like pre-port behavior.
        if (SL_GROUP_VERIFY_URL != "") {
            mode = MODE_NONE;
            llDialog(lockHolder,
                "What would you like to verify?\n\n"
                + "Self: link your SL avatar to your SLParcels account.\n"
                + "SL Group: complete realty-group founder verification.",
                ["Self", "SL Group"], menuChan);
        } else {
            mode = MODE_SELF;
            llTextBox(lockHolder, "Enter your 6-digit SLParcels code:", menuChan);
        }

        // Start lock-TTL timer (60s). Replaced by 30s data timer after code entry.
        timerPhase = TIMER_LOCK;
        llSetTimerEvent(60.0);
    }

    listen(integer channel, string name, key id, string message) {
        // Only handle our channel and our lock holder.
        if (channel != menuChan || id != lockHolder) return;

        // Top-level menu choice (only reachable when SL_GROUP_VERIFY_URL is set).
        if (mode == MODE_NONE) {
            if (message == "Self") {
                mode = MODE_SELF;
                // Re-listen for the code entry on the same channel.
                listenHandle = llListen(menuChan, "", lockHolder, "");
                llTextBox(lockHolder, "Enter your 6-digit SLParcels code:", menuChan);
                return;
            }
            if (message == "SL Group") {
                mode = MODE_GROUP;
                listenHandle = llListen(menuChan, "", lockHolder, "");
                llTextBox(lockHolder,
                    "Enter the SL Group Verify code from your SL Parcels web UI "
                    + "(format: SLPA-XXXXXXXXXXXX):",
                    menuChan);
                return;
            }
            // Unrecognized menu reply -- release lock so the toucher can retry.
            releaseLock();
            return;
        }

        // Remove the listen immediately — one shot.
        llListenRemove(listenHandle);
        listenHandle = -1;

        if (mode == MODE_GROUP) {
            string trimmed = llStringTrim(message, STRING_TRIM);
            if (trimmed == "") {
                llDialog(lockHolder, "Verification code cannot be empty.",
                    ["OK"], menuChan);
                releaseLock();
                return;
            }
            storedCode = trimmed;
            // No avatar-data fetch needed for SL group verify -- the body
            // is just { verificationCode, founderAvatarUuid } and we already
            // have storedAvatarUuid from touch_start.
            postSlGroupVerifyRequest();
            // Switch to 30s HTTP-response timeout (re-using TIMER_DATA phase).
            timerPhase = TIMER_DATA;
            llSetTimerEvent(30.0);
            return;
        }

        // MODE_SELF: original 6-digit-code account-linking flow.
        if (!isSixDigitCode(message)) {
            llDialog(lockHolder, "Your verification code must be 6 digits. Please try again.", ["OK"], menuChan);
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

        if (mode == MODE_GROUP) {
            // Sub-project E spec section 7.3 response handling. Per
            // terminal-output-genericisation policy: user-facing results go
            // via llDialog (private to the toucher), backend ProblemDetail
            // title/detail stay in DEBUG_MODE owner-say only.
            if (status == 200) {
                llDialog(lockHolder,
                    "SL group verified. The realty group can now list "
                    + "parcels owned by this SL group.",
                    ["OK"], menuChan);
                if (DEBUG_MODE)
                    llOwnerSay("SLParcels Verification Terminal: SL Group Verify OK for "
                        + (string)storedAvatarUuid);
            } else {
                string gcode = llJsonGetValue(body, ["code"]);
                string failMsg;
                if (status == 410) {
                    failMsg = "Verification code expired. The realty group "
                        + "leader needs to start a new registration.";
                } else if (status == 422 && gcode == "SL_GROUP_FOUNDER_MISMATCH") {
                    failMsg = "You are not the founder of the SL group "
                        + "registered with this code.";
                } else if (status == 404) {
                    failMsg = "Verification code not recognised. Check the code "
                        + "and try again, or ask the realty group leader to "
                        + "restart the SL group registration.";
                } else {
                    failMsg = "There was a problem with SL Group Verify. "
                        + "Check the code and try again.";
                }
                llDialog(lockHolder, failMsg, ["OK"], menuChan);
                if (DEBUG_MODE) {
                    string gtitle  = llJsonGetValue(body, ["title"]);
                    string gdetail = llJsonGetValue(body, ["detail"]);
                    llOwnerSay("SLParcels Verification Terminal: SL Group Verify failed "
                        + "for " + (string)storedAvatarUuid
                        + " status=" + (string)status
                        + " code=" + gcode
                        + " title=" + gtitle
                        + " detail=" + gdetail);
                }
            }
            releaseLock();
            return;
        }

        // MODE_SELF: original account-linking response handling.
        if (status == 200) {
            string verified    = llJsonGetValue(body, ["verified"]);
            string userId      = llJsonGetValue(body, ["userId"]);
            string slAvatarName = llJsonGetValue(body, ["slAvatarName"]);

            // llJsonGetValue returns the LSL JSON_TRUE constant ("~true")
            // for a JSON boolean true, NOT the literal string "true". Comparing
            // against the literal silently fails into the "verification failed"
            // branch even on a successful verify response. Always compare
            // boolean JSON values against JSON_TRUE / JSON_FALSE.
            if (verified == JSON_TRUE) {
                llDialog(lockHolder,
                    "Your avatar has been linked to your SLParcels account",
                    ["OK"], menuChan);
                if (DEBUG_MODE)
                    llOwnerSay("SLParcels Verification Terminal: verify ok: userId=" + userId);
            } else {
                llDialog(lockHolder,
                    "Verification failed. Your code may be expired. Generate a new one at slparcels.com",
                    ["OK"], menuChan);
                if (DEBUG_MODE)
                    llOwnerSay("SLParcels Verification Terminal: verify denied: not verified");
            }
        } else if (status >= 400 && status < 500) {
            string title = llJsonGetValue(body, ["title"]);
            if (title == JSON_INVALID || title == "") title = "Error";
            llDialog(lockHolder,
                "There was a problem with SLParcels. Please try again.",
                ["OK"], menuChan);
            if (DEBUG_MODE)
                llOwnerSay("SLParcels Verification Terminal: verify denied: " + title);
        } else {
            // 5xx or 0 (HTTP timeout / network failure)
            llDialog(lockHolder,
                "There was a problem with SLParcels. Please try again. (500/0)",
                ["OK"], menuChan);
            if (DEBUG_MODE)
                llOwnerSay("SLParcels Verification Terminal: http error: status=" + (string)status);
        }

        releaseLock();
    }

    timer() {
        if (timerPhase == TIMER_DATA) {
            // MODE_SELF: avatar data (DATA_BORN / DATA_PAYINFO) didn't arrive.
            // MODE_GROUP: HTTP response from /sl/sl-group/verify didn't arrive.
            // Both surface the same generic "try again" so we don't leak
            // implementation details to the toucher.
            if (mode == MODE_GROUP) {
                llDialog(lockHolder,
                    "SL Group Verify took too long to respond. Please try again.",
                    ["OK"], menuChan);
            } else {
                llDialog(lockHolder,
                    "We couldn't read your avatar data. Please try again.",
                    ["OK"], menuChan);
            }
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
