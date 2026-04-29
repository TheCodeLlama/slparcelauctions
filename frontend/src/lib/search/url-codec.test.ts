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
});
