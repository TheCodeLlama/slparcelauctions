# Land Pricing Analytics Dashboard — Design Spec

## Overview

A full analytics dashboard for Second Life Mainland land-for-sale data. The system consists of a lightweight FastAPI backend serving CSV data as JSON and a React frontend (Vite) that performs client-side statistical computation, filtering, outlier exclusion, and visualization.

The goal is to provide everything needed to make informed pricing decisions in the SL mainland market: distribution analysis, maturity/size segmentation, configurable outlier exclusion, and interactive filtering.

## Architecture

### Hybrid Client-Server Model

- **Backend (FastAPI):** Reads `output/sl_land_listings.csv` into memory on startup. Serves raw listing data and dataset metadata via two REST endpoints. No database.
- **Frontend (React + Vite):** Fetches all data on load. Performs all filtering, outlier detection, statistical computation, and charting client-side. At ~3,500 rows this is trivially fast in the browser.
- **Communication:** Frontend fetches JSON from the API. No WebSockets or real-time updates needed.

### Directory Structure

```
land-for-sale/
├── api/
│   ├── main.py              # FastAPI app
│   └── requirements.txt     # Python dependencies
├── frontend/
│   ├── package.json
│   ├── vite.config.js
│   ├── index.html
│   └── src/
│       ├── main.jsx
│       ├── App.jsx
│       ├── components/
│       │   ├── Dashboard.jsx
│       │   ├── SummaryMetrics.jsx
│       │   ├── FilterSidebar.jsx
│       │   ├── QuickViews.jsx
│       │   ├── charts/
│       │   │   ├── PriceDistribution.jsx
│       │   │   ├── PricePerSqmDistribution.jsx
│       │   │   ├── ScatterPlot.jsx
│       │   │   ├── BoxPlotByMaturity.jsx
│       │   │   ├── BoxPlotBySizeCategory.jsx
│       │   │   ├── PriceBySizeBar.jsx
│       │   │   └── PercentileTable.jsx
│       │   └── ui/
│       │       └── RangeSlider.jsx
│       ├── hooks/
│       │   ├── useListings.js
│       │   ├── useFilters.js
│       │   └── useStats.js
│       ├── utils/
│       │   ├── statistics.js
│       │   └── outliers.js
│       └── styles/
│           └── index.css
├── output/
│   ├── sl_land_listings.csv
│   └── STATS.md
├── for_sale_scraper.py
└── README.md
```

## Backend API

### `GET /api/listings`

Returns all CSV rows as a JSON array.

**Response:**
```json
[
  {
    "name": "Land For Sale",
    "type": "Mainland",
    "price": 430,
    "area": 512,
    "price_per_sqm": 0.84,
    "maturity": "General"
  }
]
```

### `GET /api/metadata`

Returns dataset summary for initializing the frontend.

**Response:**
```json
{
  "total_count": 3518,
  "scrape_date": "2026-04-09",
  "maturity_levels": ["General", "Moderate", "Adult"],
  "area_range": { "min": 512, "max": 65536 },
  "price_range": { "min": 200, "max": 999999999 },
  "price_per_sqm_range": { "min": 0.35, "max": 976562.5 }
}
```

### Implementation Details

- FastAPI with uvicorn
- Reads CSV via pandas on startup, stores as list of dicts in memory
- CORS enabled for local dev (frontend on different port)
- Serves from `land-for-sale/api/main.py`
- Dependencies: `fastapi`, `uvicorn[standard]`, `pandas`

## Frontend

### Tech Stack

