// lsl-scripts/utils/dump_agent_fields.lsl
//
// Touch-triggered dump of the SL avatar metadata that the SLPA backend
// persists on a verified user (see SlVerificationService.java:73-78 and
// SlVerifyRequest.java). Drop into an in-world object, touch it, and it
// will llOwnerSay:
//   - the six sl_* values in labeled form
//   - a ready-to-paste UPDATE for the `users` table
//
// Intended as a dev shortcut for situations where a user was marked
// `verified = true` directly in the DB but the sl_* columns are still
// NULL because the real /api/v1/sl/verify flow was skipped.
//
// Caveats:
//   - llOwnerSay restricts visibility to the object owner only.
//   - Display names containing single quotes are not SQL-escaped in the
//     UPDATE output; hand-edit them before pasting if your avatar's
//     display name has an apostrophe.
//   - The script does not hit the backend; it just prints. Paste the
//     UPDATE into `docker compose exec -T postgres psql -U slpa -d slpa`.

key    gToucher    = NULL_KEY;
key    gReqBorn    = NULL_KEY;
key    gReqPayinfo = NULL_KEY;

string  gAvatarName  = "";
string  gDisplayName = "";
string  gUsername    = "";
string  gBornDate    = "";
integer gPayInfo     = -1;

reset_collection() {
    gReqBorn     = NULL_KEY;
    gReqPayinfo  = NULL_KEY;
    gAvatarName  = "";
    gDisplayName = "";
    gUsername    = "";
    gBornDate    = "";
    gPayInfo     = -1;
}

// Only emits once both async dataserver responses have arrived.
maybe_report() {
    if (gBornDate == "" || gPayInfo < 0) return;

    llOwnerSay("\n=== SLPA avatar metadata ===");
    llOwnerSay("avatar UUID:  " + (string)gToucher);
    llOwnerSay("avatar name:  " + gAvatarName);
    llOwnerSay("display name: " + gDisplayName);
    llOwnerSay("username:     " + gUsername);
    llOwnerSay("born date:    " + gBornDate);
    llOwnerSay("payinfo:      " + (string)gPayInfo);

    llOwnerSay("\n-- UPDATE (edit the email before pasting) --");
    llOwnerSay(
        "UPDATE users SET "
        + "sl_avatar_uuid='"  + (string)gToucher    + "', "
        + "sl_avatar_name='"  + gAvatarName         + "', "
        + "sl_display_name='" + gDisplayName        + "', "
        + "sl_username='"     + gUsername           + "', "
        + "sl_born_date='"    + gBornDate           + "', "
        + "sl_payinfo="       + (string)gPayInfo
        + " WHERE email='you@example.com';"
    );
}

default {
    state_entry() {
        llSetText("Touch to dump\navatar metadata", <1.0, 1.0, 1.0>, 1.0);
    }

    touch_start(integer n) {
        gToucher = llDetectedKey(0);
        reset_collection();

        gAvatarName  = llDetectedName(0);
        gDisplayName = llGetDisplayName(gToucher);
        gUsername    = llGetUsername(gToucher);

        // born + payinfo arrive asynchronously via dataserver events.
        gReqBorn    = llRequestAgentData(gToucher, DATA_BORN);
        gReqPayinfo = llRequestAgentData(gToucher, DATA_PAYINFO);
    }

    dataserver(key queryid, string data) {
        if (queryid == gReqBorn) {
            gBornDate = data;
        } else if (queryid == gReqPayinfo) {
            gPayInfo = (integer)data;
        }
        maybe_report();
    }
}
