# ADR 003: On-Device Computer Vision and Analysis

## Status
Accepted

## Context
Standard hackathon implementations rely on cloud APIs to analyze images. This introduces network latency, violates the offline-first mandate, and breaks the chain of custody if raw evidence is transmitted over volatile networks before being cryptographically signed. It also makes results dependent on a remote model that can change between runs, which undermines reproducibility under cross-examination.

## Decision
All computer vision and mathematical decision logic will be executed locally on the device using an embedded OpenCV (C++) pipeline with a versioned algorithm (`algorithm_version`) and versioned reference data (`reference_data_version`) stored in each record.

## Consequences
### Benefits
* Consistent with the order-of-volatility principle in NIST SP 800-86: data is analyzed and hashed in active memory before it reaches persistent disk.
* Zero dependency on external server uptime.
* Deterministic, re-runnable analysis: a stored raw image hash plus algorithm version lets results be reproduced.

### Trade-offs
* Significant complexity in building and linking C++ libraries via CMake for Android.
* Heavy matrix algebra must run off the UI thread so the viewfinder does not freeze.
* Floating-point results can differ slightly across CPU architectures, so stored values use fixed-precision decimal strings.

## Related Documents
* docs/architecture.md
* docs/TODO.md
