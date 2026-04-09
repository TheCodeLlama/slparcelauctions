# Land Pricing Analytics Dashboard Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a full analytics dashboard for Second Life Mainland land pricing with a FastAPI backend serving CSV data and a React frontend with interactive charts, configurable IQR outlier exclusion, and filtering.

**Architecture:** Hybrid client-server. FastAPI reads the CSV once on startup and serves it as JSON via two endpoints. React frontend fetches all data on load and performs filtering, outlier detection, statistics, and charting entirely client-side. ~3,500 rows makes this trivially fast in the browser.

**Tech Stack:** Python (FastAPI, pandas, uvicorn), React 18 (Vite, Recharts), CSS custom properties for dark theme.

**Spec:** `docs/superpowers/specs/2026-04-09-land-pricing-frontend-design.md`

---

## File Map

### Backend — `land-for-sale/api/`
| File | Responsibility |
|------|---------------|
| `api/requirements.txt` | Python dependencies |
| `api/main.py` | FastAPI app: two endpoints (`/api/listings`, `/api/metadata`), reads CSV on startup |
| `api/test_api.py` | API endpoint tests |

### Frontend — `land-for-sale/frontend/`
| File | Responsibility |
|------|---------------|
| `frontend/package.json` | Dependencies (react, react-dom, recharts, @vitejs/plugin-react) |
| `frontend/vite.config.js` | Vite config with React plugin and API proxy |
| `frontend/index.html` | Entry HTML (update `#app` div, title, script src) |
| `frontend/src/main.jsx` | React entry point (renders App) |
| `frontend/src/App.jsx` | Root component: fetches data, holds filter state, orchestrates layout |
| `frontend/src/utils/statistics.js` | Pure functions: percentile, mean, median, stdDev, iqr, histogram |
| `frontend/src/utils/statistics.test.js` | Unit tests for statistics functions |
| `frontend/src/utils/outliers.js` | `detectOutliers(data, field, multiplier)` |
| `frontend/src/utils/outliers.test.js` | Unit tests for outlier detection |
| `frontend/src/hooks/useListings.js` | Fetch `/api/listings` + `/api/metadata` on mount |
| `frontend/src/hooks/useFilters.js` | Filter state management (maturity, area, price, outlier config, quick views) |
| `frontend/src/hooks/useStats.js` | Derives filtered data + computed stats from listings + filters |
| `frontend/src/components/Dashboard.jsx` | Layout shell: top bar, sidebar, main content grid |
| `frontend/src/components/QuickViews.jsx` | Predefined view pill buttons |
| `frontend/src/components/FilterSidebar.jsx` | Maturity checkboxes, range sliders, outlier config |
| `frontend/src/components/SummaryMetrics.jsx` | Five metric cards row |
| `frontend/src/components/ui/RangeSlider.jsx` | Dual-handle range slider component |
| `frontend/src/components/charts/PricePerSqmDistribution.jsx` | Histogram of price/m² |
| `frontend/src/components/charts/PriceDistribution.jsx` | Histogram of total price |
| `frontend/src/components/charts/ScatterPlot.jsx` | Area vs price/m², color by maturity |
| `frontend/src/components/charts/BoxPlotByMaturity.jsx` | Box plots comparing maturity levels |
| `frontend/src/components/charts/PriceBySizeBar.jsx` | Bar chart of price/m² by size category |
| `frontend/src/components/charts/PercentileTable.jsx` | Percentile breakdown table |
| `frontend/src/styles/index.css` | Dark theme styles, layout grid, component styles |

---

## Task 1: Backend API

**Files:**
- Create: `land-for-sale/api/requirements.txt`
- Create: `land-for-sale/api/main.py`
- Create: `land-for-sale/api/test_api.py`

- [ ] **Step 1: Create requirements.txt**

```
fastapi
uvicorn[standard]
pandas
httpx
pytest
```

- [ ] **Step 2: Create the FastAPI app**

Create `land-for-sale/api/main.py`:

```python
import os
from datetime import datetime
from pathlib import Path

import pandas as pd
from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

app = FastAPI(title="SL Land Analytics API")

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["*"],
    allow_headers=["*"],
)

CSV_PATH = Path(__file__).parent.parent / "output" / "sl_land_listings.csv"

listings = []
metadata = {}


@app.on_event("startup")
def load_data():
    global listings, metadata
    df = pd.read_csv(CSV_PATH)
    df["price"] = pd.to_numeric(df["price"], errors="coerce")
    df["area"] = pd.to_numeric(df["area"], errors="coerce")
    df["price_per_sqm"] = pd.to_numeric(df["price_per_sqm"], errors="coerce")
    df = df.dropna(subset=["price", "area", "price_per_sqm"])
    listings = df.to_dict(orient="records")
    metadata = {
        "total_count": len(listings),
        "scrape_date": datetime.fromtimestamp(
            os.path.getmtime(CSV_PATH)
        ).strftime("%Y-%m-%d"),
        "maturity_levels": sorted(df["maturity"].unique().tolist()),
        "area_range": {"min": float(df["area"].min()), "max": float(df["area"].max())},
        "price_range": {
            "min": float(df["price"].min()),
            "max": float(df["price"].max()),
        },
        "price_per_sqm_range": {
            "min": float(df["price_per_sqm"].min()),
            "max": float(df["price_per_sqm"].max()),
        },
    }


@app.get("/api/listings")
def get_listings():
    return listings


@app.get("/api/metadata")
def get_metadata():
    return metadata
```

- [ ] **Step 3: Write API tests**

Create `land-for-sale/api/test_api.py`:

```python
import pytest
from fastapi.testclient import TestClient
from main import app


@pytest.fixture
def client():
    return TestClient(app)


def test_listings_returns_list(client):
    resp = client.get("/api/listings")
    assert resp.status_code == 200
    data = resp.json()
    assert isinstance(data, list)
    assert len(data) > 0


def test_listing_shape(client):
    resp = client.get("/api/listings")
    item = resp.json()[0]
    assert "name" in item
    assert "type" in item
    assert "price" in item
    assert "area" in item
    assert "price_per_sqm" in item
    assert "maturity" in item
    assert isinstance(item["price"], (int, float))
    assert isinstance(item["area"], (int, float))
    assert isinstance(item["price_per_sqm"], float)


def test_metadata_shape(client):
    resp = client.get("/api/metadata")
    assert resp.status_code == 200
    data = resp.json()
    assert "total_count" in data
    assert "scrape_date" in data
    assert "maturity_levels" in data
    assert "area_range" in data
    assert "price_range" in data
    assert "price_per_sqm_range" in data
    assert isinstance(data["total_count"], int)
    assert data["total_count"] > 0
    assert isinstance(data["maturity_levels"], list)
    assert data["area_range"]["min"] <= data["area_range"]["max"]
```

- [ ] **Step 4: Install dependencies and run tests**

```bash
cd land-for-sale/api
pip install -r requirements.txt
pytest test_api.py -v
```

Expected: all 3 tests pass.

- [ ] **Step 5: Verify the API starts and serves data**

```bash
cd land-for-sale/api
uvicorn main:app --port 8000 &
curl http://localhost:8000/api/metadata
curl http://localhost:8000/api/listings | head -c 500
# Kill the server
kill %1
```

Expected: metadata returns JSON with `total_count` ~3518; listings returns a JSON array.

- [ ] **Step 6: Commit**

```bash
cd land-for-sale
git add api/
git commit -m "feat: add FastAPI backend serving land listing data as JSON"
```

---

## Task 2: Frontend Scaffolding — React + Vite Setup

**Files:**
- Modify: `land-for-sale/frontend/package.json`
- Create: `land-for-sale/frontend/vite.config.js`
- Modify: `land-for-sale/frontend/index.html`
- Create: `land-for-sale/frontend/src/main.jsx`
- Create: `land-for-sale/frontend/src/App.jsx`
- Create: `land-for-sale/frontend/src/styles/index.css`
- Delete: `land-for-sale/frontend/src/main.js`, `land-for-sale/frontend/src/counter.js`, `land-for-sale/frontend/src/style.css`

