# Phone-Sync — Exhaustive Screen + UI Inventory

> Hand this document to the design AI. Each entry lists: purpose, surface type (screen / modal / bottom-sheet / popover / toast / inline), required content, interaction states, and which engineering phase it's needed in.
>
> **Scope:** Android mobile app (Expo + native module) AND Tauri desktop app. iOS is stubbed — skip.

---

## Platform / Surface Legend

- **M-Screen** — full Android screen, Expo Router route.
- **M-Modal** — Android full-screen modal (blocking, with close button).
- **M-Sheet** — Android bottom sheet (draggable, detents).
- **M-Popover** — Android popover / menu / tooltip.
- **M-Toast** — Android transient snackbar.
- **M-Inline** — embedded card / section inside a screen.
- **M-Notif** — system notification posted by the app itself (not a mirror).
- **D-Window** — Tauri desktop window.
- **D-Tray** — Tauri tray menu / menu-bar popover.
- **D-Modal** — desktop modal / dialog.
- **D-Toast** — desktop toast / native OS notification.

---

## App-Wide Primitives

Design once, reuse everywhere. List these first so the designer establishes baselines.

| ID | Primitive | Notes |
|---|---|---|
| P-01 | App icon (Android foreground + background) | Adaptive icon, all density buckets |
| P-02 | App icon (Tauri desktop) | macOS .icns, Windows .ico, Linux PNG set |
| P-03 | Notification channel icon (small + large) | Mirrored notifications use this as default |
| P-04 | Connection-status dot | 4 states: `LAN` (green), `Relay` (blue), `Offline` (red), `Pairing` (amber pulse) |
| P-05 | Device chip | Avatar + label like `Pixel 9 Pro` — used in status + history |
| P-06 | App chip (package icon + name) | For notification cards and app-filter lists |
| P-07 | Fingerprint display | 16 groups of 4 uppercase hex, monospaced, color-highlighted on match |
| P-08 | Loading states | Inline spinner, skeleton, full-screen loader with message |
| P-09 | Empty states | Illustration + headline + CTA — see contexts below |
| P-10 | Error card | Inline, with retry + dismiss actions |
| P-11 | Destructive confirmation | Modal pattern used by unpair, clear history, etc. |
| P-12 | Code / monospace blocks | For showing relay URL, pair token, JWT, keys |
| P-13 | Theme tokens | Light/dark/system — Material You tinting if opted in |
| P-14 | Typography scale | Display, title, body, caption, mono |

---

## 1 · Onboarding (Phase 3+)

First-launch flow. User has fresh install, no pair, no permissions granted.

| ID | Name | Surface | Purpose | Content |
|---|---|---|---|---|
| ON-01 | Welcome | M-Screen | Hook the user | Hero illustration, tagline (“Mirror notifications between your phones, end-to-end encrypted”), `Continue` |
| ON-02 | How it works (carousel) | M-Screen | Explain the 3 pillars | 3 swipeable cards: pairing, mirroring, privacy. Skip + Next |
| ON-03 | Choose role | M-Screen | Decide "this is my first phone" vs "I have a code from my other phone" | 2 big buttons: `Start pairing (this is Phone A)`, `Scan code from other phone (this is Phone B)` |
| ON-04 | Relay setup | M-Screen | Point the app at a relay | Default suggestion (`localhost` / laptop IP for dev), paste URL, test connection button → shows P-04 |
| ON-05 | Permissions needed | M-Screen | Explain why we need access | 3 permission cards with rationale: `POST_NOTIFICATIONS`, `Notification access`, `Nearby WiFi / Local Network`. Each has a `Grant` button that deep-links to system settings |
| ON-06 | OEM reliability setup | M-Screen | OEM-specific battery fix-up | Detects manufacturer (Xiaomi / Samsung / Oppo / OnePlus / generic). Shows bespoke instructions + deep links to system settings pages |
| ON-07 | Ready | M-Screen | Confetti-moment + CTA to pair | “You’re ready. Pair your other phone now.” button → PAIR-01 |

