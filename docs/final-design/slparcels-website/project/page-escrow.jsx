// page-escrow.jsx — escrow status & timeline
function EscrowPage({ setPage, setAuctionId }) {
  const D = window.SLP_DATA;
  const parcel = D.AUCTIONS[2];
  const [showDispute, setShowDispute] = React.useState(false);

  return (
    <div className="page container" style={{ padding: '24px 24px 80px' }}>
      <div style={{ fontSize: 12, color: 'var(--fg-subtle)', marginBottom: 12 }}>
        <a className="muted" onClick={() => setPage('home')} style={{ cursor: 'pointer' }}>Home</a> /{' '}
        <a className="muted" onClick={() => setPage('dashboard')} style={{ cursor: 'pointer' }}>Dashboard</a> / Escrow #ESC-2491
      </div>

      <div style={{ display: 'flex', alignItems: 'flex-start', justifyContent: 'space-between', marginBottom: 24, gap: 20 }}>
        <div>
          <div style={{ display: 'flex', gap: 8, alignItems: 'center', marginBottom: 8 }}>
            <Badge tone="brand" dot pulse>Awaiting parcel transfer</Badge>
            <span style={{ fontFamily: 'var(--font-mono)', fontSize: 12, color: 'var(--fg-subtle)' }}>ESC-2491</span>
          </div>
          <h1 style={{ fontSize: 26, fontWeight: 700, letterSpacing: '-0.02em', margin: 0 }}>You won — escrow in progress</h1>
          <p style={{ color: 'var(--fg-muted)', fontSize: 14, marginTop: 6, marginBottom: 0 }}>
            Funds are locked. Once {parcel.seller.name} transfers the parcel in-world, confirm here to release payment.
          </p>
        </div>
        <div style={{ display: 'flex', gap: 6 }}>
          <Btn variant="secondary" size="sm">Message seller</Btn>
          <Btn variant="ghost" size="sm" onClick={() => setShowDispute(true)} style={{ color: 'var(--danger)' }}>Open dispute</Btn>
        </div>
      </div>

      <div style={{ display: 'grid', gridTemplateColumns: '1fr 380px', gap: 28, alignItems: 'flex-start' }}>
        <div>
          {/* Timeline */}
          <div className="card" style={{ padding: 24 }}>
            <h3 style={{ margin: 0, fontSize: 14, fontWeight: 600, marginBottom: 20 }}>Escrow timeline</h3>
            <div style={{ position: 'relative' }}>
              {D.ESCROW_STEPS.map((s, i) => {
                const isActive = s.state === 'active';
                const isComplete = s.state === 'complete';
                return (
                  <div key={s.id} style={{ display: 'flex', gap: 16, paddingBottom: i === D.ESCROW_STEPS.length - 1 ? 0 : 24, position: 'relative' }}>
                    {i < D.ESCROW_STEPS.length - 1 && (
                      <div style={{ position: 'absolute', left: 13, top: 30, bottom: 0,
                        width: 2, background: isComplete ? 'var(--brand)' : 'var(--border)' }} />
                    )}
                    <div style={{
                      width: 28, height: 28, borderRadius: '50%', flexShrink: 0,
                      display: 'grid', placeItems: 'center',
                      background: isComplete ? 'var(--brand)' : isActive ? 'var(--brand-soft)' : 'var(--bg-muted)',
                      color: isComplete ? 'white' : isActive ? 'var(--brand)' : 'var(--fg-faint)',
                      border: isActive ? '2px solid var(--brand)' : 'none',
                      position: 'relative', zIndex: 1,
                    }}>
                      {isComplete ? <Icons.Check size={14} sw={3} /> : <span style={{ fontSize: 11, fontWeight: 700 }}>{i + 1}</span>}
                    </div>
                    <div style={{ flex: 1, paddingTop: 3 }}>
                      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'baseline' }}>
                        <div style={{ fontWeight: 600, fontSize: 14, color: isActive || isComplete ? 'var(--fg)' : 'var(--fg-subtle)' }}>{s.label}</div>
                        {isActive && <Badge tone="brand">In progress</Badge>}
                        {isComplete && <span style={{ fontSize: 11.5, color: 'var(--fg-subtle)' }}>completed 12 min ago</span>}
                      </div>
                      <div style={{ fontSize: 12.5, color: 'var(--fg-muted)', marginTop: 3 }}>{s.desc}</div>
                      {isActive && (
                        <div style={{ marginTop: 12, padding: 12, background: 'var(--brand-soft)', border: '1px solid var(--brand-border)', borderRadius: 'var(--r-md)', display: 'flex', gap: 10, alignItems: 'center', justifyContent: 'space-between' }}>
                          <div style={{ fontSize: 12.5 }}>
                            <div style={{ fontWeight: 600 }}>Waiting on seller (auto-cancel in 41h 22m)</div>
                            <div style={{ color: 'var(--fg-muted)' }}>You'll be notified the moment the parcel transfer completes.</div>
                          </div>
                          <Btn variant="primary" size="sm">I received the parcel</Btn>
                        </div>
                      )}
                    </div>
                  </div>
                );
              })}
            </div>
          </div>

          {/* Activity log */}
          <div className="card" style={{ padding: 0, marginTop: 20 }}>
            <div style={{ padding: '14px 20px', borderBottom: '1px solid var(--border)' }}>
              <h3 style={{ margin: 0, fontSize: 14, fontWeight: 600 }}>Activity log</h3>
            </div>
            {[
              ['Buyer confirmed wallet funds', '8 min ago', <Icons.Wallet size={14} />],
              ['Funds moved to escrow vault', '12 min ago', <Icons.Lock size={14} />],
              ['Auction won by Aria Northcrest', '14 min ago', <Icons.Gavel size={14} />],
              ['Auction closed', '15 min ago', <Icons.Clock size={14} />],
            ].map(([t, time, icon], i) => (
              <div key={i} style={{ padding: '12px 20px', borderTop: i === 0 ? 'none' : '1px solid var(--border-subtle)', display: 'flex', alignItems: 'center', gap: 12 }}>
                <div style={{ width: 28, height: 28, borderRadius: 8, background: 'var(--bg-muted)', color: 'var(--fg-muted)', display: 'grid', placeItems: 'center' }}>{icon}</div>
                <div style={{ flex: 1, fontSize: 13 }}>{t}</div>
                <div style={{ fontSize: 12, color: 'var(--fg-subtle)' }}>{time}</div>
              </div>
            ))}
          </div>
        </div>

        {/* Sidebar summary */}
        <aside>
          <div className="card">
            <ParcelImage parcel={parcel} ratio="16/10" showSave={false} />
            <div style={{ padding: 16 }}>
              <div style={{ fontSize: 14.5, fontWeight: 600, marginBottom: 4 }}>{parcel.title}</div>
              <div style={{ fontFamily: 'var(--font-mono)', fontSize: 11, color: 'var(--fg-subtle)', marginBottom: 14 }}>{parcel.coords}</div>

              <div style={{ display: 'flex', flexDirection: 'column', gap: 10, fontSize: 13 }}>
                {[
                  ['Winning bid', `L$ ${parcel.currentBid.toLocaleString()}`, true],
                  ['Marketplace fee', '— L$ 50', false],
                  ['Seller receives', `L$ ${(parcel.currentBid - 50).toLocaleString()}`, false],
                ].map(([k, v, b]) => (
                  <div key={k} style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                    <span style={{ color: 'var(--fg-muted)' }}>{k}</span>
                    <span style={{ fontWeight: b ? 700 : 500 }} className="tabular">{v}</span>
                  </div>
                ))}
                <div className="divider" style={{ margin: '4px 0' }} />
                <div style={{ display: 'flex', justifyContent: 'space-between' }}>
                  <span style={{ color: 'var(--fg-muted)' }}>Settlement</span>
                  <span className="tabular bold">in 41h 22m</span>
                </div>
              </div>

              <div className="divider" />

              <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
                <Avatar name={parcel.seller.name} size={36} color={parcel.seller.avatar} />
                <div style={{ flex: 1, minWidth: 0 }}>
                  <div style={{ fontSize: 13, fontWeight: 600 }}>{parcel.seller.name}</div>
                  <StarRating value={parcel.seller.rating} size={11} />
                </div>
              </div>
            </div>
          </div>

          <div style={{ marginTop: 12, padding: 14, fontSize: 12.5, color: 'var(--fg-muted)', display: 'flex', gap: 10 }}>
            <Icons.ShieldCheck size={16} style={{ color: 'var(--brand)', flexShrink: 0, marginTop: 1 }} />
            <span>Your funds are insured. If the seller fails to deliver, you receive a full refund within 48 hours.</span>
          </div>
        </aside>
      </div>

      {showDispute && <DisputeModal onClose={() => setShowDispute(false)} />}
    </div>
  );
}

