import { describe, expect, it } from "vitest";
import { http, HttpResponse } from "msw";
import { server } from "@/test/msw/server";
import type { GroupLedgerEntry, GroupWallet, GroupWithdrawResponse } from "@/types/realty";
import {
  getGroupWallet,
  getGroupLedger,
  withdrawFromGroupWallet,
} from "./realtyGroupWallet";

// ─── Fixture builders ────────────────────────────────────────────────────────

function fakeWallet(overrides: Partial<GroupWallet> = {}): GroupWallet {
  return {
    balance: 5000,
    reserved: 0,
    available: 5000,
    leaderTermsAcceptedAt: null,
    recentLedger: [],
    ...overrides,
  };
}

function fakeLedgerEntry(
  overrides: Partial<GroupLedgerEntry> = {},
): GroupLedgerEntry {
  return {
    publicId: "00000000-0000-0000-0000-000000000001",
    entryType: "AGENT_FEE_CREDIT",
    amount: 500,
    balanceAfter: 5000,
    reservedAfter: 0,
    createdAt: "2026-05-10T12:00:00Z",
    ...overrides,
  };
}

const GROUP_ID = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";

// ─── getGroupWallet ───────────────────────────────────────────────────────────

describe("getGroupWallet", () => {
  it("returns GroupWallet on 200", async () => {
    const wallet = fakeWallet({ balance: 1500, available: 1500 });
    server.use(
      http.get(`*/api/v1/realty/groups/${GROUP_ID}/wallet`, () =>
        HttpResponse.json(wallet),
      ),
    );

    const result = await getGroupWallet(GROUP_ID);

    expect(result.balance).toBe(1500);
    expect(result.available).toBe(1500);
    expect(result.reserved).toBe(0);
    expect(result.recentLedger).toEqual([]);
  });

  it("populates recentLedger entries", async () => {
    const entry = fakeLedgerEntry({ amount: 300, balanceAfter: 4700 });
    const wallet = fakeWallet({ recentLedger: [entry] });
    server.use(
      http.get(`*/api/v1/realty/groups/${GROUP_ID}/wallet`, () =>
        HttpResponse.json(wallet),
      ),
    );

    const result = await getGroupWallet(GROUP_ID);

    expect(result.recentLedger).toHaveLength(1);
    expect(result.recentLedger[0].entryType).toBe("AGENT_FEE_CREDIT");
    expect(result.recentLedger[0].amount).toBe(300);
  });

  it("throws ApiError on 403", async () => {
    server.use(
      http.get(`*/api/v1/realty/groups/${GROUP_ID}/wallet`, () =>
        HttpResponse.json(
          {
            status: 403,
            code: "INSUFFICIENT_GROUP_PERMISSION",
            title: "Insufficient group permission",
          },
          { status: 403 },
        ),
      ),
    );

    await expect(getGroupWallet(GROUP_ID)).rejects.toMatchObject({ status: 403 });
  });

  it("throws ApiError on 404", async () => {
    server.use(
      http.get(`*/api/v1/realty/groups/${GROUP_ID}/wallet`, () =>
        HttpResponse.json(
          { status: 404, code: "REALTY_GROUP_NOT_FOUND", title: "Not found" },
          { status: 404 },
        ),
      ),
    );

    await expect(getGroupWallet(GROUP_ID)).rejects.toMatchObject({ status: 404 });
  });
});

// ─── getGroupLedger ──────────────────────────────────────────────────────────

describe("getGroupLedger", () => {
  it("returns array of entries on 200 with no cursor", async () => {
    const entries = [
      fakeLedgerEntry({ publicId: "00000000-0000-0000-0000-000000000001" }),
      fakeLedgerEntry({ publicId: "00000000-0000-0000-0000-000000000002" }),
    ];
    server.use(
      http.get(`*/api/v1/realty/groups/${GROUP_ID}/wallet/ledger`, () =>
        HttpResponse.json(entries),
      ),
    );

    const result = await getGroupLedger(GROUP_ID);

    expect(result).toHaveLength(2);
    expect(result[0].publicId).toBe("00000000-0000-0000-0000-000000000001");
  });

  it("forwards cursor and limit as query params", async () => {
    let capturedUrl: URL | null = null;
    server.use(
      http.get(
        `*/api/v1/realty/groups/${GROUP_ID}/wallet/ledger`,
        ({ request }) => {
          capturedUrl = new URL(request.url);
          return HttpResponse.json([]);
        },
      ),
    );

    await getGroupLedger(GROUP_ID, "2026-05-10T12:00:00Z", 25);

    expect(capturedUrl!.searchParams.get("cursor")).toBe("2026-05-10T12:00:00Z");
    expect(capturedUrl!.searchParams.get("limit")).toBe("25");
  });

  it("omits cursor and limit when not provided", async () => {
    let capturedUrl: URL | null = null;
    server.use(
      http.get(
        `*/api/v1/realty/groups/${GROUP_ID}/wallet/ledger`,
        ({ request }) => {
          capturedUrl = new URL(request.url);
          return HttpResponse.json([]);
        },
      ),
    );

    await getGroupLedger(GROUP_ID);

    expect(capturedUrl!.searchParams.has("cursor")).toBe(false);
    expect(capturedUrl!.searchParams.has("limit")).toBe(false);
  });

  it("throws ApiError on 403", async () => {
    server.use(
      http.get(`*/api/v1/realty/groups/${GROUP_ID}/wallet/ledger`, () =>
        HttpResponse.json(
          { status: 403, code: "INSUFFICIENT_GROUP_PERMISSION" },
          { status: 403 },
        ),
      ),
    );

    await expect(getGroupLedger(GROUP_ID)).rejects.toMatchObject({ status: 403 });
  });
});

