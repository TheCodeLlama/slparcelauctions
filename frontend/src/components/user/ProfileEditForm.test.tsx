import { describe, it, expect } from "vitest";
import { renderWithProviders, screen, waitFor } from "@/test/render";
import userEvent from "@testing-library/user-event";
import { server } from "@/test/msw/server";
import { userHandlers } from "@/test/msw/handlers";
import { mockVerifiedCurrentUser } from "@/test/msw/fixtures";
import { ProfileEditForm } from "./ProfileEditForm";

function setup() {
  server.use(userHandlers.updateMeSuccess());
}

describe("ProfileEditForm", () => {
  it("pre-fills with displayName and bio", () => {
    renderWithProviders(
      <ProfileEditForm user={mockVerifiedCurrentUser} />,
      { auth: "authenticated" },
    );

    const nameInput = screen.getByLabelText(/display name/i) as HTMLInputElement;
    expect(nameInput.value).toBe("Verified Tester");

    const bioInput = screen.getByLabelText(/bio/i) as HTMLTextAreaElement;
    expect(bioInput.value).toBe("Auction enthusiast");
  });

  it("empty displayName shows validation error", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <ProfileEditForm user={mockVerifiedCurrentUser} />,
      { auth: "authenticated" },
    );

    const nameInput = screen.getByLabelText(/display name/i);
    await user.clear(nameInput);
    // Type then clear to trigger dirty state, then submit
    await user.click(screen.getByRole("button", { name: /save/i }));

    expect(
      await screen.findByText(/display name is required/i),
    ).toBeInTheDocument();
  });

  it("51-char displayName shows validation error", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <ProfileEditForm user={mockVerifiedCurrentUser} />,
      { auth: "authenticated" },
    );

    const nameInput = screen.getByLabelText(/display name/i);
    await user.clear(nameInput);
    await user.type(nameInput, "A".repeat(51));
    await user.click(screen.getByRole("button", { name: /save/i }));

    expect(
      await screen.findByText(/50 characters or fewer/i),
    ).toBeInTheDocument();
  });

  it("whitespace-only displayName shows validation error", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <ProfileEditForm user={mockVerifiedCurrentUser} />,
      { auth: "authenticated" },
    );

    const nameInput = screen.getByLabelText(/display name/i);
    await user.clear(nameInput);
    await user.type(nameInput, "   ");
    await user.click(screen.getByRole("button", { name: /save/i }));

    expect(
      await screen.findByText(/cannot be blank or whitespace/i),
    ).toBeInTheDocument();
  });

  it("valid submit fires mutation and resets dirty state", async () => {
    setup();
    const user = userEvent.setup();
    renderWithProviders(
      <ProfileEditForm user={mockVerifiedCurrentUser} />,
      { auth: "authenticated" },
    );

    const nameInput = screen.getByLabelText(/display name/i);
    await user.clear(nameInput);
    await user.type(nameInput, "New Name");

    const saveBtn = screen.getByRole("button", { name: /save/i });
    expect(saveBtn).toBeEnabled();
    await user.click(saveBtn);

    // After success, dirty state resets and Save becomes disabled
    await waitFor(() => {
      expect(screen.getByRole("button", { name: /save/i })).toBeDisabled();
    });
  });

  it("save disabled when form is not dirty", () => {
    renderWithProviders(
      <ProfileEditForm user={mockVerifiedCurrentUser} />,
      { auth: "authenticated" },
    );

    expect(screen.getByRole("button", { name: /save/i })).toBeDisabled();
  });
});
