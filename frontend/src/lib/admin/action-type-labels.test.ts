import { describe, expect, it } from "vitest";

import { ACTION_TYPE_LABELS, labelFor } from "./action-type-labels";

describe("ACTION_TYPE_LABELS", () => {
  it("provides a human label for every documented action type", () => {
    expect(ACTION_TYPE_LABELS.DISMISS_REPORT).toBe("Dismiss report");
    expect(ACTION_TYPE_LABELS.REALTY_GROUP_WALLET_ADMIN_ADJUSTMENT)
      .toBe("Realty group wallet adjustment");
    expect(ACTION_TYPE_LABELS.REALTY_GROUP_SL_GROUP_FORCE_UNREGISTER)
      .toBe("Force-unregister SL group");
  });
});

describe("labelFor", () => {
  it("returns the configured label for known values", () => {
    expect(labelFor("CREATE_BAN")).toBe("Create ban");
  });
  it("falls back to title-cased enum name for unknown values", () => {
    // simulates a future backend enum addition the frontend hasn't caught up to
    expect(labelFor("SOMETHING_NEW" as never)).toBe("Something New");
  });
});
