// data.jsx — mock data for the prototype
const REGIONS = [
  { name: 'Bay City', vibe: 'urban', hue: 210 },
  { name: 'Heterocera', vibe: 'mountains', hue: 145 },
  { name: 'Sansara', vibe: 'mainland', hue: 95 },
  { name: 'Nautilus', vibe: 'coastal', hue: 195 },
  { name: 'Corsica', vibe: 'rural', hue: 60 },
  { name: 'Zindra', vibe: 'urban', hue: 280 },
  { name: 'Satori', vibe: 'oriental', hue: 0 },
  { name: 'Gaeta V', vibe: 'snow', hue: 200 },
  { name: 'Jeogeot', vibe: 'forest', hue: 120 },
];

const TIERS = ['Mainland', 'Premium', 'Estate', 'Homestead', 'Full Region'];
const COVENANTS = ['None', 'Residential', 'Commercial', 'Adult', 'Mature'];

const SELLERS = [
  { id: 'u1', name: 'Aria Northcrest', avatar: '#E3631E', rating: 4.9, sales: 142, member: '2019', completion: 98 },
  { id: 'u2', name: 'Devon Halloway', avatar: '#5B4FE0', rating: 4.7, sales: 87, member: '2021', completion: 96 },
  { id: 'u3', name: 'Mira Kessel', avatar: '#0F766E', rating: 5.0, sales: 211, member: '2017', completion: 99 },
  { id: 'u4', name: 'Otto Wendel', avatar: '#9333EA', rating: 4.6, sales: 53, member: '2022', completion: 94 },
  { id: 'u5', name: 'Sable Vance', avatar: '#DC2626', rating: 4.8, sales: 119, member: '2020', completion: 97 },
];

function makeAuction(i) {
  const region = REGIONS[i % REGIONS.length];
  const tier = TIERS[i % TIERS.length];
  const seller = SELLERS[i % SELLERS.length];
  const sizes = [512, 1024, 2048, 4096, 8192, 16384, 32768, 65536];
  const size = sizes[i % sizes.length];
  const startBid = [500, 1200, 2500, 4500, 8000, 18000, 45000, 95000][i % 8];
  const bumps = (i * 7) % 14 + 2;
  const currentBid = Math.round(startBid * (1 + bumps * 0.08));
  const bidCount = bumps + ((i * 3) % 8);
  const hoursLeft = [0.4, 1.2, 3, 7, 14, 26, 48, 72, 120][i % 9];
  const titles = [
    'Lakeside parcel with sunset view',
    'Corner lot near telehub',
    'Beachfront estate, fully terraformed',
    'Sky platform 4000m, low lag',
    'Old-growth forest plot with creek',
    'Downtown commercial frontage',
    'Mountain summit with panoramic build space',
    'Quiet residential cul-de-sac',
    'Waterway-adjacent linden home',
    'Premium homestead, no neighbors',
    'Snow region with chalet pad',
    'Roman-themed RP parcel',
    'High-traffic mall location',
    'Hilltop with lighthouse rights',
    'Marina-adjacent corner parcel',
  ];
  return {
    id: `a${i}`,
    title: titles[i % titles.length],
    region: region.name,
    coords: `${(i * 47) % 256}, ${(i * 73) % 256}`,
    altitude: i % 3 === 0 ? 4000 : 24,
    tier,
    covenant: COVENANTS[i % COVENANTS.length],
    sizeM2: size,
    primCount: Math.round(size * 0.234),
    description: 'Cleanly terraformed parcel with established landscaping. Owner is moving regions and looking for a quick, fair sale. Includes existing foundation and partial water access where applicable. Reasonable covenant.',
    startingBid: startBid,
    currentBid,
    bidCount,
    bin: i % 4 === 0 ? Math.round(currentBid * 1.8) : null,
    reserve: i % 3 === 0 ? Math.round(startBid * 1.4) : null,
    reserveMet: bumps > 5,
    secondsLeft: Math.round(hoursLeft * 3600),
    seller,
    hue: region.hue,
    saved: i % 5 === 0,
    status: hoursLeft < 0.5 ? 'ending-soon' : 'active',
    images: 4 + (i % 3),
    proxyMax: i % 3 === 0 ? Math.round(currentBid * 1.3) : null,
    snipeProtection: true,
  };
}

const AUCTIONS = Array.from({ length: 28 }, (_, i) => makeAuction(i));

