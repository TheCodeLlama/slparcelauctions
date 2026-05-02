// primitives.jsx — shared UI primitives

function Btn({ variant = 'secondary', size, block, children, ...rest }) {
  const cls = ['btn', `btn--${variant}`];
  if (size) cls.push(`btn--${size}`);
  if (block) cls.push('btn--block');
  return <button className={cls.join(' ')} {...rest}>{children}</button>;
}

function Badge({ tone = 'neutral', dot, pulse, children, ...rest }) {
  return (
    <span className={`badge badge--${tone}`} {...rest}>
      {dot && <span className={`badge-dot ${pulse ? 'badge-dot--pulse' : ''}`} />}
      {children}
    </span>
  );
}

function Chip({ active, onClose, children, ...rest }) {
  return (
    <button className={`chip ${active ? 'chip--active' : ''}`} {...rest}>
      {children}
      {onClose && (
        <span className="chip-x" onClick={(e) => { e.stopPropagation(); onClose(); }}>
          <Icons.X size={10} sw={2} />
        </span>
      )}
    </button>
  );
}

function Avatar({ name, size = 28, color }) {
  const initials = name ? name.replace(/[^A-Za-z]/g, '').slice(0, 2).toUpperCase() : 'U';
  // hash to hue
  const hash = name ? name.split('').reduce((a, c) => a + c.charCodeAt(0), 0) : 0;
  const hue = color || `hsl(${hash % 360}, 38%, 42%)`;
  return (
    <div style={{
      width: size, height: size, borderRadius: '50%',
      background: hue, color: 'white',
      display: 'grid', placeItems: 'center',
      fontSize: size * 0.38, fontWeight: 600,
      flexShrink: 0,
      letterSpacing: 0,
    }}>{initials}</div>
  );
}

function StarRating({ value, size = 12, showNumber = true }) {
  const stars = [];
  for (let i = 1; i <= 5; i++) {
    stars.push(
      <Icons.Star key={i} size={size} filled={i <= Math.round(value)}
        style={{ color: i <= Math.round(value) ? '#E3631E' : 'var(--fg-faint)' }} />
    );
  }
  return (
    <span style={{ display: 'inline-flex', alignItems: 'center', gap: 4 }}>
      <span style={{ display: 'inline-flex', gap: 1 }}>{stars}</span>
      {showNumber && <span style={{ fontSize: 12, color: 'var(--fg-muted)', fontWeight: 500 }}>{Number(value).toFixed(1)}</span>}
    </span>
  );
}

// Parcel imagery placeholder — colorful gradient + grid + coords
function ParcelImage({ parcel, ratio = '4 / 3', showSave = true, big = false, children }) {
  const [saved, setSaved] = React.useState(parcel.saved);
  return (
    <div className="parcel-img" style={{ aspectRatio: ratio, background: parcel.grad }}>
      <div className="parcel-img-grid" />
      {/* Topographic-ish ridges */}
      <svg viewBox="0 0 200 150" preserveAspectRatio="none"
        style={{ position: 'absolute', inset: 0, width: '100%', height: '100%', opacity: .35, mixBlendMode: 'screen' }}>
        <path d="M 0 110 Q 30 80 60 95 T 120 90 T 200 75 L 200 150 L 0 150 Z" fill="rgba(255,255,255,.18)" />
        <path d="M 0 130 Q 40 105 80 118 T 160 110 T 200 105 L 200 150 L 0 150 Z" fill="rgba(255,255,255,.14)" />
      </svg>
      {parcel.isFeatured && (
        <div className="parcel-img-tier">
          <Badge tone="brand"><Icons.Star size={10} filled /> Featured</Badge>
        </div>
      )}
      {!parcel.isFeatured && big && (
        <div className="parcel-img-tier">
          <Badge tone="neutral" style={{ background: 'rgba(0,0,0,.55)', color: '#fff', backdropFilter: 'blur(6px)' }}>{parcel.tier}</Badge>
        </div>
      )}
      {showSave && (
        <button className={`parcel-img-save ${saved ? 'is-saved' : ''}`}
          onClick={(e) => { e.stopPropagation(); setSaved(!saved); }}>
          <Icons.Heart size={14} filled={saved} />
        </button>
      )}
      <div className="parcel-img-coords">{parcel.coords}</div>
      {children}
    </div>
  );
}

