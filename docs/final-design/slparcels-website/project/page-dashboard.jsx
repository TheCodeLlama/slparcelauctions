// page-dashboard.jsx — Dashboard with tabs: Overview, Bids, Listings (+ Verify as standalone)

function DashboardShell({ tab, setTab, children, setPage }) {
  const tabs = [
    { id: 'overview',  label: 'Overview' },
    { id: 'bids',      label: 'My bids' },
    { id: 'listings',  label: 'My listings' },
  ];
  return (
    <div className="page container" style={{ padding: '28px 24px 80px' }}>
      <div style={{ marginBottom: 22 }}>
        <div style={{ fontSize: 12, color: 'var(--fg-subtle)', marginBottom: 6 }}>
          <a className="muted" onClick={() => setPage('home')} style={{ cursor: 'pointer' }}>Home</a> / Dashboard
        </div>
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-end', gap: 16 }}>
          <h1 style={{ fontSize: 28, fontWeight: 700, letterSpacing: '-0.02em', margin: 0 }}>Dashboard</h1>
          <div style={{ display: 'flex', gap: 8 }}>
            <Btn variant="secondary" onClick={() => setPage('wallet')}><Icons.Wallet size={14} /> Wallet</Btn>
            <Btn variant="primary" onClick={() => setPage('create')}><Icons.Plus size={14} /> List a parcel</Btn>
          </div>
        </div>
      </div>

      <div style={{ borderBottom: '1px solid var(--border)', display: 'flex', gap: 2, marginBottom: 22 }}>
        {tabs.map((t) => (
          <button key={t.id} onClick={() => setTab(t.id)} className="btn btn--ghost"
            style={{ borderRadius: 0, padding: '11px 16px', fontSize: 14, fontWeight: 500,
              borderBottom: '2px solid ' + (tab === t.id ? 'var(--brand)' : 'transparent'),
              color: tab === t.id ? 'var(--fg)' : 'var(--fg-muted)', marginBottom: -1 }}>
            {t.label}
          </button>
        ))}
      </div>

      {children}
    </div>
  );
}

// ────────────────────────────────────────────────────────────────────────
// OVERVIEW
// ────────────────────────────────────────────────────────────────────────

