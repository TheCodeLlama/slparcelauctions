// page-admin.jsx — admin home, users, user detail, disputes, dispute detail

function StatCard({ label, value, hint, tone, icon, onClick }) {
  return (
    <div onClick={onClick} className="card" style={{
      padding: 18, cursor: onClick ? 'pointer' : 'default',
      transition: 'border-color .15s, box-shadow .15s',
    }}>
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 8 }}>
        <div style={{ fontSize: 11.5, color: 'var(--fg-subtle)', textTransform: 'uppercase', letterSpacing: '0.06em', fontWeight: 600 }}>{label}</div>
        {icon && <span style={{ color: tone === 'danger' ? 'var(--danger)' : tone === 'warning' ? 'var(--warning)' : 'var(--fg-muted)' }}>{icon}</span>}
      </div>
      <div style={{ fontSize: 26, fontWeight: 700, letterSpacing: '-0.02em', lineHeight: 1.1 }}>{value}</div>
      {hint && <div style={{ fontSize: 12, color: 'var(--fg-muted)', marginTop: 4 }}>{hint}</div>}
    </div>
  );
}

function AdminHomePage({ setPage }) {
  const A = window.ADMIN_DATA;
  const s = A.PLATFORM_STATS;
  return (
    <AdminShell page="admin-home" setPage={setPage} title="Dashboard" subtitle="Platform overview · lifetime">
      <div style={{ marginBottom: 14, fontSize: 13, fontWeight: 600, color: 'var(--fg-muted)', textTransform: 'uppercase', letterSpacing: '0.05em' }}>Needs attention</div>
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(4, 1fr)', gap: 14, marginBottom: 32 }}>
        <StatCard label="Open fraud flags" value={A.QUEUE_COUNTS.fraudFlags} hint="High signal score" tone="danger" icon={<Icons.Shield size={16} />} onClick={() => setPage('admin-fraud-flags')} />
        <StatCard label="Open reports" value={A.QUEUE_COUNTS.reports} hint="Awaiting triage" tone="warning" icon={<Icons.AlertTriangle size={16} />} onClick={() => setPage('admin-reports')} />
        <StatCard label="Pending payments" value={A.QUEUE_COUNTS.pendingPayments} hint="Stuck > 1 hour" icon={<Icons.Wallet size={16} />} />
        <StatCard label="Active disputes" value={A.QUEUE_COUNTS.disputes} hint="Need resolution" tone="warning" icon={<Icons.AlertCircle size={16} />} onClick={() => setPage('admin-disputes')} />
      </div>
      <div style={{ marginBottom: 14, fontSize: 13, fontWeight: 600, color: 'var(--fg-muted)', textTransform: 'uppercase', letterSpacing: '0.05em' }}>Lifetime stats</div>
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3, 1fr)', gap: 14, marginBottom: 14 }}>
        <StatCard label="Active listings" value={s.activeListings.toLocaleString()} hint="Currently live" />
        <StatCard label="Total users" value={s.totalUsers.toLocaleString()} hint={`+${(s.totalUsers * 0.018 | 0).toLocaleString()} this month`} />
        <StatCard label="Active escrows" value={s.activeEscrows.toLocaleString()} hint="In progress" />
      </div>
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3, 1fr)', gap: 14 }}>
        <StatCard label="Completed sales" value={s.completedSales.toLocaleString()} hint="All time" />
        <StatCard label="Gross volume" value={'L$ ' + s.grossVolume.toLocaleString()} hint="All time" />
        <StatCard label="Commission earned" value={'L$ ' + s.commissionEarned.toLocaleString()} hint="4.0% avg" />
      </div>
    </AdminShell>
  );
}

function StatusBadge({ status }) {
  const map = {
    active: ['success', 'Active'],
    suspended: ['warning', 'Suspended'],
    banned: ['danger', 'Banned'],
    DISPUTED: ['warning', 'Disputed'],
    FROZEN: ['danger', 'Frozen'],
    open: ['warning', 'Open'],
    reviewed: ['neutral', 'Reviewed'],
    resolved: ['success', 'Resolved'],
    lifted: ['neutral', 'Lifted'],
  };
  const [tone, label] = map[status] || ['neutral', status];
  return <Badge tone={tone} dot>{label}</Badge>;
}

