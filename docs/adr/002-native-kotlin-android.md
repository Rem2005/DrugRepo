# ADR 002: Native Kotlin Android Implementation

## Status
Accepted

## Context
Developing in cross-platform frameworks like Flutter or React Native accelerates UI development. However, this application is fundamentally a scientific instrument and forensic tool, requiring low-level access to the camera sensor and the hardware-isolated Secure Element. Abstraction layers over the camera and Keystore would hide exactly the capability flags and failure modes that this app must detect and record.

## Decision
We will build the application using native Android (Kotlin) combined with the C++ Java Native Interface (JNI).

## Consequences
### Benefits
* Direct access to the Camera2 API allows us to request uncompressed YUV_420_888 arrays and manually lock Auto-White-Balance (AWB) and Auto-Exposure (AE) where device capabilities permit.
* Efficient memory handling for passing large frame buffers to OpenCV via JNI, avoiding cross-platform bridge latency.
* Direct API access to Android Keystore and the specific X.509 ASN.1 OID extension (1.3.6.1.4.1.11129.2.1.17) required for hardware attestation.

### Trade-offs
* Slower UI development time.
* Zero code portability to iOS.
* Camera2 behavior varies across vendors, so capability detection and per-device testing are required.

## Related Documents
* docs/architecture.md