function DashboardOverview({ setPage, setAuctionId }) {
  const D = window.SLP_DATA;
  const [showSuspension] = React.useState(false); // toggle to demo
  const [bio, setBio] = React.useState('Builder, terraformer, and long-time SL resident. I curate parcels for quiet residential and themed RP communities.');
  const [displayName, setDisplayName] = React.useState('Aria Northcrest');
  const [savedFlash, setSavedFlash] = React.useState(false);
  const [openCancel, setOpenCancel] = React.useState(null);

  const pendingReviews = [
    { id: 'pr1', parcel: D.AUCTIONS[2], role: 'Buyer', counterparty: 'Mira Kessel', closesIn: '5 days' },
    { id: 'pr2', parcel: D.AUCTIONS[6], role: 'Seller', counterparty: 'Otto Wendel', closesIn: '18 hours' },
  ];

  const cancellations = [
    { id: 'c1', parcel: 'Hilltop with lighthouse rights', date: 'Mar 14, 2026', status: 'ACTIVE',  hadBids: true,  penalty: 'L$200 fine', reason: 'Buyer requested withdrawal due to a region merger announcement that affected covenant terms. Refunded all bids in good faith.' },
    { id: 'c2', parcel: 'Sky platform 4000m, low lag',     date: 'Feb 02, 2026', status: 'DRAFT',   hadBids: false, penalty: null,        reason: 'Decided not to list — chose a different parcel from inventory instead.' },
    { id: 'c3', parcel: 'Snow region with chalet pad',     date: 'Dec 28, 2025', status: 'ACTIVE',  hadBids: true,  penalty: null,        reason: null },
  ];

  return (
    <div style={{ display: 'grid', gridTemplateColumns: '1fr 340px', gap: 24, alignItems: 'flex-start' }}>
      <div style={{ display: 'flex', flexDirection: 'column', gap: 22 }}>

        {/* Suspension banner — conditional */}
        {showSuspension && (
          <div className="card" style={{ padding: 16, borderColor: 'var(--danger)', background: 'var(--danger-bg)', display: 'flex', gap: 12, alignItems: 'flex-start' }}>
            <div style={{ color: 'var(--danger)', flexShrink: 0, marginTop: 2 }}><Icons.AlertCircle size={18} /></div>
            <div style={{ flex: 1 }}>
              <div style={{ fontWeight: 600, fontSize: 14, color: 'var(--danger)' }}>Listing privileges restricted</div>
              <div style={{ fontSize: 13, color: 'var(--fg)', marginTop: 4 }}>
                Suspension lifts on <span className="bold">Apr 22, 2026</span>. Outstanding penalty: <span className="bold">L$500</span> — visit any in-world SLPA terminal to clear it.
              </div>
              <a style={{ fontSize: 13, color: 'var(--danger)', fontWeight: 600, marginTop: 8, display: 'inline-block', cursor: 'pointer' }}>Contact support →</a>
            </div>
          </div>
        )}

        {/* Pending reviews */}
        {pendingReviews.length > 0 && (
          <section>
            <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 12 }}>
              <h2 style={{ fontSize: 16, fontWeight: 600, margin: 0 }}>Pending reviews</h2>
              <span style={{ fontSize: 12, color: 'var(--fg-subtle)' }}>{pendingReviews.length} awaiting your feedback</span>
            </div>
            <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
              {pendingReviews.map((r) => (
                <div key={r.id} className="card" style={{ padding: 14, display: 'flex', gap: 14, alignItems: 'center' }}>
                  <div style={{ width: 64, height: 64, borderRadius: 8, overflow: 'hidden', flexShrink: 0 }}>
                    <ParcelImage parcel={r.parcel} ratio="1/1" showSave={false} />
                  </div>
                  <div style={{ flex: 1, minWidth: 0 }}>
                    <div style={{ fontSize: 14, fontWeight: 600, marginBottom: 3 }}>{r.parcel.title}</div>
                    <div style={{ display: 'flex', alignItems: 'center', gap: 10, fontSize: 12.5, color: 'var(--fg-muted)' }}>
                      <span style={{ display: 'inline-flex', alignItems: 'center', gap: 5 }}>
                        <Avatar name={r.counterparty} size={18} /> {r.counterparty}
                      </span>
                      <span>·</span>
                      <Badge tone="neutral">{r.role}</Badge>
                      <span>·</span>
                      <span style={{ color: r.closesIn.includes('hour') ? 'var(--warning)' : 'var(--fg-muted)' }}>
                        Closes in {r.closesIn}
                      </span>
                    </div>
                  </div>
                  <Btn variant="primary" size="sm" onClick={() => setPage('escrow')}>
                    <Icons.Star size={12} /> Leave a review
                  </Btn>
                </div>
              ))}
            </div>
          </section>
        )}

        {/* Profile section */}
        <section className="card" style={{ padding: 22 }}>
          <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 18 }}>
            <h2 style={{ fontSize: 16, fontWeight: 600, margin: 0 }}>Profile</h2>
            {savedFlash && <Badge tone="success" dot>Saved</Badge>}
          </div>

          <div style={{ display: 'flex', gap: 18, marginBottom: 22, alignItems: 'center' }}>
            <div style={{ position: 'relative' }}>
              <Avatar name="Aria Northcrest" size={84} />
              <button style={{
                position: 'absolute', bottom: -2, right: -2,
                width: 28, height: 28, borderRadius: '50%',
                background: 'var(--surface)', border: '1px solid var(--border-strong)',
                color: 'var(--fg)', cursor: 'pointer',
                display: 'grid', placeItems: 'center', boxShadow: 'var(--shadow-sm)'
              }}>
                <Icons.Camera size={13} />
              </button>
            </div>
            <div style={{ flex: 1 }}>
              <div style={{ fontSize: 16, fontWeight: 600 }}>Aria Northcrest</div>
              <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginTop: 4 }}>
                <Badge tone="success" dot><Icons.Shield size={10} /> Verified</Badge>
                <span style={{ fontSize: 12, color: 'var(--fg-subtle)' }}>Member since Aug 2019</span>
              </div>
              <div style={{ marginTop: 8, fontSize: 12.5, color: 'var(--fg-muted)' }}>
                <span className="mono" style={{ background: 'var(--bg-muted)', padding: '2px 6px', borderRadius: 4 }}>aria.northcrest@slparcels.io</span>
              </div>
            </div>
            <div style={{ fontSize: 12, color: 'var(--fg-subtle)' }}>
              <a style={{ cursor: 'pointer' }} className="muted">Drop image to replace</a>
            </div>
          </div>

          <div style={{ display: 'grid', gridTemplateColumns: '1fr', gap: 14 }}>
            <div>
              <label className="field-label">Display name</label>
              <input className="input" value={displayName} onChange={(e) => setDisplayName(e.target.value)} />
            </div>
            <div>
              <label className="field-label">Bio</label>
              <textarea className="textarea" rows={3} value={bio} onChange={(e) => setBio(e.target.value)} />
              <div className="field-help">{bio.length} / 280</div>
            </div>
            <div style={{ display: 'flex', justifyContent: 'flex-end' }}>
              <Btn variant="primary" onClick={() => { setSavedFlash(true); setTimeout(() => setSavedFlash(false), 1600); }}>Save changes</Btn>
            </div>
          </div>
        </section>

        {/* Cancellation history */}
        <section>
          <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 12 }}>
            <h2 style={{ fontSize: 16, fontWeight: 600, margin: 0 }}>Cancellation history</h2>
            <span style={{ fontSize: 12, color: 'var(--fg-subtle)' }}>{cancellations.length} total</span>
          </div>
          <div className="card" style={{ overflow: 'hidden' }}>
            {cancellations.map((c, i) => (
              <div key={c.id} style={{ borderTop: i === 0 ? 'none' : '1px solid var(--border-subtle)' }}>
                <div style={{ padding: '14px 18px', display: 'grid', gridTemplateColumns: '1fr 110px 110px 130px 24px', alignItems: 'center', gap: 12 }}>
                  <div>
                    <div style={{ fontSize: 13.5, fontWeight: 500 }}>{c.parcel}</div>
                    <div style={{ fontSize: 11.5, color: 'var(--fg-subtle)', marginTop: 2 }}>{c.date} · {c.hadBids ? 'Had bids' : 'No bids'}</div>
                  </div>
                  <Badge tone="neutral">{c.status}</Badge>
                  {c.penalty ? <Badge tone="danger">{c.penalty}</Badge> : <span style={{ fontSize: 12, color: 'var(--fg-faint)' }}>—</span>}
                  <button className="btn btn--ghost btn--sm" disabled={!c.reason}
                    onClick={() => setOpenCancel(openCancel === c.id ? null : c.id)}
                    style={{ justifySelf: 'start', opacity: c.reason ? 1 : 0.4 }}>
                    {c.reason ? (openCancel === c.id ? 'Hide reason' : 'Show reason') : 'No reason'}
                  </button>
                  <Icons.ChevronRight size={14} style={{ color: 'var(--fg-faint)' }} />
                </div>
                {openCancel === c.id && c.reason && (
                  <div style={{ padding: '0 18px 16px 18px' }}>
                    <div style={{ background: 'var(--bg-subtle)', border: '1px solid var(--border-subtle)', borderRadius: 8, padding: 12, fontSize: 13, color: 'var(--fg-muted)', lineHeight: 1.55 }}>
                      {c.reason}
                    </div>
                  </div>
                )}
              </div>
            ))}
          </div>
        </section>
      </div>

      {/* Sidebar */}
      <div style={{ display: 'flex', flexDirection: 'column', gap: 16, position: 'sticky', top: 'calc(var(--header-h) + 16px)' }}>
        <div className="card" style={{ padding: 18 }}>
          <div style={{ fontSize: 12, color: 'var(--fg-subtle)', textTransform: 'uppercase', letterSpacing: '0.05em', fontWeight: 600, marginBottom: 6 }}>Account</div>
          <div style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
            <Stat label="Account standing"  value={<Badge tone="success" dot>Good</Badge>} />
            <Stat label="Wallet available"   value={<L amount={D.WALLET_AVAILABLE} />} />
            <Stat label="In escrow"          value={<L amount={D.WALLET_RESERVED} />} />
            <div style={{ height: 1, background: 'var(--border-subtle)' }} />
            <Stat label="Active bids"        value={<span className="bold">7</span>} />
            <Stat label="Won this month"     value={<span className="bold" style={{ color: 'var(--success)' }}>3</span>} />
            <Stat label="Active listings"    value={<span className="bold">2</span>} />
          </div>
        </div>

        <div className="card" style={{ padding: 18 }}>
          <div style={{ fontSize: 12, color: 'var(--fg-subtle)', textTransform: 'uppercase', letterSpacing: '0.05em', fontWeight: 600, marginBottom: 12 }}>Quick actions</div>
          <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
            <Btn variant="secondary" block onClick={() => setPage('browse')}><Icons.Search size={13} /> Browse parcels</Btn>
            <Btn variant="secondary" block onClick={() => setPage('wallet')}><Icons.Plus size={13} /> Add funds</Btn>
            <Btn variant="secondary" block onClick={() => setPage('create')}><Icons.Tag size={13} /> List a parcel</Btn>
          </div>
        </div>
      </div>
    </div>
  );
}

