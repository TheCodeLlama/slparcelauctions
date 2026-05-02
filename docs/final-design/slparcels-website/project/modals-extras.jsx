// modals-extras.jsx — modals & drawers from the audit follow-up
// SuspensionErrorModal, ReportListingModal, ConfirmBidDialog, FlagReviewModal,
// ReinstateListingModal, MobileNavDrawer, AdminMobileDrawer

// ============================================================
// 1. SuspensionErrorModal — 403 on listing edit / submit
// ============================================================
function SuspensionErrorModal({ open, onClose, setPage }) {
  return (
    <Modal open={open} onClose={onClose} title="Listing locked" width={460}
      footer={
        <>
          <Btn variant="ghost" onClick={onClose}>Close</Btn>
          <Btn variant="secondary" onClick={() => { onClose(); setPage && setPage('contact'); }}>Contact support</Btn>
          <Btn variant="primary" onClick={() => { onClose(); setPage && setPage('dashboard'); }}>Back to dashboard</Btn>
        </>
      }>
      <div style={{ display: 'flex', gap: 14, marginBottom: 14 }}>
        <div style={{
          width: 40, height: 40, borderRadius: 'var(--r-md)', flexShrink: 0,
          background: 'rgba(217, 119, 6, 0.12)', color: 'var(--warning, #b45309)',
          display: 'grid', placeItems: 'center',
        }}>
          <Icons.AlertTriangle size={20} />
        </div>
        <div>
          <div style={{ fontSize: 14, fontWeight: 600, marginBottom: 4 }}>This listing has been suspended.</div>
          <div style={{ fontSize: 13, color: 'var(--fg-muted)', lineHeight: 1.55 }}>
            A moderator paused this listing pending review. You can&apos;t edit, relist, or accept bids on it until the suspension is resolved.
          </div>
        </div>
      </div>
      <div style={{ padding: 12, background: 'var(--bg-subtle)', borderRadius: 'var(--r-md)', fontSize: 13 }}>
        <div style={{ fontSize: 11, fontWeight: 600, color: 'var(--fg-subtle)', textTransform: 'uppercase', letterSpacing: '0.05em', marginBottom: 6 }}>Reference</div>
        <div className="mono" style={{ fontSize: 12 }}>case_id: SUSP-2025-04-29-A881</div>
        <div style={{ fontSize: 12.5, color: 'var(--fg-muted)', marginTop: 6 }}>Reason summarized: <em>Pending response to a buyer report from 26 Apr</em>.</div>
      </div>
      <div style={{ marginTop: 14, fontSize: 12.5, color: 'var(--fg-subtle)' }}>
        Most suspensions are resolved within 24–48 hours after seller response.
      </div>
    </Modal>
  );
}

