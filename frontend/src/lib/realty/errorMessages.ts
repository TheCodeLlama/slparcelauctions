import { isApiError } from "@/lib/api";

/**
 * Map realty-groups backend error codes to user-facing copy.
 *
 * Mirrors `adminListingErrorMessage` (see
 * `frontend/src/hooks/admin/useAdminListings.ts`): accept `unknown`,
 * narrow via `isApiError`, read `problem.code`. Codes match spec §5.7.
 *
 * For 410 GONE on a missing image (`REALTY_GROUP_IMAGE_NOT_FOUND` /
 * 404 from the byte endpoint) the UI handles the absence by rendering
 * a placeholder, so this helper falls through to the caller-provided
 * fallback rather than surfacing a confusing toast.
 */
export function realtyGroupErrorMessage(
  err: unknown,
  fallback: string,
): string {
  if (!isApiError(err)) return fallback;
  const problem = err.problem as {
    code?: string;
    cooldownEndsAt?: string;
  };
  const code = problem.code;
  switch (code) {
    case "REALTY_GROUP_NOT_FOUND":
      return "That realty group could not be found.";
    case "GROUP_NAME_TAKEN":
      return "That name is already in use.";
    case "GROUP_RENAME_COOLDOWN":
      return problem.cooldownEndsAt
        ? `Renames are limited to once every 30 days. Try again after ${problem.cooldownEndsAt}.`
        : "Renames are limited to once every 30 days.";
    case "SEAT_LIMIT_REACHED":
      return "This group has reached its member limit.";
    case "INVITATION_ALREADY_PENDING":
      return "There's already a pending invitation for this user.";
    case "INVITATION_EXPIRED":
      return "That invitation is no longer valid.";
    case "INVITATION_NOT_FOUND":
      return "Invitation not found.";
    case "LEADER_CANNOT_LEAVE":
      return "Transfer leadership or dissolve the group before leaving.";
    case "CANNOT_REMOVE_LEADER":
      return "The leader cannot be removed.";
    case "TRANSFER_TARGET_NOT_MEMBER":
      return "The new leader must already be a member of the group.";
    case "REALTY_GROUP_PERMISSION_DENIED":
      return "You don't have permission to do that in this group.";
    case "GROUP_DISSOLVED":
      return "This realty group has been dissolved.";
    case "INVALID_WEBSITE_URL":
      return "Please enter a valid URL (http or https).";
    case "ALREADY_MEMBER":
      return "That user is already a member of this group.";
    case "UNSUPPORTED_IMAGE_FORMAT":
      return "Unsupported image format. Use JPEG, PNG, or WebP.";
    case "REALTY_GROUP_IMAGE_NOT_FOUND":
      return fallback;
    default:
      return fallback;
  }
}
