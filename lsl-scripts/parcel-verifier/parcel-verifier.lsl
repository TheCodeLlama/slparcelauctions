// SLPA Parcel Verifier
//
// Single-use rezzable object. Seller rezzes it on the parcel they want to
// list, the script reads parcel metadata, auto-prompts for a 6-digit PARCEL
// code, POSTs to backend, then llDie()s.
//
// Configuration is loaded from a notecard named "config" in the same prim.
// See README.md for deployment and operations details.
//
// Grid guard note:
//   llGetEnv("sim_channel") == "Second Life Server"   (in-world env value)
//   X-SecondLife-Shard: "Production"                  (HTTP header value, checked backend-side)
// These are DIFFERENT strings. Both are checked: this script checks sim_channel
// after notecard EOF, SlHeaderValidator checks X-SecondLife-Shard on every request.

// === Configuration loaded from notecard ===
string  PARCEL_VERIFY_URL = "";
integer DEBUG_OWNER_SAY   = TRUE;

// === Notecard reading state ===
key     notecardLineRequest = NULL_KEY;
integer notecardLineNum     = 0;

// === Parcel data (read after notecard EOF) ===
key     parcelUuid   = NULL_KEY;
key     ownerUuid    = NULL_KEY;
key     groupUuid    = NULL_KEY;
string  parcelName   = "";
string  description  = "";
integer areaSqm      = 0;
integer primCapacity = 0;
vector  pos          = ZERO_VECTOR;
key     rezzer       = NULL_KEY;
string  verificationCode = "";

// === Listen / HTTP state ===
integer listenHandle = -1;
key     httpReqId    = NULL_KEY;
integer codeChan     = 0;   // set to random negative in state_entry

// === Phase enum ===
integer PHASE_INIT         = 0;
integer PHASE_AWAITING_CODE = 1;
integer PHASE_HTTP_INFLIGHT = 2;
integer phase              = 0;

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

    string key = llStringTrim(llGetSubString(line, 0, eq - 1), STRING_TRIM);
    string val = llStringTrim(llGetSubString(line, eq + 1, -1), STRING_TRIM);

    if (key == "PARCEL_VERIFY_URL") PARCEL_VERIFY_URL = val;
    else if (key == "DEBUG_OWNER_SAY")
        DEBUG_OWNER_SAY = (val == "true" || val == "TRUE" || val == "1");
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

dieWithMessage(string msg) {
    llOwnerSay(msg);
    llDie();
}

// ---------------------------------------------------------------------------
// Default state
// ---------------------------------------------------------------------------