- [ ] **Step 1: Install React dependencies**

```bash
cd land-for-sale/frontend
npm install react react-dom recharts
npm install -D @vitejs/plugin-react vitest @testing-library/react @testing-library/jest-dom jsdom
```

- [ ] **Step 2: Create vite.config.js**

Create `land-for-sale/frontend/vite.config.js`:

```js
import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react()],
  server: {
    proxy: {
      '/api': 'http://localhost:8000',
    },
  },
  test: {
    environment: 'jsdom',
    setupFiles: [],
  },
});
```

- [ ] **Step 3: Update index.html**

Replace the full content of `land-for-sale/frontend/index.html`:

```html
<!doctype html>
<html lang="en">
  <head>
    <meta charset="UTF-8" />
    <link rel="icon" type="image/svg+xml" href="/favicon.svg" />
    <meta name="viewport" content="width=device-width, initial-scale=1.0" />
    <title>SL Land Analytics</title>
  </head>
  <body>
    <div id="root"></div>
    <script type="module" src="/src/main.jsx"></script>
  </body>
</html>
```

- [ ] **Step 4: Delete old boilerplate files**

```bash
cd land-for-sale/frontend
rm src/main.js src/counter.js src/style.css
```

- [ ] **Step 5: Create styles/index.css with dark theme**

Create `land-for-sale/frontend/src/styles/index.css`:

```css
:root {
  --bg-primary: #1a1a2e;
  --bg-card: #16213e;
  --bg-input: #0f3460;
  --border: #0f3460;
  --accent: #e94560;
  --purple: #533483;
  --green: #2b9348;
  --text-primary: #e0e0e0;
  --text-secondary: #aaaaaa;
  --text-muted: #888888;
  --text-dim: #666666;
}

*, *::before, *::after {
  box-sizing: border-box;
  margin: 0;
  padding: 0;
}

body {
  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
  background: var(--bg-primary);
  color: var(--text-primary);
  min-height: 100vh;
}

#root {
  min-height: 100vh;
}

/* Layout */
.dashboard {
  display: flex;
  flex-direction: column;
  min-height: 100vh;
}

.top-bar {
  background: var(--bg-card);
  padding: 12px 20px;
  display: flex;
  justify-content: space-between;
  align-items: center;
  border-bottom: 1px solid var(--border);
}

.top-bar__title {
  color: var(--accent);
  font-weight: bold;
  font-size: 16px;
}

.top-bar__info {
  color: var(--text-muted);
  font-size: 12px;
}

.quick-views {
  background: var(--bg-card);
  padding: 8px 20px;
  display: flex;
  gap: 8px;
  border-bottom: 1px solid var(--border);
  flex-wrap: wrap;
}

.quick-views__pill {
  background: var(--bg-input);
  color: var(--text-secondary);
  padding: 4px 12px;
  border-radius: 14px;
  font-size: 11px;
  border: none;
  cursor: pointer;
  transition: background 0.15s, color 0.15s;
}

.quick-views__pill--active {
  background: var(--accent);
  color: white;
}

.main-layout {
  display: flex;
  flex: 1;
}

/* Sidebar */
.sidebar {
  width: 240px;
  background: var(--bg-card);
  padding: 16px;
  border-right: 1px solid var(--border);
  flex-shrink: 0;
  overflow-y: auto;
  max-height: calc(100vh - 90px);
}

.sidebar__section-title {
  color: var(--accent);
  font-size: 11px;
  text-transform: uppercase;
  letter-spacing: 1px;
  margin-bottom: 12px;
}

.sidebar__label {
  color: var(--text-primary);
  font-size: 12px;
  margin-bottom: 6px;
}

.sidebar__divider {
  border: none;
  border-top: 1px solid var(--border);
  margin: 12px 0;
}

.sidebar__checkbox-group {
  display: flex;
  flex-direction: column;
  gap: 6px;
  margin-bottom: 16px;
}

.sidebar__checkbox-label {
  color: var(--text-secondary);
  font-size: 12px;
  display: flex;
  align-items: center;
  gap: 6px;
  cursor: pointer;
}

.sidebar__outlier-info {
  color: var(--text-muted);
  font-size: 10px;
  margin-top: 4px;
}

.sidebar__select {
  background: var(--bg-input);
  color: var(--text-primary);
  border: 1px solid var(--border);
  padding: 4px 8px;
  font-size: 11px;
  width: 100%;
  border-radius: 4px;
}

/* Main content */
.content {
  flex: 1;
  padding: 16px;
  overflow-y: auto;
  max-height: calc(100vh - 90px);
}

/* Summary metrics */
.metrics-row {
  display: grid;
  grid-template-columns: repeat(5, 1fr);
  gap: 12px;
  margin-bottom: 20px;
}

.metric-card {
  background: var(--bg-card);
  border-radius: 8px;
  padding: 14px;
  text-align: center;
}

.metric-card__label {
  color: var(--text-muted);
  font-size: 10px;
  text-transform: uppercase;
}

.metric-card__value {
  color: var(--accent);
  font-size: 22px;
  font-weight: bold;
  margin: 4px 0;
}

.metric-card__sub {
  color: var(--text-dim);
  font-size: 9px;
}

/* Chart grid */
.chart-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
}

.chart-card {
  background: var(--bg-card);
  border-radius: 8px;
  padding: 16px;
}

.chart-card__title {
  color: var(--text-primary);
  font-size: 13px;
  font-weight: 600;
  margin-bottom: 12px;
}

/* Percentile table */
.percentile-table {
  width: 100%;
  font-size: 12px;
  border-collapse: collapse;
}

.percentile-table th {
  text-align: left;
  padding: 6px 8px;
  color: var(--text-muted);
  border-bottom: 1px solid var(--border);
}

.percentile-table td {
  padding: 5px 8px;
  color: var(--text-secondary);
}

.percentile-table tr:nth-child(even) {
  background: rgba(15, 52, 96, 0.3);
}

.percentile-table tr.highlight td {
  color: var(--accent);
  font-weight: bold;
}

/* Range slider */
.range-slider {
  margin-bottom: 16px;
}

.range-slider__track {
  position: relative;
  height: 6px;
  background: var(--bg-input);
  border-radius: 3px;
  margin: 8px 0;
}

.range-slider__values {
  display: flex;
  justify-content: space-between;
  color: var(--text-muted);
  font-size: 10px;
}

.range-slider input[type="range"] {
  -webkit-appearance: none;
  appearance: none;
  width: 100%;
  height: 6px;
  background: transparent;
  pointer-events: none;
  position: absolute;
  top: 0;
  left: 0;
}

.range-slider input[type="range"]::-webkit-slider-thumb {
  -webkit-appearance: none;
  appearance: none;
  width: 14px;
  height: 14px;
  border-radius: 50%;
  background: var(--accent);
  cursor: pointer;
  pointer-events: auto;
}

/* IQR slider */
.iqr-slider {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 4px;
}

.iqr-slider input[type="range"] {
  flex: 1;
  -webkit-appearance: none;
  appearance: none;
  height: 6px;
  background: var(--bg-input);
  border-radius: 3px;
  outline: none;
}

.iqr-slider input[type="range"]::-webkit-slider-thumb {
  -webkit-appearance: none;
  appearance: none;
  width: 14px;
  height: 14px;
  border-radius: 50%;
  background: var(--accent);
  cursor: pointer;
}

.iqr-slider__value {
  color: var(--accent);
  font-size: 14px;
  font-weight: bold;
  min-width: 28px;
  text-align: right;
}

/* Error banner */
.error-banner {
  background: rgba(233, 69, 96, 0.15);
  border: 1px solid var(--accent);
  color: var(--accent);
  padding: 12px 20px;
  text-align: center;
  font-size: 14px;
}

.error-banner button {
  background: var(--accent);
  color: white;
  border: none;
  padding: 4px 12px;
  border-radius: 4px;
  cursor: pointer;
  margin-left: 12px;
  font-size: 12px;
}

/* Loading */
.loading {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 100vh;
  color: var(--text-muted);
  font-size: 16px;
}
```

