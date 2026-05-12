import { describe, expect, it, vi, beforeEach } from "vitest";
import { http, HttpResponse } from "msw";
import { renderWithProviders, screen, userEvent, waitFor } from "@/test/render";
import { server } from "@/test/msw/server";
import type { AdminRealtyGroupReportRow } from "@/types/realty";

const mockReplace = vi.fn();

vi.mock("next/navigation", () => ({
  useRouter: () => ({
    push: vi.fn(),
    replace: mockReplace,
    refresh: vi.fn(),
    back: vi.fn(),
    forward: vi.fn(),
    prefetch: vi.fn(),
  }),
  usePathname: () => "/admin/realty-groups/reports",
  useSearchParams: () => new URLSearchParams(),
}));

import { AdminGroupReportsQueuePage } from "./AdminGroupReportsQueuePage";

function pageOf(rows: AdminRealtyGroupReportRow[]) {
  return {
    content: rows,
    totalElements: rows.length,
    totalPages: rows.length === 0 ? 0 : 1,
    number: 0,
    size: 25,
  };
}

function makeRow(
  overrides: Partial<AdminRealtyGroupReportRow> = {},
): AdminRealtyGroupReportRow {
  return {
    publicId: "11111111-1111-1111-1111-111111111111",
    groupPublicId: "22222222-2222-2222-2222-222222222222",
    groupName: "Sunset Estates",
    reporter: {
      publicId: "33333333-3333-3333-3333-333333333333",
      displayName: "Reporter One",
    },
    reason: "FRAUDULENT_LISTINGS",
    status: "OPEN",
    createdAt: "2026-05-12T12:00:00Z",
    ...overrides,
  };
}

describe("AdminGroupReportsQueuePage", () => {
  beforeEach(() => {
    mockReplace.mockReset();
  });

  it("renders the heading and empty state when no rows match", async () => {
    server.use(
      http.get("*/api/v1/admin/realty-groups/reports", () =>
        HttpResponse.json(pageOf([])),
      ),
    );
    renderWithProviders(<AdminGroupReportsQueuePage />);
    expect(
      screen.getByRole("heading", { name: "Group Reports" }),
    ).toBeInTheDocument();
    await waitFor(() =>
      expect(
        screen.getByTestId("admin-group-reports-empty"),
      ).toBeInTheDocument(),
    );
  });

  it("renders rows returned by the queue endpoint", async () => {
    server.use(
      http.get("*/api/v1/admin/realty-groups/reports", () =>
        HttpResponse.json(
          pageOf([
            makeRow({
              publicId: "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
              groupName: "Alpha Group",
            }),
            makeRow({
              publicId: "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb",
              groupName: "Beta Group",
            }),
          ]),
        ),
      ),
    );
    renderWithProviders(<AdminGroupReportsQueuePage />);
    await waitFor(() =>
      expect(screen.getByText("Alpha Group")).toBeInTheDocument(),
    );
    expect(screen.getByText("Beta Group")).toBeInTheDocument();
    expect(
      screen.getByTestId(
        "admin-group-report-row-aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
      ),
    ).toBeInTheDocument();
  });

  it("requests the OPEN status by default", async () => {
    let lastQuery = "";
    server.use(
      http.get("*/api/v1/admin/realty-groups/reports", ({ request }) => {
        lastQuery = new URL(request.url).search;
        return HttpResponse.json(pageOf([]));
      }),
    );
    renderWithProviders(<AdminGroupReportsQueuePage />);
    await waitFor(() => expect(lastQuery).toContain("status=OPEN"));
  });

  it("writes the chosen status filter to the URL", async () => {
    server.use(
      http.get("*/api/v1/admin/realty-groups/reports", () =>
        HttpResponse.json(pageOf([])),
      ),
    );
    renderWithProviders(<AdminGroupReportsQueuePage />);
    await waitFor(() =>
      expect(
        screen.getByTestId("group-reports-status-resolved"),
      ).toBeInTheDocument(),
    );
    await userEvent.click(
      screen.getByTestId("group-reports-status-resolved"),
    );
    expect(mockReplace).toHaveBeenCalledWith(
      expect.stringContaining("status=resolved"),
      expect.anything(),
    );
  });

  it("omits the status param when filter is the OPEN default", async () => {
    server.use(
      http.get("*/api/v1/admin/realty-groups/reports", () =>
        HttpResponse.json(pageOf([])),
      ),
    );
    renderWithProviders(<AdminGroupReportsQueuePage />);
    await waitFor(() =>
      expect(
        screen.getByTestId("group-reports-status-all"),
      ).toBeInTheDocument(),
    );
    // Click "all" (non-default), then "open" — final URL should drop the
    // status param entirely.
    await userEvent.click(screen.getByTestId("group-reports-status-all"));
    mockReplace.mockReset();
    await userEvent.click(screen.getByTestId("group-reports-status-open"));
    expect(mockReplace).toHaveBeenCalled();
    const lastCall = mockReplace.mock.calls.at(-1);
    expect(lastCall?.[0]).not.toContain("status=");
  });
});
