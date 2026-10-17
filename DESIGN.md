# TransCap Design Language

> TransCap shows live translated captions over any film or video playing on an Android phone, for people who watch in a language they do not read well. Speech recognition and translation run on the phone.

Source of this file: the app theme (`app/src/main/java/com/voicecaption/ui/theme/Color.kt`, `Type.kt`,
`Spacing.kt`, `Theme.kt`), the caption overlay (`overlay/OverlayRenderer.kt`) and
`docs/design/2026-10-17-transcap-app-design-v2.md`. There is no Figma file, so nothing here was
read from a live design file. Values marked *(intent)* are design decisions not yet checked in code.

## 1. Visual Theme

**Theme statement:** Calm, focused, honest utility.

**Character:** A dark interface that stays out of the way of the video. One bright action (Start), one
accent for emphasis, and caption boxes whose contrast comes from their own background, never from
the picture behind them. Plain words, no decoration that does not help reading.

**Logo:** two stacked caption boxes: a small amber box (the original language) above a larger blue
box (the translation), each with text lines, on slate `#090D18`. It is the product's idea drawn
literally. Wordmark: **Trans** in the text colour, **Cap** in amber, system bold sans. Files in
`docs/design/logo/`: `transcap-mark.svg`, `transcap-mark-transparent.svg`, `transcap-icon-512.png`
(Play Store), `transcap-lockup.png`, `transcap-logo-sheet.png`. The Android launcher icon
(`ic_launcher_foreground.xml`, `ic_launcher_monochrome.xml`) and the notification icon
(`ic_notification.xml`) use the same shapes. Rules: keep the two colours as defined; keep every
shape inside the launcher's 66 dp safe circle; the mark is never used smaller than 24 dp; the
notification and themed icons are single colour.

**References:** Android Live Caption (familiar behaviour), subtitle practice in film (reading time,
two lines), dark media apps (video-first dark surfaces).

---

## 2. Color Palette

Dark is the default. The light theme follows the system setting. No dynamic (wallpaper) colour: the
palette is the brand.

### Neutral Scale
| Token | Dark | Light | Usage |
|-------|------|-------|-------|
| `Slate950` | `#090D18` | (text in light) | Dark background and surface; light-theme text; text on primary and accent |
| `Slate900` | `#1A1F2D` | - | Dark cards, grouped surfaces |
| `Slate50` | `#EAF2FF` | - | Dark-theme text |
| `Slate300` | `#9CA4BA` | - | Dark secondary text, outlines |
| `Slate600` | - | `#555D72` | Light secondary text, outlines |
| `Slate25` | - | `#F2F5FB` | Light cards *(not in the generated palette)* |
| `White` | - | `#FFFFFF` | Light background and surface |

### Brand Scale
| Token | Value | Usage |
|-------|-------|-------|
| `ElectricBlue300` | `#6092FF` | Primary on dark: Start button, selected controls, progress |
| `ElectricBlue600` | `#2439EE` | Primary on light |
| `Amber` | `#FFB21A` | Accent, both themes; always with dark text (`Slate950`) |

### Semantic Colors
| Role | Light | Dark |
|------|-------|------|
| Error | `Red700` `#AE1B00` | `Orange300` `#FF753E` |
| Success | not used as a colour; state is written ("Ready", "Installed") | same |
| Warning | written text in the error colour (contrast warning) | same |

### Caption colours (user choice, not theme)
| Setting | Presets | Default |
|---------|---------|---------|
| Text | `#FFFFFF` white, `#FFE45C` yellow, `#7FE3FF` cyan, `#8DF28D` green, `#D0D0D0` light grey | white |
| Plate background | `#000000` black, `#1C1C1C` dark grey, `#0B1A33` navy, `#24113D` purple, `#2B1A10` brown | black |

Both accept a custom `RRGGBB`. Stored fully opaque; the background slider (50 to 100 %) alone sets
transparency. A pair below 4.5:1 shows a written warning.

### Measured contrast (WCAG)
Dark: text on background 17.2:1, secondary text 7.8:1 (6.6:1 on cards), primary on background 6.5:1,
button label on primary 6.5:1, error 7.3:1, accent with dark text 10.7:1. Light: primary 7.3:1,
secondary 6.6:1, error 7.1:1. All 25 caption preset pairs 10.8:1 or higher. Minimum allowed: 4.5:1.

---

## 3. Typography

**Font families:** system font (Roboto on Android) for everything. No custom or web fonts.

**Type scale (sp):**
| Token | Size | Line height | Usage |
|-------|------|-------------|-------|
| `headlineMedium` | 32 | 40 | Onboarding titles |
| `headlineSmall` | 24 | 32 | Screen titles |
| `titleLarge` | 20 | 28 | Section headings (Settings) |
| `titleMedium` | 16 | 24 | Sub-headings, control titles |
| `bodyLarge` | 16 | 24 | Body, setting names |
| `bodyMedium` | 14 | 20 | Helper text, hints |
| `bodySmall` / `labelMedium` / `labelSmall` | 12 | 16 | Fine print, labels |
| `labelLarge` | 14 | 20 | Button text |

**Caption text:** 18 sp by default, 14 to 32 sp by choice (slider). The original-language box uses
80 % of that size and 85 % text opacity. Sizes are in sp, so caption text also follows the system
font size. Weight regular. Maximum 5 lines per box.

---

## 4. Components

