// shell.jsx — Header, footer, mobile nav

function Header({ tweaks, setTweak, page, setPage, openSearch, headerStyle }) {
  const [openProfile, setOpenProfile] = React.useState(false);
  const [openNotif, setOpenNotif] = React.useState(false);
  const [openMobileNav, setOpenMobileNav] = React.useState(false);
  const D = window.SLP_DATA;

  const cls = ['hdr'];
  if (headerStyle === 'minimal') cls.push('hdr--minimal');
  if (headerStyle === 'bold') cls.push('hdr--bold');

  return (
    <header className={cls.join(' ')}>
      <div className="container hdr-inner">
        <div className="hdr-logo" onClick={() => setPage('home')}>
          <div className="hdr-logo-mark">SL</div>
          <span className="hdr-logo-text">Parcels</span>
        </div>
        <nav className="hdr-nav hide-mobile">
          <a className={`hdr-link ${page === 'browse' ? 'hdr-link--active' : ''}`} onClick={() => setPage('browse')}>Browse</a>
          <a className={`hdr-link ${page === 'create' ? 'hdr-link--active' : ''}`} onClick={() => setPage('create')}>Sell parcel</a>
          <a className={`hdr-link ${page === 'dashboard' ? 'hdr-link--active' : ''}`} onClick={() => setPage('dashboard')}>Dashboard</a>
          <a className="hdr-link" onClick={() => setPage('home')}>Help</a>
        </nav>
        <div className="hdr-actions">
          <button className="hdr-icon-btn hide-mobile" onClick={openSearch} aria-label="Search">
            <Icons.Search size={18} />
          </button>
          <button className="hdr-icon-btn hide-mobile" onClick={() => setTweak && setTweak('dark', !tweaks.dark)} aria-label={tweaks.dark ? 'Switch to light mode' : 'Switch to dark mode'} title={tweaks.dark ? 'Light mode' : 'Dark mode'}>
            {tweaks.dark ? <Icons.Sun size={18} /> : <Icons.Moon size={18} />}
          </button>
          <div style={{ position: 'relative' }} className="hide-mobile">
            <button className="hdr-icon-btn" onClick={() => {setOpenNotif(!openNotif);setOpenProfile(false);}} aria-label="Notifications">
              <Icons.Bell size={18} />
              <span className="hdr-badge" style={{ width: "16px", fontSize: "10px", borderWidth: "0px", height: "16px", padding: "0px", margin: "0px" }}>3</span>
            </button>
            {openNotif && <NotifPopover onClose={() => setOpenNotif(false)} />}
          </div>
          <button className="wallet-pill hide-mobile" onClick={() => setPage('wallet')}>
            <span className="wallet-pill-icon" style={{ width: "24px", height: "24px", fontSize: "13px" }}>L$</span>
            <span className="wallet-amount">{D.WALLET_AVAILABLE.toLocaleString()}</span>
          </button>
          <div style={{ position: 'relative' }}>
            <button className="hdr-icon-btn" onClick={() => {setOpenProfile(!openProfile);setOpenNotif(false);}} aria-label="Profile" style={{ padding: 0 }}>
              <Avatar name="Aria Northcrest" size={28} />
            </button>
            {openProfile && <ProfilePopover onClose={() => setOpenProfile(false)} setPage={setPage} />}
          </div>
          <button className="hdr-icon-btn show-mobile" onClick={() => setOpenMobileNav(true)} aria-label="Menu">
            <Icons.Menu size={20} />
          </button>
        </div>
      </div>
      <MobileNavDrawer open={openMobileNav} onClose={() => setOpenMobileNav(false)} setPage={setPage} page={page} />
    </header>);

}

