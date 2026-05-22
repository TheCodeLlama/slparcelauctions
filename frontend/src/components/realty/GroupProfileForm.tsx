"use client";

import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { z } from "zod";
import { Button } from "@/components/ui/Button";
import { Card } from "@/components/ui/Card";
import { Input } from "@/components/ui/Input";
import { ImagePairField } from "@/components/ui/ImagePairField";
import {
  useDeleteCover,
  useDeleteDefaultListing,
  useDeleteLogo,
  useUpdateGroup,
  useUploadCover,
  useUploadDefaultListing,
  useUploadLogo,
} from "@/hooks/realty/useRealtyGroups";
import { cn } from "@/lib/cn";
import type {
  RealtyGroupPermission,
  RealtyGroupPublicDto,
} from "@/types/realty";

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
 * name, description, website, and the logo / cover / default-listing
 * image pairs, gated by {@code EDIT_GROUP_PROFILE}. The leader has the
 * permission implicitly. Disabled inputs render with a tooltip
 * ({@code title} attribute) so a caller can see why a field is locked.
 *
 * Logo, cover, and default listing picture render as paired light/dark
 * slots (plan `2026-05-21-theme-image-variants`). Each variant is
 * uploaded and deleted independently; a single preview below each pair
 * uses {@link ThemedImage} so the admin can see what visitors will see
 * at the current theme. The default listing picture seeds the sort-0
 * photo on auctions created on behalf of the group.
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
  const uploadDefaultListing = useUploadDefaultListing();
  const deleteDefaultListing = useDeleteDefaultListing();

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
            testIdPrefix="group-profile"
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
            testIdPrefix="group-profile"
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

          {/* Default listing picture: same dual-slot pattern as cover. */}
          <ImagePairField
            surface="default-listing"
            testIdPrefix="group-profile"
            heading="Default listing picture"
            description="Used as the first photo on every listing created on behalf of this group. Light and dark variants are optional - if you upload only one, it will be used in both themes."
            lightUrl={group.defaultListingLightUrl}
            darkUrl={group.defaultListingDarkUrl}
            altPrefix={`${group.name} default listing picture`}
            disabled={!canEditProfile}
            disabledTitle={profileLockedTitle}
            slotClassName="aspect-[4/3] w-full rounded border border-border bg-bg-hover object-contain"
            emptyClassName="aspect-[4/3] w-full rounded border border-border bg-bg-hover"
            previewClassName="aspect-[4/3] w-full rounded border border-border bg-bg-hover object-contain"
            onUpload={(variant, file) =>
              uploadDefaultListing.mutate({
                publicId: group.publicId,
                variant,
                file,
              })
            }
            onDelete={(variant) =>
              deleteDefaultListing.mutate({
                publicId: group.publicId,
                variant,
              })
            }
            uploadBusyLight={
              uploadDefaultListing.isPending &&
              uploadDefaultListing.variables?.variant === "light"
            }
            uploadBusyDark={
              uploadDefaultListing.isPending &&
              uploadDefaultListing.variables?.variant === "dark"
            }
            deleteBusyLight={
              deleteDefaultListing.isPending &&
              deleteDefaultListing.variables?.variant === "light"
            }
            deleteBusyDark={
              deleteDefaultListing.isPending &&
              deleteDefaultListing.variables?.variant === "dark"
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
