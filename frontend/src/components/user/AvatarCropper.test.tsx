import { describe, it, expect, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";

// Mock react-easy-crop: render a stub <Cropper> that fires
// onCropComplete once on mount via useEffect (firing inside the render
// body would create an infinite re-render loop because the parent
// setState would re-render the mock, which would call onCropComplete
// again, ad infinitum).
import { useEffect } from "react";

function MockCropper({
  onCropComplete,
}: {
  onCropComplete: (a: unknown, b: unknown) => void;
}) {
  useEffect(() => {
    onCropComplete({}, { x: 0, y: 0, width: 100, height: 100 });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);
  return <div data-testid="mock-cropper" />;
}

vi.mock("react-easy-crop", () => ({
  __esModule: true,
  default: MockCropper,
}));

// getCroppedImg uses real DOM Image / Canvas APIs that aren't reliable
// in jsdom. Mock it to a Blob factory that exercises the parent's
// onSave wiring.
vi.mock("@/lib/avatar/cropImage", () => ({
  getCroppedImg: vi.fn(async () => new Blob(["x"], { type: "image/png" })),
}));

import { AvatarCropper } from "./AvatarCropper";

describe("AvatarCropper", () => {
  it("Save button calls onSave with the cropped Blob", async () => {
    const user = userEvent.setup();
    const onSave = vi.fn();

    render(
      <AvatarCropper imageSrc="blob:http://localhost/test" onSave={onSave} />,
    );

    const save = await screen.findByRole("button", { name: /save/i });
    await user.click(save);

    expect(onSave).toHaveBeenCalledTimes(1);
    const arg = onSave.mock.calls[0][0];
    expect(arg).toBeInstanceOf(Blob);
  });

  it("Cancel calls onCancel", async () => {
    const user = userEvent.setup();
    const onCancel = vi.fn();

    render(
      <AvatarCropper
        imageSrc="blob:http://localhost/test"
        onSave={vi.fn()}
        onCancel={onCancel}
      />,
    );

    const cancel = await screen.findByRole("button", { name: /cancel/i });
    await user.click(cancel);

    expect(onCancel).toHaveBeenCalledTimes(1);
  });

  it("when getCroppedImg throws, surfaces the error state", async () => {
    const { getCroppedImg } = await import("@/lib/avatar/cropImage");
    (getCroppedImg as unknown as ReturnType<typeof vi.fn>).mockRejectedValueOnce(
      new Error("boom"),
    );
    const user = userEvent.setup();
    const onSave = vi.fn();

    render(
      <AvatarCropper
        imageSrc="blob:http://localhost/test"
        onSave={onSave}
        onCancel={vi.fn()}
      />,
    );

    const save = await screen.findByRole("button", { name: /save/i });
    await user.click(save);

    expect(await screen.findByTestId("avatar-cropper-error")).toBeInTheDocument();
    expect(onSave).not.toHaveBeenCalled();
  });
});
