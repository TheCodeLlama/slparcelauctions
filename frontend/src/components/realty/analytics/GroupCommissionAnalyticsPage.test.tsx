import { describe, expect, it } from "vitest";
import { http, HttpResponse } from "msw";
import { renderWithProviders, screen, userEvent, waitFor } from "@/test/render";
import { server } from "@/test/msw/server";
import { commissionAnalyticsHandlers } from "@/test/msw/handlers";
import type {
  MemberCommissionRow,
  RealtyGroupPublicDto,
} from "@/types/realty";
import { GroupCommissionAnalyticsPage } from "./GroupCommissionAnalyticsPage";

const GROUP_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";

function group(name: string): RealtyGroupPublicDto {
  return {
    publicId: GROUP_ID,
    name,
    slug: name.toLowerCase().replace(/\s+/g, "-"),
    description: null,
    website: null,
    logoLightUrl: null, logoDarkUrl: null,
    coverLightUrl: null, coverDarkUrl: null,
    memberSince: "2026-01-01T00:00:00Z",
    leader: {
      userPublicId: "00000000-0000-0000-0000-000000000b00",
      displayName: "Leader Liz",
      avatarUrl: null,
    },
    agents: [],
    memberSeatLimit: 25,
    memberCount: 1,
  };
}

function installGroup(name: string) {
  server.use(
    http.get(`*/api/v1/realty-groups/${GROUP_ID}`, () =>
      HttpResponse.json(group(name)),
    ),
  );
}

function row(
  overrides: Partial<MemberCommissionRow> = {},
): MemberCommissionRow {
  return {
    memberPublicId: "11111111-1111-1111-1111-111111111111",
    displayName: "Alice",
    lifetimeLindens: 1000,
    last30DaysLindens: 100,
    ...overrides,
  };
}

