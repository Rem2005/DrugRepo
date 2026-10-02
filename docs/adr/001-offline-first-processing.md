# ADR 001: Offline-First Processing

## Status
Accepted

## Context
Field officers conducting narcotics seizures frequently operate in border regions, subterranean environments, or areas with highly unreliable cellular networks. A system requiring cloud connectivity to process images or generate forensic certificates would fail catastrophically in these environments, and a failure at the moment of seizure cannot be re-staged later.
Offline Searchable Log: Every test is stored on the device, works without internet, and can be searched and exported as a PDF certificate.

## Decision
The core application workflow (image capture, colorimetric processing, algorithmic decision-making, and cryptographic sealing) must occur 100% on the device. Network connectivity is strictly relegated to optional, deferred synchronization via Android WorkManager after the evidentiary record is securely locked in the local SQLite ledger. Sync may upload records and acknowledge the latest chain hash, but it must never modify or block local records.

## Consequences
### Benefits
* Guaranteed operational availability in any environment.
* Immediate generation of the Section 52A "Pre-Magistrate Inventory" data and BSA 2023 certificate regardless of network state.
* No evidence leaves the device before it has been hashed and signed.

### Trade-offs
* Requires heavier local computation (C++ matrix algebra).
* App size increases due to embedded OpenCV libraries.
* Off-device backup of the chain head depends on opportunistic sync, so deletion of the newest records on a compromised device is only detectable after a sync.

## Related Documents
* docs/PRD.md
* docs/architecture.md