- [ ] **Step 6: Create main.jsx entry point**

Create `land-for-sale/frontend/src/main.jsx`:

```jsx
import React from 'react';
import ReactDOM from 'react-dom/client';
import App from './App';
import './styles/index.css';

ReactDOM.createRoot(document.getElementById('root')).render(
  <React.StrictMode>
    <App />
  </React.StrictMode>
);
```

- [ ] **Step 7: Create placeholder App.jsx**

Create `land-for-sale/frontend/src/App.jsx`:

```jsx
import React from 'react';

export default function App() {
  return (
    <div className="loading">
      Loading SL Land Analytics...
    </div>
  );
}
```

- [ ] **Step 8: Verify the frontend starts**

```bash
cd land-for-sale/frontend
npm run dev
```

Expected: Vite dev server starts, browser shows "Loading SL Land Analytics..." on dark background.

- [ ] **Step 9: Commit**

```bash
cd land-for-sale
git add frontend/
git commit -m "feat: scaffold React frontend with Vite, dark theme CSS"
```

---

## Task 3: Statistics Utilities with Tests

**Files:**
- Create: `land-for-sale/frontend/src/utils/statistics.js`
- Create: `land-for-sale/frontend/src/utils/statistics.test.js`

- [ ] **Step 1: Write tests for statistics functions**

Create `land-for-sale/frontend/src/utils/statistics.test.js`:

```js
import { describe, it, expect } from 'vitest';
import { mean, median, percentile, stdDev, iqr, histogram } from './statistics';

describe('mean', () => {
  it('computes arithmetic mean', () => {
    expect(mean([1, 2, 3, 4, 5])).toBe(3);
  });

  it('returns NaN for empty array', () => {
    expect(mean([])).toBeNaN();
  });

  it('handles single value', () => {
    expect(mean([7])).toBe(7);
  });
});

describe('median', () => {
  it('returns middle value for odd-length array', () => {
    expect(median([3, 1, 2])).toBe(2);
  });

  it('returns average of two middle values for even-length', () => {
    expect(median([1, 2, 3, 4])).toBe(2.5);
  });

  it('returns NaN for empty array', () => {
    expect(median([])).toBeNaN();
  });
});

describe('percentile', () => {
  it('computes 25th percentile', () => {
    const data = [1, 2, 3, 4, 5, 6, 7, 8, 9, 10];
    expect(percentile(data, 25)).toBeCloseTo(3.25, 1);
  });

  it('computes 75th percentile', () => {
    const data = [1, 2, 3, 4, 5, 6, 7, 8, 9, 10];
    expect(percentile(data, 75)).toBeCloseTo(7.75, 1);
  });

  it('computes 50th percentile equal to median', () => {
    const data = [1, 2, 3, 4, 5];
    expect(percentile(data, 50)).toBe(median(data));
  });

  it('returns min at 0th percentile', () => {
    expect(percentile([5, 10, 15], 0)).toBe(5);
  });

  it('returns max at 100th percentile', () => {
    expect(percentile([5, 10, 15], 100)).toBe(15);
  });
});

describe('stdDev', () => {
  it('computes population standard deviation', () => {
    expect(stdDev([2, 4, 4, 4, 5, 5, 7, 9])).toBeCloseTo(2.0, 1);
  });

  it('returns 0 for uniform values', () => {
    expect(stdDev([5, 5, 5])).toBe(0);
  });
});

describe('iqr', () => {
  it('computes Q1, Q3, and IQR', () => {
    const data = [1, 2, 3, 4, 5, 6, 7, 8, 9, 10];
    const result = iqr(data);
    expect(result.q1).toBeCloseTo(3.25, 1);
    expect(result.q3).toBeCloseTo(7.75, 1);
    expect(result.iqr).toBeCloseTo(4.5, 1);
  });
});

describe('histogram', () => {
  it('returns the correct number of bins', () => {
    const data = [1, 2, 3, 4, 5, 6, 7, 8, 9, 10];
    const bins = histogram(data, 5);
    expect(bins).toHaveLength(5);
  });

  it('each bin has min, max, count', () => {
    const bins = histogram([1, 2, 3, 4, 5], 2);
    for (const bin of bins) {
      expect(bin).toHaveProperty('min');
      expect(bin).toHaveProperty('max');
      expect(bin).toHaveProperty('count');
    }
  });

  it('total count across bins equals data length', () => {
    const data = [1, 1, 2, 3, 5, 8, 13, 21];
    const bins = histogram(data, 4);
    const total = bins.reduce((sum, b) => sum + b.count, 0);
    expect(total).toBe(data.length);
  });

  it('uses Sturges formula for default bin count', () => {
    const data = Array.from({ length: 100 }, (_, i) => i);
    const bins = histogram(data);
    // Sturges: ceil(log2(100) + 1) = ceil(7.64) = 8
    expect(bins).toHaveLength(8);
  });
});
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
cd land-for-sale/frontend
npx vitest run src/utils/statistics.test.js
```

Expected: FAIL — module `./statistics` not found.

- [ ] **Step 3: Implement statistics.js**

Create `land-for-sale/frontend/src/utils/statistics.js`:

```js
export function mean(arr) {
  if (arr.length === 0) return NaN;
  return arr.reduce((sum, v) => sum + v, 0) / arr.length;
}

export function median(arr) {
  if (arr.length === 0) return NaN;
  const sorted = [...arr].sort((a, b) => a - b);
  const mid = Math.floor(sorted.length / 2);
  return sorted.length % 2 !== 0
    ? sorted[mid]
    : (sorted[mid - 1] + sorted[mid]) / 2;
}

export function percentile(arr, p) {
  if (arr.length === 0) return NaN;
  const sorted = [...arr].sort((a, b) => a - b);
  if (p <= 0) return sorted[0];
  if (p >= 100) return sorted[sorted.length - 1];
  const index = (p / 100) * (sorted.length - 1);
  const lower = Math.floor(index);
  const fraction = index - lower;
  if (lower + 1 >= sorted.length) return sorted[lower];
  return sorted[lower] + fraction * (sorted[lower + 1] - sorted[lower]);
}

export function stdDev(arr) {
  if (arr.length === 0) return NaN;
  const m = mean(arr);
  const squaredDiffs = arr.map(v => (v - m) ** 2);
  return Math.sqrt(mean(squaredDiffs));
}

export function iqr(arr) {
  const q1 = percentile(arr, 25);
  const q3 = percentile(arr, 75);
  return { q1, q3, iqr: q3 - q1 };
}

export function histogram(arr, binCount) {
  if (arr.length === 0) return [];
  if (binCount === undefined) {
    binCount = Math.ceil(Math.log2(arr.length) + 1);
  }
  const min = Math.min(...arr);
  const max = Math.max(...arr);
  const binWidth = (max - min) / binCount || 1;
  const bins = Array.from({ length: binCount }, (_, i) => ({
    min: min + i * binWidth,
    max: min + (i + 1) * binWidth,
    count: 0,
  }));
  for (const value of arr) {
    let idx = Math.floor((value - min) / binWidth);
    if (idx >= binCount) idx = binCount - 1;
    bins[idx].count++;
  }
  return bins;
}
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
cd land-for-sale/frontend
npx vitest run src/utils/statistics.test.js
```

Expected: all tests pass.

- [ ] **Step 5: Commit**

```bash
cd land-for-sale
git add frontend/src/utils/statistics.js frontend/src/utils/statistics.test.js
git commit -m "feat: add statistics utility functions with tests"
```

---

## Task 4: Outlier Detection with Tests

**Files:**
- Create: `land-for-sale/frontend/src/utils/outliers.js`
- Create: `land-for-sale/frontend/src/utils/outliers.test.js`

