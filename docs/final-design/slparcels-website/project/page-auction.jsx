// page-auction.jsx — the money page
function AuctionPage({ auctionId, setPage, setAuctionId }) {
  const D = window.SLP_DATA;
  const parcel = D.AUCTIONS.find(a => a.id === auctionId) || D.AUCTIONS[0];
  const [activeImg, setActiveImg] = React.useState(0);
  const [bidAmount, setBidAmount] = React.useState(parcel.currentBid + 100);
  const [showBidModal, setShowBidModal] = React.useState(false);
  const [showConfirm, setShowConfirm] = React.useState(false);
  const [showReport, setShowReport] = React.useState(false);
  const [tab, setTab] = React.useState('details');
  const minBid = parcel.currentBid + 100;
  const ending = parcel.minutesLeft < 60;

  // Over-cap warning: if user bids > 50% over current top
  const placeBid = () => {
    if (bidAmount > parcel.currentBid * 1.5) setShowConfirm(true);
    else setShowBidModal(true);
  };

  return (
    <div className="page container" style={{ padding: '20px 24px 80px' }}>
      {/* Breadcrumb */}
      <div style={{ fontSize: 12, color: 'var(--fg-subtle)', marginBottom: 12 }}>
        <a className="muted" onClick={() => setPage('home')} style={{ cursor: 'pointer' }}>Home</a> /{' '}
        <a className="muted" onClick={() => setPage('browse')} style={{ cursor: 'pointer' }}>Browse</a> /{' '}
        <a className="muted">{parcel.region}</a> / <span style={{ color: 'var(--fg)' }}>{parcel.title}</span>
      </div>

      <div style={{ display: 'grid', gridTemplateColumns: '1fr 380px', gap: 32, alignItems: 'flex-start' }}>
        <div>
          {/* Title row */}
          <div style={{ marginBottom: 18 }}>
            <div style={{ display: 'flex', gap: 6, marginBottom: 10, flexWrap: 'wrap' }}>
              <Badge tone="neutral">{parcel.tier}</Badge>
              <Badge tone="outline">{formatSqm(parcel.sqm)}</Badge>
              <Badge tone="outline">{parcel.covenant} covenant</Badge>
              <Badge tone={parcel.reserveMet ? 'success' : 'warning'}>
                {parcel.reserveMet ? 'Reserve met' : 'Reserve not met'}
              </Badge>
              {ending && <Badge tone="danger" dot pulse>Ending soon</Badge>}
            </div>
            <h1 style={{ fontSize: 28, fontWeight: 700, letterSpacing: '-0.02em', margin: 0, lineHeight: 1.2 }}>{parcel.title}</h1>
            <div style={{ display: 'flex', gap: 14, alignItems: 'center', marginTop: 8, fontSize: 13.5, color: 'var(--fg-muted)' }}>
              <span style={{ display: 'inline-flex', gap: 5, alignItems: 'center' }}><Icons.MapPin size={13} /> {parcel.region}</span>
              <span style={{ fontFamily: 'var(--font-mono)', fontSize: 12 }}>{parcel.coords}</span>
              <span style={{ display: 'inline-flex', gap: 5, alignItems: 'center' }}><Icons.Eye size={13} /> {parcel.watchers} watching</span>
            </div>
          </div>

          {/* Gallery */}
          <div style={{ marginBottom: 24 }}>
            <div style={{ borderRadius: 'var(--r-lg)', overflow: 'hidden', border: '1px solid var(--border)' }}>
              <ParcelImage parcel={parcel} ratio="16/10" big showSave={false}>
                <div style={{ position: 'absolute', bottom: 12, right: 12, display: 'flex', gap: 6 }}>
                  <button className="btn btn--secondary btn--sm" style={{ background: 'rgba(0,0,0,.5)', color: '#fff', borderColor: 'transparent', backdropFilter: 'blur(8px)' }}>
                    <Icons.Maximize size={12} /> View in-world
                  </button>
                </div>
              </ParcelImage>
            </div>
            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(5, 1fr)', gap: 8, marginTop: 8 }}>
              {[0,1,2,3,4].map(i => (
                <button key={i} onClick={() => setActiveImg(i)} style={{
                  border: '2px solid ' + (activeImg === i ? 'var(--brand)' : 'transparent'),
                  borderRadius: 8, padding: 0, cursor: 'pointer', overflow: 'hidden', background: 'transparent',
                }}>
                  <div style={{ aspectRatio: '1/1', background: parcel.grad, position: 'relative' }}>
                    <div style={{ position: 'absolute', inset: 0, background: `hsla(${parcel.hue}, 30%, ${30 + i * 8}%, 0.4)` }} />
                  </div>
                </button>
              ))}
            </div>
          </div>

          {/* Tabs */}
          <div style={{ borderBottom: '1px solid var(--border)', display: 'flex', gap: 4, marginBottom: 24 }}>
            {[['details', 'Details'], ['bids', 'Bid history'], ['seller', 'Seller'], ['region', 'Region info']].map(([k, l]) => (
              <button key={k} onClick={() => setTab(k)} className="btn btn--ghost"
                style={{
                  borderRadius: 0, padding: '10px 14px', fontSize: 13.5, fontWeight: 500,
                  borderBottom: '2px solid ' + (tab === k ? 'var(--brand)' : 'transparent'),
                  color: tab === k ? 'var(--fg)' : 'var(--fg-muted)',
                  marginBottom: -1,
                }}>{l}</button>
            ))}
          </div>

          {tab === 'details' && <DetailsTab parcel={parcel} />}
          {tab === 'bids' && <BidsTab parcel={parcel} />}
          {tab === 'seller' && <SellerTab parcel={parcel} />}
          {tab === 'region' && <RegionTab parcel={parcel} />}
        </div>

        {/* Sticky bid panel */}
        <aside style={{ position: 'sticky', top: 'calc(var(--header-h) + 16px)' }}>
          <div className="card" style={{ overflow: 'visible' }}>
            <div style={{ padding: 18, borderBottom: '1px solid var(--border)' }}>
              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'baseline', marginBottom: 10 }}>
                <div style={{ fontSize: 11, fontWeight: 600, letterSpacing: '0.06em', textTransform: 'uppercase', color: 'var(--fg-subtle)' }}>Top bid</div>
                <div style={{ fontSize: 11.5, color: 'var(--fg-subtle)' }}>{parcel.bidCount} bids</div>
              </div>
              <div style={{ fontSize: 32, fontWeight: 700, letterSpacing: '-0.02em', display: 'flex', alignItems: 'baseline' }}>
                <L amount={parcel.currentBid} />
              </div>
              <div style={{ fontSize: 12.5, color: 'var(--fg-muted)', marginTop: 4 }}>
                ≈ US$ {(parcel.currentBid / 270).toFixed(2)} · Min next bid <span className="bold tabular">L${minBid.toLocaleString()}</span>
              </div>
            </div>

            <div style={{ padding: 18, borderBottom: '1px solid var(--border)', background: ending ? 'var(--danger-bg)' : 'var(--bg-subtle)' }}>
              <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 6 }}>
                <Icons.Clock size={14} style={{ color: ending ? 'var(--danger)' : 'var(--fg-muted)' }} />
                <span style={{ fontSize: 12, fontWeight: 600, letterSpacing: '0.04em', textTransform: 'uppercase', color: ending ? 'var(--danger)' : 'var(--fg-muted)' }}>
                  {ending ? 'Ending in' : 'Time left'}
                </span>
              </div>
              <div style={{ fontSize: 22, fontWeight: 700, color: ending ? 'var(--danger)' : 'var(--fg)' }}>
                <Countdown minutes={parcel.minutesLeft} />
              </div>
              <div style={{ fontSize: 11.5, color: 'var(--fg-subtle)', marginTop: 4 }}>
                Snipe protection on · auction extends by 5 min if bid in last 5 min
              </div>
            </div>

            <div style={{ padding: 18 }}>
              <label className="field-label">Your bid</label>
              <div style={{ display: 'flex', gap: 6, marginBottom: 10 }}>
                <div style={{ position: 'relative', flex: 1 }}>
                  <span style={{ position: 'absolute', left: 12, top: '50%', transform: 'translateY(-50%)', color: 'var(--fg-subtle)', fontSize: 13, fontWeight: 500 }}>L$</span>
                  <input className="input input--lg tabular" value={bidAmount.toLocaleString()}
                    onChange={(e) => setBidAmount(+e.target.value.replace(/[^0-9]/g, '') || 0)}
                    style={{ paddingLeft: 32, fontSize: 16, fontWeight: 600 }} />
                </div>
                <button className="btn btn--secondary" onClick={() => setBidAmount(b => b + 100)} style={{ padding: '0 12px' }}><Icons.Plus size={14} /></button>
              </div>
              <div style={{ display: 'flex', gap: 6, marginBottom: 14 }}>
                {[100, 500, 1000, 5000].map(inc => (
                  <button key={inc} className="chip" onClick={() => setBidAmount(b => b + inc)} style={{ flex: 1, justifyContent: 'center', fontSize: 12 }}>+{inc.toLocaleString()}</button>
                ))}
              </div>

              <Btn variant="primary" size="lg" block onClick={placeBid}>
                <Icons.Gavel size={15} /> Place bid
              </Btn>

              {parcel.bin && (
                <Btn variant="dark" size="lg" block style={{ marginTop: 8 }}>
                  Buy now · <L amount={parcel.bin} />
                </Btn>
              )}

              <div style={{ display: 'flex', gap: 8, marginTop: 12 }}>
                <Btn variant="ghost" size="sm" block><Icons.Heart size={13} /> Watch</Btn>
                <Btn variant="ghost" size="sm" block><Icons.Share size={13} /> Share</Btn>
              </div>
              <button onClick={() => setShowReport(true)} style={{
                marginTop: 8, width: '100%', padding: '6px', border: 'none', background: 'transparent',
                fontSize: 11.5, color: 'var(--fg-subtle)', cursor: 'pointer', textDecoration: 'underline',
                textDecorationStyle: 'dotted', textUnderlineOffset: 3,
              }}>Report this listing</button>

              <div style={{ marginTop: 16, padding: 12, background: 'var(--brand-soft)', borderRadius: 'var(--r-md)', border: '1px solid var(--brand-border)', display: 'flex', gap: 10 }}>
                <Icons.ShieldCheck size={16} style={{ color: 'var(--brand)', flexShrink: 0, marginTop: 1 }} />
                <div style={{ fontSize: 12, color: 'var(--fg)', lineHeight: 1.5 }}>
                  <div style={{ fontWeight: 600, marginBottom: 2 }}>Protected by SLParcels Escrow</div>
                  Your bid is fully refundable if the seller does not transfer the parcel as described.
                </div>
              </div>
            </div>
          </div>

          <div style={{ marginTop: 12, padding: 14, fontSize: 12.5, color: 'var(--fg-muted)', display: 'flex', gap: 10, alignItems: 'center' }}>
            <Icons.Info size={14} />
            <span>Bidding requires <span className="bold" style={{ color: 'var(--fg)' }}>L${minBid.toLocaleString()}</span> available in your wallet</span>
          </div>
        </aside>
      </div>

      {/* Similar */}
      <section style={{ marginTop: 64 }}>
        <div className="section-hd"><div><h2 style={{ fontSize: 18 }}>Similar parcels in {parcel.region}</h2></div></div>
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(4, 1fr)', gap: 18 }}>
          {D.AUCTIONS.filter(a => a.id !== parcel.id).slice(0, 4).map(p => (
            <AuctionCard key={p.id} parcel={p} onClick={() => { setAuctionId(p.id); window.scrollTo(0, 0); }} />
          ))}
        </div>
      </section>

      {showBidModal && <BidModal parcel={parcel} amount={bidAmount} onClose={() => setShowBidModal(false)} setPage={setPage} />}
      <ConfirmBidDialog open={showConfirm} onClose={() => setShowConfirm(false)}
        onConfirm={() => { setShowConfirm(false); setShowBidModal(true); }}
        amount={bidAmount} currentBid={parcel.currentBid} walletAvailable={D.WALLET_AVAILABLE} />
      <ReportListingModal open={showReport} onClose={() => setShowReport(false)} parcel={parcel} />
    </div>
  );
}

