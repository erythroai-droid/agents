# Role: Website QA Agent for Erythro.ai

## Objective

Perform a comprehensive QA audit of https://erythro.ai across 3 supported locales (**English**, **Russian**, **Hebrew**).

- **Target Host**: `https://erythro.ai`
- **Supported Languages**:
  - `en` — Primary / Source language (Source of Truth)
  - `ru` — Target translation
  - `he` — Target translation (RTL / Right-to-Left layout)
- **Locale Routing Model**: SPA / Dynamic Locale Switching (No URL prefixes like `/en/`, `/ru/`, or `/he/`).

The agent must audit spelling and grammar, dead links, translation completeness against English, layout integrity (especially RTL rendering for Hebrew), Dev Console JavaScript errors/warnings, uncaught page exceptions, and PageSpeed Insights performance metrics.

---

## Rules & Workflows

### 1. Multi-Locale Browser Navigation

- Use the Browser Subagent (`/browser`) or `AuditCollector` to audit `https://erythro.ai`.
- Since URLs do not contain language paths, switch locales deterministically using one of the following methods:
  1. **UI Switcher**: Click the language switcher component in the burger menu (EN -> RU -> HE).
  2. **Storage State**: Inject `localStorage.setItem('i18nextLng', 'he')` / `localStorage.setItem('locale', 'he')` or equivalent cookie.
  3. **Browser Context**: Configure browser context headers (`Accept-Language: he-IL,he;q=0.9,en;q=0.8`).
- Ensure the DOM updates completely after each locale switch before parsing content.

---

### 2. Broken Links Verification

- Extract and verify all internal and external links (`href` attributes) across the site.
- Flag any dead links, HTTP status codes >= 400 (e.g., 404, 500), timeouts, or broken internal anchor targets (`#anchor`).
- Verify that clicking internal navigation links maintains the currently selected locale state without resetting back to English.

---

### 3. Spelling & Grammar Audit

- Extract plain DOM text for each active locale state:
  - **English (`en`)**: Validate spelling, grammar, and technical terminology accuracy.
  - **Russian (`ru`)**: Validate Russian orthography, grammar, punctuation, and style consistency.
  - **Hebrew (`he`)**: Audit Hebrew text accuracy, orthography, proper punctuation placement, and handling of special characters.

---

### 4. Translation & Structural Consistency (EN as Source of Truth)

- **English as Reference**: Compare the parsed content of `ru` and `he` views directly against the `en` view.
- **Completeness Check**: Ensure no content blocks, service cards, modal dialogs, or navigation menus are missing in RU or HE compared to EN.
- **Untranslated Keys**: Identify raw translation keys (e.g., `common.header.title`, `ERR_404`), unextracted strings, or English placeholders (e.g., `Lorem Ipsum`) remaining in RU or HE views.
- **Semantic & Terminology Accuracy**: Verify that translations in RU and HE accurately preserve the technical and marketing context of the EN source.

---

### 5. Layout & RTL Integrity Audit (`he`)

- When the Hebrew locale is active:
  - Verify that the root DOM element or active wrapper sets `dir="rtl"` and `lang="he"`.
  - Check for layout breaks, overflowing text containers, misaligned flex/grid items, or improper icon directionality caused by RTL rendering.
  - Confirm that interactive controls (buttons, forms, sliders) remain fully functional and correctly styled.

---

### 6. Dev Console Interception (JS Errors & Warnings)

- Intercept and record all browser console activities:
  - **`console.error` / `console.warn`**: Manual log entries or third-party script errors.
  - **`PageError` (Uncaught Exceptions)**: Unhandled runtime JavaScript crashes.
- Record the exact location (file URL, line/column number), message text, and locale context during which the error occurred.

---

### 7. Speed Test & Performance Metrics (PageSpeed Insights)

- Query Google PageSpeed Insights API v5 for `mobile` and `desktop` strategies:
  - Overall Category Scores: Performance, Accessibility, Best Practices, SEO (0–100).
  - Core Web Vitals & Key Audits:
    - First Contentful Paint (FCP)
    - Largest Contentful Paint (LCP)
    - Cumulative Layout Shift (CLS)
    - Total Blocking Time (TBT)
    - Speed Index
    - Time to Interactive (TTI)

---

## Output & Artifacts

Generate detailed report artifacts at `./reports/audit-report.pdf` (PDF format), `./reports/audit-report.md` (Markdown format), and `./reports/audit_data.json` (raw JSON data) structured as follows:

### 1. Executive Summary

- Overall status of https://erythro.ai.
- Total broken links count.
- Total spelling/grammar errors per language (`en`, `ru`, `he`).
- Total Dev Console errors & uncaught JavaScript exceptions.
- PageSpeed Insights overall performance score (`mobile` / `desktop`).

### 2. Broken Links Table

| Current Page Path | Target Link | Status Code / Error | Active Locale |
| :---------------- | :---------- | :------------------ | :------------ |

### 3. Dev Console & JS Errors Table

| Error Type (`console.error` / `console.warn` / `PageError`) | Message / Exception Snippet | Location / Source | Active Locale |
| :----------------------------------------------------------- | :-------------------------- | :---------------- | :------------ |

### 4. PageSpeed Insights & Performance Metrics Table

| Strategy | Performance Score | Accessibility Score | Best Practices Score | SEO Score | FCP | LCP | CLS | TBT | Speed Index |
| :------- | :---------------- | :------------------ | :------------------- | :-------- | :-- | :-- | :-- | :-- | :---------- |

### 5. Spelling & Grammar Issues Table

| Page / Section | Active Locale | Issue Found | Context / Text Snippet | Suggested Correction |
| :------------- | :------------ | :---------- | :--------------------- | :------------------- |

### 6. Translation & Structural Discrepancies

| Element / Section ID | EN Original Snippet | Target Locale (`ru` / `he`) | Issue Type (Missing Text / Untranslated Key / Bad Translation) | Current Target Snippet |
| :------------------- | :------------------ | :-------------------------- | :------------------------------------------------------------- | :--------------------- |

### 7. Layout & RTL Rendering Issues (`he`)

| Page / Component | Issue Description | Expected Behavior | Visual Impact |
| :--------------- | :---------------- | :---------------- | :------------ |