- [ ] **Step 1: Write tests for outlier detection**

Create `land-for-sale/frontend/src/utils/outliers.test.js`:

```js
import { describe, it, expect } from 'vitest';
import { detectOutliers } from './outliers';

const sampleData = [
  { price: 500, area: 512, price_per_sqm: 0.98 },
  { price: 1000, area: 512, price_per_sqm: 1.95 },
  { price: 2000, area: 1024, price_per_sqm: 1.95 },
  { price: 2500, area: 1024, price_per_sqm: 2.44 },
  { price: 3000, area: 1024, price_per_sqm: 2.93 },
  { price: 5000, area: 2048, price_per_sqm: 2.44 },
  { price: 8000, area: 2048, price_per_sqm: 3.91 },
  { price: 10000, area: 4096, price_per_sqm: 2.44 },
  { price: 500000, area: 512, price_per_sqm: 976.56 },  // outlier
];

describe('detectOutliers', () => {
  it('returns included and excluded arrays', () => {
    const result = detectOutliers(sampleData, 'price_per_sqm', 1.5);
    expect(result).toHaveProperty('included');
    expect(result).toHaveProperty('excluded');
    expect(result).toHaveProperty('bounds');
    expect(result.included.length + result.excluded.length).toBe(sampleData.length);
  });

  it('excludes the extreme outlier on price_per_sqm', () => {
    const result = detectOutliers(sampleData, 'price_per_sqm', 1.5);
    expect(result.excluded.length).toBeGreaterThanOrEqual(1);
    const excludedPrices = result.excluded.map(d => d.price_per_sqm);
    expect(excludedPrices).toContain(976.56);
  });

  it('includes normal values', () => {
    const result = detectOutliers(sampleData, 'price_per_sqm', 1.5);
    const includedPrices = result.included.map(d => d.price_per_sqm);
    expect(includedPrices).toContain(1.95);
    expect(includedPrices).toContain(2.44);
  });

  it('higher multiplier excludes fewer items', () => {
    const tight = detectOutliers(sampleData, 'price_per_sqm', 1.0);
    const loose = detectOutliers(sampleData, 'price_per_sqm', 5.0);
    expect(tight.excluded.length).toBeGreaterThanOrEqual(loose.excluded.length);
  });

  it('returns correct bounds', () => {
    const result = detectOutliers(sampleData, 'price_per_sqm', 1.5);
    expect(result.bounds.lower).toBeDefined();
    expect(result.bounds.upper).toBeDefined();
    expect(result.bounds.lower).toBeLessThan(result.bounds.upper);
  });

  it('handles "both" field by excluding on either dimension', () => {
    const result = detectOutliers(sampleData, 'both', 1.5);
    expect(result.excluded.length).toBeGreaterThanOrEqual(1);
  });

  it('returns all items included when multiplier is very large', () => {
    const result = detectOutliers(sampleData, 'price_per_sqm', 1000);
    expect(result.excluded.length).toBe(0);
    expect(result.included.length).toBe(sampleData.length);
  });

  it('handles empty data', () => {
    const result = detectOutliers([], 'price_per_sqm', 1.5);
    expect(result.included).toEqual([]);
    expect(result.excluded).toEqual([]);
  });
});
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
cd land-for-sale/frontend
npx vitest run src/utils/outliers.test.js
```

Expected: FAIL — module `./outliers` not found.

- [ ] **Step 3: Implement outliers.js**

Create `land-for-sale/frontend/src/utils/outliers.js`:

```js
import { iqr as computeIqr } from './statistics';

function boundsForField(data, field, multiplier) {
  const values = data.map(d => d[field]);
  const { q1, q3, iqr } = computeIqr(values);
  return {
    lower: q1 - multiplier * iqr,
    upper: q3 + multiplier * iqr,
  };
}

export function detectOutliers(data, field, multiplier) {
  if (data.length === 0) {
    return { included: [], excluded: [], bounds: { lower: 0, upper: 0 } };
  }

  if (field === 'both') {
    const priceBounds = boundsForField(data, 'price', multiplier);
    const sqmBounds = boundsForField(data, 'price_per_sqm', multiplier);
    const included = [];
    const excluded = [];
    for (const item of data) {
      const priceOut = item.price < priceBounds.lower || item.price > priceBounds.upper;
      const sqmOut = item.price_per_sqm < sqmBounds.lower || item.price_per_sqm > sqmBounds.upper;
      if (priceOut || sqmOut) {
        excluded.push(item);
      } else {
        included.push(item);
      }
    }
    return { included, excluded, bounds: sqmBounds };
  }

  const bounds = boundsForField(data, field, multiplier);
  const included = [];
  const excluded = [];
  for (const item of data) {
    const value = item[field];
    if (value < bounds.lower || value > bounds.upper) {
      excluded.push(item);
    } else {
      included.push(item);
    }
  }
  return { included, excluded, bounds };
}
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
cd land-for-sale/frontend
npx vitest run src/utils/outliers.test.js
```

Expected: all tests pass.

- [ ] **Step 5: Commit**

```bash
cd land-for-sale
git add frontend/src/utils/outliers.js frontend/src/utils/outliers.test.js
git commit -m "feat: add IQR-based outlier detection with tests"
```

---

## Task 5: Data Hooks — useListings, useFilters, useStats

**Files:**
- Create: `land-for-sale/frontend/src/hooks/useListings.js`
- Create: `land-for-sale/frontend/src/hooks/useFilters.js`
- Create: `land-for-sale/frontend/src/hooks/useStats.js`

- [ ] **Step 1: Create useListings hook**

Create `land-for-sale/frontend/src/hooks/useListings.js`:

```js
import { useState, useEffect } from 'react';

export function useListings() {
  const [listings, setListings] = useState([]);
  const [metadata, setMetadata] = useState(null);
  const [error, setError] = useState(null);
  const [loading, setLoading] = useState(true);

  const fetchData = async () => {
    setLoading(true);
    setError(null);
    try {
      const [listingsRes, metadataRes] = await Promise.all([
        fetch('/api/listings'),
        fetch('/api/metadata'),
      ]);
      if (!listingsRes.ok || !metadataRes.ok) {
        throw new Error('API request failed');
      }
      const listingsData = await listingsRes.json();
      const metadataData = await metadataRes.json();
      setListings(listingsData);
      setMetadata(metadataData);
    } catch (err) {
      setError(err.message);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    fetchData();
  }, []);

  return { listings, metadata, loading, error, retry: fetchData };
}
```

- [ ] **Step 2: Create useFilters hook**

Create `land-for-sale/frontend/src/hooks/useFilters.js`:

