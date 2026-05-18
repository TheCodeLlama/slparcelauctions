import { describe, it, expect, beforeEach, afterEach, vi } from "vitest";
import {
  termsBannerDismissalKey,
  isTermsBannerDismissed,
  dismissTermsBanner,
} from "./terms-banner-dismissed";

describe("terms-banner-dismissed", () => {
  beforeEach(() => {
    window.localStorage.clear();
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("namespaces the key by terms version", () => {
    expect(termsBannerDismissalKey("1.0")).toBe(
      "slpa.walletTermsBannerDismissed.v1.0",
    );
    expect(termsBannerDismissalKey("2.3")).toBe(
      "slpa.walletTermsBannerDismissed.v2.3",
    );
  });

  it("defaults to not-dismissed", () => {
    expect(isTermsBannerDismissed("1.0")).toBe(false);
  });

  it("round-trips a dismissal for a given version", () => {
    dismissTermsBanner("1.0");
    expect(isTermsBannerDismissed("1.0")).toBe(true);
  });

  it("dismissal is scoped to the version that set it", () => {
    dismissTermsBanner("1.0");
    expect(isTermsBannerDismissed("2.0")).toBe(false);
  });

  it("is SSR-safe: no window means not-dismissed and no throw", () => {
    vi.stubGlobal("window", undefined);
    expect(isTermsBannerDismissed("1.0")).toBe(false);
    expect(() => dismissTermsBanner("1.0")).not.toThrow();
  });
});
