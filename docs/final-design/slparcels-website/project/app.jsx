// app.jsx — root, routing, theme + tweaks

function applyTweaks(t) {
  const root = document.documentElement;
  root.dataset.theme = t.dark ? 'dark' : 'light';
  // Brand hue
  const h = t.primaryHue;
  const brand = `hsl(${h}, 78%, 50%)`;
  const brandHover = `hsl(${h}, 78%, 44%)`;
  const brandSoft = t.dark ? `hsla(${h}, 78%, 55%, 0.14)` : `hsl(${h}, 100%, 96%)`;
  const brandBorder = t.dark ? `hsla(${h}, 78%, 55%, 0.32)` : `hsl(${h}, 75%, 86%)`;
  root.style.setProperty('--brand', brand);
  root.style.setProperty('--brand-hover', brandHover);
  root.style.setProperty('--brand-soft', brandSoft);
  root.style.setProperty('--brand-border', brandBorder);
  // Radii
  const r = t.radius;
  root.style.setProperty('--r-sm', Math.max(2, r * 0.6) + 'px');
  root.style.setProperty('--r-md', r + 'px');
  root.style.setProperty('--r-lg', (r + 4) + 'px');
  root.style.setProperty('--r-xl', (r + 8) + 'px');
  // Density (1-10) → padding scale
  const d = t.density;
  const headerH = 50 + (10 - d) * 1.2;
  root.style.setProperty('--header-h', headerH + 'px');
}

