import { describe, it, expect } from "vitest";
import { notificationKeys } from "./queryKeys";

describe("notificationKeys", () => {
  it("all key is stable", () => {
    expect(notificationKeys.all).toEqual(["notifications"]);
  });

  it("list key includes filters", () => {
    const k = notificationKeys.list({ group: "bidding", unreadOnly: true });
    expect(k[0]).toBe("notifications");
    expect(k[1]).toBe("list");
    expect(k[2]).toMatchObject({ group: "bidding", unreadOnly: true });
  });

  it("list key with no args uses empty object", () => {
    const k = notificationKeys.list();
    expect(k[2]).toEqual({});
  });

  it("unreadCount key is nested under all", () => {
    const k = notificationKeys.unreadCount();
    expect(k).toEqual(["notifications", "unreadCount"]);
  });

  it("unreadCountBreakdown key is nested under unreadCount", () => {
    const k = notificationKeys.unreadCountBreakdown();
    expect(k).toEqual(["notifications", "unreadCount", "breakdown"]);
  });
});
