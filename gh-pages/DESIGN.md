# Virga showcase site — build contract

This is the shared spec for a static GitHub Pages site showcasing the Virga Android app.
Three agents build in parallel against this contract (CSS+assets, HTML, JS). Do not
deviate from the token names, file ownership, or the class/ID contract — they're how the
separately-built files fit together. Source of truth for brand: the app's `BRAND.md`.

## What Virga is (ground truth — do not invent beyond this)

Virga is an open-source Android app that backs up and syncs your phone to cloud storage,
powered by a bundled rclone engine. No account with the developer, no tracking, credentials
stay encrypted on the device. Pre-release (version 0.1.0): not yet on app stores.

Brand essence (BRAND.md §1): **"Weather for your data."** A *virga* is rain that evaporates
before it reaches the ground. Data in motion should feel calm, legible, atmospheric — never a
scary technical wall. Personality: calm, trustworthy, atmospheric, beginner-safe by default,
deeply capable on demand, honest about what happens to your files. NOT loud, gamified, flashy.

Voice (BRAND.md §2): plain, reassuring, second person ("your files", "your phone"). Buttons are
verbs. Use the product vocabulary, no synonyms: **Remote / Cloud account**, **Sync task**, **Run**,
**Upload** (phone→cloud), **Download** (cloud→phone), **Two-way sync** (not "bisync"), **Mirror**
(with a warning), **Source** (local folder), **Destination** (cloud folder).

Apply the anti-ai-slop-writing rules to every word of copy: no "leverage/robust/seamless/
comprehensive/ecosystem/streamline/effortless", no rule-of-three padding, vary sentence length,
active voice, contractions, at most one em dash per ~500 words.

## Deployment constraints (all agents respect these)

- Plain static site. No framework, no bundler, no build step. Vanilla HTML/CSS/JS only.
- Served from a project Pages URL under a subpath (`/virga/`). **All asset paths must be
  relative** (`assets/logo.svg`, `styles.css`, `main.js`) — never root-absolute (`/assets/...`).
- One web font allowed: Manrope, loaded from Google Fonts with `rel="preconnect"` + a
  `media="print" onload` swap or `display=swap`. Body text uses the system stack (no web font).
- Must work with JS disabled (content and layout intact; JS is enhancement only).
- WCAG 2.1 AA: text contrast ≥ 4.5:1 (≥3:1 large), visible focus, semantic landmarks, alt text.
- Honor `prefers-color-scheme` (ship light AND dark) and `prefers-reduced-motion` (kill the
  precipitation + reveal animations, show the static end state).
- Fast: no libraries, inline critical structure, lazy/`loading="lazy"` any imagery, total JS small.

## File ownership (no agent edits another's file)

- **Agent CSS** → `styles.css`, `assets/logo.svg`, `assets/favicon.svg` (+ `assets/og-image.svg` optional).
- **Agent HTML** → `index.html`.
- **Agent JS** → `main.js`.
- Lead (already done) → `.nojekyll`, this `DESIGN.md`.

## Color tokens (CSS custom properties — exact names + values, from BRAND.md §4)

Define on `:root` (light) and override the surface/text set under
`@media (prefers-color-scheme: dark)`.

Brand seeds (constant in both themes):
- `--virga-blue: #1E6FD9;`  `--virga-teal: #1FA8A0;`  `--virga-blue-dark: #0B3C7A;`
- `--virga-error: #BA1A1A;`  `--virga-success: #2E7D32;`  `--virga-warning: #B26A00;`

Light surfaces/text (define):
- `--bg: #F7F9FC;` `--surface: #FFFFFF;` `--surface-2: #EEF3FA;`
- `--text: #0E1726;` `--text-muted: #4A5568;` `--border: #D8E0EC;`

Dark surfaces/text (override under the dark media query):
- `--bg: #0A1220;` `--surface: #111B2E;` `--surface-2: #16223A;`
- `--text: #E8EEF7;` `--text-muted: #9FB0C6;` `--border: #233148;`
  (In dark, accents may lighten for contrast: a `--virga-teal-bright: #4FD0C7` and
  `--virga-blue-bright: #5B9BF0` are allowed for text/links on dark.)