function DisputeModal({ onClose }) {
  const [reason, setReason] = React.useState('');
  return (
    <div onClick={onClose} style={{
      position: 'fixed', inset: 0, background: 'rgba(0,0,0,.5)', backdropFilter: 'blur(4px)',
      zIndex: 1000, display: 'grid', placeItems: 'center', padding: 20,
    }}>
      <div onClick={(e) => e.stopPropagation()} className="card" style={{ width: 520, maxWidth: '100%' }}>
        <div style={{ padding: '20px 24px', borderBottom: '1px solid var(--border)', display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
          <h3 style={{ margin: 0, fontSize: 17, fontWeight: 700 }}>Open a dispute</h3>
          <button className="hdr-icon-btn" onClick={onClose}><Icons.X size={16} /></button>
        </div>
        <div style={{ padding: 24 }}>
          <p style={{ fontSize: 13, color: 'var(--fg-muted)', marginTop: 0, lineHeight: 1.5 }}>
            Tell us what went wrong. A trust & safety agent will review within 24 hours and reach out to both parties.
          </p>
          <label className="field-label">Reason for dispute</label>
          <select className="select" value={reason} onChange={(e) => setReason(e.target.value)}>
            <option value="">Select a reason…</option>
            <option>Parcel not transferred</option>
            <option>Parcel does not match listing</option>
            <option>Wrong coordinates / region</option>
            <option>Covenant differs from listing</option>
            <option>Other</option>
          </select>
          <label className="field-label" style={{ marginTop: 16 }}>Details</label>
          <textarea className="textarea" placeholder="Describe what happened…" rows={4} />
          <div style={{ display: 'flex', gap: 8, marginTop: 18 }}>
            <Btn variant="secondary" block onClick={onClose}>Cancel</Btn>
            <Btn variant="primary" block style={{ background: 'var(--danger)', borderColor: 'var(--danger)' }}>Submit dispute</Btn>
          </div>
        </div>
      </div>
    </div>
  );
}

window.EscrowPage = EscrowPage;
