import { describe, expect, it } from "vitest";
import { renderWithProviders, screen } from "@/test/render";
import CreateGroupPage from "./page";

describe("/groups/new", () => {
  it("renders the create-group form shell", () => {
    renderWithProviders(<CreateGroupPage />, { auth: "authenticated" });
    expect(screen.getByTestId("group-create-form")).toBeInTheDocument();
    expect(
      screen.getByRole("heading", { level: 1, name: /Create a realty group/i }),
    ).toBeInTheDocument();
  });
});
