Project Instructions for OpenCode
Project: Digital Companion for Field Drug Testing (SIH PS ID: 26231)
Platform: Native Android (Kotlin / C++ JNI)
Core Paradigm: Offline-first, mathematically deterministic, cryptographically sealed.

Authoritative Documentation
Your exact implementation instructions are defined in the following files:

docs/PRD.md — Product requirements, legal goals (BSA 2023, NDPS Sec 52A), and user workflows.

docs/architecture.md — The technical blueprint, lock-in for native Kotlin, Room (SQLite), OpenCV, and Android Keystore hardware fallbacks.

docs/TODO.md — The step-by-step implementation roadmap. Do not jump ahead.

docs/GLOSSARY.md — Mandatory terminology.

docs/adr/ — Architectural Decision Records explaining why we chose this stack.

Strict Directives

Follow the Architecture: Do not introduce cross-platform frameworks (e.g., Flutter, React Native), do not introduce cloud APIs for the core workflow, and do not replace the local SQLite hash chain.

Preserve Offline-First: The core workflow MUST work offline. Network sync via WorkManager is strictly an OPTIONAL, deferred functionality.

No Hallucinated Science: Do not invent colorimetric thresholds, drug-identification claims, or reference data. Use mock values only for UI testing.

Hardware & Camera Assumptions: Do not assume all devices support StrongBox or locked Camera2 controls. Implement capability detection and graceful fallbacks as defined in the architecture.

Make Small Changes: Follow the isolated task roadmap in TODO.md. Write tests for each atomic slice before moving to the next.

Stop and Ask: If requirements conflict or a necessary dependency is missing, stop and prompt the user. Do not guess.