// ─── withdrawFromGroupWallet ─────────────────────────────────────────────────

describe("withdrawFromGroupWallet", () => {
  it("posts the request body and returns GroupWithdrawResponse on 202", async () => {
    const response: GroupWithdrawResponse = {
      queueId: 42,
      estimatedFulfillmentSeconds: 60,
    };
    let capturedBody: unknown = null;
    server.use(
      http.post(
        `*/api/v1/realty/groups/${GROUP_ID}/wallet/withdraw`,
        async ({ request }) => {
          capturedBody = await request.json();
          return HttpResponse.json(response, { status: 202 });
        },
      ),
    );

    const result = await withdrawFromGroupWallet(GROUP_ID, {
      amount: 1000,
      idempotencyKey: "test-key-abc",
      recipient: "AVATAR",
    });

    expect(result.queueId).toBe(42);
    expect(result.estimatedFulfillmentSeconds).toBe(60);
    expect(capturedBody).toMatchObject({
      amount: 1000,
      idempotencyKey: "test-key-abc",
      recipient: "AVATAR",
    });
  });

  // Sub-project G §7.3 — verify the recipient field round-trips for both
  // values. The API client is a thin wrapper, but this guards against an
  // accidental field rename that would silently fall back to AVATAR.
  it("forwards recipient=SL_GROUP on the request body", async () => {
    let capturedBody: unknown = null;
    server.use(
      http.post(
        `*/api/v1/realty/groups/${GROUP_ID}/wallet/withdraw`,
        async ({ request }) => {
          capturedBody = await request.json();
          return HttpResponse.json(
            { queueId: 7, estimatedFulfillmentSeconds: 45 },
            { status: 202 },
          );
        },
      ),
    );

    await withdrawFromGroupWallet(GROUP_ID, {
      amount: 250,
      idempotencyKey: "sl-key",
      recipient: "SL_GROUP",
    });

    expect(capturedBody).toMatchObject({ recipient: "SL_GROUP" });
  });

  it("throws ApiError with INSUFFICIENT_GROUP_BALANCE code on 422", async () => {
    server.use(
      http.post(
        `*/api/v1/realty/groups/${GROUP_ID}/wallet/withdraw`,
        () =>
          HttpResponse.json(
            {
              status: 422,
              code: "INSUFFICIENT_GROUP_BALANCE",
              title: "Insufficient group balance",
              detail: "The group wallet does not have enough available funds.",
              available: 200,
              requested: 1000,
            },
            { status: 422 },
          ),
      ),
    );

    await expect(
      withdrawFromGroupWallet(GROUP_ID, {
        amount: 1000,
        idempotencyKey: "k",
        recipient: "AVATAR",
      }),
    ).rejects.toMatchObject({
      status: 422,
      problem: {
        code: "INSUFFICIENT_GROUP_BALANCE",
        available: 200,
        requested: 1000,
      },
    });
  });

  it("throws ApiError with LEADER_TERMS_NOT_ACCEPTED on 422", async () => {
    server.use(
      http.post(
        `*/api/v1/realty/groups/${GROUP_ID}/wallet/withdraw`,
        () =>
          HttpResponse.json(
            {
              status: 422,
              code: "LEADER_TERMS_NOT_ACCEPTED",
              title: "Leader has not accepted wallet terms",
            },
            { status: 422 },
          ),
      ),
    );

    await expect(
      withdrawFromGroupWallet(GROUP_ID, {
        amount: 500,
        idempotencyKey: "k2",
        recipient: "AVATAR",
      }),
    ).rejects.toMatchObject({
      status: 422,
      problem: { code: "LEADER_TERMS_NOT_ACCEPTED" },
    });
  });

  it("throws ApiError on 403", async () => {
    server.use(
      http.post(
        `*/api/v1/realty/groups/${GROUP_ID}/wallet/withdraw`,
        () =>
          HttpResponse.json(
            { status: 403, code: "INSUFFICIENT_GROUP_PERMISSION" },
            { status: 403 },
          ),
      ),
    );

    await expect(
      withdrawFromGroupWallet(GROUP_ID, {
        amount: 100,
        idempotencyKey: "k3",
        recipient: "AVATAR",
      }),
    ).rejects.toMatchObject({ status: 403 });
  });

  it("throws ApiError on 410 when group is dissolved", async () => {
    server.use(
      http.post(
        `*/api/v1/realty/groups/${GROUP_ID}/wallet/withdraw`,
        () =>
          HttpResponse.json(
            { status: 410, code: "GROUP_DISSOLVED", title: "Group is dissolved" },
            { status: 410 },
          ),
      ),
    );

    await expect(
      withdrawFromGroupWallet(GROUP_ID, {
        amount: 100,
        idempotencyKey: "k4",
        recipient: "AVATAR",
      }),
    ).rejects.toMatchObject({ status: 410, problem: { code: "GROUP_DISSOLVED" } });
  });
});