- **React 18** via Vite
- **Recharts** for all chart types (React-native, composable, supports all needed chart types)
- **No UI framework** — custom CSS with dark theme (the mockup's color scheme: dark navy background, red/purple accents)

### Layout

The dashboard is a single-page app with three zones:

1. **Top bar:** App title, dataset info (listing count, scrape date), and a row of predefined quick-select view buttons
2. **Left sidebar (220px):** Filter controls panel
3. **Main content area:** Summary metrics row + chart grid

### Predefined Quick Views

Pill-shaped toggle buttons across the top. Clicking one sets the filters to match that view. Active view is highlighted. Views:

- All Listings (default)
- General / Moderate / Adult (maturity filters)
- Small (<1,024 m²) / Medium (1,025-4,096 m²) / Large (4,097+ m²)

These are shortcuts — they set the sidebar filters accordingly. The user can further refine after selecting a quick view.

### Filter Sidebar

All filters update every chart and metric simultaneously in real time.

**Maturity checkboxes:**
- General, Moderate, Adult — multi-select, all checked by default

**Area range slider:**
- Dual-handle range slider
- Min: 512, Max: 65,536 m²
- Displays current selection values

**Price range slider:**
- Dual-handle range slider
- Min/Max from dataset
- Displays current selection values

**Outlier Configuration (dedicated section):**
- IQR multiplier slider: range 0.5 to 5.0, step 0.1, default 1.5
- "Apply outlier to" dropdown: Price per m², Total Price, or Both
- Display: "Excluded: N listings (X%)" — updates in real time
- The outlier bounds are: [Q1 - k*IQR, Q3 + k*IQR] where k is the multiplier
- Outlier exclusion is applied AFTER maturity/area/price filters, so it operates on the filtered subset

### Summary Metrics Row

Five metric cards across the top of the main content area. All reflect the current filtered + outlier-excluded dataset:

1. **Listings** — count after all filters + outlier exclusion
2. **Median Price/m²** — the key pricing metric
3. **Average Price** — mean total price
4. **IQR (Price/m²)** — with Q1 and Q3 displayed below
5. **Average Area** — mean parcel size

### Charts

All charts operate on the filtered + outlier-excluded dataset.

#### 1. Price/m² Distribution (Histogram)
- X-axis: price per square meter bins
- Y-axis: listing count
- Bins auto-calculated based on data range
- Shows the shape of the pricing distribution

#### 2. Total Price Distribution (Histogram)
- X-axis: total price bins
- Y-axis: listing count
- Complementary to price/m² — shows absolute price spread

#### 3. Area vs Price/m² Scatter Plot
- X-axis: area (m²)
- Y-axis: price per m²
- Color-coded by maturity level (General=red, Moderate=purple, Adult=green)
- Reveals pricing trends by parcel size and maturity
- Tooltip on hover showing listing details

#### 4. Price/m² by Maturity (Box Plot)
- One box per maturity level
- Shows median, Q1, Q3, whiskers, and any remaining outliers as dots
- Direct visual comparison of pricing across maturity levels

#### 5. Price/m² by Size Category (Bar Chart)
- Six size categories matching the scraper's bins: 0-1024, 1025-4096, 4097-8129, 8130-16384, 16385-32768, 32769-65536
- Bar height = median or average price/m² for that category
- Reveals how pricing scales with parcel size

#### 6. Percentile Breakdown (Table)
- Rows: 10th, 25th (Q1), 50th (Median), 75th (Q3), 90th percentiles
- Columns: Price/m², Total Price
- Median row highlighted
- Compact statistical reference

### Data Flow

1. App mounts → fetches `/api/listings` and `/api/metadata`
2. Raw listings stored in state via `useListings` hook
3. `useFilters` hook manages filter state (maturity, area range, price range, outlier config, active quick view)
4. On any filter change, the pipeline runs:
   a. Filter by maturity level
   b. Filter by area range
   c. Filter by price range
   d. Compute IQR on the filtered set
   e. Exclude outliers based on IQR multiplier and selected field
   f. Compute statistics on the final dataset
5. `useStats` hook computes: count, mean, median, std dev, percentiles, IQR, Q1, Q3, histogram bins
6. All chart components receive the filtered+computed data as props and re-render

### Client-Side Statistics (`utils/statistics.js`)

Functions needed:
- `percentile(arr, p)` — returns the p-th percentile value
- `mean(arr)` — arithmetic mean
- `median(arr)` — 50th percentile
- `stdDev(arr)` — standard deviation
- `iqr(arr)` — returns { q1, q3, iqr }
- `histogram(arr, binCount?)` — returns array of { min, max, count } bins. Default bin count: Sturges' formula `ceil(log2(n) + 1)` (~12 bins for 3,500 items)

### Outlier Detection (`utils/outliers.js`)

- `detectOutliers(data, field, multiplier)` — returns { included: [...], excluded: [...], bounds: { lower, upper } }
- Uses IQR method: lower = Q1 - k*IQR, upper = Q3 + k*IQR
- `field` is either `price_per_sqm`, `price`, or both
- When "both" is selected, a listing is excluded if it's an outlier on either field

### Styling

- Dark theme matching the mockup: dark navy (#1a1a2e) background, card backgrounds (#16213e), accent red (#e94560), purple (#533483), green (#2b9348)
- Responsive but primarily designed for desktop (1200px+ viewport)
- CSS custom properties for theming
- No external CSS framework

## Error Handling

- If the API is unreachable, show a connection error banner with retry button
- If the CSV has no data, show an empty state message
- All filter controls have sensible defaults so the dashboard works immediately on load

## Testing Strategy

- **Statistics utilities:** Unit tests for percentile, mean, median, IQR, outlier detection — these are the correctness-critical functions
- **API:** Basic test that endpoints return expected shape
- **Components:** Manual visual verification during development

## Dependencies

### Backend (`api/requirements.txt`)
- fastapi
- uvicorn[standard]
- pandas

### Frontend (`frontend/package.json`)
- react, react-dom
- recharts
- (dev) vite, @vitejs/plugin-react