// Live countdown that ticks every second
function Countdown({ minutes, compact, urgent }) {
  const [secs, setSecs] = React.useState(minutes * 60);
  React.useEffect(() => {
    const t = setInterval(() => setSecs((s) => Math.max(0, s - 1)), 1000);
    return () => clearInterval(t);
  }, []);
  const d = Math.floor(secs / 86400);
  const h = Math.floor((secs % 86400) / 3600);
  const m = Math.floor((secs % 3600) / 60);
  const s = secs % 60;
  const isUrgent = urgent || secs < 60 * 60;
  if (compact) {
    if (d > 0) return <span className="tabular">{d}d {h}h</span>;
    if (h > 0) return <span className="tabular">{h}h {String(m).padStart(2,'0')}m</span>;
    return <span className="tabular" style={{ color: isUrgent ? 'var(--danger)' : undefined }}>
      {String(m).padStart(2,'0')}:{String(s).padStart(2,'0')}
    </span>;
  }
  const parts = [];
  if (d > 0) parts.push([d, 'd']);
  parts.push([h, 'h']);
  parts.push([m, 'm']);
  if (d === 0 && h === 0) parts.push([s, 's']);
  return (
    <span style={{ display: 'inline-flex', gap: 6, alignItems: 'baseline', fontVariantNumeric: 'tabular-nums' }}>
      {parts.map(([v, u], i) => (
        <span key={i}>
          <span style={{ fontWeight: 700, fontSize: 'inherit' }}>{String(v).padStart(2,'0')}</span>
          <span style={{ fontSize: '0.62em', color: 'var(--fg-subtle)', fontWeight: 500, marginLeft: 1 }}>{u}</span>
        </span>
      ))}
    </span>
  );
}

// Linden-dollar amount formatter
function L({ amount, big, faint, currency = true }) {
  return (
    <span className="amt" style={{
      fontSize: big ? 'inherit' : undefined,
      color: faint ? 'var(--fg-subtle)' : undefined,
    }}>
      {currency && <span className="amt-currency">L$</span>}{amount.toLocaleString()}
    </span>
  );
}