default {
    state_entry() {
        // Initialize all globals to sentinel values.
        PARCEL_VERIFY_URL = "";
        DEBUG_OWNER_SAY   = TRUE;
        parcelUuid        = NULL_KEY;
        ownerUuid         = NULL_KEY;
        groupUuid         = NULL_KEY;
        parcelName        = "";
        description       = "";
        areaSqm           = 0;
        primCapacity      = 0;
        pos               = ZERO_VECTOR;
        rezzer            = NULL_KEY;
        verificationCode  = "";
        listenHandle      = -1;
        httpReqId         = NULL_KEY;
        phase             = PHASE_INIT;

        // Random negative channel for llTextBox to reduce collision risk.
        codeChan = (integer)(llFrand(-2000000000.0) - 1000000.0);

        // Begin reading the config notecard.
        readNotecardLine(0);
    }

    dataserver(key requested, string data) {
        if (requested != notecardLineRequest) return;

        if (data == NAK) {
            llOwnerSay("✗ Parcel Verifier: notecard 'config' missing or unreadable");
            llDie();
            return;
        }

        if (data == EOF) {
            // Validate required config.
            if (PARCEL_VERIFY_URL == "") {
                dieWithMessage("✗ Parcel Verifier: incomplete config — PARCEL_VERIFY_URL required");
                return;
            }

            // Mainland guard — fires here, after notecard is read.
            // llGetEnv("sim_channel") == "Second Life Server" on main grid.
            // This is distinct from X-SecondLife-Shard ("Production") checked backend-side.
            if (llGetEnv("sim_channel") != "Second Life Server") {
                dieWithMessage("✗ Wrong grid.");
                return;
            }

            // Read parcel data at current position.
            list parcelData = llGetParcelDetails(llGetPos(), [
                PARCEL_DETAILS_ID, PARCEL_DETAILS_OWNER, PARCEL_DETAILS_GROUP,
                PARCEL_DETAILS_NAME, PARCEL_DETAILS_DESC, PARCEL_DETAILS_AREA,
                PARCEL_DETAILS_PRIM_CAPACITY]);
            parcelUuid   = (key)llList2String(parcelData, 0);
            ownerUuid    = (key)llList2String(parcelData, 1);
            groupUuid    = (key)llList2String(parcelData, 2);
            parcelName   = llList2String(parcelData, 3);
            description  = llList2String(parcelData, 4);
            areaSqm      = (integer)llList2String(parcelData, 5);
            primCapacity = (integer)llList2String(parcelData, 6);
            pos          = llGetPos();
            rezzer       = llGetOwner();

            // Client-side owner short-circuit (saves backend round-trip on
            // common mistakes). If the parcel has a group (groupUuid != NULL_KEY)
            // then it may be group-owned — skip the check and let backend decide.
            if (ownerUuid != rezzer && groupUuid == NULL_KEY) {
                dieWithMessage("✗ This parcel isn't yours. Please rez on land you own.");
                return;
            }

            // Auto-prompt: open listen + show llTextBox immediately.
            listenHandle = llListen(codeChan, "", rezzer, "");
            llTextBox(rezzer, "Enter your 6-digit PARCEL code:", codeChan);
            llSetTimerEvent(90.0);  // 90s code-entry timeout
            phase = PHASE_AWAITING_CODE;
            return;
        }

        // Normal notecard line — parse and advance.
        parseConfigLine(data);
        readNotecardLine(notecardLineNum + 1);
    }

    listen(integer channel, string name, key id, string message) {
        if (channel != codeChan || id != rezzer) return;

        // Remove listen immediately — one-shot.
        llListenRemove(listenHandle);
        listenHandle = -1;

        if (!isSixDigitCode(message)) {
            dieWithMessage("✗ Code must be 6 digits.");
            return;
        }

        verificationCode = message;

        // Build JSON body with numeric fields unquoted (backend Jackson is strict).
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

        httpReqId = llHTTPRequest(PARCEL_VERIFY_URL,
            [HTTP_METHOD, "POST",
             HTTP_MIMETYPE, "application/json",
             HTTP_BODY_MAXLENGTH, 16384],
            body);

        phase = PHASE_HTTP_INFLIGHT;
        llSetTimerEvent(30.0);  // 30s HTTP timeout
    }

    http_response(key req, integer status, list meta, string body) {
        if (req != httpReqId) return;
        httpReqId = NULL_KEY;
        llSetTimerEvent(0);

        if (status == 204) {
            dieWithMessage("✓ Parcel verified — your listing is live on slparcelauctions.com.");
        } else if (status >= 400 && status < 500) {
            string title  = llJsonGetValue(body, ["title"]);
            string detail = llJsonGetValue(body, ["detail"]);
            if (title == JSON_INVALID || title == "") title = "Error";
            if (detail == JSON_INVALID || detail == "") detail = "status " + (string)status;
            dieWithMessage("✗ " + title + ": " + detail);
        } else {
            // 5xx or 0 (HTTP timeout / network failure)
            dieWithMessage("✗ Backend unreachable. Please rez again in a moment.");
        }
    }

    timer() {
        llSetTimerEvent(0);
        if (phase == PHASE_AWAITING_CODE) {
            // Code-entry timed out — clean up listen before dying.
            if (listenHandle != -1) {
                llListenRemove(listenHandle);
                listenHandle = -1;
            }
            dieWithMessage("✗ Timed out waiting for code.");
        } else {
            // PHASE_HTTP_INFLIGHT — HTTP timed out; listen already removed.
            dieWithMessage("✗ Timed out reaching SLPA.");
        }
    }

    changed(integer change) {
        if (change & CHANGED_INVENTORY) {
            llResetScript();
        }
    }
}