```js
import { useState, useCallback } from 'react';

const SIZE_PRESETS = {
  small: { min: 0, max: 1024 },
  medium: { min: 1025, max: 4096 },
  large: { min: 4097, max: 65536 },
};

const DEFAULT_FILTERS = {
  maturity: { General: true, Moderate: true, Adult: true },
  areaRange: { min: 0, max: 65536 },
  priceRange: { min: 0, max: Infinity },
  outlierMultiplier: 1.5,
  outlierField: 'price_per_sqm',
  activeView: 'all',
};

export function useFilters(metadata) {
  const [filters, setFilters] = useState(DEFAULT_FILTERS);

  const initFromMetadata = useCallback((meta) => {
    if (!meta) return;
    setFilters(prev => ({
      ...prev,
      areaRange: { min: meta.area_range.min, max: meta.area_range.max },
      priceRange: { min: meta.price_range.min, max: meta.price_range.max },
    }));
  }, []);

  const setMaturity = useCallback((level, checked) => {
    setFilters(prev => ({
      ...prev,
      maturity: { ...prev.maturity, [level]: checked },
      activeView: 'custom',
    }));
  }, []);

  const setAreaRange = useCallback((min, max) => {
    setFilters(prev => ({
      ...prev,
      areaRange: { min, max },
      activeView: 'custom',
    }));
  }, []);

  const setPriceRange = useCallback((min, max) => {
    setFilters(prev => ({
      ...prev,
      priceRange: { min, max },
      activeView: 'custom',
    }));
  }, []);

  const setOutlierMultiplier = useCallback((multiplier) => {
    setFilters(prev => ({ ...prev, outlierMultiplier: multiplier }));
  }, []);

  const setOutlierField = useCallback((field) => {
    setFilters(prev => ({ ...prev, outlierField: field }));
  }, []);

  const applyQuickView = useCallback((view, meta) => {
    if (!meta) return;
    const base = {
      ...DEFAULT_FILTERS,
      areaRange: { min: meta.area_range.min, max: meta.area_range.max },
      priceRange: { min: meta.price_range.min, max: meta.price_range.max },
      outlierMultiplier: filters.outlierMultiplier,
      outlierField: filters.outlierField,
      activeView: view,
    };

    if (view === 'all') {
      setFilters(base);
    } else if (['General', 'Moderate', 'Adult'].includes(view)) {
      setFilters({
        ...base,
        maturity: { General: view === 'General', Moderate: view === 'Moderate', Adult: view === 'Adult' },
      });
    } else if (SIZE_PRESETS[view]) {
      setFilters({
        ...base,
        areaRange: SIZE_PRESETS[view],
      });
    }
  }, [filters.outlierMultiplier, filters.outlierField]);

  return {
    filters,
    setMaturity,
    setAreaRange,
    setPriceRange,
    setOutlierMultiplier,
    setOutlierField,
    applyQuickView,
    initFromMetadata,
  };
}
```

- [ ] **Step 3: Create useStats hook**

Create `land-for-sale/frontend/src/hooks/useStats.js`:

```js
import { useMemo } from 'react';
import { mean, median, percentile, iqr, histogram } from '../utils/statistics';
import { detectOutliers } from '../utils/outliers';

const SIZE_CATEGORIES = [
  { label: '0-1,024', min: 0, max: 1024 },
  { label: '1,025-4,096', min: 1025, max: 4096 },
  { label: '4,097-8,129', min: 4097, max: 8129 },
  { label: '8,130-16,384', min: 8130, max: 16384 },
  { label: '16,385-32,768', min: 16385, max: 32768 },
  { label: '32,769-65,536', min: 32769, max: 65536 },
];

export function useStats(listings, filters) {
  return useMemo(() => {
    if (!listings || listings.length === 0) {
      return { filtered: [], stats: null, outlierInfo: null };
    }

    // Step 1: Apply maturity filter
    let data = listings.filter(d => filters.maturity[d.maturity]);

    // Step 2: Apply area range filter
    data = data.filter(d => d.area >= filters.areaRange.min && d.area <= filters.areaRange.max);

    // Step 3: Apply price range filter
    data = data.filter(d => d.price >= filters.priceRange.min && d.price <= filters.priceRange.max);

    // Step 4: Apply outlier exclusion
    const outlierResult = detectOutliers(data, filters.outlierField, filters.outlierMultiplier);
    const filtered = outlierResult.included;

    if (filtered.length === 0) {
      return {
        filtered: [],
        stats: null,
        outlierInfo: {
          excludedCount: outlierResult.excluded.length,
          excludedPct: data.length > 0 ? (outlierResult.excluded.length / data.length) * 100 : 0,
          bounds: outlierResult.bounds,
        },
      };
    }

    // Step 5: Compute statistics
    const prices = filtered.map(d => d.price);
    const sqmPrices = filtered.map(d => d.price_per_sqm);
    const areas = filtered.map(d => d.area);
    const iqrResult = iqr(sqmPrices);

    const stats = {
      count: filtered.length,
      medianPricePerSqm: median(sqmPrices),
      avgPrice: mean(prices),
      avgArea: mean(areas),
      iqr: iqrResult,
      percentiles: {
        p10: { price: percentile(prices, 10), sqm: percentile(sqmPrices, 10) },
        p25: { price: percentile(prices, 25), sqm: percentile(sqmPrices, 25) },
        p50: { price: percentile(prices, 50), sqm: percentile(sqmPrices, 50) },
        p75: { price: percentile(prices, 75), sqm: percentile(sqmPrices, 75) },
        p90: { price: percentile(prices, 90), sqm: percentile(sqmPrices, 90) },
      },
      pricePerSqmHistogram: histogram(sqmPrices),
      priceHistogram: histogram(prices),
      byMaturity: ['General', 'Moderate', 'Adult'].map(level => {
        const subset = filtered.filter(d => d.maturity === level);
        if (subset.length === 0) return { level, count: 0 };
        const vals = subset.map(d => d.price_per_sqm);
        const iqrRes = iqr(vals);
        return {
          level,
          count: subset.length,
          median: median(vals),
          q1: iqrRes.q1,
          q3: iqrRes.q3,
          min: Math.min(...vals),
          max: Math.max(...vals),
        };
      }),
      bySizeCategory: SIZE_CATEGORIES.map(cat => {
        const subset = filtered.filter(d => d.area >= cat.min && d.area <= cat.max);
        if (subset.length === 0) return { label: cat.label, count: 0, medianSqm: 0, avgSqm: 0 };
        const vals = subset.map(d => d.price_per_sqm);
        return {
          label: cat.label,
          count: subset.length,
          medianSqm: median(vals),
          avgSqm: mean(vals),
        };
      }),
    };

    return {
      filtered,
      stats,
      outlierInfo: {
        excludedCount: outlierResult.excluded.length,
        excludedPct: data.length > 0 ? (outlierResult.excluded.length / data.length) * 100 : 0,
        bounds: outlierResult.bounds,
      },
    };
  }, [listings, filters]);
}
```

- [ ] **Step 4: Commit**

```bash
cd land-for-sale
git add frontend/src/hooks/
git commit -m "feat: add data hooks for listings, filters, and stats"
```

---

## Task 6: Dashboard Layout Shell + QuickViews + FilterSidebar + SummaryMetrics

**Files:**
- Create: `land-for-sale/frontend/src/components/Dashboard.jsx`
- Create: `land-for-sale/frontend/src/components/QuickViews.jsx`
- Create: `land-for-sale/frontend/src/components/FilterSidebar.jsx`
- Create: `land-for-sale/frontend/src/components/SummaryMetrics.jsx`
- Create: `land-for-sale/frontend/src/components/ui/RangeSlider.jsx`
- Modify: `land-for-sale/frontend/src/App.jsx`

- [ ] **Step 1: Create RangeSlider component**

Create `land-for-sale/frontend/src/components/ui/RangeSlider.jsx`:

```jsx
import React from 'react';

export default function RangeSlider({ label, min, max, value, onChange, formatValue }) {
  const fmt = formatValue || (v => v.toLocaleString());

  return (
    <div className="range-slider">
      <div className="sidebar__label">{label}</div>
      <div className="range-slider__track">
        <input
          type="range"
          min={min}
          max={max}
          value={value[0]}
          onChange={e => onChange([Number(e.target.value), value[1]])}
        />
        <input
          type="range"
          min={min}
          max={max}
          value={value[1]}
          onChange={e => onChange([value[0], Number(e.target.value)])}
        />
      </div>
      <div className="range-slider__values">
        <span>{fmt(value[0])}</span>
        <span>{fmt(value[1])}</span>
      </div>
    </div>
  );
}
```

- [ ] **Step 2: Create QuickViews component**

Create `land-for-sale/frontend/src/components/QuickViews.jsx`:

```jsx
import React from 'react';

const VIEWS = [
  { id: 'all', label: 'All Listings' },
  { id: 'General', label: 'General' },
  { id: 'Moderate', label: 'Moderate' },
  { id: 'Adult', label: 'Adult' },
  { id: 'small', label: 'Small (<1,024 m\u00B2)' },
  { id: 'medium', label: 'Medium (1,025-4,096 m\u00B2)' },
  { id: 'large', label: 'Large (4,097+ m\u00B2)' },
];

export default function QuickViews({ activeView, onSelect }) {
  return (
    <div className="quick-views">
      {VIEWS.map(view => (
        <button
          key={view.id}
          className={`quick-views__pill ${activeView === view.id ? 'quick-views__pill--active' : ''}`}
          onClick={() => onSelect(view.id)}
        >
          {view.label}
        </button>
      ))}
    </div>
  );
}
```

