# SLParcels Monetization Options

_Brainstorm catalog, compiled 2026-05-28. Not a spec, not a commitment. A menu of candidate monetizable features with stable IDs so individual options can be referenced, accepted, or rejected later._

## How to read this

Every option has a stable ID (for example `PROMO-01`, `SELL-03`). IDs never change once assigned, so a decision like "ship PROMO-01 and SELL-01, drop BUY-04" stays unambiguous over time. New ideas get the next free number in their group; rejected ideas keep their ID and get marked rejected rather than deleted.

ID groups:

| Prefix | Group |
|---|---|
| `PROMO-*` | One-off promotion purchases (anyone, mostly sellers) |
| `SELL-*` | Seller membership perks |
| `BUY-*` | Buyer features |
| `AGY-*` | Realty group / agency features |
| `PLAT-*` | Platform-wide / other revenue streams |
| `TIER-*` | Membership tier bundles |
| `PKG-*` | Overall packaging strategies |

Currency convention: prices shown in L$ with a USD equivalent at L$255 = US$1. All numbers are starting points to revisit at real volume.

## What already exists today

So the catalog below is additive, not a from-scratch revenue model:

- 5% commission on completed sales (seller-paid, deducted from escrow payout, minimum L$50).
- L$100 listing fee, paid at DRAFT to DRAFT_PAID, non-refundable once the auction goes ACTIVE.
- A wallet system (L$ top-up and withdraw) plus group wallets for realty groups.
- Coupon / promo-code infrastructure (recently added), so discounts and credits are already buildable.
- Buyers and bidders currently pay nothing beyond their winning bid.

## Guiding principles (the redlines)

These framed every price point below and are recorded so the reasoning survives:

1. **Trust and safety stays free.** Escrow, ownership verification, and dispute resolution are never paywalled. They are why people trust the platform.
2. **Fair bidding stays free.** Proxy / auto-bid and snipe protection are free for everyone. Nothing here lets a paying user out-bid a non-paying user on anything other than their own budget.
3. **Bidding is a hard redline.** No fee to bid, no fee to bid "better," no paid head start on auctions. Visibility and information can be sold; bid advantage cannot.
4. **Sellers and agencies carry the monetization.** They profit directly from a sale and already expect fees. Buyer-side features are kept cheap or free on purpose, because a deeper bidder pool raises final prices, which helps sellers and grows the 5% commission. Subsidize the demand side, monetize the supply side.

## Pricing reference points

- L$255 = US$1 (approximate LindeX rate).
- SL Premium membership is about US$11.99/month, so SL users have a built-in anchor for "a few dollars a month" recurring spend.
- Existing listing fee L$100 (about US$0.39). Existing commission 5%, minimum L$50.

---

## PROMO: one-off promotion purchases

Pay-once boosts any seller can buy with no subscription. Lowest friction, L$-native, reuses wallet plus coupon plumbing. All redline-safe because they sell visibility, never bid advantage.

| ID | Feature | What it is | Price | Notes |
|---|---|---|---|---|
| PROMO-01 | Featured listing | **Shipped at L$500 (configurable).** Top of browse plus the homepage "Featured" carousel and placement on the platform's physical in-world HQ display boards (up to 13 board slots, default 5). Seller pays from their wallet; slot releases automatically on auction end, cancellation, or withdrawal. Spec: `docs/superpowers/specs/2026-06-01-hq-featured-boards-design.md`. Plan: `docs/superpowers/plans/2026-06-01-hq-featured-boards-plan.md`. | L$500 / listing (~$1.96) | Flagship one-off boost |
| PROMO-02 | Listing bump | Re-sort the listing to the top of "Newest" | L$75 / bump (~$0.29) | Cheap impulse repeat-buy |
| PROMO-03 | Region / category spotlight | Premium slot pinned to a specific region or tag page | L$750 / week (~$2.94) | Scarcity gives pricing power |
| PROMO-04 | Homepage hero spotlight | The single largest homepage slot, one at a time | L$1,500 / week (~$5.88), or auction the slot | Could itself be auctioned for true price discovery |
| PROMO-05 | Extra media | Raise the per-listing cap on photos, add short video or 360 view | L$100 add-on (~$0.39) | |
| PROMO-06 | Highlight styling | Colored border or "Hot" ribbon on the listing card | L$50 (~$0.20) | Pure cosmetic upsell |
| PROMO-07 | Social cross-post boost | Push the listing into an SLPA in-world group / external feed, if one exists | L$100 (~$0.39) | Depends on owning a distribution channel |