function AdminUsersPage({ setPage, setAdminUserId }) {
  const A = window.ADMIN_DATA;
  const [q, setQ] = React.useState('');
  const [page2, setPage2] = React.useState(1);
  const filtered = q.trim() ? A.ADMIN_USERS.filter(u => (u.email + u.displayName + u.slName + u.id).toLowerCase().includes(q.toLowerCase())) : A.ADMIN_USERS;
  const perPage = 12;
  const slice = filtered.slice((page2 - 1) * perPage, page2 * perPage);
  const totalPages = Math.max(1, Math.ceil(filtered.length / perPage));
  return (
    <AdminShell page="admin-users" setPage={setPage} title="Users" subtitle={`${A.ADMIN_USERS.length.toLocaleString()} total accounts`}>
      <div className="search-input" style={{ maxWidth: 460, marginBottom: 18 }}>
        <Icons.Search size={14} />
        <input className="input" value={q} onChange={(e) => { setQ(e.target.value); setPage2(1); }} placeholder="Search by email, display name, SL avatar, or user ID" />
      </div>
      <div className="card" style={{ overflow: 'hidden' }}>
        <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 13.5 }}>
          <thead>
            <tr style={{ background: 'var(--bg-subtle)' }}>
              {['Email', 'Display name', 'SL avatar', 'Verified', 'Created', 'Status'].map(h => (
                <th key={h} style={{ textAlign: 'left', padding: '10px 14px', fontSize: 11, fontWeight: 600, color: 'var(--fg-muted)', textTransform: 'uppercase', letterSpacing: '0.05em', borderBottom: '1px solid var(--border)' }}>{h}</th>
              ))}
            </tr>
          </thead>
          <tbody>
            {slice.length === 0 && (
              <tr><td colSpan={6} style={{ padding: 40, textAlign: 'center', color: 'var(--fg-muted)' }}>No users match "{q}"</td></tr>
            )}
            {slice.map(u => (
              <tr key={u.id} onClick={() => { setAdminUserId(u.id); setPage('admin-user-detail'); }}
                style={{ cursor: 'pointer', borderTop: '1px solid var(--border-subtle)' }}
                onMouseEnter={(e) => e.currentTarget.style.background = 'var(--bg-subtle)'}
                onMouseLeave={(e) => e.currentTarget.style.background = 'transparent'}>
                <td style={{ padding: '12px 14px', fontFamily: 'var(--font-mono)', fontSize: 12.5 }}>{u.email}</td>
                <td style={{ padding: '12px 14px', fontWeight: 500 }}>{u.displayName}</td>
                <td style={{ padding: '12px 14px', fontFamily: 'var(--font-mono)', fontSize: 12.5, color: 'var(--fg-muted)' }}>{u.slName}</td>
                <td style={{ padding: '12px 14px' }}>{u.verified ? <Badge tone="success" dot><Icons.Shield size={10} /> Verified</Badge> : <Badge tone="neutral">Unverified</Badge>}</td>
                <td style={{ padding: '12px 14px', color: 'var(--fg-muted)' }}>{u.created}</td>
                <td style={{ padding: '12px 14px' }}><StatusBadge status={u.status} /></td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <Pagination page={page2} setPage={setPage2} total={totalPages} resultCount={filtered.length} perPage={perPage} />
    </AdminShell>
  );
}

function Pagination({ page, setPage, total, resultCount, perPage }) {
  return (
    <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginTop: 16, fontSize: 13, color: 'var(--fg-muted)' }}>
      <div>Showing {Math.min((page - 1) * perPage + 1, resultCount)}–{Math.min(page * perPage, resultCount)} of {resultCount}</div>
      <div style={{ display: 'flex', gap: 6 }}>
        <Btn variant="secondary" size="sm" onClick={() => setPage(Math.max(1, page - 1))} disabled={page === 1}><Icons.ChevronLeft size={13} /> Prev</Btn>
        <span style={{ padding: '6px 10px', fontSize: 12.5 }}>Page <strong style={{ color: 'var(--fg)' }}>{page}</strong> of {total}</span>
        <Btn variant="secondary" size="sm" onClick={() => setPage(Math.min(total, page + 1))} disabled={page === total}>Next <Icons.ChevronRight size={13} /></Btn>
      </div>
    </div>
  );
}