describe("GroupCommissionAnalyticsPage", () => {
  it("renders_table_and_bar_chart_with_data", async () => {
    installGroup("Sunset Estates");
    server.use(
      commissionAnalyticsHandlers.getSuccess<MemberCommissionRow>([
        row({ memberPublicId: "aaa", displayName: "Alice", lifetimeLindens: 5000, last30DaysLindens: 1000 }),
        row({ memberPublicId: "bob", displayName: "Bob", lifetimeLindens: 2500, last30DaysLindens: 500 }),
      ]),
    );

    renderWithProviders(<GroupCommissionAnalyticsPage groupPublicId={GROUP_ID} />);

    await waitFor(() =>
      expect(screen.getByTestId("commission-analytics-page")).toBeInTheDocument(),
    );
    expect(screen.getByRole("heading", { name: /Commission analytics/i })).toBeInTheDocument();
    expect(screen.getByTestId("commission-analytics-group-name")).toHaveTextContent(
      "Sunset Estates",
    );

    // Table renders both rows.
    expect(screen.getByTestId("commission-analytics-table")).toBeInTheDocument();
    expect(screen.getByTestId("commission-analytics-row-aaa")).toBeInTheDocument();
    expect(screen.getByTestId("commission-analytics-row-bob")).toBeInTheDocument();
    expect(screen.getByTestId("commission-analytics-lifetime-aaa")).toHaveTextContent("L$ 5,000");
    expect(screen.getByTestId("commission-analytics-recent-bob")).toHaveTextContent("L$ 500");

    // Bar chart below renders one bar row per member.
    expect(screen.getByTestId("member-commission-bars")).toBeInTheDocument();
    expect(screen.getByTestId("member-commission-bar-row-aaa")).toBeInTheDocument();
    expect(screen.getByTestId("member-commission-bar-row-bob")).toBeInTheDocument();
  });

  it("renders_empty_state_when_no_data", async () => {
    installGroup("Sunset Estates");
    server.use(commissionAnalyticsHandlers.getEmpty());

    renderWithProviders(<GroupCommissionAnalyticsPage groupPublicId={GROUP_ID} />);

    await waitFor(() =>
      expect(screen.getByTestId("commission-analytics-empty")).toBeInTheDocument(),
    );
    expect(screen.getByText("No commissions paid out yet.")).toBeInTheDocument();
    expect(screen.queryByTestId("commission-analytics-table")).not.toBeInTheDocument();
    expect(screen.queryByTestId("member-commission-bars")).not.toBeInTheDocument();
  });

  it("renders empty state when every row has zero lifetime totals", async () => {
    installGroup("Sunset Estates");
    server.use(
      commissionAnalyticsHandlers.getSuccess<MemberCommissionRow>([
        row({ memberPublicId: "a", displayName: "A", lifetimeLindens: 0, last30DaysLindens: 0 }),
        row({ memberPublicId: "b", displayName: "B", lifetimeLindens: 0, last30DaysLindens: 0 }),
      ]),
    );
    renderWithProviders(<GroupCommissionAnalyticsPage groupPublicId={GROUP_ID} />);
    await waitFor(() =>
      expect(screen.getByTestId("commission-analytics-empty")).toBeInTheDocument(),
    );
  });

  it("sortByLifetime_descending_default — top earner is first row", async () => {
    installGroup("Sunset Estates");
    server.use(
      commissionAnalyticsHandlers.getSuccess<MemberCommissionRow>([
        // Server arbitrary order; client should sort by lifetime desc by default.
        row({ memberPublicId: "mid", displayName: "Mid", lifetimeLindens: 5000 }),
        row({ memberPublicId: "top", displayName: "Top", lifetimeLindens: 10000 }),
        row({ memberPublicId: "low", displayName: "Low", lifetimeLindens: 1000 }),
      ]),
    );

    renderWithProviders(<GroupCommissionAnalyticsPage groupPublicId={GROUP_ID} />);

    await waitFor(() =>
      expect(screen.getByTestId("commission-analytics-table")).toBeInTheDocument(),
    );

    const rowEls = screen.getAllByTestId(/^commission-analytics-row-/);
    expect(rowEls.map((el) => el.getAttribute("data-testid"))).toEqual([
      "commission-analytics-row-top",
      "commission-analytics-row-mid",
      "commission-analytics-row-low",
    ]);
  });

  it("clicking the lifetime column toggles sort direction", async () => {
    installGroup("Sunset Estates");
    server.use(
      commissionAnalyticsHandlers.getSuccess<MemberCommissionRow>([
        row({ memberPublicId: "mid", displayName: "Mid", lifetimeLindens: 5000 }),
        row({ memberPublicId: "top", displayName: "Top", lifetimeLindens: 10000 }),
        row({ memberPublicId: "low", displayName: "Low", lifetimeLindens: 1000 }),
      ]),
    );

    renderWithProviders(<GroupCommissionAnalyticsPage groupPublicId={GROUP_ID} />);
    await waitFor(() =>
      expect(screen.getByTestId("commission-analytics-table")).toBeInTheDocument(),
    );

    await userEvent.click(screen.getByTestId("commission-analytics-sort-lifetime"));

    const rowEls = screen.getAllByTestId(/^commission-analytics-row-/);
    expect(rowEls.map((el) => el.getAttribute("data-testid"))).toEqual([
      "commission-analytics-row-low",
      "commission-analytics-row-mid",
      "commission-analytics-row-top",
    ]);
  });

  it("clicking the member column sorts alphabetically asc on first click", async () => {
    installGroup("Sunset Estates");
    server.use(
      commissionAnalyticsHandlers.getSuccess<MemberCommissionRow>([
        row({ memberPublicId: "c", displayName: "Carol", lifetimeLindens: 5000 }),
        row({ memberPublicId: "a", displayName: "Alice", lifetimeLindens: 1000 }),
        row({ memberPublicId: "b", displayName: "Bob", lifetimeLindens: 3000 }),
      ]),
    );

    renderWithProviders(<GroupCommissionAnalyticsPage groupPublicId={GROUP_ID} />);
    await waitFor(() =>
      expect(screen.getByTestId("commission-analytics-table")).toBeInTheDocument(),
    );

    await userEvent.click(screen.getByTestId("commission-analytics-sort-displayName"));

    const rowEls = screen.getAllByTestId(/^commission-analytics-row-/);
    expect(rowEls.map((el) => el.getAttribute("data-testid"))).toEqual([
      "commission-analytics-row-a",
      "commission-analytics-row-b",
      "commission-analytics-row-c",
    ]);
  });

  it("renders permission-denied state on 403", async () => {
    installGroup("Sunset Estates");
    server.use(commissionAnalyticsHandlers.getForbidden());

    renderWithProviders(<GroupCommissionAnalyticsPage groupPublicId={GROUP_ID} />);

    await waitFor(() =>
      expect(
        screen.getByTestId("commission-analytics-forbidden"),
      ).toBeInTheDocument(),
    );
    expect(
      screen.getByText(/You do not have permission/i),
    ).toBeInTheDocument();
    const back = screen.getByTestId("commission-analytics-back-link");
    await waitFor(() =>
      expect(back).toHaveAttribute("href", "/groups/sunset-estates"),
    );
  });

  it("bars_scale_relative_to_max — top earner is at 100% width", async () => {
    installGroup("Sunset Estates");
    server.use(
      commissionAnalyticsHandlers.getSuccess<MemberCommissionRow>([
        row({ memberPublicId: "top", displayName: "Top", lifetimeLindens: 10000 }),
        row({ memberPublicId: "mid", displayName: "Mid", lifetimeLindens: 5000 }),
      ]),
    );

    renderWithProviders(<GroupCommissionAnalyticsPage groupPublicId={GROUP_ID} />);
    await waitFor(() =>
      expect(screen.getByTestId("member-commission-bars")).toBeInTheDocument(),
    );

    expect(
      screen
        .getByTestId("member-commission-bar-lifetime-top")
        .getAttribute("data-bar-width"),
    ).toBe("w-[100%]");
    expect(
      screen
        .getByTestId("member-commission-bar-lifetime-mid")
        .getAttribute("data-bar-width"),
    ).toBe("w-[50%]");
  });
});
