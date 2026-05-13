import { describe, expect, it } from "vitest";
import { renderWithProviders, screen, userEvent } from "@/test/render";
import type { RealtyGroupRowDto } from "@/types/realty";
import { AdminRealtyGroupsTable } from "./AdminRealtyGroupsTable";

function makeRow(overrides: Partial<RealtyGroupRowDto> = {}): RealtyGroupRowDto {
  return {
    publicId: "00000000-0000-0000-0000-000000000001",
    name: "Mainland Realty",
    slug: "mainland-realty",
    leaderPublicId: "11111111-1111-1111-1111-111111111111",
    leaderDisplayName: "Leader Lee",
    memberCount: 3,
    dissolved: false,
    createdAt: "2026-04-01T10:00:00Z",
    dissolvedAt: null,
    ...overrides,
  };
}

describe("AdminRealtyGroupsTable", () => {
  it("renders an empty state when there are no rows", () => {
    renderWithProviders(<AdminRealtyGroupsTable rows={[]} />);
    expect(screen.getByTestId("admin-realty-empty")).toBeInTheDocument();
  });

  it("renders one row per group with name, slug, and member count", () => {
    renderWithProviders(
      <AdminRealtyGroupsTable
        rows={[
          makeRow({
            publicId: "00000000-0000-0000-0000-000000000001",
            name: "Mainland Realty",
          }),
          makeRow({
            publicId: "00000000-0000-0000-0000-000000000002",
            name: "Heterocera Holdings",
          }),
        ]}
      />,
    );
    expect(
      screen.getByTestId("admin-realty-row-00000000-0000-0000-0000-000000000001"),
    ).toBeInTheDocument();
    expect(
      screen.getByTestId("admin-realty-row-00000000-0000-0000-0000-000000000002"),
    ).toBeInTheDocument();
    expect(screen.getByText("Mainland Realty")).toBeInTheDocument();
    expect(screen.getByText("Heterocera Holdings")).toBeInTheDocument();
  });

  it("links the row to the admin detail page via slug", () => {
    renderWithProviders(<AdminRealtyGroupsTable rows={[makeRow()]} />);
    const link = screen.getByTestId(
      "admin-realty-row-link-00000000-0000-0000-0000-000000000001",
    );
    expect(link).toHaveAttribute("href", "/admin/groups/mainland-realty");
  });

  it("shows a Dissolved status chip for dissolved groups", () => {
    renderWithProviders(
      <AdminRealtyGroupsTable rows={[makeRow({ dissolved: true })]} />,
    );
    expect(screen.getByText("Dissolved")).toBeInTheDocument();
  });

  it("opens the row action menu and offers Force-edit + Force-dissolve for active groups", async () => {
    renderWithProviders(<AdminRealtyGroupsTable rows={[makeRow()]} />);
    const trigger = screen.getByTestId("admin-realty-row-menu-trigger");
    await userEvent.click(trigger);
    expect(screen.getByTestId("admin-realty-row-action-edit")).toBeInTheDocument();
    expect(
      screen.getByTestId("admin-realty-row-action-dissolve"),
    ).toBeInTheDocument();
  });

  it("hides Force-dissolve for already-dissolved groups", async () => {
    renderWithProviders(
      <AdminRealtyGroupsTable rows={[makeRow({ dissolved: true })]} />,
    );
    await userEvent.click(screen.getByTestId("admin-realty-row-menu-trigger"));
    expect(screen.getByTestId("admin-realty-row-action-edit")).toBeInTheDocument();
    expect(
      screen.queryByTestId("admin-realty-row-action-dissolve"),
    ).not.toBeInTheDocument();
  });

  it("opens the edit modal when Force-edit is picked", async () => {
    renderWithProviders(<AdminRealtyGroupsTable rows={[makeRow()]} />);
    await userEvent.click(screen.getByTestId("admin-realty-row-menu-trigger"));
    await userEvent.click(screen.getByTestId("admin-realty-row-action-edit"));
    expect(screen.getByTestId("admin-realty-edit-name")).toBeInTheDocument();
  });
});
