// SLPA Parcel Verifier Giver
//
// Single-purpose touch-to-receive prim. Hands out a copy of the SLPA
// Parcel Verifier inventory item to anyone who touches it. Free, no L$.
//
// Header-trust only — no shared secret, no L$, no backend HTTP. The only
// runtime concern is per-avatar rate-limiting (60s) so a griefer can't
// spam the giver and exhaust the 16-pending-give-inventory cap.
//
// Replaces the "Get Parcel Verifier" menu option that used to live on the
// SLPA Terminal. Splitting into a dedicated prim removes the two-place
// inventory-update rule (previously you had to drag-drop the new verifier
// into every deployed payment terminal whenever parcel-verifier.lsl was
// updated). Now the verifier-giver instances are the only place that
// ships the verifier.
//
// Configuration is loaded from a notecard named "config" in the same prim.
// See README.md for deployment and operations details.

string  VERIFIER_NAME    = "SLPA Parcel Verifier";
integer DEBUG_MODE       = TRUE;
integer RATE_LIMIT_SECONDS = 60;

// Notecard reading state
key     notecardLineRequest = NULL_KEY;
integer notecardLineNum     = 0;

// Per-avatar rate-limit list: [avatarKey, lastGivenAt, ...]
list givenSessions = [];

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

    if      (k == "VERIFIER_NAME")         VERIFIER_NAME = v;
    else if (k == "RATE_LIMIT_SECONDS")    RATE_LIMIT_SECONDS = (integer)v;
    else if (k == "DEBUG_MODE")
        DEBUG_MODE = (v == "true" || v == "TRUE" || v == "1");
}

setIdleChrome() {
    llSetText("SLPA Parcel Verifier \xe2\x80\x94 Free\nTouch to receive",
        <0.6, 0.9, 0.6>, 1.0);
    llSetObjectName("SLPA Parcel Verifier Giver");
}

default {
    state_entry() {
        givenSessions = [];

        // Mainland-only grid guard
        if (llGetEnv("sim_channel") != "Second Life Server") {
            llOwnerSay("CRITICAL: not on Second Life Server grid; halting.");
            return;
        }

        setIdleChrome();
        readNotecardLine(0);
    }

    on_rez(integer n) {
        llResetScript();
    }

    changed(integer change) {
        if (change & CHANGED_INVENTORY) {
            llResetScript();
        }
    }

    dataserver(key requested, string data) {
        if (requested == notecardLineRequest) {
            if (data == EOF) {
                if (DEBUG_MODE)
                    llOwnerSay("SLPA Verifier Giver: config loaded (rate_limit="
                        + (string)RATE_LIMIT_SECONDS + "s).");
                return;
            }
            parseConfigLine(data);
            readNotecardLine(notecardLineNum + 1);
        }
    }

    touch_start(integer num) {
        key toucher = llDetectedKey(0);
        string toucherName = llDetectedName(0);

        // Per-avatar rate-limit
        integer slot = llListFindList(givenSessions, [toucher]);
        integer now = llGetUnixTime();
        if (slot >= 0) {
            integer lastAt = llList2Integer(givenSessions, slot + 1);
            if (now - lastAt < RATE_LIMIT_SECONDS) {
                llRegionSayTo(toucher, 0,
                    "Just gave you one — wait a minute before requesting another.");
                return;
            }
            givenSessions = llListReplaceList(givenSessions,
                [toucher, now], slot, slot + 1);
        } else {
            givenSessions += [toucher, now];
        }

        // Verify the named inventory item exists
        if (llGetInventoryType(VERIFIER_NAME) == INVENTORY_NONE) {
            llRegionSayTo(toucher, 0,
                "Sorry — the verifier object is missing. Please contact SLPA support.");
            llOwnerSay("CRITICAL: '" + VERIFIER_NAME + "' missing from giver prim inventory.");
            return;
        }

        llGiveInventory(toucher, VERIFIER_NAME);
        if (DEBUG_MODE)
            llOwnerSay("SLPA Verifier Giver: gave verifier to " + toucherName);
    }
}