---

## SELL: seller membership perks

Recurring value for active sellers. SELL-01 is the strongest anchor: a high-volume seller saves the membership cost back in commission alone.

| ID | Feature | What it is | Price | Notes |
|---|---|---|---|---|
| SELL-01 | Reduced commission | Commission drops from 5% to 3% on the member's sales | Membership perk | Anchor perk. Self-funding for active sellers |
| SELL-02 | Included listing fees | N free listings per month (versus L$100 each) | Membership perk | |
| SELL-03 | Featured credits | N free PROMO-01 featured slots per month | Membership perk | Bundles the one-off boost into the subscription |
| SELL-04 | Listing analytics | Views, watchers, bid velocity, traffic sources | Membership perk, or L$150 one-off report | Also sellable a la carte |
| SELL-05 | Relist plus templates | Save listing templates, one-click relist of unsold parcels | Membership perk | |
| SELL-06 | Auto-relist unsold | Automatically relist a parcel that ends without meeting reserve | Membership perk | |
| SELL-07 | Seller storefront | A branded page aggregating all of a seller's active listings | Membership perk | |
| SELL-08 | Pro badge | Cosmetic "Pro" badge on listings and profile | Membership perk | Cosmetic only. Must not imply SLPA-verified safety (trust redline) |
| SELL-09 | Scheduled listing | Queue listings to go live at a chosen future time | Membership perk | |
| SELL-10 | Priority support | Faster support-ticket response time | Membership perk | Response speed only. Never dispute outcome (trust redline) |

---

## BUY: buyer features

Kept deliberately cheap or free to grow the bidder pool. A small optional buyer tier, never a barrier to participating.

| ID | Feature | What it is | Price | Notes |
|---|---|---|---|---|
| BUY-01 | Saved searches plus alerts | Free tier: 3 saved searches and a daily digest. Paid: unlimited searches and instant notifications | L$500 / mo (~$1.96) | Redline-safe. An alert is not early bid access |
| BUY-02 | Market insights | Sold-price history and region price trends ("comps") | L$200 one-off report (~$0.78), or in a buyer tier | Buyers value comparables highly |
| BUY-03 | Advanced / map search | Map-based browsing (was Phase 2 out-of-scope) | Buyer membership perk | |
| BUY-04 | Unlimited watchlist | Free tier caps the watchlist at N items; paid removes the cap | Buyer membership perk | Minor perk |
| BUY-05 | Custom digest | Tune frequency and filters of the new-listing digest | Buyer membership perk | |
| BUY-06 | Wanted / ISO posts | Post "in search of" a parcel and get matched to new listings | L$100 / post (~$0.39) | Demand signal sellers can answer |
| BUY-X1 | ~~Early access to listings~~ | Seeing or bidding on auctions before the public | REJECTED | A paid head start to set max bids is pay-to-win. Violates the bidding redline. Do not build |

---

## AGY: realty group / agency features

The best fit for recurring USD billing. Professional land businesses run many listings and expect SaaS-style pricing.

