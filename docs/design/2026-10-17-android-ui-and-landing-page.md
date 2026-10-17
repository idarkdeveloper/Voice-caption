# Android UI and Landing Page Design

Date: 2026-10-17
Status: Draft for review
Maturity: **L0 (greenfield)**. No `DESIGN.md` or design system exists. Every
concrete value below (hex, px/dp, sp) is **provisional**, chosen to be sensible
and consistent, and must be reconciled once a design language exists. Nothing
here is a silent "system": run `/creative` then `/map-design` to turn these
decisions into `DESIGN.md`.

Backend design is a separate document (`2026-10-17-paid-tier-backend-design.md`).
The earlier idea of a Chrome extension is dropped: the web deliverable is a
landing page only.

## 1. Design principles applied

- **Honest** (critical): state the DRM limit up front (no Netflix-style apps), state what the free
  tier does and what the paid tier adds, and never promise "any film".
- **Minimal / killer UX**: the key journey is "open a film, tap Start, read captions". The home screen
  has one primary action.
- **Perceptible**: captions must stay readable over any video, so contrast comes from an opaque
  caption plate, not from the video behind it. Status never relies on color alone.
- **Tolerance for error**: every failure (blocked app, offline, missing language pack) has a plain
  message and a next step; nothing crashes silently.
- **Equitable**: free users get the full on-device experience; the paid tier is quality and speed, not
  a gate on basic captioning.

## 2. Provisional foundations (shared by app and landing page)

| Token | Value (provisional) | Notes |
|---|---|---|
| Spacing base | 8 dp/px, with a 4 dp mini-unit inside components | `space-1..8` = 4, 8, 12, 16, 24, 32, 48 |
| Type | System sans (Roboto on Android, system-ui on web) | Scale 12/14/16/20/24/32/48 |
| Caption type | 18 sp default, user range 14–32 sp | Scales with system font size |
| Surface (dark) | `#12151A` | App default is dark: used next to video |
| Surface (light) | `#FFFFFF` | Follows system theme |
| Primary accent | `#2DD4BF` on dark, `#0F766E` on light | Contrast checked, see below |
| Caption plate | `#000000` at user opacity (default 80%), text `#FFFFFF` | White on black ≥ 12:1 at 80% over dark video; floor opacity 50% |
| Status colors | ok `#34D399`, warn `#FBBF24`, error `#F87171` | Always paired with an icon and text |
| Touch targets | ≥ 48 dp | Applies to all controls |
| Motion | 150–200 ms fades; caption text changes without animation | Purposeful only; respect "remove animations" |

Contrast target: body text ≥ 4.5:1, caption text ≥ 7:1, UI icons ≥ 3:1. These are checked in the
Task 11 review before release.

## 3. Android app

### 3.1 Screen map

```
First run → Onboarding (3 steps) → Home ⇄ Settings
                                     └→ (later plan) Paywall
Overlay (floating, over any app) is driven by the foreground service.
```

### 3.2 Onboarding (first run only, one step per screen, skippable back)

| Step | Content | Primary action | Failure handling |
|---|---|---|---|
| 1. What it does | One sentence + a looping 5 s illustration of captions over a video; **plain note: "Doesn't work with apps that block audio capture, such as some streaming apps."** | Continue | none |
| 2. Permissions | Explain why: "Show captions over other apps" (overlay), "Keep running while you watch" (notifications). One button per permission, showing granted/not-granted with icon + text | Grant overlay / Grant notifications | Denied → explain what will not work, offer "Open settings", allow Continue only when overlay granted |
| 3. Languages and download | Pick "Film language" and "Show captions in"; shows download size for the speech model and language pack, progress bar with percent text | Download | Offline or failed → error text + Retry; Start stays disabled on Home until both are ready |

### 3.3 Home

Layout pattern: single column, vertically centered content (mobile-first, 4-col grid, 16 dp margins).

| Zone | Content |
|---|---|
| Header | App name; settings icon button (48 dp) |
| Language pair | Two tappable fields "Film language" → "Captions in" with a swap button |
| Primary action | One large button: **Start captions** (becomes **Stop captions** while running). Full width, 56 dp tall |
| Status line | Text + icon: "Ready", "Listening", "Offline mode", "Can't capture this app" |
| Quality note (free tier) | Small text: "On-device captions. Cloud mode (faster, more accurate) is coming." Hidden until paid tier exists |

Start flow: tap Start → system screen-capture consent dialog (Android requires it every session; app shows a
one-line pre-explanation: "Android will ask to record your screen. Captions only use the audio.") →
service starts → app can be left; overlay appears when sound plays.

### 3.4 Settings

| Setting | Control | Default | Live preview |
|---|---|---|---|
| Caption size | Slider 14–32 sp with value label | 18 | Sample caption shown above sliders |
| Caption background | Slider 50–100% | 80% | Same sample |
| Show original text | Switch (bilingual) | Off | Same sample |
| Speech model | Segmented: Fast (base) / Accurate (small) | Fast | Helper text: "Accurate uses more battery and may lag on older phones" |
| Languages | Same two fields as Home | | |
| Storage | Row showing model sizes with Delete | | Confirm before delete (tolerance for error) |

### 3.5 Caption overlay

- Bottom-center plate, max width 90% of screen, 2 lines max, radius 8 dp, inset 12 dp.
- Draggable vertically; position remembered; never covers the status bar or gesture area.
- Touches outside the plate pass through to the video app.
- Translation-only by default; with "Show original text", the source line is smaller (80%) and
  above the translation.

