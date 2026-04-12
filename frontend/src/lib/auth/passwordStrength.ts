// frontend/src/lib/auth/passwordStrength.ts

export type PasswordStrength = "empty" | "weak" | "fair" | "good" | "strong";

/**
 * Backend regex from Task 01-07's RegisterRequest validator. Mirror exactly:
 *   ^(?=.*[A-Za-z])(?=.*[\d\W]).{10,}$
 * 10+ chars, at least one letter, at least one digit OR symbol.
 */
const BACKEND_REGEX = /^(?=.*[A-Za-z])(?=.*[\d\W]).{10,}$/;

/**
 * Hand-rolled strength estimator aligned with the backend regex.
 *
 * Level mapping:
 *   - empty:  input length 0
 *   - weak:   doesn't meet backend regex (too short OR missing required class)
 *   - fair:   doesn't meet backend regex but is close (length ≥8 with progress)
 *   - good:   meets backend regex exactly
 *   - strong: meets backend regex AND goes beyond (≥14 chars OR 3+ char classes)
 *
 * A password that satisfies the backend regex MUST NEVER show less than "good".
 * A user who meets the real requirement and sees only 2 bars will feel punished
 * for complying. "Strong" is the bonus for going above and beyond.
 *
 * See spec §7.1.
 */
export function computePasswordStrength(password: string): PasswordStrength {
  if (password.length === 0) return "empty";

  if (BACKEND_REGEX.test(password)) {
    const characterClasses = countCharacterClasses(password);
    if (password.length >= 14 || characterClasses >= 3) return "strong";
    return "good";
  }

  // Below the backend requirement. Classify as weak or fair based on progress.
  if (password.length >= 8) return "fair";
  return "weak";
}

function countCharacterClasses(password: string): number {
  let count = 0;
  if (/[a-z]/.test(password)) count++;
  if (/[A-Z]/.test(password)) count++;
  if (/\d/.test(password)) count++;
  if (/[^A-Za-z\d]/.test(password)) count++;
  return count;
}

export function strengthToBars(strength: PasswordStrength): number {
  switch (strength) {
    case "empty": return 0;
    case "weak": return 1;
    case "fair": return 2;
    case "good": return 3;
    case "strong": return 4;
  }
}

export function strengthToLabel(strength: PasswordStrength): string {
  switch (strength) {
    case "empty": return "";
    case "weak": return "Weak";
    case "fair": return "Fair";
    case "good": return "Good";
    case "strong": return "Strong";
  }
}
