We do not want to show bidder names on listings (can show the first couple of characters of name):

- Don't show bidder names to buyer until auction has completed (only highest bidder shown)
- Seller name is shown on listing along with Group if part of a realty group.
- Users should be able to set a custom display name that is not their SL name. If they choose to do this the display
  name should be shown with their SL name in parenthesis or something

Email and SL IM notifications should ALL be optional. Default emails to on and SL IM to on. These should be set during
registration. It also needs to be able to be configured in userr settings.

For now we need to ensure we're only allowing mainland sales. A later phase should include sorting out private islands
and how we will handle those

Users need to be able to add predefined categories that should be enums

- Things like Waterfront, Grass, Snow, Streetfront, Sailable, etc etc

Need to add functionality (link on website) that will teleport the user to the parcel

Need to add listing report functionality. Should set a number of reports that emails admin to view reports. Reports need
to be able to contain a subject and text

1) For commissions just do 5%
2) For mainland listings we do not need a floor (as we will have a L$50 commission minimum)
3) Yes, we will create a dedicated premium SL account for this
4) We do not need to worry about this atm
5) 1 initial location, we will allow partnerships. We can add a section to the website for partners, as well as a way to
   request to become a partner (and what that means)
6) Dispute resolution will be handled manually. SLPA staff will arbitrate.
7) Do not rate limit on bid placement. Same IP across multiple accounts bidding should be flagged. Last second bids from
   new accounts should be flagged. Need to be able to ban IP and SL Avatars. No ban automation yet
8) We don't have one yet
9) officially slparcelauctions.com (due to LL TOS), Also getting slparcels.com which will reroute
10) Show a reserve price exists, and this should be filterable (reserve met/reserve not met)

Break down features into chunks/testable phases at a high level

Generate prompt for google stitch site design

parcel layout script to generate image of parcel? Full square = region, green pixel = part of parcel, red pixel = not
part of parcel

- display on listing
- Runs with script verification, or run by bot on first visit when bot visits parcel
- can be wearable object by bot?

Do some research.

- Find a contact for Linden Labs to help with building this out as a partner?
- Potentially getting API calls that could help, permission to hold land in escrow for up to x days without paying land
  usage fees, percentage of sales go to LL?
- Do they ever do this for third party integrators?

https://search.secondlife.com/?page=1&search_type=land&collection_chosen=land&search_type=land&method=buy&land_type=mainland&area_low=0&area_high=65536&price_low=0&price_high=1000000000&maturity=gma&query_term=&sort=land_price_asc
Write a script that goes though each page and creates a json with name, type, price, area, and L$/m^2

- Use this to get percentiles of land prices