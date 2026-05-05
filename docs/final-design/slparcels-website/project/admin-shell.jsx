// admin-shell.jsx — admin sidebar shell + shared admin data + modals/slide-overs

(function() {
  // Synthetic admin data
  const D = window.SLP_DATA;

  function makeUser(i, overrides = {}) {
    const names = [
      ['Aria Northcrest', 'aria.northcrest', 'aria@northcrest.io'],
      ['Devon Hale', 'devon.hale', 'd.hale@gmail.com'],
      ['Mira Solano', 'mira.solano', 'mira.s@studio.co'],
      ['Otto Whitmer', 'otto.w', 'otto@protonmail.com'],
      ['Sable Vance', 'sable.vance', 'sable.v@example.com'],
      ['Nova Branch', 'nova.b', 'novab@inbox.io'],
      ['Quill Marsh', 'quill.marsh', 'quill@cove.dev'],
      ['Ezra Lin', 'ezra.lin', 'ezra@helio.app'],
      ['Renn Volkov', 'renn.volkov', 'renn@blockmail.io'],
      ['Tess Rune', 'tess.rune', 'tess.r@studio.co'],
      ['Cyril Blanche', 'cyril.b', 'cyril@protonmail.com'],
      ['Onyx Hale', 'onyx.hale', 'onyx@northcrest.io'],
    ];
    const n = names[i % names.length];
    return {
      id: 'u' + (1000 + i),
      displayName: n[0],
      slName: n[1],
      email: n[2],
      verified: i % 5 !== 0,
      created: ['2022-08-14','2023-01-22','2023-04-05','2023-06-19','2023-09-30','2024-01-11','2024-03-04','2024-05-22','2024-07-08','2024-09-15','2024-11-02','2025-01-18'][i % 12],
      status: i % 11 === 0 ? 'banned' : i % 7 === 0 ? 'suspended' : 'active',
      ...overrides,
    };
  }

  const ADMIN_USERS = Array.from({length: 24}, (_, i) => makeUser(i));

  const DISPUTES = Array.from({length: 12}, (_, i) => ({
    id: 'd' + (4001 + i),
    parcelTitle: D.AUCTIONS[i % D.AUCTIONS.length].title,
    parcelCoords: D.AUCTIONS[i % D.AUCTIONS.length].coords,
    seller: makeUser(i),
    winner: makeUser(i + 5),
    amount: 8500 + (i * 1430) % 22000,
    status: i % 4 === 0 ? 'FROZEN' : 'DISPUTED',
    filed: ['2 hours ago', '5 hours ago', '1 day ago', '2 days ago', '3 days ago', '5 days ago', '1 wk ago', '1 wk ago', '2 wks ago', '2 wks ago', '3 wks ago', '4 wks ago'][i],
    updated: ['12 min ago', '1 hour ago', '4 hours ago', '8 hours ago', '1 day ago', '1 day ago', '2 days ago', '3 days ago', '4 days ago', '1 wk ago', '2 wks ago', '3 wks ago'][i],
    reason: ['not-responding', 'wrong-parcel', 'paid-not-funded', 'fraud', 'other', 'not-responding', 'wrong-parcel', 'paid-not-funded', 'not-responding', 'fraud', 'other', 'wrong-parcel'][i],
  }));

  const REPORTS = Array.from({length: 14}, (_, i) => ({
    id: 'rp' + (7001 + i),
    parcelTitle: D.AUCTIONS[i % D.AUCTIONS.length].title,
    reportedUser: makeUser(i),
    reporter: makeUser(i + 7),
    category: ['scam', 'misleading-listing', 'spam', 'inappropriate', 'covenant-violation', 'duplicate-listing', 'scam', 'spam', 'misleading-listing', 'inappropriate', 'covenant-violation', 'scam', 'spam', 'misleading-listing'][i],
    filed: ['1 hour ago', '3 hours ago', '6 hours ago', '11 hours ago', '1 day ago', '2 days ago', '3 days ago', '4 days ago', '5 days ago', '6 days ago', '1 wk ago', '2 wks ago', '3 wks ago', '4 wks ago'][i],
    status: i < 5 ? 'open' : 'reviewed',
    description: 'User submitted a parcel listing with claims that don\'t match the in-world covenant. Buyer alleges seller refused to honor stated terms.',
  }));

  const FLAGS = Array.from({length: 10}, (_, i) => ({
    id: 'f' + (9001 + i),
    user: makeUser(i + 2),
    reason: ['rapid-bid-pattern', 'multiple-accounts-same-ip', 'unusual-withdrawal-pattern', 'shill-bidding', 'rapid-bid-pattern', 'flagged-by-system', 'multiple-accounts-same-ip', 'shill-bidding', 'unusual-withdrawal-pattern', 'rapid-bid-pattern'][i],
    score: [97, 88, 82, 78, 74, 71, 68, 64, 58, 53][i],
    created: ['25 min ago', '1 hour ago', '4 hours ago', '11 hours ago', '1 day ago', '2 days ago', '3 days ago', '5 days ago', '1 wk ago', '2 wks ago'][i],
    status: i < 4 ? 'open' : 'resolved',
  }));

  const BANS = Array.from({length: 14}, (_, i) => ({
    id: 'b' + (3001 + i),
    target: i % 2 === 0 ? makeUser(i).email : '198.51.100.' + (10 + i),
    targetUser: makeUser(i),
    type: ['avatar', 'ip', 'both', 'avatar', 'ip', 'avatar', 'both', 'ip', 'avatar', 'avatar', 'ip', 'both', 'avatar', 'ip'][i],
    reason: ['Fraud — confirmed shill bidding', 'Multi-account abuse', 'Repeated covenant violation', 'Payment chargeback fraud', 'Spam listings', 'Threats to other users', 'Repeated dispute fraud', 'Botted bidding pattern', 'Fake parcel ownership', 'Manipulated wallet', 'Multi-account abuse', 'Fraud — confirmed', 'Repeated abuse reports', 'Botted bidding pattern'][i],
    created: ['2 days ago','5 days ago','1 wk ago','2 wks ago','3 wks ago','1 mo ago','1 mo ago','2 mo ago','2 mo ago','3 mo ago','4 mo ago','5 mo ago','6 mo ago','8 mo ago'][i],
    createdBy: ['admin.eve', 'admin.kai', 'admin.eve', 'admin.maru', 'admin.eve', 'admin.kai', 'admin.maru', 'admin.kai', 'admin.eve', 'admin.maru', 'admin.eve', 'admin.kai', 'admin.maru', 'admin.eve'][i],
    expires: i % 3 === 0 ? 'permanent' : ['in 28 days','in 4 days','in 11 days','in 22 days','in 5 days','in 14 days','in 31 days','in 9 days','in 18 days','in 6 days','in 13 days','in 28 days','in 22 days','in 4 days'][i],
    status: i < 9 ? 'active' : 'lifted',
  }));

  const AUDIT_LOG = Array.from({length: 30}, (_, i) => ({
    id: 'al' + (5000 + i),
    timestamp: ['2025-04-30 14:22:18','2025-04-30 13:11:04','2025-04-30 12:45:33','2025-04-30 11:08:12','2025-04-30 09:56:48','2025-04-30 08:33:01','2025-04-29 23:14:55','2025-04-29 22:01:09','2025-04-29 19:47:22','2025-04-29 17:33:18','2025-04-29 15:10:41','2025-04-29 13:28:55','2025-04-29 11:02:44','2025-04-29 09:14:12','2025-04-28 22:48:36','2025-04-28 19:55:21','2025-04-28 17:33:08','2025-04-28 14:11:54','2025-04-28 11:48:22','2025-04-28 09:33:18','2025-04-27 22:14:55','2025-04-27 19:01:09','2025-04-27 17:47:22','2025-04-27 14:33:18','2025-04-27 12:10:41','2025-04-27 10:28:55','2025-04-26 23:02:44','2025-04-26 21:14:12','2025-04-26 18:48:36','2025-04-26 14:55:21'][i],
    action: ['BAN_CREATED','REPORT_REVIEWED','DISPUTE_RESOLVED','FRAUD_FLAG_RESOLVED','LISTING_SUSPENDED','BAN_LIFTED','REPORT_DISMISSED','DISPUTE_FROZEN','SECRET_ROTATED','WITHDRAWAL_APPROVED','LISTING_REINSTATED','USER_FORCE_VERIFIED','BAN_CREATED','PENALTY_OVERRIDDEN','REPORT_REVIEWED','DISPUTE_RESOLVED','FRAUD_FLAG_RESOLVED','BAN_CREATED','LISTING_CANCELLED','USER_DELETED','REPORT_REVIEWED','DISPUTE_FROZEN','BAN_LIFTED','FRAUD_FLAG_RESOLVED','LISTING_SUSPENDED','SECRET_ROTATED','WITHDRAWAL_APPROVED','REPORT_DISMISSED','BAN_CREATED','DISPUTE_RESOLVED'][i],
    admin: ['admin.eve','admin.kai','admin.maru','admin.eve','admin.kai','admin.maru','admin.eve','admin.kai','admin.maru','admin.eve','admin.kai','admin.maru','admin.eve','admin.kai','admin.maru','admin.eve','admin.kai','admin.maru','admin.eve','admin.kai','admin.maru','admin.eve','admin.kai','admin.maru','admin.eve','admin.kai','admin.maru','admin.eve','admin.kai','admin.maru'][i],
    target: makeUser(i % 12),
    summary: ['Banned avatar for confirmed shill bidding','Marked report rp7001 reviewed','Resolved dispute d4001 — refund to winner','Resolved fraud flag f9001 — false positive','Suspended listing a3 — covenant violation','Lifted ban b3009','Dismissed report — insufficient evidence','Froze escrow d4002 pending evidence','Rotated SLParcels-bot signing secret','Approved withdrawal of L$ 28,400','Reinstated listing a7 after appeal','Force-verified avatar after manual check','Banned IP for multi-account abuse','Overrode 7-day cancellation cooldown','Marked report rp7003 reviewed','Resolved dispute d4003 — release to seller','Resolved fraud flag f9003 — confirmed action','Banned avatar — repeated dispute fraud','Cancelled listing a5','Deleted user account per request','Marked report rp7005 reviewed','Froze escrow d4005','Lifted ban b3010','Resolved fraud flag f9004','Suspended listing a9','Rotated escrow signing secret','Approved withdrawal of L$ 14,200','Dismissed report — duplicate','Banned avatar — botted bidding','Resolved dispute d4006 — partial refund'][i],
  }));

  const SERVICES = [
    { name: 'API gateway', uptime: 99.998, status: 'up', latency: 42, errorRate: 0.02 },
    { name: 'Database (primary)', uptime: 99.992, status: 'up', latency: 8, errorRate: 0.00 },
    { name: 'Queue workers', uptime: 99.987, status: 'up', latency: 220, errorRate: 0.04 },
    { name: 'Email service', uptime: 99.71, status: 'degraded', latency: 1820, errorRate: 1.81 },
    { name: 'SL bot worker', uptime: 99.945, status: 'up', latency: 340, errorRate: 0.12 },
    { name: 'Webhook delivery', uptime: 99.98, status: 'up', latency: 88, errorRate: 0.07 },
    { name: 'CDN / static', uptime: 99.999, status: 'up', latency: 18, errorRate: 0.00 },
    { name: 'Background scheduler', uptime: 99.92, status: 'up', latency: 410, errorRate: 0.18 },
  ];

  const QUEUES = [
    { name: 'Payment processing', depth: 14, age: '14s' },
    { name: 'Verification queue', depth: 3, age: '8s' },
    { name: 'Dispute resolution', depth: 22, age: '4 hours' },
    { name: 'Escrow settlement', depth: 7, age: '2m' },
    { name: 'Email outbound', depth: 218, age: '12m' },
    { name: 'SL IM dispatch', depth: 41, age: '1m' },
  ];

  const INCIDENTS = [
    { id: 'INC-2814', title: 'Email delivery latency elevated', service: 'Email service', severity: 'minor', opened: '38 min ago', status: 'monitoring' },
    { id: 'INC-2807', title: 'Webhook retry backoff exhausted on payments-v2', service: 'Webhook delivery', severity: 'minor', opened: '2 hours ago', status: 'investigating' },
    { id: 'INC-2799', title: 'Scheduled DB maintenance — read-replica failover', service: 'Database', severity: 'maintenance', opened: '1 day ago', status: 'resolved' },
    { id: 'INC-2793', title: 'Queue worker OOM on large evidence-attachment dispute', service: 'Queue workers', severity: 'major', opened: '3 days ago', status: 'resolved' },
  ];

  const PLATFORM_STATS = {
    activeListings: 1247,
    totalUsers: 14_823,
    activeEscrows: 89,
    completedSales: 8_432,
    grossVolume: 41_829_400,
    commissionEarned: 1_672_376,
  };

  const QUEUE_COUNTS = {
    fraudFlags: FLAGS.filter(f => f.status === 'open').length,
    reports: REPORTS.filter(r => r.status === 'open').length,
    pendingPayments: 12,
    disputes: DISPUTES.length,
  };

  window.ADMIN_DATA = { ADMIN_USERS, DISPUTES, REPORTS, FLAGS, BANS, AUDIT_LOG, SERVICES, QUEUES, INCIDENTS, PLATFORM_STATS, QUEUE_COUNTS };
})();

