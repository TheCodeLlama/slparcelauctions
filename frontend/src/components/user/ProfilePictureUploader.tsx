"use client";

import { useCallback, useRef, useState, type DragEvent, type ChangeEvent } from "react";
import { Avatar } from "@/components/ui/Avatar";
import { Card } from "@/components/ui/Card";
import { Upload } from "@/components/ui/icons";
import { AvatarCropper } from "@/components/user/AvatarCropper";
import { useUploadAvatar } from "@/lib/user";
import type { CurrentUser } from "@/lib/user/api";

const ACCEPTED_TYPES = new Set([
  "image/jpeg",
  "image/png",
  "image/webp",
]);

type UploaderState =
  | { status: "idle" }
  | { status: "file-selected"; file: File; previewUrl: string }
  | { status: "uploading"; file: File; previewUrl: string }
  | { status: "error"; message: string };

type ProfilePictureUploaderProps = {
  user: CurrentUser;
};

export function ProfilePictureUploader({ user }: ProfilePictureUploaderProps) {
  const [state, setState] = useState<UploaderState>({ status: "idle" });
  const [dragOver, setDragOver] = useState(false);
  const fileInputRef = useRef<HTMLInputElement>(null);
  const uploadAvatar = useUploadAvatar();

  const validateAndSelect = useCallback((file: File) => {
    if (!ACCEPTED_TYPES.has(file.type)) {
      setState({
        status: "error",
        message: "Unsupported file type. Please upload a JPEG, PNG, or WebP image.",
      });
      return;
    }
    // No pre-upload size cap — the cropper downsamples to <=1024px WebP
    // before submission, so any reasonable source image yields a small
    // payload regardless of the original file size.
    const previewUrl = URL.createObjectURL(file);
    setState({ status: "file-selected", file, previewUrl });
  }, []);

  const handleDrop = useCallback(
    (e: DragEvent<HTMLDivElement>) => {
      e.preventDefault();
      setDragOver(false);
      const file = e.dataTransfer.files[0];
      if (file) validateAndSelect(file);
    },
    [validateAndSelect],
  );

  const handleDragOver = useCallback((e: DragEvent<HTMLDivElement>) => {
    e.preventDefault();
    setDragOver(true);
  }, []);

  const handleDragLeave = useCallback(() => {
    setDragOver(false);
  }, []);

  const handleFileChange = useCallback(
    (e: ChangeEvent<HTMLInputElement>) => {
      const file = e.target.files?.[0];
      if (file) validateAndSelect(file);
      // Reset input value so selecting the same file triggers onChange again
      e.target.value = "";
    },
    [validateAndSelect],
  );

  const handleCancel = useCallback(() => {
    if (state.status === "file-selected") {
      URL.revokeObjectURL(state.previewUrl);
    }
    setState({ status: "idle" });
  }, [state]);

  const handleSaveBlob = useCallback(
    async (blob: Blob) => {
      if (state.status !== "file-selected") return;
      const { previewUrl } = state;
      setState({ status: "uploading", file: state.file, previewUrl });
      try {
        const file = new File([blob], "avatar.webp", {
          type: blob.type || "image/webp",
        });
        await uploadAvatar.mutateAsync(file);
        URL.revokeObjectURL(previewUrl);
        setState({ status: "idle" });
      } catch {
        URL.revokeObjectURL(previewUrl);
        setState({
          status: "error",
          message: "Upload failed. Please try again.",
        });
      }
    },
    [state, uploadAvatar],
  );

  return (
    <Card>
      <Card.Header>
        <h2 className="text-sm font-semibold tracking-tight">Profile Picture</h2>
      </Card.Header>
      <Card.Body>
        <div className="flex flex-col items-center gap-4">
          {/* Current avatar / cropper. Once a file is picked, the
              cropper takes over until the user saves or cancels. */}
          {state.status === "file-selected" || state.status === "uploading" ? (
            <div className="w-full">
              <AvatarCropper
                imageSrc={state.previewUrl}
                onSave={handleSaveBlob}
                onCancel={handleCancel}
                saveLabel="Save"
              />
            </div>
          ) : (
            <Avatar
              src={user.profilePicUrl ?? undefined}
              alt={user.displayName ?? "Avatar"}
              name={user.displayName ?? undefined}
              size="xl"
              cacheBust={user.updatedAt}
            />
          )}

          {/* Drop zone — only when not currently picked / uploading. */}
          {state.status !== "file-selected" && state.status !== "uploading" && (
            <div
              role="button"
              tabIndex={0}
              onClick={() => fileInputRef.current?.click()}
              onKeyDown={(e) => {
                if (e.key === "Enter" || e.key === " ") {
                  e.preventDefault();
                  fileInputRef.current?.click();
                }
              }}
              onDrop={handleDrop}
              onDragOver={handleDragOver}
              onDragLeave={handleDragLeave}
              className={`flex w-full cursor-pointer flex-col items-center gap-2 rounded-lg border-2 border-dashed p-6 transition-colors ${
                dragOver
                  ? "border-brand bg-brand/5"
                  : "border-border-subtle hover:border-brand"
              }`}
            >
              <Upload className="size-6 text-fg-muted" aria-hidden="true" />
              <p className="text-xs text-fg-muted">
                Drag and drop or click to select
              </p>
              <p className="text-xs text-fg-muted">
                JPEG, PNG, or WebP. Max 2MB.
              </p>
            </div>
          )}

          <input
            ref={fileInputRef}
            type="file"
            accept="image/jpeg,image/png,image/webp"
            className="hidden"
            onChange={handleFileChange}
            data-testid="avatar-file-input"
          />

          {/* Error message */}
          {state.status === "error" && (
            <p role="alert" className="text-xs text-danger">
              {state.message}
            </p>
          )}
        </div>
      </Card.Body>
    </Card>
  );
}
