// page-wallet.jsx
function WalletPage({ setPage }) {
  const D = window.SLP_DATA;
  const [showDeposit, setShowDeposit] = React.useState(false);
  return (
    <div className="page container" style={{ padding: '24px 24px 80px' }}>
      <div style={{ fontSize: 12, color: 'var(--fg-subtle)', marginBottom: 12 }}>
        <a className="muted" onClick={() => setPage('home')} style={{ cursor: 'pointer' }}>Home</a> / Wallet
      </div>
      <h1 style={{ fontSize: 28, fontWeight: 700, letterSpacing: '-0.02em', margin: '0 0 24px' }}>Wallet</h1>

      <div style={{ display: 'grid', gridTemplateColumns: '1fr 320px', gap: 24, alignItems: 'flex-start' }}>
        <div>
          {/* Balance cards */}
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3, 1fr)', gap: 12, marginBottom: 20 }}>
            {[
              ['Total balance', D.WALLET_BALANCE, 'L$ in your account'],
              ['Available', D.WALLET_AVAILABLE, 'Free to bid or withdraw'],
              ['Reserved', D.WALLET_RESERVED, 'Locked in active bids'],
            ].map(([k, v, sub], i) => (
              <div key={k} className="card" style={{ padding: 18, background: i === 0 ? 'var(--fg)' : 'var(--surface)', color: i === 0 ? 'var(--bg)' : 'var(--fg)', borderColor: i === 0 ? 'var(--fg)' : 'var(--border)' }}>
                <div style={{ fontSize: 11.5, fontWeight: 600, letterSpacing: '0.05em', textTransform: 'uppercase', opacity: 0.7 }}>{k}</div>
                <div style={{ fontSize: 26, fontWeight: 700, marginTop: 6, letterSpacing: '-0.02em' }}><L amount={v} /></div>
                <div style={{ fontSize: 12, opacity: 0.65, marginTop: 4 }}>{sub}</div>
              </div>
            ))}
          </div>

          <div style={{ display: 'flex', gap: 8, marginBottom: 24 }}>
            <Btn variant="primary" onClick={() => setShowDeposit(true)}><Icons.Plus size={14} /> Deposit L$</Btn>
            <Btn variant="secondary"><Icons.ArrowUpRight size={14} /> Withdraw</Btn>
            <Btn variant="ghost"><Icons.History size={14} /> Statement</Btn>
          </div>

          {/* Activity */}
          <div className="card" style={{ overflow: 'hidden' }}>
            <div style={{ padding: '14px 18px', borderBottom: '1px solid var(--border)', display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
              <h3 style={{ margin: 0, fontSize: 14, fontWeight: 600 }}>Recent activity</h3>
              <select className="select" style={{ width: 140, height: 30, padding: '4px 10px', fontSize: 12 }}>
                <option>Last 30 days</option><option>Last 90 days</option><option>This year</option>
              </select>
            </div>
            <div style={{ padding: '10px 18px', display: 'grid', gridTemplateColumns: '32px 1fr 100px 100px', fontSize: 11.5, color: 'var(--fg-subtle)', textTransform: 'uppercase', letterSpacing: '0.05em', fontWeight: 600, borderBottom: '1px solid var(--border-subtle)', background: 'var(--bg-subtle)' }}>
              <div></div><div>Description</div><div style={{ textAlign: 'right' }}>Amount</div><div style={{ textAlign: 'right' }}>Time</div>
            </div>
            {D.WALLET_ACTIVITY.map((t, i) => (
              <div key={t.id} style={{ padding: '14px 18px', display: 'grid', gridTemplateColumns: '32px 1fr 100px 100px', alignItems: 'center', fontSize: 13.5, borderTop: i === 0 ? 'none' : '1px solid var(--border-subtle)' }}>
                <div style={{ width: 28, height: 28, borderRadius: 8, background: 'var(--bg-muted)', color: t.amount > 0 ? 'var(--success)' : t.tone === 'danger' ? 'var(--danger)' : 'var(--fg-muted)', display: 'grid', placeItems: 'center' }}>
                  {t.amount > 0 ? <Icons.Plus size={14} /> : <Icons.Minus size={14} />}
                </div>
                <div>
                  <div style={{ fontWeight: 500 }}>{t.label}</div>
                  <div style={{ fontSize: 11.5, color: 'var(--fg-subtle)', marginTop: 1, textTransform: 'capitalize' }}>{t.type.replace('-', ' ')}</div>
                </div>
                <div style={{ textAlign: 'right', fontWeight: 600, color: t.amount > 0 ? 'var(--success)' : 'var(--fg)' }} className="tabular">
                  {t.amount > 0 ? '+' : ''}<L amount={Math.abs(t.amount)} />
                </div>
                <div style={{ textAlign: 'right', fontSize: 12, color: 'var(--fg-subtle)' }}>{t.time}</div>
              </div>
            ))}
          </div>
        </div>

        <aside>
          <div className="card" style={{ padding: 18 }}>
            <h3 style={{ margin: 0, fontSize: 14, fontWeight: 600, marginBottom: 14 }}>Active bid reservations</h3>
            {D.AUCTIONS.slice(0, 3).map((p, i) => (
              <div key={p.id} style={{ display: 'flex', gap: 10, padding: '10px 0', borderTop: i === 0 ? 'none' : '1px solid var(--border-subtle)' }}>
                <div style={{ width: 44, borderRadius: 6, overflow: 'hidden', flexShrink: 0 }}>
                  <ParcelImage parcel={p} ratio="1/1" showSave={false} />
                </div>
                <div style={{ flex: 1, minWidth: 0 }}>
                  <div style={{ fontSize: 12.5, fontWeight: 500, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{p.title}</div>
                  <div style={{ fontSize: 11, color: 'var(--fg-subtle)', fontFamily: 'var(--font-mono)' }}>Reserved L$ {p.currentBid.toLocaleString()}</div>
                </div>
                <div style={{ fontSize: 11, color: 'var(--fg-subtle)', textAlign: 'right' }}><Countdown minutes={p.minutesLeft} compact /></div>
              </div>
            ))}
          </div>

          <div className="card" style={{ padding: 18, marginTop: 12 }}>
            <h3 style={{ margin: 0, fontSize: 14, fontWeight: 600, marginBottom: 4 }}>Deposit terminals</h3>
            <p style={{ fontSize: 12.5, color: 'var(--fg-muted)', margin: '0 0 12px' }}>Visit any SLParcels terminal in-world to top up.</p>
            {['SLPT-014 · Bay City Plaza', 'SLPT-007 · Sansara Hub', 'SLPT-022 · Nautilus Market'].map(s => (
              <div key={s} style={{ padding: '8px 10px', background: 'var(--bg-subtle)', borderRadius: 6, fontSize: 12, marginBottom: 6, fontFamily: 'var(--font-mono)', display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
                <span>{s}</span>
                <Icons.MapPin size={12} style={{ color: 'var(--fg-subtle)' }} />
              </div>
            ))}
          </div>
        </aside>
      </div>

      {showDeposit && <DepositModal onClose={() => setShowDeposit(false)} />}
    </div>
  );
}

function DepositModal({ onClose }) {
  return (
    <div onClick={onClose} style={{ position: 'fixed', inset: 0, background: 'rgba(0,0,0,.5)', backdropFilter: 'blur(4px)', zIndex: 1000, display: 'grid', placeItems: 'center', padding: 20 }}>
      <div onClick={(e) => e.stopPropagation()} className="card" style={{ width: 440, maxWidth: '100%' }}>
        <div style={{ padding: '20px 24px', borderBottom: '1px solid var(--border)', display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
          <h3 style={{ margin: 0, fontSize: 17, fontWeight: 700 }}>Deposit L$</h3>
          <button className="hdr-icon-btn" onClick={onClose}><Icons.X size={16} /></button>
        </div>
        <div style={{ padding: 24 }}>
          <label className="field-label">Amount</label>
          <div style={{ position: 'relative', marginBottom: 14 }}>
            <span style={{ position: 'absolute', left: 14, top: '50%', transform: 'translateY(-50%)', color: 'var(--fg-subtle)', fontSize: 14, fontWeight: 500 }}>L$</span>
            <input className="input input--lg" defaultValue="5,000" style={{ paddingLeft: 38, fontSize: 18, fontWeight: 600 }} />
          </div>
          <div style={{ display: 'flex', gap: 6, marginBottom: 18 }}>
            {['1k','5k','10k','25k'].map(v => <Chip key={v}>L$ {v}</Chip>)}
          </div>
          <label className="field-label">Method</label>
          <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
            {[['In-world terminal', 'Instant · 0% fee'], ['Bank transfer (USD)', '1–2 days · 1.5%'], ['Credit card', 'Instant · 3.2%']].map(([k, v], i) => (
              <label key={k} style={{ display: 'flex', alignItems: 'center', gap: 10, padding: 12, border: '1px solid ' + (i === 0 ? 'var(--brand)' : 'var(--border)'), borderRadius: 'var(--r-md)', cursor: 'pointer', background: i === 0 ? 'var(--brand-soft)' : 'transparent' }}>
                <input type="radio" name="method" defaultChecked={i === 0} style={{ accentColor: 'var(--brand)' }} />
                <div style={{ flex: 1 }}>
                  <div style={{ fontSize: 13.5, fontWeight: 500 }}>{k}</div>
                  <div style={{ fontSize: 12, color: 'var(--fg-muted)' }}>{v}</div>
                </div>
              </label>
            ))}
          </div>
          <Btn variant="primary" block size="lg" style={{ marginTop: 18 }}>Continue</Btn>
        </div>
      </div>
    </div>
  );
}

window.WalletPage = WalletPage;