- [ ] **Step 3: Create FilterSidebar component**

Create `land-for-sale/frontend/src/components/FilterSidebar.jsx`:

```jsx
import React from 'react';
import RangeSlider from './ui/RangeSlider';

export default function FilterSidebar({ filters, metadata, outlierInfo, actions }) {
  if (!metadata) return null;

  return (
    <aside className="sidebar">
      <div className="sidebar__section-title">Filters</div>

      <div className="sidebar__label">Maturity</div>
      <div className="sidebar__checkbox-group">
        {['General', 'Moderate', 'Adult'].map(level => (
          <label key={level} className="sidebar__checkbox-label">
            <input
              type="checkbox"
              checked={filters.maturity[level]}
              onChange={e => actions.setMaturity(level, e.target.checked)}
            />
            {level}
          </label>
        ))}
      </div>

      <RangeSlider
        label="Area (m\u00B2)"
        min={metadata.area_range.min}
        max={metadata.area_range.max}
        value={[filters.areaRange.min, filters.areaRange.max]}
        onChange={([min, max]) => actions.setAreaRange(min, max)}
        formatValue={v => v.toLocaleString() + ' m\u00B2'}
      />

      <RangeSlider
        label="Price (L$)"
        min={metadata.price_range.min}
        max={metadata.price_range.max}
        value={[filters.priceRange.min, filters.priceRange.max]}
        onChange={([min, max]) => actions.setPriceRange(min, max)}
        formatValue={v => 'L$ ' + v.toLocaleString()}
      />

      <hr className="sidebar__divider" />

      <div className="sidebar__section-title">Outlier Config</div>

      <div className="sidebar__label">IQR Multiplier</div>
      <div className="iqr-slider">
        <input
          type="range"
          min="0.5"
          max="5.0"
          step="0.1"
          value={filters.outlierMultiplier}
          onChange={e => actions.setOutlierMultiplier(Number(e.target.value))}
        />
        <span className="iqr-slider__value">{filters.outlierMultiplier.toFixed(1)}</span>
      </div>

      <div className="sidebar__label" style={{ marginTop: '8px' }}>Apply outlier to</div>
      <select
        className="sidebar__select"
        value={filters.outlierField}
        onChange={e => actions.setOutlierField(e.target.value)}
      >
        <option value="price_per_sqm">Price per m&sup2;</option>
        <option value="price">Total Price</option>
        <option value="both">Both</option>
      </select>

      {outlierInfo && (
        <div className="sidebar__outlier-info">
          Excluded: {outlierInfo.excludedCount} listings ({outlierInfo.excludedPct.toFixed(1)}%)
        </div>
      )}
    </aside>
  );
}
```

- [ ] **Step 4: Create SummaryMetrics component**

Create `land-for-sale/frontend/src/components/SummaryMetrics.jsx`:

```jsx
import React from 'react';

function fmt(val, prefix = '') {
  if (val === undefined || val === null || isNaN(val)) return '—';
  return prefix + val.toLocaleString(undefined, { maximumFractionDigits: 2 });
}

export default function SummaryMetrics({ stats }) {
  if (!stats) return null;

  return (
    <div className="metrics-row">
      <div className="metric-card">
        <div className="metric-card__label">Listings</div>
        <div className="metric-card__value">{stats.count.toLocaleString()}</div>
        <div className="metric-card__sub">after outlier exclusion</div>
      </div>
      <div className="metric-card">
        <div className="metric-card__label">Median Price/m&sup2;</div>
        <div className="metric-card__value">{fmt(stats.medianPricePerSqm, 'L$ ')}</div>
        <div className="metric-card__sub">filtered dataset</div>
      </div>
      <div className="metric-card">
        <div className="metric-card__label">Avg Price</div>
        <div className="metric-card__value">{fmt(stats.avgPrice, 'L$ ')}</div>
        <div className="metric-card__sub">filtered dataset</div>
      </div>
      <div className="metric-card">
        <div className="metric-card__label">IQR (Price/m&sup2;)</div>
        <div className="metric-card__value">{fmt(stats.iqr.iqr, 'L$ ')}</div>
        <div className="metric-card__sub">Q1: {fmt(stats.iqr.q1)} — Q3: {fmt(stats.iqr.q3)}</div>
      </div>
      <div className="metric-card">
        <div className="metric-card__label">Avg Area</div>
        <div className="metric-card__value">{fmt(stats.avgArea)} m&sup2;</div>
        <div className="metric-card__sub">filtered dataset</div>
      </div>
    </div>
  );
}
```

- [ ] **Step 5: Create Dashboard layout shell**

Create `land-for-sale/frontend/src/components/Dashboard.jsx`:

```jsx
import React from 'react';
import QuickViews from './QuickViews';
import FilterSidebar from './FilterSidebar';
import SummaryMetrics from './SummaryMetrics';

export default function Dashboard({ metadata, filters, stats, outlierInfo, filterActions, children }) {
  return (
    <div className="dashboard">
      <div className="top-bar">
        <div className="top-bar__title">SL Land Analytics</div>
        <div className="top-bar__info">
          {metadata
            ? `${metadata.total_count.toLocaleString()} listings \u2022 Last scraped: ${metadata.scrape_date}`
            : 'Loading...'}
        </div>
      </div>

      <QuickViews
        activeView={filters.activeView}
        onSelect={view => filterActions.applyQuickView(view, metadata)}
      />

      <div className="main-layout">
        <FilterSidebar
          filters={filters}
          metadata={metadata}
          outlierInfo={outlierInfo}
          actions={filterActions}
        />
        <main className="content">
          <SummaryMetrics stats={stats} />
          <div className="chart-grid">
            {children}
          </div>
        </main>
      </div>
    </div>
  );
}
```

- [ ] **Step 6: Wire up App.jsx**

Replace `land-for-sale/frontend/src/App.jsx`:

```jsx
import React, { useEffect } from 'react';
import { useListings } from './hooks/useListings';
import { useFilters } from './hooks/useFilters';
import { useStats } from './hooks/useStats';
import Dashboard from './components/Dashboard';

export default function App() {
  const { listings, metadata, loading, error, retry } = useListings();
  const { filters, setMaturity, setAreaRange, setPriceRange, setOutlierMultiplier, setOutlierField, applyQuickView, initFromMetadata } = useFilters();
  const { filtered, stats, outlierInfo } = useStats(listings, filters);

  useEffect(() => {
    if (metadata) initFromMetadata(metadata);
  }, [metadata, initFromMetadata]);

  if (error) {
    return (
      <div className="error-banner">
        Failed to load data: {error}
        <button onClick={retry}>Retry</button>
      </div>
    );
  }

  if (loading) {
    return <div className="loading">Loading SL Land Analytics...</div>;
  }

  const filterActions = {
    setMaturity,
    setAreaRange,
    setPriceRange,
    setOutlierMultiplier,
    setOutlierField,
    applyQuickView,
  };

  return (
    <Dashboard
      metadata={metadata}
      filters={filters}
      stats={stats}
      outlierInfo={outlierInfo}
      filterActions={filterActions}
    >
      {/* Charts will be added in Task 7 */}
      <div className="chart-card">
        <div className="chart-card__title">Charts coming next...</div>
      </div>
    </Dashboard>
  );
}
```

- [ ] **Step 7: Verify the dashboard renders**

Start the API and frontend:

```bash
# Terminal 1
cd land-for-sale/api && uvicorn main:app --port 8000

# Terminal 2
cd land-for-sale/frontend && npm run dev
```

Expected: Dashboard loads with the top bar (showing listing count and scrape date), quick view pills, filter sidebar with working controls, and five summary metric cards all showing computed values.

