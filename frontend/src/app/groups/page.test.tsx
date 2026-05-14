import { describe, expect, it } from "vitest";
import { http, HttpResponse } from "msw";
import { renderWithProviders, screen, waitFor } from "@/test/render";
import { server } from "@/test/msw/server";
import GroupsBrowseRoute from "./page";

describe("/groups page", () => {
  it("renders the browse client", async () => {
    server.use(
      http.get("*/api/v1/realty-groups", () =>
        HttpResponse.json({
          content: [],
          totalElements: 0,
          totalPages: 0,
          number: 0,
          size: 20,
        }),
      ),
    );

    renderWithProviders(<GroupsBrowseRoute />);

    await waitFor(() =>
      expect(screen.getByTestId("groups-browse-client")).toBeInTheDocument(),
    );
  });
});
