import { describe, it, expect, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { ThemeProvider } from "next-themes";
import type { ReactNode } from "react";
import { ImagePairField, type ImagePairFieldProps } from "./ImagePairField";

// See useThemedImage.test.tsx for why we use defaultTheme + enableSystem=false
// instead of forcedTheme to drive `resolvedTheme` inside the tests.
function wrap(theme: "light" | "dark", children: ReactNode) {
  return (
    <ThemeProvider
      attribute="data-theme"
      defaultTheme={theme}
      enableSystem={false}
    >
      {children}
    </ThemeProvider>
  );
}

function baseProps(
  overrides: Partial<ImagePairFieldProps> = {},
): ImagePairFieldProps {
  return {
    surface: "cover",
    testIdPrefix: "fixture",
    heading: "Cover",
    description: "Helper copy.",
    lightUrl: null,
    darkUrl: null,
    altPrefix: "Cover",
    disabled: false,
    disabledTitle: undefined,
    slotClassName: "slot",
    emptyClassName: "empty",
    previewClassName: "preview",
    onUpload: vi.fn(),
    onDelete: vi.fn(),
    uploadBusyLight: false,
    uploadBusyDark: false,
    deleteBusyLight: false,
    deleteBusyDark: false,
    ...overrides,
  };
}

describe("ImagePairField", () => {
  it("renders a Light and a Dark slot, namespaced by testIdPrefix + surface", () => {
    render(wrap("light", <ImagePairField {...baseProps()} />));
    expect(screen.getByTestId("fixture-cover-light-slot")).toBeInTheDocument();
    expect(screen.getByTestId("fixture-cover-dark-slot")).toBeInTheDocument();
  });

  it("shows the empty placeholder in both slots and the preview when no URLs", () => {
    render(wrap("light", <ImagePairField {...baseProps()} />));
    expect(screen.getByTestId("fixture-cover-light-empty")).toBeInTheDocument();
    expect(screen.getByTestId("fixture-cover-dark-empty")).toBeInTheDocument();
    expect(
      screen.getByTestId("fixture-cover-preview-empty"),
    ).toBeInTheDocument();
  });

  it("calls onUpload with the light variant when the light slot input changes", async () => {
    const onUpload = vi.fn();
    const user = userEvent.setup();
    render(wrap("light", <ImagePairField {...baseProps({ onUpload })} />));

    const file = new File([new Uint8Array(8)], "x.jpg", { type: "image/jpeg" });
    await user.upload(screen.getByTestId("fixture-cover-light-input"), file);

    expect(onUpload).toHaveBeenCalledWith("light", file);
  });

  it("calls onUpload with the dark variant when the dark slot input changes", async () => {
    const onUpload = vi.fn();
    const user = userEvent.setup();
    render(wrap("light", <ImagePairField {...baseProps({ onUpload })} />));

    const file = new File([new Uint8Array(8)], "x.jpg", { type: "image/jpeg" });
    await user.upload(screen.getByTestId("fixture-cover-dark-input"), file);

    expect(onUpload).toHaveBeenCalledWith("dark", file);
  });

  it("ignores files with an unaccepted MIME type", async () => {
    const onUpload = vi.fn();
    const user = userEvent.setup();
    render(wrap("light", <ImagePairField {...baseProps({ onUpload })} />));

    const file = new File([new Uint8Array(8)], "x.gif", { type: "image/gif" });
    await user.upload(screen.getByTestId("fixture-cover-light-input"), file);

    expect(onUpload).not.toHaveBeenCalled();
  });

  it("calls onDelete with the matching variant when Remove is clicked", async () => {
    const onDelete = vi.fn();
    const user = userEvent.setup();
    render(
      wrap(
        "light",
        <ImagePairField
          {...baseProps({
            onDelete,
            lightUrl: "/api/v1/light.webp",
            darkUrl: "/api/v1/dark.webp",
          })}
        />,
      ),
    );

    await user.click(screen.getByTestId("fixture-cover-light-delete-button"));
    expect(onDelete).toHaveBeenCalledWith("light");

    await user.click(screen.getByTestId("fixture-cover-dark-delete-button"));
    expect(onDelete).toHaveBeenCalledWith("dark");
  });

  it("renders a theme-aware preview from the populated variants", () => {
    render(
      wrap(
        "dark",
        <ImagePairField
          {...baseProps({
            lightUrl: "/api/v1/light.webp",
            darkUrl: "/api/v1/dark.webp",
          })}
        />,
      ),
    );
    const preview = screen.getByTestId(
      "fixture-cover-preview-image",
    ) as HTMLImageElement;
    expect(preview.src).toContain("/api/v1/dark.webp");
  });

  it("hides the Remove button on an empty slot", () => {
    render(wrap("light", <ImagePairField {...baseProps()} />));
    expect(
      screen.queryByTestId("fixture-cover-light-delete-button"),
    ).not.toBeInTheDocument();
  });

  it("disables slot controls when disabled is true", () => {
    render(wrap("light", <ImagePairField {...baseProps({ disabled: true })} />));
    expect(
      screen.getByTestId("fixture-cover-light-upload-button"),
    ).toBeDisabled();
  });
});
