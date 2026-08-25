# Product Assets Page Overrides

> **PROJECT:** AI Bookkeeping
> **Generated:** 2026-08-10 10:22:51
> **Page Type:** Dashboard / Data View

> ⚠️ **IMPORTANT:** Rules in this file **override** the Master file (`design-system/MASTER.md`).
> Only deviations from the Master are documented here. For all other rules, refer to the Master.

---

## Page-Specific Rules

### Layout Overrides

- **Max Width:** 1400px or full-width
- **Grid:** 12-column grid for data flexibility
- **Sections:** 1. Hero (product + live preview or status), 2. Key metrics/indicators, 3. How it works, 4. CTA (Start trial / Contact)

### Spacing Overrides

- **Content Density:** High — optimize for information display

### Typography Overrides

- Product names use the global UI face; purchase date, held days, depreciation rate, and daily cost use the monospace data face.

### Color Overrides

- **Strategy:** Use the shared violet/cyan system. Coral is reserved for value loss or overdue actions; green indicates realized sale value or positive value retention.

### Component Overrides

- Avoid: Load 50MB textures
- Avoid: Single row actions only
- Avoid: Auto-play high-res video loops

---

## Page-Specific Components

- No unique components for this page

---

## Recommendations

- Effects: Hover tooltips, chart zoom on click, row highlighting on hover, smooth filter animations, data loading spinners
- Sustainability: Compress and lazy load 3D models
- Data Entry: Allow multi-select and bulk edit
- Sustainability: Click-to-play or pause when off-screen
- CTA Placement: Primary CTA in nav + After metrics
