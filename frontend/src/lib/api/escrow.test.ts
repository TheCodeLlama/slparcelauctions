import { describe, it, expect } from "vitest";
import { server } from "@/test/msw/server";
import { http, HttpResponse } from "msw";
import { fileDispute, getEscrowStatus } from "./escrow";
import { fakeEscrow } from "@/test/fixtures/escrow";

describe("escrow API client", () => {
  describe("getEscrowStatus", () => {
    it("returns EscrowStatusResponse on 200", async () => {
      server.use(
        http.get("*/api/v1/auctions/7/escrow", () =>
          HttpResponse.json(fakeEscrow({ auctionId: 7, escrowId: 1 })),
        ),
      );
      const result = await getEscrowStatus(7);
      expect(result.escrowId).toBe(1);
      expect(result.auctionId).toBe(7);
      expect(result.state).toBe("ESCROW_PENDING");
    });

    it("throws on 404", async () => {
      server.use(
        http.get("*/api/v1/auctions/99/escrow", () =>
          HttpResponse.json(
            { status: 404, code: "ESCROW_NOT_FOUND", detail: "no escrow" },
            { status: 404 },
          ),
        ),
      );
      await expect(getEscrowStatus(99)).rejects.toMatchObject({ status: 404 });
    });

    it("throws on 403", async () => {
      server.use(
        http.get("*/api/v1/auctions/7/escrow", () =>
          HttpResponse.json(
            { status: 403, code: "ESCROW_FORBIDDEN", detail: "not a party" },
            { status: 403 },
          ),
        ),
      );
      await expect(getEscrowStatus(7)).rejects.toMatchObject({ status: 403 });
    });
  });

  describe("fileDispute", () => {
    it("posts the body and returns the new EscrowStatusResponse", async () => {
      server.use(
        http.post("*/api/v1/auctions/7/escrow/dispute", async ({ request }) => {
          const body = (await request.json()) as {
            reasonCategory: string;
            description: string;
          };
          expect(body.reasonCategory).toBe("SELLER_NOT_RESPONSIVE");
          expect(body.description).toBe("Not responding to messages");
          return HttpResponse.json(
            fakeEscrow({
              auctionId: 7,
              state: "DISPUTED",
              disputedAt: new Date().toISOString(),
              disputeReasonCategory: "SELLER_NOT_RESPONSIVE",
              disputeDescription: "Not responding to messages",
            }),
          );
        }),
      );

      const result = await fileDispute(7, {
        reasonCategory: "SELLER_NOT_RESPONSIVE",
        description: "Not responding to messages",
      });

      expect(result.state).toBe("DISPUTED");
      expect(result.disputeReasonCategory).toBe("SELLER_NOT_RESPONSIVE");
    });

    it("throws on 409 ESCROW_INVALID_TRANSITION", async () => {
      server.use(
        http.post("*/api/v1/auctions/7/escrow/dispute", () =>
          HttpResponse.json(
            {
              status: 409,
              code: "ESCROW_INVALID_TRANSITION",
              detail: "too late",
            },
            { status: 409 },
          ),
        ),
      );
      await expect(
        fileDispute(7, { reasonCategory: "OTHER", description: "1234567890" }),
      ).rejects.toMatchObject({
        status: 409,
        problem: { code: "ESCROW_INVALID_TRANSITION" },
      });
    });
  });
});
