import { describe, expect, it, vi, beforeEach } from "vitest";
import { http, HttpResponse } from "msw";
import { renderWithProviders, screen, userEvent, waitFor } from "@/test/render";
import { server } from "@/test/msw/server";
import { realtyGroupReportHandlers } from "@/test/msw/handlers";

vi.mock("next/navigation", () => ({
  useRouter: () => ({
    push: vi.fn(),
    replace: vi.fn(),
    refresh: vi.fn(),
    back: vi.fn(),
    forward: vi.fn(),
    prefetch: vi.fn(),
  }),
  usePathname: () => "/admin/groups/reports/abc",
  useSearchParams: () => new URLSearchParams(),
}));

import { AdminGroupReportDetailPage } from "./AdminGroupReportDetailPage";

const REPORT_ID = "cccccccc-cccc-cccc-cccc-cccccccccccc";

describe("AdminGroupReportDetailPage", () => {
  beforeEach(() => {
    server.use(realtyGroupReportHandlers.adminDetailSuccess());
  });

  it("renders report details with resolve + dismiss buttons on OPEN reports", async () => {
    renderWithProviders(
      <AdminGroupReportDetailPage reportPublicId={REPORT_ID} />,
    );

    await waitFor(() =>
      expect(screen.getByTestId("report-detail-body")).toBeInTheDocument(),
    );

    expect(screen.getByText("Sunset Estates")).toBeInTheDocument();
    expect(screen.getByText("Reporter User")).toBeInTheDocument();
    expect(screen.getByTestId("report-detail-status")).toHaveTextContent(
      "OPEN",
    );
    expect(
      screen.getByTestId("report-detail-resolve-btn"),
    ).toBeInTheDocument();
    expect(
      screen.getByTestId("report-detail-dismiss-btn"),
    ).toBeInTheDocument();
  });

  it("hides action buttons + shows resolution notes on non-OPEN reports", async () => {
    server.use(
      realtyGroupReportHandlers.adminDetailSuccess({
        status: "RESOLVED",
        resolvedAt: "2026-05-12T13:00:00Z",
        resolvedByAdmin: {
          publicId: "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb",
          displayName: "Admin User",
        },
        resolutionNotes: "Confirmed; group suspended.",
      }),
    );

    renderWithProviders(
      <AdminGroupReportDetailPage reportPublicId={REPORT_ID} />,
    );

    await waitFor(() =>
      expect(
        screen.getByTestId("report-detail-resolution-notes"),
      ).toBeInTheDocument(),
    );
    expect(screen.getByTestId("report-detail-status")).toHaveTextContent(
      "RESOLVED",
    );
    expect(
      screen.queryByTestId("report-detail-resolve-btn"),
    ).not.toBeInTheDocument();
    expect(
      screen.queryByTestId("report-detail-dismiss-btn"),
    ).not.toBeInTheDocument();
    expect(screen.getByText("Confirmed; group suspended.")).toBeInTheDocument();
  });

  it("renders error state when detail fetch fails", async () => {
    server.use(
      http.get("*/api/v1/admin/realty-groups/reports/:publicId", () =>
        HttpResponse.json({ status: 500 }, { status: 500 }),
      ),
    );

    renderWithProviders(
      <AdminGroupReportDetailPage reportPublicId={REPORT_ID} />,
    );

    await waitFor(() =>
      expect(screen.getByTestId("report-detail-error")).toBeInTheDocument(),
    );
  });

  it("resolve happy path posts notes and closes modal", async () => {
    let resolvePayload: Record<string, unknown> | null = null;
    server.use(
      http.post(
        "*/api/v1/admin/realty-groups/reports/:publicId/resolve",
        async ({ request }) => {
          resolvePayload = (await request.json()) as Record<string, unknown>;
          return HttpResponse.json({
            publicId: REPORT_ID,
            group: {
              publicId: "dddddddd-dddd-dddd-dddd-dddddddddddd",
              name: "Sunset Estates",
            },
            reporter: {
              publicId: "eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee",
              displayName: "Reporter User",
            },
            reason: "FRAUDULENT_LISTINGS",
            details: "Test details body.",
            status: "RESOLVED",
            resolvedByAdmin: {
              publicId: "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb",
              displayName: "Admin User",
            },
            resolvedAt: "2026-05-12T13:00:00Z",
            resolutionNotes: "Confirmed.",
            createdAt: "2026-05-12T12:00:00Z",
          });
        },
      ),
    );

    renderWithProviders(
      <AdminGroupReportDetailPage reportPublicId={REPORT_ID} />,
    );

    await waitFor(() =>
      expect(
        screen.getByTestId("report-detail-resolve-btn"),
      ).toBeInTheDocument(),
    );

    await userEvent.click(screen.getByTestId("report-detail-resolve-btn"));
    await userEvent.type(
      screen.getByTestId("resolve-modal-notes"),
      "Confirmed.",
    );
    await userEvent.click(screen.getByTestId("resolve-modal-submit"));

    await waitFor(() => expect(resolvePayload).not.toBeNull());
    expect(resolvePayload).toEqual({
      notes: "Confirmed.",
      escalateTo: null,
    });
    await waitFor(() =>
      expect(
        screen.queryByTestId("resolve-modal-submit"),
      ).not.toBeInTheDocument(),
    );
  });

  it("resolve with escalateTo chains into the escalation modal", async () => {
    let resolvePayload: Record<string, unknown> | null = null;
    server.use(
      http.post(
        "*/api/v1/admin/realty-groups/reports/:publicId/resolve",
        async ({ request }) => {
          resolvePayload = (await request.json()) as Record<string, unknown>;
          return HttpResponse.json({
            publicId: REPORT_ID,
            group: {
              publicId: "dddddddd-dddd-dddd-dddd-dddddddddddd",
              name: "Sunset Estates",
            },
            reporter: {
              publicId: "eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee",
              displayName: "Reporter User",
            },
            reason: "FRAUDULENT_LISTINGS",
            details: "Test details body.",
            status: "RESOLVED",
            resolvedByAdmin: {
              publicId: "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb",
              displayName: "Admin User",
            },
            resolvedAt: "2026-05-12T13:00:00Z",
            resolutionNotes: "Suspending now.",
            createdAt: "2026-05-12T12:00:00Z",
          });
        },
      ),
    );

    renderWithProviders(
      <AdminGroupReportDetailPage reportPublicId={REPORT_ID} />,
    );

    await waitFor(() =>
      expect(
        screen.getByTestId("report-detail-resolve-btn"),
      ).toBeInTheDocument(),
    );

    await userEvent.click(screen.getByTestId("report-detail-resolve-btn"));
    await userEvent.type(
      screen.getByTestId("resolve-modal-notes"),
      "Suspending now.",
    );
    await userEvent.selectOptions(
      screen.getByTestId("resolve-modal-escalate"),
      "SUSPEND_GROUP",
    );
    await userEvent.click(screen.getByTestId("resolve-modal-submit"));

    await waitFor(() =>
      expect(
        screen.getByTestId("escalate-modal-body"),
      ).toBeInTheDocument(),
    );
    expect(resolvePayload).toEqual({
      notes: "Suspending now.",
      escalateTo: "SUSPEND_GROUP",
    });
    expect(screen.getByTestId("escalate-modal-body")).toHaveTextContent(
      /Sunset Estates/,
    );
  });

  it("dismiss happy path posts notes and closes modal", async () => {
    let dismissPayload: Record<string, unknown> | null = null;
    server.use(
      http.post(
        "*/api/v1/admin/realty-groups/reports/:publicId/dismiss",
        async ({ request }) => {
          dismissPayload = (await request.json()) as Record<string, unknown>;
          return HttpResponse.json({
            publicId: REPORT_ID,
            group: {
              publicId: "dddddddd-dddd-dddd-dddd-dddddddddddd",
              name: "Sunset Estates",
            },
            reporter: {
              publicId: "eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee",
              displayName: "Reporter User",
            },
            reason: "FRAUDULENT_LISTINGS",
            details: "Test details body.",
            status: "DISMISSED",
            resolvedByAdmin: {
              publicId: "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb",
              displayName: "Admin User",
            },
            resolvedAt: "2026-05-12T13:00:00Z",
            resolutionNotes: "Not actionable.",
            createdAt: "2026-05-12T12:00:00Z",
          });
        },
      ),
    );

    renderWithProviders(
      <AdminGroupReportDetailPage reportPublicId={REPORT_ID} />,
    );

    await waitFor(() =>
      expect(
        screen.getByTestId("report-detail-dismiss-btn"),
      ).toBeInTheDocument(),
    );

    await userEvent.click(screen.getByTestId("report-detail-dismiss-btn"));
    await userEvent.type(
      screen.getByTestId("dismiss-modal-notes"),
      "Not actionable.",
    );
    await userEvent.click(screen.getByTestId("dismiss-modal-submit"));

    await waitFor(() => expect(dismissPayload).not.toBeNull());
    expect(dismissPayload).toEqual({ notes: "Not actionable." });
    await waitFor(() =>
      expect(
        screen.queryByTestId("dismiss-modal-submit"),
      ).not.toBeInTheDocument(),
    );
  });
});