// ============================================================
// 2. ReportListingModal — flag a parcel from auction detail
// ============================================================
function ReportListingModal({ open, onClose, parcel }) {
  const [category, setCategory] = React.useState('');
  const [details, setDetails] = React.useState('');
  const [submitted, setSubmitted] = React.useState(false);

  const reasons = [
    ['fraud', 'Suspected fraud or impersonation', 'Seller does not actually own this parcel, or is impersonating someone.'],
    ['misleading', 'Misleading description or photos', 'Coordinates, prim allowance, or covenant differs from what is shown.'],
    ['policy', 'Policy violation', 'Adult content in mainland zone, prohibited rental terms, etc.'],
    ['spam', 'Spam or duplicate', 'Same parcel relisted multiple times, or filler content.'],
    ['other', 'Something else', 'A concern that doesn&apos;t fit the categories above.'],
  ];

  const reset = () => { setCategory(''); setDetails(''); setSubmitted(false); onClose(); };

  return (
    <Modal open={open} onClose={reset} title={submitted ? 'Report submitted' : 'Report this listing'} width={520}
      footer={
        submitted
          ? <Btn variant="primary" onClick={reset}>Done</Btn>
          : <>
              <Btn variant="ghost" onClick={reset}>Cancel</Btn>
              <Btn variant="primary" disabled={!category} onClick={() => setSubmitted(true)}>Submit report</Btn>
            </>
      }>
      {submitted ? (
        <div style={{ textAlign: 'center', padding: '12px 0 8px' }}>
          <div style={{ width: 48, height: 48, borderRadius: '50%', background: 'rgba(22,163,74,.12)', color: 'var(--success, #16a34a)', display: 'grid', placeItems: 'center', margin: '0 auto 14px' }}>
            <Icons.Check size={22} />
          </div>
          <div style={{ fontSize: 15, fontWeight: 600, marginBottom: 6 }}>Thanks — we&apos;ve received your report.</div>
          <div style={{ fontSize: 13.5, color: 'var(--fg-muted)', lineHeight: 1.55, maxWidth: 360, margin: '0 auto' }}>
            Our trust &amp; safety team reviews reports within 24 hours. We won&apos;t share your identity with the seller. If we need anything else, we&apos;ll email you at <span className="mono" style={{ color: 'var(--fg)' }}>aria@northcrest.io</span>.
          </div>
          <div style={{ marginTop: 14, fontSize: 12, color: 'var(--fg-subtle)' }}>Reference <span className="mono" style={{ color: 'var(--fg-muted)' }}>RPT-2025-04-30-7421</span></div>
        </div>
      ) : (
        <>
          <div style={{ fontSize: 13, color: 'var(--fg-muted)', marginBottom: 14, lineHeight: 1.55 }}>
            Reporting <strong style={{ color: 'var(--fg)' }}>{parcel?.title || 'this listing'}</strong>. Reports are anonymous to the seller.
          </div>
          <div style={{ display: 'flex', flexDirection: 'column', gap: 8, marginBottom: 16 }}>
            {reasons.map(([k, l, hint]) => (
              <label key={k} style={{
                display: 'flex', gap: 10, alignItems: 'flex-start', padding: 12,
                border: '1px solid ' + (category === k ? 'var(--brand)' : 'var(--border)'),
                background: category === k ? 'var(--brand-soft)' : 'var(--surface)',
                borderRadius: 'var(--r-md)', cursor: 'pointer',
              }}>
                <input type="radio" name="rpt" checked={category === k} onChange={() => setCategory(k)} style={{ marginTop: 2 }} />
                <div>
                  <div style={{ fontSize: 13.5, fontWeight: 500 }}>{l}</div>
                  <div style={{ fontSize: 12, color: 'var(--fg-muted)', marginTop: 2 }} dangerouslySetInnerHTML={{ __html: hint }} />
                </div>
              </label>
            ))}
          </div>
          <label className="field-label">Additional details (optional)</label>
          <textarea className="textarea" rows={4} value={details} onChange={(e) => setDetails(e.target.value)}
            placeholder="Coords, screenshots URLs, chat references — anything that helps us investigate." />
          <div className="field-help">Don&apos;t include the seller&apos;s personal info or anything outside this transaction.</div>
        </>
      )}
    </Modal>
  );
}

// ============================================================
// 3. ConfirmBidDialog — over-cap warning before placing huge bid
// ============================================================
function ConfirmBidDialog({ open, onClose, onConfirm, amount, currentBid, walletAvailable }) {
  const overByPct = currentBid > 0 ? Math.round(((amount - currentBid) / currentBid) * 100) : 0;
  return (
    <Modal open={open} onClose={onClose} title="Confirm large bid" width={460}
      footer={
        <>
          <Btn variant="ghost" onClick={onClose}>Go back</Btn>
          <Btn variant="primary" onClick={onConfirm}>Yes, place bid</Btn>
        </>
      }>
      <div style={{ display: 'flex', gap: 14, marginBottom: 16 }}>
        <div style={{
          width: 40, height: 40, borderRadius: 'var(--r-md)', flexShrink: 0,
          background: 'rgba(217, 119, 6, 0.12)', color: 'var(--warning, #b45309)',
          display: 'grid', placeItems: 'center',
        }}>
          <Icons.AlertTriangle size={20} />
        </div>
        <div>
          <div style={{ fontSize: 14, fontWeight: 600, marginBottom: 4 }}>This bid is unusually high.</div>
          <div style={{ fontSize: 13, color: 'var(--fg-muted)', lineHeight: 1.55 }}>
            You&apos;re about to bid <strong style={{ color: 'var(--fg)' }}>L${amount.toLocaleString()}</strong>, which is <strong style={{ color: 'var(--warning, #b45309)' }}>{overByPct}% over</strong> the current top bid of L${currentBid.toLocaleString()}. Bids are binding once placed.
          </div>
        </div>
      </div>
      <div className="card" style={{ padding: 14, background: 'var(--bg-subtle)', border: 'none' }}>
        <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: 13, marginBottom: 6 }}>
          <span style={{ color: 'var(--fg-muted)' }}>Your bid</span>
          <span className="bold tabular">L$ {amount.toLocaleString()}</span>
        </div>
        <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: 13, marginBottom: 6 }}>
          <span style={{ color: 'var(--fg-muted)' }}>Reserved from wallet</span>
          <span className="tabular">L$ {amount.toLocaleString()}</span>
        </div>
        <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: 13, paddingTop: 8, borderTop: '1px solid var(--border)' }}>
          <span style={{ color: 'var(--fg-muted)' }}>Wallet after reservation</span>
          <span className="tabular">L$ {(walletAvailable - amount).toLocaleString()}</span>
        </div>
      </div>
      <label style={{ display: 'flex', gap: 10, alignItems: 'flex-start', marginTop: 14, fontSize: 12.5, color: 'var(--fg-muted)', lineHeight: 1.55 }}>
        <input type="checkbox" defaultChecked style={{ marginTop: 3 }} />
        <span>I understand this bid is binding. If I&apos;m the highest bidder when the auction closes, the funds become escrow for the seller and cannot be cancelled without penalty.</span>
      </label>
    </Modal>
  );
}

