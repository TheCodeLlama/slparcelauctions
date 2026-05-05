// page-listing-flow.jsx — Listing edit, Listing activate, Escrow dispute

function ListingEditPage({ setPage }) {
  const [step, setStep] = React.useState(1);
  const [showSuspended, setShowSuspended] = React.useState(false);
  const [title, setTitle] = React.useState('Hilltop with lighthouse rights');
  const [desc, setDesc] = React.useState('Beautifully terraformed parcel on a coastal hill with unobstructed sunset views and existing lighthouse rights from the estate covenant. Includes terraformed access path and small dock. Buyer assumes covenant terms which are residential-friendly with up to 700 prims.');
  const [tags, setTags] = React.useState(['Coastal', 'Residential', 'Terraformed', 'Premium']);
  const [photos, setPhotos] = React.useState([0, 1, 2]);
  const D = window.SLP_DATA;
  const parcel = D.AUCTIONS[5];

  return (
    <div className="page container" style={{ padding: '28px 24px 80px', maxWidth: 920 }}>
      <div style={{ fontSize: 12, color: 'var(--fg-subtle)', marginBottom: 6 }}>
        <a className="muted" onClick={() => setPage('dashboard')} style={{ cursor: 'pointer' }}>Dashboard</a> / Edit listing
      </div>
      <h1 style={{ fontSize: 26, fontWeight: 700, letterSpacing: '-0.02em', margin: 0 }}>Edit listing</h1>
      <p style={{ fontSize: 14, color: 'var(--fg-muted)', marginTop: 4, marginBottom: 22 }}>Configure your auction and review before publishing.</p>

      <div style={{ display: 'flex', gap: 0, marginBottom: 24, borderRadius: 'var(--r-md)', overflow: 'hidden', border: '1px solid var(--border)' }}>
        {[['Configure', 1], ['Review & submit', 2]].map(([l, n]) => (
          <button key={n} onClick={() => setStep(n)} style={{
            flex: 1, padding: '12px 16px', border: 'none', cursor: 'pointer',
            background: step === n ? 'var(--surface)' : 'var(--bg-subtle)',
            borderRight: n === 1 ? '1px solid var(--border)' : 'none',
            display: 'flex', alignItems: 'center', gap: 10, fontSize: 14, fontWeight: 500,
            color: step === n ? 'var(--fg)' : 'var(--fg-muted)',
          }}>
            <div style={{
              width: 22, height: 22, borderRadius: '50%',
              background: step >= n ? 'var(--brand)' : 'var(--border)',
              color: step >= n ? 'white' : 'var(--fg-muted)',
              display: 'grid', placeItems: 'center', fontSize: 11, fontWeight: 700,
            }}>{n}</div>
            {l}
          </button>
        ))}
      </div>

      {step === 1 && (
        <div style={{ display: 'flex', flexDirection: 'column', gap: 22 }}>
          <section className="card" style={{ padding: 20 }}>
            <h2 style={{ fontSize: 15, fontWeight: 600, margin: '0 0 14px' }}>Title & description</h2>
            <div>
              <div style={{ display: 'flex', justifyContent: 'space-between' }}>
                <label className="field-label">Title</label>
                <span style={{ fontSize: 12, color: title.length > 100 ? 'var(--warning)' : 'var(--fg-subtle)' }}>{title.length} / 120</span>
              </div>
              <input className="input" value={title} onChange={(e) => setTitle(e.target.value)} maxLength={120} />
            </div>
            <div style={{ marginTop: 14 }}>
              <div style={{ display: 'flex', justifyContent: 'space-between' }}>
                <label className="field-label">Description</label>
                <span style={{ fontSize: 12, color: 'var(--fg-subtle)' }}>{desc.length} / 5000</span>
              </div>
              <textarea className="textarea" value={desc} onChange={(e) => setDesc(e.target.value)} rows={5} maxLength={5000} />
            </div>
            <div style={{ marginTop: 14 }}>
              <label className="field-label">Tags</label>
              <div style={{ display: 'flex', flexWrap: 'wrap', gap: 6 }}>
                {tags.map(t => <Chip key={t} active onClose={() => setTags(tags.filter(x => x !== t))}>{t}</Chip>)}
                <Chip>+ Add tag</Chip>
              </div>
            </div>
          </section>

          <section className="card" style={{ padding: 20 }}>
            <h2 style={{ fontSize: 15, fontWeight: 600, margin: '0 0 14px' }}>Parcel</h2>
            <div style={{ display: 'flex', gap: 14, padding: 14, borderRadius: 'var(--r-md)', background: 'var(--bg-subtle)' }}>
              <div style={{ width: 72, height: 72, borderRadius: 8, overflow: 'hidden', flexShrink: 0 }}>
                <ParcelImage parcel={parcel} ratio="1/1" showSave={false} />
              </div>
              <div style={{ flex: 1 }}>
                <div style={{ fontSize: 14, fontWeight: 600 }}>{parcel.title}</div>
                <div style={{ fontSize: 12, fontFamily: 'var(--font-mono)', color: 'var(--fg-subtle)', marginTop: 2 }}>{parcel.coords}</div>
                <div style={{ fontSize: 12.5, color: 'var(--fg-muted)', marginTop: 6 }}>
                  {window.formatSqm(parcel.sqm)} · {parcel.tier} · {parcel.primCount} prims · Owner: <span className="bold">aria.northcrest</span>
                </div>
              </div>
            </div>
          </section>

          <section className="card" style={{ padding: 20 }}>
            <h2 style={{ fontSize: 15, fontWeight: 600, margin: '0 0 14px' }}>Auction settings</h2>
            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(2, 1fr)', gap: 14 }}>
              <div><label className="field-label">Starting bid (L$)</label><input className="input" defaultValue="2500" /></div>
              <div><label className="field-label">Reserve price (L$)</label><input className="input" defaultValue="4500" /></div>
              <div><label className="field-label">Buy-now price (L$)</label><input className="input" placeholder="Optional" /></div>
              <div><label className="field-label">Duration (hours)</label><input className="input" defaultValue="72" /></div>
            </div>
            <div style={{ marginTop: 14, display: 'flex', justifyContent: 'space-between', alignItems: 'center', padding: 12, background: 'var(--bg-subtle)', borderRadius: 'var(--r-md)' }}>
              <div>
                <div style={{ fontSize: 13.5, fontWeight: 500 }}>Snipe protection</div>
                <div style={{ fontSize: 12, color: 'var(--fg-muted)', marginTop: 2 }}>Extends the auction if a bid lands in the final window.</div>
              </div>
              <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
                <input className="input" defaultValue="3" style={{ width: 70 }} /><span style={{ fontSize: 12, color: 'var(--fg-muted)' }}>min</span>
                <Toggle value={true} onChange={() => {}} />
              </div>
            </div>
          </section>

          <section className="card" style={{ padding: 20 }}>
            <h2 style={{ fontSize: 15, fontWeight: 600, margin: '0 0 14px' }}>Photos</h2>
            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(4, 1fr)', gap: 10 }}>
              {photos.map(p => (
                <div key={p} style={{ position: 'relative', aspectRatio: '1', borderRadius: 8, overflow: 'hidden' }}>
                  <ParcelImage parcel={parcel} ratio="1/1" showSave={false} />
                  <button onClick={() => setPhotos(photos.filter(x => x !== p))} style={{
                    position: 'absolute', top: 6, right: 6, width: 22, height: 22, borderRadius: '50%',
                    background: 'rgba(0,0,0,.55)', color: 'white', border: 'none', cursor: 'pointer',
                    display: 'grid', placeItems: 'center',
                  }}><Icons.X size={12} /></button>
                </div>
              ))}
              <div style={{ aspectRatio: '1', border: '1.5px dashed var(--border-strong)', borderRadius: 8, display: 'grid', placeItems: 'center', cursor: 'pointer', color: 'var(--fg-muted)' }}>
                <div style={{ textAlign: 'center', fontSize: 12 }}>
                  <Icons.Plus size={16} /><div style={{ marginTop: 4 }}>Drop or click</div>
                </div>
              </div>
            </div>
          </section>

          <div style={{ display: 'flex', justifyContent: 'space-between' }}>
            <Btn variant="ghost" onClick={() => setShowSuspended(true)}>Save as draft</Btn>
            <Btn variant="primary" onClick={() => setStep(2)}>Continue to review →</Btn>
          </div>
        </div>
      )}

      {step === 2 && (
        <div>
          <div className="card" style={{ padding: 20 }}>
            <Badge tone="info">Preview</Badge>
            <h2 style={{ fontSize: 18, fontWeight: 600, margin: '12px 0 4px' }}>{title}</h2>
            <div style={{ fontSize: 12, fontFamily: 'var(--font-mono)', color: 'var(--fg-subtle)', marginBottom: 14 }}>{parcel.coords}</div>
            <div style={{ borderRadius: 8, overflow: 'hidden', marginBottom: 16 }}>
              <ParcelImage parcel={parcel} ratio="16/9" showSave={false} />
            </div>
            <p style={{ fontSize: 14, color: 'var(--fg-muted)', lineHeight: 1.55 }}>{desc}</p>
            <div style={{ display: 'flex', gap: 6, marginTop: 14, flexWrap: 'wrap' }}>{tags.map(t => <Badge key={t} tone="neutral">{t}</Badge>)}</div>
            <div style={{ marginTop: 18, padding: 14, background: 'var(--bg-subtle)', borderRadius: 'var(--r-md)', display: 'grid', gridTemplateColumns: 'repeat(4, 1fr)', gap: 14, fontSize: 13 }}>
              <div><div style={{ color: 'var(--fg-subtle)', fontSize: 11, textTransform: 'uppercase', letterSpacing: '0.05em' }}>Start</div><L amount={2500} /></div>
              <div><div style={{ color: 'var(--fg-subtle)', fontSize: 11, textTransform: 'uppercase', letterSpacing: '0.05em' }}>Reserve</div><L amount={4500} /></div>
              <div><div style={{ color: 'var(--fg-subtle)', fontSize: 11, textTransform: 'uppercase', letterSpacing: '0.05em' }}>Duration</div>72h</div>
              <div><div style={{ color: 'var(--fg-subtle)', fontSize: 11, textTransform: 'uppercase', letterSpacing: '0.05em' }}>Snipe protection</div>3 min</div>
            </div>
          </div>
          <div style={{ display: 'flex', justifyContent: 'space-between', marginTop: 20 }}>
            <Btn variant="secondary" onClick={() => setStep(1)}>← Back to edit</Btn>
            <Btn variant="primary" onClick={() => setPage('listing-activate')}>Submit listing</Btn>
          </div>
        </div>
      )}

      <SuspensionErrorModal open={showSuspended} onClose={() => setShowSuspended(false)} setPage={setPage} />
    </div>
  );
}

