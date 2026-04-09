#!/usr/bin/env python3
"""
Second Life Mainland Land Scraper

Scrapes land listings from search.secondlife.com using Firefox cookies
for authentication, across all maturity levels (General, Moderate, Adult).

Generates:
  - sl_land_listings.csv  (all listings with maturity column)
  - STATS.md              (percentiles and averages, overall and by category)

Usage:
  pip install requests beautifulsoup4 browser_cookie3
  python sl_land_scraper.py --rate-limit 30
  python sl_land_scraper.py --rate-limit 20 --output-dir ./data
"""

import argparse
import csv
import math
import os
import re
import sys
import time
from datetime import datetime

try:
    import requests
    from bs4 import BeautifulSoup
    import browser_cookie3
except ImportError:
    print("Missing dependencies. Install with:")
    print("  pip install requests beautifulsoup4 browser_cookie3")
    sys.exit(1)

BASE_URL = (
    "https://search.secondlife.com/?page={page}"
    "&search_type=land&collection_chosen=land&search_type=land"
    "&method=buy&land_type=mainland"
    "&area_low=0&area_high=65536"
    "&price_low=0&price_high=1000000000"
    "&maturity={maturity}&query_term=&sort=land_price_asc"
)

MATURITY_LEVELS = [
    ("g", "General"),
    ("m", "Moderate"),
    ("a", "Adult"),
]

AREA_RANGES = [
    (0, 1024),
    (1025, 4096),
    (4097, 8129),
    (8130, 16384),
    (16385, 32768),
    (32769, 65536),
]

RESULTS_PER_PAGE = 20


def load_firefox_cookies():
    """Load secondlife.com cookies from Firefox."""
    try:
        cj = browser_cookie3.firefox(domain_name=".secondlife.com")
        count = sum(1 for _ in cj)
        if count == 0:
            print("Warning: No secondlife.com cookies found in Firefox.")
            print("  Log into search.secondlife.com in Firefox first.")
            sys.exit(1)
        return cj
    except Exception as e:
        print(f"Error reading Firefox cookies: {e}")
        print("  Make sure Firefox is closed and you're logged into search.secondlife.com")
        sys.exit(1)


def parse_page(html):
    """Parse a single page of results, returning list of listing dicts."""
    soup = BeautifulSoup(html, "html.parser")
    listings = []

    for result in soup.select(".search-result__type-land"):
        name_el = result.select_one(".land-name")
        type_el = result.select_one(".land-type")
        price_el = result.select_one(".land-price")
        area_el = result.select_one(".land-area")
        sqm_el = result.select_one(".land-sqm")

        if not all([name_el, type_el, price_el, area_el, sqm_el]):
            continue

        name = name_el.get_text(strip=True)
        land_type = type_el.get_text(strip=True)
        price_text = price_el.get_text(strip=True).replace("L$", "").replace(",", "").strip()
        area_text = area_el.get_text(strip=True).replace(",", "").strip()
        sqm_text = sqm_el.get_text(strip=True).replace(",", "").strip()

        try:
            price = int(price_text)
            area = int(area_text)
            sqm = float(sqm_text)
        except ValueError:
            continue

        listings.append({
            "name": name,
            "type": land_type,
            "price": price,
            "area": area,
            "price_per_sqm": sqm,
        })

    return listings


def get_total_results(html):
    """Extract total result count from page HTML."""
    match = re.search(r"(\d[\d,]*)\s+Results", html)
    if match:
        return int(match.group(1).replace(",", ""))
    return 0


