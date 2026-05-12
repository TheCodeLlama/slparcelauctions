import { describe, expect, it, vi, beforeEach } from "vitest";
import { http, HttpResponse } from "msw";
import { renderWithProviders, screen, userEvent, waitFor } from "@/test/render";
import { server } from "@/test/msw/server";
import { GroupCreateForm } from "./GroupCreateForm";

const pushMock = vi.fn();
vi.mock("next/navigation", () => ({
  useRouter: () => ({ push: pushMock, replace: pushMock, prefetch: vi.fn() }),
  usePathname: () => "/dashboard/groups/create",
}));

beforeEach(() => {
  pushMock.mockClear();
});

describe("GroupCreateForm", () => {
  it("renders the name, description, and website fields", () => {
    renderWithProviders(<GroupCreateForm />);
    expect(screen.getByLabelText(/Name/i)).toBeInTheDocument();
    expect(screen.getByLabelText(/Description/i)).toBeInTheDocument();
    expect(screen.getByLabelText(/Website/i)).toBeInTheDocument();
  });

  it("shows a validation error when name is empty on submit", async () => {
    renderWithProviders(<GroupCreateForm />);
    await userEvent.click(screen.getByTestId("group-create-submit"));
    expect(await screen.findByText(/Name is required/i)).toBeInTheDocument();
  });

  it("shows a validation error for an invalid website URL", async () => {
    renderWithProviders(<GroupCreateForm />);
    await userEvent.type(screen.getByTestId("group-create-name"), "My Group");
    await userEvent.type(screen.getByTestId("group-create-website"), "not-a-url");
    await userEvent.click(screen.getByTestId("group-create-submit"));
    expect(await screen.findByText(/Enter a valid URL/i)).toBeInTheDocument();
  });

  it("submits and navigates to the manage page with the created slug", async () => {
    server.use(
      http.post("*/api/v1/realty-groups", () =>
        HttpResponse.json({
          publicId: "00000000-0000-0000-0000-000000000001",
          name: "My Group",
          slug: "my-group",
          description: null,
          website: null,
          logoUrl: null,
          coverUrl: null,
          memberSince: "2026-05-11T10:00:00Z",
          leader: {
            userPublicId: "11111111-1111-1111-1111-111111111111",
            displayName: "Leader",
            avatarUrl: null,
          },
          agents: [],
          memberSeatLimit: 50,
          memberCount: 1,
        }),
      ),
    );
    renderWithProviders(<GroupCreateForm />);
    await userEvent.type(screen.getByTestId("group-create-name"), "My Group");
    await userEvent.click(screen.getByTestId("group-create-submit"));
    await waitFor(() =>
      expect(pushMock).toHaveBeenCalledWith("/dashboard/groups/my-group/manage"),
    );
  });

  it("surfaces a toast error when the API returns a problem detail", async () => {
    server.use(
      http.post("*/api/v1/realty-groups", () =>
        HttpResponse.json(
          {
            type: "https://slpa.example/problems/realty",
            title: "GROUP_NAME_TAKEN",
            status: 409,
            detail: "GROUP_NAME_TAKEN",
            code: "GROUP_NAME_TAKEN",
          },
          { status: 409 },
        ),
      ),
    );
    renderWithProviders(<GroupCreateForm />);
    await userEvent.type(screen.getByTestId("group-create-name"), "Taken");
    await userEvent.click(screen.getByTestId("group-create-submit"));
    expect(
      await screen.findByText(/That name is already in use/i),
    ).toBeInTheDocument();
    expect(pushMock).not.toHaveBeenCalled();
  });
});
