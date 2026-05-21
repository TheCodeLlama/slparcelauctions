import { describe, expect, it, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { renderWithProviders, screen, waitFor } from "@/test/render";
import { server } from "@/test/msw/server";
import { mockVerifiedCurrentUser } from "@/test/msw/fixtures";
import type { CurrentUser } from "@/lib/user/api";

vi.mock("@/lib/image/resizeImage", () => ({
  resizeImage: vi.fn(async (file: File) => file),
}));

import { resizeImage } from "@/lib/image/resizeImage";
import { DefaultCoverCard } from "./DefaultCoverCard";

function makeMe(overrides: Partial<CurrentUser> = {}): CurrentUser {
  return { ...mockVerifiedCurrentUser, ...overrides };
}

const LIGHT_URL =
  "/api/v1/users/00000000-0000-0000-0000-00000000002a/default-cover/image?variant=light";
const DARK_URL =
  "/api/v1/users/00000000-0000-0000-0000-00000000002a/default-cover/image?variant=dark";

describe("DefaultCoverCard", () => {
  it("renders both Light and Dark slots", async () => {
    server.use(
      http.get("*/api/v1/users/me", () =>
        HttpResponse.json(
          makeMe({ defaultCoverLightUrl: null, defaultCoverDarkUrl: null }),
        ),
      ),
    );
    renderWithProviders(<DefaultCoverCard />, { auth: "authenticated" });

    expect(
      await screen.findByTestId("default-cover-cover-light-slot"),
    ).toBeInTheDocument();
    expect(
      screen.getByTestId("default-cover-cover-dark-slot"),
    ).toBeInTheDocument();
  });

  it("shows the empty state in both slots when both variants are null", async () => {
    server.use(
      http.get("*/api/v1/users/me", () =>
        HttpResponse.json(
          makeMe({ defaultCoverLightUrl: null, defaultCoverDarkUrl: null }),
        ),
      ),
    );
    renderWithProviders(<DefaultCoverCard />, { auth: "authenticated" });

    expect(
      await screen.findByTestId("default-cover-cover-light-empty"),
    ).toBeInTheDocument();
    expect(
      screen.getByTestId("default-cover-cover-dark-empty"),
    ).toBeInTheDocument();
    expect(
      screen.getByTestId("default-cover-cover-preview-empty"),
    ).toBeInTheDocument();
  });

  it("uploads the light variant independently of the dark slot", async () => {
    server.use(
      http.get("*/api/v1/users/me", () =>
        HttpResponse.json(
          makeMe({ defaultCoverLightUrl: null, defaultCoverDarkUrl: null }),
        ),
      ),
    );
    let lightHit = false;
    let darkHit = false;
    server.use(
      http.post("*/api/v1/users/me/default-cover/light", () => {
        lightHit = true;
        return HttpResponse.json(makeMe({ defaultCoverLightUrl: LIGHT_URL }));
      }),
      http.post("*/api/v1/users/me/default-cover/dark", () => {
        darkHit = true;
        return HttpResponse.json(makeMe({ defaultCoverDarkUrl: DARK_URL }));
      }),
    );
    const user = userEvent.setup();
    renderWithProviders(<DefaultCoverCard />, { auth: "authenticated" });

    const input = (await screen.findByTestId(
      "default-cover-cover-light-input",
    )) as HTMLInputElement;
    const file = new File([new Uint8Array(8)], "x.jpg", { type: "image/jpeg" });
    await user.upload(input, file);

    await waitFor(() => {
      expect(resizeImage).toHaveBeenCalledWith(file, { maxDim: 2048 });
    });
    await waitFor(() => {
      expect(lightHit).toBe(true);
    });
    expect(darkHit).toBe(false);
  });

  it("uploads the dark variant independently of the light slot", async () => {
    server.use(
      http.get("*/api/v1/users/me", () =>
        HttpResponse.json(
          makeMe({ defaultCoverLightUrl: null, defaultCoverDarkUrl: null }),
        ),
      ),
    );
    let lightHit = false;
    let darkHit = false;
    server.use(
      http.post("*/api/v1/users/me/default-cover/light", () => {
        lightHit = true;
        return HttpResponse.json(makeMe({ defaultCoverLightUrl: LIGHT_URL }));
      }),
      http.post("*/api/v1/users/me/default-cover/dark", () => {
        darkHit = true;
        return HttpResponse.json(makeMe({ defaultCoverDarkUrl: DARK_URL }));
      }),
    );
    const user = userEvent.setup();
    renderWithProviders(<DefaultCoverCard />, { auth: "authenticated" });

    const input = (await screen.findByTestId(
      "default-cover-cover-dark-input",
    )) as HTMLInputElement;
    const file = new File([new Uint8Array(8)], "x.jpg", { type: "image/jpeg" });
    await user.upload(input, file);

    await waitFor(() => {
      expect(darkHit).toBe(true);
    });
    expect(lightHit).toBe(false);
  });

  it("deletes the light variant independently", async () => {
    server.use(
      http.get("*/api/v1/users/me", () =>
        HttpResponse.json(
          makeMe({
            defaultCoverLightUrl: LIGHT_URL,
            defaultCoverDarkUrl: DARK_URL,
          }),
        ),
      ),
    );
    let lightDeleteHit = false;
    let darkDeleteHit = false;
    server.use(
      http.delete("*/api/v1/users/me/default-cover/light", () => {
        lightDeleteHit = true;
        return HttpResponse.json(makeMe({ defaultCoverDarkUrl: DARK_URL }));
      }),
      http.delete("*/api/v1/users/me/default-cover/dark", () => {
        darkDeleteHit = true;
        return HttpResponse.json(makeMe({ defaultCoverLightUrl: LIGHT_URL }));
      }),
    );
    const user = userEvent.setup();
    renderWithProviders(<DefaultCoverCard />, { auth: "authenticated" });

    const removeBtn = await screen.findByTestId(
      "default-cover-cover-light-delete-button",
    );
    await user.click(removeBtn);

    await waitFor(() => {
      expect(lightDeleteHit).toBe(true);
    });
    expect(darkDeleteHit).toBe(false);
  });

  it("deletes the dark variant independently", async () => {
    server.use(
      http.get("*/api/v1/users/me", () =>
        HttpResponse.json(
          makeMe({
            defaultCoverLightUrl: LIGHT_URL,
            defaultCoverDarkUrl: DARK_URL,
          }),
        ),
      ),
    );
    let lightDeleteHit = false;
    let darkDeleteHit = false;
    server.use(
      http.delete("*/api/v1/users/me/default-cover/light", () => {
        lightDeleteHit = true;
        return HttpResponse.json(makeMe({ defaultCoverDarkUrl: DARK_URL }));
      }),
      http.delete("*/api/v1/users/me/default-cover/dark", () => {
        darkDeleteHit = true;
        return HttpResponse.json(makeMe({ defaultCoverLightUrl: LIGHT_URL }));
      }),
    );
    const user = userEvent.setup();
    renderWithProviders(<DefaultCoverCard />, { auth: "authenticated" });

    const removeBtn = await screen.findByTestId(
      "default-cover-cover-dark-delete-button",
    );
    await user.click(removeBtn);

    await waitFor(() => {
      expect(darkDeleteHit).toBe(true);
    });
    expect(lightDeleteHit).toBe(false);
  });

  it("renders a theme-aware preview when a variant is set", async () => {
    server.use(
      http.get("*/api/v1/users/me", () =>
        HttpResponse.json(
          makeMe({
            defaultCoverLightUrl: LIGHT_URL,
            defaultCoverDarkUrl: DARK_URL,
          }),
        ),
      ),
    );
    renderWithProviders(<DefaultCoverCard />, { auth: "authenticated" });

    // ThemedImage resolves to the light variant under the default test theme.
    const preview = await screen.findByTestId(
      "default-cover-cover-preview-image",
    );
    expect(preview).toHaveAttribute(
      "src",
      expect.stringContaining("variant=light"),
    );
  });
});
