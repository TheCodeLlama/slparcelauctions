// page-admin-2.jsx — reports, fraud flags, audit log, bans, infrastructure

function AdminReportsPage({ setPage }) {
  const A = window.ADMIN_DATA;
  const [tab, setTab] = React.useState('open');
  const [openId, setOpenId] = React.useState(null);
  const filtered = tab === 'all' ? A.REPORTS : A.REPORTS.filter(r => r.status === tab);
  const open = openId ? A.REPORTS.find(r => r.id === openId) : null;
  const idx = open ? filtered.findIndex(r => r.id === open.id) : -1;
  const goPrev = () => idx > 0 && setOpenId(filtered[idx - 1].id);
  const goNext = () => idx < filtered.length - 1 && setOpenId(filtered[idx + 1].id);

  return (
    <AdminShell page="admin-reports" setPage={setPage} title="Reports"
      subtitle={`${A.REPORTS.length} total · ${A.REPORTS.filter(r => r.status === 'open').length} open`}>
      <div style={{ display: 'flex', gap: 4, marginBottom: 16, borderBottom: '1px solid var(--border)' }}>
        {[['open','Open',A.REPORTS.filter(r=>r.status==='open').length],['reviewed','Reviewed',A.REPORTS.filter(r=>r.status==='reviewed').length],['all','All',A.REPORTS.length]].map(([k,l,c]) => (
          <button key={k} onClick={() => setTab(k)} className="btn btn--ghost" style={{
            borderRadius: 0, padding: '10px 14px', fontSize: 13.5, fontWeight: 500,
            borderBottom: '2px solid ' + (tab === k ? 'var(--brand)' : 'transparent'),
            color: tab === k ? 'var(--fg)' : 'var(--fg-muted)', marginBottom: -1,
          }}>{l} <span style={{ marginLeft: 4, fontSize: 11, color: 'var(--fg-subtle)' }}>{c}</span></button>
        ))}
      </div>
      <div className="card" style={{ overflow: 'hidden' }}>
        <table style={{ width: '100%', fontSize: 13.5, borderCollapse: 'collapse' }}>
          <thead><tr style={{ background: 'var(--bg-subtle)' }}>
            {['Parcel', 'Reported user', 'Reporter', 'Category', 'Filed', 'Status'].map(h => (
              <th key={h} style={{ textAlign: 'left', padding: '10px 14px', fontSize: 11, fontWeight: 600, color: 'var(--fg-muted)', textTransform: 'uppercase', letterSpacing: '0.05em', borderBottom: '1px solid var(--border)' }}>{h}</th>
            ))}
          </tr></thead>
          <tbody>{filtered.map(r => (
            <tr key={r.id} onClick={() => setOpenId(r.id)} style={{ borderTop: '1px solid var(--border-subtle)', cursor: 'pointer' }}
              onMouseEnter={(e) => e.currentTarget.style.background = 'var(--bg-subtle)'}
              onMouseLeave={(e) => e.currentTarget.style.background = 'transparent'}>
              <td style={{ padding: '12px 14px', fontWeight: 500 }}>{r.parcelTitle}</td>
              <td style={{ padding: '12px 14px', fontFamily: 'var(--font-mono)', fontSize: 12.5 }}>{r.reportedUser.email}</td>
              <td style={{ padding: '12px 14px', fontFamily: 'var(--font-mono)', fontSize: 12.5, color: 'var(--fg-muted)' }}>{r.reporter.email}</td>
              <td style={{ padding: '12px 14px' }}><Badge tone="neutral">{r.category.replace(/-/g, ' ')}</Badge></td>
              <td style={{ padding: '12px 14px', color: 'var(--fg-muted)' }}>{r.filed}</td>
              <td style={{ padding: '12px 14px' }}><StatusBadge status={r.status} /></td>
            </tr>
          ))}</tbody>
        </table>
      </div>

      <SlideOver open={!!open} onClose={() => setOpenId(null)} title={`Report ${open?.id}`} width={560}
        onPrev={idx > 0 ? goPrev : null} onNext={idx < filtered.length - 1 ? goNext : null}
        footer={<div style={{ display: 'flex', gap: 8, justifyContent: 'flex-end', flexWrap: 'wrap' }}>
          <Btn variant="ghost" size="sm">Warn seller</Btn>
          <Btn variant="ghost" size="sm">Suspend listing</Btn>
          <Btn variant="ghost" size="sm" style={{ color: 'var(--danger)' }}>Cancel listing</Btn>
          <Btn variant="primary" size="sm">{open?.status === 'open' ? 'Mark reviewed' : 'Reopen'}</Btn>
        </div>}>
        {open && (
          <div>
            <div style={{ fontSize: 11, fontWeight: 600, color: 'var(--fg-subtle)', textTransform: 'uppercase', letterSpacing: '0.05em' }}>Listing</div>
            <div style={{ fontSize: 16, fontWeight: 600, marginTop: 4 }}>{open.parcelTitle}</div>
            <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 14, marginTop: 16, padding: 12, background: 'var(--bg-subtle)', borderRadius: 'var(--r-md)' }}>
              <div><div style={{ fontSize: 11, fontWeight: 600, color: 'var(--fg-subtle)', textTransform: 'uppercase', letterSpacing: '0.05em' }}>Reported user</div><div style={{ display: 'flex', gap: 8, alignItems: 'center', marginTop: 6 }}><Avatar name={open.reportedUser.displayName} size={28} /><div><div style={{ fontSize: 13, fontWeight: 500 }}>{open.reportedUser.displayName}</div><div style={{ fontFamily: 'var(--font-mono)', fontSize: 11.5, color: 'var(--fg-muted)' }}>{open.reportedUser.email}</div></div></div></div>
              <div><div style={{ fontSize: 11, fontWeight: 600, color: 'var(--fg-subtle)', textTransform: 'uppercase', letterSpacing: '0.05em' }}>Reporter</div><div style={{ display: 'flex', gap: 8, alignItems: 'center', marginTop: 6 }}><Avatar name={open.reporter.displayName} size={28} /><div><div style={{ fontSize: 13, fontWeight: 500 }}>{open.reporter.displayName}</div><div style={{ fontFamily: 'var(--font-mono)', fontSize: 11.5, color: 'var(--fg-muted)' }}>{open.reporter.email}</div></div></div></div>
            </div>
            <div style={{ marginTop: 16 }}>
              <div style={{ fontSize: 11, fontWeight: 600, color: 'var(--fg-subtle)', textTransform: 'uppercase', letterSpacing: '0.05em' }}>Category</div>
              <Badge tone="warning" style={{ marginTop: 6 }}>{open.category.replace(/-/g, ' ')}</Badge>
            </div>
            <div style={{ marginTop: 14 }}>
              <div style={{ fontSize: 11, fontWeight: 600, color: 'var(--fg-subtle)', textTransform: 'uppercase', letterSpacing: '0.05em' }}>Description</div>
              <p style={{ fontSize: 13.5, color: 'var(--fg-muted)', lineHeight: 1.55, marginTop: 6 }}>{open.description}</p>
            </div>
            <div style={{ marginTop: 14 }}>
              <div style={{ fontSize: 11, fontWeight: 600, color: 'var(--fg-subtle)', textTransform: 'uppercase', letterSpacing: '0.05em' }}>Evidence</div>
              <div style={{ display: 'flex', gap: 8, marginTop: 8 }}>
                {['screenshot1.png', 'chat-log.txt'].map(f => <div key={f} style={{ flex: 1, padding: 10, background: 'var(--bg-subtle)', borderRadius: 'var(--r-sm)', fontSize: 12, display: 'flex', gap: 6, alignItems: 'center' }}><Icons.Image size={13} /> {f}</div>)}
              </div>
            </div>
            <div style={{ marginTop: 18, padding: 12, background: 'var(--bg-subtle)', borderRadius: 'var(--r-md)' }}>
              <div style={{ fontSize: 11, fontWeight: 600, color: 'var(--fg-subtle)', textTransform: 'uppercase', letterSpacing: '0.05em' }}>Other reports against this listing</div>
              <div style={{ fontSize: 13, marginTop: 6 }}>2 prior reports · 1 dismissed · 1 reviewed</div>
            </div>
          </div>
        )}
      </SlideOver>
    </AdminShell>
  );
}

