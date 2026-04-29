import { describe, it, expect, beforeEach } from "vitest";
import { http, HttpResponse } from "msw";
import { renderWithProviders, waitFor } from "@/test/render";
import { server } from "@/test/msw/server";
import { CuratorTrayMount } from "./CuratorTrayMount";

beforeEach(() => {
  document.body.innerHTML = "";
  server.use(
    http.get("*/api/v1/me/saved/ids", () =>
      HttpResponse.json({ ids: [] }),
    ),
  );
});

describe("CuratorTrayMount", () => {
  it("renders nothing when the caller is unauthenticated", () => {
    const slot = document.createElement("div");
    slot.id = "curator-tray-slot";
    document.body.appendChild(slot);

    renderWithProviders(<CuratorTrayMount />, { auth: "anonymous" });
    expect(slot.childNodes.length).toBe(0);
  });

  it("portals the trigger into #curator-tray-slot when authenticated", async () => {
    const slot = document.createElement("div");
    slot.id = "curator-tray-slot";
    document.body.appendChild(slot);

    renderWithProviders(<CuratorTrayMount />, { auth: "authenticated" });

    await waitFor(() => {
      expect(slot.querySelector("[data-testid='curator-tray-trigger']"))
        .not.toBeNull();
    });
  });

  it("renders in dark mode without crashing", async () => {
    const slot = document.createElement("div");
    slot.id = "curator-tray-slot";
    document.body.appendChild(slot);

    renderWithProviders(<CuratorTrayMount />, {
      auth: "authenticated",
      theme: "dark",
      forceTheme: true,
    });
    await waitFor(() => {
      expect(slot.querySelector("[data-testid='curator-tray-trigger']"))
        .not.toBeNull();
    });
  });
});
