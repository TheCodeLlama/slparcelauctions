// SLParcels Featured Board
//
// Single-purpose: one of N board prims at SLParcels HQ. Renders the
// per-board MOAP page at BASE_URL/in-world/board/BOARD_INDEX and, on
// touch, hits the /touch endpoint to learn which listing is currently
// on-screen, then llDialogs the toucher with [Teleport] [View listing].
//
// Outbound HTTP only. No shared secret -- the touch endpoint is anonymous.

// === Configuration loaded from notecard ===
string BASE_URL = "";
integer BOARD_INDEX = 0;
integer DEBUG_MODE = TRUE;

// === Runtime state ===
key   touchRequestId = NULL_KEY;
key   currentToucher = NULL_KEY;
integer dialogChannel = 0;
integer dialogListenHandle = -1;

string currentListingUrl = "";
string currentSlurl = "";

// === Notecard reader ===
string NOTECARD_NAME = "config";
integer notecardLineNum = 0;
key notecardLineRequest = NULL_KEY;
integer notecardDone = FALSE;

debugSay(string s) {
    if (DEBUG_MODE) llOwnerSay("[featured-board] " + s);
}

resetMediaUrl() {
    string url = BASE_URL + "/in-world/board/" + (string)BOARD_INDEX;
    list params = [
        PRIM_MEDIA_AUTO_PLAY,      TRUE,
        PRIM_MEDIA_AUTO_LOOP,      TRUE,
        PRIM_MEDIA_AUTO_SCALE,     TRUE,
        PRIM_MEDIA_AUTO_ZOOM,      FALSE,
        PRIM_MEDIA_FIRST_CLICK_INTERACT, FALSE,
        PRIM_MEDIA_WIDTH_PIXELS,   1024,
        PRIM_MEDIA_HEIGHT_PIXELS,  1024,
        PRIM_MEDIA_HOME_URL,       url,
        PRIM_MEDIA_CURRENT_URL,    url,
        PRIM_MEDIA_PERMS_INTERACT, PRIM_MEDIA_PERM_ANYONE,
        PRIM_MEDIA_PERMS_CONTROL,  PRIM_MEDIA_PERM_OWNER,
        PRIM_MEDIA_WHITELIST_ENABLE, TRUE,
        PRIM_MEDIA_WHITELIST,      "slparcels.com,*.slparcels.com"
    ];
    integer rc = llSetLinkMedia(LINK_THIS, 0, params);
    debugSay("media set: face=0 url=" + url + " rc=" + (string)rc);
}

requestTouchPayload(key toucher) {
    currentToucher = toucher;
    string url = BASE_URL + "/api/v1/in-world/featured-board/"
        + (string)BOARD_INDEX + "/touch";
    touchRequestId = llHTTPRequest(url, [
        HTTP_METHOD, "GET",
        HTTP_MIMETYPE, "application/json"
    ], "");
}

handleTouchResponse(string body) {
    currentListingUrl = "";
    currentSlurl = "";
    if (body == "" || body == "null") {
        llRegionSayTo(currentToucher, 0,
            "No featured listing on this board right now. Visit slparcels.com to browse.");
        return;
    }
    string title = llJsonGetValue(body, ["title"]);
    string listingUrl = llJsonGetValue(body, ["listingUrl"]);
    string slurl = llJsonGetValue(body, ["slurl"]);
    if (listingUrl != JSON_INVALID) currentListingUrl = BASE_URL + listingUrl;
    if (slurl != JSON_INVALID && slurl != "null") currentSlurl = slurl;

    dialogChannel = -1 - (integer)llFrand(1000000);
    if (dialogListenHandle != -1) llListenRemove(dialogListenHandle);
    dialogListenHandle = llListen(dialogChannel, "", currentToucher, "");
    llSetTimerEvent(60.0);
    list buttons = ["Cancel"];
    if (currentListingUrl != "") buttons = ["View listing"] + buttons;
    if (currentSlurl != "")      buttons = ["Teleport"]     + buttons;
    llDialog(currentToucher,
        title + "\n\nWhat would you like to do?", buttons, dialogChannel);
}

handleDialogChoice(string choice) {
    if (choice == "Teleport" && currentSlurl != "") {
        // SLURLs look like secondlife://Region/x/y/z -- extract for llMapDestination.
        // The /touch payload already gives us a fully formed SLURL; we can
        // also use llLoadURL with the SLURL which is the simplest approach.
        llLoadURL(currentToucher,
            "Teleport to the featured parcel:", currentSlurl);
    } else if (choice == "View listing" && currentListingUrl != "") {
        llLoadURL(currentToucher,
            "Open this listing on slparcels.com?", currentListingUrl);
    }
    if (dialogListenHandle != -1) {
        llListenRemove(dialogListenHandle);
        dialogListenHandle = -1;
    }
    llSetTimerEvent(0.0);
}

// === Notecard parsing ===
readNotecardLine(integer n) {
    notecardLineNum = n;
    notecardLineRequest = llGetNotecardLine(NOTECARD_NAME, n);
}

applyNotecardLine(string line) {
    line = llStringTrim(line, STRING_TRIM);
    if (line == "" || llGetSubString(line, 0, 0) == "#") return;
    integer eq = llSubStringIndex(line, "=");
    if (eq < 1) return;
    string key = llStringTrim(llGetSubString(line, 0, eq - 1), STRING_TRIM);
    string val = llStringTrim(llGetSubString(line, eq + 1, -1), STRING_TRIM);
    if (key == "BASE_URL")    BASE_URL = val;
    else if (key == "BOARD_INDEX") BOARD_INDEX = (integer)val;
    else if (key == "DEBUG_MODE")  DEBUG_MODE = (val == "true" || val == "TRUE" || val == "1");
}

default {
    state_entry() {
        readNotecardLine(0);
    }

    dataserver(key id, string data) {
        if (id != notecardLineRequest) return;
        if (data == EOF) {
            notecardDone = TRUE;
            if (BASE_URL == "" || BOARD_INDEX < 1) {
                llOwnerSay("[featured-board] config invalid: BASE_URL='"
                    + BASE_URL + "' BOARD_INDEX=" + (string)BOARD_INDEX);
                return;
            }
            debugSay("ready: BASE_URL=" + BASE_URL
                + " BOARD_INDEX=" + (string)BOARD_INDEX);
            resetMediaUrl();
            return;
        }
        applyNotecardLine(data);
        readNotecardLine(notecardLineNum + 1);
    }

    touch_start(integer n) {
        if (!notecardDone) {
            llRegionSayTo(llDetectedKey(0), 0,
                "Board still booting; touch again in a moment.");
            return;
        }
        requestTouchPayload(llDetectedKey(0));
    }

    http_response(key id, integer status, list meta, string body) {
        if (id == touchRequestId) {
            if (status != 200) {
                debugSay("touch endpoint status=" + (string)status);
                llRegionSayTo(currentToucher, 0,
                    "Couldn't load this board's listing. Try again shortly.");
                return;
            }
            handleTouchResponse(body);
        }
    }

    listen(integer chan, string name, key id, string msg) {
        if (chan != dialogChannel || id != currentToucher) return;
        handleDialogChoice(msg);
    }

    timer() {
        // Dialog timed out without a choice -- clean up.
        if (dialogListenHandle != -1) {
            llListenRemove(dialogListenHandle);
            dialogListenHandle = -1;
        }
        llSetTimerEvent(0.0);
    }

    changed(integer change) {
        if (change & CHANGED_INVENTORY) llResetScript();
    }
}