**Empty-state onboarding variants:**
- ON-08 — "Peer device not yet paired" card shown on Home screen when no pair record exists.

---

## 2 · Pairing Flow (Phase 2 surface, Phase 3 UI)

Two roles: **Initiator (Phone A)** displays QR + approves B's fingerprint; **Joiner (Phone B)** scans QR + approves A's fingerprint + pulls sig.

| ID | Name | Surface | Purpose | Content |
|---|---|---|---|---|
| PAIR-01 | Pair role chooser | M-Screen | Re-entry to ON-03 logic if already past onboarding | Same 2 buttons |
| PAIR-02 | Generating keys | M-Screen | Loading while CryptoStore generates X25519 + Ed25519 keypairs (first time can take ~1–2s) | Spinner + status line |
| PAIR-03 | Show QR (Device A) | M-Screen | Display pair QR to be scanned | Large QR (60–70 % of screen width), pair token shown as plaintext fallback for copy, 5-minute countdown timer, `Cancel` button |
| PAIR-04 | Waiting for peer (Device A) | M-Screen | After B scanned QR, relay pushes event → A is waiting to see B’s fingerprint | Pulsing indicator + explanation text |
| PAIR-05 | Scan QR (Device B) | M-Screen | Camera view with QR overlay frame | Front camera, torch toggle, `Enter code manually` fallback |
| PAIR-06 | Manual pair entry (Device B) | M-Sheet | If camera unavailable or QR faulty | Paste-into textarea accepts the JSON payload; shows `device_id` + `relay_url` when valid |
| PAIR-07 | Confirm Device B's fingerprint (on A) | M-Screen | Two-sided: A sees B's fingerprint, compares visually | Big P-07 fingerprint, `Approve and sign` / `Reject` buttons, instruction "Check that this matches what Phone B is showing" |
| PAIR-08 | Confirm Device A's fingerprint (on B) | M-Screen | Mirror of PAIR-07 | Same layout |
| PAIR-09 | Transferring signature | M-Sheet | Transient state — A's confirmation_sig en route to B via relay WebSocket | Progress ring |
| PAIR-10 | Paired success | M-Screen | Both devices now trusted | Success illustration, peer device name, `Done` → Home (HOM-01) |
| PAIR-11 | Pair failed | M-Screen | Token expired, fingerprints didn't match, network failed, relay rejected sig | Error explanation + `Try again` / `Start over` |
| PAIR-12 | Unpair confirm | M-Modal | P-11 instance — confirms user wants to disconnect | "This will clear peer keys and rotate yours. You'll need to pair again." Destructive `Unpair`, `Cancel` |
| PAIR-13 | Unpaired | M-Toast | Confirmation after successful unpair | Redirects to ON-03 |

---

## 3 · Home / Status (always-visible main screen)

| ID | Name | Surface | Purpose | Content |
|---|---|---|---|---|
| HOM-01 | Home | M-Screen | Primary dashboard | Big connection-status card (P-04 + text), peer device chip (P-05), mirror on/off switch, last-synced timestamp, small activity summary (e.g. “12 mirrored today”), CTA row (Pair more devices / Settings), recent activity list preview (last 3 items) with "See all" link |
| HOM-02 | Troubleshooting card (inline) | M-Inline | Surfaces when connection drops | Explains why (LAN unreachable, relay down, permission revoked), shows fix actions |
| HOM-03 | Permission missing card (inline) | M-Inline | If NLS grant was revoked or POST_NOTIFICATIONS denied | Red banner with "Fix now" button |
| HOM-04 | OEM warning card (inline) | M-Inline | If battery optimization is enabled on known-hostile OEM | Explains risk + fix |
| HOM-05 | FCM quota warning (inline) | M-Inline | After a burst of notifications triggered FCM throttling | "Some notifications may be delayed" with explanation |
| HOM-06 | Mirror-off global toggle | M-Popover | Quick-toggle to pause mirroring | From Home + from M-Tray / persistent notification |
| HOM-07 | Always-connected mode toggle | M-Popover | User-visible choice between lazy-FGS and always-on | Exposed in Settings primarily; shortcut from Home |

