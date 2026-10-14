# Features, design and safety boundaries

[中文版](FEATURES.zh-CN.md)

## Architecture
An Android Activity runs the local ML Kit Text Recognition v2 (Latin) model. An explicitly authorized Shizuku UserService (UID 2000/0) starts the packaged, SHA-256-pinned `/system/bin/sh` script, captures the foreground ServiceMode display to a temporary screenshot, and forwards a file descriptor through a private Binder interface for recognition. A short-lived foreground service provides an optional pass-through progress overlay. No arbitrary external shell command API is exposed.

## Six actions
1. `UE SETTING & INFO → SETTING → SYSTEM → NEXT PAGE × 4 → SET LTE BAND CONFIG → SET DEFAULT LTE BAND`.
2. `... → PROTOCOL → NR5G → PRUNE STATUS → REMOVE NR5G PRUNE`.
3. `... → PROTOCOL → GCF → REMOVE ONLY NR WHITELIST`.
4. `... → PROTOCOL → NR5G → SUB6/MMWAVE → ALL ENABLE` (**full only**).
5. `... → PROTOCOL → NR5G → SA/NSA MODE CONTROL → SA/NSA ENABLE`.
6. `... → PROTOCOL → NR5G → SA/NSA MODE CONTROL → REMOVE SA PERMIT LIST` (**not skipped when #5 is selected**).

## OCR safety
Input coordinates come from actual screen text bounding boxes, not fixed positions. The parser strips numbering, accepts only a bounded set of documented OCR aliases and requires menu/page signatures. If a selected marker `(*)` becomes uncertain `()`, the write is blocked. The UI state machine retries only a bounded number of times, never uses the Android Back key, never clicks ambiguous choices, and verifies ServiceMode foreground before actions.

## Limitations
- `uiautomator` is not used; screenshots may still be blocked by platform safeguards.
- It cannot read protected modem/NV details, turn unprovisioned 5G services on, bypass region/carrier restrictions, or guarantee any spectrum band.
- Full restoration may alter mmWave selections affecting both stacks; only use with explicit consent.
- Only one SIM and specific US U/U1 variants are allowlisted; changes across Android/One UI versions can invalidate the flow.
- Auto-return is best effort because Android 15+ imposes background-activity launch restrictions; use the foreground notification fallback.
- Root-free Shizuku usually needs manual restart after a device reboot.
- Proof of complete script execution is *not* proof of network reception or NV retention.

## Automated test coverage
Offline tests simulate full/core probe and apply, all six paths, alias variants (`NEXT PAGE -|`, `SUB/MMWAVE`, `UE SETTING 8& INFO`, selected markers), ambiguous/stale UI, wrong device, loss of focus, failure recovery and reboot gating. Simulator tests do not replace hardware testing.
