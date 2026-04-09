# Linden Lab Partnership Research & Outreach

_Created: 2026-04-09_

---

## Contact Information

| Channel              | Details                                        |
|----------------------|------------------------------------------------|
| **Business/Sales**   | business@lindenlab.com                         |
| **Press**            | presscontact@lindenlab.com                     |
| **Billing (phone)**  | 800-294-1067 (US/CA toll-free) or 703-286-6277 |
| **Support Portal**   | https://support.secondlife.com/                |
| **Community Portal** | http://community.secondlife.com/               |

### Key People

- **Brad Oberwager** - Executive Chair of Linden Lab, effectively running the company. Publicly engaged, has done interviews with Wagner James Au (New World Notes). Stated LL is "very open to talking to anybody and everybody." Making a major executive hire to grow SL in 2026.
- **Wagner James Au** - New World Notes blogger (wjamesau.substack.com). Long-standing relationship with LL leadership. Potential warm introduction channel if cold email fails.

---

## Precedent: LL Third-Party Partnerships

### 1. L$ Authorized Reseller Program (2013-2015)

- **What:** LL authorized third parties to buy L$ on the LindeX and resell to users via their own platforms using international currencies and payment methods.
- **Who:** ZoHa Islands was one of the authorized resellers.
- **Status:** Pilot program concluded in 2015 (shut down).
- **Relevance:** Proves LL has been willing to let third parties handle L$ transactions. Our escrow system is a similar concept - holding and distributing L$ on behalf of transaction participants.
- **Source:** https://wiki.secondlife.com/wiki/Linden_Lab_Official:Linden_Dollar_(L$)_Authorized_Reseller_Program

### 2. Registration API (RegAPI)

- **What:** API allowing third parties to programmatically create SL accounts.
- **Who:** The Vesuvius Group, LLC was a certified support provider.
- **Status:** Still documented on wiki, unclear if actively maintained.
- **Relevance:** Shows LL has given third parties deep platform integration access. We'd want similar API-level access for parcel data.
- **Source:** https://wiki.secondlife.com/wiki/Linden_Lab_Official:Registration_API

### 3. Community Gateway Program

- **What:** Organizations given special status to onboard new SL users. Includes land, tools, and direct collaboration with LL.
- **Requirements:** Account 1+ year old, no abuse reports, good financial standing, website with SL registration integration.
- **Status:** Relaunched as part of Creator Partnership Program (2025).
- **Relevance:** Demonstrates LL grants land and special privileges to partners who drive user engagement.
- **Source:** https://wiki.secondlife.com/wiki/Linden_Lab_Official:Community_Gateway

### 4. Creator Partnership Program (April 2025)

- **What:** Open call for collaboration between LL and creators to co-create content and experiences. Focus on Welcome Packs, perks, community gateway experiences.
- **Quote from LL:** "We are very open to talking to anybody and everybody, and that's evidenced in the announcement of the Creator Partnership Program, and it's genuinely an open call for collaboration."
- **Status:** Active, accepting proposals.
- **Relevance:** LL is actively seeking partners RIGHT NOW. SLPA isn't a traditional "creator" but the program signals openness to third-party collaboration broadly. Could pitch under this umbrella or reference it in outreach.
- **Source:** https://community.secondlife.com/news/featured-news/introducing-the-second-life-creator-partnership-program-r11184/

### 5. Certified HTTP Escrow (2009 - Internal Architecture)

- **What:** LL's own internal design for a distributed transaction coordinator handling L$ transfers, object purchases, and LAND purchases.
- **Key quote:** "Some sort of API for third parties to be participants to Second Life transactions."
- **Design:** Modeled all transactions as resource transfers. Resources include L$, Assets, and **Parcels** ("a certificate that the simulator is willing to transfer ownership of the parcel").
- **Status:** Internal infrastructure document. Unclear if ever fully implemented or exposed publicly.
- **Relevance:** This is huge. LL has architecturally considered third-party participation in land transactions at the protocol level. Even if this API was never built, it shows the concept isn't foreign to them. This is exactly what we'd want - a programmatic way to transfer land ownership as part of an escrow flow.
- **Source:** https://wiki.secondlife.com/wiki/Certified_HTTP_Escrow

### 6. Third-Party Viewer Program

