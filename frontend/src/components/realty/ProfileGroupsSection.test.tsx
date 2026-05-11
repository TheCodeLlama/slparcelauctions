import { describe, it, expect } from "vitest";
import { http, HttpResponse } from "msw";
import { renderWithProviders, screen, waitFor } from "@/test/render";
import { server } from "@/test/msw/server";
import type { UserRealtyGroupAffiliationDto } from "@/types/realty";
import { ProfileGroupsSection } from "./ProfileGroupsSection";

const USER_ID = "00000000-0000-0000-0000-00000000002a";

function affiliation(
  overrides: Partial<UserRealtyGroupAffiliationDto> = {},
): UserRealtyGroupAffiliationDto {
  return {
    groupPublicId: "10000000-0000-0000-0000-000000000001",
    groupName: "Mainland Realty",
    groupSlug: "mainland-realty",
    logoUrl: null,
    role: "LEADER",
    ...overrides,
  };
}

function mockAffiliations(affiliations: UserRealtyGroupAffiliationDto[]) {
  server.use(
    http.get(`*/api/v1/users/${USER_ID}/realty-groups`, () =>
      HttpResponse.json(affiliations),
    ),
  );
}

describe("ProfileGroupsSection", () => {
  it("renders the section heading and a chip per affiliation when populated", async () => {
    mockAffiliations([
      affiliation({
        groupPublicId: "10000000-0000-0000-0000-000000000001",
        groupName: "Mainland Realty",
        groupSlug: "mainland-realty",
        role: "LEADER",
      }),
      affiliation({
        groupPublicId: "10000000-0000-0000-0000-000000000002",
        groupName: "Linden Estates",
        groupSlug: "linden-estates",
        role: "AGENT",
      }),
    ]);

    renderWithProviders(<ProfileGroupsSection userPublicId={USER_ID} />);

    await screen.findByText("Groups");
    const chips = await screen.findAllByTestId("group-chip");
    expect(chips).toHaveLength(2);
    expect(screen.getByText("Mainland Realty")).toBeInTheDocument();
    expect(screen.getByText("Linden Estates")).toBeInTheDocument();
  });

  it("renders 'Leader' badge for the LEADER role", async () => {
    mockAffiliations([
      affiliation({
        groupPublicId: "10000000-0000-0000-0000-000000000001",
        role: "LEADER",
      }),
    ]);

    renderWithProviders(<ProfileGroupsSection userPublicId={USER_ID} />);

    expect(await screen.findByText("Leader")).toBeInTheDocument();
    expect(screen.queryByText("Agent")).not.toBeInTheDocument();
  });

  it("renders 'Agent' badge for the AGENT role", async () => {
    mockAffiliations([
      affiliation({
        groupPublicId: "10000000-0000-0000-0000-000000000003",
        role: "AGENT",
      }),
    ]);

    renderWithProviders(<ProfileGroupsSection userPublicId={USER_ID} />);

    expect(await screen.findByText("Agent")).toBeInTheDocument();
    expect(screen.queryByText("Leader")).not.toBeInTheDocument();
  });

  it("hides the section entirely when the affiliations list is empty", async () => {
    mockAffiliations([]);

    renderWithProviders(<ProfileGroupsSection userPublicId={USER_ID} />);

    // Allow the query to resolve, then assert nothing rendered. We can't
    // poll for a "not present" condition synchronously, so wait a tick
    // first via a microtask flush.
    await new Promise((resolve) => setTimeout(resolve, 0));
    await waitFor(() => {
      expect(
        screen.queryByTestId("profile-groups-section"),
      ).not.toBeInTheDocument();
    });
    expect(screen.queryByText("Groups")).not.toBeInTheDocument();
  });

  it("hides the section when the API errors", async () => {
    server.use(
      http.get(`*/api/v1/users/${USER_ID}/realty-groups`, () =>
        HttpResponse.json({ code: "SERVER_ERROR" }, { status: 500 }),
      ),
    );

    renderWithProviders(<ProfileGroupsSection userPublicId={USER_ID} />);

    // Wait for the failed query to settle (retry: false is the test
    // default, see test/render.tsx), then confirm the section is absent.
    await new Promise((resolve) => setTimeout(resolve, 50));
    expect(
      screen.queryByTestId("profile-groups-section"),
    ).not.toBeInTheDocument();
    expect(screen.queryByText("Groups")).not.toBeInTheDocument();
  });
});