def scrape_maturity(session, maturity_code, maturity_label, delay):
    """Scrape all pages for a given maturity level. Returns list of listings."""
    # Fetch page 1 to discover total results
    url = BASE_URL.format(page=1, maturity=maturity_code)
    print(f"\n[{maturity_label}] Fetching page 1 to get result count...", flush=True)

    try:
        resp = session.get(url, timeout=30)
        resp.raise_for_status()
    except requests.RequestException as e:
        print(f"  ERROR fetching page 1: {e}")
        return []

    total = get_total_results(resp.text)
    if total == 0:
        print(f"  No results for maturity={maturity_code}")
        return []

    total_pages = math.ceil(total / RESULTS_PER_PAGE)
    print(f"  {total} results across {total_pages} pages")

    # Parse page 1 results (already fetched)
    all_listings = parse_page(resp.text)
    print(f"  Page 1/{total_pages}: {len(all_listings)} listings", flush=True)

    # Fetch remaining pages
    for page in range(2, total_pages + 1):
        time.sleep(delay)
        url = BASE_URL.format(page=page, maturity=maturity_code)
        print(f"  Page {page}/{total_pages}...", end=" ", flush=True)

        try:
            resp = session.get(url, timeout=30)
            resp.raise_for_status()
        except requests.RequestException as e:
            print(f"ERROR: {e}")
            continue

        page_listings = parse_page(resp.text)
        count = len(page_listings)
        print(f"{count} listings", flush=True)

        if count == 0:
            print("  No more results. Stopping early.")
            break

        all_listings.extend(page_listings)

    # Tag all listings with maturity
    for listing in all_listings:
        listing["maturity"] = maturity_label

    print(f"  [{maturity_label}] Total: {len(all_listings)} listings")
    return all_listings


def percentile(sorted_data, p):
    """Calculate p-th percentile of pre-sorted data using linear interpolation."""
    if not sorted_data:
        return 0
    if len(sorted_data) == 1:
        return sorted_data[0]
    k = (len(sorted_data) - 1) * (p / 100)
    f = math.floor(k)
    c = math.ceil(k)
    if f == c:
        return sorted_data[int(k)]
    return sorted_data[f] * (c - k) + sorted_data[c] * (k - f)


def compute_stats(listings):
    """Compute statistics from a list of listings."""
    if not listings:
        return None

    prices = sorted(l["price"] for l in listings)
    areas = sorted(l["area"] for l in listings)
    sqms = sorted(l["price_per_sqm"] for l in listings)

    pct_labels = ["Low", "10%", "25%", "50%", "75%", "90%", "High"]
    pct_values = [0, 10, 25, 50, 75, 90, 100]

    price_pcts = {}
    sqm_pcts = {}
    for label, pval in zip(pct_labels, pct_values):
        if label == "Low":
            price_pcts[label] = prices[0]
            sqm_pcts[label] = sqms[0]
        elif label == "High":
            price_pcts[label] = prices[-1]
            sqm_pcts[label] = sqms[-1]
        else:
            price_pcts[label] = percentile(prices, pval)
            sqm_pcts[label] = percentile(sqms, pval)

    return {
        "count": len(listings),
        "avg_price": sum(prices) / len(prices),
        "avg_area": sum(areas) / len(areas),
        "avg_sqm": sum(sqms) / len(sqms),
        "price_percentiles": price_pcts,
        "sqm_percentiles": sqm_pcts,
    }


def format_price(val):
    """Format a price value for display."""
    if val == int(val):
        return f"L$ {int(val):,}"
    return f"L$ {val:,.2f}"


def format_sqm(val):
    """Format a L$/m² value for display."""
    return f"{val:.2f}"