Gradient (BRAND.md §4.5 — the hero "atmosphere", used sparingly, never behind body text
without a scrim): `--grad-hero: linear-gradient(160deg, var(--virga-blue), var(--virga-teal));`
dark variant starts at `--virga-blue-dark`. The streak gradient in the logo is
blue→teal→transparent (see logo spec).

## Type, spacing, radius, shadow tokens

- `--font-display: "Manrope", system-ui, sans-serif;` (Display/headlines — tight, heavy: 700–800)
- `--font-body: system-ui, -apple-system, "Segoe UI", Roboto, sans-serif;`
- Fluid display sizes with `clamp()`. Hero title large but never cartoonish; this brand is calm.
- Spacing scale (web rhythm, larger than the app's): `--space-xs:4px; --space-sm:8px;
  --space-md:16px; --space-lg:24px; --space-xl:32px; --space-2xl:48px; --space-3xl:80px;`
- Radius: `--radius-sm:8px; --radius-md:16px; --radius-lg:28px;` Cards = `--radius-md`,
  hero/feature surfaces may use `--radius-lg`. Pills/buttons = full.
- Elevation by tone first (use `--surface`/`--surface-2`); reserve a soft shadow token
  `--shadow: 0 6px 24px rgba(11,60,122,0.10);` for the hero card and buttons only.
- Content max width ~1080px, centered, `--space-md` gutters; sections get `--space-3xl` vertical.

## Logo spec (Agent CSS recreates this as `assets/logo.svg`, `viewBox="0 0 108 108"`)

The Virga mark from `ic_launcher_foreground.xml` — a soft blue→teal streak that fades before a
baseline, trailed by three shrinking, fading teal droplets ("rain that evaporates"):

- Streak: rounded stadium path `M47,33 A7,7 0 0 1 61,33 L61,53 A7,7 0 0 1 47,53 Z`, filled with a
  vertical linear gradient (x 54, y 26→60): stop `0%` `#1E6FD9`, `55%` `#1FA8A0`, `100%`
  `#1FA8A0` at 0 opacity.
- Droplets (solid teal `#1FA8A0`, decreasing size + opacity):
  `M50,67 a4,4 0 1 0 8,0 a4,4 0 1 0 -8,0 Z` (opacity 1),
  `M51.4,75 a2.6,2.6 0 1 0 5.2,0 a2.6,2.6 0 1 0 -5.2,0 Z` (opacity .8),
  `M52.3,81 a1.7,1.7 0 1 0 3.4,0 a1.7,1.7 0 1 0 -3.4,0 Z` (opacity .6).
- Provide it on a transparent background (the navy disc is optional for a favicon variant).
- `favicon.svg`: the mark on a `--virga-blue-dark` (#0B3C7A) rounded-square background.
- The wordmark "Virga" in the header is just `--font-display` text next to the mark, not in the SVG.

## Page structure, section IDs, and exact content (Agent HTML owns; classes are the contract)

Order and required hooks (IDs/classes used by CSS and JS — keep them exact):

1. `<header class="site-header">` — `.site-header__brand` (the `assets/logo.svg` `<img>` + the
   word "Virga"), `<nav class="site-nav">` with anchor links to #features #privacy #how #get and
   a "GitHub" link. A `<button class="nav-toggle" aria-expanded="false" aria-controls="site-nav">`
   for mobile (JS toggles it).

2. `<section id="hero" class="hero">`
   - `<canvas id="precip" class="hero__precip" aria-hidden="true"></canvas>` (JS draws rain;
     pure decoration). CSS must make the hero readable even if the canvas is empty.
   - `.hero__mark` (large logo), `<h1 class="hero__title">Weather for your data</h1>`,
   - `<p class="hero__promise">` — copy (tighten, keep honest):
     "Connect a cloud account, pick a folder, and have a working backup in under a minute — while
     every rclone capability stays one deliberate tap away."
   - `.hero__cta`: primary `<a class="btn btn--primary" href="https://github.com/lusky3/virga/releases">Get it on GitHub</a>`,
     secondary `<a class="btn btn--secondary" href="https://github.com/lusky3/virga/wiki">Read the docs</a>`.
   - A small honest note: "Open source · Android 8+ · Pre-release".

3. `<section id="what" class="section section--intro">` — the metaphor, 2 short paragraphs:
   what Virga does in plain terms, and the "virga = rain that evaporates before it lands" idea
   tied to calm, legible data movement. No feature list here.

4. `<section id="features" class="section">` `<h2>` + `<ul class="features">` of
   `<li class="feature">` each with `.feature__icon` (inline SVG, decorative `aria-hidden`),
   `.feature__title`, `.feature__body`. Use SIX features (not three — avoid rule-of-three):
   - **Set up in under a minute** — a guided first sync: connect an account, pick a folder, done.
   - **Your clouds, your way** — sign in to Google Drive, OneDrive, or Dropbox; or point Virga at
     S3, WebDAV, SFTP and dozens more through rclone. (Verify rclone's backend count from a
     reliable source; if unsure say "dozens of storage providers" — do not fabricate a number.)
   - **One-way or two-way** — Upload, Download, or Two-way sync. Mirror is opt-in and warns you
     before it deletes anything.
   - **Runs on your terms** — schedules, Wi-Fi-only, charge-only, and separate Wi-Fi/mobile speed
     limits. It waits for the right moment.
   - **Private by design** — your credentials are encrypted on the device and rclone runs locally.
     Nothing passes through anyone's server. No tracking; crash reports are off until you opt in.
   - **Power when you want it** — filters, checksums, and a dry-run preview are a tap away, without
     cluttering the simple path.

5. `<section id="privacy" class="section section--privacy">` — a stronger statement. Bullet the
   real facts (each is a `<li>`, keep it to ~5): encrypted config via Android Keystore;
   loopback-only (rclone never exposed to the network); OAuth uses PKCE and tokens stay on the
   phone; the F-Droid/foss build ships no telemetry; crash reporting is opt-in and redacts paths,
   remote names, and tokens. Link the privacy policy:
   `https://github.com/lusky3/virga/blob/main/docs/privacy-policy.md`.

6. `<section id="how" class="section">` `<h2>How it works</h2>` + `<ol class="steps">` of
   `<li class="step">` with `.step__num`, `.step__title`, `.step__body`. THREE steps is fine here
   (it genuinely is a 3-step flow): 1) Connect a cloud account. 2) Pick a folder to back up.
   3) Virga keeps it in sync — on your schedule, on your terms.

