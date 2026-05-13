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
  usePathname: () => "/admin/groups",
  useSearchParams: () => new URLSearchParams(),
}));

import AdminGroupsListRoute from "./page";

describe("/admin/groups", () => {
  it("renders the AdminRealtyGroupsPage shell heading", async () => {
    server.use(
      http.get("*/api/v1/admin/realty-groups", () =>
        HttpResponse.json({
          content: [],
          totalElements: 0,
          totalPages: 1,
          number: 0,
          size: 25,
          first: true,
          last: true,
          numberOfElements: 0,
          empty: true,
        }),
      ),
    );

    renderWithProviders(<AdminGroupsListRoute />);

    await waitFor(() =>
      expect(
        screen.getByRole("heading", { name: "Realty Groups" }),
      ).toBeInTheDocument(),
    );
  });
});
