// frontend/src/components/auth/RequireAuth.test.tsx
import { describe, it, expect, vi, beforeEach } from "vitest";
import { renderWithProviders, screen, waitFor } from "@/test/render";
import { server } from "@/test/msw/server";
import { authHandlers } from "@/test/msw/handlers";
import { RequireAuth } from "./RequireAuth";

const mockPush = vi.fn();
vi.mock("next/navigation", () => ({
  useRouter: () => ({ push: mockPush }),
  usePathname: () => "/dashboard",
}));

describe("RequireAuth", () => {
  beforeEach(() => {
    mockPush.mockReset();
  });

  it("renders a loading spinner while session is loading", () => {
    server.use(authHandlers.refreshUnauthenticated());
    const { container } = renderWithProviders(
      <RequireAuth>
        <div>protected content</div>
      </RequireAuth>
    );
    // The loading state renders the spinner div before the bootstrap resolves.
    expect(container.querySelector(".animate-spin")).toBeInTheDocument();
  });

  it("redirects to /login?next=... when unauthenticated", async () => {
    server.use(authHandlers.refreshUnauthenticated());
    renderWithProviders(
      <RequireAuth>
        <div>protected content</div>
      </RequireAuth>
    );

    await waitFor(() => {
      expect(mockPush).toHaveBeenCalledWith("/login?next=%2Fdashboard");
    });
    expect(screen.queryByText("protected content")).not.toBeInTheDocument();
  });

  it("renders children when authenticated", async () => {
    server.use(authHandlers.refreshSuccess());
    renderWithProviders(
      <RequireAuth>
        <div>protected content</div>
      </RequireAuth>
    );

    expect(await screen.findByText("protected content")).toBeInTheDocument();
    expect(mockPush).not.toHaveBeenCalled();
  });
});
