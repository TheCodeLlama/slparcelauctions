"use client";
import { useState, type FormEvent, type ReactNode } from "react";
import { ExternalLink, Plus, Trash2 } from "@/components/ui/icons";
import { Button } from "@/components/ui/Button";
import { Card } from "@/components/ui/Card";
import { Checkbox } from "@/components/ui/Checkbox";
import { Input } from "@/components/ui/Input";
import { Modal } from "@/components/ui/Modal";
import { UserSearchAutocomplete } from "@/components/admin/bans/UserSearchAutocomplete";
import { useCreateAdminCoupon } from "@/hooks/admin/useCreateAdminCoupon";
import { isApiError } from "@/lib/api";
import type {
  CouponDiscountDto,
  CreateCouponRequest,
  DiscountOp,
  DiscountTarget,
} from "@/types/coupon";
import type { AdminUserSummary } from "@/lib/admin/types";

/**
 * Admin create-coupon form. Renders the six spec §8 sections and
 * POSTs to `/api/v1/admin/coupons`. On success, `useCreateAdminCoupon`
 * routes to the new coupon's detail page.
 *
 * Validation:
 *  - Lifetime (Section 3): at least one of `durationDays` / `useCount`
 *    must be set. Server enforces the same rule (LIFETIME_REQUIRED)
 *    but we surface it inline so the user sees it before submit.
 *  - Signup window (Section 5): both `signupWindowStart` and
 *    `signupWindowEnd` must be set or both must be empty (server:
 *    SIGNUP_WINDOW_PAIRED).
 *  - At least one discount row required (server: `@NotEmpty`).
 *
 * Server errors render as a banner above the submit button. The three
 * known service-level codes get user-friendly messages; everything
 * else falls back to `problem.detail` / `problem.title`.
 */

const TARGETS: Array<{ value: DiscountTarget; label: string }> = [
  { value: "LISTING_FEE", label: "Listing fee" },
  { value: "COMMISSION_RATE", label: "Commission rate" },
];

const OPS: Array<{ value: DiscountOp; label: string }> = [
  { value: "OVERRIDE", label: "Override" },
  { value: "PERCENT_OFF", label: "Percent off" },
  { value: "FLAT_OFF", label: "Flat off (L$)" },
];

function generateCode(): string {
  // Eight-char alphanumeric, uppercase. Matches the `WELCOME30`
  // shape the spec calls out as a typical code without forcing a
  // specific dictionary.
  if (typeof crypto !== "undefined" && typeof crypto.randomUUID === "function") {
    return crypto.randomUUID().replace(/-/g, "").slice(0, 8).toUpperCase();
  }
  // Deterministic fallback for the (rare) test environments without
  // `crypto.randomUUID`; the form Generate button is interactive so
  // a re-click reshuffles anyway.
  return Math.random().toString(36).slice(2, 10).toUpperCase();
}

function freshDiscount(): CouponDiscountDto {
  return { target: "LISTING_FEE", op: "PERCENT_OFF", value: "" };
}

interface UuidChipProps {
  value: string;
  label: string;
  onRemove: () => void;
}

function UuidChip({ value, label, onRemove }: UuidChipProps) {
  return (
    <span
      className="inline-flex items-center gap-1.5 rounded-full bg-info-bg px-2.5 py-1 text-[11px] text-info"
      data-testid={`allowed-user-chip-${value}`}
    >
      <span className="font-medium">{label}</span>
      <button
        type="button"
        onClick={onRemove}
        aria-label={`Remove ${label}`}
        className="rounded-full p-0.5 hover:bg-info/20"
      >
        <Trash2 className="size-3" />
      </button>
    </span>
  );
}

interface SectionProps {
  title: string;
  description?: ReactNode;
  children: React.ReactNode;
}