Overlay states:

| State | What the user sees | Notes |
|---|---|---|
| Listening, no speech | Nothing (plate hidden) | A small 24 dp dot at the plate position fades out after 2 s so the user knows it is active |
| Source text arrived | Source-language text, dimmed | Replaced in place when translation arrives, no flicker |
| Final caption | Translated text at full contrast | Clears after 4 s of silence |
| Capture blocked | Plate message: "This app doesn't allow audio capture" + icon | After ~5 s of silence while media plays |
| Model/language pack missing, offline | Plate message: "Language pack not downloaded. Open the app to download." | Never crashes |
| Offline mode (paid tier, later) | Small "Offline" badge beside the plate | Not shown for free tier |

### 3.6 Accessibility (Android)

- All controls ≥ 48 dp, with content descriptions; TalkBack reads status line changes.
- Overlay is `importantForAccessibility=no` for each caption update (avoids constant interruptions); a
  Settings option "Announce captions with TalkBack" is out of scope for v1.
- Respect system font scale and dark/light theme; verify at 200% font scale.
- Status never conveyed by color alone.

### 3.7 Heuristic check

| Heuristic | Finding | Severity | Adjustment |
|---|---|---|---|
| Visibility of system status | Running state visible in status line and notification | OK | none |
| Error prevention | Capture consent is a system dialog the user may dismiss | P2 | Pre-explain on Home; show "Not started" if dismissed |
| Recognition over recall | Language pair repeated in Home and Settings | P2 | Single source of truth in settings repository (Task 11); same component in both |
| Honesty | DRM limit shown in onboarding and in the blocked message | OK | none |

## 4. Landing page

**Goal:** a visitor understands the app in 10 seconds, trusts it (honest limits, privacy), and reaches
the download in one tap. **Not** a Chrome extension; a static website.

### 4.1 Page structure (mobile-first, one column on mobile, 12-col grid ≥ 1024 px)

Each section uses a different layout family to avoid a repeated template.

| # | Section | Layout | Content |
|---|---|---|---|
| 1 | Hero | Split (text left, phone mockup right; stacked on mobile) | H1: "Live captions for any film, in your language." Sub: "Watch in any language. Captions appear over your video, translated as it plays." Primary button: **Get it on Google Play**. Secondary text: "Android 10 or newer. Free." Phone mockup shows the caption plate over a video frame |
| 2 | How it works | Three numbered steps in a horizontal row (stacked on mobile) | 1 Pick your languages. 2 Tap Start and play a film in any app. 3 Read translated captions on screen |
| 3 | Free vs. paid | Two-column comparison table | On-device (free): private, works offline, 2–4 s delay. Cloud (coming): ~1 s delay, higher accuracy, fair-use cap. **Cloud tier marked "Coming soon" until it exists** (honesty) |
| 4 | Privacy and limits | Full-width band, plain text list | "Free tier: audio never leaves your phone." "Cloud tier (optional): audio is processed, never stored." "Doesn't work with apps that block audio capture, such as some streaming apps." |
| 5 | FAQ | Accordion, 5–6 items | Which apps work? Does it need internet? Which languages? Battery? Why does Android ask to record my screen? |
| 6 | Final call | Centered, large button repeated | Same download button |
| Footer | | Links: Privacy policy, Terms, Contact | Privacy policy is required for the store listing |

Download button behavior: links to the Play Store listing URL. **Until the app is published the
button shows "Coming soon" with an email sign-up (no fake link).** Do not present the page with a
dead or invented store URL.

### 4.2 Content rules

- No unverified claims: no "99% accurate", no language count until measured (benchmark results in
  `docs/benchmarks/free-tier.md` supply real numbers after Task 12).
- Imagery: phone mockup and caption demo are placeholders until real screenshots exist. Mark them as
  placeholders in the page source and replace with real captures from the app; do not ship AI-drafted
  imagery as final.
- Languages and a supported-apps list come from what is actually tested.

### 4.3 Technical notes

- Static site (single HTML page with CSS, no framework needed), or a small static-site generator if the
  team prefers; deploy on any static host (e.g. Cloudflare Pages).
- Performance: no layout shift, images with explicit sizes, system fonts (no web-font download).
- Accessibility: semantic landmarks, visible focus rings, contrast ≥ 4.5:1, FAQ accordion keyboard
  operable, `prefers-color-scheme` and `prefers-reduced-motion` respected.
- SEO basics: title, meta description, Open Graph image, structured data for a mobile application.

### 4.4 Responsive behavior

| Breakpoint | Change |
|---|---|
| < 480 px | Single column, 16 px margins, buttons full width |
| 480–767 px | Two-column FAQ is not used; steps remain stacked |
| 768–1023 px | Steps in a row; comparison table side by side; 24 px margins |
| ≥ 1024 px | 12-col grid, hero split, 48 px margins, max content width 1120 px |

## 5. Gaps and next steps

- No DS exists, so there are no component gaps to file. Once `DESIGN.md` exists, reconcile the provisional
  tokens in section 2.
- Run `/creative` to choose a visual direction (logo, accent, tone) before the landing page is built.
- The landing page should become its own small plan (static site + deployment); it is not part of the
  Android free-tier plan.
- The Paywall screen belongs with the billing plan.