- [ ] **Step 8: Commit**

```bash
cd land-for-sale
git add frontend/src/components/ frontend/src/App.jsx
git commit -m "feat: add dashboard layout, filters, quick views, and summary metrics"
```

---

## Task 7: All Chart Components

**Files:**
- Create: `land-for-sale/frontend/src/components/charts/PricePerSqmDistribution.jsx`
- Create: `land-for-sale/frontend/src/components/charts/PriceDistribution.jsx`
- Create: `land-for-sale/frontend/src/components/charts/ScatterPlot.jsx`
- Create: `land-for-sale/frontend/src/components/charts/BoxPlotByMaturity.jsx`
- Create: `land-for-sale/frontend/src/components/charts/PriceBySizeBar.jsx`
- Create: `land-for-sale/frontend/src/components/charts/PercentileTable.jsx`
- Modify: `land-for-sale/frontend/src/App.jsx`

- [ ] **Step 1: Create PricePerSqmDistribution histogram**

Create `land-for-sale/frontend/src/components/charts/PricePerSqmDistribution.jsx`:

```jsx
import React from 'react';
import { BarChart, Bar, XAxis, YAxis, Tooltip, ResponsiveContainer } from 'recharts';

export default function PricePerSqmDistribution({ histogramData }) {
  if (!histogramData || histogramData.length === 0) return null;

  const chartData = histogramData.map(bin => ({
    range: `${bin.min.toFixed(1)}-${bin.max.toFixed(1)}`,
    count: bin.count,
  }));

  return (
    <div className="chart-card">
      <div className="chart-card__title">Price/m² Distribution</div>
      <ResponsiveContainer width="100%" height={200}>
        <BarChart data={chartData}>
          <XAxis dataKey="range" tick={{ fontSize: 9, fill: '#888' }} interval="preserveStartEnd" />
          <YAxis tick={{ fontSize: 10, fill: '#888' }} />
          <Tooltip
            contentStyle={{ background: '#16213e', border: '1px solid #0f3460', color: '#e0e0e0', fontSize: 12 }}
          />
          <Bar dataKey="count" fill="#e94560" radius={[2, 2, 0, 0]} />
        </BarChart>
      </ResponsiveContainer>
    </div>
  );
}
```

- [ ] **Step 2: Create PriceDistribution histogram**

Create `land-for-sale/frontend/src/components/charts/PriceDistribution.jsx`:

```jsx
import React from 'react';
import { BarChart, Bar, XAxis, YAxis, Tooltip, ResponsiveContainer } from 'recharts';

function formatPrice(val) {
  if (val >= 1000000) return `${(val / 1000000).toFixed(1)}M`;
  if (val >= 1000) return `${(val / 1000).toFixed(0)}K`;
  return val.toFixed(0);
}

export default function PriceDistribution({ histogramData }) {
  if (!histogramData || histogramData.length === 0) return null;

  const chartData = histogramData.map(bin => ({
    range: `${formatPrice(bin.min)}-${formatPrice(bin.max)}`,
    count: bin.count,
  }));

  return (
    <div className="chart-card">
      <div className="chart-card__title">Total Price Distribution</div>
      <ResponsiveContainer width="100%" height={200}>
        <BarChart data={chartData}>
          <XAxis dataKey="range" tick={{ fontSize: 9, fill: '#888' }} interval="preserveStartEnd" />
          <YAxis tick={{ fontSize: 10, fill: '#888' }} />
          <Tooltip
            contentStyle={{ background: '#16213e', border: '1px solid #0f3460', color: '#e0e0e0', fontSize: 12 }}
          />
          <Bar dataKey="count" fill="#533483" radius={[2, 2, 0, 0]} />
        </BarChart>
      </ResponsiveContainer>
    </div>
  );
}
```

- [ ] **Step 3: Create ScatterPlot**

Create `land-for-sale/frontend/src/components/charts/ScatterPlot.jsx`:

```jsx
import React from 'react';
import { ScatterChart, Scatter, XAxis, YAxis, Tooltip, ResponsiveContainer, Legend } from 'recharts';

const MATURITY_COLORS = {
  General: '#e94560',
  Moderate: '#533483',
  Adult: '#2b9348',
};

function CustomTooltip({ active, payload }) {
  if (!active || !payload || !payload[0]) return null;
  const d = payload[0].payload;
  return (
    <div style={{ background: '#16213e', border: '1px solid #0f3460', padding: '8px', borderRadius: 4, fontSize: 11, color: '#e0e0e0' }}>
      <div><strong>{d.name}</strong></div>
      <div>Area: {d.area.toLocaleString()} m²</div>
      <div>Price/m²: L$ {d.price_per_sqm.toFixed(2)}</div>
      <div>Price: L$ {d.price.toLocaleString()}</div>
      <div>Maturity: {d.maturity}</div>
    </div>
  );
}

export default function ScatterPlotChart({ data }) {
  if (!data || data.length === 0) return null;

  const byMaturity = {};
  for (const d of data) {
    if (!byMaturity[d.maturity]) byMaturity[d.maturity] = [];
    byMaturity[d.maturity].push(d);
  }

  return (
    <div className="chart-card">
      <div className="chart-card__title">Area vs Price/m²</div>
      <ResponsiveContainer width="100%" height={250}>
        <ScatterChart>
          <XAxis
            dataKey="area"
            name="Area"
            tick={{ fontSize: 10, fill: '#888' }}
            tickFormatter={v => v.toLocaleString()}
          />
          <YAxis
            dataKey="price_per_sqm"
            name="Price/m²"
            tick={{ fontSize: 10, fill: '#888' }}
          />
          <Tooltip content={<CustomTooltip />} />
          <Legend wrapperStyle={{ fontSize: 11 }} />
          {Object.entries(byMaturity).map(([level, items]) => (
            <Scatter
              key={level}
              name={level}
              data={items}
              fill={MATURITY_COLORS[level] || '#888'}
              opacity={0.7}
            />
          ))}
        </ScatterChart>
      </ResponsiveContainer>
    </div>
  );
}
```

- [ ] **Step 4: Create BoxPlotByMaturity**

Recharts doesn't have a native box plot, so we'll build it with a composite `BarChart` using error bars and custom shapes.

Create `land-for-sale/frontend/src/components/charts/BoxPlotByMaturity.jsx`:

```jsx
import React from 'react';
import { BarChart, Bar, XAxis, YAxis, Tooltip, ResponsiveContainer, Cell, ErrorBar } from 'recharts';

const COLORS = { General: '#e94560', Moderate: '#533483', Adult: '#2b9348' };

function BoxTooltip({ active, payload }) {
  if (!active || !payload || !payload[0]) return null;
  const d = payload[0].payload;
  if (d.count === 0) return null;
  return (
    <div style={{ background: '#16213e', border: '1px solid #0f3460', padding: '8px', borderRadius: 4, fontSize: 11, color: '#e0e0e0' }}>
      <div><strong>{d.level}</strong> ({d.count} listings)</div>
      <div>Min: L$ {d.min.toFixed(2)}/m²</div>
      <div>Q1: L$ {d.q1.toFixed(2)}/m²</div>
      <div>Median: L$ {d.median.toFixed(2)}/m²</div>
      <div>Q3: L$ {d.q3.toFixed(2)}/m²</div>
      <div>Max: L$ {d.max.toFixed(2)}/m²</div>
    </div>
  );
}

export default function BoxPlotByMaturity({ byMaturity }) {
  if (!byMaturity) return null;
  const data = byMaturity.filter(d => d.count > 0).map(d => ({
    ...d,
    boxBase: d.q1,
    boxHeight: d.q3 - d.q1,
    whiskerDown: d.q1 - d.min,
    whiskerUp: d.max - d.q3,
  }));

  if (data.length === 0) return null;

  return (
    <div className="chart-card">
      <div className="chart-card__title">Price/m² by Maturity</div>
      <ResponsiveContainer width="100%" height={250}>
        <BarChart data={data} barSize={50}>
          <XAxis dataKey="level" tick={{ fontSize: 11, fill: '#aaa' }} />
          <YAxis tick={{ fontSize: 10, fill: '#888' }} />
          <Tooltip content={<BoxTooltip />} />
          <Bar dataKey="boxBase" stackId="box" fill="transparent" />
          <Bar dataKey="boxHeight" stackId="box">
            {data.map(entry => (
              <Cell key={entry.level} fill={COLORS[entry.level] || '#888'} fillOpacity={0.4} stroke={COLORS[entry.level] || '#888'} strokeWidth={2} />
            ))}
            <ErrorBar dataKey="whiskerUp" direction="y" width={20} stroke="#aaa" />
          </Bar>
        </BarChart>
      </ResponsiveContainer>
      <div style={{ textAlign: 'center', fontSize: 10, color: '#888', marginTop: 4 }}>
        {byMaturity.filter(d => d.count > 0).map(d => (
          <span key={d.level} style={{ marginRight: 16 }}>
            {d.level}: median L$ {d.median.toFixed(2)}/m²
          </span>
        ))}
      </div>
    </div>
  );
}
```

