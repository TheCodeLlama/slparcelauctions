// page-utility.jsx — Notifications, Saved, Settings, Settings-notifications, User profile, User listings

function NotificationsPage({ setPage, setAuctionId }) {
  const D = window.SLP_DATA;
  const [filter, setFilter] = React.useState('all');
  const groups = [
    ['all', 'All', D.NOTIFICATIONS.length],
    ['unread', 'Unread', D.NOTIFICATIONS.filter(n => n.unread).length],
    ['outbid', 'Outbid', D.NOTIFICATIONS.filter(n => n.type === 'outbid').length],
    ['won', 'Won', D.NOTIFICATIONS.filter(n => n.type === 'won').length],
    ['ending', 'Ending', D.NOTIFICATIONS.filter(n => n.type === 'ending').length],
    ['system', 'System', D.NOTIFICATIONS.filter(n => n.type === 'system').length],
  ];
  // Synthesize a longer feed
  const feed = Array.from({length: 18}, (_, i) => {
    const base = D.NOTIFICATIONS[i % D.NOTIFICATIONS.length];
    return { ...base, id: 'fn'+i, unread: i < 3 };
  });
  const filtered = filter === 'all' ? feed : filter === 'unread' ? feed.filter(n => n.unread) : feed.filter(n => n.type === filter);

  return (
    <div className="page container" style={{ padding: '28px 24px 80px' }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-end', marginBottom: 24 }}>
        <div>
          <div style={{ fontSize: 12, color: 'var(--fg-subtle)', marginBottom: 4 }}>
            <a className="muted" onClick={() => setPage('home')} style={{ cursor: 'pointer' }}>Home</a> / Notifications
          </div>
          <h1 style={{ fontSize: 28, fontWeight: 700, letterSpacing: '-0.02em', margin: 0 }}>Notifications</h1>
          <p style={{ fontSize: 14, color: 'var(--fg-muted)', marginTop: 4 }}>Activity from your bids, listings, and account.</p>
        </div>
        <Btn variant="secondary" onClick={() => setPage('settings-notifications')}><Icons.User size={14} /> Settings</Btn>
      </div>
      <div style={{ display: 'grid', gridTemplateColumns: '220px 1fr', gap: 24, alignItems: 'flex-start' }}>
        <div className="card" style={{ padding: 8 }}>
          {groups.map(([k, l, c]) => (
            <button key={k} onClick={() => setFilter(k)} style={{
              display: 'flex', justifyContent: 'space-between', alignItems: 'center', width: '100%',
              padding: '8px 12px', borderRadius: 'var(--r-sm)', border: 'none',
              background: filter === k ? 'var(--bg-muted)' : 'transparent',
              color: filter === k ? 'var(--fg)' : 'var(--fg-muted)',
              fontSize: 13.5, fontWeight: 500, cursor: 'pointer', marginBottom: 2,
            }}>
              <span>{l}</span>
              <span style={{ fontSize: 11.5, color: 'var(--fg-subtle)', fontWeight: 600 }}>{c}</span>
            </button>
          ))}
        </div>
        <div className="card" style={{ overflow: 'hidden' }}>
          {filtered.length === 0 && (
            <div style={{ padding: '60px 20px', textAlign: 'center', color: 'var(--fg-muted)' }}>
              <Icons.Bell size={28} style={{ color: 'var(--fg-faint)', marginBottom: 8 }} />
              <div>No notifications in this filter.</div>
            </div>
          )}
          {filtered.map((n, i) => (
            <div key={n.id} style={{
              padding: '14px 18px', display: 'flex', gap: 14, cursor: 'pointer',
              borderTop: i === 0 ? 'none' : '1px solid var(--border-subtle)',
              background: n.unread ? 'var(--brand-soft)' : 'transparent',
            }}>
              <div style={{
                width: 32, height: 32, flexShrink: 0, borderRadius: 8, background: 'var(--bg-muted)',
                display: 'grid', placeItems: 'center',
                color: n.type === 'won' ? 'var(--success)' : n.type === 'outbid' ? 'var(--danger)' : 'var(--brand)',
              }}>
                {n.type === 'outbid' && <Icons.AlertCircle size={15} />}
                {n.type === 'ending' && <Icons.Clock size={15} />}
                {n.type === 'won' && <Icons.CheckCircle size={15} />}
                {n.type === 'review' && <Icons.Star size={15} filled />}
                {n.type === 'system' && <Icons.Wallet size={15} />}
              </div>
              <div style={{ flex: 1, minWidth: 0 }}>
                <div style={{ fontSize: 14, color: 'var(--fg)' }}>{n.text}</div>
                <div style={{ fontSize: 11.5, color: 'var(--fg-subtle)', marginTop: 2 }}>{n.time}</div>
              </div>
              {n.unread && <div style={{ width: 8, height: 8, borderRadius: '50%', background: 'var(--brand)', marginTop: 8 }} />}
            </div>
          ))}
        </div>
      </div>
    </div>
  );
}

function SavedPage({ setPage, setAuctionId }) {
  const D = window.SLP_DATA;
  const [auth] = React.useState(true);
  const saved = D.AUCTIONS.filter(a => a.saved).slice(0, 8);
  if (!auth) {
    return (
      <div style={{ display: 'grid', placeItems: 'center', minHeight: 400, padding: 40 }}>
        <div className="card" style={{ padding: 40, textAlign: 'center', maxWidth: 380 }}>
          <Icons.Heart size={36} style={{ color: 'var(--fg-faint)', marginBottom: 12 }} />
          <div style={{ fontSize: 18, fontWeight: 600 }}>Sign in to see your saved parcels</div>
          <Btn variant="primary" onClick={() => setPage('login')} style={{ marginTop: 16 }}>Sign in</Btn>
        </div>
      </div>
    );
  }
  return (
    <div className="page container" style={{ padding: '28px 24px 80px' }}>
      <div style={{ marginBottom: 24 }}>
        <h1 style={{ fontSize: 28, fontWeight: 700, letterSpacing: '-0.02em', margin: 0 }}>Saved parcels</h1>
        <p style={{ fontSize: 14, color: 'var(--fg-muted)', marginTop: 4 }}>{saved.length} saved · active only</p>
      </div>
      <div className="search-input" style={{ marginBottom: 18, maxWidth: 360 }}>
        <Icons.Search size={14} />
        <input className="input" placeholder="Filter saved…" />
      </div>
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(280px, 1fr))', gap: 16 }}>
        {saved.map(p => (
          <AuctionCard key={p.id} parcel={p} layout="standard" onClick={() => { setAuctionId(p.id); setPage('auction'); }} />
        ))}
      </div>
    </div>
  );
}

