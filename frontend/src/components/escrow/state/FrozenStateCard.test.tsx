import { describe, it, expect } from "vitest";
import { renderWithProviders, screen } from "@/test/render";
import { FrozenStateCard } from "./FrozenStateCard";
import { fakeEscrow } from "@/test/fixtures/escrow";

describe("FrozenStateCard", () => {
  describe("default freeze (UNKNOWN_OWNER / PARCEL_DELETED)", () => {
    it.each(["seller", "winner"] as const)(
      "renders default copy for role=%s",
      (role) => {
        renderWithProviders(
          <FrozenStateCard
            escrow={fakeEscrow({
              state: "FROZEN",
              frozenAt: "2026-05-02T09:00:00Z",
              freezeReason: "UNKNOWN_OWNER",
            })}
            role={role}
          />,
        );
        expect(screen.getByText(/escrow frozen/i)).toBeInTheDocument();
        expect(
          screen.getByText(/refunded automatically/i),
        ).toBeInTheDocument();
      },
    );

    it("exposes the freeze reason in the copy", () => {
      renderWithProviders(
        <FrozenStateCard
          escrow={fakeEscrow({
            state: "FROZEN",
            frozenAt: "2026-05-02T09:00:00Z",
            freezeReason: "PARCEL_DELETED",
          })}
          role="seller"
        />,
      );
      expect(screen.getByText(/parcel deleted/i)).toBeInTheDocument();
    });
  });

  describe("WORLD_API_PERSISTENT_FAILURE softer copy", () => {
    it("renders the softer copy instead of the default", () => {
      renderWithProviders(
        <FrozenStateCard
          escrow={fakeEscrow({
            state: "FROZEN",
            frozenAt: "2026-05-02T09:00:00Z",
            freezeReason: "WORLD_API_PERSISTENT_FAILURE",
          })}
          role="winner"
        />,
      );
      expect(
        screen.getByText(/couldn't verify parcel ownership repeatedly/i),
      ).toBeInTheDocument();
      expect(
        screen.getByText(/transient issue/i),
      ).toBeInTheDocument();
      // Default copy should be suppressed.
      expect(
        screen.queryByText(/refunded automatically/i),
      ).not.toBeInTheDocument();
    });
  });

  it("does not render a dispute link", () => {
    renderWithProviders(
      <FrozenStateCard
        escrow={fakeEscrow({
          state: "FROZEN",
          frozenAt: "2026-05-02T09:00:00Z",
          freezeReason: "UNKNOWN_OWNER",
        })}
        role="seller"
      />,
    );
    expect(
      screen.queryByRole("link", { name: /file a dispute/i }),
    ).not.toBeInTheDocument();
  });
});
