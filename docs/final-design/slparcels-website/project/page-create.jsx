// page-create.jsx — minimal create-listing wizard
function CreatePage({ setPage }) {
  const [step, setStep] = React.useState(1);
  const steps = ['Parcel', 'Auction terms', 'Review'];

  return (
    <div className="page container" style={{ padding: '24px 24px 80px', maxWidth: 880 }}>
      <div style={{ fontSize: 12, color: 'var(--fg-subtle)', marginBottom: 12 }}>
        <a className="muted" onClick={() => setPage('home')} style={{ cursor: 'pointer' }}>Home</a> / Sell parcel
      </div>
      <h1 style={{ fontSize: 28, fontWeight: 700, letterSpacing: '-0.02em', margin: '0 0 8px' }}>List your parcel</h1>
      <p style={{ color: 'var(--fg-muted)', fontSize: 14, marginTop: 0, marginBottom: 28 }}>Reach 12,000+ active buyers. Listing fee L$ 50 — refundable if your parcel sells.</p>

      {/* Stepper */}
      <div style={{ display: 'flex', alignItems: 'center', gap: 12, marginBottom: 32 }}>
        {steps.map((s, i) => (
          <React.Fragment key={s}>
            <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
              <div style={{ width: 26, height: 26, borderRadius: '50%',
                background: i + 1 <= step ? 'var(--brand)' : 'var(--bg-muted)',
                color: i + 1 <= step ? 'white' : 'var(--fg-muted)',
                display: 'grid', placeItems: 'center', fontSize: 12, fontWeight: 700 }}>
                {i + 1 < step ? <Icons.Check size={13} sw={3} /> : i + 1}
              </div>
              <span style={{ fontSize: 13, fontWeight: i + 1 === step ? 600 : 500, color: i + 1 === step ? 'var(--fg)' : 'var(--fg-muted)' }}>{s}</span>
            </div>
            {i < steps.length - 1 && <div style={{ flex: 1, height: 1, background: 'var(--border)' }} />}
          </React.Fragment>
        ))}
      </div>

      <div className="card" style={{ padding: 28 }}>
        {step === 1 && (
          <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
            <div>
              <label className="field-label">Listing title</label>
              <input className="input input--lg" placeholder="e.g. Beachfront parcel with sunset view" />
            </div>
            <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 16 }}>
              <div>
                <label className="field-label">Region</label>
                <select className="select"><option>Bay City</option><option>Sansara</option><option>Heterocera</option></select>
              </div>
              <div>
                <label className="field-label">Coordinates</label>
                <input className="input" placeholder="128, 96, 24" />
              </div>
            </div>
            <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr 1fr', gap: 16 }}>
              <div>
                <label className="field-label">Tier</label>
                <select className="select"><option>Mainland</option><option>Premium</option></select>
              </div>
              <div>
                <label className="field-label">Size (m²)</label>
                <input className="input" placeholder="2048" />
              </div>
              <div>
                <label className="field-label">Covenant</label>
                <select className="select"><option>Residential</option><option>Commercial</option></select>
              </div>
            </div>
            <div>
              <label className="field-label">Description</label>
              <textarea className="textarea" rows={4} placeholder="Highlight what makes this parcel desirable…" />
            </div>
            <div>
              <label className="field-label">Photos</label>
              <div style={{ border: '2px dashed var(--border)', borderRadius: 'var(--r-md)', padding: 32, textAlign: 'center', color: 'var(--fg-muted)' }}>
                <Icons.Image size={28} style={{ opacity: 0.4, marginBottom: 8 }} />
                <div style={{ fontSize: 13.5, fontWeight: 500 }}>Drop screenshots here, or click to browse</div>
                <div style={{ fontSize: 12, color: 'var(--fg-subtle)', marginTop: 4 }}>Up to 8 images · PNG or JPG · Min 1280×720</div>
              </div>
            </div>
          </div>
        )}
        {step === 2 && (
          <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
            <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 16 }}>
              <div>
                <label className="field-label">Starting bid (L$)</label>
                <input className="input input--lg" placeholder="2,500" />
              </div>
              <div>
                <label className="field-label">Reserve price (L$) <span style={{ color: 'var(--fg-subtle)' }}>· optional</span></label>
                <input className="input input--lg" placeholder="3,500" />
              </div>
            </div>
            <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 16 }}>
              <div>
                <label className="field-label">Buy-it-now (L$) <span style={{ color: 'var(--fg-subtle)' }}>· optional</span></label>
                <input className="input input--lg" placeholder="6,000" />
              </div>
              <div>
                <label className="field-label">Auction duration</label>
                <select className="select"><option>3 days</option><option>5 days</option><option>7 days</option></select>
              </div>
            </div>
            <label style={{ display: 'flex', gap: 10, alignItems: 'flex-start', padding: 14, border: '1px solid var(--border)', borderRadius: 'var(--r-md)' }}>
              <input type="checkbox" defaultChecked style={{ marginTop: 2, accentColor: 'var(--brand)' }} />
              <div>
                <div style={{ fontSize: 13.5, fontWeight: 500 }}>Enable snipe protection</div>
                <div style={{ fontSize: 12.5, color: 'var(--fg-muted)' }}>Auction extends by 5 minutes if a bid arrives in the final 5 minutes.</div>
              </div>
            </label>
            <label style={{ display: 'flex', gap: 10, alignItems: 'flex-start', padding: 14, border: '1px solid var(--border)', borderRadius: 'var(--r-md)' }}>
              <input type="checkbox" defaultChecked style={{ marginTop: 2, accentColor: 'var(--brand)' }} />
              <div>
                <div style={{ fontSize: 13.5, fontWeight: 500 }}>Use SLParcels Escrow (recommended)</div>
                <div style={{ fontSize: 12.5, color: 'var(--fg-muted)' }}>Funds locked until parcel transfers. Sellers using escrow see 2.4× higher trust scores.</div>
              </div>
            </label>
          </div>
        )}
        {step === 3 && (
          <div>
            <h3 style={{ marginTop: 0, fontSize: 16, fontWeight: 600 }}>Ready to publish</h3>
            <div style={{ background: 'var(--bg-subtle)', padding: 16, borderRadius: 'var(--r-md)', marginBottom: 16 }}>
              <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 12, fontSize: 13 }}>
                {[['Title', 'Beachfront parcel'], ['Region', 'Bay City (128, 96, 24)'], ['Size', '2,048 m²'], ['Starting bid', 'L$ 2,500'], ['Duration', '5 days'], ['Listing fee', 'L$ 50']].map(([k, v]) => (
                  <div key={k}><div style={{ color: 'var(--fg-subtle)', fontSize: 11.5 }}>{k}</div><div style={{ fontWeight: 500, marginTop: 1 }}>{v}</div></div>
                ))}
              </div>
            </div>
            <div style={{ padding: 14, background: 'var(--brand-soft)', border: '1px solid var(--brand-border)', borderRadius: 'var(--r-md)', display: 'flex', gap: 10 }}>
              <Icons.ShieldCheck size={16} style={{ color: 'var(--brand)', flexShrink: 0, marginTop: 1 }} />
              <div style={{ fontSize: 12.5 }}>L$ 50 listing fee will be deducted from your wallet on publish. Refunded automatically if your parcel sells.</div>
            </div>
          </div>
        )}

        <div style={{ display: 'flex', justifyContent: 'space-between', marginTop: 28, paddingTop: 20, borderTop: '1px solid var(--border)' }}>
          {step > 1 ? <Btn variant="secondary" onClick={() => setStep(step - 1)}><Icons.ChevronLeft size={14} /> Back</Btn> : <span />}
          {step < 3
            ? <Btn variant="primary" onClick={() => setStep(step + 1)}>Continue <Icons.ChevronRight size={14} /></Btn>
            : <Btn variant="primary" onClick={() => setPage('dashboard')}>Publish listing</Btn>}
        </div>
      </div>
    </div>
  );
}

window.CreatePage = CreatePage;
