// page-browse.jsx
function BrowsePage({ setPage, setAuctionId, cardLayout }) {
  const D = window.SLP_DATA;
  const [view, setView] = React.useState('grid');
  const [sort, setSort] = React.useState('ending-soon');
  const [filters, setFilters] = React.useState({
    tiers: [],
    covenants: [],
    sizeMin: 0, sizeMax: 65536,
    bidMin: 0, bidMax: 200000,
    region: 'any',
    endingWithin: 'any',
    reserveMet: false,
    bin: false,
  });

  const toggle = (k, v) => setFilters(f => ({
    ...f,
    [k]: f[k].includes(v) ? f[k].filter(x => x !== v) : [...f[k], v]
  }));

  let results = D.AUCTIONS.filter(p => {
    if (filters.tiers.length && !filters.tiers.includes(p.tier)) return false;
    if (filters.covenants.length && !filters.covenants.includes(p.covenant)) return false;
    if (p.sizeM2 < filters.sizeMin || p.sizeM2 > filters.sizeMax) return false;
    if (p.currentBid < filters.bidMin || p.currentBid > filters.bidMax) return false;
    if (filters.region !== 'any' && p.region !== filters.region) return false;
    if (filters.reserveMet && !p.reserveMet) return false;
    if (filters.bin && !p.bin) return false;
    return true;
  });
  if (sort === 'ending-soon') results = [...results].sort((a,b) => a.minutesLeft - b.minutesLeft);
  else if (sort === 'price-low') results = [...results].sort((a,b) => a.currentBid - b.currentBid);
  else if (sort === 'price-high') results = [...results].sort((a,b) => b.currentBid - a.currentBid);
  else if (sort === 'most-bids') results = [...results].sort((a,b) => b.bidCount - a.bidCount);
  else if (sort === 'biggest') results = [...results].sort((a,b) => b.sizeM2 - a.sizeM2);

  const open = (id) => { setAuctionId(id); setPage('auction'); };

  return (
    <div className="page container" style={{ padding: '24px 24px 80px' }}>
      {/* Breadcrumb + title */}
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-end', marginBottom: 20, gap: 16, flexWrap: 'wrap' }}>
        <div>
          <div style={{ fontSize: 12, color: 'var(--fg-subtle)', marginBottom: 4 }}>
            <a className="muted" onClick={() => setPage('home')} style={{ cursor: 'pointer' }}>Home</a> / Browse
          </div>
          <h1 style={{ fontSize: 28, fontWeight: 700, letterSpacing: '-0.02em', margin: 0 }}>
            Browse auctions <span style={{ color: 'var(--fg-subtle)', fontWeight: 500 }}>· {results.length} results</span>
          </h1>
        </div>
        <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
          <select className="select" value={sort} onChange={(e) => setSort(e.target.value)} style={{ width: 200 }}>
            <option value="ending-soon">Ending soonest</option>
            <option value="price-low">Price: low to high</option>
            <option value="price-high">Price: high to low</option>
            <option value="most-bids">Most bids</option>
            <option value="biggest">Largest parcels</option>
          </select>
          <div style={{ display: 'flex', border: '1px solid var(--border)', borderRadius: 'var(--r-sm)', overflow: 'hidden' }}>
            <button className="btn btn--ghost" style={{
              borderRadius: 0, padding: '8px 10px',
              background: view === 'grid' ? 'var(--bg-muted)' : 'transparent',
              color: view === 'grid' ? 'var(--fg)' : 'var(--fg-muted)',
            }} onClick={() => setView('grid')}><Icons.Grid size={14} /></button>
            <button className="btn btn--ghost" style={{
              borderRadius: 0, padding: '8px 10px',
              background: view === 'list' ? 'var(--bg-muted)' : 'transparent',
              color: view === 'list' ? 'var(--fg)' : 'var(--fg-muted)',
            }} onClick={() => setView('list')}><Icons.Menu size={14} /></button>
            <button className="btn btn--ghost" style={{
              borderRadius: 0, padding: '8px 10px',
              background: view === 'map' ? 'var(--bg-muted)' : 'transparent',
              color: view === 'map' ? 'var(--fg)' : 'var(--fg-muted)',
            }} onClick={() => setView('map')}><Icons.Map size={14} /></button>
          </div>
        </div>
      </div>

      <div style={{ display: 'grid', gridTemplateColumns: '264px 1fr', gap: 28 }}>
        {/* Filter sidebar */}
        <aside style={{ position: 'sticky', top: 'calc(var(--header-h) + 16px)', height: 'fit-content' }}>
          <FilterGroup title="Region">
            <select className="select" value={filters.region} onChange={(e) => setFilters({...filters, region: e.target.value})}>
              <option value="any">Any region</option>
              {D.REGIONS.map(r => <option key={r.name}>{r.name}</option>)}
            </select>
          </FilterGroup>

          <FilterGroup title="Tier">
            {D.TIERS.map(t => (
              <Checkbox key={t} label={t} checked={filters.tiers.includes(t)} onChange={() => toggle('tiers', t)} />
            ))}
          </FilterGroup>

          <FilterGroup title="Covenant">
            {D.COVENANTS.map(c => (
              <Checkbox key={c} label={c} checked={filters.covenants.includes(c)} onChange={() => toggle('covenants', c)} />
            ))}
          </FilterGroup>

          <FilterGroup title="Parcel size">
            <RangeRow min="0 m²" max="64k m²" />
            <input type="range" min={0} max={65536} step={512} value={filters.sizeMax}
              onChange={(e) => setFilters({...filters, sizeMax: +e.target.value})}
              style={{ width: '100%', accentColor: 'var(--brand)' }} />
            <div style={{ fontSize: 12, color: 'var(--fg-subtle)', marginTop: 2 }}>Up to {formatSqm(filters.sizeMax)}</div>
          </FilterGroup>

          <FilterGroup title="Current bid (L$)">
            <RangeRow min="L$0" max="L$200k" />
            <input type="range" min={0} max={200000} step={500} value={filters.bidMax}
              onChange={(e) => setFilters({...filters, bidMax: +e.target.value})}
              style={{ width: '100%', accentColor: 'var(--brand)' }} />
            <div style={{ fontSize: 12, color: 'var(--fg-subtle)', marginTop: 2 }}>Up to L$ {filters.bidMax.toLocaleString()}</div>
          </FilterGroup>

          <FilterGroup title="Quick filters">
            <Checkbox label="Reserve met" checked={filters.reserveMet} onChange={() => setFilters({...filters, reserveMet: !filters.reserveMet})} />
            <Checkbox label="Buy-it-now available" checked={filters.bin} onChange={() => setFilters({...filters, bin: !filters.bin})} />
          </FilterGroup>

          <Btn variant="ghost" size="sm" block onClick={() => setFilters({ tiers: [], covenants: [], sizeMin: 0, sizeMax: 65536, bidMin: 0, bidMax: 200000, region: 'any', endingWithin: 'any', reserveMet: false, bin: false })}>
            Clear all filters
          </Btn>
        </aside>

        {/* Results */}
        <main>
          {/* Active chips */}
          {(filters.tiers.length || filters.covenants.length || filters.region !== 'any' || filters.reserveMet || filters.bin) ? (
            <div style={{ display: 'flex', gap: 6, marginBottom: 16, flexWrap: 'wrap' }}>
              {filters.region !== 'any' && <Chip onClose={() => setFilters({...filters, region: 'any'})}>Region · {filters.region}</Chip>}
              {filters.tiers.map(t => <Chip key={t} onClose={() => toggle('tiers', t)}>{t}</Chip>)}
              {filters.covenants.map(c => <Chip key={c} onClose={() => toggle('covenants', c)}>{c}</Chip>)}
              {filters.reserveMet && <Chip onClose={() => setFilters({...filters, reserveMet: false})}>Reserve met</Chip>}
              {filters.bin && <Chip onClose={() => setFilters({...filters, bin: false})}>Buy-it-now</Chip>}
            </div>
          ) : null}

          {view === 'map' ? (
            <MapView parcels={results} onOpen={open} />
          ) : view === 'list' ? (
            <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
              {results.map(p => <AuctionCard key={p.id} parcel={p} layout="compact" onClick={() => open(p.id)} />)}
            </div>
          ) : (
            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3, 1fr)', gap: 18 }}>
              {results.map(p => <AuctionCard key={p.id} parcel={p} layout={cardLayout} onClick={() => open(p.id)} />)}
            </div>
          )}

          {results.length === 0 && (
            <div className="card" style={{ padding: 48, textAlign: 'center' }}>
              <div style={{ fontWeight: 600, marginBottom: 4 }}>No matches</div>
              <div style={{ color: 'var(--fg-muted)', fontSize: 13 }}>Try widening your filters.</div>
            </div>
          )}

          {results.length > 0 && (
            <div style={{ display: 'flex', justifyContent: 'center', alignItems: 'center', gap: 4, marginTop: 32 }}>
              <Btn variant="ghost" size="sm"><Icons.ChevronLeft size={14} /> Prev</Btn>
              {[1,2,3,4,5].map(n => (
                <button key={n} className="btn" style={{
                  width: 32, height: 32, padding: 0,
                  background: n === 1 ? 'var(--fg)' : 'transparent',
                  color: n === 1 ? 'var(--bg)' : 'var(--fg-muted)',
                  borderColor: 'transparent',
                }}>{n}</button>
              ))}
              <Btn variant="ghost" size="sm">Next <Icons.ChevronRight size={14} /></Btn>
            </div>
          )}
        </main>
      </div>
    </div>
  );
}

