import { describe, it, expect, vi, beforeEach } from "vitest";
import { renderWithProviders, screen, waitFor, fireEvent } from "@/test/render";
import userEvent from "@testing-library/user-event";
import { server } from "@/test/msw/server";
import { userHandlers } from "@/test/msw/handlers";
import { mockVerifiedCurrentUser } from "@/test/msw/fixtures";
import { ProfilePictureUploader } from "./ProfilePictureUploader";

function setup() {
  server.use(userHandlers.uploadAvatarSuccess());
}

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

  it("shows error for oversized file", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <ProfilePictureUploader user={mockVerifiedCurrentUser} />,
    );

    const input = screen.getByTestId("avatar-file-input") as HTMLInputElement;
    const bigFile = makeFile("big.png", "image/png", 3 * 1024 * 1024);
    await user.upload(input, bigFile);

    expect(
      await screen.findByText(/file is too large/i),
    ).toBeInTheDocument();
  });

  it("valid file transitions to preview with Save button", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <ProfilePictureUploader user={mockVerifiedCurrentUser} />,
    );

    const input = screen.getByTestId("avatar-file-input") as HTMLInputElement;
    const validFile = makeFile("avatar.png", "image/png", 1024);
    await user.upload(input, validFile);

    expect(
      await screen.findByRole("button", { name: /save/i }),
    ).toBeInTheDocument();
    expect(
      screen.getByRole("button", { name: /cancel/i }),
    ).toBeInTheDocument();
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

    expect(screen.queryByRole("button", { name: /save/i })).not.toBeInTheDocument();
    expect(revokeObjectURLSpy).toHaveBeenCalled();
  });

  it("save uploads via MSW and returns to idle on success", async () => {
    setup();
    const user = userEvent.setup();
    renderWithProviders(
      <ProfilePictureUploader user={mockVerifiedCurrentUser} />,
      { auth: "authenticated" },
    );

    const input = screen.getByTestId("avatar-file-input") as HTMLInputElement;
    const validFile = makeFile("avatar.png", "image/png", 1024);
    await user.upload(input, validFile);

    const saveBtn = await screen.findByRole("button", { name: /save/i });
    await user.click(saveBtn);

    await waitFor(() => {
      expect(
        screen.queryByRole("button", { name: /save/i }),
      ).not.toBeInTheDocument();
    });
    expect(revokeObjectURLSpy).toHaveBeenCalled();
  });
});
