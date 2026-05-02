// page-home.jsx
function HomePage({ setPage, setAuctionId }) {
  const D = window.SLP_DATA;
  const featured = D.AUCTIONS.filter(a => a.isFeatured).slice(0, 3);
  const ending = [...D.AUCTIONS].sort((a,b) => a.minutesLeft - b.minutesLeft).slice(0, 4);
  const trending = D.AUCTIONS.slice(8, 12);

  const open = (id) => { setAuctionId(id); setPage('auction'); };

  return (
    <div className="page">
      {/* Hero */}
      <section style={{ background: 'var(--bg-subtle)', borderBottom: '1px solid var(--border)' }}>
        <div className="container" style={{ padding: '56px 24px 64px' }}>
          <div style={{ display: 'grid', gridTemplateColumns: '1.1fr 1fr', gap: 56, alignItems: 'center' }}>
            <div>
              <div className="eyebrow" style={{ marginBottom: 16 }}>The marketplace for virtual land</div>
              <h1 style={{ fontSize: 48, fontWeight: 800, lineHeight: 1.05, letterSpacing: '-0.03em', margin: 0 }}>
                Auction parcels with real escrow protection.
              </h1>
              <p style={{ fontSize: 17, color: 'var(--fg-muted)', maxWidth: 540, marginTop: 18, lineHeight: 1.5 }}>
                Find premium Second Life parcels at fair prices. Every transaction is protected by SLParcels Escrow — no more lost L$ to bad-faith sellers.
              </p>
              <div style={{ display: 'flex', gap: 10, marginTop: 28 }}>
                <Btn variant="primary" size="lg" onClick={() => setPage('browse')}>Browse auctions <Icons.ArrowRight size={16} /></Btn>
                <Btn variant="secondary" size="lg" onClick={() => setPage('create')}>List your parcel</Btn>
              </div>
              <div style={{ display: 'flex', gap: 28, marginTop: 36 }}>
                {[['1,284', 'Active auctions'], ['L$ 12.4M', 'Settled this month'], ['4.92', 'Trust rating']].map(([v, l]) => (
                  <div key={l}>
                    <div style={{ fontSize: 22, fontWeight: 700, letterSpacing: '-0.02em' }}>{v}</div>
                    <div style={{ fontSize: 12, color: 'var(--fg-subtle)', marginTop: 2 }}>{l}</div>
                  </div>
                ))}
              </div>
            </div>
            {/* Hero card stack */}
            <div style={{ position: 'relative', height: 420 }}>
              {featured.slice(0, 3).map((p, i) => (
                <div key={p.id} onClick={() => open(p.id)} style={{
                  position: 'absolute',
                  top: i * 14, left: i * 24, right: i === 0 ? 60 : i === 1 ? 30 : 0,
                  transform: `rotate(${(i - 1) * 1.5}deg)`,
                  transition: 'transform .2s',
                  zIndex: 3 - i,
                }}>
                  <div className="card card--raised" style={{ overflow: 'hidden' }}>
                    <ParcelImage parcel={p} ratio="16/10" showSave={i === 0} />
                    <div style={{ padding: 14, display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                      <div>
                        <div style={{ fontSize: 13, fontWeight: 600, marginBottom: 2 }}>{p.title}</div>
                        <div style={{ fontFamily: 'var(--font-mono)', fontSize: 10.5, color: 'var(--fg-subtle)' }}>{p.coords}</div>
                      </div>
                      <div style={{ textAlign: 'right' }}>
                        <div style={{ fontSize: 11, color: 'var(--fg-subtle)' }}>Bid</div>
                        <div style={{ fontSize: 14 }}><L amount={p.currentBid} /></div>
                      </div>
                    </div>
                  </div>
                </div>
              ))}
            </div>
          </div>
        </div>
      </section>

      {/* Search bar */}
      <section className="container" style={{ padding: '40px 24px 0' }}>
        <div className="card" style={{ padding: 12, display: 'flex', gap: 8, alignItems: 'center' }}>
          <div className="search-input" style={{ flex: 1 }}>
            <Icons.Search size={16} />
            <input className="input" placeholder="Search by region, tier, or keyword (e.g. beachfront Bay City)…" />
          </div>
          <select className="select" style={{ width: 160 }}>
            <option>Any tier</option>
            {D.TIERS.map(t => <option key={t}>{t}</option>)}
          </select>
          <select className="select" style={{ width: 180 }}>
            <option>Any size</option>
            <option>Under 1024 m²</option>
            <option>1024 – 4096 m²</option>
            <option>Over 4096 m²</option>
          </select>
          <Btn variant="primary" onClick={() => setPage('browse')}>Search</Btn>
        </div>
      </section>

      {/* Ending soon */}
      <section className="container" style={{ padding: '56px 24px 0' }}>
        <div className="section-hd">
          <div>
            <h2>Ending soon</h2>
            <div className="section-hd-sub">Auctions closing in the next few hours.</div>
          </div>
          <a className="btn btn--ghost" onClick={() => setPage('browse')}>View all <Icons.ArrowRight size={14} /></a>
        </div>
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(4, 1fr)', gap: 18 }}>
          {ending.map(p => <AuctionCard key={p.id} parcel={p} onClick={() => open(p.id)} />)}
        </div>
      </section>

      {/* Featured */}
      <section className="container" style={{ padding: '56px 24px 0' }}>
        <div className="section-hd">
          <div>
            <h2>Featured this week</h2>
            <div className="section-hd-sub">Hand-picked premium parcels with verified covenants.</div>
          </div>
        </div>
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3, 1fr)', gap: 18 }}>
          {featured.map(p => <AuctionCard key={p.id} parcel={p} layout="detailed" onClick={() => open(p.id)} />)}
        </div>
      </section>

      {/* Trust strip */}
      <section className="container" style={{ padding: '56px 24px 0' }}>
        <div className="card" style={{ padding: 32, background: 'var(--bg-subtle)', borderRadius: 'var(--r-xl)' }}>
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(4, 1fr)', gap: 32 }}>
            {[
              { i: <Icons.ShieldCheck size={22} />, t: 'Escrow on every sale', d: 'L$ is locked until both parties confirm the parcel transfer. Disputes get human review within 24 hours.' },
              { i: <Icons.Lock size={22} />, t: 'Verified sellers', d: 'Account age, sales history, and identity check filters keep bots and scammers out of the marketplace.' },
              { i: <Icons.Gavel size={22} />, t: 'Fair bidding', d: 'Anti-snipe extensions, proxy bidding, and transparent bid histories — no last-second rug pulls.' },
              { i: <Icons.RefreshCw size={22} />, t: 'Money-back guarantee', d: 'If a parcel does not match its listing, we refund your full bid and any associated fees, no questions.' },
            ].map((x, i) => (
              <div key={i}>
                <div style={{ width: 40, height: 40, borderRadius: 10, background: 'var(--brand-soft)', color: 'var(--brand)', display: 'grid', placeItems: 'center', marginBottom: 14 }}>{x.i}</div>
                <div style={{ fontWeight: 600, fontSize: 14, marginBottom: 6 }}>{x.t}</div>
                <div style={{ fontSize: 13, color: 'var(--fg-muted)', lineHeight: 1.5 }}>{x.d}</div>
              </div>
            ))}
          </div>
        </div>
      </section>

      {/* Trending */}
      <section className="container" style={{ padding: '56px 24px 80px' }}>
        <div className="section-hd">
          <div>
            <h2>Trending across regions</h2>
            <div className="section-hd-sub">Most-watched parcels right now.</div>
          </div>
          <a className="btn btn--ghost" onClick={() => setPage('browse')}>View all <Icons.ArrowRight size={14} /></a>
        </div>
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(4, 1fr)', gap: 18 }}>
          {trending.map(p => <AuctionCard key={p.id} parcel={p} onClick={() => open(p.id)} />)}
        </div>
      </section>
    </div>
  );
}

window.HomePage = HomePage;
