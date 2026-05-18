import { describe, it, expect } from "vitest";
import {
  queryFromSearchParams,
  searchParamsFromQuery,
  defaultAuctionSearchQuery,
} from "./url-codec";
import type { AuctionSearchQuery } from "@/types/search";

describe("url codec", () => {
  it("returns defaults when searchParams are empty", () => {
    expect(queryFromSearchParams(new URLSearchParams())).toEqual(
      defaultAuctionSearchQuery,
    );
  });

  it("decodes scalar filters", () => {
    const q = queryFromSearchParams(
      new URLSearchParams("region=Tula&min_price=1000&max_price=50000"),
    );
    expect(q.region).toBe("Tula");
    expect(q.minPrice).toBe(1000);
    expect(q.maxPrice).toBe(50000);
  });

  it("decodes CSV multi-selects", () => {
    const q = queryFromSearchParams(
      new URLSearchParams(
        "maturity=GENERAL,MODERATE&tags=BEACHFRONT,ROADSIDE",
      ),
    );
    expect(q.maturity).toEqual(["GENERAL", "MODERATE"]);
    expect(q.tags).toEqual(["BEACHFRONT", "ROADSIDE"]);
  });

  it("round-trips a realistic query without lossiness", () => {
    const q: AuctionSearchQuery = {
      region: "Tula",
      maturity: ["MODERATE", "ADULT"],
      minPrice: 500,
      maxPrice: 10000,
      tags: ["BEACHFRONT"],
      tagsMode: "and",
      reserveStatus: "reserve_met",
      sort: "ending_soonest",
      page: 2,
      size: 24,
    };
    const restored = queryFromSearchParams(
      new URLSearchParams(searchParamsFromQuery(q).toString()),
    );
    expect(restored).toEqual(q);
  });

  it("drops defaults from the encoded URL", () => {
    const sp = searchParamsFromQuery({ sort: "newest", page: 0, size: 24 });
    expect(sp.toString()).toBe("");
  });

  it("ignores unknown params silently", () => {
    const q = queryFromSearchParams(
      new URLSearchParams("foo=bar&region=Tula"),
    );
    expect(q.region).toBe("Tula");
  });

  it("rejects invalid enum values without polluting the query", () => {
    const q = queryFromSearchParams(
      new URLSearchParams("sort=bogus&reserve_status=mystery"),
    );
    expect(q.sort).toBe("newest");
    expect(q.reserveStatus).toBeUndefined();
  });

  it("round-trips verification_tier CSV", () => {
    const q: AuctionSearchQuery = {
      ...defaultAuctionSearchQuery,
      verificationTier: ["BOT", "OWNERSHIP_TRANSFER"],
    };
    const restored = queryFromSearchParams(
      new URLSearchParams(searchParamsFromQuery(q).toString()),
    );
    expect(restored.verificationTier).toEqual(["BOT", "OWNERSHIP_TRANSFER"]);
  });

  it("drops non-default statusFilter round-trip", () => {
    const sp = searchParamsFromQuery({
      ...defaultAuctionSearchQuery,
      statusFilter: "ended_only",
    });
    expect(sp.get("status_filter")).toBe("ended_only");
    const q = queryFromSearchParams(sp);
    expect(q.statusFilter).toBe("ended_only");
  });

  describe("distance round-trip", () => {
    it("round-trips distance=0 (a meaningful anchor-only value, not 'no filter')", () => {
      const sp = searchParamsFromQuery({
        ...defaultAuctionSearchQuery,
        nearRegion: "Tula",
        distance: 0,
      });
      // The classic falsy-skip bug would drop distance when it is 0.
      expect(sp.get("distance")).toBe("0");
      const q = queryFromSearchParams(sp);
      expect(q.distance).toBe(0);
      expect(q.nearRegion).toBe("Tula");
    });

    it("round-trips a positive distance", () => {
      const sp = searchParamsFromQuery({
        ...defaultAuctionSearchQuery,
        nearRegion: "Tula",
        distance: 7,
      });
      expect(sp.get("distance")).toBe("7");
      expect(queryFromSearchParams(sp).distance).toBe(7);
    });

    it("absent distance stays absent (encode omits it, decode leaves it undefined)", () => {
      const sp = searchParamsFromQuery({
        ...defaultAuctionSearchQuery,
        nearRegion: "Tula",
      });
      expect(sp.get("distance")).toBeNull();
      expect(queryFromSearchParams(sp).distance).toBeUndefined();
    });
  });

  describe("q field round-trip", () => {
    it("decodes ?q=foo", () => {
      const sp = new URLSearchParams("q=foo");
      expect(queryFromSearchParams(sp).q).toBe("foo");
    });
    it("encodes q=foo", () => {
      const sp = searchParamsFromQuery({
        ...defaultAuctionSearchQuery,
        q: "foo",
      });
      expect(sp.get("q")).toBe("foo");
    });
    it("drops q on encode when blank", () => {
      const sp = searchParamsFromQuery({
        ...defaultAuctionSearchQuery,
        q: "",
      });
      expect(sp.get("q")).toBeNull();
    });
    it("trims whitespace on decode", () => {
      const sp = new URLSearchParams("q=%20%20foo%20%20");
      expect(queryFromSearchParams(sp).q).toBe("foo");
    });
  });
});