const BIDS = [
  { id: 'b1', bidder: 'Mira K.', amount: 8640, time: '2 min ago', proxy: false },
  { id: 'b2', bidder: 'Otto W.', amount: 8400, time: '4 min ago', proxy: true },
  { id: 'b3', bidder: 'Aria N.', amount: 8200, time: '11 min ago', proxy: false },
  { id: 'b4', bidder: 'Sable V.', amount: 7900, time: '23 min ago', proxy: false },
  { id: 'b5', bidder: 'Devon H.', amount: 7600, time: '34 min ago', proxy: true },
  { id: 'b6', bidder: 'Mira K.', amount: 7300, time: '1 hr ago', proxy: false },
  { id: 'b7', bidder: 'Otto W.', amount: 7000, time: '2 hr ago', proxy: true },
  { id: 'b8', bidder: 'Aria N.', amount: 6800, time: '3 hr ago', proxy: false },
];

const NOTIFICATIONS = [
  { id: 'n1', type: 'outbid', text: 'You were outbid on Lakeside parcel with sunset view', time: '3 min ago', unread: true },
  { id: 'n2', type: 'ending', text: 'An auction you saved is ending in 1 hour', time: '57 min ago', unread: true },
  { id: 'n3', type: 'won', text: 'You won Corner lot near telehub for L$2,920', time: '2 hr ago', unread: true },
  { id: 'n4', type: 'review', text: 'Otto Wendel left you a 5-star review', time: '1 day ago', unread: false },
  { id: 'n5', type: 'system', text: 'Wallet deposit of L$5,000 confirmed', time: '2 days ago', unread: false },
];

const WALLET_BALANCE = 14250;
const WALLET_RESERVED = 3400;
const WALLET_AVAILABLE = WALLET_BALANCE - WALLET_RESERVED;

const WALLET_ACTIVITY = [
  { id: 't1', type: 'bid-reserve', label: 'Bid reservation · Lakeside parcel', amount: -3400, time: '8 min ago', tone: 'neutral' },
  { id: 't2', type: 'deposit', label: 'Deposit · Terminal #SLPT-014', amount: +5000, time: '2 days ago', tone: 'success' },
  { id: 't3', type: 'refund', label: 'Refund · Outbid on Corner lot', amount: +1800, time: '4 days ago', tone: 'success' },
  { id: 't4', type: 'escrow-debit', label: 'Escrow settled · Hilltop parcel', amount: -8400, time: '6 days ago', tone: 'neutral' },
  { id: 't5', type: 'listing-fee', label: 'Listing fee · Beachfront estate', amount: -50, time: '1 wk ago', tone: 'neutral' },
  { id: 't6', type: 'penalty', label: 'Penalty payment', amount: -200, time: '2 wks ago', tone: 'danger' },
  { id: 't7', type: 'withdrawal', label: 'Withdrawal · Bank transfer', amount: -2500, time: '3 wks ago', tone: 'neutral' },
  { id: 't8', type: 'deposit', label: 'Deposit · Terminal #SLPT-007', amount: +10000, time: '4 wks ago', tone: 'success' },
];

const ESCROW_STEPS = [
  { id: 'won', label: 'Auction won', desc: 'Winner declared. Funds reserved.', state: 'complete' },
  { id: 'paid', label: 'Payment locked', desc: 'L$ moved to escrow vault.', state: 'complete' },
  { id: 'transfer', label: 'Parcel transfer', desc: 'Seller transfers parcel in-world.', state: 'active' },
  { id: 'confirm', label: 'Buyer confirms', desc: 'Buyer verifies parcel ownership.', state: 'pending' },
  { id: 'settle', label: 'Settled', desc: 'Funds released to seller.', state: 'pending' },
];

// Helpers
function gradFromHue(h) {
  return `linear-gradient(160deg,
    hsl(${h}, 32%, 22%) 0%,
    hsl(${h}, 38%, 38%) 45%,
    hsl(${(h + 18) % 360}, 48%, 58%) 100%)`;
}
function formatSqm(n) {
  if (n >= 1024) return (n / 1024).toFixed(n >= 4096 ? 0 : 1) + 'k m²';
  return n + ' m²';
}
function fmtL(n) { return 'L$' + n.toLocaleString(); }

// Adapt auctions: add `grad`, `coords`, `minutesLeft`, watchers, isFeatured
AUCTIONS.forEach((a, i) => {
  a.grad = gradFromHue(a.hue);
  a.coords = `${a.region} (${a.coords}, ${a.altitude})`;
  a.minutesLeft = Math.round(a.secondsLeft / 60);
  a.watchers = 8 + (i * 13) % 92;
  a.isFeatured = i % 7 === 0;
  a.sqm = a.sizeM2;
  a.startBid = a.startingBid;
  a.sellerRating = a.seller.rating;
});

window.formatSqm = formatSqm;
window.fmtL = fmtL;
window.SLP_DATA = {
  REGIONS, TIERS, COVENANTS, SELLERS, AUCTIONS, BIDS, NOTIFICATIONS,
  WALLET_BALANCE, WALLET_RESERVED, WALLET_AVAILABLE, WALLET_ACTIVITY,
  ESCROW_STEPS,
};
