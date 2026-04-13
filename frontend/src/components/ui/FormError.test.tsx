// frontend/src/components/ui/FormError.test.tsx
import { describe, it, expect } from "vitest";
import { renderWithProviders, screen } from "@/test/render";
import { FormError } from "./FormError";

describe("FormError", () => {
  it("renders nothing when message is undefined", () => {
    renderWithProviders(<FormError />);
    expect(screen.queryByRole("alert")).toBeNull();
  });

  it("renders the message in an alert role with error styling", () => {
    renderWithProviders(<FormError message="Email or password is incorrect." />);
    const alert = screen.getByRole("alert");
    expect(alert).toHaveTextContent("Email or password is incorrect.");
    expect(alert.className).toContain("bg-error-container");
  });
});