---

## 4 · Notification Mirror Rendering (Phase 3+)

Mirrored notifications posted BY Phone-Sync itself. Follow the spec's Phase 1 policy: single `mirrored_notifications` channel, `IMPORTANCE_DEFAULT`, `VISIBILITY_PRIVATE` by default.

| ID | Name | Surface | Purpose | Content |
|---|---|---|---|---|
| MIR-01 | Mirror notification (Phase 1 form) | M-Notif | Basic mirror — title + body + origin app icon | Title, body, app chip (P-06), sub-text “Mirrored from <device>”, dismiss + reply (if supported) actions |
| MIR-02 | Mirror notification (Phase 2 form) | M-Notif | MessagingStyle reconstruction | Chat bubbles, sender avatars, inline reply |
| MIR-03 | Reply composer (inline on notification) | M-Inline | RemoteInput sheet | Text field, send button, status on sent |
| MIR-04 | Reply-failed toast | M-Toast | Shown when reply comes back with `reply.failed` | "Couldn’t send reply — open the conversation on <peer>" |
| MIR-05 | Persistent FG notification | M-Notif | Required for the foreground SyncService | Status line ("Phone-Sync active — LAN connected"), quick actions (pause / open app) |

---

## 5 · History / Activity

| ID | Name | Surface | Purpose | Content |
|---|---|---|---|---|
| HIS-01 | History list | M-Screen | Chronological list of every mirrored notification (for debug + trust) | Search bar, filter chips (app, direction, reply sent), rows show app chip + timestamp + title + direction (↑ posted from this device / ↓ received mirror) |
| HIS-02 | History row details | M-Sheet | Tap a row | Full content, canon_id, timestamps (origin, relay, posted), encrypted-payload size, `Delete from history` |
| HIS-03 | Clear history confirmation | M-Modal | P-11 instance | Cleans local-only history; does NOT affect peer |

---

## 6 · App Filter (Allowlist / Denylist)

| ID | Name | Surface | Purpose | Content |
|---|---|---|---|---|
| FIL-01 | App filter list | M-Screen | Full list of installed apps with toggles | Search, scoped tabs: `All`, `Mirrored`, `Blocked`. Toggle per row. Category chips (Banking / OTP / Messaging / Media). |
| FIL-02 | Default denylist explainer | M-Modal | On first open of FIL-01 — shows the shipped hash-verified denylist | List of common OTP + banking apps pre-blocked, explains integrity check, `Review and customize` |
| FIL-03 | Tampered-denylist warning | M-Modal | If SHA-256 hash of denylist JSON doesn't match compiled constant | "Your build may be tampered with. Mirroring is disabled." blocking error, only quit option |
| FIL-04 | App detail sheet | M-Sheet | Tap on an app row | Shows per-channel toggles (Phase 2), visibility policy, "Always block this app" quick action |
| FIL-05 | Category bulk action | M-Popover | "Block all banking apps" style | Preview list before apply |

---

## 7 · Settings