def write_stats_section(lines, title, listings):
    """Write a complete stats section (averages + percentiles + per-range) for a set of listings."""
    stats = compute_stats(listings)
    if not stats:
        lines.append(f"## {title}")
        lines.append("")
        lines.append("*No data*")
        lines.append("")
        return

    lines.append(f"## {title}")
    lines.append("")
    lines.append(f"**Listings:** {stats['count']}")
    lines.append("")

    # Averages
    lines.append("| Metric | Value |")
    lines.append("|--------|-------|")
    lines.append(f"| Average Price L$/m² | {format_sqm(stats['avg_sqm'])} |")
    lines.append(f"| Average Total Price | {format_price(stats['avg_price'])} |")
    lines.append(f"| Average Area | {stats['avg_area']:,.1f} m² |")
    lines.append("")

    # Price percentiles
    lines.append("### Price Percentiles (Total Price)")
    lines.append("")
    lines.append("| Percentile | Value |")
    lines.append("|------------|-------|")
    for label, val in stats["price_percentiles"].items():
        lines.append(f"| {label} | {format_price(val)} |")
    lines.append("")

    # L$/m² percentiles
    lines.append("### Price Percentiles (L$/m²)")
    lines.append("")
    lines.append("| Percentile | Value |")
    lines.append("|------------|-------|")
    for label, val in stats["sqm_percentiles"].items():
        lines.append(f"| {label} | {format_sqm(val)} |")
    lines.append("")

    # Per area range
    lines.append("### By Land Size")
    lines.append("")
    for low, high in AREA_RANGES:
        range_listings = [l for l in listings if low <= l["area"] <= high]
        range_stats = compute_stats(range_listings)

        lines.append(f"#### {low:,} - {high:,} m²")
        lines.append("")

        if not range_stats:
            lines.append("*No listings in this range*")
            lines.append("")
            continue

        lines.append(f"**Listings:** {range_stats['count']}")
        lines.append("")
        lines.append("| Metric | Value |")
        lines.append("|--------|-------|")
        lines.append(f"| Average Price L$/m² | {format_sqm(range_stats['avg_sqm'])} |")
        lines.append(f"| Average Total Price | {format_price(range_stats['avg_price'])} |")
        lines.append(f"| Average Area | {range_stats['avg_area']:,.1f} m² |")
        lines.append("")

        lines.append("| Percentile | L$ Price | L$/m² |")
        lines.append("|------------|----------|-------|")
        for label in ["Low", "10%", "25%", "50%", "75%", "90%", "High"]:
            p = range_stats["price_percentiles"][label]
            s = range_stats["sqm_percentiles"][label]
            lines.append(f"| {label} | {format_price(p)} | {format_sqm(s)} |")
        lines.append("")


def write_stats(all_listings, output_path):
    """Write STATS.md file with overall, per-maturity, and per-range statistics."""
    lines = []
    lines.append("# Second Life Mainland Land Statistics")
    lines.append("")
    lines.append(f"*Generated: {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}*")
    lines.append("")
    lines.append(f"**Total Listings:** {len(all_listings)}")
    lines.append("")
    lines.append("---")
    lines.append("")

    # Overall stats
    write_stats_section(lines, "Overall (All Maturity Levels)", all_listings)

    # Per-maturity stats
    for _, maturity_label in MATURITY_LEVELS:
        lines.append("---")
        lines.append("")
        mat_listings = [l for l in all_listings if l["maturity"] == maturity_label]
        write_stats_section(lines, f"{maturity_label} Only", mat_listings)

    with open(output_path, "w", encoding="utf-8") as f:
        f.write("\n".join(lines))


def main():
    parser = argparse.ArgumentParser(
        description="Scrape Second Life mainland land listings and generate statistics"
    )
    parser.add_argument(
        "--rate-limit", type=float, default=30,
        help="Max requests per minute (default: 30)"
    )
    parser.add_argument(
        "--output-dir", type=str, default=".",
        help="Output directory for CSV and STATS.md (default: current dir)"
    )
    args = parser.parse_args()

    if args.rate_limit <= 0:
        print("Error: --rate-limit must be positive")
        sys.exit(1)

    delay = 60.0 / args.rate_limit

    print("Loading Firefox cookies...")
    cookies = load_firefox_cookies()

    session = requests.Session()
    session.cookies = cookies
    session.headers.update({
        "User-Agent": "Mozilla/5.0 (compatible; SL-Land-Scraper/1.0)"
    })

    os.makedirs(args.output_dir, exist_ok=True)

    print(f"Rate limit: {args.rate_limit} req/min ({delay:.1f}s between requests)")

    # Scrape each maturity level
    all_listings = []
    for maturity_code, maturity_label in MATURITY_LEVELS:
        listings = scrape_maturity(session, maturity_code, maturity_label, delay)
        all_listings.extend(listings)

    print(f"\n{'='*50}")
    print(f"Total scraped: {len(all_listings)} listings")

    # Write CSV
    csv_path = os.path.join(args.output_dir, "sl_land_listings.csv")
    with open(csv_path, "w", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(
            f, fieldnames=["name", "type", "price", "area", "price_per_sqm", "maturity"]
        )
        writer.writeheader()
        writer.writerows(all_listings)
    print(f"CSV written:   {csv_path}")

    # Write STATS.md
    stats_path = os.path.join(args.output_dir, "STATS.md")
    write_stats(all_listings, stats_path)
    print(f"Stats written: {stats_path}")


if __name__ == "__main__":
    main()