function FilterGroup({ title, children }) {
  return (
    <div style={{ marginBottom: 24 }}>
      <div style={{ fontSize: 11, fontWeight: 600, letterSpacing: '0.06em', textTransform: 'uppercase', color: 'var(--fg-subtle)', marginBottom: 10 }}>
        {title}
      </div>
      <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>{children}</div>
    </div>
  );
}

function Checkbox({ label, checked, onChange }) {
  return (
    <label style={{ display: 'flex', alignItems: 'center', gap: 8, cursor: 'pointer', fontSize: 13.5 }}>
      <span style={{
        width: 16, height: 16, borderRadius: 4,
        border: '1.5px solid ' + (checked ? 'var(--brand)' : 'var(--border-strong)'),
        background: checked ? 'var(--brand)' : 'var(--surface)',
        display: 'grid', placeItems: 'center', flexShrink: 0,
        transition: 'all .15s',
      }}>
        {checked && <Icons.Check size={11} sw={3} style={{ color: 'white' }} />}
      </span>
      <input type="checkbox" checked={checked} onChange={onChange} style={{ display: 'none' }} />
      {label}
    </label>
  );
}

function RangeRow({ min, max }) {
  return (
    <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: 11.5, color: 'var(--fg-subtle)', marginBottom: 4 }}>
      <span>{min}</span><span>{max}</span>
    </div>
  );
}

