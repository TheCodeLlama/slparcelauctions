/* eslint-disable @next/next/no-img-element -- logo/cover bytes are API-served binary content */
"use client";

import { useCallback, useRef, type ChangeEvent } from "react";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { z } from "zod";
import { Button } from "@/components/ui/Button";
import { Card } from "@/components/ui/Card";
import { Input } from "@/components/ui/Input";
import { ThemedImage } from "@/components/ui/ThemedImage";
import {
  useDeleteCover,
  useDeleteLogo,
  useUpdateGroup,
  useUploadCover,
  useUploadLogo,
} from "@/hooks/realty/useRealtyGroups";
import { apiUrl } from "@/lib/api/url";
import { cn } from "@/lib/cn";
import type {
  RealtyGroupPermission,
  RealtyGroupPublicDto,
} from "@/types/realty";

const ACCEPTED_TYPES = new Set(["image/jpeg", "image/png", "image/webp"]);

const profileSchema = z.object({
  name: z
    .string()
    .min(1, "Name is required")
    .max(64, "Name must be 64 characters or fewer"),
  description: z
    .string()
    .max(2000, "Description must be 2000 characters or fewer")
    .optional()
    .or(z.literal("")),
  website: z
    .string()
    .url("Enter a valid URL")
    .optional()
    .or(z.literal("")),
});

type ProfileFormValues = z.infer<typeof profileSchema>;

export interface GroupProfileFormProps {
  group: RealtyGroupPublicDto;
  /** Set of permissions the caller has on this group. Leader has all-implicit. */
  callerPermissions: Set<RealtyGroupPermission>;
  /** True when the caller is the leader; bypasses every disabled gate. */
  isLeader: boolean;
}

/**
 * Profile-tab form for `/dashboard/groups/[slug]/manage`. Edits:
 * name, description, website, and the logo/cover image pair, gated by
 * {@code EDIT_GROUP_PROFILE}. The leader has the permission implicitly.
 * Disabled inputs render with a tooltip ({@code title} attribute) so a
 * caller can see why a field is locked.
 *
 * Logo + cover render as paired light/dark slots (plan
 * `2026-05-21-theme-image-variants`). Each variant is uploaded and
 * deleted independently; a single preview below the pair uses
 * {@link ThemedImage} so the admin can see what visitors will see at the
 * current theme.
 */