function App() {
  const [tweaks, setTweak] = useTweaks(window.TWEAK_DEFAULTS);
  const [page, setPage] = React.useState('home');
  const [auctionId, setAuctionId] = React.useState('a0');
  const [dashboardTab, setDashboardTab] = React.useState('overview');
  const [adminUserId, setAdminUserId] = React.useState(null);
  const [adminDisputeId, setAdminDisputeId] = React.useState(null);
  const [demoModal, setDemoModal] = React.useState(null);

  React.useEffect(() => { applyTweaks(tweaks); }, [tweaks]);
  React.useEffect(() => { window.scrollTo(0, 0); }, [page, auctionId, dashboardTab, adminUserId, adminDisputeId]);

  const isAdminPage = page.startsWith('admin-');
  const pageProps = { setPage, setAuctionId, cardLayout: tweaks.cardLayout, dashboardTab, setDashboardTab, adminUserId, setAdminUserId, adminDisputeId, setAdminDisputeId };

  return (
    <div className="app">
      {!isAdminPage && <Header
        page={page}
        setPage={setPage}
        openSearch={() => setPage('browse')}
        headerStyle={tweaks.headerStyle}
        tweaks={tweaks}
        setTweak={setTweak}
      />}
      {isAdminPage && (
        <div style={{ height: 'var(--header-h)', display: 'flex', alignItems: 'center', padding: '0 24px', background: 'var(--surface)', borderBottom: '1px solid var(--border)', position: 'sticky', top: 0, zIndex: 50 }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 10, fontSize: 15, fontWeight: 700, letterSpacing: '-0.01em' }}>
            <span style={{ color: 'var(--brand)' }}>SLParcels</span>
            <span style={{ color: 'var(--fg-subtle)', fontWeight: 400 }}>/</span>
            <span>Admin</span>
          </div>
          <div style={{ flex: 1 }} />
          <button onClick={() => setTweak('dark', !tweaks.dark)} aria-label={tweaks.dark ? 'Switch to light mode' : 'Switch to dark mode'} title={tweaks.dark ? 'Light mode' : 'Dark mode'} style={{
            border: 'none', background: 'transparent', cursor: 'pointer', color: 'var(--fg-muted)',
            padding: 6, borderRadius: 'var(--r-sm)', display: 'inline-flex', alignItems: 'center', justifyContent: 'center', marginRight: 12,
          }}>
            {tweaks.dark ? <Icons.Sun size={16} /> : <Icons.Moon size={16} />}
          </button>
          <div style={{ fontSize: 12, color: 'var(--fg-muted)', display: 'flex', alignItems: 'center', gap: 6 }}>
            <span style={{ width: 6, height: 6, borderRadius: 3, background: 'var(--success, #16a34a)' }} />
            All systems normal
          </div>
        </div>
      )}
      {page === 'home' && <HomePage {...pageProps} />}
      {page === 'browse' && <BrowsePage {...pageProps} />}
      {page === 'auction' && <AuctionPage auctionId={auctionId} {...pageProps} />}
      {page === 'escrow' && <EscrowPage {...pageProps} />}
      {page === 'wallet' && <WalletPage {...pageProps} />}
      {page === 'dashboard' && <DashboardPage {...pageProps} />}
      {page === 'verify' && <VerifyPage {...pageProps} />}
      {page === 'create' && <CreatePage {...pageProps} />}
      {page === 'login' && <LoginPage {...pageProps} />}
      {page === 'register' && <RegisterPage {...pageProps} />}
      {page === 'forgot' && <ForgotPasswordPage {...pageProps} />}
      {page === 'goodbye' && <GoodbyePage {...pageProps} />}
      {page === 'about' && <AboutPage {...pageProps} />}
      {page === 'contact' && <ContactPage {...pageProps} />}
      {page === 'partners' && <PartnersPage {...pageProps} />}
      {page === 'terms' && <TermsPage {...pageProps} />}
      {page === 'notifications' && <NotificationsPage {...pageProps} />}
      {page === 'saved' && <SavedPage {...pageProps} />}
      {page === 'settings-notifications' && <SettingsNotificationsPage {...pageProps} />}
      {page === 'user-profile' && <UserProfilePage {...pageProps} />}
      {page === 'user-listings' && <UserListingsPage {...pageProps} />}
      {page === 'listing-edit' && <ListingEditPage {...pageProps} />}
      {page === 'listing-activate' && <ListingActivatePage {...pageProps} />}
      {page === 'escrow-dispute' && <EscrowDisputePage {...pageProps} />}

      {page === 'admin-home' && <AdminHomePage {...pageProps} />}
      {page === 'admin-users' && <AdminUsersPage {...pageProps} />}
      {page === 'admin-user-detail' && <AdminUserDetailPage {...pageProps} />}
      {page === 'admin-disputes' && <AdminDisputesPage {...pageProps} />}
      {page === 'admin-dispute-detail' && <AdminDisputeDetailPage {...pageProps} />}
      {page === 'admin-reports' && <AdminReportsPage {...pageProps} />}
      {page === 'admin-fraud-flags' && <AdminFraudFlagsPage {...pageProps} />}
      {page === 'admin-bans' && <AdminBansPage {...pageProps} />}
      {page === 'admin-audit-log' && <AdminAuditLogPage {...pageProps} />}
      {page === 'admin-infrastructure' && <AdminInfrastructurePage {...pageProps} />}

      {!isAdminPage && <Footer />}

      <SuspensionErrorModal open={demoModal === 'suspension'} onClose={() => setDemoModal(null)} setPage={setPage} />
      <ReportListingModal open={demoModal === 'report'} onClose={() => setDemoModal(null)} parcel={window.SLP_DATA && window.SLP_DATA.AUCTIONS[0]} />
      <ConfirmBidDialog open={demoModal === 'confirmbid'} onClose={() => setDemoModal(null)} onConfirm={() => setDemoModal(null)}
        amount={5000} currentBid={2200} walletAvailable={(window.SLP_DATA && window.SLP_DATA.WALLET_AVAILABLE) || 12000} />
      <FlagReviewModal open={demoModal === 'flagreview'} onClose={() => setDemoModal(null)}
        review={{ author: 'Otto Whitmer', stars: 4, text: 'Good parcel and fair price. Handover took a day longer than expected but no complaints overall.' }} />
      <ReinstateListingModal open={demoModal === 'reinstate'} onClose={() => setDemoModal(null)}
        listing={{ title: 'Hilltop with lighthouse rights', coords: 'Cypress (124, 88, 47)' }} />

      <TweaksPanel>
        <TweakSection label="Appearance" />
        <TweakToggle label="Dark mode" value={tweaks.dark} onChange={(v) => setTweak('dark', v)} />
        <TweakSlider label="Accent hue" value={tweaks.primaryHue} min={0} max={360} unit="°" onChange={(v) => setTweak('primaryHue', v)} />
        <TweakSlider label="Card radius" value={tweaks.radius} min={0} max={20} unit="px" onChange={(v) => setTweak('radius', v)} />
        <TweakSlider label="UI density" value={tweaks.density} min={1} max={10} onChange={(v) => setTweak('density', v)} />

        <TweakSection label="Layout" />
        <TweakRadio label="Auction card" value={tweaks.cardLayout}
          options={['standard', 'detailed', 'compact']}
          onChange={(v) => setTweak('cardLayout', v)} />
        <TweakRadio label="Header style" value={tweaks.headerStyle}
          options={['default', 'minimal', 'bold']}
          onChange={(v) => setTweak('headerStyle', v)} />

        <TweakSection label="Content" />
        <TweakToggle label="Trust badges" value={tweaks.showTrustBadges} onChange={(v) => setTweak('showTrustBadges', v)} />

        <TweakSection label="Demo modals" />
        <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 4 }}>
          {[['suspension','Suspension error'],['report','Report listing'],['confirmbid','Confirm large bid'],['flagreview','Flag review'],['reinstate','Reinstate listing']].map(([k, l]) => (
            <button key={k} onClick={() => setDemoModal(k)} style={{
              padding: '6px 8px', borderRadius: 6, fontSize: 11.5,
              border: '1px solid rgba(0,0,0,.1)', background: 'rgba(255,255,255,.6)',
              cursor: 'pointer', textAlign: 'left',
            }}>{l}</button>
          ))}
        </div>

        <TweakSection label="Jump to page" />
        <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 4 }}>
          {[['home','Home'],['browse','Browse'],['auction','Auction'],['escrow','Escrow'],['escrow-dispute','Dispute'],['wallet','Wallet'],['dashboard','Dashboard'],['verify','Verify'],['create','List'],['listing-edit','Edit listing'],['listing-activate','Activate'],['notifications','Notifications'],['saved','Saved'],['settings-notifications','Settings'],['user-profile','User profile'],['user-listings','User listings'],['login','Sign in'],['register','Register'],['forgot','Forgot pw'],['goodbye','Goodbye'],['about','About'],['contact','Contact'],['partners','Partners'],['terms','Terms'],['admin-home','⚙ Admin home'],['admin-users','⚙ Users'],['admin-user-detail','⚙ User detail'],['admin-disputes','⚙ Disputes'],['admin-dispute-detail','⚙ Dispute detail'],['admin-reports','⚙ Reports'],['admin-fraud-flags','⚙ Fraud flags'],['admin-bans','⚙ Bans'],['admin-audit-log','⚙ Audit log'],['admin-infrastructure','⚙ Infrastructure']].map(([k,l]) => (
            <button key={k} onClick={() => setPage(k)}
              style={{
                padding: '6px 8px', borderRadius: 6, fontSize: 11.5,
                border: '1px solid ' + (page === k ? 'var(--brand)' : 'rgba(0,0,0,.1)'),
                background: page === k ? 'var(--brand)' : 'rgba(255,255,255,.6)',
                color: page === k ? 'white' : 'inherit',
                cursor: 'pointer', textAlign: 'left',
              }}>{l}</button>
          ))}
        </div>
      </TweaksPanel>
    </div>
  );
}

ReactDOM.createRoot(document.getElementById('root')).render(<App />);