// Map view — region grid
function MapView({ parcels, onOpen }) {
  return (
    <div className="card" style={{ overflow: 'hidden' }}>
      <div style={{ display: 'grid', gridTemplateColumns: '1fr 320px', minHeight: 560 }}>
        <div style={{
          position: 'relative',
          background: 'linear-gradient(135deg, #1e3a5f 0%, #2a6a8e 100%)',
          backgroundImage: `
            radial-gradient(circle at 30% 40%, rgba(255,200,150,.12) 0%, transparent 30%),
            radial-gradient(circle at 70% 70%, rgba(150,255,180,.1) 0%, transparent 35%),
            linear-gradient(135deg, #1e3a5f 0%, #2a6a8e 100%)
          `,
        }}>
          {/* grid */}
          <div style={{
            position: 'absolute', inset: 0,
            backgroundImage: 'linear-gradient(rgba(255,255,255,.05) 1px, transparent 1px), linear-gradient(90deg, rgba(255,255,255,.05) 1px, transparent 1px)',
            backgroundSize: '40px 40px',
          }} />
          {/* pins */}
          {parcels.slice(0, 14).map((p, i) => {
            const x = ((i * 73) % 90) + 5;
            const y = ((i * 41) % 80) + 8;
            return (
              <button key={p.id} onClick={() => onOpen(p.id)} style={{
                position: 'absolute', left: `${x}%`, top: `${y}%`,
                background: 'var(--brand)', color: 'white',
                border: '2px solid white',
                width: 28, height: 28, borderRadius: '50% 50% 50% 0',
                transform: 'translate(-50%, -100%) rotate(-45deg)',
                cursor: 'pointer',
                boxShadow: '0 4px 12px rgba(0,0,0,.4)',
                display: 'grid', placeItems: 'center',
                fontSize: 10, fontWeight: 700,
              }} title={p.title}>
                <span style={{ transform: 'rotate(45deg)', fontFamily: 'var(--font-mono)' }}>{p.bidCount}</span>
              </button>
            );
          })}
          <div style={{ position: 'absolute', bottom: 12, left: 12, padding: '6px 10px', borderRadius: 6, background: 'rgba(0,0,0,.5)', backdropFilter: 'blur(8px)', color: 'white', fontFamily: 'var(--font-mono)', fontSize: 11 }}>
            Grid · {parcels.length} parcels
          </div>
          <div style={{ position: 'absolute', top: 12, right: 12, display: 'flex', flexDirection: 'column', gap: 4 }}>
            <button className="hdr-icon-btn" style={{ background: 'rgba(255,255,255,.95)', color: 'var(--fg)' }}><Icons.Plus size={14} /></button>
            <button className="hdr-icon-btn" style={{ background: 'rgba(255,255,255,.95)', color: 'var(--fg)' }}><Icons.Minus size={14} /></button>
          </div>
        </div>
        <div style={{ padding: 16, overflowY: 'auto', maxHeight: 560, display: 'flex', flexDirection: 'column', gap: 8, borderLeft: '1px solid var(--border)' }}>
          <div style={{ fontSize: 12, fontWeight: 600, color: 'var(--fg-muted)', textTransform: 'uppercase', letterSpacing: '0.06em', marginBottom: 4 }}>
            Visible parcels
          </div>
          {parcels.slice(0, 8).map(p => <AuctionCard key={p.id} parcel={p} layout="compact" onClick={() => onOpen(p.id)} />)}
        </div>
      </div>
    </div>
  );
}

window.BrowsePage = BrowsePage;