function Stat({ label, value }) {
  return (
    <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
      <span style={{ fontSize: 13, color: 'var(--fg-muted)' }}>{label}</span>
      <span style={{ fontSize: 13.5 }}>{value}</span>
    </div>
  );
}

// ────────────────────────────────────────────────────────────────────────
// BIDS
// ────────────────────────────────────────────────────────────────────────

function DashboardBids({ setPage, setAuctionId }) {
  const D = window.SLP_DATA;
  const [filter, setFilter] = React.useState('all');
  const [shown, setShown] = React.useState(8);

  // Synthesize bid records from auctions
  const records = D.AUCTIONS.slice(0, 16).map((p, i) => {
    const isWon  = i % 6 === 2;
    const isLost = i % 6 === 4;
    const isActive = !isWon && !isLost && p.minutesLeft > 0;
    const status = isWon ? 'won' : isLost ? 'lost' : 'active';
    const yourBid = p.currentBid - (i % 3 === 1 ? 250 : 0) - (status === 'lost' ? 800 : 0);
    const outbid = status === 'active' && i % 3 === 1;
    const placed = ['2 min ago','11 min ago','38 min ago','1 hr ago','3 hr ago','6 hr ago','1 day ago','2 days ago'][i % 8];
    return { parcel: p, status, yourBid, outbid, placed };
  });

  const counts = {
    all: records.length,
    active: records.filter(r => r.status === 'active').length,
    won: records.filter(r => r.status === 'won').length,
    lost: records.filter(r => r.status === 'lost').length,
  };
  const filtered = filter === 'all' ? records : records.filter(r => r.status === filter);
  const visible = filtered.slice(0, shown);

  const filters = [
    { id: 'all',    label: 'All' },
    { id: 'active', label: 'Active' },
    { id: 'won',    label: 'Won' },
    { id: 'lost',   label: 'Lost' },
  ];

  const open = (id) => { setAuctionId(id); setPage('auction'); };

  return (
    <div>
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 18, flexWrap: 'wrap', gap: 12 }}>
        <div style={{ display: 'flex', gap: 6, padding: 4, background: 'var(--bg-muted)', borderRadius: 'var(--r-md)' }}>
          {filters.map((f) => (
            <button key={f.id} onClick={() => { setFilter(f.id); setShown(8); }}
              style={{
                padding: '6px 14px', borderRadius: 'var(--r-sm)',
                border: 'none',
                background: filter === f.id ? 'var(--surface)' : 'transparent',
                boxShadow: filter === f.id ? 'var(--shadow-xs)' : 'none',
                color: filter === f.id ? 'var(--fg)' : 'var(--fg-muted)',
                fontSize: 13, fontWeight: 500, cursor: 'pointer',
                display: 'flex', alignItems: 'center', gap: 6,
              }}>
              {f.label}
              <span style={{ fontSize: 11, color: 'var(--fg-subtle)', fontWeight: 600,
                             background: filter === f.id ? 'var(--bg-muted)' : 'transparent',
                             padding: '1px 6px', borderRadius: 999 }}>{counts[f.id]}</span>
            </button>
          ))}
        </div>
        <div style={{ fontSize: 12.5, color: 'var(--fg-subtle)' }}>
          Showing {visible.length} of {filtered.length}
        </div>
      </div>

      {filtered.length === 0 && (
        <EmptyState
          icon={<Icons.Gavel size={32} />}
          title={filter === 'all' ? 'No bids yet' : `No ${filter} bids`}
          desc={filter === 'all' ? 'When you place a bid, it will show up here.' : `You have no ${filter} bids right now.`}
          cta={filter === 'all' ? <Btn variant="primary" onClick={() => setPage('browse')}>Browse parcels</Btn> : null}
        />
      )}

      {filtered.length > 0 && (
        <div className="card" style={{ overflow: 'hidden' }}>
          <div style={{ padding: '12px 18px', display: 'grid', gridTemplateColumns: '64px 1fr 130px 140px 140px 130px 28px', fontSize: 11, fontWeight: 600, letterSpacing: '0.05em', textTransform: 'uppercase', color: 'var(--fg-subtle)', borderBottom: '1px solid var(--border)', background: 'var(--bg-subtle)' }}>
            <div></div><div>Parcel</div><div>Your bid</div><div>Top bid</div><div>Status</div><div>Placed</div><div></div>
          </div>
          {visible.map((r, i) => {
            const p = r.parcel;
            return (
              <div key={p.id} onClick={() => open(p.id)}
                style={{ padding: '14px 18px', display: 'grid', gridTemplateColumns: '64px 1fr 130px 140px 140px 130px 28px',
                         alignItems: 'center', fontSize: 13.5, borderTop: i === 0 ? 'none' : '1px solid var(--border-subtle)', cursor: 'pointer' }}
                onMouseEnter={(e) => e.currentTarget.style.background = 'var(--bg-subtle)'}
                onMouseLeave={(e) => e.currentTarget.style.background = 'transparent'}>
                <div style={{ width: 52, borderRadius: 6, overflow: 'hidden' }}>
                  <ParcelImage parcel={p} ratio="1/1" showSave={false} />
                </div>
                <div style={{ minWidth: 0 }}>
                  <div style={{ fontWeight: 500, marginBottom: 2, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{p.title}</div>
                  <div style={{ fontSize: 11.5, fontFamily: 'var(--font-mono)', color: 'var(--fg-subtle)' }}>{p.coords}</div>
                </div>
                <div className="tabular bold"><L amount={r.yourBid} /></div>
                <div className="tabular muted"><L amount={p.currentBid} /></div>
                <div>
                  {r.status === 'won' && <Badge tone="success" dot>You won</Badge>}
                  {r.status === 'lost' && <Badge tone="neutral">Lost</Badge>}
                  {r.status === 'active' && r.outbid && <Badge tone="danger" dot>Outbid</Badge>}
                  {r.status === 'active' && !r.outbid && <Badge tone="brand" dot>Top bidder</Badge>}
                </div>
                <div className="tabular" style={{ color: 'var(--fg-muted)', fontSize: 12.5 }}>{r.placed}</div>
                <Icons.ChevronRight size={14} style={{ color: 'var(--fg-faint)' }} />
              </div>
            );
          })}
        </div>
      )}

      {visible.length < filtered.length && (
        <div style={{ display: 'flex', justifyContent: 'center', marginTop: 18 }}>
          <Btn variant="secondary" onClick={() => setShown(s => s + 8)}>Load more</Btn>
        </div>
      )}
    </div>
  );
}