function AdminFraudFlagsPage({ setPage }) {
  const A = window.ADMIN_DATA;
  const [tab, setTab] = React.useState('open');
  const [openId, setOpenId] = React.useState(null);
  const filtered = tab === 'all' ? A.FLAGS : A.FLAGS.filter(f => f.status === tab);
  const open = openId ? A.FLAGS.find(f => f.id === openId) : null;
  const idx = open ? filtered.findIndex(f => f.id === open.id) : -1;

  return (
    <AdminShell page="admin-fraud-flags" setPage={setPage} title="Fraud flags"
      subtitle={`${A.FLAGS.length} total · ${A.FLAGS.filter(f => f.status === 'open').length} open`}>
      <div style={{ display: 'flex', gap: 4, marginBottom: 16, borderBottom: '1px solid var(--border)' }}>
        {[['open','Open',A.FLAGS.filter(f=>f.status==='open').length],['resolved','Resolved',A.FLAGS.filter(f=>f.status==='resolved').length],['all','All',A.FLAGS.length]].map(([k,l,c]) => (
          <button key={k} onClick={() => setTab(k)} className="btn btn--ghost" style={{
            borderRadius: 0, padding: '10px 14px', fontSize: 13.5, fontWeight: 500,
            borderBottom: '2px solid ' + (tab === k ? 'var(--brand)' : 'transparent'),
            color: tab === k ? 'var(--fg)' : 'var(--fg-muted)', marginBottom: -1,
          }}>{l} <span style={{ marginLeft: 4, fontSize: 11, color: 'var(--fg-subtle)' }}>{c}</span></button>
        ))}
      </div>
      <div className="card" style={{ overflow: 'hidden' }}>
        <table style={{ width: '100%', fontSize: 13.5, borderCollapse: 'collapse' }}>
          <thead><tr style={{ background: 'var(--bg-subtle)' }}>
            {['Flag', 'User', 'Reason', 'Score', 'Created', 'Status'].map(h => (
              <th key={h} style={{ textAlign: 'left', padding: '10px 14px', fontSize: 11, fontWeight: 600, color: 'var(--fg-muted)', textTransform: 'uppercase', letterSpacing: '0.05em', borderBottom: '1px solid var(--border)' }}>{h}</th>
            ))}
          </tr></thead>
          <tbody>{filtered.map(f => (
            <tr key={f.id} onClick={() => setOpenId(f.id)} style={{ borderTop: '1px solid var(--border-subtle)', cursor: 'pointer' }}
              onMouseEnter={(e) => e.currentTarget.style.background = 'var(--bg-subtle)'}
              onMouseLeave={(e) => e.currentTarget.style.background = 'transparent'}>
              <td style={{ padding: '12px 14px', fontFamily: 'var(--font-mono)', fontSize: 12.5 }}>{f.id}</td>
              <td style={{ padding: '12px 14px' }}>
                <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
                  <Avatar name={f.user.displayName} size={24} />
                  <div><div style={{ fontSize: 13, fontWeight: 500 }}>{f.user.displayName}</div><div style={{ fontFamily: 'var(--font-mono)', fontSize: 11.5, color: 'var(--fg-muted)' }}>{f.user.email}</div></div>
                </div>
              </td>
              <td style={{ padding: '12px 14px' }}>{f.reason.replace(/-/g, ' ')}</td>
              <td style={{ padding: '12px 14px' }}>
                <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                  <div style={{ width: 60, height: 6, background: 'var(--bg-muted)', borderRadius: 3, overflow: 'hidden' }}>
                    <div style={{ width: f.score + '%', height: '100%', background: f.score >= 80 ? 'var(--danger)' : f.score >= 60 ? 'var(--warning)' : 'var(--brand)' }} />
                  </div>
                  <span style={{ fontWeight: 600, fontVariantNumeric: 'tabular-nums' }}>{f.score}</span>
                </div>
              </td>
              <td style={{ padding: '12px 14px', color: 'var(--fg-muted)' }}>{f.created}</td>
              <td style={{ padding: '12px 14px' }}><StatusBadge status={f.status} /></td>
            </tr>
          ))}</tbody>
        </table>
      </div>

      <SlideOver open={!!open} onClose={() => setOpenId(null)} title={`Fraud flag ${open?.id}`} width={520}
        onPrev={idx > 0 ? () => setOpenId(filtered[idx - 1].id) : null}
        onNext={idx < filtered.length - 1 ? () => setOpenId(filtered[idx + 1].id) : null}
        footer={<div style={{ display: 'flex', gap: 8, justifyContent: 'flex-end', flexWrap: 'wrap' }}>
          <Btn variant="ghost" size="sm" style={{ color: 'var(--danger)' }}>Ban user</Btn>
          <Btn variant="ghost" size="sm">Recheck ownership</Btn>
          <Btn variant="primary" size="sm">{open?.status === 'open' ? 'Resolve' : 'Reopen'}</Btn>
        </div>}>
        {open && (
          <div>
            <div style={{ display: 'flex', gap: 12, marginBottom: 16 }}>
              <Avatar name={open.user.displayName} size={40} />
              <div>
                <div style={{ fontSize: 15, fontWeight: 600 }}>{open.user.displayName}</div>
                <div style={{ fontFamily: 'var(--font-mono)', fontSize: 12, color: 'var(--fg-muted)' }}>{open.user.email}</div>
                <div style={{ fontSize: 12, color: 'var(--fg-subtle)', marginTop: 2 }}>Account created {open.user.created}</div>
              </div>
            </div>
            <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 12, marginBottom: 14 }}>
              <div className="card" style={{ padding: 12 }}>
                <div style={{ fontSize: 11, fontWeight: 600, color: 'var(--fg-subtle)', textTransform: 'uppercase', letterSpacing: '0.05em' }}>Reason</div>
                <div style={{ fontSize: 14, fontWeight: 500, marginTop: 4 }}>{open.reason.replace(/-/g, ' ')}</div>
              </div>
              <div className="card" style={{ padding: 12 }}>
                <div style={{ fontSize: 11, fontWeight: 600, color: 'var(--fg-subtle)', textTransform: 'uppercase', letterSpacing: '0.05em' }}>Confidence</div>
                <div style={{ fontSize: 18, fontWeight: 700, color: open.score >= 80 ? 'var(--danger)' : 'var(--warning)', marginTop: 4 }}>{open.score} / 100</div>
              </div>
            </div>
            <div className="card" style={{ padding: 14, marginBottom: 14 }}>
              <div style={{ fontSize: 11, fontWeight: 600, color: 'var(--fg-subtle)', textTransform: 'uppercase', letterSpacing: '0.05em', marginBottom: 6 }}>Detected patterns</div>
              <ul style={{ fontSize: 13, color: 'var(--fg-muted)', lineHeight: 1.6, paddingLeft: 18, margin: 0 }}>
                <li>14 bids placed within 6 minutes across 3 listings</li>
                <li>Bid amounts increased by exact L$10 increments</li>
                <li>2 other accounts share IP 198.51.100.42 (Berlin)</li>
                <li>Account is 11 days old</li>
              </ul>
            </div>
            <label className="field-label">Admin notes</label>
            <textarea className="textarea" rows={4} placeholder="Document your findings before resolving…" />
          </div>
        )}
      </SlideOver>
    </AdminShell>
  );
}