| ID | Feature | What it is | Price | Notes |
|---|---|---|---|---|
| AGY-01 | Extra agent seats | First 3 agents free, then per-seat | $4 / seat / mo | Classic SaaS expansion lever |
| AGY-02 | Branded storefront | Group logo, banner, vanity URL | Agency tier perk | |
| AGY-03 | Volume commission | Tiered commission discount scaling with group sales volume | Agency tier perk | |
| AGY-04 | Agency dashboard | Per-agent performance, conversion, pipeline | Agency tier perk | |
| AGY-05 | Bulk import plus scheduling | List and schedule many parcels at once | Agency tier perk | |
| AGY-06 | API / white-label embed | Surface SLPA auctions on the agency's own site | $29+ / mo | Premium add-on |
| AGY-07 | Agent directory placement | Listing in a "find a land agent" directory | Agency tier perk | Lead generation |
| AGY-08 | Pooled featured allocation | A monthly pool of PROMO-01 featured slots shared across the group | Agency tier perk | |

---

## PLAT: platform-wide and other revenue

Revenue streams that do not fit a single persona.

| ID | Feature | What it is | Price | Notes |
|---|---|---|---|---|
| PLAT-01 | Advertising slots | Land-adjacent SL businesses advertise to land shoppers | L$ or USD, flat or per-impression | Revenue from advertisers, not from users |
| PLAT-02 | Cosmetic profile flair | Profile badges and themes | Small L$ | Low priority |
| PLAT-03 | Gift cards / promo credit | Buy wallet credit as a gift, redeemable via coupon infra | Face value | Mostly a growth and retention lever, not net new margin |
| PLAT-04 | Partner / terminal-host badge | Paid or earned badge for businesses hosting an SLPA terminal | TBD | Ties into the existing partner program |
| PLAT-05 | Market data report | A periodic aggregate land-market report (prices, volumes, trends) | Premium one-off or subscription | Sellable to agencies, investors, even LL |

---

## TIER: membership tier bundles

Candidate bundles assembled from the perks above. These are options, not a final lineup.

| ID | Tier | Audience | Price | Bundles |
|---|---|---|---|---|
| TIER-01 | Free | Everyone | L$0 | List (pays existing L$100 fee), bid freely, 3 saved searches, capped watchlist |
| TIER-02 | Pro Seller | Active sellers | ~L$1,500 / mo (~$5.88) or ~L$15,000 / yr (~$59, two months free) | SELL-01 through SELL-10, plus a few SELL-03 featured credits |
| TIER-03 | Land Hunter | Active buyers | ~L$500 / mo (~$1.96) | BUY-01 through BUY-05 |
| TIER-04 | Agency / Business | Realty groups | $19 to $29 / mo base, plus AGY-01 per-seat | AGY-01 through AGY-08, plus everything in Pro Seller |
| TIER-05 | Pro Combo | Solo power users | ~L$1,800 / mo (~$7.06) | Pro Seller plus Land Hunter perks in one price |

---

## PKG: overall packaging strategies

How to assemble the catalog into a sellable shape. Pick one to anchor on.

| ID | Strategy | Summary | Trade-off |
|---|---|---|---|
| PKG-A | Unified membership | One "SLParcels Pro" tier at one price, bundling the best perks across roles | Simplest to build and explain. Under-captures agencies, over-charges buyers for perks they ignore |
| PKG-B | Persona tiers | Separate Pro Seller, Land Hunter, and Agency tiers (TIER-02 through TIER-04) | Best value-capture and best fit per audience. More surface to build and maintain |
| PKG-C | A la carte only | Only one-off PROMO purchases, no subscriptions at all | Lowest friction for L$-native users who dislike subscriptions. No predictable recurring revenue |
| PKG-D | Hybrid (recommended) | PROMO one-offs for anyone, plus opt-in persona memberships (Pro Seller and Land Hunter in L$, Agency in USD) | Captures impulse spenders, committed sellers, and professional agencies on their preferred rails. Leans on plumbing that already exists. Most moving parts |

---

## Open questions for later

- Should PROMO-04 (homepage hero) be a fixed weekly price or an auctioned slot?
- Does an SLPA distribution channel (in-world group, Discord) exist to make PROMO-07 real?
- Is there appetite to stand up USD billing (Stripe or similar) for the Agency tier, or keep everything L$ for now?
- At what monthly sales volume does SELL-01 (3% commission) stop being self-funding and start eroding margin?
