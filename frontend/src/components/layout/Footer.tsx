import Link from "next/link";

export function Footer() {
  return (
    <footer className="mt-auto border-t border-border bg-bg-subtle py-8">
      <div className="mx-auto flex w-full max-w-[var(--container-w)] flex-wrap items-center justify-between gap-4 px-6">
        <div className="flex items-center gap-2.5">
          <span
            aria-hidden
            className={
              "grid h-[22px] w-[22px] place-items-center rounded-[5px] " +
              "bg-brand text-white text-[10px] font-extrabold tracking-tight"
            }
          >
            SL
          </span>
          <span className="text-xs text-fg-subtle">
            © {new Date().getFullYear()} SLPA · Independent marketplace, not affiliated with Linden Lab.
          </span>
        </div>
        <nav className="flex flex-wrap gap-6">
          <FooterLink href="/about">About</FooterLink>
          <FooterLink href="/contact">Contact</FooterLink>
          <FooterLink href="/partners">Partners</FooterLink>
          <FooterLink href="/terms">Terms</FooterLink>
        </nav>
      </div>
    </footer>
  );
}

function FooterLink({ href, children }: { href: string; children: React.ReactNode }) {
  return (
    <Link
      href={href}
      className="text-sm text-fg-muted transition-colors hover:text-fg"
    >
      {children}
    </Link>
  );
}