7. `<section id="get" class="section section--cta">` — honest availability:
   "Virga is pre-release and open source. Grab the latest build from GitHub Releases, or build it
   yourself. F-Droid and Google Play are planned." Buttons: GitHub Releases (primary), Build from
   source → `https://github.com/lusky3/virga/wiki/Building-from-Source` (secondary). Optionally a
   disabled/"coming soon" F-Droid + Play row — if shown, label them clearly as planned, not live.

8. `<footer class="site-footer">` — links: GitHub repo, Wiki, Privacy policy, and a credit line:
   "Built on [rclone](https://rclone.org). Virga isn't affiliated with the rclone project."
   License line: read the repo's `LICENSE` (Agent HTML: open `../virga/LICENSE` to state it
   correctly; if absent, say "open source" and link the repo — do not guess a license name).
   Close with the brand line: "Virga — weather for your data."

### JS hooks (Agent JS binds to these; Agent HTML must include them)
- `#precip` canvas → precipitation animation (calm, slow downward streaks/droplets that fade,
  tinted teal/blue; low density; pause when tab hidden; full stop under prefers-reduced-motion).
- Elements with `data-reveal` → fade/slide-in on scroll via IntersectionObserver; if
  prefers-reduced-motion or no JS, they're simply visible (CSS default = visible; JS adds the
  pre-animation state only when motion is allowed).
- `.nav-toggle` + `#site-nav` → mobile menu open/close, manage `aria-expanded`.
- Optional: active-section highlight in the nav on scroll.

## Quality bar
Calm, premium, atmospheric — like the app. Restraint over flash. The gradient and the
precipitation are the only "effects"; everything else is clean type, generous space, tonal
surfaces. If it feels like a loud SaaS landing page, it's wrong.