function DetailsTab({ parcel }) {
  const D = window.SLP_DATA;
  const facts = [
    ['Region', parcel.region],
    ['Coordinates', parcel.coords],
    ['Altitude', `${parcel.altitude} m`],
    ['Land tier', parcel.tier],
    ['Covenant', parcel.covenant],
    ['Parcel size', formatSqm(parcel.sqm)],
    ['Prim allowance', `${parcel.primCount.toLocaleString()} LI`],
    ['Mainland adjacent', parcel.tier === 'Mainland' ? 'Yes' : 'No'],
    ['Listing type', parcel.bin ? 'Auction + Buy now' : 'Auction'],
    ['Listed', '2 days ago'],
  ];
  return (
    <div>
      <h3 style={{ fontSize: 16, fontWeight: 600, marginTop: 0, marginBottom: 12 }}>About this parcel</h3>
      <p style={{ color: 'var(--fg-muted)', fontSize: 14, lineHeight: 1.65, marginBottom: 24, maxWidth: 720 }}>
        {parcel.description} The seller has provided clean ownership history and the parcel is currently free of build restrictions beyond the covenant noted. Adjacent terraforming has been finalized to avoid edge mismatch.
      </p>
      <h3 style={{ fontSize: 14, fontWeight: 600, marginTop: 0, marginBottom: 10 }}>Specifications</h3>
      <div className="card" style={{ padding: 0, overflow: 'hidden' }}>
        {facts.map(([k, v], i) => (
          <div key={k} style={{
            display: 'grid', gridTemplateColumns: '180px 1fr',
            padding: '12px 16px',
            borderTop: i === 0 ? 'none' : '1px solid var(--border-subtle)',
            fontSize: 13.5,
          }}>
            <div style={{ color: 'var(--fg-muted)' }}>{k}</div>
            <div style={{ fontWeight: 500 }}>{v}</div>
          </div>
        ))}
      </div>

      <h3 style={{ fontSize: 14, fontWeight: 600, marginTop: 24, marginBottom: 10 }}>Auction terms</h3>
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(2, 1fr)', gap: 10 }}>
        {[
          ['Starting bid', `L$ ${parcel.startingBid.toLocaleString()}`],
          ['Bid increment', 'L$ 100'],
          ['Reserve price', parcel.reserve ? `L$ ${parcel.reserve.toLocaleString()}` : 'No reserve'],
          ['Buy-it-now', parcel.bin ? `L$ ${parcel.bin.toLocaleString()}` : '—'],
          ['Snipe protection', '5-min extension window'],
          ['Settlement window', '48 hours after auction close'],
        ].map(([k, v]) => (
          <div key={k} className="card" style={{ padding: '12px 14px', display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
            <span style={{ fontSize: 12.5, color: 'var(--fg-muted)' }}>{k}</span>
            <span className="bold tabular" style={{ fontSize: 13.5 }}>{v}</span>
          </div>
        ))}
      </div>
    </div>
  );
}

function BidsTab({ parcel }) {
  const D = window.SLP_DATA;
  return (
    <div>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 12 }}>
        <h3 style={{ fontSize: 16, fontWeight: 600, margin: 0 }}>Bid history · {parcel.bidCount} bids</h3>
        <span style={{ fontSize: 12, color: 'var(--fg-subtle)' }}>Updated live</span>
      </div>
      <div className="card" style={{ overflow: 'hidden' }}>
        <div style={{ padding: '10px 16px', display: 'grid', gridTemplateColumns: '40px 1fr 120px 120px 80px', fontSize: 11.5, textTransform: 'uppercase', letterSpacing: '0.05em', color: 'var(--fg-subtle)', fontWeight: 600, borderBottom: '1px solid var(--border)', background: 'var(--bg-subtle)' }}>
          <div>#</div><div>Bidder</div><div>Amount</div><div>Type</div><div>Time</div>
        </div>
        {D.BIDS.map((b, i) => (
          <div key={b.id} style={{
            padding: '12px 16px',
            display: 'grid', gridTemplateColumns: '40px 1fr 120px 120px 80px',
            alignItems: 'center', fontSize: 13.5,
            borderTop: i === 0 ? 'none' : '1px solid var(--border-subtle)',
            background: i === 0 ? 'var(--brand-soft)' : 'transparent',
          }}>
            <div style={{ fontFamily: 'var(--font-mono)', color: 'var(--fg-subtle)', fontSize: 12 }}>{D.BIDS.length - i}</div>
            <div style={{ display: 'flex', gap: 10, alignItems: 'center' }}>
              <Avatar name={b.bidder} size={26} />
              <span style={{ fontWeight: 500 }}>{b.bidder}</span>
              {i === 0 && <Badge tone="brand">Top bid</Badge>}
            </div>
            <div className="bold tabular"><L amount={b.amount} /></div>
            <div style={{ color: 'var(--fg-muted)' }}>{b.proxy ? 'Proxy bid' : 'Manual'}</div>
            <div style={{ color: 'var(--fg-subtle)', fontSize: 12 }}>{b.time}</div>
          </div>
        ))}
      </div>
    </div>
  );
}