function NotifPopover({ onClose }) {
  const D = window.SLP_DATA;
  React.useEffect(() => {
    const h = () => onClose();
    setTimeout(() => window.addEventListener('click', h), 0);
    return () => window.removeEventListener('click', h);
  }, []);
  return (
    <div onClick={(e) => e.stopPropagation()} style={{
      position: 'absolute', right: 0, top: 'calc(100% + 8px)',
      width: 360, maxHeight: 480, overflow: 'auto',
      background: 'var(--surface)', border: '1px solid var(--border)',
      borderRadius: 'var(--r-lg)', boxShadow: 'var(--shadow-lg)',
      zIndex: 200
    }}>
      <div style={{ padding: '14px 16px', borderBottom: '1px solid var(--border)', display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
        <div style={{ fontWeight: 600, fontSize: 13.5 }}>Notifications</div>
        <a className="muted" style={{ fontSize: 12, cursor: 'pointer' }}>Mark all read</a>
      </div>
      {D.NOTIFICATIONS.map((n) =>
      <div key={n.id} style={{
        padding: '12px 16px', borderBottom: '1px solid var(--border-subtle)',
        display: 'flex', gap: 12, cursor: 'pointer',
        background: n.unread ? 'var(--brand-soft)' : 'transparent'
      }}>
          <div style={{
          width: 28, height: 28, flexShrink: 0,
          borderRadius: 8, background: 'var(--bg-muted)',
          display: 'grid', placeItems: 'center',
          color: n.type === 'won' ? 'var(--success)' : n.type === 'outbid' ? 'var(--danger)' : 'var(--brand)'
        }}>
            {n.type === 'outbid' && <Icons.AlertCircle size={14} />}
            {n.type === 'ending' && <Icons.Clock size={14} />}
            {n.type === 'won' && <Icons.CheckCircle size={14} />}
            {n.type === 'review' && <Icons.Star size={14} filled />}
            {n.type === 'system' && <Icons.Wallet size={14} />}
          </div>
          <div style={{ flex: 1, minWidth: 0 }}>
            <div style={{ fontSize: 13, lineHeight: 1.4, color: 'var(--fg)' }}>{n.text}</div>
            <div style={{ fontSize: 11.5, color: 'var(--fg-subtle)', marginTop: 2 }}>{n.time}</div>
          </div>
          {n.unread && <div style={{ width: 6, height: 6, borderRadius: '50%', background: 'var(--brand)', marginTop: 6, flexShrink: 0 }} />}
        </div>
      )}
    </div>);

}

function ProfilePopover({ onClose, setPage }) {
  React.useEffect(() => {
    const h = () => onClose();
    setTimeout(() => window.addEventListener('click', h), 0);
    return () => window.removeEventListener('click', h);
  }, []);
  const items = [
  { label: 'Dashboard', icon: <Icons.Home size={14} />, page: 'dashboard' },
  { label: 'My bids', icon: <Icons.Gavel size={14} />, page: 'dashboard' },
  { label: 'My listings', icon: <Icons.Tag size={14} />, page: 'dashboard' },
  { label: 'Wallet', icon: <Icons.Wallet size={14} />, page: 'wallet' },
  { label: 'Verify avatar', icon: <Icons.Shield size={14} />, page: 'verify' },
  { label: 'Settings', icon: <Icons.User size={14} /> },
  { label: 'Sign out', icon: <Icons.ArrowUpRight size={14} /> }];

  return (
    <div onClick={(e) => e.stopPropagation()} style={{
      position: 'absolute', right: 0, top: 'calc(100% + 8px)',
      width: 240,
      background: 'var(--surface)', border: '1px solid var(--border)',
      borderRadius: 'var(--r-lg)', boxShadow: 'var(--shadow-lg)',
      zIndex: 200, overflow: 'hidden'
    }}>
      <div style={{ padding: 14, borderBottom: '1px solid var(--border)', display: 'flex', gap: 10, alignItems: 'center' }}>
        <Avatar name="Aria Northcrest" size={36} />
        <div style={{ minWidth: 0 }}>
          <div style={{ fontSize: 13.5, fontWeight: 600 }}>Aria Northcrest</div>
          <div style={{ fontSize: 11.5, color: 'var(--fg-subtle)', fontFamily: 'var(--font-mono)' }}>aria.northcrest</div>
        </div>
      </div>
      <div style={{ padding: 6 }}>
        {items.map((it, i) =>
        <a key={i} onClick={() => {it.page && setPage(it.page);onClose();}} style={{
          display: 'flex', alignItems: 'center', gap: 10,
          padding: '8px 10px', borderRadius: 6, cursor: 'pointer',
          fontSize: 13, color: 'var(--fg)'
        }}
        onMouseEnter={(e) => e.currentTarget.style.background = 'var(--bg-hover)'}
        onMouseLeave={(e) => e.currentTarget.style.background = 'transparent'}>
            <span style={{ color: 'var(--fg-muted)' }}>{it.icon}</span>{it.label}
          </a>
        )}
      </div>
    </div>);

}

function Footer() {
  return (
    <footer className="ftr">
      <div className="container ftr-inner">
        <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
          <div className="hdr-logo-mark" style={{ width: 22, height: 22, fontSize: 10, borderRadius: 5 }}>SL</div>
          <span className="ftr-copyright">© 2026 SLParcels · Independent marketplace, not affiliated with Linden Lab.</span>
        </div>
        <div className="ftr-links">
          <a className="ftr-link">Terms</a>
          <a className="ftr-link">Privacy</a>
          <a className="ftr-link">Fees</a>
          <a className="ftr-link">Trust & safety</a>
        </div>
      </div>
    </footer>);

}

window.Header = Header;
window.Footer = Footer;