function ListingActivatePage({ setPage }) {
  const [status, setStatus] = React.useState('DRAFT'); // DRAFT → DRAFT_PAID → VERIFICATION_PENDING → ACTIVE
  const D = window.SLP_DATA;
  const parcel = D.AUCTIONS[5];
  const steps = [
    { id: 'DRAFT',                label: 'Pay listing fee' },
    { id: 'DRAFT_PAID',           label: 'Choose verification' },
    { id: 'VERIFICATION_PENDING', label: 'Verifying ownership' },
    { id: 'ACTIVE',               label: 'Listing live' },
  ];
  const idx = steps.findIndex(s => s.id === status);

  return (
    <div className="page container" style={{ padding: '28px 24px 80px', maxWidth: 760 }}>
      <div style={{ fontSize: 12, color: 'var(--fg-subtle)', marginBottom: 6 }}>
        <a className="muted" onClick={() => setPage('dashboard')} style={{ cursor: 'pointer' }}>Dashboard</a> / Activate listing
      </div>
      <h1 style={{ fontSize: 26, fontWeight: 700, letterSpacing: '-0.02em', margin: 0 }}>Activate "{parcel.title}"</h1>
      <p style={{ fontSize: 14, color: 'var(--fg-muted)', marginTop: 4, marginBottom: 28 }}>Complete the steps below to take your listing live.</p>

      {/* Stepper */}
      <div className="card" style={{ padding: 20, marginBottom: 22, display: 'flex', gap: 8, alignItems: 'center' }}>
        {steps.map((s, i) => (
          <React.Fragment key={s.id}>
            <div style={{ flex: 1, display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 6 }}>
              <div style={{
                width: 32, height: 32, borderRadius: '50%',
                background: i < idx ? 'var(--success)' : i === idx ? 'var(--brand)' : 'var(--bg-muted)',
                color: i <= idx ? 'white' : 'var(--fg-muted)',
                display: 'grid', placeItems: 'center', fontSize: 13, fontWeight: 700,
              }}>{i < idx ? <Icons.Check size={15} /> : i + 1}</div>
              <div style={{ fontSize: 11.5, fontWeight: 500, color: i === idx ? 'var(--fg)' : 'var(--fg-muted)', textAlign: 'center' }}>{s.label}</div>
            </div>
            {i < steps.length - 1 && <div style={{ flex: 0.6, height: 2, background: i < idx ? 'var(--success)' : 'var(--border)', marginTop: -16 }} />}
          </React.Fragment>
        ))}
      </div>

      {status === 'DRAFT' && (
        <div className="card" style={{ padding: 22 }}>
          <h2 style={{ fontSize: 16, fontWeight: 600, margin: '0 0 8px' }}>Pay your listing fee in-world</h2>
          <p style={{ fontSize: 14, color: 'var(--fg-muted)', lineHeight: 1.5, margin: 0 }}>
            Visit any SLParcels terminal in-world and pay the listing fee. We'll detect the payment automatically.
          </p>
          <div style={{ marginTop: 18, padding: 16, background: 'var(--bg-subtle)', borderRadius: 'var(--r-md)' }}>
            <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: 13, marginBottom: 6 }}><span className="muted">Listing fee</span><L amount={50} /></div>
            <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: 13, marginBottom: 6 }}><span className="muted">Terminal location</span><span className="mono">Bay City (128, 128)</span></div>
            <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: 13 }}><span className="muted">Memo</span><span className="mono">SLParcels-LST-7421</span></div>
          </div>
          <div style={{ display: 'flex', gap: 8, marginTop: 18 }}>
            <Btn variant="primary" onClick={() => setStatus('DRAFT_PAID')}>Simulate payment received</Btn>
            <Btn variant="ghost">Cancel listing</Btn>
          </div>
        </div>
      )}

      {status === 'DRAFT_PAID' && (
        <div className="card" style={{ padding: 22 }}>
          <h2 style={{ fontSize: 16, fontWeight: 600, margin: '0 0 8px' }}>Verify parcel ownership</h2>
          <p style={{ fontSize: 14, color: 'var(--fg-muted)', lineHeight: 1.5, margin: '0 0 16px' }}>Choose how you'd like us to verify you own this parcel.</p>
          {[
            ['object', 'Place verification object', "Rez our verifier object on the parcel and we'll detect it within 60 seconds."],
            ['signed', 'Signed parcel about info', "Paste a signed string into the parcel's About → Description."],
            ['terminal', 'Visit terminal', 'Bring a code from your dashboard to any SLParcels terminal in-world.'],
          ].map(([k, l, d], i) => (
            <label key={k} style={{ display: 'flex', gap: 12, padding: 14, marginBottom: 8, border: '1px solid ' + (i === 0 ? 'var(--brand)' : 'var(--border)'), borderRadius: 'var(--r-md)', cursor: 'pointer', background: i === 0 ? 'var(--brand-soft)' : 'transparent' }}>
              <input type="radio" name="vmethod" defaultChecked={i === 0} style={{ marginTop: 2 }} />
              <div>
                <div style={{ fontSize: 14, fontWeight: 500 }}>{l}</div>
                <div style={{ fontSize: 13, color: 'var(--fg-muted)', marginTop: 2 }}>{d}</div>
              </div>
            </label>
          ))}
          <Btn variant="primary" block onClick={() => setStatus('VERIFICATION_PENDING')} style={{ marginTop: 12 }}>Start verification</Btn>
        </div>
      )}

      {status === 'VERIFICATION_PENDING' && (
        <div className="card" style={{ padding: 22 }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 12, marginBottom: 16 }}>
            <div style={{ position: 'relative', width: 14, height: 14 }}>
              <span style={{ position: 'absolute', inset: 0, borderRadius: '50%', background: 'var(--brand)' }} />
              <span style={{ position: 'absolute', inset: 0, borderRadius: '50%', background: 'var(--brand)', opacity: .35, animation: 'pulse 1.6s ease-out infinite' }} />
            </div>
            <h2 style={{ fontSize: 16, fontWeight: 600, margin: 0 }}>Verification in progress…</h2>
          </div>
          <p style={{ fontSize: 14, color: 'var(--fg-muted)', lineHeight: 1.5 }}>We're polling for your verifier object on the parcel. This usually completes within a minute.</p>
          <div style={{ display: 'flex', gap: 8, marginTop: 16 }}>
            <Btn variant="secondary" onClick={() => setStatus('ACTIVE')}><Icons.Refresh size={13} /> Refresh status</Btn>
            <Btn variant="ghost">Cancel listing</Btn>
          </div>
        </div>
      )}

      {status === 'ACTIVE' && (
        <div className="card" style={{ padding: 32, textAlign: 'center' }}>
          <div style={{ width: 64, height: 64, borderRadius: '50%', background: 'var(--success-bg)', color: 'var(--success)', display: 'grid', placeItems: 'center', margin: '0 auto 16px' }}>
            <Icons.CheckCircle size={32} />
          </div>
          <h2 style={{ fontSize: 22, fontWeight: 700, margin: '0 0 6px' }}>Your listing is live</h2>
          <p style={{ fontSize: 14, color: 'var(--fg-muted)', maxWidth: 360, margin: '0 auto 20px' }}>Bidders can now find your parcel on Browse. We'll notify you when bids come in.</p>
          <div style={{ display: 'flex', justifyContent: 'center', gap: 10 }}>
            <Btn variant="secondary" onClick={() => setPage('dashboard')}>Back to listings</Btn>
            <Btn variant="primary" onClick={() => setPage('auction')}>View public listing</Btn>
          </div>
        </div>
      )}
    </div>
  );
}

