# Design System Strategy: The Digital Curator

## 1. Overview & Creative North Star
The Creative North Star for this design system is **"The Digital Curator."** In the context of a virtual land auction platform, we are not merely building a marketplace; we are designing an elite gallery experience where every plot of land is treated as a masterpiece. 

To move beyond the "template" look, this system utilizes **Intentional Asymmetry** and **Tonal Depth**. We break the rigid grid by allowing high-end imagery to bleed into margins and using overlapping glass layers to create a sense of three-dimensional space. The goal is an editorial-first interface that feels "breathed into existence" rather than snapped to a grid.

---

## 2. Colors & Surface Architecture
This system relies on a sophisticated palette of warm ivories and cool slates to establish a "Quiet Luxury" aesthetic.

### The "No-Line" Rule
Standard 1px borders are strictly prohibited for sectioning. Definition must be achieved through **Background Shifts**. For example, a main content area (`surface`) transitions to a sidebar using `surface-container-low`. This creates a seamless, high-end feel that mimics architectural planes rather than digital boxes.

### Surface Hierarchy & Nesting
Treat the UI as a series of physical layers. Use the following tiers to define importance:
*   **Base Layer:** `surface` (#f7f9fb) – The canvas.
*   **Structural Sections:** `surface-container-low` (#f2f4f6) – For subtle grouping.
*   **Interactive Cards:** `surface-container-lowest` (#ffffff) – To create a natural "pop" against the background.
*   **Elevated Details:** `surface-container-high` (#e6e8ea) – For recessed elements like search bars or inactive states.

### The "Glass & Gradient" Rule
To add soul to the interface, Primary CTAs should not be flat. Utilize a subtle **Linear Gradient** from `primary` (#7b5808) to `primary_container` (#c99e4c). 
For floating navigation or high-end filters, apply **Glassmorphism**: 
*   **Fill:** `surface_container_lowest` at 70% opacity.
*   **Effect:** Backdrop blur (12px - 20px).
*   **Edge:** A 1px "Ghost Border" using `outline_variant` at 15% opacity.

---

## 3. Typography: Editorial Authority
We use **Manrope** exclusively. Its geometric yet humanist qualities provide the "Trustworthy" foundation required for high-stakes auctions.

*   **Display (lg/md):** Use for land titles and auction prices. Set with a tight letter-spacing (-0.02em) to create an authoritative, "tighter" editorial look.
*   **Headline (sm/md):** Use for section headers. These should often be paired with generous top-padding to let the content breathe.
*   **Title (md/sm):** Used for card labels. Pair `title-sm` in `primary` color for a sophisticated "accent label" look.
*   **Body (md):** Our workhorse. Ensure a line-height of at least 1.6 to maintain the "clean and modern" promise.
*   **Label (md):** Small-caps or increased letter-spacing should be used for metadata (e.g., "PLOT SIZE") to differentiate from body text.

---

## 4. Elevation & Depth
Depth in this system is achieved through **Tonal Layering** rather than heavy shadows.

*   **The Layering Principle:** To lift a card, place a `surface-container-lowest` card on a `surface-container-low` background. This creates a "soft lift" that feels premium and light.
*   **Ambient Shadows:** When a shadow is unavoidable (e.g., a floating "Bid" button), use a wide-spread, low-intensity shadow: `box-shadow: 0 20px 40px rgba(25, 28, 30, 0.06)`. The tint is derived from `on_surface` to keep it natural.
*   **The Ghost Border:** If an element requires a container boundary on a white background, use `outline_variant` (#d2c5b2) at **10% opacity**. It should be felt, not seen.

---

## 5. Components

### Buttons
*   **Primary:** Gradient (`primary` to `primary_container`), `on_primary` text, `DEFAULT` (1rem) roundness.
*   **Secondary:** Ghost style. `surface_container_lowest` background with a 10% `outline` border.
*   **Tertiary:** No background. `primary` text with an underline that appears only on hover.

### Cards (Land Plots)
*   **Prohibition:** No divider lines. Use `body-md` for descriptions and `title-lg` for pricing, separated by 1.5rem of whitespace.
*   **Styling:** Use `surface_container_lowest` with a `sm` (0.5rem) shadow. Images should have a 4px (Round 4) corner radius to match the system.

### Input Fields
*   **State:** Default fields use `surface_container_low` background. 
*   **Focus:** Transition to `surface_container_lowest` with a 1px `primary` border. This "glows" against the softer background.

### Auction Chips
*   **Status Indicators:** Use `tertiary_container` for "Active" and `error_container` for "Ending Soon." Keep text high-contrast using `on_tertiary_container` and `on_error_container`.

### Additional Component: The "Curator Tray"
A signature glassmorphic bottom-sheet or floating dock that holds "Saved Plots" or "Active Bids," using 80% `surface_container_lowest` and a heavy backdrop blur to sit above the map or gallery view.

---

## 6. Do's and Don'ts

### Do:
*   **Do** use asymmetrical margins (e.g., 80px left, 120px right) for hero sections to create an editorial feel.
*   **Do** use `primary_fixed_dim` for subtle icons to maintain the amber accent without overwhelming the eye.
*   **Do** allow high-resolution land imagery to occupy 60% of the viewport width in auction details.

### Don't:
*   **Don't** use pure black (#000000). Always use `on_surface` (#191c1e) for typography to keep the "Ivory" warmth.
*   **Don't** use 1px dividers to separate list items; use 12px or 16px of vertical whitespace.
*   **Don't** use "Round 4" (1rem) on small elements like checkboxes; use the `sm` (0.5rem) token to maintain visual weight.