export function GroupProfileForm({
  group,
  callerPermissions,
  isLeader,
}: GroupProfileFormProps) {
  const updateGroup = useUpdateGroup();
  const uploadLogo = useUploadLogo();
  const deleteLogo = useDeleteLogo();
  const uploadCover = useUploadCover();
  const deleteCover = useDeleteCover();

  const canEditProfile =
    isLeader || callerPermissions.has("EDIT_GROUP_PROFILE");

  const profileLockedTitle = canEditProfile
    ? undefined
    : "Requires the Edit group profile permission.";

  const {
    register,
    handleSubmit,
    reset,
    formState: { errors, isDirty, isSubmitting },
  } = useForm<ProfileFormValues>({
    resolver: zodResolver(profileSchema),
    defaultValues: {
      name: group.name,
      description: group.description ?? "",
      website: group.website ?? "",
    },
  });

  const onSubmit = async (values: ProfileFormValues) => {
    if (!canEditProfile) return;
    const body = {
      name: values.name?.trim() || undefined,
      description: values.description ? values.description.trim() : undefined,
      website: values.website ? values.website.trim() : undefined,
    };
    try {
      const updated = await updateGroup.mutateAsync({
        publicId: group.publicId,
        body,
      });
      reset({
        name: updated.name,
        description: updated.description ?? "",
        website: updated.website ?? "",
      });
    } catch {
      // Mutation hook handles the error toast; swallow rethrow here.
    }
  };

  return (
    <Card>
      <Card.Header>
        <h2 className="text-sm font-semibold tracking-tight">Group profile</h2>
      </Card.Header>
      <Card.Body>
        <form
          onSubmit={handleSubmit(onSubmit)}
          className="flex flex-col gap-5"
          aria-label="Group profile"
        >
          {/* Logo: light + dark slot pair, with a single theme-aware preview below. */}
          <ImagePairField
            surface="logo"
            heading="Logo"
            description="A square or wide logo for chips and member lists."
            lightUrl={group.logoLightUrl}
            darkUrl={group.logoDarkUrl}
            altPrefix={`${group.name} logo`}
            disabled={!canEditProfile}
            disabledTitle={profileLockedTitle}
            slotClassName="h-20 w-auto max-w-[12rem] rounded border border-border bg-surface-raised object-contain"
            emptyClassName="inline-flex h-20 w-20 items-center justify-center rounded border border-border bg-info-bg text-xs font-semibold text-info"
            previewClassName="h-20 w-auto max-w-[12rem] rounded border border-border bg-surface-raised object-contain"
            onUpload={(variant, file) =>
              uploadLogo.mutate({ publicId: group.publicId, variant, file })
            }
            onDelete={(variant) =>
              deleteLogo.mutate({ publicId: group.publicId, variant })
            }
            uploadBusyLight={
              uploadLogo.isPending &&
              uploadLogo.variables?.variant === "light"
            }
            uploadBusyDark={
              uploadLogo.isPending && uploadLogo.variables?.variant === "dark"
            }
            deleteBusyLight={
              deleteLogo.isPending &&
              deleteLogo.variables?.variant === "light"
            }
            deleteBusyDark={
              deleteLogo.isPending && deleteLogo.variables?.variant === "dark"
            }
          />

          {/* Cover: same dual-slot pattern as logo. */}
          <ImagePairField
            surface="cover"
            heading="Cover"
            description="Hero banner shown on the public group page."
            lightUrl={group.coverLightUrl}
            darkUrl={group.coverDarkUrl}
            altPrefix={`${group.name} cover`}
            disabled={!canEditProfile}
            disabledTitle={profileLockedTitle}
            slotClassName="aspect-[16/5] w-full rounded border border-border bg-bg-hover object-contain"
            emptyClassName="aspect-[16/5] w-full rounded border border-border bg-bg-hover"
            previewClassName="aspect-[16/5] w-full rounded border border-border bg-bg-hover object-contain"
            onUpload={(variant, file) =>
              uploadCover.mutate({ publicId: group.publicId, variant, file })
            }
            onDelete={(variant) =>
              deleteCover.mutate({ publicId: group.publicId, variant })
            }
            uploadBusyLight={
              uploadCover.isPending &&
              uploadCover.variables?.variant === "light"
            }
            uploadBusyDark={
              uploadCover.isPending &&
              uploadCover.variables?.variant === "dark"
            }
            deleteBusyLight={
              deleteCover.isPending &&
              deleteCover.variables?.variant === "light"
            }
            deleteBusyDark={
              deleteCover.isPending &&
              deleteCover.variables?.variant === "dark"
            }
          />

          <Input
            label="Name"
            error={errors.name?.message}
            disabled={!canEditProfile}
            title={profileLockedTitle}
            data-testid="group-profile-name"
            {...register("name")}
          />

          <div className="flex flex-col gap-1">
            <label
              htmlFor="group-profile-description"
              className="text-xs font-medium text-fg-muted"
            >
              Description
            </label>
            <textarea
              id="group-profile-description"
              rows={4}
              disabled={!canEditProfile}
              title={profileLockedTitle}
              className={cn(
                "w-full rounded-lg bg-bg-subtle text-fg placeholder:text-fg-muted px-4 py-3 ring-1 ring-transparent transition-all focus:bg-surface-raised focus:outline-none focus:ring-brand resize-none",
                !canEditProfile && "opacity-60 cursor-not-allowed",
              )}
              aria-invalid={errors.description ? true : undefined}
              data-testid="group-profile-description"
              {...register("description")}
            />
            {errors.description?.message && (
              <span className="text-xs text-danger">
                {errors.description.message}
              </span>
            )}
          </div>

          <Input
            label="Website"
            placeholder="https://example.com"
            error={errors.website?.message}
            disabled={!canEditProfile}
            title={profileLockedTitle}
            data-testid="group-profile-website"
            {...register("website")}
          />

          <div className="flex justify-end">
            <Button
              type="submit"
              variant="primary"
              loading={isSubmitting || updateGroup.isPending}
              disabled={
                !isDirty ||
                isSubmitting ||
                updateGroup.isPending ||
                !canEditProfile
              }
              data-testid="group-profile-submit"
            >
              Save changes
            </Button>
          </div>
        </form>
      </Card.Body>
    </Card>
  );
}

// ─── Dual-slot upload field ────────────────────────────────────────────────

interface ImagePairFieldProps {
  /** Logical surface for the test-id namespace ("logo" / "cover"). */
  surface: "logo" | "cover";
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
 */
function ImagePairField({
  surface,
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
  return (
    <fieldset className="flex flex-col gap-3" disabled={disabled}>
      <legend className="text-xs font-medium text-fg-muted">
        {heading}
        <span className="ml-2 font-normal text-fg-subtle">{description}</span>
      </legend>

      <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
        <ImagePairSlot
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
      <div
        className="flex flex-col gap-1.5"
        data-testid={`group-profile-${surface}-preview`}
      >
        <span className="text-[11px] font-medium uppercase tracking-wide text-fg-subtle">
          Preview (current theme)
        </span>
        {lightUrl || darkUrl ? (
          <ThemedImage
            lightSrc={lightUrl}
            darkSrc={darkUrl}
            alt={`${altPrefix} preview`}
            className={previewClassName}
            data-testid={`group-profile-${surface}-preview-image`}
          />
        ) : (
          <div
            className={emptyClassName}
            data-testid={`group-profile-${surface}-preview-empty`}
          >
            <span className="text-fg-subtle">No image</span>
          </div>
        )}
      </div>
    </fieldset>
  );
}

interface ImagePairSlotProps {
  surface: "logo" | "cover";
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
  const testidBase = `group-profile-${surface}-${variant}`;

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
        <div
          className={emptyClassName}
          data-testid={`${testidBase}-empty`}
        >
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
