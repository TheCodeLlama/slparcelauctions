// frontend/src/components/ui/FormError.tsx

type FormErrorProps = {
  message?: string;
};

/**
 * Generic form-level error display. Used by every form in the project to
 * surface server errors that don't map to a specific field (e.g., network
 * failures, "Email or password is incorrect" on login).
 *
 * Lives in components/ui/ (not components/auth/) because it's a generic
 * primitive — Epic 2's verification form, Epic 3's listing form, etc. all
 * reuse it.
 *
 * Two lines of CSS, one prop, one conditional. Don't let it grow.
 */
export function FormError({ message }: FormErrorProps) {
  if (!message) return null;
  return (
    <div
      role="alert"
      className="rounded-md bg-danger-bg px-4 py-3 text-xs font-medium text-danger"
    >
      {message}
    </div>
  );
}