| ID | Name | Surface | Purpose | Content |
|---|---|---|---|---|
| SET-01 | Settings root | M-Screen | Navigation hub | Grouped cards: Pairing, Sync, Privacy, Reliability, Battery, About |
| SET-02 | Pairing settings | M-Screen | Peer device info, unpair | Peer name, peer fingerprint, pair date, `Unpair` (→ PAIR-12) |
| SET-03 | Sync settings | M-Screen | Relay URL, transport preference, LAN toggle | Relay URL field + test button, LAN preferred toggle, Always Connected toggle (HOM-07), FCM enabled toggle |
| SET-04 | Privacy settings | M-Screen | Allowlist/denylist quick access, lock-screen visibility, private mode | Link to FIL-01, global VISIBILITY toggle, Android Auto exclusion toggle |
| SET-05 | Reliability settings | M-Screen | OEM fixes, battery optimization | Per-OEM instructions (auto-detect), permission audit row list, quick links to system settings |
| SET-06 | Battery settings | M-Screen | Battery-efficiency profile | Radio: Lazy FGS (default), Always Connected. Show projected %/day impact. |
| SET-07 | Advanced / debug | M-Screen | Hidden behind long-press on version | Device ID, pubkeys (copy), relay ping test, force regenerate keys, clear history |
| SET-08 | About | M-Screen | Version, licenses, privacy policy link, source code link | |
| SET-09 | Notification channel manage | M-Screen | Deep link to system notification channel settings for mirrored_notifications | |

---

## 8 · Error / Recovery surfaces

| ID | Name | Surface | Purpose | Content |
|---|---|---|---|---|
| ERR-01 | Relay unreachable | M-Inline + M-Screen full-page variant | Can't reach relay for N minutes | Explain, retry, change relay URL |
| ERR-02 | FCM unavailable (GMS-less / degoogled ROM) | M-Modal | First-launch detection that Play Services not available | Explain the LAN-only + Dozed limitation, recommend Always-Connected mode |
| ERR-03 | Key generation failed | M-Modal | Rare Keystore provisioning failure | Retry, contact support |
| ERR-04 | Pair token expired | M-Modal | Used on PAIR-03 timer expiry | Go back to PAIR-01 |
| ERR-05 | Fingerprint mismatch | M-Modal | PAIR-07 / PAIR-08 user said fingerprints didn't match | Destructive — pairing aborted, start over |
| ERR-06 | Replay detected | M-Toast | Internal debug surface (advanced mode only) | |
| ERR-07 | Storage full | M-Modal | History DB / DataStore full | Clear history CTA |
| ERR-08 | Untrusted build (tampered denylist) | M-Modal | See FIL-03 | |

---

## 9 · Notifications from Phone-Sync itself (not mirrors)

| ID | Name | Surface | Purpose |
|---|---|---|---|
| NOT-01 | Foreground service persistent | M-Notif | FGS lifecycle (see MIR-05) |
| NOT-02 | Permission revoked alert | M-Notif | If NLS grant removed externally |
| NOT-03 | Pair request received | M-Notif | When Device A pushes confirmation_sig to Device B over WS (Phase 3+) |
| NOT-04 | Mirror system error | M-Notif | Non-blocking background error surfaced to user |

---

## 10 · Desktop (Tauri) — Phase 5+

Receiver-only in v1. Runs as tray app + on-demand window.

### 10.a Tray / Menu-bar

| ID | Name | Surface | Purpose |
|---|---|---|---|
| TRY-01 | Tray icon (idle) | D-Tray | Greyscale logo — not paired OR no peer online |
| TRY-02 | Tray icon (connected) | D-Tray | Colored logo — peer online, mirror active |
| TRY-03 | Tray menu | D-Tray | `Peer: <name>`, `Mirroring: On/Off`, `Open window`, `Settings`, `Quit` |
| TRY-04 | Recent activity peek | D-Tray | Last 5 mirrored notifications inline in menu |

### 10.b Windows

| ID | Name | Surface | Purpose |
|---|---|---|---|
| DSK-01 | Welcome / first launch | D-Window | Desktop analogue of ON-01/04 — enter relay URL, scan/paste QR |
| DSK-02 | Pair scan | D-Window | Camera scan OR paste-in QR (if no camera), fingerprint confirmation UI |
| DSK-03 | Home | D-Window | Connection status card, peer chip, recent activity, settings nav — equivalent of HOM-01 |
| DSK-04 | History | D-Window | Same as HIS-01 |
| DSK-05 | Settings | D-Window | Relay URL, launch at login, show in dock, notification styles, battery-aware pause on lock |
| DSK-06 | Reply composer | D-Window | For platforms without native inline reply (Linux + Windows v1) — small modal window |