function SellerTab({ parcel }) {
  const s = parcel.seller;
  const [flagReview, setFlagReview] = React.useState(null);
  const reviews = [
    { id: 'r1', author: 'Devon Hale', stars: 5, when: '2 weeks ago', text: 'Smooth transfer — coords matched, prims allocated correctly. Would buy again.' },
    { id: 'r2', author: 'Mira Solano', stars: 5, when: '1 month ago', text: 'Beautifully terraformed and exactly as described. Seller responded within an hour.' },
    { id: 'r3', author: 'Otto Whitmer', stars: 4, when: '2 months ago', text: 'Good parcel and fair price. Handover took a day longer than expected but no complaints overall.' },
    { id: 'r4', author: 'Sable Vance', stars: 5, when: '3 months ago', text: 'Excellent comms. Threw in a small landscaping bonus.' },
  ];
  return (
    <div>
      <div className="card" style={{ padding: 20, display: 'flex', gap: 18, alignItems: 'flex-start' }}>
        <Avatar name={s.name} size={64} color={s.avatar} />
        <div style={{ flex: 1 }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginBottom: 4 }}>
            <h3 style={{ fontSize: 18, fontWeight: 700, margin: 0 }}>{s.name}</h3>
            <Badge tone="success"><Icons.ShieldCheck size={11} /> Verified</Badge>
          </div>
          <div style={{ display: 'flex', gap: 14, alignItems: 'center', fontSize: 13, color: 'var(--fg-muted)', marginBottom: 12 }}>
            <StarRating value={s.rating} />
            <span>·</span>
            <span>{s.sales} sales</span>
            <span>·</span>
            <span>Member since {s.member}</span>
          </div>
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(4, 1fr)', gap: 12, marginTop: 8 }}>
            {[
              ['Completion rate', `${s.completion}%`],
              ['Avg. response', '< 2h'],
              ['Disputes', '0 active'],
              ['Returning buyers', '38%'],
            ].map(([k, v]) => (
              <div key={k}>
                <div style={{ fontSize: 11.5, color: 'var(--fg-subtle)' }}>{k}</div>
                <div style={{ fontSize: 16, fontWeight: 600, marginTop: 2 }}>{v}</div>
              </div>
            ))}
          </div>
        </div>
        <Btn variant="secondary" size="sm">Message seller</Btn>
      </div>

      <div style={{ marginTop: 24 }}>
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'baseline', marginBottom: 12 }}>
          <h3 style={{ fontSize: 16, fontWeight: 600, margin: 0 }}>Recent reviews</h3>
          <a className="muted" style={{ fontSize: 12.5, cursor: 'pointer' }}>See all {s.sales} →</a>
        </div>
        <div className="card" style={{ overflow: 'hidden' }}>
          {reviews.map((r, i) => (
            <div key={r.id} style={{ padding: 16, borderTop: i === 0 ? 'none' : '1px solid var(--border-subtle)', display: 'flex', gap: 12, alignItems: 'flex-start' }}>
              <Avatar name={r.author} size={32} />
              <div style={{ flex: 1, minWidth: 0 }}>
                <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 4, flexWrap: 'wrap' }}>
                  <span style={{ fontSize: 13.5, fontWeight: 600 }}>{r.author}</span>
                  <StarRating value={r.stars} size={11} />
                  <span style={{ fontSize: 12, color: 'var(--fg-subtle)' }}>· {r.when}</span>
                </div>
                <div style={{ fontSize: 13.5, color: 'var(--fg-muted)', lineHeight: 1.55 }}>{r.text}</div>
              </div>
              <button onClick={() => setFlagReview(r)} title="Flag this review" style={{
                border: 'none', background: 'transparent', cursor: 'pointer',
                color: 'var(--fg-subtle)', padding: 6, borderRadius: 'var(--r-sm)',
              }}>
                <Icons.AlertTriangle size={14} />
              </button>
            </div>
          ))}
        </div>
      </div>

      <FlagReviewModal open={!!flagReview} onClose={() => setFlagReview(null)} review={flagReview} />
    </div>
  );
}