// ============================================================
// 4. FlagReviewModal — flag an individual seller review
// ============================================================
function FlagReviewModal({ open, onClose, review }) {
  const [reason, setReason] = React.useState('');
  const reasons = [
    ['unrelated', 'Unrelated to the transaction'],
    ['abusive', 'Abusive language or harassment'],
    ['offtopic', 'Off-topic or spam'],
    ['private', 'Reveals private information'],
    ['fake', 'Looks fake or paid'],
    ['other', 'Other'],
  ];
  return (
    <Modal open={open} onClose={onClose} title="Flag this review" width={440}
      footer={
        <>
          <Btn variant="ghost" onClick={onClose}>Cancel</Btn>
          <Btn variant="primary" disabled={!reason} onClick={onClose}>Flag review</Btn>
        </>
      }>
      {review && (
        <div style={{ padding: 12, background: 'var(--bg-subtle)', borderRadius: 'var(--r-md)', fontSize: 12.5, color: 'var(--fg-muted)', marginBottom: 14, borderLeft: '3px solid var(--border)' }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 4 }}>
            <strong style={{ color: 'var(--fg)' }}>{review.author}</strong>
            <StarRating value={review.stars} size={10} />
          </div>
          &ldquo;{review.text}&rdquo;
        </div>
      )}
      <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
        {reasons.map(([k, l]) => (
          <label key={k} style={{
            display: 'flex', gap: 10, alignItems: 'center', padding: '8px 12px',
            border: '1px solid ' + (reason === k ? 'var(--brand)' : 'var(--border)'),
            background: reason === k ? 'var(--brand-soft)' : 'transparent',
            borderRadius: 'var(--r-sm)', cursor: 'pointer', fontSize: 13.5,
          }}>
            <input type="radio" name="flagrev" checked={reason === k} onChange={() => setReason(k)} />
            {l}
          </label>
        ))}
      </div>
      <div style={{ marginTop: 12, padding: 10, background: 'var(--bg-subtle)', fontSize: 12, color: 'var(--fg-muted)', borderRadius: 'var(--r-sm)' }}>
        Flags are anonymous. We review within 24 hours and remove the review if it violates our policy.
      </div>
    </Modal>
  );
}

// ============================================================
// 5. ReinstateListingModal — admin user-detail action
// ============================================================
function ReinstateListingModal({ open, onClose, listing }) {
  const [refund, setRefund] = React.useState(true);
  const [notify, setNotify] = React.useState(true);
  const [note, setNote] = React.useState('');
  return (
    <Modal open={open} onClose={onClose} title="Reinstate listing" width={500}
      footer={
        <>
          <Btn variant="ghost" onClick={onClose}>Cancel</Btn>
          <Btn variant="primary" onClick={onClose}>Reinstate listing</Btn>
        </>
      }>
      <div style={{ padding: 12, background: 'var(--bg-subtle)', borderRadius: 'var(--r-md)', marginBottom: 14, fontSize: 13 }}>
        <div style={{ fontWeight: 600 }}>{listing?.title || 'Hilltop with lighthouse rights'}</div>
        <div style={{ fontFamily: 'var(--font-mono)', fontSize: 12, color: 'var(--fg-muted)', marginTop: 4 }}>{listing?.coords || 'Cypress (124, 88, 47)'}</div>
        <div style={{ marginTop: 6, fontSize: 12, color: 'var(--fg-muted)' }}>Cancelled <strong style={{ color: 'var(--fg)' }}>3 weeks ago</strong> · reason: <em>buyer changed mind</em> · penalty L$500</div>
      </div>
      <div style={{ display: 'flex', flexDirection: 'column', gap: 10, marginBottom: 14 }}>
        <label style={{ display: 'flex', gap: 10, alignItems: 'flex-start', cursor: 'pointer' }}>
          <input type="checkbox" checked={refund} onChange={(e) => setRefund(e.target.checked)} style={{ marginTop: 3 }} />
          <div>
            <div style={{ fontSize: 13.5, fontWeight: 500 }}>Refund cancellation penalty (L$500)</div>
            <div style={{ fontSize: 12, color: 'var(--fg-muted)' }}>Returns the penalty to the seller&apos;s wallet. Logged in audit trail.</div>
          </div>
        </label>
        <label style={{ display: 'flex', gap: 10, alignItems: 'flex-start', cursor: 'pointer' }}>
          <input type="checkbox" checked={notify} onChange={(e) => setNotify(e.target.checked)} style={{ marginTop: 3 }} />
          <div>
            <div style={{ fontSize: 13.5, fontWeight: 500 }}>Email seller about reinstatement</div>
            <div style={{ fontSize: 12, color: 'var(--fg-muted)' }}>Includes the reason note below if provided.</div>
          </div>
        </label>
      </div>
      <label className="field-label">Internal note (required)</label>
      <textarea className="textarea" rows={3} value={note} onChange={(e) => setNote(e.target.value)}
        placeholder="e.g. Cancellation was a system error during failed handover; seller acted in good faith." />
      <div className="field-help">Visible to other admins in the audit log.</div>
    </Modal>
  );
}

