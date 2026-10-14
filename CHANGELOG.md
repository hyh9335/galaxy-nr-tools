
### v0.10.0-beta.1 — Three-stage ServiceMode launch (experimental)

- Tries exported ServiceMode activity and explicit Samsung secret-code broadcast, verifies the actual main menu, and caches known permission-denied entry points for the current run.
- Falls back to Samsung Dialer screenshots and on-device OCR: validates a complete dialpad grid, taps `*`, `#`, and all digits, and checks the entered code prefix before the final `#`. No `KEYCODE_POUND` events or fixed coordinates.
- If keypad layout cannot be verified or the phone number does not match, aborts rather than tapping an uncertain target. Does not automatically retry final `#`.
- Keeps the six existing ServiceMode menu operations and the independent `REMOVE SA PERMIT LIST` operation unchanged.
- **Hardware status:** Tested with mocks and CI only; direct activity / broadcast are normally permission-restricted on unrooted firmware. Verify probe mode on S23 Ultra and S25 Ultra. Do not use restore until successful probe.
- Upgrade note: this prerelease may be signed with a different GitHub-hosted debug key, requiring an uninstall/reinstall of the previous beta.

# Changelog

## 0.9.0-beta.1 — 2026-10-14
- Renamed to **Galaxy NR Tools** with original adaptive/themed icon.
- Native light/dark styling follows Android's system theme; Chinese UI for `zh` and English fallback.
- Best-effort return to the app after probe/apply completion when ServiceMode is foreground, with a notification fallback.
- Expanded guarded US U/U1 model allowlist from Galaxy S23 through S26 families (experimental outside S25 Ultra).
- Open-source repository documentation in English and Chinese, privacy/licensing and reproducible CI workflow.
- Preserves original six-menu offline-OCR state machine, fail-closed behavior and distinct SA/NSA and SA permit list operations.

## 0.8.0 — 2026-10-14
- OCR-only navigation and page-aware menu aliases.
- Dark card UI and first icon.

## 0.7.0 — 2026-10-14
- Separate SA/NSA enable and removal of the SA permit list.
