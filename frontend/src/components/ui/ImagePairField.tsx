/* eslint-disable @next/next/no-img-element -- variant image bytes are API-served binary content */
"use client";

import { useCallback, useRef, type ChangeEvent } from "react";
import { Button } from "@/components/ui/Button";
import { ThemedImage } from "@/components/ui/ThemedImage";
import { apiUrl } from "@/lib/api/url";

const ACCEPTED_TYPES = new Set(["image/jpeg", "image/png", "image/webp"]);

export interface ImagePairFieldProps {
  /**
   * Logical surface for the test-id namespace. Test ids are derived as
   * {@code `${testIdPrefix}-${surface}-...`} so two pair fields on the
   * same screen never collide.
   */
  surface: string;
  /** Test-id namespace root (e.g. "group-profile" or "default-cover"). */
  testIdPrefix: string;
  /** Section heading rendered above the slot pair. */
  heading: string;
  /** Helper copy under the heading. */
  description: string;
  lightUrl: string | null;
  darkUrl: string | null;
  altPrefix: string;
  disabled: boolean;
  disabledTitle: string | undefined;
  /** Tailwind classes applied to the {@code <img>} inside a populated slot. */
  slotClassName: string;
  /** Tailwind classes applied to the empty-state placeholder. */
  emptyClassName: string;
  /** Tailwind classes applied to the theme-aware preview image. */
  previewClassName: string;
  onUpload: (variant: "light" | "dark", file: File) => void;
  onDelete: (variant: "light" | "dark") => void;
  uploadBusyLight: boolean;
  uploadBusyDark: boolean;
  deleteBusyLight: boolean;
  deleteBusyDark: boolean;
}

/**
 * Two side-by-side slots ("Light mode" + "Dark mode") plus a single preview
 * underneath that renders whichever variant matches the active theme via
 * {@link ThemedImage}. Each slot owns its own file input and uploads /
 * deletes its variant independently of the other.
 *
 * Shared by the realty group profile form (logo / cover / default listing
 * picture) and the user settings default-cover card (plan
 * {@code 2026-05-21-theme-image-variants}).
 */
export function ImagePairField({
  surface,
  testIdPrefix,
  heading,
  description,
  lightUrl,
  darkUrl,
  altPrefix,
  disabled,
  disabledTitle,
  slotClassName,
  emptyClassName,
  previewClassName,
  onUpload,
  onDelete,
  uploadBusyLight,
  uploadBusyDark,
  deleteBusyLight,
  deleteBusyDark,
}: ImagePairFieldProps) {
  const idBase = `${testIdPrefix}-${surface}`;
  return (
    <fieldset className="flex flex-col gap-3" disabled={disabled}>
      <legend className="text-xs font-medium text-fg-muted">
        {heading}
        <span className="ml-2 font-normal text-fg-subtle">{description}</span>
      </legend>

      <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
        <ImagePairSlot
          idBase={idBase}
          surface={surface}
          variant="light"
          label="Light mode"
          variantUrl={lightUrl}
          altPrefix={altPrefix}
          disabled={disabled}
          disabledTitle={disabledTitle}
          slotClassName={slotClassName}
          emptyClassName={emptyClassName}
          uploadBusy={uploadBusyLight}
          deleteBusy={deleteBusyLight}
          onUpload={(file) => onUpload("light", file)}
          onDelete={() => onDelete("light")}
        />
        <ImagePairSlot
          idBase={idBase}
          surface={surface}
          variant="dark"
          label="Dark mode"
          variantUrl={darkUrl}
          altPrefix={altPrefix}
          disabled={disabled}
          disabledTitle={disabledTitle}
          slotClassName={slotClassName}
          emptyClassName={emptyClassName}
          uploadBusy={uploadBusyDark}
          deleteBusy={deleteBusyDark}
          onUpload={(file) => onUpload("dark", file)}
          onDelete={() => onDelete("dark")}
        />
      </div>

      {/* Theme-aware preview: what visitors see at the active theme. */}
      <div className="flex flex-col gap-1.5" data-testid={`${idBase}-preview`}>
        <span className="text-[11px] font-medium uppercase tracking-wide text-fg-subtle">
          Preview (current theme)
        </span>
        {lightUrl || darkUrl ? (
          <ThemedImage
            lightSrc={lightUrl}
            darkSrc={darkUrl}
            alt={`${altPrefix} preview`}
            className={previewClassName}
            data-testid={`${idBase}-preview-image`}
          />
        ) : (
          <div
            className={emptyClassName}
            data-testid={`${idBase}-preview-empty`}
          >
            <span className="text-fg-subtle">No image</span>
          </div>
        )}
      </div>
    </fieldset>
  );
}

interface ImagePairSlotProps {
  idBase: string;
  surface: string;
  variant: "light" | "dark";
  label: string;
  variantUrl: string | null;
  altPrefix: string;
  disabled: boolean;
  disabledTitle: string | undefined;
  slotClassName: string;
  emptyClassName: string;
  uploadBusy: boolean;
  deleteBusy: boolean;
  onUpload: (file: File) => void;
  onDelete: () => void;
}

function ImagePairSlot({
  idBase,
  surface,
  variant,
  label,
  variantUrl,
  altPrefix,
  disabled,
  disabledTitle,
  slotClassName,
  emptyClassName,
  uploadBusy,
  deleteBusy,
  onUpload,
  onDelete,
}: ImagePairSlotProps) {
  const inputRef = useRef<HTMLInputElement>(null);
  const testidBase = `${idBase}-${variant}`;

  const handlePick = useCallback(
    (e: ChangeEvent<HTMLInputElement>) => {
      const file = e.target.files?.[0];
      e.target.value = "";
      if (!file) return;
      if (!ACCEPTED_TYPES.has(file.type)) return;
      onUpload(file);
    },
    [onUpload],
  );

  return (
    <div className="flex flex-col gap-2" data-testid={`${testidBase}-slot`}>
      <span className="text-[11px] font-medium uppercase tracking-wide text-fg-subtle">
        {label}
      </span>
      {variantUrl ? (
        <img
          src={apiUrl(variantUrl) ?? undefined}
          alt={`${altPrefix} (${variant} mode)`}
          className={slotClassName}
          data-testid={`${testidBase}-image`}
        />
      ) : (
        <div className={emptyClassName} data-testid={`${testidBase}-empty`}>
          <span className="text-fg-subtle text-xs">No image</span>
        </div>
      )}
      <div className="flex flex-wrap items-center gap-2">
        <Button
          type="button"
          variant="secondary"
          size="sm"
          onClick={() => inputRef.current?.click()}
          disabled={disabled || uploadBusy || deleteBusy}
          loading={uploadBusy}
          title={disabledTitle}
          data-testid={`${testidBase}-upload-button`}
        >
          {variantUrl ? "Replace" : "Upload"}
        </Button>
        {variantUrl && (
          <Button
            type="button"
            variant="tertiary"
            size="sm"
            onClick={() => onDelete()}
            disabled={disabled || uploadBusy || deleteBusy}
            loading={deleteBusy}
            title={disabledTitle}
            data-testid={`${testidBase}-delete-button`}
          >
            Remove
          </Button>
        )}
      </div>
      <input
        ref={inputRef}
        type="file"
        accept="image/jpeg,image/png,image/webp"
        className="hidden"
        aria-label={`Upload ${variant} mode ${surface}`}
        onChange={handlePick}
        data-testid={`${testidBase}-input`}
      />
    </div>
  );
}
