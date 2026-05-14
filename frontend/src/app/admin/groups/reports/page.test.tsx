import { describe, expect, it, vi } from "vitest";
import { http, HttpResponse } from "msw";
import { renderWithProviders, screen, waitFor } from "@/test/render";
import { server } from "@/test/msw/server";

vi.mock("next/navigation", () => ({
  useRouter: () => ({
    push: vi.fn(),
    replace: vi.fn(),
    refresh: vi.fn(),
    back: vi.fn(),
    forward: vi.fn(),
    prefetch: vi.fn(),
  }),
  usePathname: () => "/admin/groups/reports",
  useSearchParams: () => new URLSearchParams(),
}));

import AdminGroupReportsQueueRoute from "./page";

describe("/admin/groups/reports", () => {
  it("renders the AdminGroupReportsQueuePage heading", async () => {
    server.use(
      http.get("*/api/v1/admin/realty-groups/reports", () =>
        HttpResponse.json({
          content: [],
          totalElements: 0,
          totalPages: 0,
          number: 0,
          size: 25,
        }),
      ),
    );

    renderWithProviders(<AdminGroupReportsQueueRoute />);

    await waitFor(() =>
      expect(
        screen.getByRole("heading", { name: "Group Reports" }),
      ).toBeInTheDocument(),
    );
  });
});
