# Second Life Mainland Land Scraper

Scrapes all mainland land-for-sale listings from [search.secondlife.com](https://search.secondlife.com) across General, Moderate, and Adult maturity levels. Outputs a CSV of all listings and a STATS.md with price percentiles, averages, and breakdowns by land size.

## Prerequisites

- **Python 3.8+**
- **Firefox** with an active login to [search.secondlife.com](https://search.secondlife.com) (required for Moderate/Adult access)
- **Close Firefox** before running the script (Firefox locks its cookie database)

## Setup

```bash
pip install requests beautifulsoup4 browser_cookie3
```

## Usage

```bash
python for_sale_scraper.py [--rate-limit N] [--output-dir DIR]
```

| Flag | Default | Description |
|------|---------|-------------|
| `--rate-limit` | 30 | Max requests per minute |
| `--output-dir` | `.` | Directory for output files |

### Examples

```bash
# Default settings (30 req/min, output to current directory)
python for_sale_scraper.py

# Slower rate, output to a specific folder
python for_sale_scraper.py --rate-limit 20 --output-dir ./output
```

## Output

- **sl_land_listings.csv** — All listings with columns: `name`, `type`, `price`, `area`, `price_per_sqm`, `maturity`
- **STATS.md** — Statistics report containing:
  - Overall and per-maturity (General, Moderate, Adult) sections
  - Average Price L$/m², Average Total Price, Average Area
  - Price percentiles: Low, 10%, 25%, 50%, 75%, 90%, High
  - Breakdowns by land size: 0-1024, 1025-4096, 4097-8129, 8130-16384, 16385-32768, 32769-65536 m²

## How It Works

1. Reads authentication cookies from Firefox's local cookie store
2. For each maturity level (General, Moderate, Adult):
   - Fetches page 1 to discover the total number of results
   - Calculates total pages (20 results per page)
   - Scrapes all pages with the configured rate limit
3. Writes combined CSV and statistics markdown