// ============================================================
// 6. MobileNavDrawer — site-wide hamburger drawer
// ============================================================
function MobileNavDrawer({ open, onClose, setPage, page }) {
  const D = window.SLP_DATA;
  if (!open) return null;

  const go = (p) => { setPage(p); onClose(); };
  const navItems = [
    ['browse', 'Browse', <Icons.Search size={16} />],
    ['create', 'Sell parcel', <Icons.Plus size={16} />],
    ['dashboard', 'Dashboard', <Icons.Home size={16} />],
    ['saved', 'Saved', <Icons.Heart size={16} />],
    ['notifications', 'Notifications', <Icons.Bell size={16} />, 3],
    ['wallet', 'Wallet', <Icons.Wallet size={16} />],
    ['settings-notifications', 'Settings', <Icons.User size={16} />],
  ];

  return (
    <div onClick={onClose} style={{
      position: 'fixed', inset: 0, background: 'rgba(15,18,23,.55)', zIndex: 999,
      animation: 'fadeIn .15s ease-out',
    }}>
      <div onClick={(e) => e.stopPropagation()} style={{
        position: 'absolute', top: 0, right: 0, height: '100%', width: 'min(86vw, 320px)',
        background: 'var(--surface)', boxShadow: '-12px 0 40px rgba(0,0,0,.2)',
        display: 'flex', flexDirection: 'column',
        animation: 'slideInRight .25s ease-out',
      }}>
        <div style={{ padding: '14px 18px', borderBottom: '1px solid var(--border)', display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
            <Avatar name="Aria Northcrest" size={32} />
            <div>
              <div style={{ fontSize: 13, fontWeight: 600, lineHeight: 1.2 }}>Aria Northcrest</div>
              <div style={{ fontSize: 11, color: 'var(--fg-subtle)' }}>aria@northcrest.io</div>
            </div>
          </div>
          <button onClick={onClose} style={{ border: 'none', background: 'transparent', cursor: 'pointer', color: 'var(--fg-muted)', padding: 4 }}>
            <Icons.X size={18} />
          </button>
        </div>
        <button onClick={() => go('wallet')} style={{
          margin: 14, padding: '12px 14px', display: 'flex', alignItems: 'center', justifyContent: 'space-between',
          background: 'var(--brand-soft)', border: '1px solid var(--brand-border)', borderRadius: 'var(--r-md)',
          cursor: 'pointer', textAlign: 'left',
        }}>
          <div>
            <div style={{ fontSize: 11, color: 'var(--fg-subtle)', textTransform: 'uppercase', letterSpacing: '0.05em', fontWeight: 600 }}>Wallet</div>
            <div style={{ fontSize: 18, fontWeight: 700, marginTop: 2 }}>L$ {D.WALLET_AVAILABLE.toLocaleString()}</div>
          </div>
          <Icons.ArrowRight size={16} style={{ color: 'var(--brand)' }} />
        </button>
        <div style={{ padding: '4px 6px', flex: 1, overflowY: 'auto' }}>
          {navItems.map(([k, l, icon, count]) => (
            <button key={k} onClick={() => go(k)} style={{
              display: 'flex', alignItems: 'center', gap: 12, width: '100%',
              padding: '12px 14px', borderRadius: 'var(--r-sm)', border: 'none',
              background: page === k ? 'var(--brand-soft)' : 'transparent',
              color: page === k ? 'var(--brand)' : 'var(--fg)',
              fontSize: 14.5, fontWeight: 500, cursor: 'pointer', textAlign: 'left',
            }}>
              <span style={{ color: page === k ? 'var(--brand)' : 'var(--fg-muted)' }}>{icon}</span>
              <span style={{ flex: 1 }}>{l}</span>
              {count > 0 && <span style={{ background: 'var(--brand)', color: 'white', fontSize: 11, fontWeight: 600, padding: '2px 7px', borderRadius: 999 }}>{count}</span>}
            </button>
          ))}
        </div>
        <div style={{ padding: 14, borderTop: '1px solid var(--border)', display: 'flex', flexDirection: 'column', gap: 4 }}>
          {[['about', 'About'], ['contact', 'Help & contact'], ['terms', 'Terms']].map(([k, l]) => (
            <button key={k} onClick={() => go(k)} style={{
              padding: '8px 14px', border: 'none', background: 'transparent',
              fontSize: 13, color: 'var(--fg-muted)', cursor: 'pointer', textAlign: 'left',
            }}>{l}</button>
          ))}
          <button onClick={() => go('login')} style={{
            marginTop: 6, padding: '10px 14px', border: '1px solid var(--border)', background: 'transparent',
            fontSize: 13, color: 'var(--fg-muted)', cursor: 'pointer', borderRadius: 'var(--r-sm)', textAlign: 'left',
          }}>Sign out</button>
        </div>
      </div>
    </div>
  );
}

// ============================================================
// 7. AdminMobileDrawer — admin sidebar collapsed
// ============================================================
function AdminMobileDrawer({ open, onClose, setPage, page }) {
  const A = window.ADMIN_DATA;
  if (!open) return null;
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
  const go = (k) => { setPage(k); onClose(); };
  return (
    <div onClick={onClose} style={{
      position: 'fixed', inset: 0, background: 'rgba(15,18,23,.55)', zIndex: 999,
      animation: 'fadeIn .15s ease-out',
    }}>
      <div onClick={(e) => e.stopPropagation()} style={{
        position: 'absolute', top: 0, left: 0, height: '100%', width: 'min(80vw, 280px)',
        background: 'var(--surface)', boxShadow: '12px 0 40px rgba(0,0,0,.2)',
        display: 'flex', flexDirection: 'column', padding: 14,
        animation: 'slideInLeft .25s ease-out',
      }}>
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 14 }}>
          <div style={{ fontSize: 11, fontWeight: 700, letterSpacing: '0.08em', textTransform: 'uppercase', color: 'var(--fg-subtle)' }}>Admin console</div>
          <button onClick={onClose} style={{ border: 'none', background: 'transparent', cursor: 'pointer', color: 'var(--fg-muted)', padding: 4 }}>
            <Icons.X size={16} />
          </button>
        </div>
        {items.map(it => (
          <button key={it.k} onClick={() => go(it.k)} style={{
            display: 'flex', alignItems: 'center', gap: 10, width: '100%',
            padding: '10px 12px', borderRadius: 'var(--r-sm)', border: 'none',
            background: page === it.k ? 'var(--brand-soft)' : 'transparent',
            color: page === it.k ? 'var(--brand)' : 'var(--fg)',
            fontSize: 14, fontWeight: 500, cursor: 'pointer', textAlign: 'left',
          }}>
            <span style={{ color: page === it.k ? 'var(--brand)' : 'var(--fg-muted)' }}>{it.icon}</span>
            <span style={{ flex: 1 }}>{it.l}</span>
            {it.count > 0 && (
              <span style={{ background: 'var(--bg-muted)', color: 'var(--fg-muted)', fontSize: 11, fontWeight: 600, padding: '2px 7px', borderRadius: 999 }}>{it.count}</span>
            )}
          </button>
        ))}
        <div style={{ flex: 1 }} />
        <button onClick={() => go('home')} style={{
          padding: '10px 12px', border: '1px solid var(--border)', background: 'transparent',
          fontSize: 13, color: 'var(--fg-muted)', cursor: 'pointer', borderRadius: 'var(--r-sm)',
          display: 'flex', alignItems: 'center', gap: 6,
        }}><Icons.ChevronLeft size={12} /> Back to site</button>
      </div>
    </div>
  );
}

window.SuspensionErrorModal = SuspensionErrorModal;
window.ReportListingModal = ReportListingModal;
window.ConfirmBidDialog = ConfirmBidDialog;
window.FlagReviewModal = FlagReviewModal;
window.ReinstateListingModal = ReinstateListingModal;
window.MobileNavDrawer = MobileNavDrawer;
window.AdminMobileDrawer = AdminMobileDrawer;