function AdminUserDetailPage({ setPage, adminUserId }) {
  const A = window.ADMIN_DATA;
  const D = window.SLP_DATA;
  const u = A.ADMIN_USERS.find(x => x.id === adminUserId) || A.ADMIN_USERS[0];
  const [tab, setTab] = React.useState('listings');
  const [modal, setModal] = React.useState(null);
  const tabs = [
    ['listings', 'Listings', 6],
    ['bids', 'Bids', 14],
    ['cancellations', 'Cancellations', 2],
    ['reports', 'Reports', 3],
    ['fraud', 'Fraud Flags', 1],
    ['moderation', 'Moderation', 5],
  ];

  const stats = [
    ['Total listings', '12'], ['Active listings', '4'], ['Completed sales', '8'],
    ['Total bids', '47'], ['Active bids', '3'], ['Won auctions', '11'],
    ['Cancellations', '2 (4.2%)'], ['Reports filed against', '3'],
    ['Fraud flags', '1'], ['Avg seller rating', '4.6'], ['Avg buyer rating', '4.9'],
  ];

  return (
    <AdminShell page="admin-user-detail" setPage={setPage} title={u.displayName} subtitle={`User ${u.id} · ${u.email}`}
      actions={
        <Btn variant="ghost" onClick={() => setPage('admin-users')}><Icons.ChevronLeft size={13} /> Back to users</Btn>
      }>
      {/* Profile header */}
      <div className="card" style={{ padding: 22, marginBottom: 20, display: 'grid', gridTemplateColumns: '1fr 280px', gap: 24, alignItems: 'flex-start' }}>
        <div style={{ display: 'flex', gap: 16 }}>
          <Avatar name={u.displayName} size={72} />
          <div style={{ flex: 1 }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: 10, flexWrap: 'wrap' }}>
              <h2 style={{ fontSize: 20, fontWeight: 700, margin: 0 }}>{u.displayName}</h2>
              <StatusBadge status={u.status} />
              {u.verified && <Badge tone="success" dot><Icons.Shield size={10} /> Verified</Badge>}
            </div>
            <div style={{ fontSize: 13, color: 'var(--fg-muted)', marginTop: 4 }}>
              <span className="mono">{u.email}</span> · SL: <span className="mono">{u.slName}</span>
            </div>
            <div style={{ fontSize: 13, color: 'var(--fg-subtle)', marginTop: 2 }}>Member since {u.created} · ID <span className="mono">{u.id}</span></div>
            <div style={{ display: 'flex', gap: 8, marginTop: 12, flexWrap: 'wrap' }}>
              <Btn variant="ghost" size="sm" onClick={() => setModal('ips')}>Recent IPs</Btn>
              <Btn variant="ghost" size="sm">View public profile</Btn>
              <Btn variant="ghost" size="sm">Send admin message</Btn>
            </div>
          </div>
        </div>
        {/* User-actions rail */}
        <div style={{ borderLeft: '1px solid var(--border)', paddingLeft: 22 }}>
          <div style={{ fontSize: 11, fontWeight: 700, letterSpacing: '0.06em', textTransform: 'uppercase', color: 'var(--fg-subtle)', marginBottom: 10 }}>Moderation actions</div>
          <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
            {u.status !== 'banned' && <Btn variant="secondary" size="sm" onClick={() => setModal('ban')} block>+ Add ban</Btn>}
            {u.status === 'banned' && <Btn variant="secondary" size="sm" onClick={() => setModal('lift')} block>Lift ban</Btn>}
            {u.status !== 'suspended' && <Btn variant="ghost" size="sm" onClick={() => setModal('suspend')} block>Suspend account</Btn>}
            {u.status === 'suspended' && <Btn variant="ghost" size="sm" onClick={() => setModal('unsuspend')} block>Lift suspension</Btn>}
            {!u.verified && <Btn variant="ghost" size="sm" onClick={() => setModal('verify')} block>Force-verify avatar</Btn>}
            <Btn variant="ghost" size="sm" onClick={() => setModal('reset')} block>Reset frivolous counter</Btn>
            <Btn variant="ghost" size="sm" onClick={() => setModal('penalty')} block>Override penalty</Btn>
            <div style={{ marginTop: 10, paddingTop: 10, borderTop: '1px solid var(--border-subtle)' }}>
              <Btn variant="ghost" size="sm" onClick={() => setModal('delete')} block style={{ color: 'var(--danger)' }}>Delete user</Btn>
            </div>
          </div>
        </div>
      </div>

      {/* Stats grid */}
      <div className="card" style={{ padding: 0, marginBottom: 20, overflow: 'hidden' }}>
        <div style={{ padding: '12px 18px', fontSize: 11, fontWeight: 600, letterSpacing: '0.06em', textTransform: 'uppercase', color: 'var(--fg-subtle)', borderBottom: '1px solid var(--border-subtle)', background: 'var(--bg-subtle)' }}>Activity stats</div>
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(4, 1fr)' }}>
          {stats.map(([l, v], i) => (
            <div key={l} style={{
              padding: '14px 18px',
              borderRight: (i + 1) % 4 === 0 ? 'none' : '1px solid var(--border-subtle)',
              borderTop: i >= 4 ? '1px solid var(--border-subtle)' : 'none',
            }}>
              <div style={{ fontSize: 11, color: 'var(--fg-subtle)', textTransform: 'uppercase', letterSpacing: '0.05em', fontWeight: 600 }}>{l}</div>
              <div style={{ fontSize: 18, fontWeight: 700, marginTop: 4 }}>{v}</div>
            </div>
          ))}
        </div>
      </div>

      {/* Tabs */}
      <div style={{ borderBottom: '1px solid var(--border)', display: 'flex', gap: 4, marginBottom: 16 }}>
        {tabs.map(([k, l, c]) => (
          <button key={k} onClick={() => setTab(k)} className="btn btn--ghost" style={{
            borderRadius: 0, padding: '10px 14px', fontSize: 13.5, fontWeight: 500,
            borderBottom: '2px solid ' + (tab === k ? 'var(--brand)' : 'transparent'),
            color: tab === k ? 'var(--fg)' : 'var(--fg-muted)', marginBottom: -1,
          }}>{l} <span style={{ marginLeft: 4, fontSize: 11, color: 'var(--fg-subtle)' }}>{c}</span></button>
        ))}
      </div>

      {/* Tab body — small simulated tables */}
      <div className="card" style={{ overflow: 'hidden' }}>
        {tab === 'listings' && (
          <table style={{ width: '100%', fontSize: 13.5, borderCollapse: 'collapse' }}>
            <thead><tr style={{ background: 'var(--bg-subtle)' }}>{['Parcel', 'Coords', 'Top bid', 'Status', 'Created'].map(h => <th key={h} style={{ textAlign: 'left', padding: '10px 14px', fontSize: 11, color: 'var(--fg-muted)', textTransform: 'uppercase', letterSpacing: '0.05em', fontWeight: 600 }}>{h}</th>)}</tr></thead>
            <tbody>{D.AUCTIONS.slice(0, 6).map(p => (
              <tr key={p.id} style={{ borderTop: '1px solid var(--border-subtle)', cursor: 'pointer' }}>
                <td style={{ padding: '12px 14px', fontWeight: 500 }}>{p.title}</td>
                <td style={{ padding: '12px 14px', fontFamily: 'var(--font-mono)', fontSize: 12, color: 'var(--fg-muted)' }}>{p.coords}</td>
                <td style={{ padding: '12px 14px' }}><L amount={p.currentBid} /></td>
                <td style={{ padding: '12px 14px' }}><Badge tone="success" dot>Active</Badge></td>
                <td style={{ padding: '12px 14px', color: 'var(--fg-muted)' }}>2 days ago</td>
              </tr>
            ))}</tbody>
          </table>
        )}
        {tab === 'bids' && (
          <table style={{ width: '100%', fontSize: 13.5, borderCollapse: 'collapse' }}>
            <thead><tr style={{ background: 'var(--bg-subtle)' }}>{['Parcel', 'Your bid', 'Status', 'Placed'].map(h => <th key={h} style={{ textAlign: 'left', padding: '10px 14px', fontSize: 11, color: 'var(--fg-muted)', textTransform: 'uppercase', letterSpacing: '0.05em', fontWeight: 600 }}>{h}</th>)}</tr></thead>
            <tbody>{D.AUCTIONS.slice(0, 8).map((p, i) => (
              <tr key={p.id} style={{ borderTop: '1px solid var(--border-subtle)', cursor: 'pointer' }}>
                <td style={{ padding: '12px 14px', fontWeight: 500 }}>{p.title}</td>
                <td style={{ padding: '12px 14px' }}><L amount={p.currentBid - 200} /></td>
                <td style={{ padding: '12px 14px' }}><Badge tone={i < 3 ? 'success' : i < 5 ? 'danger' : 'neutral'} dot>{i < 3 ? 'Won' : i < 5 ? 'Lost' : 'Active'}</Badge></td>
                <td style={{ padding: '12px 14px', color: 'var(--fg-muted)' }}>{['1h','4h','1d','2d','3d','5d','1w','2w'][i]} ago</td>
              </tr>
            ))}</tbody>
          </table>
        )}
        {tab === 'cancellations' && (
          <table style={{ width: '100%', fontSize: 13.5, borderCollapse: 'collapse' }}>
            <thead><tr style={{ background: 'var(--bg-subtle)' }}>{['Parcel', 'Reason', 'Penalty', 'Date', ''].map(h => <th key={h} style={{ textAlign: 'left', padding: '10px 14px', fontSize: 11, color: 'var(--fg-muted)', textTransform: 'uppercase', letterSpacing: '0.05em', fontWeight: 600 }}>{h}</th>)}</tr></thead>
            <tbody>
              <tr style={{ borderTop: '1px solid var(--border-subtle)' }}>
                <td style={{ padding: '12px 14px' }}>Hilltop with lighthouse rights</td>
                <td style={{ padding: '12px 14px', color: 'var(--fg-muted)' }}>Buyer changed mind</td>
                <td style={{ padding: '12px 14px' }}><L amount={500} /></td>
                <td style={{ padding: '12px 14px', color: 'var(--fg-muted)' }}>3 wks ago</td>
                <td style={{ padding: '12px 14px' }}><Btn variant="ghost" size="sm" onClick={() => setModal('reinstate-1')}>Reinstate</Btn></td>
              </tr>
              <tr style={{ borderTop: '1px solid var(--border-subtle)' }}>
                <td style={{ padding: '12px 14px' }}>Lakeside forest plot</td>
                <td style={{ padding: '12px 14px', color: 'var(--fg-muted)' }}>Listing error</td>
                <td style={{ padding: '12px 14px' }}>Waived</td>
                <td style={{ padding: '12px 14px', color: 'var(--fg-muted)' }}>2 mo ago</td>
                <td style={{ padding: '12px 14px' }}><Btn variant="ghost" size="sm" onClick={() => setModal('reinstate-2')}>Reinstate</Btn></td>
              </tr>
            </tbody>
          </table>
        )}
        {tab === 'reports' && (
          <div style={{ padding: 40, textAlign: 'center', color: 'var(--fg-muted)' }}>3 reports filed against this user. <a style={{ color: 'var(--brand)', cursor: 'pointer' }}>View all →</a></div>
        )}
        {tab === 'fraud' && (
          <div style={{ padding: 40, textAlign: 'center', color: 'var(--fg-muted)' }}>1 fraud flag · resolved · false-positive determination by admin.kai</div>
        )}
        {tab === 'moderation' && (
          <table style={{ width: '100%', fontSize: 13.5, borderCollapse: 'collapse' }}>
            <thead><tr style={{ background: 'var(--bg-subtle)' }}>{['Action', 'Admin', 'Note', 'Time'].map(h => <th key={h} style={{ textAlign: 'left', padding: '10px 14px', fontSize: 11, color: 'var(--fg-muted)', textTransform: 'uppercase', letterSpacing: '0.05em', fontWeight: 600 }}>{h}</th>)}</tr></thead>
            <tbody>{[
              ['Force-verified avatar', 'admin.eve', 'Manual SL match confirmed', '2 wks ago'],
              ['Penalty waived', 'admin.kai', 'Listing error in good faith', '2 mo ago'],
              ['Warning issued', 'admin.eve', 'Slow handover — buyer complaint', '3 mo ago'],
              ['Account verified', 'system', 'Email + SL avatar', '8 mo ago'],
              ['Account created', 'system', '', '8 mo ago'],
            ].map((r, i) => (
              <tr key={i} style={{ borderTop: '1px solid var(--border-subtle)' }}>
                <td style={{ padding: '12px 14px', fontWeight: 500 }}>{r[0]}</td>
                <td style={{ padding: '12px 14px', fontFamily: 'var(--font-mono)', fontSize: 12.5 }}>{r[1]}</td>
                <td style={{ padding: '12px 14px', color: 'var(--fg-muted)' }}>{r[2]}</td>
                <td style={{ padding: '12px 14px', color: 'var(--fg-subtle)' }}>{r[3]}</td>
              </tr>
            ))}</tbody>
          </table>
        )}
      </div>

      {/* Modals */}
      <Modal open={modal === 'ban'} onClose={() => setModal(null)} title="Add ban"
        footer={<><Btn variant="ghost" onClick={() => setModal(null)}>Cancel</Btn><Btn variant="primary" onClick={() => setModal(null)}>Create ban</Btn></>}>
        <div style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
          <div><label className="field-label">Ban type</label>
            <select className="select"><option>Avatar</option><option>IP</option><option>Both (avatar + IP)</option></select>
          </div>
          <div><label className="field-label">Reason</label>
            <textarea className="textarea" rows={3} placeholder="Visible only to admins. Be specific." />
          </div>
          <div><label className="field-label">Duration</label>
            <div style={{ display: 'flex', gap: 8 }}>
              <select className="select" style={{ flex: 1 }}><option>Permanent</option><option>Custom expiration</option></select>
              <input className="input" type="date" style={{ width: 160 }} />
            </div>
          </div>
        </div>
      </Modal>

      <Modal open={modal === 'lift'} onClose={() => setModal(null)} title="Lift ban"
        footer={<><Btn variant="ghost" onClick={() => setModal(null)}>Cancel</Btn><Btn variant="primary" onClick={() => setModal(null)}>Lift ban</Btn></>}>
        <div style={{ marginBottom: 14, padding: 12, background: 'var(--bg-subtle)', borderRadius: 'var(--r-md)', fontSize: 13 }}>
          <div><strong>Active ban:</strong> Avatar — Fraud, confirmed shill bidding</div>
          <div style={{ color: 'var(--fg-muted)', marginTop: 4 }}>Created 2 wks ago by admin.eve · expires permanent</div>
        </div>
        <label className="field-label">Reason for lifting (optional)</label>
        <textarea className="textarea" rows={3} placeholder="Note for the audit log…" />
      </Modal>

      <Modal open={modal === 'delete'} onClose={() => setModal(null)} title="Delete user permanently" width={440}
        footer={<><Btn variant="ghost" onClick={() => setModal(null)}>Cancel</Btn><Btn variant="primary" onClick={() => setModal(null)} style={{ background: 'var(--danger)', borderColor: 'var(--danger)' }}>Delete forever</Btn></>}>
        <div style={{ display: 'flex', gap: 12 }}>
          <Icons.AlertTriangle size={20} style={{ color: 'var(--danger)', flexShrink: 0 }} />
          <div style={{ fontSize: 14, lineHeight: 1.5 }}>
            This permanently deletes <strong>{u.displayName}</strong>. Past auctions, bids, and reviews will remain attributed to "Deleted user". This cannot be undone.
          </div>
        </div>
        <div style={{ marginTop: 16 }}>
          <label className="field-label">Type the user's email to confirm</label>
          <input className="input" placeholder={u.email} />
        </div>
      </Modal>

      <Modal open={['suspend','unsuspend','verify','reset','penalty'].includes(modal)} onClose={() => setModal(null)}
        title={{suspend:'Suspend account',unsuspend:'Lift suspension',verify:'Force-verify avatar',reset:'Reset frivolous counter',penalty:'Override penalty'}[modal]}
        footer={<><Btn variant="ghost" onClick={() => setModal(null)}>Cancel</Btn><Btn variant="primary" onClick={() => setModal(null)}>Confirm</Btn></>}>
        <p style={{ fontSize: 14, color: 'var(--fg-muted)', lineHeight: 1.5 }}>
          {{
            suspend: 'Suspend this account. The user will be unable to bid, list, or withdraw funds. They can still sign in to view their account.',
            unsuspend: 'Lift the suspension on this account. The user can bid, list, and withdraw normally again.',
            verify: 'Manually mark this avatar as verified. Used when the in-world verification flow has issues but the admin has confirmed identity out-of-band.',
            reset: 'Reset this user\'s frivolous-dispute counter to zero. Use sparingly — counters auto-decay after 12 months.',
            penalty: 'Override or waive the most recent cancellation penalty for this user.',
          }[modal]}
        </p>
        <label className="field-label" style={{ marginTop: 14 }}>Note for audit log</label>
        <textarea className="textarea" rows={3} />
      </Modal>

      <ReinstateListingModal open={!!modal && String(modal).startsWith('reinstate-')} onClose={() => setModal(null)}
        listing={modal === 'reinstate-1' ? { title: 'Hilltop with lighthouse rights', coords: 'Cypress (124, 88, 47)' } : { title: 'Lakeside forest plot', coords: 'Mossbridge (88, 200, 36)' }} />

      <Modal open={modal === 'ips'} onClose={() => setModal(null)} title="Recent IPs" width={520}>
        <table style={{ width: '100%', fontSize: 13, borderCollapse: 'collapse' }}>
          <thead><tr><th style={{ textAlign: 'left', padding: '8px 0', fontSize: 11, color: 'var(--fg-muted)', textTransform: 'uppercase', letterSpacing: '0.05em' }}>IP</th><th style={{ textAlign: 'left', fontSize: 11, color: 'var(--fg-muted)', textTransform: 'uppercase', letterSpacing: '0.05em' }}>Region</th><th style={{ textAlign: 'right', fontSize: 11, color: 'var(--fg-muted)', textTransform: 'uppercase', letterSpacing: '0.05em' }}>Last seen</th></tr></thead>
          <tbody>{[
            ['198.51.100.42', 'Berlin, DE', '12 min ago'],
            ['203.0.113.18',  'Berlin, DE', '6 hours ago'],
            ['198.51.100.42', 'Berlin, DE', '1 day ago'],
            ['203.0.113.99',  'Vienna, AT', '4 days ago'],
            ['198.51.100.5',  'Munich, DE', '2 wks ago'],
          ].map((r, i) => (
            <tr key={i} style={{ borderTop: '1px solid var(--border-subtle)' }}>
              <td style={{ padding: '10px 0', fontFamily: 'var(--font-mono)' }}>{r[0]}</td>
              <td style={{ color: 'var(--fg-muted)' }}>{r[1]}</td>
              <td style={{ textAlign: 'right', color: 'var(--fg-subtle)' }}>{r[2]}</td>
            </tr>
          ))}</tbody>
        </table>
      </Modal>
    </AdminShell>
  );
}