function RegionTab({ parcel }) {
  return (
    <div>
      <div className="card" style={{ padding: 20 }}>
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', marginBottom: 12 }}>
          <div>
            <h3 style={{ fontSize: 18, fontWeight: 700, margin: 0 }}>{parcel.region}</h3>
            <div style={{ fontSize: 12.5, color: 'var(--fg-muted)', marginTop: 2 }}>Continent · 1 of 6 SL mainlands</div>
          </div>
          <Badge tone="success" dot>Online</Badge>
        </div>
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(4, 1fr)', gap: 16 }}>
          {[
            ['Region rating', '4.8 ★'],
            ['Avg. sale (L$/m²)', '12.4'],
            ['Active listings', '47'],
            ['Sold (30d)', '93'],
          ].map(([k, v]) => (
            <div key={k}>
              <div style={{ fontSize: 11.5, color: 'var(--fg-subtle)' }}>{k}</div>
              <div style={{ fontSize: 16, fontWeight: 600, marginTop: 2 }}>{v}</div>
            </div>
          ))}
        </div>
      </div>
    </div>
  );
}

function BidModal({ parcel, amount, onClose, setPage }) {
  const D = window.SLP_DATA;
  const [step, setStep] = React.useState('confirm');
  const [proxyMax, setProxyMax] = React.useState(amount);
  const [useProxy, setUseProxy] = React.useState(false);
  const reserve = useProxy ? proxyMax : amount;
  const sufficient = D.WALLET_AVAILABLE >= reserve;

  const submit = () => {
    setStep('processing');
    setTimeout(() => setStep('done'), 1200);
  };

  return (
    <div onClick={onClose} style={{
      position: 'fixed', inset: 0, background: 'rgba(0,0,0,.5)', backdropFilter: 'blur(4px)',
      zIndex: 1000, display: 'grid', placeItems: 'center', padding: 20,
    }}>
      <div onClick={(e) => e.stopPropagation()} className="card" style={{ width: 480, maxWidth: '100%', padding: 0 }}>
        {step === 'confirm' && (
          <>
            <div style={{ padding: '20px 24px', borderBottom: '1px solid var(--border)', display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
              <h3 style={{ margin: 0, fontSize: 17, fontWeight: 700 }}>Confirm your bid</h3>
              <button className="hdr-icon-btn" onClick={onClose}><Icons.X size={16} /></button>
            </div>
            <div style={{ padding: 24 }}>
              <div style={{ display: 'flex', gap: 12, marginBottom: 18 }}>
                <div style={{ width: 56, borderRadius: 8, overflow: 'hidden' }}>
                  <ParcelImage parcel={parcel} ratio="1/1" showSave={false} />
                </div>
                <div style={{ flex: 1, minWidth: 0 }}>
                  <div style={{ fontSize: 14, fontWeight: 600 }}>{parcel.title}</div>
                  <div style={{ fontFamily: 'var(--font-mono)', fontSize: 11, color: 'var(--fg-subtle)' }}>{parcel.coords}</div>
                </div>
              </div>

              <div style={{ background: 'var(--bg-subtle)', padding: 14, borderRadius: 'var(--r-md)', marginBottom: 16 }}>
                <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'baseline', marginBottom: 6 }}>
                  <span style={{ fontSize: 12.5, color: 'var(--fg-muted)' }}>Your bid</span>
                  <span style={{ fontSize: 22, fontWeight: 700 }}><L amount={amount} /></span>
                </div>
                <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: 12.5, color: 'var(--fg-subtle)' }}>
                  <span>Current top bid</span>
                  <span className="tabular">L$ {parcel.currentBid.toLocaleString()}</span>
                </div>
              </div>

              <label style={{ display: 'flex', gap: 10, alignItems: 'flex-start', padding: 12, border: '1px solid var(--border)', borderRadius: 'var(--r-md)', cursor: 'pointer', marginBottom: 12 }}>
                <input type="checkbox" checked={useProxy} onChange={() => setUseProxy(!useProxy)} style={{ marginTop: 2, accentColor: 'var(--brand)' }} />
                <div style={{ flex: 1 }}>
                  <div style={{ fontSize: 13.5, fontWeight: 500 }}>Use proxy bidding</div>
                  <div style={{ fontSize: 12, color: 'var(--fg-muted)' }}>We'll auto-bid up to your max in L$ 100 increments to keep you on top.</div>
                  {useProxy && (
                    <div style={{ marginTop: 10, position: 'relative' }}>
                      <span style={{ position: 'absolute', left: 10, top: '50%', transform: 'translateY(-50%)', color: 'var(--fg-subtle)', fontSize: 12 }}>L$</span>
                      <input className="input" value={proxyMax.toLocaleString()} onChange={(e) => setProxyMax(+e.target.value.replace(/[^0-9]/g, '') || 0)} style={{ paddingLeft: 30 }} />
                    </div>
                  )}
                </div>
              </label>

              <div style={{ padding: 12, background: sufficient ? 'var(--success-bg)' : 'var(--danger-bg)', borderRadius: 'var(--r-md)', display: 'flex', gap: 10, fontSize: 12.5, marginBottom: 16 }}>
                {sufficient ? <Icons.CheckCircle size={15} style={{ color: 'var(--success)' }} /> : <Icons.AlertCircle size={15} style={{ color: 'var(--danger)' }} />}
                <div>
                  <div style={{ fontWeight: 600, color: sufficient ? 'var(--success)' : 'var(--danger)' }}>
                    {sufficient ? 'Wallet has sufficient funds' : 'Insufficient wallet balance'}
                  </div>
                  <div style={{ color: 'var(--fg-muted)', marginTop: 2 }}>L$ {reserve.toLocaleString()} will be reserved · L$ {D.WALLET_AVAILABLE.toLocaleString()} available</div>
                </div>
              </div>

              <div style={{ fontSize: 11.5, color: 'var(--fg-subtle)', marginBottom: 16, lineHeight: 1.5 }}>
                By placing this bid you agree to the SLParcels Auction Terms. Funds are reserved in escrow until the auction settles or you are outbid.
              </div>

              <div style={{ display: 'flex', gap: 8 }}>
                <Btn variant="secondary" block onClick={onClose}>Cancel</Btn>
                <Btn variant="primary" block onClick={submit} disabled={!sufficient}>
                  <Icons.Gavel size={14} /> Confirm bid
                </Btn>
              </div>
            </div>
          </>
        )}
        {step === 'processing' && (
          <div style={{ padding: 60, textAlign: 'center' }}>
            <div style={{ width: 40, height: 40, margin: '0 auto 16px', border: '3px solid var(--border)', borderTopColor: 'var(--brand)', borderRadius: '50%', animation: 'spin 0.8s linear infinite' }} />
            <div style={{ fontWeight: 600, marginBottom: 4 }}>Reserving funds…</div>
            <div style={{ fontSize: 12.5, color: 'var(--fg-muted)' }}>Locking L$ {amount.toLocaleString()} in escrow</div>
            <style>{`@keyframes spin { to { transform: rotate(360deg); } }`}</style>
          </div>
        )}
        {step === 'done' && (
          <div style={{ padding: 32, textAlign: 'center' }}>
            <div style={{ width: 56, height: 56, margin: '0 auto 16px', borderRadius: '50%', background: 'var(--success-bg)', color: 'var(--success)', display: 'grid', placeItems: 'center' }}>
              <Icons.Check size={28} sw={2.5} />
            </div>
            <div style={{ fontSize: 18, fontWeight: 700, marginBottom: 4 }}>You're the top bidder</div>
            <div style={{ fontSize: 13, color: 'var(--fg-muted)', marginBottom: 20 }}>
              L$ {amount.toLocaleString()} reserved. You'll be notified instantly if you're outbid.
            </div>
            <div style={{ display: 'flex', gap: 8 }}>
              <Btn variant="secondary" block onClick={onClose}>Keep browsing</Btn>
              <Btn variant="primary" block onClick={() => { onClose(); setPage('escrow'); }}>View in dashboard</Btn>
            </div>
          </div>
        )}
      </div>
    </div>
  );
}

window.AuctionPage = AuctionPage;