- [ ] **Step 5: Create PriceBySizeBar chart**

Create `land-for-sale/frontend/src/components/charts/PriceBySizeBar.jsx`:

```jsx
import React from 'react';
import { BarChart, Bar, XAxis, YAxis, Tooltip, ResponsiveContainer } from 'recharts';

export default function PriceBySizeBar({ bySizeCategory }) {
  if (!bySizeCategory) return null;
  const data = bySizeCategory.filter(d => d.count > 0);
  if (data.length === 0) return null;

  return (
    <div className="chart-card">
      <div className="chart-card__title">Median Price/m² by Size Category</div>
      <ResponsiveContainer width="100%" height={200}>
        <BarChart data={data}>
          <XAxis dataKey="label" tick={{ fontSize: 9, fill: '#888' }} />
          <YAxis tick={{ fontSize: 10, fill: '#888' }} />
          <Tooltip
            contentStyle={{ background: '#16213e', border: '1px solid #0f3460', color: '#e0e0e0', fontSize: 12 }}
            formatter={(value) => [`L$ ${value.toFixed(2)}/m²`, 'Median']}
          />
          <Bar dataKey="medianSqm" fill="#e94560" radius={[4, 4, 0, 0]} />
        </BarChart>
      </ResponsiveContainer>
    </div>
  );
}
```

- [ ] **Step 6: Create PercentileTable**

Create `land-for-sale/frontend/src/components/charts/PercentileTable.jsx`:

```jsx
import React from 'react';

function fmt(val) {
  if (val === undefined || val === null || isNaN(val)) return '—';
  return 'L$ ' + val.toLocaleString(undefined, { maximumFractionDigits: 2 });
}

const ROWS = [
  { key: 'p10', label: '10th' },
  { key: 'p25', label: '25th (Q1)' },
  { key: 'p50', label: '50th (Median)', highlight: true },
  { key: 'p75', label: '75th (Q3)' },
  { key: 'p90', label: '90th' },
];

export default function PercentileTable({ percentiles }) {
  if (!percentiles) return null;

  return (
    <div className="chart-card">
      <div className="chart-card__title">Percentile Breakdown</div>
      <table className="percentile-table">
        <thead>
          <tr>
            <th>Percentile</th>
            <th style={{ textAlign: 'right' }}>Price/m²</th>
            <th style={{ textAlign: 'right' }}>Total Price</th>
          </tr>
        </thead>
        <tbody>
          {ROWS.map(row => (
            <tr key={row.key} className={row.highlight ? 'highlight' : ''}>
              <td>{row.label}</td>
              <td style={{ textAlign: 'right' }}>{fmt(percentiles[row.key]?.sqm)}</td>
              <td style={{ textAlign: 'right' }}>{fmt(percentiles[row.key]?.price)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
```

- [ ] **Step 7: Wire all charts into App.jsx**

Replace the placeholder content in `land-for-sale/frontend/src/App.jsx`. The `Dashboard` children section becomes:

```jsx
import React, { useEffect } from 'react';
import { useListings } from './hooks/useListings';
import { useFilters } from './hooks/useFilters';
import { useStats } from './hooks/useStats';
import Dashboard from './components/Dashboard';
import PricePerSqmDistribution from './components/charts/PricePerSqmDistribution';
import PriceDistribution from './components/charts/PriceDistribution';
import ScatterPlotChart from './components/charts/ScatterPlot';
import BoxPlotByMaturity from './components/charts/BoxPlotByMaturity';
import PriceBySizeBar from './components/charts/PriceBySizeBar';
import PercentileTable from './components/charts/PercentileTable';

export default function App() {
  const { listings, metadata, loading, error, retry } = useListings();
  const { filters, setMaturity, setAreaRange, setPriceRange, setOutlierMultiplier, setOutlierField, applyQuickView, initFromMetadata } = useFilters();
  const { filtered, stats, outlierInfo } = useStats(listings, filters);

  useEffect(() => {
    if (metadata) initFromMetadata(metadata);
  }, [metadata, initFromMetadata]);

  if (error) {
    return (
      <div className="error-banner">
        Failed to load data: {error}
        <button onClick={retry}>Retry</button>
      </div>
    );
  }

  if (loading) {
    return <div className="loading">Loading SL Land Analytics...</div>;
  }

  const filterActions = {
    setMaturity,
    setAreaRange,
    setPriceRange,
    setOutlierMultiplier,
    setOutlierField,
    applyQuickView,
  };

  return (
    <Dashboard
      metadata={metadata}
      filters={filters}
      stats={stats}
      outlierInfo={outlierInfo}
      filterActions={filterActions}
    >
      <PricePerSqmDistribution histogramData={stats?.pricePerSqmHistogram} />
      <PriceDistribution histogramData={stats?.priceHistogram} />
      <ScatterPlotChart data={filtered} />
      <BoxPlotByMaturity byMaturity={stats?.byMaturity} />
      <PriceBySizeBar bySizeCategory={stats?.bySizeCategory} />
      <PercentileTable percentiles={stats?.percentiles} />
    </Dashboard>
  );
}
```

- [ ] **Step 8: Verify the full dashboard**

Start both servers and verify all 6 charts render with real data, filters update charts in real time, quick views switch correctly, and outlier slider changes the excluded count.

```bash
# Terminal 1
cd land-for-sale/api && uvicorn main:app --port 8000

# Terminal 2
cd land-for-sale/frontend && npm run dev
```

Expected: Full dashboard with all 6 chart types rendering, metrics updating on filter change, outlier exclusion working.

- [ ] **Step 9: Commit**

```bash
cd land-for-sale
git add frontend/src/components/charts/ frontend/src/App.jsx
git commit -m "feat: add all chart components — histograms, scatter, box plot, bar, percentile table"
```

---

## Task 8: Final Polish and Verification

**Files:**
- Possibly touch CSS or component tweaks based on visual verification

- [ ] **Step 1: Run all frontend tests**

```bash
cd land-for-sale/frontend
npx vitest run
```

Expected: All statistics and outlier tests pass.

- [ ] **Step 2: Run API tests**

```bash
cd land-for-sale/api
pytest test_api.py -v
```

Expected: All 3 API tests pass.

- [ ] **Step 3: Full manual smoke test**

Start both servers. Verify:
1. Dashboard loads with all data
2. Quick view pills switch between maturity/size presets
3. Maturity checkboxes filter correctly
4. Area and price range sliders filter data
5. IQR multiplier slider changes excluded count and updates all charts
6. Outlier field dropdown switches between price/sqm/both
7. All 6 charts render and update reactively
8. Summary metrics reflect current filter state
9. Error banner shows if API is stopped (stop API, refresh frontend)

- [ ] **Step 4: Commit any polish fixes**

```bash
cd land-for-sale
git add -A
git commit -m "chore: final polish and verification"
```
