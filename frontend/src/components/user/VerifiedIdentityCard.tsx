"use client";

import { BadgeCheck } from "@/components/ui/icons";
import { Card } from "@/components/ui/Card";
import { StatusBadge } from "@/components/ui/StatusBadge";
import type { CurrentUser, PublicUserProfile } from "@/lib/user/api";

const PAY_INFO_LABELS: Record<number, string> = {
  0: "No payment info",
  1: "Payment info on file",
  2: "Payment info used",
  3: "Verified payment",
};

type DashboardUser = Pick<
  CurrentUser,
  | "slAvatarName"
  | "slDisplayName"
  | "slBornDate"
  | "slPayinfo"
  | "verifiedAt"
  | "verified"
>;

type PublicUser = Pick<
  PublicUserProfile,
  "slAvatarName" | "slDisplayName" | "verified"
>;

type VerifiedIdentityCardProps =
  | { user: DashboardUser; variant: "dashboard" }
  | { user: PublicUser; variant: "public" };

export function calculateAccountAge(bornDate: string): string {
  const born = new Date(bornDate);
  const now = new Date();

  let years = now.getFullYear() - born.getFullYear();
  let months = now.getMonth() - born.getMonth();

  if (months < 0) {
    years -= 1;
    months += 12;
  }

  if (now.getDate() < born.getDate()) {
    months -= 1;
    if (months < 0) {
      years -= 1;
      months += 12;
    }
  }

  const totalMonths = years * 12 + months;

  if (totalMonths < 12) {
    return `${totalMonths} month${totalMonths === 1 ? "" : "s"}`;
  }
  return `${years} year${years === 1 ? "" : "s"}`;
}

export function VerifiedIdentityCard(props: VerifiedIdentityCardProps) {
  const { user, variant } = props;

  return (
    <Card>
      <Card.Header>
        <div className="flex items-center gap-2">
          <h2 className="text-sm font-semibold tracking-tight">Second Life Identity</h2>
          {user.verified && (
            <BadgeCheck className="size-5 text-brand" aria-hidden="true" />
          )}
        </div>
      </Card.Header>
      <Card.Body>
        <div className="flex flex-col gap-3">
          {user.slAvatarName && (
            <p className="text-base font-medium">{user.slAvatarName}</p>
          )}
          {user.slDisplayName &&
            user.slDisplayName !== user.slAvatarName && (
              <p className="text-sm text-fg-muted">
                {user.slDisplayName}
              </p>
            )}

          {variant === "dashboard" && (
            <>
              {(props.user as DashboardUser).slBornDate && (
                <p className="text-xs text-fg-muted">
                  Account age:{" "}
                  {calculateAccountAge(
                    (props.user as DashboardUser).slBornDate!,
                  )}
                </p>
              )}
              {(props.user as DashboardUser).slPayinfo != null && (
                <StatusBadge tone="default">
                  {PAY_INFO_LABELS[
                    (props.user as DashboardUser).slPayinfo!
                  ] ?? "Unknown"}
                </StatusBadge>
              )}
              {(props.user as DashboardUser).verifiedAt && (
                <p className="text-xs text-fg-muted">
                  Verified:{" "}
                  {new Date(
                    (props.user as DashboardUser).verifiedAt!,
                  ).toLocaleDateString()}
                </p>
              )}
            </>
          )}
        </div>
      </Card.Body>
    </Card>
  );
}
