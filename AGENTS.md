# Project Instructions for OpenCode

**Project:** Digital Companion for Field Drug Testing (SIH PS ID: 26231)
**Platform:** Native Android (Kotlin / C++ JNI)
**Core Paradigm:** Offline-first, mathematically deterministic, cryptographically sealed.

## Authoritative Documentation
Your exact implementation instructions are defined in the following files:
* `docs/PRD.md` — Product requirements, legal goals (BSA 2023, NDPS Sec 52A), user workflows, and honest security limits.
* `docs/architecture.md` — The technical blueprint: lock-in for native Kotlin, Room (SQLite), OpenCV, Android Keystore hardware fallbacks, the full CIEDE2000 formula, and the normative hash-chain specification.
* `docs/TODO.md` — The step-by-step implementation roadmap, with tests per phase. Do not jump ahead.
* `docs/GLOSSARY.md` — Mandatory terminology. Use these terms in code, comments, UI strings, and the PDF certificate.
* `docs/adr/` — Architectural Decision Records explaining why we chose this stack.

## Strict Directives
1. **Follow the Architecture:** Do not introduce cross-platform frameworks (e.g., Flutter, React Native), do not introduce cloud APIs for the core workflow, and do not replace the local SQLite hash chain.
2. **Preserve Offline-First:** The core workflow (capture, analyze, seal, store) MUST work offline. Network sync via WorkManager is strictly an OPTIONAL, deferred functionality and must never block or alter the local chain.
3. **No Hallucinated Science:** Do not invent colorimetric thresholds, drug-identification claims, or reference data. Use mock values only for UI/pipeline testing, label them `MOCK` in code, UI, and PDF, and store a `reference_data_version` in every record.
4. **Hardware & Camera Assumptions:** Do not assume all devices support StrongBox or locked Camera2 controls, and do not assume IMEI is readable (Android 10+ blocks it for normal apps). Implement capability detection and graceful fallbacks as defined in the architecture, and record what was actually achieved in the auditable record.
5. **Make Small Changes:** Follow the isolated task roadmap in TODO.md. Write tests for each atomic slice and get them passing before moving to the next.
6. **Determinism:** Hashing must follow the canonicalization spec in architecture.md §6. CIEDE2000 must pass the published reference test pairs (TODO Phase 4). The same input bytes must always produce the same output bytes.
7. **Honest Claims:** All results are presumptive only. Never write UI text, comments, or certificate wording that claims definitive chemical identification or guaranteed legal admissibility.
8. **Stop and Ask:** If requirements conflict or a necessary dependency is missing, stop and prompt the user. Do not guess.