// ────────────────────────────────────────────────────────────────────────
// LISTINGS
// ────────────────────────────────────────────────────────────────────────

function DashboardListings({ setPage, setAuctionId }) {
  const D = window.SLP_DATA;
  const [filter, setFilter] = React.useState('all');
  const [cancelTarget, setCancelTarget] = React.useState(null);
  const [permaBan] = React.useState(false); // demo flag — disables create button

  const listings = [
    { id: 'l1', parcel: D.AUCTIONS[0],  status: 'ACTIVE',                bidCount: 14, hasBids: true,  endsAt: '3 days',  reserveMet: true },
    { id: 'l2', parcel: D.AUCTIONS[3],  status: 'ACTIVE',                bidCount: 7,  hasBids: true,  endsAt: '6 hours', reserveMet: false },
    { id: 'l3', parcel: D.AUCTIONS[5],  status: 'DRAFT',                 bidCount: 0,  hasBids: false, endsAt: null,      reserveMet: null },
    { id: 'l4', parcel: D.AUCTIONS[8],  status: 'VERIFICATION_PENDING',  bidCount: 0,  hasBids: false, endsAt: null,      reserveMet: null },
    { id: 'l5', parcel: D.AUCTIONS[11], status: 'ENDED',                 bidCount: 22, hasBids: true,  endsAt: '2 days ago', reserveMet: true },
    { id: 'l6', parcel: D.AUCTIONS[14], status: 'CANCELLED',             bidCount: 3,  hasBids: true,  endsAt: '1 wk ago', reserveMet: false },
    { id: 'l7', parcel: D.AUCTIONS[17], status: 'SUSPENDED',             bidCount: 0,  hasBids: false, endsAt: null,      reserveMet: null },
    { id: 'l8', parcel: D.AUCTIONS[20], status: 'DRAFT_PAID',            bidCount: 0,  hasBids: false, endsAt: null,      reserveMet: null },
  ];

  const matches = (l) => {
    if (filter === 'all') return true;
    if (filter === 'active')    return l.status === 'ACTIVE';
    if (filter === 'drafts')    return l.status === 'DRAFT' || l.status === 'DRAFT_PAID' || l.status === 'VERIFICATION_PENDING';
    if (filter === 'ended')     return l.status === 'ENDED';
    if (filter === 'cancelled') return l.status === 'CANCELLED';
    if (filter === 'suspended') return l.status === 'SUSPENDED';
    return true;
  };

  const counts = {
    all: listings.length,
    active: listings.filter(l => l.status === 'ACTIVE').length,
    drafts: listings.filter(l => ['DRAFT','DRAFT_PAID','VERIFICATION_PENDING'].includes(l.status)).length,
    ended: listings.filter(l => l.status === 'ENDED').length,
    cancelled: listings.filter(l => l.status === 'CANCELLED').length,
    suspended: listings.filter(l => l.status === 'SUSPENDED').length,
  };

  const filtered = listings.filter(matches);
  const filters = [
    ['all', 'All'], ['active', 'Active'], ['drafts', 'Drafts'],
    ['ended', 'Ended'], ['cancelled', 'Cancelled'],
    ...(counts.suspended > 0 ? [['suspended', 'Suspended']] : []),
  ];

  const open = (l) => {
    if (l.status === 'DRAFT' || l.status === 'DRAFT_PAID') {
      setPage('create');
    } else {
      setAuctionId(l.parcel.id);
      setPage('auction');
    }
  };

  return (
    <div>
      <div style={{ display: 'flex', alignItems: 'flex-end', justifyContent: 'space-between', marginBottom: 14, flexWrap: 'wrap', gap: 12 }}>
        <div>
          <h2 style={{ fontSize: 18, fontWeight: 600, margin: 0 }}>My listings</h2>
          <div style={{ fontSize: 12.5, color: 'var(--fg-subtle)', marginTop: 4 }}>
            {listings.length} total · {counts.active} active · {counts.drafts} drafts
          </div>
        </div>
        <div title={permaBan ? 'Listing privileges suspended' : ''}>
          <Btn variant="primary" disabled={permaBan} onClick={() => setPage('create')}>
            <Icons.Plus size={14} /> Create new listing
          </Btn>
        </div>
      </div>

      <div style={{ display: 'flex', gap: 8, marginBottom: 18, flexWrap: 'wrap' }}>
        {filters.map(([k, l]) => (
          <Chip key={k} active={filter === k} onClick={() => setFilter(k)}>
            {l}
            <span style={{ marginLeft: 4, color: filter === k ? 'rgba(255,255,255,.6)' : 'var(--fg-faint)', fontVariantNumeric: 'tabular-nums', fontWeight: 600 }}>
              {counts[k]}
            </span>
          </Chip>
        ))}
      </div>

      {filtered.length === 0 && (
        listings.length === 0 ? (
          <EmptyState
            icon={<Icons.Tag size={32} />}
            title="No listings yet"
            desc="Create your first listing to start auctioning a parcel."
            cta={<Btn variant="primary" onClick={() => setPage('create')}><Icons.Plus size={14} /> Create your first listing</Btn>}
          />
        ) : (
          <EmptyState
            icon={<Icons.Filter size={32} />}
            title="No listings in this filter"
            desc="Try a different filter to see your listings."
          />
        )
      )}

      {filtered.length > 0 && (
        <div className="card" style={{ overflow: 'hidden' }}>
          <div style={{ padding: '12px 18px', display: 'grid', gridTemplateColumns: '64px 1.4fr 150px 130px 140px 140px 28px', fontSize: 11, fontWeight: 600, letterSpacing: '0.05em', textTransform: 'uppercase', color: 'var(--fg-subtle)', borderBottom: '1px solid var(--border)', background: 'var(--bg-subtle)' }}>
            <div></div><div>Parcel</div><div>Status</div><div>Starting</div><div>Current bid</div><div>Time</div><div></div>
          </div>
          {filtered.map((l, i) => (
            <div key={l.id}
              style={{ padding: '14px 18px', display: 'grid', gridTemplateColumns: '64px 1.4fr 150px 130px 140px 140px 28px',
                       alignItems: 'center', fontSize: 13.5, borderTop: i === 0 ? 'none' : '1px solid var(--border-subtle)', cursor: 'pointer' }}
              onClick={() => open(l)}
              onMouseEnter={(e) => e.currentTarget.style.background = 'var(--bg-subtle)'}
              onMouseLeave={(e) => e.currentTarget.style.background = 'transparent'}>
              <div style={{ width: 52, borderRadius: 6, overflow: 'hidden' }}>
                <ParcelImage parcel={l.parcel} ratio="1/1" showSave={false} />
              </div>
              <div style={{ minWidth: 0 }}>
                <div style={{ fontWeight: 500, marginBottom: 2, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{l.parcel.title}</div>
                <div style={{ fontSize: 11.5, fontFamily: 'var(--font-mono)', color: 'var(--fg-subtle)' }}>{l.parcel.coords}</div>
              </div>
              <div>
                <ListingStatusBadge status={l.status} />
              </div>
              <div className="tabular muted"><L amount={l.parcel.startBid} /></div>
              <div className="tabular">
                {l.bidCount > 0 ? (
                  <div>
                    <L amount={l.parcel.currentBid} />
                    {l.reserveMet === false && (
                      <div style={{ fontSize: 11, color: 'var(--warning)', marginTop: 1 }}>Reserve not met</div>
                    )}
                  </div>
                ) : (
                  <span className="muted" style={{ fontSize: 12.5 }}>{l.bidCount} bids</span>
                )}
              </div>
              <div className="tabular" style={{ fontSize: 12.5, color: 'var(--fg-muted)' }}>
                {l.endsAt ? (l.status === 'ACTIVE' ? `Ends in ${l.endsAt}` : l.endsAt) : '—'}
              </div>
              <Icons.ChevronRight size={14} style={{ color: 'var(--fg-faint)' }} />
            </div>
          ))}
        </div>
      )}

      {/* Cancel modal — wired but trigger is in detail; show as dismissed */}
      {cancelTarget && (
        <CancelListingModal listing={cancelTarget} onClose={() => setCancelTarget(null)} />
      )}
    </div>
  );
}

function ListingStatusBadge({ status }) {
  const map = {
    ACTIVE:               { tone: 'success', label: 'Active', dot: true },
    DRAFT:                { tone: 'neutral', label: 'Draft' },
    DRAFT_PAID:           { tone: 'info',    label: 'Draft · paid' },
    VERIFICATION_PENDING: { tone: 'warning', label: 'Reviewing' },
    ENDED:                { tone: 'neutral', label: 'Ended' },
    CANCELLED:            { tone: 'danger',  label: 'Cancelled' },
    SUSPENDED:            { tone: 'danger',  label: 'Suspended' },
    EXPIRED:              { tone: 'neutral', label: 'Expired' },
    COMPLETED:            { tone: 'success', label: 'Completed' },
  };
  const c = map[status] || { tone: 'neutral', label: status };
  return <Badge tone={c.tone} dot={c.dot}>{c.label}</Badge>;
}

function CancelListingModal({ listing, onClose }) {
  const [reason, setReason] = React.useState('');
  return (
    <div style={{ position: 'fixed', inset: 0, background: 'rgba(0,0,0,.5)', zIndex: 300, display: 'grid', placeItems: 'center', padding: 20 }} onClick={onClose}>
      <div onClick={(e) => e.stopPropagation()} style={{ background: 'var(--surface)', width: 480, maxWidth: '100%', borderRadius: 'var(--r-lg)', overflow: 'hidden' }}>
        <div style={{ padding: '18px 22px', borderBottom: '1px solid var(--border)' }}>
          <div style={{ fontSize: 16, fontWeight: 600 }}>Cancel listing?</div>
        </div>
        <div style={{ padding: 22 }}>
          <p style={{ marginTop: 0, fontSize: 13.5, color: 'var(--fg-muted)' }}>
            This listing has bids — cancelling will refund all bidders and may apply a cancellation penalty per the seller policy.
          </p>
          <label className="field-label">Reason (optional)</label>
          <textarea className="textarea" value={reason} onChange={(e) => setReason(e.target.value)} placeholder="Help us understand why…" />
        </div>
        <div style={{ padding: '14px 22px', borderTop: '1px solid var(--border)', display: 'flex', justifyContent: 'flex-end', gap: 8 }}>
          <Btn variant="ghost" onClick={onClose}>Keep listing</Btn>
          <Btn variant="primary" style={{ background: 'var(--danger)', borderColor: 'var(--danger)' }} onClick={onClose}>Cancel listing</Btn>
        </div>
      </div>
    </div>
  );
}

function EmptyState({ icon, title, desc, cta }) {
  return (
    <div className="card" style={{ padding: '48px 24px', textAlign: 'center' }}>
      <div style={{ width: 64, height: 64, margin: '0 auto 14px', borderRadius: 16, background: 'var(--bg-muted)', display: 'grid', placeItems: 'center', color: 'var(--fg-faint)' }}>
        {icon}
      </div>
      <div style={{ fontSize: 16, fontWeight: 600, marginBottom: 4 }}>{title}</div>
      <div style={{ fontSize: 13.5, color: 'var(--fg-muted)', maxWidth: 380, margin: '0 auto 18px' }}>{desc}</div>
      {cta}
    </div>
  );
}

// ────────────────────────────────────────────────────────────────────────
// VERIFY
// ────────────────────────────────────────────────────────────────────────

function VerifyPage({ setPage }) {
  const [code, setCode] = React.useState(null);
  const [status, setStatus] = React.useState('idle'); // idle | waiting | detected | failed
  const [copied, setCopied] = React.useState(false);
  const [secondsLeft, setSecondsLeft] = React.useState(0);

  React.useEffect(() => {
    if (!code) return;
    const t = setInterval(() => setSecondsLeft(s => Math.max(0, s - 1)), 1000);
    return () => clearInterval(t);
  }, [code]);

  const generate = () => {
    const c = String(Math.floor(100000 + Math.random() * 900000));
    setCode(c);
    setStatus('waiting');
    setSecondsLeft(600);
  };
  const copy = () => {
    if (!code) return;
    navigator.clipboard?.writeText(code);
    setCopied(true);
    setTimeout(() => setCopied(false), 1400);
  };
  const refresh = () => {
    setStatus('detected');
    setTimeout(() => setPage('dashboard'), 1500);
  };

  const mins = Math.floor(secondsLeft / 60);
  const secs = secondsLeft % 60;

  return (
    <div className="page container" style={{ padding: '40px 24px 80px', maxWidth: 720 }}>
      <div style={{ fontSize: 12, color: 'var(--fg-subtle)', marginBottom: 8 }}>
        <a className="muted" onClick={() => setPage('home')} style={{ cursor: 'pointer' }}>Home</a> / Verify
      </div>
      <h1 style={{ fontSize: 28, fontWeight: 700, letterSpacing: '-0.02em', margin: 0 }}>Verify your Second Life avatar</h1>
      <p style={{ fontSize: 15, color: 'var(--fg-muted)', marginTop: 12, lineHeight: 1.55, maxWidth: 560 }}>
        Linking your SL avatar lets you bid, list, and receive payouts. This is a one-time step — once verified, you won't see this page again.
      </p>

      <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 22, marginTop: 32 }}>

        {/* Left: instructions */}
        <div>
          <div style={{ fontSize: 11, fontWeight: 600, letterSpacing: '0.06em', textTransform: 'uppercase', color: 'var(--brand)', marginBottom: 12 }}>How it works</div>
          <ol style={{ paddingLeft: 0, listStyle: 'none', margin: 0, display: 'flex', flexDirection: 'column', gap: 14 }}>
            {[
              ['Generate a code', 'Tap the button on the right to issue a fresh 6-digit code.'],
              ['Visit a verification terminal', <span>In-world, find any <span className="mono" style={{ background: 'var(--bg-muted)', padding: '1px 6px', borderRadius: 4 }}>SLPA Verifier</span> object — they’re at every major hub.</span>],
              ['Touch the terminal', 'It will prompt you to enter the code via the in-world chat.'],
              ['Paste the code', 'The terminal links your avatar to your SLParcels account.'],
              ['Return here', 'This page auto-detects when verification completes.'],
            ].map(([title, desc], i) => (
              <li key={i} style={{ display: 'flex', gap: 12 }}>
                <div style={{
                  width: 26, height: 26, flexShrink: 0,
                  borderRadius: '50%', background: 'var(--brand)', color: 'white',
                  display: 'grid', placeItems: 'center',
                  fontSize: 12, fontWeight: 700,
                }}>{i + 1}</div>
                <div>
                  <div style={{ fontSize: 14, fontWeight: 600 }}>{title}</div>
                  <div style={{ fontSize: 13, color: 'var(--fg-muted)', lineHeight: 1.5, marginTop: 2 }}>{desc}</div>
                </div>
              </li>
            ))}
          </ol>
        </div>

        {/* Right: code panel */}
        <div className="card" style={{ padding: 24, alignSelf: 'flex-start' }}>
          {!code && (
            <div>
              <div style={{ fontSize: 14, fontWeight: 600, marginBottom: 6 }}>No active code</div>
              <p style={{ fontSize: 13, color: 'var(--fg-muted)', lineHeight: 1.55, marginTop: 0 }}>
                Codes are valid for 10 minutes. You can regenerate any time.
              </p>
              <Btn variant="primary" block onClick={generate}>
                <Icons.Shield size={14} /> Generate verification code
              </Btn>
            </div>
          )}
          {code && (
            <div>
              <div style={{ fontSize: 11, fontWeight: 600, letterSpacing: '0.06em', textTransform: 'uppercase', color: 'var(--fg-subtle)', marginBottom: 8 }}>
                Your code
              </div>
              <div style={{ display: 'flex', gap: 6, marginBottom: 12 }}>
                {code.split('').map((ch, i) => (
                  <div key={i} style={{
                    flex: 1, padding: '14px 0',
                    border: '1px solid var(--border-strong)', borderRadius: 'var(--r-md)',
                    background: 'var(--bg-subtle)',
                    textAlign: 'center', fontFamily: 'var(--font-mono)',
                    fontSize: 24, fontWeight: 600, letterSpacing: '0.05em',
                  }}>{ch}</div>
                ))}
              </div>
              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', fontSize: 12.5, color: 'var(--fg-subtle)', marginBottom: 14 }}>
                <span>Expires in <span className="mono">{String(mins).padStart(2,'0')}:{String(secs).padStart(2,'0')}</span></span>
                <button onClick={copy} className="btn btn--ghost btn--sm" style={{ padding: '4px 8px' }}>
                  <Icons.Copy size={12} /> {copied ? 'Copied' : 'Copy'}
                </button>
              </div>

              <div style={{ background: 'var(--bg-subtle)', borderRadius: 'var(--r-md)', padding: 12, marginBottom: 14, display: 'flex', gap: 10, alignItems: 'center' }}>
                <div style={{ position: 'relative', width: 10, height: 10 }}>
                  <span style={{ position: 'absolute', inset: 0, borderRadius: '50%', background: status === 'detected' ? 'var(--success)' : 'var(--brand)' }} />
                  <span style={{ position: 'absolute', inset: 0, borderRadius: '50%', background: status === 'detected' ? 'var(--success)' : 'var(--brand)', opacity: .35, animation: 'pulse 1.6s ease-out infinite' }} />
                </div>
                <div style={{ flex: 1, fontSize: 13 }}>
                  {status === 'waiting' && 'Waiting for in-world verification…'}
                  {status === 'detected' && <span style={{ color: 'var(--success)', fontWeight: 600 }}>Verified! Redirecting…</span>}
                  {status === 'failed' && <span style={{ color: 'var(--danger)' }}>Couldn’t verify. Try again.</span>}
                </div>
              </div>

              <Btn variant="secondary" block onClick={refresh} disabled={status === 'detected'}>
                <Icons.Refresh size={13} /> I’ve entered the code — refresh status
              </Btn>
              <Btn variant="ghost" size="sm" block onClick={generate} style={{ marginTop: 6 }}>
                Regenerate code
              </Btn>
            </div>
          )}
        </div>
      </div>

      <div style={{ marginTop: 28, padding: 14, background: 'var(--bg-subtle)', borderRadius: 'var(--r-md)', display: 'flex', gap: 10, fontSize: 13, color: 'var(--fg-muted)' }}>
        <Icons.AlertCircle size={16} style={{ flexShrink: 0, marginTop: 1, color: 'var(--brand)' }} />
        <div>
          <span className="bold" style={{ color: 'var(--fg)' }}>Auto-detection is on.</span> This page checks every 5 seconds and will redirect you the moment your avatar is linked.
        </div>
      </div>
    </div>
  );
}

// ────────────────────────────────────────────────────────────────────────
// ROUTER
// ────────────────────────────────────────────────────────────────────────

function DashboardPage({ setPage, setAuctionId, dashboardTab, setDashboardTab }) {
  return (
    <DashboardShell tab={dashboardTab} setTab={setDashboardTab} setPage={setPage}>
      {dashboardTab === 'overview' && <DashboardOverview setPage={setPage} setAuctionId={setAuctionId} />}
      {dashboardTab === 'bids'     && <DashboardBids     setPage={setPage} setAuctionId={setAuctionId} />}
      {dashboardTab === 'listings' && <DashboardListings setPage={setPage} setAuctionId={setAuctionId} />}
    </DashboardShell>
  );
}

window.DashboardPage = DashboardPage;
window.VerifyPage = VerifyPage;