### 10.c OS Notifications from Desktop

| ID | Name | Surface | Purpose |
|---|---|---|---|
| DN-01 | macOS mirrored notification | D-Toast | `UNUserNotificationCenter` native rendering |
| DN-02 | Windows mirrored notification | D-Toast | Toast via `winrt::Windows::UI::Notifications` direct shim (reliable dismiss callback) |
| DN-03 | Linux mirrored notification | D-Toast | `libnotify` / `org.freedesktop.Notifications` |
| DN-04 | Desktop reply fallback window | D-Modal | When OS doesn't support inline reply |

---

## 11 · Empty / Zero states (call out for designer)

| ID | Context | Message / illustration |
|---|---|---|
| EMP-01 | Home, no pair | "No peer paired yet — set up another device to start mirroring" + CTA |
| EMP-02 | History, no events | "Nothing mirrored yet" |
| EMP-03 | App filter, search no match | "No apps match '<query>'" |
| EMP-04 | Reliability audit, all good | "All clear — nothing to fix" (green) |
| EMP-05 | Desktop tray, not paired | Match EMP-01 |

---

## 12 · Accessibility + Internationalization notes for designer

- Text-scalable typography (no fixed heights on text containers).
- 48 dp minimum tap target (Android Material).
- Fingerprint display MUST remain monospaced + group-separable even in small font sizes.
- Color should not be the sole indicator for connection state (pair with icon + text).
- Right-to-left support from day one (Arabic/Hebrew layouts mirror).
- Screen-reader labels for QR code, status dot, peer chip.
- Animations respect "Reduce motion" setting.

---

## 13 · Screen count summary

- **Onboarding:** 8
- **Pairing:** 13
- **Home/Status:** 7
- **Mirror UI (notifications):** 5
- **History:** 3
- **App filter:** 5
- **Settings:** 9
- **Error:** 8
- **Self-notifications:** 4
- **Desktop tray:** 4
- **Desktop windows:** 6
- **Desktop OS notifications:** 4
- **Empty states:** 5
- **Global primitives:** 14

**Total distinct surfaces to design: ~95** (many share tokens & components).

---

## 14 · Priority tiers for designer

### Tier 1 — required to ship Phase 3 functional UI (first real product)

Primitives (P-01–P-14), ON-01 to ON-07, PAIR-01 to PAIR-13, HOM-01 to HOM-04, MIR-01, MIR-05, FIL-01, FIL-02, SET-01 to SET-05, SET-08, ERR-01 to ERR-05, NOT-01, NOT-02, EMP-01, EMP-02.

### Tier 2 — Phase 4 polish

MIR-02, MIR-03, MIR-04, HIS-01 to HIS-03, SET-06, SET-07, SET-09, HOM-05 to HOM-07, FIL-03 to FIL-05, ERR-06 to ERR-08, NOT-03, NOT-04, EMP-03 to EMP-05.

### Tier 3 — Phase 5+ (desktop)

All TRY-*, DSK-*, DN-* surfaces.

---

## 15 · What I need back from the designer

1. Design tokens: colors (light + dark), typography scale, spacing scale, radii, shadows, motion.
2. Primitive components rendered (P-01 to P-14).
3. One reference "hero" screen polished to the final fidelity (I suggest HOM-01 or PAIR-03) so we can see the visual language.
4. All Tier 1 screens at medium-fidelity (Figma frames or PNG exports).
5. Component naming that matches our code conventions (camelCase for exports).
6. Redlines only where the layout is non-obvious.
7. App icon + notification channel icons as SVG + all Android densities.

Platform deliverables (if the designer can produce them):
- Figma file or shared link.
- Exported assets: SVG for vector, PNG @1×/2×/3× for raster.
- A component-tokens JSON if using a design system (shadcn / NativeWind / Tamagui format).

When you have them, drop them into `docs/design/assets/` and I'll wire them up during Phase 3.
