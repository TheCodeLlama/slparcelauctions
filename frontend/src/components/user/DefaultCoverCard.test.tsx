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

describe("DefaultCoverCard", () => {
  it("renders empty state with 'Choose image' button when no cover set", async () => {
    server.use(
      http.get("*/api/v1/users/me", () =>
        HttpResponse.json(makeMe({ defaultCoverUrl: null })),
      ),
    );
    renderWithProviders(<DefaultCoverCard />, { auth: "authenticated" });

    expect(
      await screen.findByRole("button", { name: /choose image/i }),
    ).toBeVisible();
    expect(screen.queryByAltText(/default cover/i)).not.toBeInTheDocument();
  });

  it("renders set state with preview + Replace + Remove when cover set", async () => {
    server.use(
      http.get("*/api/v1/users/me", () =>
        HttpResponse.json(
          makeMe({
            defaultCoverUrl:
              "/api/v1/users/00000000-0000-0000-0000-00000000002a/default-cover/image",
          }),
        ),
      ),
    );
    renderWithProviders(<DefaultCoverCard />, { auth: "authenticated" });

    expect(await screen.findByAltText(/default cover/i)).toBeVisible();
    expect(screen.getByRole("button", { name: /replace/i })).toBeVisible();
    expect(screen.getByRole("button", { name: /remove/i })).toBeVisible();
  });

  it("calls resizeImage with maxDim 2048 then PUTs on file pick", async () => {
    server.use(
      http.get("*/api/v1/users/me", () =>
        HttpResponse.json(makeMe({ defaultCoverUrl: null })),
      ),
    );
    let putHit = false;
    server.use(
      http.put("*/api/v1/users/me/default-cover", () => {
        putHit = true;
        return HttpResponse.json({
          url: "https://example/x",
          contentType: "image/jpeg",
          sizeBytes: 1234,
        });
      }),
    );
    const user = userEvent.setup();
    renderWithProviders(<DefaultCoverCard />, { auth: "authenticated" });

    await screen.findByRole("button", { name: /choose image/i });
    const file = new File([new Uint8Array(8)], "x.jpg", { type: "image/jpeg" });
    const input = screen.getByTestId("default-cover-file-input") as HTMLInputElement;
    await user.upload(input, file);

    await waitFor(() => {
      expect(resizeImage).toHaveBeenCalledWith(file, { maxDim: 2048 });
    });
    await waitFor(() => {
      expect(putHit).toBe(true);
    });
  });

  it("calls DELETE when Remove is clicked", async () => {
    server.use(
      http.get("*/api/v1/users/me", () =>
        HttpResponse.json(
          makeMe({
            defaultCoverUrl:
              "/api/v1/users/00000000-0000-0000-0000-00000000002a/default-cover/image",
          }),
        ),
      ),
    );
    let deleteHit = false;
    server.use(
      http.delete("*/api/v1/users/me/default-cover", () => {
        deleteHit = true;
        return new HttpResponse(null, { status: 204 });
      }),
    );
    const user = userEvent.setup();
    renderWithProviders(<DefaultCoverCard />, { auth: "authenticated" });

    const removeBtn = await screen.findByRole("button", { name: /remove/i });
    await user.click(removeBtn);

    await waitFor(() => {
      expect(deleteHit).toBe(true);
    });
  });
});