// ============================================================
// AdminShell — admin layout w/ sidebar
// ============================================================
function AdminShell({ page, setPage, title, subtitle, actions, children }) {
  const A = window.ADMIN_DATA;
  const [openMobile, setOpenMobile] = React.useState(false);
  const items = [
    { k: 'admin-home',     l: 'Dashboard',      icon: <Icons.Home size={15} /> },
    { k: 'admin-users',    l: 'Users',          icon: <Icons.User size={15} /> },
    { k: 'admin-disputes', l: 'Disputes',       icon: <Icons.AlertCircle size={15} />, count: A.QUEUE_COUNTS.disputes },
    { k: 'admin-reports',  l: 'Reports',        icon: <Icons.AlertTriangle size={15} />, count: A.QUEUE_COUNTS.reports },
    { k: 'admin-fraud-flags', l: 'Fraud flags', icon: <Icons.Shield size={15} />, count: A.QUEUE_COUNTS.fraudFlags },
    { k: 'admin-bans',     l: 'Bans',           icon: <Icons.Lock size={15} /> },
    { k: 'admin-audit-log',l: 'Audit log',      icon: <Icons.Clock size={15} /> },
    { k: 'admin-infrastructure', l: 'Infrastructure', icon: <Icons.Zap size={15} /> },
  ];
  // detail pages collapse to their parent
  const activeKey = page === 'admin-user-detail' ? 'admin-users'
                  : page === 'admin-dispute-detail' ? 'admin-disputes' : page;

  return (
    <div style={{ display: 'grid', gridTemplateColumns: '232px 1fr', minHeight: 'calc(100vh - var(--header-h))' }} className="admin-shell">
      <aside className="admin-sidebar hide-mobile" style={{
        background: 'var(--bg-subtle)', borderRight: '1px solid var(--border)',
        padding: '20px 14px', position: 'sticky', top: 'var(--header-h)', height: 'calc(100vh - var(--header-h))',
        display: 'flex', flexDirection: 'column', gap: 2, overflowY: 'auto',
      }}>
        <div style={{
          fontSize: 10.5, fontWeight: 700, letterSpacing: '0.08em', textTransform: 'uppercase',
          color: 'var(--fg-subtle)', padding: '4px 10px 12px',
        }}>Admin console</div>
        {items.map(it => (
          <button key={it.k} onClick={() => setPage(it.k)} style={{
            display: 'flex', alignItems: 'center', gap: 10, width: '100%',
            padding: '8px 10px', borderRadius: 'var(--r-sm)', border: 'none',
            background: activeKey === it.k ? 'var(--surface)' : 'transparent',
            color: activeKey === it.k ? 'var(--fg)' : 'var(--fg-muted)',
            fontSize: 13.5, fontWeight: 500, cursor: 'pointer', textAlign: 'left',
            boxShadow: activeKey === it.k ? '0 1px 2px rgba(0,0,0,.04)' : 'none',
          }}>
            <span style={{ color: activeKey === it.k ? 'var(--brand)' : 'var(--fg-muted)' }}>{it.icon}</span>
            <span style={{ flex: 1 }}>{it.l}</span>
            {it.count > 0 && (
              <span style={{
                background: activeKey === it.k ? 'var(--brand)' : 'var(--bg-muted)',
                color: activeKey === it.k ? 'white' : 'var(--fg-muted)',
                fontSize: 11, fontWeight: 600, padding: '2px 7px', borderRadius: 999,
                minWidth: 20, textAlign: 'center',
              }}>{it.count}</span>
            )}
          </button>
        ))}
        <div style={{ flex: 1 }} />
        <div style={{ padding: '12px 10px', borderTop: '1px solid var(--border)', marginTop: 12 }}>
          <div style={{ display: 'flex', gap: 10, alignItems: 'center' }}>
            <Avatar name="Admin Eve" size={32} />
            <div style={{ minWidth: 0, flex: 1 }}>
              <div style={{ fontSize: 13, fontWeight: 600, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>Admin Eve</div>
              <div style={{ fontSize: 11, color: 'var(--fg-subtle)' }}>Senior moderator</div>
            </div>
          </div>
          <button onClick={() => setPage('home')} style={{
            display: 'flex', alignItems: 'center', gap: 6, marginTop: 10, width: '100%',
            padding: '6px 8px', fontSize: 12, color: 'var(--fg-muted)',
            background: 'transparent', border: '1px solid var(--border)', borderRadius: 'var(--r-sm)', cursor: 'pointer',
          }}><Icons.ChevronLeft size={12} /> Back to site</button>
        </div>
      </aside>
      <main style={{ background: 'var(--bg)', minWidth: 0 }}>
        <div style={{ padding: '24px 32px 16px', borderBottom: '1px solid var(--border-subtle)', background: 'var(--surface)', display: 'flex', justifyContent: 'space-between', alignItems: 'flex-end', gap: 16, flexWrap: 'wrap' }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
            <button className="show-mobile" onClick={() => setOpenMobile(true)} aria-label="Menu" style={{
              border: '1px solid var(--border)', background: 'var(--surface)', borderRadius: 'var(--r-sm)',
              padding: 8, cursor: 'pointer', color: 'var(--fg)', display: 'none',
            }}>
              <Icons.Menu size={16} />
            </button>
            <div>
              <h1 style={{ fontSize: 22, fontWeight: 700, letterSpacing: '-0.015em', margin: 0 }}>{title}</h1>
              {subtitle && <p style={{ fontSize: 13.5, color: 'var(--fg-muted)', margin: '4px 0 0' }}>{subtitle}</p>}
            </div>
          </div>
          {actions}
        </div>
        <div style={{ padding: '24px 32px 64px' }}>
          {children}
        </div>
      </main>
      <AdminMobileDrawer open={openMobile} onClose={() => setOpenMobile(false)} setPage={setPage} page={page} />
    </div>
  );
}

// ============================================================
// Modal & SlideOver primitives
// ============================================================
function Modal({ open, onClose, title, children, footer, width = 480 }) {
  if (!open) return null;
  return (
    <div onClick={onClose} style={{
      position: 'fixed', inset: 0, background: 'rgba(15,18,23,.55)',
      backdropFilter: 'blur(2px)', zIndex: 1000,
      display: 'grid', placeItems: 'center', padding: 20,
      animation: 'fadeIn .15s ease-out',
    }}>
      <div onClick={(e) => e.stopPropagation()} className="card" style={{
        width: '100%', maxWidth: width, maxHeight: '90vh', display: 'flex', flexDirection: 'column',
        background: 'var(--surface)', boxShadow: '0 20px 60px rgba(0,0,0,.25)',
        animation: 'slideUp .2s ease-out',
      }}>
        <div style={{ padding: '16px 20px', borderBottom: '1px solid var(--border-subtle)', display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
          <h2 style={{ fontSize: 16, fontWeight: 600, margin: 0 }}>{title}</h2>
          <button onClick={onClose} style={{ border: 'none', background: 'transparent', cursor: 'pointer', color: 'var(--fg-muted)', padding: 4 }}>
            <Icons.X size={16} />
          </button>
        </div>
        <div style={{ padding: 20, overflowY: 'auto', flex: 1 }}>{children}</div>
        {footer && <div style={{ padding: '14px 20px', borderTop: '1px solid var(--border-subtle)', display: 'flex', justifyContent: 'flex-end', gap: 8, background: 'var(--bg-subtle)' }}>{footer}</div>}
      </div>
    </div>
  );
}

function SlideOver({ open, onClose, title, children, footer, width = 520, onPrev, onNext }) {
  if (!open) return null;
  return (
    <div onClick={onClose} style={{
      position: 'fixed', inset: 0, background: 'rgba(15,18,23,.45)', zIndex: 1000,
      display: 'flex', justifyContent: 'flex-end',
      animation: 'fadeIn .15s ease-out',
    }}>
      <div onClick={(e) => e.stopPropagation()} style={{
        width, maxWidth: '100vw', height: '100vh', background: 'var(--surface)',
        boxShadow: '-12px 0 40px rgba(0,0,0,.18)',
        display: 'flex', flexDirection: 'column',
        animation: 'slideInRight .2s ease-out',
      }}>
        <div style={{ padding: '14px 20px', borderBottom: '1px solid var(--border-subtle)', display: 'flex', alignItems: 'center', gap: 8 }}>
          <h2 style={{ fontSize: 15, fontWeight: 600, margin: 0, flex: 1 }}>{title}</h2>
          {onPrev && <button onClick={onPrev} className="btn btn--ghost" style={{ padding: 6 }}><Icons.ChevronLeft size={14} /></button>}
          {onNext && <button onClick={onNext} className="btn btn--ghost" style={{ padding: 6 }}><Icons.ChevronRight size={14} /></button>}
          <button onClick={onClose} style={{ border: 'none', background: 'transparent', cursor: 'pointer', color: 'var(--fg-muted)', padding: 4 }}>
            <Icons.X size={16} />
          </button>
        </div>
        <div style={{ padding: 20, overflowY: 'auto', flex: 1 }}>{children}</div>
        {footer && <div style={{ padding: '14px 20px', borderTop: '1px solid var(--border-subtle)', background: 'var(--bg-subtle)' }}>{footer}</div>}
      </div>
    </div>
  );
}

window.AdminShell = AdminShell;
window.Modal = Modal;
window.SlideOver = SlideOver;
