// icons.jsx — minimal stroke icons
const Icon = ({ size = 16, sw = 1.75, children, ...rest }) => (
  <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor"
       strokeWidth={sw} strokeLinecap="round" strokeLinejoin="round" {...rest}>
    {children}
  </svg>
);

const Icons = {
  Search: (p) => <Icon {...p}><circle cx="11" cy="11" r="7" /><path d="m20 20-3.5-3.5" /></Icon>,
  Bell: (p) => <Icon {...p}><path d="M6 8a6 6 0 0 1 12 0c0 7 3 9 3 9H3s3-2 3-9" /><path d="M10 21a2 2 0 0 0 4 0" /></Icon>,
  Sun: (p) => <Icon {...p}><circle cx="12" cy="12" r="4" /><path d="M12 2v2M12 20v2M4.93 4.93l1.41 1.41M17.66 17.66l1.41 1.41M2 12h2M20 12h2M4.93 19.07l1.41-1.41M17.66 6.34l1.41-1.41" /></Icon>,
  Moon: (p) => <Icon {...p}><path d="M21 12.79A9 9 0 1 1 11.21 3 7 7 0 0 0 21 12.79z" /></Icon>,
  Menu: (p) => <Icon {...p}><path d="M4 6h16M4 12h16M4 18h16" /></Icon>,
  X: (p) => <Icon {...p}><path d="M18 6 6 18M6 6l12 12" /></Icon>,
  ChevronDown: (p) => <Icon {...p}><path d="m6 9 6 6 6-6" /></Icon>,
  ChevronRight: (p) => <Icon {...p}><path d="m9 6 6 6-6 6" /></Icon>,
  ChevronLeft: (p) => <Icon {...p}><path d="m15 18-6-6 6-6" /></Icon>,
  Heart: ({ filled, ...p }) => <Icon {...p} fill={filled ? 'currentColor' : 'none'}><path d="M20.84 4.61a5.5 5.5 0 0 0-7.78 0L12 5.67l-1.06-1.06a5.5 5.5 0 0 0-7.78 7.78l1.06 1.06L12 21.23l7.78-7.78 1.06-1.06a5.5 5.5 0 0 0 0-7.78z" /></Icon>,
  Filter: (p) => <Icon {...p}><path d="M3 6h18M6 12h12M10 18h4" /></Icon>,
  ArrowRight: (p) => <Icon {...p}><path d="M5 12h14M12 5l7 7-7 7" /></Icon>,
  ArrowUpRight: (p) => <Icon {...p}><path d="M7 17 17 7M7 7h10v10" /></Icon>,
  Map: (p) => <Icon {...p}><path d="M9 5 3 7v14l6-2 6 2 6-2V5l-6 2-6-2zM9 5v14M15 7v14" /></Icon>,
  MapPin: (p) => <Icon {...p}><path d="M20 10c0 6-8 12-8 12s-8-6-8-12a8 8 0 0 1 16 0z" /><circle cx="12" cy="10" r="3" /></Icon>,
  Grid: (p) => <Icon {...p}><path d="M3 3h7v7H3zM14 3h7v7h-7zM3 14h7v7H3zM14 14h7v7h-7z" /></Icon>,
  Plus: (p) => <Icon {...p}><path d="M12 5v14M5 12h14" /></Icon>,
  Minus: (p) => <Icon {...p}><path d="M5 12h14" /></Icon>,
  Check: (p) => <Icon {...p}><path d="m5 12 5 5L20 7" /></Icon>,
  CheckCircle: (p) => <Icon {...p}><circle cx="12" cy="12" r="10" /><path d="m9 12 2 2 4-4" /></Icon>,
  Circle: (p) => <Icon {...p}><circle cx="12" cy="12" r="10" /></Icon>,
  AlertCircle: (p) => <Icon {...p}><circle cx="12" cy="12" r="10" /><path d="M12 8v4M12 16h.01" /></Icon>,
  AlertTriangle: (p) => <Icon {...p}><path d="M10.29 3.86 1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0zM12 9v4M12 17h.01" /></Icon>,
  Shield: (p) => <Icon {...p}><path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z" /></Icon>,
  ShieldCheck: (p) => <Icon {...p}><path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z" /><path d="m9 12 2 2 4-4" /></Icon>,
  Lock: (p) => <Icon {...p}><rect x="3" y="11" width="18" height="11" rx="2" /><path d="M7 11V7a5 5 0 0 1 10 0v4" /></Icon>,
  Clock: (p) => <Icon {...p}><circle cx="12" cy="12" r="10" /><path d="M12 6v6l4 2" /></Icon>,
  Zap: (p) => <Icon {...p}><path d="M13 2 3 14h9l-1 8 10-12h-9l1-8z" /></Icon>,
  Star: ({ filled, ...p }) => <Icon {...p} fill={filled ? 'currentColor' : 'none'}><path d="m12 2 3.09 6.26L22 9.27l-5 4.87 1.18 6.88L12 17.77l-6.18 3.25L7 14.14 2 9.27l6.91-1.01L12 2z" /></Icon>,
  Wallet: (p) => <Icon {...p}><path d="M20 12V8H6a2 2 0 0 1 0-4h12v4" /><path d="M4 6v12a2 2 0 0 0 2 2h14v-4" /><path d="M18 12a2 2 0 0 0 0 4h4v-4z" /></Icon>,
  User: (p) => <Icon {...p}><path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2" /><circle cx="12" cy="7" r="4" /></Icon>,
  Eye: (p) => <Icon {...p}><path d="M2 12s3.5-7 10-7 10 7 10 7-3.5 7-10 7-10-7-10-7z" /><circle cx="12" cy="12" r="3" /></Icon>,
  TrendingUp: (p) => <Icon {...p}><path d="m22 7-8.5 8.5-5-5L2 17" /><path d="M16 7h6v6" /></Icon>,
  Home: (p) => <Icon {...p}><path d="m3 9 9-7 9 7v11a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z" /><path d="M9 22V12h6v10" /></Icon>,
  Image: (p) => <Icon {...p}><rect x="3" y="3" width="18" height="18" rx="2" /><circle cx="9" cy="9" r="2" /><path d="m21 15-5-5L5 21" /></Icon>,
  Maximize: (p) => <Icon {...p}><path d="M3 9V3h6M21 9V3h-6M3 15v6h6M21 15v6h-6" /></Icon>,
  Info: (p) => <Icon {...p}><circle cx="12" cy="12" r="10" /><path d="M12 16v-4M12 8h.01" /></Icon>,
  Share: (p) => <Icon {...p}><circle cx="18" cy="5" r="3" /><circle cx="6" cy="12" r="3" /><circle cx="18" cy="19" r="3" /><path d="m8.6 13.5 6.8 4M15.4 6.5l-6.8 4" /></Icon>,
  MoreH: (p) => <Icon {...p}><circle cx="5" cy="12" r="1" fill="currentColor"/><circle cx="12" cy="12" r="1" fill="currentColor"/><circle cx="19" cy="12" r="1" fill="currentColor"/></Icon>,
  Hammer: (p) => <Icon {...p}><path d="m15 12-8.5 8.5a2.12 2.12 0 0 1-3-3L12 9" /><path d="M17.64 15 22 10.64M20.91 11.7l-1.25-1.25c-.6-.6-.93-1.4-.93-2.25v-.86L16.01 4.6a5.56 5.56 0 0 0-3.94-1.64H9l.92.82A6.18 6.18 0 0 1 12 8.4v1.56l2 2h2.47l2.26 1.91" /></Icon>,
  Gavel: (p) => <Icon {...p}><path d="m14 13-7.5 7.5a2.12 2.12 0 0 1-3-3L11 10M14 13l3-3M14 13l5 5M11 10l-5-5M11 10l3-3M6 5l5 5M19 21H5" /></Icon>,
  History: (p) => <Icon {...p}><path d="M3 12a9 9 0 1 0 3-6.7L3 8" /><path d="M3 3v5h5M12 7v5l4 2" /></Icon>,
  Ruler: (p) => <Icon {...p}><path d="M21.3 8.7 8.7 21.3a1 1 0 0 1-1.4 0L2.7 16.7a1 1 0 0 1 0-1.4L15.3 2.7a1 1 0 0 1 1.4 0l4.6 4.6a1 1 0 0 1 0 1.4z" /><path d="m7.5 10.5 2 2M10.5 7.5l2 2M13.5 4.5l2 2M4.5 13.5l2 2" /></Icon>,
  Tag: (p) => <Icon {...p}><path d="M20.59 13.41 13.42 20.58a2 2 0 0 1-2.83 0L2 12V2h10l8.59 8.59a2 2 0 0 1 0 2.82z" /><circle cx="7" cy="7" r="1.5" fill="currentColor"/></Icon>,
  Layers: (p) => <Icon {...p}><path d="m12 2 10 5-10 5L2 7l10-5z" /><path d="m2 17 10 5 10-5M2 12l10 5 10-5" /></Icon>,
  Building: (p) => <Icon {...p}><rect x="4" y="2" width="16" height="20" rx="1" /><path d="M9 22v-4h6v4M8 6h.01M16 6h.01M8 10h.01M16 10h.01M8 14h.01M16 14h.01" /></Icon>,
  Compass: (p) => <Icon {...p}><circle cx="12" cy="12" r="10" /><path d="m16.24 7.76-2.12 6.36-6.36 2.12 2.12-6.36 6.36-2.12z" /></Icon>,
  RefreshCw: (p) => <Icon {...p}><path d="M3 12a9 9 0 0 1 15-6.7L21 8" /><path d="M21 3v5h-5M21 12a9 9 0 0 1-15 6.7L3 16" /><path d="M8 16H3v5" /></Icon>,
  Refresh: (p) => <Icon {...p}><path d="M3 12a9 9 0 0 1 15-6.7L21 8" /><path d="M21 3v5h-5M21 12a9 9 0 0 1-15 6.7L3 16" /><path d="M8 16H3v5" /></Icon>,
  Camera: (p) => <Icon {...p}><path d="M23 19a2 2 0 0 1-2 2H3a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h4l2-3h6l2 3h4a2 2 0 0 1 2 2z" /><circle cx="12" cy="13" r="4" /></Icon>,
  Copy: (p) => <Icon {...p}><rect x="9" y="9" width="13" height="13" rx="2" /><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1" /></Icon>,
};

window.Icons = Icons;