| Component | Purpose | Key variants |
|-----------|---------|-------------|
| Primary button | The one main action (Start / Stop captions), 56 dp tall, full width | Start (primary), Stop (error outline *(intent)*), disabled with a reason |
| Language field | Choose film language or caption language, opens a list | Film (first entry "Auto-detect"), Captions |
| Swap button | Exchange the two languages | - |
| Status line | State in words plus an icon, never colour alone | Setup needed, Download needed, Downloading, Download failed, Ready, Listening, Not started |
| Download card | Size, progress, cancel, retry for the speech model and language pack | Idle, Downloading, Failed, Done |
| Segmented control | Choose one of a few options (speech model) | Auto, Fast, Accurate, SenseVoice |
| Slider with value | Caption size, background opacity; commits on release | - |
| Switch row | Whole row is the touch target (Show original text) | On, Off |
| Colour swatch | Pick a preset colour; ring plus tick when selected | Text colours, background colours |
| Hex field | Custom colour, applies when valid | Default, Error |
| Caption box | The floating text on screen | **Translation box** (main), **Original box** (smaller, above) |
| Preview | Shows both boxes with current size, colours, opacity | Original shown / hidden |
| Storage row | Installed model, size, delete with confirmation | Installed, Not installed, Delete disabled while running |
| Confirmation dialog | Names the action and the consequence | Delete model |
| Onboarding step | One idea per screen with a reason and one action | Intro, Overlay, Notifications, Audio capture, Languages and download |

---

## 5. Layout

**Grid:** single column on phones, 16 dp side margins.

**Spacing base unit:** 8 dp with a 4 dp mini-unit.

| Token | Value |
|-------|-------|
| `Mini` | 4 dp |
| `Small` | 8 dp |
| `Medium` | 12 dp |
| `Margin` | 16 dp |
| `Large` | 24 dp |
| `XLarge` | 32 dp |
| `XXLarge` | 48 dp |
| `Target` | 48 dp (minimum touch target) |
| `PrimaryButton` | 56 dp |

**Caption boxes:** radius 8 dp, padding 12 dp, 6 dp between the two boxes, bottom centre, at most 90 % of
the screen width, 48 dp above the bottom edge by default. Each box is its own window and is dragged
up or down on its own. Until the original box is moved it stays stacked just above the translation
box and follows it, also when the translation grows to more lines. After that it keeps the position
you gave it. "Reset caption positions" in Settings puts both back. Touches outside the boxes reach the video.

**Density mode:** default.

---

## 6. Depth

Flat. Hierarchy comes from surface tone (background `#090D18`, cards `#1A1F2D`), not shadows. There
are no drop shadows.

| Layer | Used for |
|-------|----------|
| Screen | App content on `background` |
| Card / surface variant | Grouped content, download card |
| Caption window | Above every other app (system overlay); boxes sit on the video |

Layering rule: caption boxes carry their own background, so they stay readable on any video. Their
transparency (50 to 100 %) is the only depth cue.

---

## 7. Do's and Don'ts

### Do
- Use theme tokens and the spacing scale; add a token before adding a raw value.
- Keep every text pair at 4.5:1 or higher, and warn on user-picked pairs below that.
- Keep touch targets at 48 dp or more.
- Say state in words plus an icon (Ready, Downloading, Installed), never colour alone.
- Show the real size before a download ("about 228 MB") and offer Cancel and Retry.
- Keep caption transparency and caption colour as separate settings.

### Don't
- Don't use raw hex values in screens; pick from the theme or the caption colour options.
- Don't put text or controls on the amber accent without dark text.
- Don't add shadows or gradients to interface surfaces.
- Don't promise accuracy, speed or language support that has not been measured.
- Don't say "plate" or "AI model" to users; say "caption box" and "speech model".

---

## 8. Responsive

Phones in portrait are the design target. On rotation or folding, the caption box width and its
vertical position are recomputed so a landscape width never stays on a portrait screen. Interface at
200 % system font size must still show every control (the speech-model control wraps or shortens its
labels). Tablets and landscape Settings layouts have not been designed yet *(intent: single column,
centred, max content width 600 dp)*.

---

## 9. Agent Prompt Guide

Invariants for any agent working on TransCap:

- Colours come from `ui/theme/Color.kt` and `core/ColorMath.kt`; never hard-code a new colour in a screen.
- Which speech model runs is decided in one place, `CaptionSettings.activeModel`. Starting captions, the download button and the shown size must all use it.
- Contrast stays at or above 4.5:1 (`ColorMath.MIN_READABLE_CONTRAST`); warn, do not silently change a user's colour.
- User-facing words: "caption box" (not plate), "speech model", "film language", "captions in", "language pack". The app is **TransCap**.
- Do not change `applicationId` (`com.voicecaption`); installed copies must keep updating.
- Never log audio, transcripts or tokens.
- Logo, launcher icon and notification icon share one set of shapes; change them together and keep all of them inside the 66 dp safe circle.
- Debug-only diagnostics (stats line, raw text) must never appear in release builds.

---

## 10. Motion

Captions change without animation (live text is replaced in place). Interface transitions are short
fades of 150 to 200 ms *(intent)*; with "remove animations" on, there are none. Motion is never
used to decorate.

---

## 11. Voice & Tone

**Voice:** a helpful person: plain, brief, never blaming. Errors say what happened, then what to do.
Buttons start with a verb.

| Prefer | Avoid |
|--------|-------|
| caption box | plate, overlay (to users) |
| speech model | AI model, engine |
| film language / captions in | source / target language |
| Auto-detect | auto, automatic language |
| language pack | translation pack, dictionary |
| Can't hear this app | Audio capture unavailable |

**Conventions:** sentence case; no exclamation marks; numbers with units ("about 228 MB"); proper
names stay as written (Whisper, SenseVoice, Wi-Fi). Error pattern: result, then cause, then next
step ("Can't hear this app. It blocks audio capture. Try another player."). Full copy table:
`docs/design/2026-10-17-transcap-app-design-v2.md`, section 9.
