import { describe, it, expect, vi } from "vitest";
import { http, HttpResponse } from "msw";
import { server } from "@/test/msw/server";
import { fakePublicAuction } from "@/test/fixtures/auction";
import EscrowStatusPage, { metadata } from "./page";

// -------------------------------------------------------------
// next/navigation mocks. The RSC calls `notFound()` — a helper that
// throws a special marker in real Next.js to unwind control back to
// the framework. We mimic that by throwing a string the test can
// assert on.  `redirect` is included for symmetry even though the
// escrow shell doesn't use it today; if a future refactor adds a
// redirect branch the test file is already wired up.
// -------------------------------------------------------------
vi.mock("next/navigation", () => ({
  notFound: () => {
    throw new Error("NEXT_NOT_FOUND");
  },
  redirect: (url: string) => {
    throw new Error(`NEXT_REDIRECT:${url}`);
  },
}));

describe("EscrowStatusPage (RSC shell)", () => {
  it("exports metadata with the Escrow title", () => {
    expect(metadata.title).toBe("Escrow");
  });

  it("calls notFound() on an empty publicId", async () => {
    // The mocked notFound throws synchronously before any fetch fires,
    // so no MSW handler is needed for the auctions endpoint.
    const params = Promise.resolve({ publicId: "" });
    await expect(EscrowStatusPage({ params })).rejects.toThrow(
      "NEXT_NOT_FOUND",
    );
  });

  it("calls notFound() when the auction endpoint returns 404", async () => {
    server.use(
      http.get("*/api/v1/auctions/00000000-0000-0000-0000-0000000003e7", () =>
        HttpResponse.json(
          { status: 404, title: "Not Found", detail: "No such auction" },
          { status: 404 },
        ),
      ),
    );
    await expect(
      EscrowStatusPage({ params: Promise.resolve({ publicId: "00000000-0000-0000-0000-0000000003e7" }) }),
    ).rejects.toThrow("NEXT_NOT_FOUND");
  });

  it("renders EscrowPageClient with seeded props for a valid id", async () => {
    const auction = fakePublicAuction({
      publicId: "00000000-0000-0000-0000-000000000007",
      sellerPublicId: "00000000-0000-0000-0000-00000000002a",
    });
    server.use(
      http.get("*/api/v1/auctions/00000000-0000-0000-0000-000000000007", () => HttpResponse.json(auction)),
    );
    const element = await EscrowStatusPage({
      params: Promise.resolve({ publicId: "00000000-0000-0000-0000-000000000007" }),
    });

    // The shell returns a single React element — the EscrowPageClient
    // with auctionPublicId + sellerPublicId seeded from the auction fetch.
    // We assert on props rather than rendering because the client relies
    // on the ws + auth + React Query stack, all of which have their own
    // coverage in EscrowPageClient.test.tsx.
    expect(element).toBeTruthy();
    const props = (
      element as unknown as {
        props: { auctionPublicId: string; sellerPublicId: string };
      }
    ).props;
    expect(props.auctionPublicId).toBe("00000000-0000-0000-0000-000000000007");
    expect(props.sellerPublicId).toBe("00000000-0000-0000-0000-00000000002a");
  });

  it("re-throws non-404 auction fetch errors so Next renders its error boundary", async () => {
    // A 500 must bubble past the RSC — only 404 is converted to notFound.
    server.use(
      http.get("*/api/v1/auctions/00000000-0000-0000-0000-000000000007", () =>
        HttpResponse.json(
          { status: 500, title: "Internal Server Error" },
          { status: 500 },
        ),
      ),
    );
    // The 500 surfaces as an ApiError thrown from `api.get`, which is
    // explicitly NOT "NEXT_NOT_FOUND" — catching the raw rejection lets
    // us assert both that the shell failed AND that it didn't quietly
    // convert the error into a notFound.
    let caught: unknown;
    try {
      await EscrowStatusPage({ params: Promise.resolve({ publicId: "00000000-0000-0000-0000-000000000007" }) });
    } catch (e) {
      caught = e;
    }
    expect(caught).toBeDefined();
    expect((caught as Error).message).not.toBe("NEXT_NOT_FOUND");
  });
});
