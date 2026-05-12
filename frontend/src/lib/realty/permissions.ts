import type { RealtyGroupPermission } from "@/types/realty";

/**
 * Canonical ordering of all realty-group permissions for UI surfaces
 * (invite form, member permission editor). Matches the backend enum
 * declaration order. New permission values land in their own slice
 * (C/D/E/F) — extend this array in the same change.
 */
export const ALL_PERMISSIONS: readonly RealtyGroupPermission[] = [
  "INVITE_AGENTS",
  "REMOVE_AGENTS",
  "EDIT_GROUP_PROFILE",
  "CONFIGURE_FEES",
  "CREATE_LISTING",
  "MANAGE_ALL_LISTINGS",
  "WITHDRAW_FROM_GROUP_WALLET",
  "VIEW_GROUP_TRANSACTIONS",
  "REGISTER_SL_GROUP",
  "MANAGE_MEMBERS",
] as const;

/**
 * Short label rendered next to the toggle. Title-case, no trailing
 * punctuation. Kept stable so screen-reader announcements don't shift
 * between renders.
 */
export function permissionLabel(p: RealtyGroupPermission): string {
  switch (p) {
    case "INVITE_AGENTS":
      return "Invite agents";
    case "REMOVE_AGENTS":
      return "Remove agents";
    case "EDIT_GROUP_PROFILE":
      return "Edit group profile";
    case "CONFIGURE_FEES":
      return "Configure fees";
    case "CREATE_LISTING":
      return "Create listings";
    case "MANAGE_ALL_LISTINGS":
      return "Manage all listings";
    case "WITHDRAW_FROM_GROUP_WALLET":
      return "Withdraw from group wallet";
    case "VIEW_GROUP_TRANSACTIONS":
      return "View group transactions";
    case "REGISTER_SL_GROUP":
      return "Register SL groups";
    case "MANAGE_MEMBERS":
      return "Manage members";
  }
}

/**
 * One-line description shown beneath the label. Plain prose — used as
 * the help text on permission toggles in the invite form and the member
 * permission editor.
 */
export function permissionDescription(p: RealtyGroupPermission): string {
  switch (p) {
    case "INVITE_AGENTS":
      return "Send invitations to new agents and revoke pending ones.";
    case "REMOVE_AGENTS":
      return "Remove existing agents from the group.";
    case "EDIT_GROUP_PROFILE":
      return "Update the group's name, description, logo, cover, and website.";
    case "CONFIGURE_FEES":
      return "Adjust the agent fee rate and split.";
    case "CREATE_LISTING":
      return "Publish auction listings on behalf of this group.";
    case "MANAGE_ALL_LISTINGS":
      return "Cancel any group listing, regardless of who created it.";
    case "WITHDRAW_FROM_GROUP_WALLET":
      return "Initiate withdrawals from the group wallet to a Linden Lab account.";
    case "VIEW_GROUP_TRANSACTIONS":
      return "View the group wallet balance and ledger history.";
    case "REGISTER_SL_GROUP":
      return "Register and unregister the in-world SL groups this realty group manages.";
    case "MANAGE_MEMBERS":
      return "Bulk-edit per-member commission rates and view per-member commission analytics.";
  }
}