function Section({ title, description, children }: SectionProps) {
  return (
    <Card>
      <Card.Header>
        <h2 className="text-sm font-semibold tracking-tight text-fg">{title}</h2>
        {description && (
          <p className="mt-1 text-xs text-fg-muted">{description}</p>
        )}
      </Card.Header>
      <Card.Body>
        <div className="flex flex-col gap-4">{children}</div>
      </Card.Body>
    </Card>
  );
}

interface DiscountHelpRow {
  target: string;
  op: string;
  meaning: string;
  example: string;
}

const DISCOUNT_HELP_ROWS: DiscountHelpRow[] = [
  {
    target: "Listing fee",
    op: "Override",
    meaning: "Replace the default listing fee with this exact L$ amount.",
    example: "0 = free listing. 50 = L$50 listing.",
  },
  {
    target: "Listing fee",
    op: "Percent off",
    meaning: "Subtract this percent from the default listing fee.",
    example: "50 = 50% off (default L$100 becomes L$50). Enter 50, not 0.5.",
  },
  {
    target: "Listing fee",
    op: "Flat off (L$)",
    meaning: "Subtract this many L$ from the default listing fee.",
    example: "25 = L$25 off (default L$100 becomes L$75). Negative results clamp to L$0.",
  },
  {
    target: "Commission rate",
    op: "Override",
    meaning: "Replace the default commission rate with this exact percent.",
    example: "3 = 3% commission. 0 = no commission. Enter 3, not 0.03.",
  },
  {
    target: "Commission rate",
    op: "Percent off",
    meaning: "Reduce the default commission rate by this percent.",
    example: "50 = half-off (default 5% becomes 2.5%). Enter 50, not 0.5.",
  },
  {
    target: "Commission rate",
    op: "Flat off (L$)",
    meaning:
      "Subtract this many percentage points from the default commission rate. (Despite the label, the unit here is percentage points, not L$.)",
    example: "2 = 2 points off (default 5% becomes 3%). Negative results clamp to 0%.",
  },
];

interface DiscountHelpModalProps {
  open: boolean;
  onClose: () => void;
}