function EscrowDisputePage({ setPage }) {
  const [reason, setReason] = React.useState('not-responding');
  const [desc, setDesc] = React.useState('');
  const [files, setFiles] = React.useState([
    { name: 'parcel-screenshot.png', size: '1.4 MB' },
    { name: 'transaction-log.txt', size: '12 KB' },
  ]);
  return (
    <div className="page container" style={{ padding: '28px 24px 80px', maxWidth: 760 }}>
      <div style={{ fontSize: 12, color: 'var(--fg-subtle)', marginBottom: 6 }}>
        <a className="muted" onClick={() => setPage('escrow')} style={{ cursor: 'pointer' }}>Escrow</a> / File a dispute
      </div>
      <h1 style={{ fontSize: 26, fontWeight: 700, letterSpacing: '-0.02em', margin: 0 }}>File a dispute</h1>
      <p style={{ fontSize: 14, color: 'var(--fg-muted)', marginTop: 4, marginBottom: 24 }}>A moderator will review within 24 hours.</p>

      <div className="card" style={{ padding: 16, marginBottom: 22, background: 'var(--bg-subtle)', display: 'grid', gridTemplateColumns: 'repeat(3, 1fr)', gap: 18 }}>
        <div><div style={{ fontSize: 11, color: 'var(--fg-subtle)', textTransform: 'uppercase', letterSpacing: '0.05em', fontWeight: 600 }}>Parcel</div><div style={{ fontSize: 13, fontWeight: 500, marginTop: 4 }}>Lakeside parcel with sunset view</div></div>
        <div><div style={{ fontSize: 11, color: 'var(--fg-subtle)', textTransform: 'uppercase', letterSpacing: '0.05em', fontWeight: 600 }}>Escrow state</div><div style={{ marginTop: 4 }}><Badge tone="warning" dot>Awaiting transfer</Badge></div></div>
        <div><div style={{ fontSize: 11, color: 'var(--fg-subtle)', textTransform: 'uppercase', letterSpacing: '0.05em', fontWeight: 600 }}>Your role</div><div style={{ fontSize: 13, fontWeight: 500, marginTop: 4 }}>Buyer (winner)</div></div>
      </div>

      <div className="card" style={{ padding: 22 }}>
        <div style={{ marginBottom: 16 }}>
          <label className="field-label">Reason</label>
          <select className="select" value={reason} onChange={(e) => setReason(e.target.value)}>
            <option value="not-responding">Seller isn't responding</option>
            <option value="wrong-parcel">Wrong parcel transferred</option>
            <option value="paid-not-funded">I paid but escrow didn't move to funded</option>
            <option value="fraud">I suspect fraud</option>
            <option value="other">Other</option>
          </select>
        </div>

        {reason === 'paid-not-funded' && (
          <div style={{ marginBottom: 16 }}>
            <label className="field-label">SL transaction key</label>
            <input className="input" placeholder="UUID e.g. 12345678-1234-1234-1234-123456789abc" />
            <div className="field-help">Required when reporting a stuck payment.</div>
          </div>
        )}

        <div style={{ marginBottom: 16 }}>
          <div style={{ display: 'flex', justifyContent: 'space-between' }}>
            <label className="field-label">Description</label>
            <span style={{ fontSize: 12, color: desc.length > 2000 ? 'var(--danger)' : 'var(--fg-subtle)' }}>{desc.length} / 2000</span>
          </div>
          <textarea className="textarea" rows={6} value={desc} onChange={(e) => setDesc(e.target.value)}
            placeholder="Describe what happened. Include dates, amounts, and any context that will help a moderator." maxLength={2000} />
          <div className="field-help">Minimum 10 characters.</div>
        </div>

        <div style={{ marginBottom: 6 }}>
          <label className="field-label">Evidence</label>
          <div style={{ border: '1.5px dashed var(--border-strong)', borderRadius: 'var(--r-md)', padding: 18, textAlign: 'center', color: 'var(--fg-muted)', fontSize: 13 }}>
            <Icons.Image size={20} style={{ marginBottom: 6 }} />
            <div>Drop screenshots, transaction logs, or chat exports here.</div>
          </div>
          {files.length > 0 && (
            <div style={{ marginTop: 12, display: 'flex', flexDirection: 'column', gap: 6 }}>
              {files.map((f, i) => (
                <div key={i} style={{ display: 'flex', alignItems: 'center', gap: 10, padding: 10, background: 'var(--bg-subtle)', borderRadius: 'var(--r-sm)', fontSize: 13 }}>
                  <Icons.Image size={14} style={{ color: 'var(--fg-muted)' }} />
                  <span style={{ flex: 1, fontFamily: 'var(--font-mono)', fontSize: 12 }}>{f.name}</span>
                  <span style={{ color: 'var(--fg-subtle)', fontSize: 12 }}>{f.size}</span>
                  <button onClick={() => setFiles(files.filter((_, j) => j !== i))} style={{ border: 'none', background: 'transparent', color: 'var(--fg-muted)', cursor: 'pointer' }}><Icons.X size={14} /></button>
                </div>
              ))}
            </div>
          )}
        </div>

        <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 8, marginTop: 24, paddingTop: 18, borderTop: '1px solid var(--border)' }}>
          <Btn variant="ghost" onClick={() => setPage('escrow')}>Cancel</Btn>
          <Btn variant="primary" onClick={() => setPage('escrow')}>File dispute</Btn>
        </div>
      </div>
    </div>
  );
}

window.ListingEditPage = ListingEditPage;
window.ListingActivatePage = ListingActivatePage;
window.EscrowDisputePage = EscrowDisputePage;