function AdminAuditLogPage({ setPage }) {
  const A = window.ADMIN_DATA;
  const [actionType, setActionType] = React.useState('all');
  const [admin, setAdmin] = React.useState('');
  let rows = A.AUDIT_LOG;
  if (actionType !== 'all') rows = rows.filter(r => r.action === actionType);
  if (admin.trim()) rows = rows.filter(r => r.admin.includes(admin.toLowerCase()));

  const ACTION_TONES = {
    BAN_CREATED: 'danger', BAN_LIFTED: 'success', DISPUTE_RESOLVED: 'success', DISPUTE_FROZEN: 'warning',
    REPORT_REVIEWED: 'neutral', REPORT_DISMISSED: 'neutral', FRAUD_FLAG_RESOLVED: 'success',
    LISTING_SUSPENDED: 'warning', LISTING_CANCELLED: 'danger', LISTING_REINSTATED: 'success',
    USER_DELETED: 'danger', USER_FORCE_VERIFIED: 'success', PENALTY_OVERRIDDEN: 'warning',
    SECRET_ROTATED: 'info', WITHDRAWAL_APPROVED: 'success',
  };

  return (
    <AdminShell page="admin-audit-log" setPage={setPage} title="Admin audit log"
      subtitle="Every admin action — fraud-flag triage, reports, bans, disputes, withdrawals, secret rotations."
      actions={<Btn variant="secondary"><Icons.ArrowUpRight size={13} /> Download CSV</Btn>}>
      <div style={{ display: 'flex', gap: 10, marginBottom: 16, flexWrap: 'wrap' }}>
        <input className="input" type="date" defaultValue="2025-04-26" style={{ width: 150 }} />
        <span style={{ alignSelf: 'center', color: 'var(--fg-muted)', fontSize: 13 }}>to</span>
        <input className="input" type="date" defaultValue="2025-04-30" style={{ width: 150 }} />
        <select className="select" value={actionType} onChange={(e) => setActionType(e.target.value)} style={{ width: 220 }}>
          <option value="all">All action types</option>
          {Object.keys(ACTION_TONES).map(a => <option key={a} value={a}>{a.replace(/_/g, ' ').toLowerCase()}</option>)}
        </select>
        <input className="input" placeholder="Admin user…" value={admin} onChange={(e) => setAdmin(e.target.value)} style={{ width: 180 }} />
        <input className="input" placeholder="Affected user / resource…" style={{ flex: 1, minWidth: 200 }} />
      </div>
      <div className="card" style={{ overflow: 'hidden' }}>
        <table style={{ width: '100%', fontSize: 13, borderCollapse: 'collapse' }}>
          <thead><tr style={{ background: 'var(--bg-subtle)' }}>
            {['Timestamp', 'Action', 'Admin', 'Affected', 'Description'].map(h => (
              <th key={h} style={{ textAlign: 'left', padding: '10px 14px', fontSize: 11, fontWeight: 600, color: 'var(--fg-muted)', textTransform: 'uppercase', letterSpacing: '0.05em', borderBottom: '1px solid var(--border)' }}>{h}</th>
            ))}
          </tr></thead>
          <tbody>{rows.map(r => (
            <tr key={r.id} style={{ borderTop: '1px solid var(--border-subtle)' }}>
              <td style={{ padding: '10px 14px', fontFamily: 'var(--font-mono)', fontSize: 12, color: 'var(--fg-muted)', whiteSpace: 'nowrap' }}>{r.timestamp}</td>
              <td style={{ padding: '10px 14px' }}><Badge tone={ACTION_TONES[r.action] || 'neutral'}>{r.action.replace(/_/g, ' ').toLowerCase()}</Badge></td>
              <td style={{ padding: '10px 14px', fontFamily: 'var(--font-mono)', fontSize: 12 }}>{r.admin}</td>
              <td style={{ padding: '10px 14px', fontFamily: 'var(--font-mono)', fontSize: 12 }}>{r.target.email}</td>
              <td style={{ padding: '10px 14px', color: 'var(--fg-muted)' }}>{r.summary}</td>
            </tr>
          ))}</tbody>
        </table>
      </div>
      <div style={{ marginTop: 14, fontSize: 13, color: 'var(--fg-muted)' }}>Showing {rows.length} of {A.AUDIT_LOG.length} total entries</div>
    </AdminShell>
  );
}

