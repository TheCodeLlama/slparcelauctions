// frontend/src/components/ui/PasswordStrengthIndicator.tsx
import {
  computePasswordStrength,
  strengthToBars,
  strengthToLabel,
} from "@/lib/auth/passwordStrength";

type PasswordStrengthIndicatorProps = {
  password: string;
};

/**
 * 4-segment password strength bar with a textual label. Computed live on every
 * render from the password value (no debounce — the function is O(n) over a
 * short string).
 *
 * Returns null when the password is empty so the field doesn't bounce up and
 * down as the user starts typing.
 *
 * Visual design matches the Stitch mockups: 4 equal-width segments, h-1,
 * gap-1, rounded-full. Filled segments use bg-brand; empty use bg-brand/20.
 *
 * See spec §7.2.
 */
export function PasswordStrengthIndicator({ password }: PasswordStrengthIndicatorProps) {
  const strength = computePasswordStrength(password);
  const bars = strengthToBars(strength);
  const label = strengthToLabel(strength);

  if (strength === "empty") return null;

  return (
    <div className="mt-2">
      <div
        className="flex gap-1"
        role="progressbar"
        aria-valuenow={bars}
        aria-valuemin={0}
        aria-valuemax={4}
        aria-label={`Password strength: ${label}`}
      >
        {[0, 1, 2, 3].map((i) => (
          <div
            key={i}
            className={`h-1 flex-1 rounded-full ${
              i < bars ? "bg-brand" : "bg-brand/20"
            }`}
          />
        ))}
      </div>
      <p className="mt-1 text-[11px] font-medium text-fg-muted">
        Strength: <span className="font-semibold text-fg">{label}</span>
      </p>
    </div>
  );
}