// Auction card variants
function AuctionCard({ parcel, layout = 'standard', onClick }) {
  const ending = parcel.minutesLeft < 60;
  const reserveTone = parcel.reserveMet ? 'success' : 'warning';

  if (layout === 'compact') {
    return (
      <div className="card card--hover" onClick={onClick} style={{ display: 'flex', gap: 12, padding: 10, alignItems: 'stretch' }}>
        <div style={{ width: 96, flexShrink: 0, borderRadius: 8, overflow: 'hidden' }}>
          <ParcelImage parcel={parcel} ratio="1/1" showSave={false} />
        </div>
        <div style={{ flex: 1, minWidth: 0, display: 'flex', flexDirection: 'column', justifyContent: 'space-between' }}>
          <div>
            <div style={{ fontSize: 13.5, fontWeight: 600, lineHeight: 1.3, marginBottom: 3,
                          overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{parcel.title}</div>
            <div style={{ fontFamily: 'var(--font-mono)', fontSize: 11, color: 'var(--fg-subtle)' }}>{parcel.coords}</div>
          </div>
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
            <div>
              <div style={{ fontSize: 11, color: 'var(--fg-subtle)' }}>Current bid</div>
              <L amount={parcel.currentBid} />
            </div>
            <div style={{ textAlign: 'right' }}>
              <div style={{ fontSize: 11, color: ending ? 'var(--danger)' : 'var(--fg-subtle)' }}>{ending ? 'Ending' : 'Ends in'}</div>
              <div style={{ fontWeight: 600, color: ending ? 'var(--danger)' : 'var(--fg)' }}>
                <Countdown minutes={parcel.minutesLeft} compact />
              </div>
            </div>
          </div>
        </div>
      </div>
    );
  }

  if (layout === 'detailed') {
    return (
      <div className="card card--hover" onClick={onClick}>
        <ParcelImage parcel={parcel} ratio="16/10" />
        <div style={{ padding: 16 }}>
          <div style={{ display: 'flex', gap: 6, marginBottom: 10, flexWrap: 'wrap' }}>
            <Badge tone="neutral">{parcel.tier}</Badge>
            <Badge tone="outline">{formatSqm(parcel.sqm)}</Badge>
            <Badge tone="outline">{parcel.covenant}</Badge>
            {ending && <Badge tone="danger" dot pulse>Ending soon</Badge>}
          </div>
          <div style={{ fontSize: 16, fontWeight: 600, marginBottom: 4, lineHeight: 1.3 }}>{parcel.title}</div>
          <div style={{ fontFamily: 'var(--font-mono)', fontSize: 11, color: 'var(--fg-subtle)', marginBottom: 14 }}>
            {parcel.coords}
          </div>
          <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 12, paddingTop: 12, borderTop: '1px solid var(--border)' }}>
            <div>
              <div style={{ fontSize: 11, color: 'var(--fg-subtle)', marginBottom: 2, textTransform: 'uppercase', letterSpacing: '0.04em' }}>Top bid</div>
              <div style={{ fontSize: 18, fontWeight: 700 }}><L amount={parcel.currentBid} /></div>
              <div style={{ fontSize: 11.5, color: 'var(--fg-subtle)', marginTop: 2 }}>{parcel.bidCount} bids · {parcel.watchers} watching</div>
            </div>
            <div style={{ textAlign: 'right' }}>
              <div style={{ fontSize: 11, color: 'var(--fg-subtle)', marginBottom: 2, textTransform: 'uppercase', letterSpacing: '0.04em' }}>Time left</div>
              <div style={{ fontSize: 18, fontWeight: 700, color: ending ? 'var(--danger)' : 'var(--fg)' }}>
                <Countdown minutes={parcel.minutesLeft} compact />
              </div>
              <div style={{ fontSize: 11.5, marginTop: 2 }}>
                <Badge tone={reserveTone}>{parcel.reserveMet ? 'Reserve met' : 'Reserve not met'}</Badge>
              </div>
            </div>
          </div>
        </div>
      </div>
    );
  }

  // standard
  return (
    <div className="card card--hover" onClick={onClick}>
      <ParcelImage parcel={parcel} ratio="4/3" />
      <div style={{ padding: 14 }}>
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', gap: 8, marginBottom: 4 }}>
          <div style={{ fontSize: 14.5, fontWeight: 600, lineHeight: 1.3, flex: 1 }}>{parcel.title}</div>
          {ending && <Badge tone="danger" dot pulse>Ending</Badge>}
        </div>
        <div style={{ fontFamily: 'var(--font-mono)', fontSize: 11, color: 'var(--fg-subtle)', marginBottom: 12 }}>
          {parcel.coords} · {formatSqm(parcel.sqm)}
        </div>
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-end' }}>
          <div>
            <div style={{ fontSize: 11, color: 'var(--fg-subtle)', marginBottom: 1 }}>{parcel.bidCount} bids</div>
            <div style={{ fontSize: 16 }}><L amount={parcel.currentBid} /></div>
          </div>
          <div style={{ textAlign: 'right' }}>
            <div style={{ fontSize: 11, color: ending ? 'var(--danger)' : 'var(--fg-subtle)', marginBottom: 1 }}>
              {ending ? 'Ending in' : 'Ends in'}
            </div>
            <div style={{ fontSize: 14, fontWeight: 600, color: ending ? 'var(--danger)' : 'var(--fg)' }}>
              <Countdown minutes={parcel.minutesLeft} compact />
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}

// Local helpers exposed
window.Btn = Btn;
window.Badge = Badge;
window.Chip = Chip;
window.Avatar = Avatar;
window.StarRating = StarRating;
window.ParcelImage = ParcelImage;
window.Countdown = Countdown;
window.L = L;
window.AuctionCard = AuctionCard;