function SettingsNotificationsPage({ setPage }) {
  const [muted, setMuted] = React.useState(false);
  const [groups, setGroups] = React.useState({
    outbid: true, ending: true, listed: true, sold: true, review: true, system: false,
  });
  return (
    <div className="page container" style={{ padding: '28px 24px 80px', maxWidth: 760 }}>
      <div style={{ marginBottom: 24 }}>
        <div style={{ fontSize: 12, color: 'var(--fg-subtle)', marginBottom: 4 }}>
          <a className="muted" onClick={() => setPage('home')} style={{ cursor: 'pointer' }}>Home</a> / Settings / Notifications
        </div>
        <h1 style={{ fontSize: 28, fontWeight: 700, letterSpacing: '-0.02em', margin: 0 }}>Notification preferences</h1>
      </div>

      <div className="card" style={{ padding: 16, marginBottom: 16, display: 'flex', gap: 12, fontSize: 13, color: 'var(--fg-muted)' }}>
        <Icons.Info size={16} style={{ color: 'var(--brand)', flexShrink: 0, marginTop: 2 }} />
        <div>SLParcels delivers notifications by email and via in-world Second Life IM. The toggles below control SL IM delivery only.</div>
      </div>

      <div className="card" style={{ marginBottom: 24 }}>
        <div style={{ padding: 18, display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
          <div>
            <div style={{ fontSize: 14.5, fontWeight: 600 }}>Mute all SL IM</div>
            <div style={{ fontSize: 13, color: 'var(--fg-muted)', marginTop: 2 }}>Pause every in-world notification at once.</div>
          </div>
          <Toggle value={muted} onChange={setMuted} />
        </div>
        <div style={{ borderTop: '1px solid var(--border)', padding: '6px 0' }}>
          <div style={{ padding: '12px 18px', fontSize: 11, fontWeight: 600, letterSpacing: '0.06em', textTransform: 'uppercase', color: 'var(--fg-subtle)' }}>SL IM</div>
          {[
            ['outbid', 'Outbid', "When someone outbids you on an auction you're in."],
            ['ending', 'Auction ending soon', 'Auctions you bid on or saved that are about to end.'],
            ['listed', 'Listing active', 'When one of your listings goes live.'],
            ['sold', 'Listing sold', 'When one of your auctions ends with a winner.'],
            ['review', 'Review received', 'When a buyer or seller leaves you a review.'],
            ['system', 'System & wallet', 'Wallet activity, system messages, policy updates.'],
          ].map(([k, l, d]) => (
            <div key={k} style={{ padding: '12px 18px', display: 'flex', justifyContent: 'space-between', alignItems: 'center', borderTop: '1px solid var(--border-subtle)', opacity: muted ? 0.5 : 1 }}>
              <div style={{ flex: 1 }}>
                <div style={{ fontSize: 14, fontWeight: 500 }}>{l}</div>
                <div style={{ fontSize: 12.5, color: 'var(--fg-muted)', marginTop: 2 }}>{d}</div>
              </div>
              <Toggle value={groups[k]} onChange={(v) => setGroups({...groups, [k]: v})} disabled={muted} />
            </div>
          ))}
        </div>
      </div>

      <div className="card" style={{ padding: 22, borderColor: 'var(--danger)' }}>
        <div style={{ fontSize: 11, fontWeight: 600, letterSpacing: '0.06em', textTransform: 'uppercase', color: 'var(--danger)', marginBottom: 8 }}>Danger zone</div>
        <div style={{ fontSize: 16, fontWeight: 600 }}>Delete your account</div>
        <p style={{ fontSize: 13.5, color: 'var(--fg-muted)', lineHeight: 1.5, marginTop: 6 }}>
          Permanently delete your SLParcels account. This cannot be undone. Past auctions, bids, and reviews will remain visible attributed to "Deleted user".
        </p>
        <Btn variant="secondary" onClick={() => setPage('goodbye')} style={{ borderColor: 'var(--danger)', color: 'var(--danger)' }}>Delete account</Btn>
      </div>
    </div>
  );
}

function Toggle({ value, onChange, disabled }) {
  return (
    <button onClick={() => !disabled && onChange(!value)} disabled={disabled}
      style={{
        width: 38, height: 22, borderRadius: 999, border: 'none',
        background: value ? 'var(--brand)' : 'var(--border-strong)',
        position: 'relative', cursor: disabled ? 'not-allowed' : 'pointer',
        transition: 'background .15s', opacity: disabled ? 0.6 : 1, flexShrink: 0,
      }}>
      <span style={{
        position: 'absolute', top: 2, left: value ? 18 : 2, width: 18, height: 18,
        borderRadius: '50%', background: 'white', boxShadow: '0 1px 2px rgba(0,0,0,.2)',
        transition: 'left .15s',
      }} />
    </button>
  );
}

function UserProfilePage({ setPage, setAuctionId }) {
  const D = window.SLP_DATA;
  const seller = D.SELLERS[2];
  const [tab, setTab] = React.useState('seller');
  const reviews = Array.from({length: 6}, (_, i) => ({
    id: 'r'+i, name: ['Aria N.', 'Otto W.', 'Sable V.', 'Devon H.', 'Mira K.', 'Nova B.'][i],
    rating: [5, 5, 4, 5, 5, 4][i],
    text: ['Smooth handover, exactly as described.', 'Excellent communication. Would buy again.', 'Took a couple days to transfer but went well.', 'Top-notch seller. Parcel was in pristine condition.', 'Quick and professional.', 'Great experience overall.'][i],
    time: ['2 days ago', '1 wk ago', '2 wks ago', '1 mo ago', '2 mo ago', '3 mo ago'][i],
  }));
  const listings = D.AUCTIONS.slice(0, 6);
  return (
    <div className="page container" style={{ padding: '28px 24px 80px' }}>
      <div className="card" style={{ padding: 28, marginBottom: 24, display: 'flex', gap: 22, alignItems: 'flex-start' }}>
        <Avatar name={seller.name} size={88} />
        <div style={{ flex: 1 }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
            <h1 style={{ fontSize: 24, fontWeight: 700, margin: 0 }}>{seller.name}</h1>
            <Badge tone="success" dot><Icons.Shield size={10} /> Verified</Badge>
          </div>
          <div style={{ fontSize: 13, color: 'var(--fg-subtle)', marginTop: 4 }}>
            SL: <span className="mono">{seller.name.toLowerCase().replace(' ', '.')}</span> · Member since {seller.member}
          </div>
          <p style={{ fontSize: 14, color: 'var(--fg-muted)', marginTop: 12, maxWidth: 540, lineHeight: 1.5 }}>
            Long-time builder and curator. I list residential and themed RP parcels with verified covenants. Quick handovers, fair prices.
          </p>
          <div style={{ display: 'flex', gap: 24, marginTop: 16 }}>
            <div><div style={{ fontSize: 11, color: 'var(--fg-subtle)', textTransform: 'uppercase', letterSpacing: '0.05em', fontWeight: 600 }}>As seller</div><div style={{ marginTop: 4 }}><StarRating value={seller.rating} /> <span style={{ fontSize: 12.5, color: 'var(--fg-subtle)' }}>· {seller.sales} sales</span></div></div>
            <div><div style={{ fontSize: 11, color: 'var(--fg-subtle)', textTransform: 'uppercase', letterSpacing: '0.05em', fontWeight: 600 }}>As buyer</div><div style={{ marginTop: 4 }}><StarRating value={4.8} /> <span style={{ fontSize: 12.5, color: 'var(--fg-subtle)' }}>· 34 deals</span></div></div>
            <div><div style={{ fontSize: 11, color: 'var(--fg-subtle)', textTransform: 'uppercase', letterSpacing: '0.05em', fontWeight: 600 }}>Completion</div><div style={{ marginTop: 4, fontWeight: 600 }}>{seller.completion}%</div></div>
          </div>
        </div>
      </div>

      <div style={{ marginBottom: 14, display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
        <h2 style={{ fontSize: 18, fontWeight: 600, margin: 0 }}>Active listings</h2>
        <a onClick={() => setPage('user-listings')} style={{ fontSize: 13, color: 'var(--brand)', cursor: 'pointer', fontWeight: 500 }}>View all listings →</a>
      </div>
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(260px, 1fr))', gap: 14, marginBottom: 36 }}>
        {listings.map(p => <AuctionCard key={p.id} parcel={p} layout="standard" onClick={() => { setAuctionId(p.id); setPage('auction'); }} />)}
      </div>

      <div style={{ borderBottom: '1px solid var(--border)', display: 'flex', gap: 4, marginBottom: 18 }}>
        {[['seller','As seller'],['buyer','As buyer']].map(([k, l]) => (
          <button key={k} onClick={() => setTab(k)} className="btn btn--ghost"
            style={{ borderRadius: 0, padding: '10px 14px', fontSize: 13.5, fontWeight: 500,
              borderBottom: '2px solid ' + (tab === k ? 'var(--brand)' : 'transparent'),
              color: tab === k ? 'var(--fg)' : 'var(--fg-muted)', marginBottom: -1 }}>{l}</button>
        ))}
      </div>
      <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
        {reviews.map(r => (
          <div key={r.id} className="card" style={{ padding: 16 }}>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start' }}>
              <div style={{ display: 'flex', gap: 10, alignItems: 'center' }}>
                <Avatar name={r.name} size={32} />
                <div>
                  <div style={{ fontSize: 13.5, fontWeight: 600 }}>{r.name}</div>
                  <StarRating value={r.rating} showNumber={false} />
                </div>
              </div>
              <span style={{ fontSize: 12, color: 'var(--fg-subtle)' }}>{r.time}</span>
            </div>
            <p style={{ fontSize: 14, color: 'var(--fg-muted)', marginTop: 10, marginBottom: 0, lineHeight: 1.5 }}>{r.text}</p>
          </div>
        ))}
      </div>
    </div>
  );
}

function UserListingsPage({ setPage, setAuctionId }) {
  const D = window.SLP_DATA;
  const seller = D.SELLERS[2];
  return (
    <div className="page container" style={{ padding: '28px 24px 80px' }}>
      <a onClick={() => setPage('user-profile')} style={{ fontSize: 13, color: 'var(--fg-muted)', cursor: 'pointer' }}>← Back to profile</a>
      <div className="card" style={{ padding: 18, marginTop: 12, marginBottom: 24, display: 'flex', gap: 14, alignItems: 'center' }}>
        <Avatar name={seller.name} size={48} />
        <div style={{ flex: 1 }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
            <span style={{ fontWeight: 600, fontSize: 16 }}>{seller.name}'s listings</span>
            <Badge tone="success" dot><Icons.Shield size={10} /> Verified</Badge>
          </div>
          <div style={{ fontSize: 12.5, color: 'var(--fg-subtle)', marginTop: 2 }}>Member since {seller.member} · <StarRating value={seller.rating} size={11} /></div>
        </div>
      </div>
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(280px, 1fr))', gap: 16 }}>
        {D.AUCTIONS.slice(0, 12).map(p => <AuctionCard key={p.id} parcel={p} layout="standard" onClick={() => { setAuctionId(p.id); setPage('auction'); }} />)}
      </div>
    </div>
  );
}

window.NotificationsPage = NotificationsPage;
window.SavedPage = SavedPage;
window.SettingsNotificationsPage = SettingsNotificationsPage;
window.UserProfilePage = UserProfilePage;
window.UserListingsPage = UserListingsPage;
