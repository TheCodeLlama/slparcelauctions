// SLPA SL IM Dispatcher
//
// Polls SLPA backend for pending notification IMs and delivers them via
// llInstantMessage. Single state machine with two cadences:
//   - 60-second poll interval when idle
//   - 2-second per-IM tick when delivering a batch
//
// Configuration is loaded from a notecard named "config" in the same prim.
// See README.md for deployment and operations details.

// === Configuration loaded from notecard ===
string POLL_URL = "";
string CONFIRM_URL_BASE = "";
string SHARED_SECRET = "";
integer DEBUG_MODE = TRUE;

// === Cadences ===
float POLL_INTERVAL = 60.0;
float IM_INTERVAL = 2.0;

// === Batch state during delivery ===
list batchIds = [];
list batchUuids = [];
list batchTexts = [];
integer batchIndex = 0;
integer deliveringBatch = FALSE;

// === HTTP request tracking ===
key pollRequestId = NULL_KEY;

// === Notecard reading state ===
key notecardLineRequest = NULL_KEY;
integer notecardLineNum = 0;

readNotecardLine(integer n) {
    notecardLineNum = n;
    notecardLineRequest = llGetNotecardLine("config", n);
}

parseConfigLine(string line) {
    if (llStringLength(line) == 0) return;
    if (llSubStringIndex(line, "#") == 0) return;  // comment

    integer eq = llSubStringIndex(line, "=");
    if (eq < 1) return;

    string cfgKey = llStringTrim(llGetSubString(line, 0, eq - 1), STRING_TRIM);
    string val = llStringTrim(llGetSubString(line, eq + 1, -1), STRING_TRIM);

    if (cfgKey == "POLL_URL") POLL_URL = val;
    else if (cfgKey == "CONFIRM_URL_BASE") CONFIRM_URL_BASE = val;
    else if (cfgKey == "SHARED_SECRET") SHARED_SECRET = val;
    else if (cfgKey == "DEBUG_MODE") DEBUG_MODE = (val == "true" || val == "TRUE" || val == "1");
}

deliverNextInBatch() {
    if (batchIndex >= llGetListLength(batchIds)) {
        // Batch complete; reset state and return to slow cadence.
        deliveringBatch = FALSE;
        batchIds = [];
        batchUuids = [];
        batchTexts = [];
        llSetTimerEvent(POLL_INTERVAL);
        return;
    }

    string id = llList2String(batchIds, batchIndex);
    string uuid = llList2String(batchUuids, batchIndex);
    string text = llList2String(batchTexts, batchIndex);

    llInstantMessage((key)uuid, text);
    llHTTPRequest(
        CONFIRM_URL_BASE + id + "/delivered",
        [HTTP_METHOD, "POST",
         HTTP_CUSTOM_HEADER, "Authorization", "Bearer " + SHARED_SECRET,
         HTTP_BODY_MAXLENGTH, 4096],
        ""
    );
    batchIndex += 1;
}

integer parseAndStoreBatch(string body) {
    // body is JSON: { "messages": [ {id, avatarUuid, messageText}, ... ] }
    string messagesJson = llJsonGetValue(body, ["messages"]);
    if (messagesJson == JSON_INVALID || messagesJson == "") return 0;

    list keys = llJson2List(messagesJson);
    integer i;
    integer n = llGetListLength(keys);
    for (i = 0; i < n; ++i) {
        string item = llList2String(keys, i);
        string id = llJsonGetValue(item, ["id"]);
        string uuid = llJsonGetValue(item, ["avatarUuid"]);
        string text = llJsonGetValue(item, ["messageText"]);
        if (id != JSON_INVALID && uuid != JSON_INVALID && text != JSON_INVALID) {
            batchIds += [id];
            batchUuids += [uuid];
            batchTexts += [text];
        }
    }
    return llGetListLength(batchIds);
}

default {
    state_entry() {
        POLL_URL = "";
        CONFIRM_URL_BASE = "";
        SHARED_SECRET = "";
        readNotecardLine(0);
    }

    dataserver(key requested, string data) {
        if (requested != notecardLineRequest) return;
        if (data == NAK) {
            llOwnerSay("SL IM dispatcher: notecard 'config' missing or unreadable");
            return;
        }
        if (data == EOF) {
            // Notecard fully read; validate config.
            if (POLL_URL == "" || CONFIRM_URL_BASE == "" || SHARED_SECRET == "") {
                llOwnerSay("SL IM dispatcher: incomplete config — POLL_URL / CONFIRM_URL_BASE / SHARED_SECRET required");
                return;
            }
            if (DEBUG_MODE) llOwnerSay("SL IM dispatcher: ready (poll=" + POLL_URL + ")");
            llSetTimerEvent(POLL_INTERVAL);
            return;
        }
        parseConfigLine(data);
        readNotecardLine(notecardLineNum + 1);
    }

    timer() {
        if (deliveringBatch) {
            deliverNextInBatch();
        } else {
            // Slow-cadence poll cycle.
            pollRequestId = llHTTPRequest(
                POLL_URL + "?limit=10",
                [HTTP_METHOD, "GET",
                 HTTP_CUSTOM_HEADER, "Authorization", "Bearer " + SHARED_SECRET,
                 HTTP_BODY_MAXLENGTH, 16384],
                ""
            );
        }
    }

    http_response(key requestId, integer status, list meta, string body) {
        if (requestId == pollRequestId) {
            pollRequestId = NULL_KEY;
            if (status != 200) {
                if (DEBUG_MODE) llOwnerSay("SL IM poll failed: " + (string)status);
                return;
            }
            integer count = parseAndStoreBatch(body);
            if (count > 0) {
                if (DEBUG_MODE) llOwnerSay("SL IM batch=" + (string)count);
                deliveringBatch = TRUE;
                batchIndex = 0;
                llSetTimerEvent(IM_INTERVAL);
                deliverNextInBatch();  // immediately fire the first IM
            } else {
                if (DEBUG_MODE) llOwnerSay("SL IM poll: 0 messages");
            }
            return;
        }
        // Confirmation responses; log only on non-2xx.
        if (status >= 400) {
            llOwnerSay("SL IM confirm failed: status=" + (string)status);
        }
    }

    on_rez(integer start_param) {
        llResetScript();
    }

    changed(integer change) {
        if (change & CHANGED_INVENTORY) {
            // Notecard may have been re-edited; reload.
            llResetScript();
        }
    }
}
