import Link from "next/link";

export function Footer() {
  return (
    <footer className="bg-surface-container-low">
      <div className="mx-auto max-w-7xl px-6 py-12">
        <div className="flex flex-col gap-8 md:flex-row md:items-center md:justify-between">
          <div className="flex flex-wrap gap-x-6 gap-y-2">
            <FooterLink href="/about">About</FooterLink>
            <FooterLink href="/terms">Terms</FooterLink>
            <FooterLink href="/contact">Contact</FooterLink>
            <FooterLink href="/partners">Partners</FooterLink>
          </div>
          <p className="text-body-sm text-on-surface-variant">
            © {new Date().getFullYear()} SLPA. Not affiliated with Linden Lab.
          </p>
        </div>
      </div>
    </footer>
  );
}

function FooterLink({ href, children }: { href: string; children: React.ReactNode }) {
  return (
    <Link
      href={href}
      className="text-body-sm text-on-surface-variant hover:text-on-surface transition-colors"
    >
      {children}
    </Link>
  );
}
