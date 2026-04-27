import { describe, it, expect, vi, beforeEach } from "vitest";
import { renderWithProviders, screen, waitFor } from "@/test/render";
import { server } from "@/test/msw/server";
import { authHandlers } from "@/test/msw/handlers";
import { mockAdminUser } from "@/test/msw/fixtures";
import { RequireAdmin } from "./RequireAdmin";

const mockReplace = vi.fn();
vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: mockReplace }),
}));

describe("RequireAdmin", () => {
  beforeEach(() => {
    mockReplace.mockReset();
  });

  it("renders a loading spinner while session is loading", () => {
    server.use(authHandlers.refreshUnauthenticated());
    const { container } = renderWithProviders(
      <RequireAdmin>
        <div>admin content</div>
      </RequireAdmin>
    );
    expect(container.querySelector(".animate-spin")).toBeInTheDocument();
  });

  it("redirects to / when unauthenticated, renders nothing", async () => {
    server.use(authHandlers.refreshUnauthenticated());
    renderWithProviders(
      <RequireAdmin>
        <div>admin content</div>
      </RequireAdmin>
    );

    await waitFor(() => {
      expect(mockReplace).toHaveBeenCalledWith("/");
    });
    expect(screen.queryByText("admin content")).not.toBeInTheDocument();
  });

  it("redirects to / when authenticated but role is not ADMIN", async () => {
    server.use(authHandlers.refreshSuccess());
    renderWithProviders(
      <RequireAdmin>
        <div>admin content</div>
      </RequireAdmin>
    );

    await waitFor(() => {
      expect(mockReplace).toHaveBeenCalledWith("/");
    });
    expect(screen.queryByText("admin content")).not.toBeInTheDocument();
  });

  it("renders children when authenticated as ADMIN", async () => {
    server.use(authHandlers.refreshSuccess(mockAdminUser));
    renderWithProviders(
      <RequireAdmin>
        <div>admin content</div>
      </RequireAdmin>
    );

    expect(await screen.findByText("admin content")).toBeInTheDocument();
    expect(mockReplace).not.toHaveBeenCalled();
  });
});
