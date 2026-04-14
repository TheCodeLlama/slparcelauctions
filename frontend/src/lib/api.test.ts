import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { api, ApiError, isApiError } from "./api";

describe("api", () => {
  beforeEach(() => {
    vi.stubGlobal("fetch", vi.fn());
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("returns parsed JSON on a 2xx response", async () => {
    vi.mocked(fetch).mockResolvedValueOnce(
      new Response(JSON.stringify({ id: 1, name: "alice" }), {
        status: 200,
        headers: { "Content-Type": "application/json" },
      })
    );

    const result = await api.get<{ id: number; name: string }>("/api/v1/users/1");
    expect(result).toEqual({ id: 1, name: "alice" });
  });

  it("throws ApiError with the parsed ProblemDetail on 4xx", async () => {
    const problem = {
      status: 400,
      title: "Validation Failed",
      detail: "email must be valid",
      errors: { email: "must be a well-formed email address" },
    };
    vi.mocked(fetch).mockResolvedValueOnce(
      new Response(JSON.stringify(problem), {
        status: 400,
        headers: { "Content-Type": "application/json" },
      })
    );

    await expect(api.post("/api/v1/users", { email: "bad" })).rejects.toMatchObject({
      status: 400,
      problem: {
        status: 400,
        errors: { email: "must be a well-formed email address" },
      },
    });
  });

  it("synthesizes a ProblemDetail when the error body is non-JSON", async () => {
    vi.mocked(fetch).mockResolvedValueOnce(
      new Response("<html>502 Bad Gateway</html>", {
        status: 502,
        statusText: "Bad Gateway",
      })
    );

    let caught: unknown;
    try {
      await api.get("/api/v1/health");
    } catch (e) {
      caught = e;
    }
    expect(isApiError(caught)).toBe(true);
    expect((caught as ApiError).status).toBe(502);
    expect((caught as ApiError).problem.title).toBe("Bad Gateway");
  });

  it("returns undefined on a 204 No Content response", async () => {
    vi.mocked(fetch).mockResolvedValueOnce(new Response(null, { status: 204 }));
    const result = await api.delete<void>("/api/v1/users/1");
    expect(result).toBeUndefined();
  });

  it("URLSearchParams-encodes the params field, stripping undefined values", async () => {
    vi.mocked(fetch).mockResolvedValueOnce(
      new Response(JSON.stringify([]), {
        status: 200,
        headers: { "Content-Type": "application/json" },
      })
    );

    await api.get("/api/v1/auctions", {
      params: { status: "active", page: 2, ended: undefined, includeDrafts: false },
    });

    expect(fetch).toHaveBeenCalledWith(
      expect.stringContaining("/api/v1/auctions?status=active&page=2&includeDrafts=false"),
      expect.any(Object)
    );
    const calledUrl = vi.mocked(fetch).mock.calls[0][0] as string;
    expect(calledUrl).not.toContain("ended=");
  });
});
