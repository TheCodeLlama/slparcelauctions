import { describe, it, expect, vi, beforeEach } from "vitest";
import { renderWithProviders, screen, fireEvent } from "@/test/render";
import userEvent from "@testing-library/user-event";
import { mockVerifiedCurrentUser } from "@/test/msw/fixtures";

// react-easy-crop hangs in jsdom waiting for ResizeObserver to fire so
// it can compute the crop area. Mock to a static stub. onCropComplete
// is fired in useEffect (NOT inline in the render body) so a parent
// setState doesn't trigger an infinite re-render loop.
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

import { ProfilePictureUploader } from "./ProfilePictureUploader";

const createObjectURLSpy = vi.fn(() => "blob:http://localhost/fake-preview");
const revokeObjectURLSpy = vi.fn();

beforeEach(() => {
  globalThis.URL.createObjectURL = createObjectURLSpy;
  globalThis.URL.revokeObjectURL = revokeObjectURLSpy;
  createObjectURLSpy.mockClear();
  revokeObjectURLSpy.mockClear();
});

function makeFile(
  name: string,
  type: string,
  sizeBytes: number,
): File {
  const buffer = new ArrayBuffer(sizeBytes);
  return new File([buffer], name, { type });
}

describe("ProfilePictureUploader", () => {
  it("renders drop zone in idle state", () => {
    renderWithProviders(
      <ProfilePictureUploader user={mockVerifiedCurrentUser} />,
    );

    expect(
      screen.getByText(/drag and drop or click to select/i),
    ).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /save/i })).not.toBeInTheDocument();
  });

  it("shows error for unsupported file type", async () => {
    renderWithProviders(
      <ProfilePictureUploader user={mockVerifiedCurrentUser} />,
    );

    const input = screen.getByTestId("avatar-file-input") as HTMLInputElement;
    const bmpFile = makeFile("test.bmp", "image/bmp", 1024);
    // fireEvent.change bypasses the accept attribute filter that
    // userEvent.upload respects, allowing the validation logic to run
    fireEvent.change(input, { target: { files: [bmpFile] } });

    expect(
      await screen.findByText(/unsupported file type/i),
    ).toBeInTheDocument();
  });

  it("valid file picks shows AvatarCropper with Save + Cancel", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <ProfilePictureUploader user={mockVerifiedCurrentUser} />,
    );

    const input = screen.getByTestId("avatar-file-input") as HTMLInputElement;
    const validFile = makeFile("avatar.png", "image/png", 1024);
    await user.upload(input, validFile);

    // After a valid pick the AvatarCropper takes over — the cropper
    // renders both Save and Cancel buttons.
    expect(await screen.findByTestId("avatar-cropper")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /save/i })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /cancel/i })).toBeInTheDocument();
  });

  it("cancel returns to idle", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <ProfilePictureUploader user={mockVerifiedCurrentUser} />,
    );

    const input = screen.getByTestId("avatar-file-input") as HTMLInputElement;
    const validFile = makeFile("avatar.png", "image/png", 1024);
    await user.upload(input, validFile);

    const cancelBtn = await screen.findByRole("button", { name: /cancel/i });
    await user.click(cancelBtn);

    expect(screen.queryByTestId("avatar-cropper")).not.toBeInTheDocument();
    expect(revokeObjectURLSpy).toHaveBeenCalled();
  });

  // The crop-and-upload save path runs through `getCroppedImg`, which
  // requires real Image / Canvas APIs. jsdom doesn't trigger
  // {@code <img>.onload} for blob URLs, so the cropper's
  // {@code croppedAreaPixels} stays null and Save is a no-op. The
  // dedicated {@code AvatarCropper.test.tsx} mocks getCroppedImg and
  // covers that path directly.
  it.todo("save crops + uploads via MSW (covered by AvatarCropper.test.tsx)");
});