function AdminDisputesPage({ setPage, setAdminDisputeId }) {
  const A = window.ADMIN_DATA;
  const [status, setStatus] = React.useState('all');
  const [reason, setReason] = React.useState('all');
  const [q, setQ] = React.useState('');
  let rows = A.DISPUTES;
  if (status !== 'all') rows = rows.filter(d => d.status === status);
  if (reason !== 'all') rows = rows.filter(d => d.reason === reason);
  if (q.trim()) rows = rows.filter(d => (d.parcelTitle + d.seller.email + d.winner.email).toLowerCase().includes(q.toLowerCase()));
  const active = A.DISPUTES.filter(d => d.status === 'DISPUTED').length;

  return (
    <AdminShell page="admin-disputes" setPage={setPage} title="Disputes"
      subtitle={`${A.DISPUTES.length} total · ${active} active · ${A.DISPUTES.length - active} frozen`}>
      <div style={{ display: 'flex', gap: 10, marginBottom: 16, flexWrap: 'wrap' }}>
        <select className="select" value={status} onChange={(e) => setStatus(e.target.value)} style={{ width: 160 }}>
          <option value="all">All statuses</option><option value="DISPUTED">Disputed</option><option value="FROZEN">Frozen</option>
        </select>
        <select className="select" value={reason} onChange={(e) => setReason(e.target.value)} style={{ width: 200 }}>
          <option value="all">All reasons</option><option value="not-responding">Not responding</option>
          <option value="wrong-parcel">Wrong parcel</option><option value="paid-not-funded">Paid, not funded</option>
          <option value="fraud">Suspected fraud</option><option value="other">Other</option>
        </select>
        <select className="select" style={{ width: 160 }}><option>Last 30 days</option><option>Last 90 days</option><option>All time</option></select>
        <div className="search-input" style={{ flex: 1, minWidth: 240 }}>
          <Icons.Search size={14} />
          <input className="input" value={q} onChange={(e) => setQ(e.target.value)} placeholder="Search parcel or user…" />
        </div>
      </div>
      <div className="card" style={{ overflow: 'hidden' }}>
        <table style={{ width: '100%', fontSize: 13.5, borderCollapse: 'collapse' }}>
          <thead>
            <tr style={{ background: 'var(--bg-subtle)' }}>
              {['Parcel', 'Seller', 'Winner', 'Amount', 'Status', 'Filed', 'Updated', 'Reason'].map(h => (
                <th key={h} style={{ textAlign: 'left', padding: '10px 14px', fontSize: 11, fontWeight: 600, color: 'var(--fg-muted)', textTransform: 'uppercase', letterSpacing: '0.05em', borderBottom: '1px solid var(--border)' }}>{h}</th>
              ))}
            </tr>
          </thead>
          <tbody>
            {rows.length === 0 && <tr><td colSpan={8} style={{ padding: 40, textAlign: 'center', color: 'var(--fg-muted)' }}>No disputes match the current filters.</td></tr>}
            {rows.map(d => (
              <tr key={d.id} onClick={() => { setAdminDisputeId(d.id); setPage('admin-dispute-detail'); }}
                style={{ borderTop: '1px solid var(--border-subtle)', cursor: 'pointer' }}
                onMouseEnter={(e) => e.currentTarget.style.background = 'var(--bg-subtle)'}
                onMouseLeave={(e) => e.currentTarget.style.background = 'transparent'}>
                <td style={{ padding: '12px 14px' }}>
                  <div style={{ fontWeight: 500 }}>{d.parcelTitle}</div>
                  <div style={{ fontSize: 11.5, fontFamily: 'var(--font-mono)', color: 'var(--fg-subtle)' }}>{d.parcelCoords}</div>
                </td>
                <td style={{ padding: '12px 14px', fontFamily: 'var(--font-mono)', fontSize: 12.5 }}>{d.seller.email}</td>
                <td style={{ padding: '12px 14px', fontFamily: 'var(--font-mono)', fontSize: 12.5 }}>{d.winner.email}</td>
                <td style={{ padding: '12px 14px' }}><L amount={d.amount} /></td>
                <td style={{ padding: '12px 14px' }}><StatusBadge status={d.status} /></td>
                <td style={{ padding: '12px 14px', color: 'var(--fg-muted)' }}>{d.filed}</td>
                <td style={{ padding: '12px 14px', color: 'var(--fg-muted)' }}>{d.updated}</td>
                <td style={{ padding: '12px 14px', color: 'var(--fg-muted)' }}>{d.reason.replace(/-/g, ' ')}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </AdminShell>
  );
}

function AdminDisputeDetailPage({ setPage, adminDisputeId }) {
  const A = window.ADMIN_DATA;
  const d = A.DISPUTES.find(x => x.id === adminDisputeId) || A.DISPUTES[0];
  const [decision, setDecision] = React.useState('');
  const [note, setNote] = React.useState('');

  const ledger = [
    { time: 'May 2 · 14:22', type: 'Payment', amount: d.amount, note: `Winner ${d.winner.email} funded escrow` },
    { time: 'May 2 · 14:23', type: 'Escrow move', amount: d.amount, note: 'Funds reserved → ESCROW (state: FUNDED)' },
    { time: 'May 4 · 09:11', type: 'Dispute', amount: 0, note: `Filed by winner · reason: ${d.reason.replace(/-/g, ' ')}` },
    { time: 'May 4 · 09:11', type: 'State change', amount: 0, note: 'FUNDED → DISPUTED · seller notified' },
  ];

  return (
    <AdminShell page="admin-dispute-detail" setPage={setPage} title={d.parcelTitle}
      subtitle={`Dispute ${d.id} · auction ${d.parcelCoords}`}
      actions={<Btn variant="ghost" onClick={() => setPage('admin-disputes')}><Icons.ChevronLeft size={13} /> Back to disputes</Btn>}>
      {/* Header card */}
      <div className="card" style={{ padding: 18, marginBottom: 18, display: 'grid', gridTemplateColumns: 'repeat(5, 1fr)', gap: 18 }}>
        <div><div style={{ fontSize: 10.5, fontWeight: 600, color: 'var(--fg-subtle)', textTransform: 'uppercase', letterSpacing: '0.05em' }}>Status</div><div style={{ marginTop: 6 }}><StatusBadge status={d.status} /></div></div>
        <div><div style={{ fontSize: 10.5, fontWeight: 600, color: 'var(--fg-subtle)', textTransform: 'uppercase', letterSpacing: '0.05em' }}>Sale amount</div><div style={{ fontSize: 16, fontWeight: 700, marginTop: 4 }}><L amount={d.amount} /></div></div>
        <div><div style={{ fontSize: 10.5, fontWeight: 600, color: 'var(--fg-subtle)', textTransform: 'uppercase', letterSpacing: '0.05em' }}>Seller</div><div style={{ fontSize: 12.5, marginTop: 4, fontFamily: 'var(--font-mono)' }}>{d.seller.email}</div></div>
        <div><div style={{ fontSize: 10.5, fontWeight: 600, color: 'var(--fg-subtle)', textTransform: 'uppercase', letterSpacing: '0.05em' }}>Winner</div><div style={{ fontSize: 12.5, marginTop: 4, fontFamily: 'var(--font-mono)' }}>{d.winner.email}</div></div>
        <div><div style={{ fontSize: 10.5, fontWeight: 600, color: 'var(--fg-subtle)', textTransform: 'uppercase', letterSpacing: '0.05em' }}>Filed / Updated</div><div style={{ fontSize: 12.5, marginTop: 4, color: 'var(--fg-muted)' }}>{d.filed} · {d.updated}</div></div>
      </div>

      <div style={{ display: 'grid', gridTemplateColumns: '1fr 360px', gap: 20, alignItems: 'flex-start' }}>
        <div style={{ display: 'flex', flexDirection: 'column', gap: 18 }}>
          {/* Ledger panel */}
          <div className="card">
            <div style={{ padding: '12px 18px', fontSize: 12, fontWeight: 600, color: 'var(--fg-muted)', textTransform: 'uppercase', letterSpacing: '0.06em', borderBottom: '1px solid var(--border-subtle)', background: 'var(--bg-subtle)' }}>Escrow ledger</div>
            <table style={{ width: '100%', fontSize: 13, borderCollapse: 'collapse' }}>
              <tbody>{ledger.map((l, i) => (
                <tr key={i} style={{ borderTop: i === 0 ? 'none' : '1px solid var(--border-subtle)' }}>
                  <td style={{ padding: '10px 18px', color: 'var(--fg-subtle)', fontSize: 12, whiteSpace: 'nowrap' }}>{l.time}</td>
                  <td style={{ padding: '10px 14px' }}><Badge tone={l.type === 'Dispute' || l.type === 'State change' ? 'warning' : 'neutral'}>{l.type}</Badge></td>
                  <td style={{ padding: '10px 14px' }}>{l.amount > 0 ? <L amount={l.amount} /> : <span style={{ color: 'var(--fg-subtle)' }}>—</span>}</td>
                  <td style={{ padding: '10px 18px', color: 'var(--fg-muted)' }}>{l.note}</td>
                </tr>
              ))}</tbody>
            </table>
          </div>

          {/* Evidence side-by-side */}
          <div className="card">
            <div style={{ padding: '12px 18px', fontSize: 12, fontWeight: 600, color: 'var(--fg-muted)', textTransform: 'uppercase', letterSpacing: '0.06em', borderBottom: '1px solid var(--border-subtle)', background: 'var(--bg-subtle)' }}>Evidence — side by side</div>
            <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr' }}>
              {[
                { title: 'Filer · Winner', user: d.winner, body: 'I sent the L$ on May 2 at 14:22 SLT and the seller has not responded to 4 IMs over 36 hours. The parcel still shows them as owner. I want a refund.', files: ['payment-receipt.png', 'im-log.txt'] },
                { title: 'Counterparty · Seller', user: d.seller, body: 'I was traveling. I returned today and have transferred the parcel to the buyer at 12:08 SLT. Please release escrow.', files: ['parcel-transfer.png'] },
              ].map((p, i) => (
                <div key={i} style={{ padding: 16, borderRight: i === 0 ? '1px solid var(--border-subtle)' : 'none' }}>
                  <div style={{ display: 'flex', gap: 10, alignItems: 'center', marginBottom: 8 }}>
                    <Avatar name={p.user.displayName} size={28} />
                    <div>
                      <div style={{ fontSize: 11, fontWeight: 600, color: 'var(--fg-subtle)', textTransform: 'uppercase', letterSpacing: '0.05em' }}>{p.title}</div>
                      <div style={{ fontSize: 12.5, fontFamily: 'var(--font-mono)' }}>{p.user.email}</div>
                    </div>
                  </div>
                  <p style={{ fontSize: 13.5, lineHeight: 1.55, color: 'var(--fg-muted)', margin: '8px 0' }}>{p.body}</p>
                  <div style={{ display: 'flex', flexDirection: 'column', gap: 4, marginTop: 10 }}>
                    {p.files.map(f => (
                      <div key={f} style={{ display: 'flex', alignItems: 'center', gap: 8, padding: 8, background: 'var(--bg-subtle)', borderRadius: 'var(--r-sm)', fontSize: 12 }}>
                        <Icons.Image size={13} /><span style={{ fontFamily: 'var(--font-mono)' }}>{f}</span>
                      </div>
                    ))}
                  </div>
                </div>
              ))}
            </div>
          </div>
        </div>

        {/* Resolution panel */}
        <div className="card" style={{ padding: 18, position: 'sticky', top: 'calc(var(--header-h) + 16px)' }}>
          <div style={{ fontSize: 11, fontWeight: 700, letterSpacing: '0.06em', textTransform: 'uppercase', color: 'var(--fg-subtle)', marginBottom: 12 }}>Resolution</div>
          <div style={{ display: 'flex', flexDirection: 'column', gap: 8, marginBottom: 14 }}>
            {[
              ['refund-winner', 'Approve & refund winner', 'var(--success)'],
              ['release-seller', 'Reject & release to seller', 'var(--brand)'],
              ['freeze', 'Freeze for further review', 'var(--warning)'],
              ['contact', 'Contact both parties first', 'var(--fg-muted)'],
            ].map(([k, l, c]) => (
              <label key={k} style={{
                display: 'flex', gap: 10, padding: 10, borderRadius: 'var(--r-sm)', cursor: 'pointer',
                border: '1px solid ' + (decision === k ? c : 'var(--border)'),
                background: decision === k ? 'var(--bg-subtle)' : 'transparent',
              }}>
                <input type="radio" checked={decision === k} onChange={() => setDecision(k)} />
                <span style={{ fontSize: 13, fontWeight: 500 }}>{l}</span>
              </label>
            ))}
          </div>
          <label className="field-label">Admin note (visible in audit log)</label>
          <textarea className="textarea" rows={4} value={note} onChange={(e) => setNote(e.target.value)} />
          <Btn variant="primary" block disabled={!decision || !note} style={{ marginTop: 12 }}>Submit resolution</Btn>
          <div style={{ marginTop: 14, paddingTop: 14, borderTop: '1px solid var(--border-subtle)' }}>
            <div style={{ fontSize: 11, fontWeight: 600, color: 'var(--fg-subtle)', textTransform: 'uppercase', letterSpacing: '0.05em', marginBottom: 6 }}>Prior actions</div>
            <div style={{ fontSize: 12.5, color: 'var(--fg-muted)' }}>No prior actions on this dispute.</div>
          </div>
        </div>
      </div>
    </AdminShell>
  );
}

window.AdminHomePage = AdminHomePage;
window.AdminUsersPage = AdminUsersPage;
window.AdminUserDetailPage = AdminUserDetailPage;
window.AdminDisputesPage = AdminDisputesPage;
window.AdminDisputeDetailPage = AdminDisputeDetailPage;
window.StatusBadge = StatusBadge;
window.Pagination = Pagination;
window.StatCard = StatCard;