function DiscountHelpModal({ open, onClose }: DiscountHelpModalProps) {
  return (
    <Modal
      open={open}
      onClose={onClose}
      title="Discount operations"
      footer={
        <Button type="button" variant="secondary" onClick={onClose} data-testid="discount-help-close">
          Close
        </Button>
      }
    >
      <p>
        A discount has three parts: a Target (which fee it touches), an Operation
        (how the value applies), and a Value. The table below shows every
        combination and the value format to enter.
      </p>
      <p className="text-xs text-fg-muted">
        Percentages are always entered as whole numbers (50 means 50%, not 0.5).
        L$ amounts are entered in lindens (50 means L$50).
      </p>
      <div className="overflow-x-auto" data-testid="discount-help-table">
        <table className="w-full text-left text-xs">
          <thead>
            <tr className="border-b border-border-subtle text-fg-muted">
              <th className="py-2 pr-3 font-medium">Target</th>
              <th className="py-2 pr-3 font-medium">Operation</th>
              <th className="py-2 pr-3 font-medium">Meaning</th>
              <th className="py-2 font-medium">Example</th>
            </tr>
          </thead>
          <tbody>
            {DISCOUNT_HELP_ROWS.map((row, i) => (
              <tr key={i} className="border-b border-border-subtle/60 align-top">
                <td className="py-2 pr-3 font-medium text-fg">{row.target}</td>
                <td className="py-2 pr-3 text-fg">{row.op}</td>
                <td className="py-2 pr-3 text-fg-muted">{row.meaning}</td>
                <td className="py-2 text-fg-muted">{row.example}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </Modal>
  );
}

function describeError(err: unknown): string {
  if (!isApiError(err)) return "Could not create coupon. Try again.";
  const problem = err.problem as { code?: string; detail?: string; title?: string };
  switch (problem.code) {
    case "IMMUTABLE_FIELD":
      // The create path only throws IMMUTABLE_FIELD on duplicate code
      // (see CouponService#create). On patch it covers locked fields,
      // but that's not this form.
      return "A coupon with that code already exists. Pick a different code.";
    case "LIFETIME_REQUIRED":
      return "Set at least one of duration (days) or use count.";
    case "SIGNUP_WINDOW_PAIRED":
      return "Signup window needs both a start and end date, or neither.";
  }
  return problem.detail ?? problem.title ?? "Could not create coupon.";
}

export function AdminCouponForm() {
  const createMutation = useCreateAdminCoupon();

  // Section 1: identity
  const [code, setCode] = useState("");
  const [description, setDescription] = useState("");

  // Section 2: discounts (at least one row required)
  const [discounts, setDiscounts] = useState<CouponDiscountDto[]>([
    freshDiscount(),
  ]);

  // Section 3: lifetime
  const [durationDays, setDurationDays] = useState("");
  const [useCount, setUseCount] = useState("");

  // Section 4: redemption controls
  const [redeemableUntil, setRedeemableUntil] = useState("");
  const [maxTotalRedemptions, setMaxTotalRedemptions] = useState("");
  const [maxPerUser, setMaxPerUser] = useState("1");
  const [allowedUsers, setAllowedUsers] = useState<AdminUserSummary[]>([]);

  // Section 5: auto-grant
  const [signupWindowStart, setSignupWindowStart] = useState("");
  const [signupWindowEnd, setSignupWindowEnd] = useState("");

  // Section 6: status
  const [active, setActive] = useState(true);
  const [notifyOnGrant, setNotifyOnGrant] = useState(true);

  // Surfaced inline validation errors (server-side rules echoed up
  // front so the user doesn't have to round-trip).
  const [lifetimeError, setLifetimeError] = useState<string | null>(null);
  const [signupWindowError, setSignupWindowError] = useState<string | null>(null);
  const [discountsError, setDiscountsError] = useState<string | null>(null);
  const [codeError, setCodeError] = useState<string | null>(null);

  // Help modal for discount-operation semantics + value formats.
  const [discountHelpOpen, setDiscountHelpOpen] = useState(false);

  function updateDiscount(index: number, patch: Partial<CouponDiscountDto>) {
    setDiscounts((prev) =>
      prev.map((d, i) => (i === index ? { ...d, ...patch } : d)),
    );
  }

  function addDiscount() {
    setDiscounts((prev) => [...prev, freshDiscount()]);
  }

  function removeDiscount(index: number) {
    setDiscounts((prev) => prev.filter((_, i) => i !== index));
  }

  function handleAllowedUserSelect(user: AdminUserSummary) {
    setAllowedUsers((prev) =>
      prev.some((u) => u.publicId === user.publicId) ? prev : [...prev, user],
    );
  }

  function removeAllowedUser(publicId: string) {
    setAllowedUsers((prev) => prev.filter((u) => u.publicId !== publicId));
  }

  function handleGenerate() {
    setCode(generateCode());
    setCodeError(null);
  }

  function validate(): boolean {
    let ok = true;

    if (code.trim().length === 0) {
      setCodeError("Code is required.");
      ok = false;
    } else if (code.length > 64) {
      setCodeError("Code must be 64 characters or fewer.");
      ok = false;
    } else {
      setCodeError(null);
    }

    if (durationDays.trim() === "" && useCount.trim() === "") {
      setLifetimeError("Set at least one of duration (days) or use count.");
      ok = false;
    } else {
      setLifetimeError(null);
    }

    const startSet = signupWindowStart.trim() !== "";
    const endSet = signupWindowEnd.trim() !== "";
    if (startSet !== endSet) {
      setSignupWindowError(
        "Signup window needs both a start and end date, or neither.",
      );
      ok = false;
    } else {
      setSignupWindowError(null);
    }

    const validDiscounts = discounts.filter((d) => d.value.trim() !== "");
    if (validDiscounts.length === 0) {
      setDiscountsError("Add at least one discount with a value.");
      ok = false;
    } else {
      setDiscountsError(null);
    }

    return ok;
  }

  function buildRequest(): CreateCouponRequest {
    const parsedDuration = durationDays.trim() === "" ? undefined : Number(durationDays);
    const parsedUseCount = useCount.trim() === "" ? undefined : Number(useCount);
    const parsedMaxTotal =
      maxTotalRedemptions.trim() === "" ? undefined : Number(maxTotalRedemptions);
    const parsedMaxPerUser = maxPerUser.trim() === "" ? undefined : Number(maxPerUser);

    // `redeemableUntil` is a `datetime-local` input value ("YYYY-MM-DDTHH:mm")
    // — convert to a full ISO `OffsetDateTime` the backend can parse.
    let redeemableUntilIso: string | undefined;
    if (redeemableUntil.trim() !== "") {
      const d = new Date(redeemableUntil);
      redeemableUntilIso = Number.isNaN(d.getTime()) ? undefined : d.toISOString();
    }

    return {
      code: code.trim(),
      description: description.trim() === "" ? undefined : description.trim(),
      durationDays: parsedDuration,
      useCount: parsedUseCount,
      redeemableUntil: redeemableUntilIso,
      maxTotalRedemptions: parsedMaxTotal,
      maxPerUser: parsedMaxPerUser,
      signupWindowStart:
        signupWindowStart.trim() === "" ? undefined : signupWindowStart,
      signupWindowEnd:
        signupWindowEnd.trim() === "" ? undefined : signupWindowEnd,
      active,
      notifyOnGrant,
      discounts: discounts
        .filter((d) => d.value.trim() !== "")
        .map((d, i) => ({
          target: d.target,
          op: d.op,
          value: d.value.trim(),
          sortOrder: i,
        })),
      allowedUserPublicIds:
        allowedUsers.length === 0 ? undefined : allowedUsers.map((u) => u.publicId),
    };
  }

  function handleSubmit(e: FormEvent) {
    e.preventDefault();
    if (!validate()) return;
    createMutation.mutate(buildRequest());
  }

  const submitting = createMutation.isPending;
  const serverError = createMutation.isError ? describeError(createMutation.error) : null;

  return (
    <form
      onSubmit={handleSubmit}
      className="flex flex-col gap-6 max-w-3xl"
      aria-label="Create coupon"
      data-testid="admin-coupon-form"
      noValidate
    >
      <div className="flex items-center justify-between">
        <h1 className="text-2xl font-semibold">Create coupon</h1>
      </div>

      <Section
        title="Identity"
        description="A unique, human-readable code users will type to redeem."
      >
        <div className="flex flex-col gap-2 sm:flex-row sm:items-end">
          <div className="flex-1">
            <Input
              label="Code"
              value={code}
              onChange={(e) => {
                setCode(e.target.value);
                if (codeError) setCodeError(null);
              }}
              placeholder="WELCOME30"
              data-testid="coupon-code-input"
              error={codeError ?? undefined}
              maxLength={64}
            />
          </div>
          <Button
            type="button"
            variant="secondary"
            onClick={handleGenerate}
            data-testid="coupon-generate-btn"
            className="sm:mb-[2px]"
          >
            Generate
          </Button>
        </div>

        <Input
          label="Description (optional)"
          value={description}
          onChange={(e) => setDescription(e.target.value)}
          placeholder="What this coupon is for"
          data-testid="coupon-description-input"
        />
      </Section>

      <Section
        title="Discount bundle"
        description={
          <>
            One or more discounts applied at listing time. Each row targets either
            the listing fee or the commission rate.{" "}
            <button
              type="button"
              onClick={() => setDiscountHelpOpen(true)}
              data-testid="discount-help-btn"
              className="inline-flex items-center gap-1 text-brand underline-offset-2 hover:underline focus:outline-none focus:ring-2 focus:ring-brand rounded"
            >
              Help
              <ExternalLink className="size-3" aria-hidden="true" />
            </button>
          </>
        }
      >
        <div className="flex flex-col gap-3" data-testid="discount-rows">
          {discounts.map((d, i) => (
            <div
              key={i}
              className="grid grid-cols-1 gap-2 rounded-lg border border-border-subtle bg-bg-subtle p-3 sm:grid-cols-[1fr_1fr_1fr_auto] sm:items-end"
              data-testid={`discount-row-${i}`}
            >
              <div className="flex flex-col gap-1">
                <label className="text-xs font-medium text-fg-muted">Target</label>
                <select
                  value={d.target}
                  data-testid={`discount-target-${i}`}
                  onChange={(e) =>
                    updateDiscount(i, { target: e.target.value as DiscountTarget })
                  }
                  className="h-10 rounded-lg bg-bg-muted px-3 text-sm text-fg ring-1 ring-border-subtle focus:outline-none focus:ring-2 focus:ring-brand"
                >
                  {TARGETS.map((t) => (
                    <option key={t.value} value={t.value}>
                      {t.label}
                    </option>
                  ))}
                </select>
              </div>

              <div className="flex flex-col gap-1">
                <label className="text-xs font-medium text-fg-muted">Operation</label>
                <select
                  value={d.op}
                  data-testid={`discount-op-${i}`}
                  onChange={(e) =>
                    updateDiscount(i, { op: e.target.value as DiscountOp })
                  }
                  className="h-10 rounded-lg bg-bg-muted px-3 text-sm text-fg ring-1 ring-border-subtle focus:outline-none focus:ring-2 focus:ring-brand"
                >
                  {OPS.map((o) => (
                    <option key={o.value} value={o.value}>
                      {o.label}
                    </option>
                  ))}
                </select>
              </div>

              <div className="flex flex-col gap-1">
                <label className="text-xs font-medium text-fg-muted">Value</label>
                <input
                  type="text"
                  inputMode="decimal"
                  value={d.value}
                  onChange={(e) => updateDiscount(i, { value: e.target.value })}
                  placeholder="0.025"
                  data-testid={`discount-value-${i}`}
                  className="h-10 rounded-lg bg-bg-muted px-3 text-sm text-fg placeholder:text-fg-muted ring-1 ring-border-subtle focus:outline-none focus:ring-2 focus:ring-brand"
                />
              </div>

              <button
                type="button"
                onClick={() => removeDiscount(i)}
                disabled={discounts.length <= 1}
                aria-label={`Remove discount ${i + 1}`}
                data-testid={`discount-remove-${i}`}
                className="inline-flex h-10 items-center justify-center rounded-lg px-3 text-fg-muted hover:bg-bg-hover hover:text-danger disabled:opacity-40 disabled:pointer-events-none"
              >
                <Trash2 className="size-4" />
              </button>
            </div>
          ))}
        </div>

        {discountsError && (
          <p className="text-xs text-danger" data-testid="discounts-error">
            {discountsError}
          </p>
        )}

        <Button
          type="button"
          variant="secondary"
          size="sm"
          onClick={addDiscount}
          leftIcon={<Plus className="size-4" />}
          data-testid="discount-add-btn"
          className="self-start"
        >
          Add another discount
        </Button>
      </Section>

      <Section
        title="Lifetime"
        description="How long a granted instance lasts. Set duration, use count, or both."
      >
        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
          <Input
            label="Duration (days)"
            type="number"
            min={1}
            value={durationDays}
            onChange={(e) => {
              setDurationDays(e.target.value);
              if (lifetimeError) setLifetimeError(null);
            }}
            placeholder="e.g. 30"
            data-testid="duration-days-input"
          />
          <div className="flex flex-col gap-1">
            <Input
              label="Use count"
              type="number"
              min={1}
              value={useCount}
              onChange={(e) => {
                setUseCount(e.target.value);
                if (lifetimeError) setLifetimeError(null);
              }}
              placeholder="e.g. 1"
              data-testid="use-count-input"
            />
            <p
              className="text-[11px] text-fg-muted"
              data-testid="use-count-hint"
            >
              How many listings this coupon can discount before it is used up.
              For example, a use count of 3 lets the recipient claim the
              discount on their next 3 listings, then the coupon is exhausted.
              Leave blank for unlimited uses within the duration window.
            </p>
          </div>
        </div>
        {lifetimeError && (
          <p className="text-xs text-danger" data-testid="lifetime-error">
            {lifetimeError}
          </p>
        )}
      </Section>

      <Section
        title="Redemption controls"
        description="Who can redeem this coupon, how often, and until when."
      >
        <Input
          label="Redeemable until (optional)"
          type="datetime-local"
          value={redeemableUntil}
          onChange={(e) => setRedeemableUntil(e.target.value)}
          data-testid="redeemable-until-input"
        />

        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
          <Input
            label="Max total redemptions (empty = unlimited)"
            type="number"
            min={1}
            value={maxTotalRedemptions}
            onChange={(e) => setMaxTotalRedemptions(e.target.value)}
            placeholder="unlimited"
            data-testid="max-total-input"
          />
          <Input
            label="Max per user"
            type="number"
            min={1}
            value={maxPerUser}
            onChange={(e) => setMaxPerUser(e.target.value)}
            data-testid="max-per-user-input"
          />
        </div>

        <div className="flex flex-col gap-2">
          <label className="text-xs font-medium text-fg-muted">
            Allowed users (optional, restricts who can redeem)
          </label>
          <UserSearchAutocomplete
            onSelect={handleAllowedUserSelect}
            placeholder="Search users to allowlist..."
          />
          {allowedUsers.length > 0 && (
            <div
              className="mt-1 flex flex-wrap gap-1.5"
              data-testid="allowed-users-list"
            >
              {allowedUsers.map((u) => (
                <UuidChip
                  key={u.publicId}
                  value={u.publicId}
                  label={u.displayName ?? u.username}
                  onRemove={() => removeAllowedUser(u.publicId)}
                />
              ))}
            </div>
          )}
          <p className="text-[11px] text-fg-muted">
            Leave empty to let any signed-in user redeem (subject to other limits).
          </p>
        </div>
      </Section>

      <Section
        title="Auto-grant signup window"
        description="If set, new users registered within this date range are auto-granted this coupon."
      >
        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
          <Input
            label="Signup window start"
            type="date"
            value={signupWindowStart}
            onChange={(e) => {
              setSignupWindowStart(e.target.value);
              if (signupWindowError) setSignupWindowError(null);
            }}
            data-testid="signup-start-input"
          />
          <Input
            label="Signup window end"
            type="date"
            value={signupWindowEnd}
            onChange={(e) => {
              setSignupWindowEnd(e.target.value);
              if (signupWindowError) setSignupWindowError(null);
            }}
            data-testid="signup-end-input"
          />
        </div>
        {signupWindowError && (
          <p className="text-xs text-danger" data-testid="signup-window-error">
            {signupWindowError}
          </p>
        )}
      </Section>

      <Section title="Status">
        <Checkbox
          label="Active (uncheck to create the coupon paused)"
          checked={active}
          onChange={(e) => setActive(e.target.checked)}
          data-testid="active-checkbox"
        />
        <Checkbox
          label="Notify recipients on grant"
          checked={notifyOnGrant}
          onChange={(e) => setNotifyOnGrant(e.target.checked)}
          data-testid="notify-on-grant-checkbox"
        />
      </Section>

      {serverError && (
        <div
          role="alert"
          data-testid="form-error"
          className="rounded-lg border border-danger/30 bg-danger-bg/50 px-4 py-3 text-sm text-danger"
        >
          {serverError}
        </div>
      )}

      <div className="flex justify-end gap-2">
        <Button
          type="submit"
          variant="primary"
          loading={submitting}
          disabled={submitting}
          data-testid="coupon-submit-btn"
        >
          Create coupon
        </Button>
      </div>

      <DiscountHelpModal
        open={discountHelpOpen}
        onClose={() => setDiscountHelpOpen(false)}
      />
    </form>
  );
}
