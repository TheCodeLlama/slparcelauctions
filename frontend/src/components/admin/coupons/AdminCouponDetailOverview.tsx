"use client";
import { Card } from "@/components/ui/Card";
import type { CouponDiscountDto, CouponDto } from "@/types/coupon";

type Props = {
  coupon: CouponDto;
  totalGrants: number;
  activeGrants: number;
};

function formatDiscount(d: CouponDiscountDto): string {
  const target = d.target === "LISTING_FEE" ? "Listing fee" : "Commission";
  switch (d.op) {
    case "OVERRIDE":
      if (d.target === "LISTING_FEE") return `${target}: L$ ${d.value}`;
      return `${target}: ${d.value}`;
    case "PERCENT_OFF":
      return `${target}: -${d.value}%`;
    case "FLAT_OFF":
      return `${target}: -L$ ${d.value}`;
  }
}

function formatDate(iso: string | null, options?: Intl.DateTimeFormatOptions) {
  if (iso === null) return null;
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return null;
  return d.toLocaleString(
    "en-US",
    options ?? {
      month: "short",
      day: "numeric",
      year: "numeric",
      hour: "2-digit",
      minute: "2-digit",
    },
  );
}

function formatDateOnly(iso: string | null) {
  return formatDate(iso, { month: "short", day: "numeric", year: "numeric" });
}

interface FieldProps {
  label: string;
  value: React.ReactNode;
  testid?: string;
}

function Field({ label, value, testid }: FieldProps) {
  return (
    <div
      className="flex flex-col gap-0.5"
      data-testid={testid}
    >
      <span className="text-[10px] font-medium uppercase tracking-wide text-fg-muted">
        {label}
      </span>
      <span className="text-sm text-fg">{value}</span>
    </div>
  );
}

const NONE = <span className="text-fg-muted">(none)</span>;
const UNLIMITED = <span className="text-fg-muted">unlimited</span>;
const NEVER = <span className="text-fg-muted">never</span>;
const ANY_USER = <span className="text-fg-muted">any signed-in user</span>;

export function AdminCouponDetailOverview({
  coupon,
  totalGrants,
  activeGrants,
}: Props) {
  const allowedUsers = coupon.allowedUserPublicIds ?? [];
  const previewIds = allowedUsers.slice(0, 3);
  const extraCount = allowedUsers.length - previewIds.length;

  return (
    <div
      className="grid grid-cols-1 gap-4 lg:grid-cols-2"
      data-testid="coupon-overview-tab"
    >
      <Card>
        <Card.Header>
          <h2 className="text-sm font-semibold tracking-tight text-fg">
            Identity and lifetime
          </h2>
        </Card.Header>
        <Card.Body>
          <div className="grid grid-cols-2 gap-4">
            <Field
              label="Code"
              value={<span className="font-mono">{coupon.code}</span>}
              testid="overview-code"
            />
            <Field
              label="Status"
              value={coupon.active ? "Active" : "Inactive"}
              testid="overview-active"
            />
            <Field
              label="Description"
              value={coupon.description ?? NONE}
              testid="overview-description"
            />
            <Field
              label="Notify on grant"
              value={coupon.notifyOnGrant ? "Yes" : "No"}
              testid="overview-notify"
            />
            <Field
              label="Duration (days)"
              value={coupon.durationDays ?? NONE}
              testid="overview-duration-days"
            />
            <Field
              label="Use count"
              value={coupon.useCount ?? NONE}
              testid="overview-use-count"
            />
            <Field
              label="Max per user"
              value={coupon.maxPerUser}
              testid="overview-max-per-user"
            />
            <Field
              label="Max total redemptions"
              value={coupon.maxTotalRedemptions ?? UNLIMITED}
              testid="overview-max-total"
            />
            <Field
              label="Redeemable until"
              value={formatDate(coupon.redeemableUntil) ?? NEVER}
              testid="overview-redeemable-until"
            />
            <Field
              label="Created"
              value={formatDate(coupon.createdAt) ?? ""}
              testid="overview-created-at"
            />
          </div>
        </Card.Body>
      </Card>

      <Card>
        <Card.Header>
          <h2 className="text-sm font-semibold tracking-tight text-fg">
            Discount bundle
          </h2>
        </Card.Header>
        <Card.Body>
          {coupon.discounts.length === 0 ? (
            <p className="text-sm text-fg-muted">(no discounts configured)</p>
          ) : (
            <ul
              className="flex flex-col gap-2"
              data-testid="overview-discounts"
            >
              {coupon.discounts.map((d, i) => (
                <li
                  key={`${d.target}-${d.op}-${i}`}
                  className="flex items-center justify-between rounded-lg border border-border-subtle bg-bg-subtle px-3 py-2 text-sm"
                  data-testid={`overview-discount-${i}`}
                >
                  <span className="text-fg">{formatDiscount(d)}</span>
                  <span className="text-[10px] uppercase tracking-wide text-fg-muted">
                    {d.op}
                  </span>
                </li>
              ))}
            </ul>
          )}
        </Card.Body>
      </Card>

      <Card>
        <Card.Header>
          <h2 className="text-sm font-semibold tracking-tight text-fg">
            Auto-grant signup window
          </h2>
        </Card.Header>
        <Card.Body>
          <div className="grid grid-cols-2 gap-4">
            <Field
              label="Start"
              value={
                formatDateOnly(coupon.signupWindowStart) ?? (
                  <span className="text-fg-muted">(disabled)</span>
                )
              }
              testid="overview-signup-start"
            />
            <Field
              label="End"
              value={
                formatDateOnly(coupon.signupWindowEnd) ?? (
                  <span className="text-fg-muted">(disabled)</span>
                )
              }
              testid="overview-signup-end"
            />
          </div>
          <p className="mt-3 text-[11px] text-fg-muted">
            Both dates must be set together to enable signup-window auto-grant.
          </p>
        </Card.Body>
      </Card>

      <Card>
        <Card.Header>
          <h2 className="text-sm font-semibold tracking-tight text-fg">
            Audience and totals
          </h2>
        </Card.Header>
        <Card.Body>
          <div className="grid grid-cols-2 gap-4 mb-4">
            <Field
              label="Total grants"
              value={
                <span
                  className="font-mono"
                  data-testid="overview-total-grants"
                >
                  {totalGrants}
                </span>
              }
            />
            <Field
              label="Active grants"
              value={
                <span
                  className="font-mono"
                  data-testid="overview-active-grants"
                >
                  {activeGrants}
                </span>
              }
            />
          </div>
          <Field
            label="Allowed users"
            value={
              allowedUsers.length === 0 ? (
                ANY_USER
              ) : (
                <span data-testid="overview-allowlist">
                  <span className="font-mono text-fg">
                    {allowedUsers.length}
                  </span>{" "}
                  user{allowedUsers.length === 1 ? "" : "s"}
                  {previewIds.length > 0 && (
                    <span className="mt-1 block text-[11px] text-fg-muted">
                      {previewIds.join(", ")}
                      {extraCount > 0 ? ` (+${extraCount} more)` : ""}
                    </span>
                  )}
                </span>
              )
            }
          />
        </Card.Body>
      </Card>
    </div>
  );
}