- **What:** Policy allowing third-party viewers to connect to SL. LibreMetaverse (which we'd use for bots) operates under this framework.
- **Status:** Active. Viewers must comply with TPV Policy and TOS.
- **Relevance:** Our verification bots using LibreMetaverse are legitimate under this policy. Not a "partnership" per se, but validates our technical approach.
- **Source:** https://secondlife.aditi.lindenlab.com/corporate/third-party-viewers

---

## What We'd Ask For

### Tier 1: Information & Blessing (Minimum Viable Ask)

1. **Confirmation that SLPA's model is TOS-compliant** - Third-party escrow for L$ land transactions using scripted objects and LibreMetaverse bots.
2. **Any undocumented APIs or endpoints** for parcel data that could help (sale status, ownership history, etc.).
3. **Guidance on bot account policies** - We'd run multiple headless bot accounts for verification. Any limits or requirements beyond the Scripted Agent Policy?

### Tier 2: Technical Partnership

4. **Parcel sale status API** - Currently impossible to detect via LSL or World API whether land is set for sale and to whom. A simple endpoint or LSL function would eliminate our biggest verification hack (the sale-to-bot approach).
5. **Ownership transfer event webhook** - Instead of polling World API every 5 minutes, a push notification when parcel ownership changes.
6. **Parcel search/discovery API** - Currently parcel UUIDs can only be obtained in-world. An external API to search parcels by region/coordinates would be valuable.

### Tier 3: Business Partnership (The Big Ask)

7. **Tier grace period for escrow** - If we ever need the bot to hold land temporarily (currently designed to avoid this), a 7-day tier exemption for escrow accounts would remove all friction.
8. **Revenue share** - Percentage of SLPA commission goes to LL. This aligns incentives - LL profits from increased land market liquidity.
9. **Featured/promoted status** - SLPA listed on SL's official land pages or marketplace as an authorized auction service.
10. **Programmatic land transfer API** - The holy grail. An API endpoint that transfers land ownership without requiring viewer interaction. This would make escrow seamless and eliminate the manual "set for sale to specific person" step. LL's own Certified HTTP Escrow design from 2009 envisioned exactly this.

---

## The Pitch (Why LL Should Care)

**SLPA directly increases Linden Lab's revenue:**

1. **More tier revenue** - Every successful auction means an active landowner paying monthly tier to LL. Vacant/abandoned Mainland parcels generate zero tier. SLPA increases land market liquidity and occupancy.

2. **No competition** - LL's existing land auctions only handle repossessed parcels from unpaid fees. There is NO player-to-player auction system. SLPA fills a gap, not competes.

3. **Mainland revival** - Mainland has a reputation for being neglected/abandoned compared to private estates. A vibrant auction marketplace makes Mainland parcels more attractive and valuable.

4. **Zero risk to LL** - We handle all the infrastructure, support, and escrow. LL just benefits from increased land transactions.

5. **Commission alignment** - We'd happily share a percentage of our 5% commission with LL. Even at 1-2% of transaction value flowing to LL, it's pure incremental revenue.

---

## Draft Email

**To:** business@lindenlab.com
**Subject:** Partnership Inquiry - Second Life Parcel Auction Platform

---

Hi,

I'm building SL Parcel Auctions (slparcelauctions.com) - a third-party auction platform for player-to-player Mainland parcel sales in Second Life.

**The gap we're filling:** Second Life has no player-to-player land auction system. LL's existing auctions only handle repossessed parcels. Residents who want to sell land currently rely on classified ads, word of mouth, or setting land for sale and hoping someone walks by. We're building a proper auction marketplace with verified listings, escrow, proxy bidding, and ratings.

**How it works:**
- Sellers verify identity via in-world terminal and verify parcel ownership via LSL scripted objects and the World API
- Auctions run on our website with real-time bidding (WebSocket)
- Escrow handled via scripted payment terminals (L$ only, no USD)
- Verification bots (LibreMetaverse, compliant with TPV Policy) monitor parcel status during escrow
- 5% commission on completed sales

**Why this benefits Linden Lab:**
- Every successful sale means an active landowner paying tier - we increase Mainland occupancy and liquidity
- We don't compete with anything LL offers
- We handle all infrastructure, support, and dispute resolution
- We're happy to discuss revenue sharing

**What we'd love to discuss:**
- Confirmation that our model is TOS-compliant
- Any API access or technical guidance that could help (particularly around parcel sale status detection, which is currently a blind spot in both LSL and the World API)
- Whether there's interest in a more formal partnership arrangement

We're currently in the design phase and would welcome the opportunity to build this in alignment with Linden Lab's vision for Second Life's land market.

Happy to jump on a call or provide our full technical design document.

Best,
Heath
[Your SL avatar name if you want to include it]
[Your email]

---

## Next Steps

- [ ] Review and personalize the draft email
- [ ] Decide whether to send cold to business@ or try to get a warm intro via community channels first
- [ ] Consider posting in SL forums to gauge community interest (builds leverage for LL conversation)
- [ ] Look into whether the Creator Partnership Program application form could be an alternative entry point
- [ ] Create the SLPAEscrow SL account and mark as Scripted Agent before outreach (shows we're serious)

---

## Notes

- The L$ Authorized Reseller Program being shut down in 2015 is worth noting - LL may be cautious about third-party L$ handling post-Tilia acquisition by Thunes (June 2025). Our escrow is simpler though - we're not reselling L$, just holding it temporarily.
- Brad Oberwager's public statements about growing SL and being open to partnerships are encouraging. LL seems to be in an expansion/collaboration mindset.
- The Tilia/Thunes payment processing transition has caused friction with merchants. LL may be sensitive about anything that adds complexity to L$ flows. Frame escrow as simple, transparent, and beneficial.
- No evidence found of LL ever granting tier exemptions to third parties. This is likely the hardest ask. Design should continue assuming we pay full tier if bot ever holds land (which current design avoids).