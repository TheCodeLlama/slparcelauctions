# Design System Specification

## 1. Overview & Creative North Star: "The Digital Concierge"

This design system is built upon the philosophy of **The Digital Concierge**. In the high-stakes world of virtual land speculation, we move away from the cluttered "marketplace" aesthetic and toward a high-end, editorial experience. It is professional, authoritative, and frictionless.

The goal is to break the "template" look. We achieve this through **Intentional Asymmetry**—where large-scale typography might offset a perfectly centered grid—and **Tonal Layering**. We do not use lines to define space; we use light and depth. The experience should feel like looking at a luxury architecture magazine: expansive, premium, and deeply intentional.

---

## 2. Color & Surface Philosophy

The palette is anchored in deep charcoals and warm ambers, creating a "Dark Mode First" environment that feels like a private lounge rather than a computer interface.

### The "No-Line" Rule
Traditional 1px solid borders are strictly prohibited for sectioning or layout containment. Boundaries must be defined solely through:
1.  **Background Shifts:** Placing a `surface-container-low` component on a `surface` background.
2.  **Negative Space:** Using generous padding to define the start and end of content blocks.

### Surface Hierarchy & Nesting
Treat the UI as a series of physical layers. We use the Material tiers to create a "Z-axis" of importance:
*   **Background (`#121416`):** The canvas.
*   **Surface Container Low (`#1a1c1e`):** Large structural areas (sidebars, footer).
*   **Surface Container High (`#282a2c`):** Interactive elements (auction cards, bidding panels).
*   **Surface Bright (`#37393b`):** Pop-overs and active states.

### The "Glass & Gradient" Rule
To add "soul" to the digital interface:
*   **Glassmorphism:** Use semi-transparent `surface-container-highest` with a `blur(12px)` for floating navigation and sticky bidding panels.
*   **Signature Textures:** Apply a subtle linear gradient (from `primary` to `primary_container`) on high-value CTAs to give them a metallic, gold-leaf luster.

---

## 3. Typography: Editorial Authority

We use a dual-font approach to balance character with readability.

*   **Display & Headlines (Manrope):** Chosen for its geometric precision and modern "tech-luxe" feel.
    *   *Usage:* Use `display-lg` for land titles and large auction prices. This conveys confidence and scale.
*   **Title & Body (Inter):** Chosen for its exceptional legibility at small sizes.
    *   *Usage:* Use `body-md` for parcel descriptions and legal text. `title-sm` should be used for auction metadata (Region, SQM, Rating).

**Hierarchy Principle:** Always prioritize the "Bid Price" and "Time Remaining" using `headline-lg` to create a focal point in the visual scan path.

---

## 4. Elevation & Depth

### The Layering Principle
Depth is achieved by stacking surface tokens. A `surface-container-lowest` card placed on a `surface-container-low` section creates a natural "recessed" look without the need for heavy drop shadows.

### Ambient Shadows
For floating elements like "Place Bid" modals, use an **Ambient Shadow**:
*   **Values:** `0px 20px 40px rgba(0, 0, 0, 0.4)`
*   **Tinting:** Never use pure black shadows. The shadow should be a deeply desaturated version of the background color to mimic natural light absorption.

### The "Ghost Border" Fallback
If a border is required for accessibility (e.g., input fields), use a **Ghost Border**:
*   Token: `outline-variant` at **20% opacity**. It should be felt, not seen.

---

## 5. Components & Signature UI

### Auction Cards
*   **Structure:** No dividers. Use `surface-container-high` for the card body. 
*   **Visuals:** Large, high-resolution parcel imagery. Overlay the `primary` price tag in the bottom-left corner using a glassmorphic background.
*   **Badges:** Maturity badges (General, Moderate, Adult) should use `secondary_container` with `on_secondary_container` text. Keep corners at `xl` (0.75rem) for a friendly, gamified feel.

### Sticky Bidding Panels
*   **Style:** Positioned at the bottom of mobile or right-side of desktop.
*   **Glass Effect:** Use `surface-container-highest` at 80% opacity with a heavy backdrop blur. This ensures the auction content "peeks" through as the user scrolls, maintaining context.

### Countdown Timers (The "Urgency" Engine)
*   **Normal:** `on_surface_variant` (Subtle grey).
*   **Warning (<1h):** `primary` (Warm Amber).
*   **Urgent (<10m):** `error` (Soft Red/Coral).
    *   *Interaction:* Urgent timers should have a subtle 2px "glow" (outer shadow) in the `error` color.

### Buttons & Inputs
*   **Primary Button:** `primary` background with `on_primary` text. No border. `md` (0.375rem) corner radius.
*   **Input Fields:** Use `surface-container-lowest` for the field background. The label should be `label-md` floating above the field in `on_surface_variant`.

### Tables & Lists
*   **Constraint:** Forbid 1px dividers.
*   **Alternative:** Use "Zebra Striping" with `surface-container-low` and `surface-container-lowest` or simply 24px of vertical whitespace between rows.

---

## 6. Do’s and Don’ts

### Do:
*   **Use Generous Whitespace:** If a layout feels crowded, increase the padding by a factor of 2.
*   **Respect the Dark Mode:** Ensure that in Light Mode, the `surface` is not pure white, but a soft, professional light grey (`#f8f9fa`).
*   **Use Tonal Shifts:** When a user hovers over a card, change the background from `surface-container-high` to `surface-bright`.

### Don’t:
*   **Don’t use "Default" Shadows:** Avoid the small, dark, "fuzzy" shadows common in low-end UI.
*   **Don’t use 100% Solid Borders:** High-contrast lines break the editorial flow and make the platform feel like a spreadsheet.
*   **Don’t use pure black:** Use the Slate/Charcoal tokens provided to maintain the "premium" depth.