function AdminBansPage({ setPage }) {
  const A = window.ADMIN_DATA;
  const [tab, setTab] = React.useState('active');
  const [type, setType] = React.useState('all');
  const [modal, setModal] = React.useState(null);
  const [liftBan, setLiftBan] = React.useState(null);
  let rows = A.BANS.filter(b => tab === 'active' ? b.status === 'active' : b.status === 'lifted');
  if (type !== 'all') rows = rows.filter(b => b.type === type);
  const activeCount = A.BANS.filter(b => b.status === 'active').length;

  return (
    <AdminShell page="admin-bans" setPage={setPage} title="Bans" subtitle={`${activeCount} active · ${A.BANS.length - activeCount} lifted/expired`}
      actions={<Btn variant="primary" onClick={() => setModal('create')}><Icons.Plus size={13} /> Create ban</Btn>}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 16, flexWrap: 'wrap', gap: 10 }}>
        <div style={{ display: 'flex', gap: 4, borderBottom: '1px solid var(--border)' }}>
          {[['active','Active',activeCount],['history','History',A.BANS.length - activeCount]].map(([k,l,c]) => (
            <button key={k} onClick={() => setTab(k)} className="btn btn--ghost" style={{
              borderRadius: 0, padding: '10px 14px', fontSize: 13.5, fontWeight: 500,
              borderBottom: '2px solid ' + (tab === k ? 'var(--brand)' : 'transparent'),
              color: tab === k ? 'var(--fg)' : 'var(--fg-muted)', marginBottom: -1,
            }}>{l} <span style={{ marginLeft: 4, fontSize: 11, color: 'var(--fg-subtle)' }}>{c}</span></button>
          ))}
        </div>
        <select className="select" value={type} onChange={(e) => setType(e.target.value)} style={{ width: 180 }}>
          <option value="all">All types</option><option value="ip">IP</option><option value="avatar">Avatar</option><option value="both">Both</option>
        </select>
      </div>
      <div className="card" style={{ overflow: 'hidden' }}>
        <table style={{ width: '100%', fontSize: 13.5, borderCollapse: 'collapse' }}>
          <thead><tr style={{ background: 'var(--bg-subtle)' }}>
            {['Ban', 'Target', 'Type', 'Reason', 'Created', 'Created by', 'Expires', 'Status', ''].map((h, i) => (
              <th key={i} style={{ textAlign: 'left', padding: '10px 14px', fontSize: 11, fontWeight: 600, color: 'var(--fg-muted)', textTransform: 'uppercase', letterSpacing: '0.05em', borderBottom: '1px solid var(--border)' }}>{h}</th>
            ))}
          </tr></thead>
          <tbody>{rows.map(b => (
            <tr key={b.id} style={{ borderTop: '1px solid var(--border-subtle)' }}>
              <td style={{ padding: '12px 14px', fontFamily: 'var(--font-mono)', fontSize: 12 }}>{b.id}</td>
              <td style={{ padding: '12px 14px', fontFamily: 'var(--font-mono)', fontSize: 12.5 }}>{b.target}</td>
              <td style={{ padding: '12px 14px' }}><Badge tone="neutral">{b.type}</Badge></td>
              <td style={{ padding: '12px 14px', maxWidth: 240, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }} title={b.reason}>{b.reason}</td>
              <td style={{ padding: '12px 14px', color: 'var(--fg-muted)' }}>{b.created}</td>
              <td style={{ padding: '12px 14px', fontFamily: 'var(--font-mono)', fontSize: 12 }}>{b.createdBy}</td>
              <td style={{ padding: '12px 14px', color: 'var(--fg-muted)' }}>{b.expires}</td>
              <td style={{ padding: '12px 14px' }}><StatusBadge status={b.status} /></td>
              <td style={{ padding: '12px 14px' }}>{b.status === 'active' && <Btn variant="ghost" size="sm" onClick={() => setLiftBan(b)}>Lift</Btn>}</td>
            </tr>
          ))}</tbody>
        </table>
      </div>

      <Modal open={modal === 'create'} onClose={() => setModal(null)} title="Create ban" width={500}
        footer={<><Btn variant="ghost" onClick={() => setModal(null)}>Cancel</Btn><Btn variant="primary" onClick={() => setModal(null)}>Create ban</Btn></>}>
        <div style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
          <div><label className="field-label">Ban type</label>
            <select className="select"><option>Avatar</option><option>IP</option><option>Both (avatar + IP)</option></select>
          </div>
          <div><label className="field-label">Target</label>
            <input className="input" placeholder="email, IP address, or SL avatar name" />
            <div className="field-help">For IP bans, provide a single IPv4 address. For Avatar, the SL avatar name (e.g. <code>aria.northcrest</code>).</div>
          </div>
          <div><label className="field-label">Reason (required)</label>
            <textarea className="textarea" rows={3} placeholder="Visible only to admins. Be specific." />
          </div>
          <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 12 }}>
            <div><label className="field-label">Duration</label><select className="select"><option>Permanent</option><option>Custom expiration</option></select></div>
            <div><label className="field-label">Expiration date</label><input className="input" type="date" /></div>
          </div>
        </div>
      </Modal>

      <Modal open={!!liftBan} onClose={() => setLiftBan(null)} title="Lift ban"
        footer={<><Btn variant="ghost" onClick={() => setLiftBan(null)}>Cancel</Btn><Btn variant="primary" onClick={() => setLiftBan(null)}>Lift ban</Btn></>}>
        {liftBan && (
          <>
            <div style={{ marginBottom: 14, padding: 12, background: 'var(--bg-subtle)', borderRadius: 'var(--r-md)', fontSize: 13 }}>
              <div><strong>Target:</strong> <span className="mono">{liftBan.target}</span></div>
              <div><strong>Type:</strong> {liftBan.type}</div>
              <div><strong>Reason:</strong> {liftBan.reason}</div>
              <div style={{ color: 'var(--fg-muted)', marginTop: 4 }}>Created {liftBan.created} by {liftBan.createdBy} · expires {liftBan.expires}</div>
            </div>
            <label className="field-label">Reason for lifting (optional)</label>
            <textarea className="textarea" rows={3} placeholder="Note for the audit log…" />
          </>
        )}
      </Modal>
    </AdminShell>
  );
}

