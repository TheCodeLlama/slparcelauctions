// page-static.jsx — About, Contact, Partners, Terms

function StaticPageShell({ title, subtitle, children, setPage }) {
  return (
    <div className="page container" style={{ padding: '48px 24px 80px', maxWidth: 820 }}>
      <div style={{ fontSize: 12, color: 'var(--fg-subtle)', marginBottom: 8 }}>
        <a className="muted" onClick={() => setPage('home')} style={{ cursor: 'pointer' }}>Home</a> / {title}
      </div>
      <h1 style={{ fontSize: 36, fontWeight: 700, letterSpacing: '-0.025em', margin: '0 0 10px' }}>{title}</h1>
      <p style={{ fontSize: 17, color: 'var(--fg-muted)', margin: '0 0 32px', lineHeight: 1.5 }}>{subtitle}</p>
      <div style={{ fontSize: 15, color: 'var(--fg)', lineHeight: 1.7 }}>{children}</div>
    </div>
  );
}

function AboutPage({ setPage }) {
  return (
    <StaticPageShell setPage={setPage} title="About SLParcels" subtitle="The story behind the auctions.">
      <p>SLParcels is an independent marketplace where Second Life residents buy and sell virtual land — parcels, homesteads, and full regions — through transparent, escrowed auctions.</p>
      <h2 style={{ fontSize: 20, fontWeight: 600, marginTop: 32, marginBottom: 8 }}>Our mission</h2>
      <p>We believe virtual land deserves a dedicated, trustworthy market. Every transaction is escrowed, every seller verified, and every dispute resolved by a real human moderator.</p>
      <h2 style={{ fontSize: 20, fontWeight: 600, marginTop: 32, marginBottom: 8 }}>How it works</h2>
      <ul>
        <li>Sellers list their parcel, set terms, and verify ownership in-world.</li>
        <li>Buyers bid in L$ from their SLParcels wallet — funds are reserved until the auction ends.</li>
        <li>Escrow holds the L$ until the parcel transfers in-world and both parties confirm.</li>
      </ul>
      <p style={{ marginTop: 24, fontSize: 13, color: 'var(--fg-subtle)' }}>SLParcels is an independent marketplace and is not affiliated with Linden Lab or Second Life.</p>
    </StaticPageShell>
  );
}

function ContactPage({ setPage }) {
  return (
    <StaticPageShell setPage={setPage} title="Contact" subtitle="Get in touch with the SLParcels team.">
      <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 16, marginTop: 8 }}>
        {[
          ['Support', 'support@slparcels.io', 'For account, bidding, escrow, and dispute help.'],
          ['Trust & Safety', 'trust@slparcels.io', 'Report fraud, abuse, or policy violations.'],
          ['Partnerships', 'partners@slparcels.io', 'Verification and bot service partners.'],
          ['Press & media', 'press@slparcels.io', 'Editorial and interview requests.'],
        ].map(([h, e, d]) => (
          <div key={h} className="card" style={{ padding: 18 }}>
            <div style={{ fontSize: 14, fontWeight: 600 }}>{h}</div>
            <div style={{ fontSize: 13, color: 'var(--brand)', fontFamily: 'var(--font-mono)', marginTop: 4 }}>{e}</div>
            <div style={{ fontSize: 13, color: 'var(--fg-muted)', marginTop: 8 }}>{d}</div>
          </div>
        ))}
      </div>
    </StaticPageShell>
  );
}

function PartnersPage({ setPage }) {
  const partners = [
    { name: 'SLPA Verifier Network', kind: 'Verification', desc: 'In-world terminals at every major hub for avatar and parcel verification.' },
    { name: 'EstateBot 3', kind: 'Bot service', desc: 'Automated parcel transfer and covenant enforcement.' },
    { name: 'Linden Trust', kind: 'Verification', desc: 'Third-party identity and ownership attestation.' },
    { name: 'GridPay', kind: 'Payments', desc: 'L$ payment processing and bank withdrawals.' },
  ];
  return (
    <StaticPageShell setPage={setPage} title="Partners" subtitle="Our verification and bot service partners.">
      <div style={{ display: 'flex', flexDirection: 'column', gap: 12, marginTop: 8 }}>
        {partners.map(p => (
          <div key={p.name} className="card" style={{ padding: 18, display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: 16 }}>
            <div>
              <div style={{ fontSize: 15, fontWeight: 600 }}>{p.name}</div>
              <div style={{ fontSize: 13, color: 'var(--fg-muted)', marginTop: 4 }}>{p.desc}</div>
            </div>
            <Badge tone="brand">{p.kind}</Badge>
          </div>
        ))}
      </div>
    </StaticPageShell>
  );
}

function TermsPage({ setPage }) {
  return (
    <StaticPageShell setPage={setPage} title="Terms of Service" subtitle="The rules of the road.">
      <p style={{ fontSize: 13, color: 'var(--fg-subtle)' }}>Last updated: April 1, 2026</p>
      {['Account', 'Listing & bidding', 'Escrow & payments', 'Disputes', 'Prohibited content', 'Limitation of liability'].map((h, i) => (
        <div key={h} style={{ marginTop: 24 }}>
          <h2 style={{ fontSize: 18, fontWeight: 600, margin: '0 0 6px' }}>{i + 1}. {h}</h2>
          <p style={{ color: 'var(--fg-muted)', margin: 0 }}>By using SLParcels you agree to the policies described in this section. Detailed terms are maintained in our policy repository and updated periodically.</p>
        </div>
      ))}
    </StaticPageShell>
  );
}

window.AboutPage = AboutPage;
window.ContactPage = ContactPage;
window.PartnersPage = PartnersPage;
window.TermsPage = TermsPage;