function AdminInfrastructurePage({ setPage }) {
  const A = window.ADMIN_DATA;
  return (
    <AdminShell page="admin-infrastructure" setPage={setPage} title="Infrastructure"
      subtitle="Operational health · last refreshed 12 seconds ago"
      actions={<Btn variant="secondary"><Icons.Refresh size={13} /> Refresh</Btn>}>
      <div style={{ marginBottom: 14, fontSize: 13, fontWeight: 600, color: 'var(--fg-muted)', textTransform: 'uppercase', letterSpacing: '0.05em' }}>Service health</div>
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(4, 1fr)', gap: 12, marginBottom: 32 }}>
        {A.SERVICES.map(s => (
          <div key={s.name} className="card" style={{ padding: 14 }}>
            <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 8 }}>
              <div style={{ fontSize: 13, fontWeight: 500 }}>{s.name}</div>
              <Badge tone={s.status === 'up' ? 'success' : s.status === 'degraded' ? 'warning' : 'danger'} dot pulse={s.status !== 'up'}>{s.status}</Badge>
            </div>
            <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: 11.5, color: 'var(--fg-muted)' }}>
              <span>Uptime <strong style={{ color: 'var(--fg)', fontVariantNumeric: 'tabular-nums' }}>{s.uptime}%</strong></span>
              <span>{s.latency}ms</span>
            </div>
            <div style={{ marginTop: 6, fontSize: 11.5, color: s.errorRate > 1 ? 'var(--danger)' : 'var(--fg-subtle)' }}>Error rate {s.errorRate}%</div>
          </div>
        ))}
      </div>

      <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 24 }}>
        <div>
          <div style={{ marginBottom: 14, fontSize: 13, fontWeight: 600, color: 'var(--fg-muted)', textTransform: 'uppercase', letterSpacing: '0.05em' }}>Queue depths</div>
          <div className="card" style={{ overflow: 'hidden' }}>
            <table style={{ width: '100%', fontSize: 13, borderCollapse: 'collapse' }}>
              <thead><tr style={{ background: 'var(--bg-subtle)' }}>
                {['Queue', 'Depth', 'Oldest item'].map(h => <th key={h} style={{ textAlign: 'left', padding: '10px 14px', fontSize: 11, fontWeight: 600, color: 'var(--fg-muted)', textTransform: 'uppercase', letterSpacing: '0.05em' }}>{h}</th>)}
              </tr></thead>
              <tbody>{A.QUEUES.map(q => (
                <tr key={q.name} style={{ borderTop: '1px solid var(--border-subtle)' }}>
                  <td style={{ padding: '10px 14px', fontWeight: 500 }}>{q.name}</td>
                  <td style={{ padding: '10px 14px', fontVariantNumeric: 'tabular-nums', fontWeight: 600, color: q.depth > 100 ? 'var(--warning)' : 'var(--fg)' }}>{q.depth}</td>
                  <td style={{ padding: '10px 14px', color: 'var(--fg-muted)' }}>{q.age}</td>
                </tr>
              ))}</tbody>
            </table>
          </div>
        </div>

        <div>
          <div style={{ marginBottom: 14, fontSize: 13, fontWeight: 600, color: 'var(--fg-muted)', textTransform: 'uppercase', letterSpacing: '0.05em' }}>Recent incidents</div>
          <div className="card" style={{ padding: 0 }}>
            {A.INCIDENTS.map((inc, i) => (
              <div key={inc.id} style={{ padding: 14, borderTop: i === 0 ? 'none' : '1px solid var(--border-subtle)' }}>
                <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', gap: 12 }}>
                  <div style={{ flex: 1 }}>
                    <div style={{ fontSize: 11.5, fontFamily: 'var(--font-mono)', color: 'var(--fg-subtle)' }}>{inc.id}</div>
                    <div style={{ fontSize: 13.5, fontWeight: 500, marginTop: 2 }}>{inc.title}</div>
                    <div style={{ fontSize: 12, color: 'var(--fg-muted)', marginTop: 2 }}>{inc.service} · opened {inc.opened}</div>
                  </div>
                  <Badge tone={inc.status === 'resolved' ? 'success' : inc.severity === 'major' ? 'danger' : 'warning'} dot={inc.status !== 'resolved'}>{inc.status}</Badge>
                </div>
              </div>
            ))}
          </div>
        </div>
      </div>

      <div style={{ marginTop: 32, marginBottom: 14, fontSize: 13, fontWeight: 600, color: 'var(--fg-muted)', textTransform: 'uppercase', letterSpacing: '0.05em' }}>Live metrics</div>
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(4, 1fr)', gap: 12 }}>
        <StatCard label="Active connections" value="3,481" hint="Across all services" />
        <StatCard label="Requests / min" value="12,847" hint="API gateway · 5m avg" />
        <StatCard label="P95 latency" value="184 ms" hint="Within SLA" />
        <StatCard label="Background jobs / hr" value="48,221" hint="Stable" />
      </div>
    </AdminShell>
  );
}

window.AdminReportsPage = AdminReportsPage;
window.AdminFraudFlagsPage = AdminFraudFlagsPage;
window.AdminAuditLogPage = AdminAuditLogPage;
window.AdminBansPage = AdminBansPage;
window.AdminInfrastructurePage = AdminInfrastructurePage;
